package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.ModItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Master support-task controller for {@link SupportEntity}. Prioritizes:
 * <ol>
 *   <li>Revive bleeding players</li>
 *   <li>Heal wounded soldiers</li>
 *   <li>Distribute ammunition to low-ammo soldiers</li>
 *   <li>Distribute medical supplies to soldiers without healing items</li>
 * </ol>
 */
public final class SupportTaskGoal extends Goal {
    private static final double TASK_RADIUS = 32.0;
    private static final double ARRIVE_DISTANCE = 3.0;
    private static final double MOVE_SPEED = 1.0;
    private static final float LOW_HEALTH_THRESHOLD = 0.50F;
    private static final int RESUPPLY_COOLDOWN_TICKS = 200;
    private static final int HEAL_COOLDOWN_TICKS = 100;
    private static final int LOW_AMMO_THRESHOLD = 20;
    private static final long CLAIM_TIMEOUT_MS = 15000;

    private static final int MAX_TASK_TICKS = 600;

    private final SupportEntity soldier;
    private SupportTask currentTask = SupportTask.IDLE;
    private LivingEntity currentTarget;
    private int taskCooldownTicks;
    private int pathRecalcTicks;
    private int reviveProgressAccum;
    private int ticksSinceTaskStart;

    private record ClaimEntry(UUID claimerId, long timestamp) {}
    private static final Map<UUID, ClaimEntry> CLAIMS = new HashMap<>();

    private enum SupportTask {
        IDLE, REVIVING, HEALING, RESUPPLY_AMMO, RESUPPLY_MEDICAL
    }

    public SupportTaskGoal(SupportEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive() || soldier.level().isClientSide) return false;
        if (soldier.isHealing() || soldier.isUsingItem()) return false;
        if (soldier.isPreparingOrReloading()) return false;
        if (soldier.isRecalling()) return false;
        if (taskCooldownTicks > 0) {
            taskCooldownTicks--;
            return false;
        }
        if (currentTask != SupportTask.IDLE) return false;
        return findNewTask();
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive() || soldier.level().isClientSide) return false;
        if (soldier.isHealing() || soldier.isUsingItem()) return false;
        if (soldier.isPreparingOrReloading()) return false;
        if (soldier.isRecalling()) return false;
        return switch (currentTask) {
            case REVIVING -> currentTarget instanceof Player player
                && PlayerReviveCompat.isLoaded() && PlayerReviveCompat.isPlayerBleeding(player);
            case HEALING -> currentTarget instanceof SoldierEntity s
                && s.isAlive() && s.getHealth() / s.getMaxHealth() < LOW_HEALTH_THRESHOLD;
            case RESUPPLY_AMMO, RESUPPLY_MEDICAL -> currentTarget instanceof SoldierEntity s
                && s.isAlive();
            default -> false;
        };
    }

    @Override
    public void start() {
        pathRecalcTicks = 0;
        reviveProgressAccum = 0;
        ticksSinceTaskStart = 0;
        navigateToTarget();
    }

    @Override
    public void stop() {
        currentTask = SupportTask.IDLE;
        currentTarget = null;
        reviveProgressAccum = 0;
        ticksSinceTaskStart = 0;
    }

    @Override
    public void tick() {
        ticksSinceTaskStart++;
        if (ticksSinceTaskStart > MAX_TASK_TICKS) {
            stop();
            taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
            return;
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            stop();
            return;
        }

        double dist = soldier.distanceTo(currentTarget);
        if (dist > ARRIVE_DISTANCE) {
            pathRecalcTicks++;
            if (pathRecalcTicks >= 20) {
                pathRecalcTicks = 0;
                navigateToTarget();
            }
            return;
        }

        soldier.getNavigation().stop();
        soldier.getLookControl().setLookAt(
            currentTarget.getX(), currentTarget.getEyeY(), currentTarget.getZ(),
            30.0F, 30.0F);

        switch (currentTask) {
            case REVIVING -> tickRevive();
            case HEALING -> tickHeal();
            case RESUPPLY_AMMO -> tickResupplyAmmo();
            case RESUPPLY_MEDICAL -> tickResupplyMedical();
            default -> {}
        }
    }

    private boolean findNewTask() {
        if (tryFindReviveTarget()) return true;
        if (tryFindHealTarget()) return true;
        if (tryFindResupplyTarget()) return true;
        return false;
    }

    // --- Revive ---

    private boolean tryFindReviveTarget() {
        if (!PlayerReviveCompat.isLoaded()) return false;
        List<ServerPlayer> bleeding = findBleedingPlayers();
        for (ServerPlayer player : bleeding) {
            if (!isClaimed(player)) {
                currentTask = SupportTask.REVIVING;
                currentTarget = player;
                claim(player);
                return true;
            }
        }
        return false;
    }

    private void tickRevive() {
        reviveProgressAccum++;
        if (reviveProgressAccum % 5 == 0 && currentTarget instanceof ServerPlayer player) {
            PlayerReviveCompat.addReviveProgress(player,
                PlayerReviveCompat.getProgressPerPlayer() * 5);
        }
    }

    // --- Heal ---

    private boolean tryFindHealTarget() {
        if (!hasHealingItems()) return false;
        List<SoldierEntity> wounded = findWoundedSoldiers();
        for (SoldierEntity target : wounded) {
            if (!isClaimed(target) && hasHealingItems()) {
                currentTask = SupportTask.HEALING;
                currentTarget = target;
                claim(target);
                return true;
            }
        }
        return false;
    }

    private void tickHeal() {
        if (!(currentTarget instanceof SoldierEntity target)) return;
        if (!hasHealingItems()) {
            stop();
            taskCooldownTicks = HEAL_COOLDOWN_TICKS;
            return;
        }

        ItemStack healItem = findFirstHealingItem();
        if (healItem.isEmpty()) {
            stop();
            taskCooldownTicks = HEAL_COOLDOWN_TICKS;
            return;
        }

        int healAmount = 4;
        float newHealth = Math.min(target.getMaxHealth(), target.getHealth() + healAmount);
        target.setHealth(newHealth);

        healItem.shrink(1);
        syncInventory();
        stop();
        taskCooldownTicks = HEAL_COOLDOWN_TICKS;
    }

    // --- Resupply ---

    private boolean tryFindResupplyTarget() {
        List<SoldierEntity> needResupply = findSoldiersNeedingResupply();
        for (SoldierEntity target : needResupply) {
            if (!isClaimed(target)) {
                if (needsAmmo(target) && hasAmmo()) {
                    currentTask = SupportTask.RESUPPLY_AMMO;
                    currentTarget = target;
                    claim(target);
                    return true;
                }
                if (needsMedical(target) && hasHealingItems()) {
                    currentTask = SupportTask.RESUPPLY_MEDICAL;
                    currentTarget = target;
                    claim(target);
                    return true;
                }
            }
        }
        return false;
    }

    private void tickResupplyAmmo() {
        if (!(currentTarget instanceof SoldierEntity target)) return;
        if (!transferCompatibleAmmo(target)) {
            stop();
            taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
            return;
        }
        stop();
        taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
    }

    private void tickResupplyMedical() {
        if (!(currentTarget instanceof SoldierEntity target)) return;
        if (!hasHealingItems()) {
            stop();
            taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
            return;
        }
        ItemStack item = findFirstHealingItem();
        if (item.isEmpty()) {
            stop();
            taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
            return;
        }
        ItemStack toInsert = item.copy();
        toInsert.setCount(1);
        if (insertItem(target.getSoldierInventory(), toInsert)) {
            item.shrink(1);
            syncInventory();
            NetworkHandler.sendToTracking(target,
                new SyncSoldierInventoryPacket(target.getId(), target.getSoldierInventory().save()));
        }
        stop();
        taskCooldownTicks = RESUPPLY_COOLDOWN_TICKS;
    }

    // --- Helpers ---

    private List<ServerPlayer> findBleedingPlayers() {
        if (!PlayerReviveCompat.isLoaded()) return List.of();
        ServerLevel level = (ServerLevel) soldier.level();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && PlayerReviveCompat.isPlayerBleeding(player)
                && box.contains(player.position())) {
                result.add(player);
            }
        }
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private List<SoldierEntity> findWoundedSoldiers() {
        ServerLevel level = (ServerLevel) soldier.level();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<SoldierEntity> result = level.getEntitiesOfClass(SoldierEntity.class, box,
            s -> s.isAlive() && s != soldier
                && s.getHealth() / s.getMaxHealth() < LOW_HEALTH_THRESHOLD
                && soldier.isFriendlyTo(s));
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private List<SoldierEntity> findSoldiersNeedingResupply() {
        ServerLevel level = (ServerLevel) soldier.level();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<SoldierEntity> result = level.getEntitiesOfClass(SoldierEntity.class, box,
            s -> s.isAlive() && s != soldier && soldier.isFriendlyTo(s)
                && (needsAmmo(s) || needsMedical(s)));
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private boolean needsAmmo(SoldierEntity target) {
        int totalAmmo = countTotalAmmo(target);
        return totalAmmo < LOW_AMMO_THRESHOLD;
    }

    private boolean needsMedical(SoldierEntity target) {
        return !hasHealingItem(target);
    }

    private boolean hasHealingItem(SoldierEntity target) {
        SoldierInventory inv = target.getSoldierInventory();
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(ModItemTags.SOLDIER_HEALING_ITEMS)) return true;
        }
        return false;
    }

    private boolean hasHealingItems() {
        return !findFirstHealingItem().isEmpty();
    }

    private boolean hasAmmo() {
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isAmmo(stack)) return true;
        }
        return false;
    }

    private ItemStack findFirstHealingItem() {
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ModItemTags.SOLDIER_HEALING_ITEMS) && stack.getCount() > 0) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private int countTotalAmmo(SoldierEntity target) {
        SoldierInventory inv = target.getSoldierInventory();
        int total = 0;
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isAmmo(stack)) total += stack.getCount();
        }
        return total;
    }

    private boolean isAmmo(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.create(
            new net.minecraft.resources.ResourceLocation("tacz", "ammo")));
    }

    private boolean transferCompatibleAmmo(SoldierEntity target) {
        SoldierInventory srcInv = soldier.getSoldierInventory();
        SoldierInventory dstInv = target.getSoldierInventory();
        boolean transferred = false;

        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack srcStack = srcInv.getItem(i);
            if (srcStack.isEmpty() || !isAmmo(srcStack)) continue;

            int toTransfer = Math.min(srcStack.getCount(), 16);
            if (toTransfer <= 0) continue;

            ItemStack splitStack = srcStack.split(toTransfer);
            if (!insertItem(dstInv, splitStack)) {
                srcStack.grow(splitStack.getCount());
                continue;
            }
            transferred = true;
            if (countTotalAmmo(target) >= LOW_AMMO_THRESHOLD) break;
        }

        if (transferred) {
            syncInventory();
            NetworkHandler.sendToTracking(target,
                new SyncSoldierInventoryPacket(target.getId(), dstInv.save()));
        }
        return transferred;
    }

    private boolean insertItem(SoldierInventory targetInv, ItemStack stack) {
        if (stack.isEmpty()) return true;

        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack slot = targetInv.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, stack)
                && slot.getCount() < slot.getMaxStackSize()) {
                int canAdd = slot.getMaxStackSize() - slot.getCount();
                int toAdd = Math.min(stack.getCount(), canAdd);
                if (toAdd > 0) {
                    slot.grow(toAdd);
                    stack.shrink(toAdd);
                    if (stack.isEmpty()) return true;
                }
            }
        }

        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            if (targetInv.getItem(i).isEmpty()) {
                targetInv.setItem(i, stack);
                return true;
            }
        }
        return false;
    }

    private void navigateToTarget() {
        if (currentTarget == null) return;
        soldier.getNavigation().moveTo(
            currentTarget.getX(), currentTarget.getY(), currentTarget.getZ(), MOVE_SPEED);
    }

    private void syncInventory() {
        NetworkHandler.sendToTracking(soldier,
            new SyncSoldierInventoryPacket(soldier.getId(), soldier.getSoldierInventory().save()));
    }

    private boolean isClaimed(LivingEntity target) {
        ClaimEntry entry = CLAIMS.get(target.getUUID());
        if (entry == null) return false;
        if (entry.claimerId().equals(soldier.getUUID())) return true;
        if (System.currentTimeMillis() - entry.timestamp() > CLAIM_TIMEOUT_MS) {
            CLAIMS.remove(target.getUUID());
            return false;
        }
        return true;
    }

    private void claim(LivingEntity target) {
        CLAIMS.put(target.getUUID(), new ClaimEntry(soldier.getUUID(), System.currentTimeMillis()));
    }

    public static void clearClaim(LivingEntity target) {
        CLAIMS.remove(target.getUUID());
    }
}

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side support work used only by {@link SupportCoverGoal}.
 *
 * This is deliberately not a Goal. The support cover wrapper remains the
 * support's single movement owner, so duty work cannot compete with cover or
 * leave a task claimed when GoalSelector rejects a goal start.
 */
public final class SupportDutyController {
    private static final double TASK_RADIUS = 32.0;
    private static final double ARRIVE_DISTANCE = 3.0;
    private static final double MOVE_SPEED = 1.0;
    private static final float LOW_HEALTH_THRESHOLD = 0.50F;
    private static final int RESUPPLY_COOLDOWN_TICKS = 200;
    private static final int HEAL_COOLDOWN_TICKS = 100;
    private static final int LOW_AMMO_THRESHOLD = 20;
    private static final long CLAIM_TIMEOUT_MS = 15000L;
    private static final int MAX_DUTY_TICKS = 600;
    private static final int SEARCH_RETRY_TICKS = 20;

    private final SupportEntity soldier;
    private SupportTask currentTask = SupportTask.IDLE;
    private LivingEntity currentTarget;
    private int taskCooldownTicks;
    private int searchCooldownTicks;
    private int pathRecalcTicks;
    private int reviveProgressTicks;
    private int dutyTicks;

    private record ClaimEntry(UUID claimerId, long timestamp) {}
    private record TaskSelection(SupportTask task, LivingEntity target) {}

    private static final Map<UUID, ClaimEntry> CLAIMS = new HashMap<>();

    private enum SupportTask {
        IDLE,
        REVIVING,
        HEALING,
        RESUPPLY_AMMO,
        RESUPPLY_MEDICAL
    }

    public enum DutyResult {
        RUNNING,
        COMPLETE,
        ABORTED
    }

    public SupportDutyController(SupportEntity soldier) {
        this.soldier = soldier;
    }

    /** Finds and claims one task after the cover wrapper has committed to duty. */
    public boolean tryStartDuty() {
        if (isActive() || !canWork()) {
            return false;
        }
        if (taskCooldownTicks > 0) {
            taskCooldownTicks--;
            return false;
        }
        if (searchCooldownTicks > 0) {
            searchCooldownTicks--;
            return false;
        }

        TaskSelection selection = findTask();
        if (selection == null) {
            searchCooldownTicks = SEARCH_RETRY_TICKS;
            return false;
        }

        currentTask = selection.task();
        currentTarget = selection.target();
        claim(currentTarget);
        return true;
    }

    public boolean isActive() {
        return currentTask != SupportTask.IDLE && currentTarget != null;
    }

    public void start() {
        pathRecalcTicks = 0;
        reviveProgressTicks = 0;
        dutyTicks = 0;
        navigateToTarget();
    }

    public DutyResult tick() {
        if (!isActive()) {
            return DutyResult.ABORTED;
        }
        if (!soldier.isAlive() || currentTarget == null || !currentTarget.isAlive()
            || soldier.isPreparingOrReloading() || soldier.isRecalling()
            || soldier.isHealing() || soldier.isUsingItem()) {
            finish(0);
            return DutyResult.ABORTED;
        }
        if (++dutyTicks > MAX_DUTY_TICKS) {
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        double distance = soldier.distanceTo(currentTarget);
        if (distance > ARRIVE_DISTANCE) {
            if (++pathRecalcTicks >= 20) {
                pathRecalcTicks = 0;
                navigateToTarget();
            }
            return DutyResult.RUNNING;
        }

        soldier.getNavigation().stop();
        soldier.getLookControl().setLookAt(
            currentTarget.getX(), currentTarget.getEyeY(), currentTarget.getZ(),
            30.0F, 30.0F);

        return switch (currentTask) {
            case REVIVING -> tickRevive();
            case HEALING -> tickHeal();
            case RESUPPLY_AMMO -> tickResupplyAmmo();
            case RESUPPLY_MEDICAL -> tickResupplyMedical();
            default -> DutyResult.ABORTED;
        };
    }

    public void stop() {
        if (currentTarget != null) {
            releaseClaim(currentTarget);
        }
        currentTask = SupportTask.IDLE;
        currentTarget = null;
        reviveProgressTicks = 0;
        dutyTicks = 0;
    }

    private boolean canWork() {
        return soldier.isAlive()
            && !soldier.level().isClientSide
            && !soldier.isHealing()
            && !soldier.isUsingItem()
            && !soldier.isPreparingOrReloading()
            && !soldier.isRecalling();
    }

    private TaskSelection findTask() {
        TaskSelection revive = findReviveTask();
        if (revive != null) return revive;

        TaskSelection heal = findHealTask();
        if (heal != null) return heal;

        for (SoldierEntity target : findSoldiersNeedingResupply()) {
            if (isClaimed(target)) continue;
            if (needsAmmo(target) && hasAmmo()) {
                return new TaskSelection(SupportTask.RESUPPLY_AMMO, target);
            }
            if (needsMedical(target) && hasHealingItems()) {
                return new TaskSelection(SupportTask.RESUPPLY_MEDICAL, target);
            }
        }
        return null;
    }

    private TaskSelection findReviveTask() {
        if (!PlayerReviveCompat.isLoaded()) return null;
        for (ServerPlayer player : findBleedingPlayers()) {
            if (!isClaimed(player)) {
                return new TaskSelection(SupportTask.REVIVING, player);
            }
        }
        return null;
    }

    private TaskSelection findHealTask() {
        if (!hasHealingItems()) return null;
        for (SoldierEntity target : findWoundedSoldiers()) {
            if (!isClaimed(target)) {
                return new TaskSelection(SupportTask.HEALING, target);
            }
        }
        return null;
    }

    private DutyResult tickRevive() {
        if (!(currentTarget instanceof ServerPlayer player)
            || !PlayerReviveCompat.isLoaded()
            || !PlayerReviveCompat.isPlayerBleeding(player)) {
            finish(0);
            return DutyResult.COMPLETE;
        }

        if (++reviveProgressTicks % 5 == 0
            && !PlayerReviveCompat.addReviveProgress(player,
                PlayerReviveCompat.getProgressPerPlayer() * 5)) {
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }
        return DutyResult.RUNNING;
    }

    private DutyResult tickHeal() {
        if (!(currentTarget instanceof SoldierEntity target)
            || !target.isAlive()
            || target.getHealth() / target.getMaxHealth() >= LOW_HEALTH_THRESHOLD) {
            finish(0);
            return DutyResult.COMPLETE;
        }

        ItemStack healItem = findFirstHealingItem();
        if (healItem.isEmpty()) {
            finish(HEAL_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        target.setHealth(Math.min(target.getMaxHealth(), target.getHealth() + 4.0F));
        healItem.shrink(1);
        syncInventory();
        finish(HEAL_COOLDOWN_TICKS);
        return DutyResult.COMPLETE;
    }

    private DutyResult tickResupplyAmmo() {
        if (!(currentTarget instanceof SoldierEntity target) || !target.isAlive()) {
            finish(0);
            return DutyResult.ABORTED;
        }
        boolean transferred = transferCompatibleAmmo(target);
        finish(RESUPPLY_COOLDOWN_TICKS);
        return transferred ? DutyResult.COMPLETE : DutyResult.ABORTED;
    }

    private DutyResult tickResupplyMedical() {
        if (!(currentTarget instanceof SoldierEntity target) || !target.isAlive()) {
            finish(0);
            return DutyResult.ABORTED;
        }

        ItemStack item = findFirstHealingItem();
        if (item.isEmpty()) {
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        ItemStack toInsert = item.copy();
        toInsert.setCount(1);
        if (!insertItem(target.getSoldierInventory(), toInsert)) {
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        item.shrink(1);
        syncInventory();
        NetworkHandler.sendToTracking(target,
            new SyncSoldierInventoryPacket(target.getId(), target.getSoldierInventory().save()));
        finish(RESUPPLY_COOLDOWN_TICKS);
        return DutyResult.COMPLETE;
    }

    private List<ServerPlayer> findBleedingPlayers() {
        if (!(soldier.level() instanceof ServerLevel level)) return List.of();
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
        if (!(soldier.level() instanceof ServerLevel level)) return List.of();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<SoldierEntity> result = level.getEntitiesOfClass(SoldierEntity.class, box,
            target -> target.isAlive() && target != soldier
                && target.getHealth() / target.getMaxHealth() < LOW_HEALTH_THRESHOLD
                && soldier.isFriendlyTo(target));
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private List<SoldierEntity> findSoldiersNeedingResupply() {
        if (!(soldier.level() instanceof ServerLevel level)) return List.of();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<SoldierEntity> result = level.getEntitiesOfClass(SoldierEntity.class, box,
            target -> target.isAlive() && target != soldier && soldier.isFriendlyTo(target)
                && (needsAmmo(target) || needsMedical(target)));
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private boolean needsAmmo(SoldierEntity target) {
        return countTotalAmmo(target) < LOW_AMMO_THRESHOLD;
    }

    private boolean needsMedical(SoldierEntity target) {
        return !hasHealingItem(target);
    }

    private boolean hasHealingItem(SoldierEntity target) {
        SoldierInventory inventory = target.getSoldierInventory();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(ModItemTags.SOLDIER_HEALING_ITEMS)) return true;
        }
        return false;
    }

    private boolean hasHealingItems() {
        return !findFirstHealingItem().isEmpty();
    }

    private boolean hasAmmo() {
        SoldierInventory inventory = soldier.getSoldierInventory();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && isAmmo(stack)) return true;
        }
        return false;
    }

    private ItemStack findFirstHealingItem() {
        SoldierInventory inventory = soldier.getSoldierInventory();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(ModItemTags.SOLDIER_HEALING_ITEMS) && stack.getCount() > 0) return stack;
        }
        return ItemStack.EMPTY;
    }

    private int countTotalAmmo(SoldierEntity target) {
        SoldierInventory inventory = target.getSoldierInventory();
        int total = 0;
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && isAmmo(stack)) total += stack.getCount();
        }
        return total;
    }

    private boolean isAmmo(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.create(
            new net.minecraft.resources.ResourceLocation("tacz", "ammo")));
    }

    private boolean transferCompatibleAmmo(SoldierEntity target) {
        SoldierInventory source = soldier.getSoldierInventory();
        SoldierInventory destination = target.getSoldierInventory();
        boolean transferred = false;

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack sourceStack = source.getItem(slot);
            if (sourceStack.isEmpty() || !isAmmo(sourceStack)) continue;

            int amount = Math.min(sourceStack.getCount(), 16);
            ItemStack splitStack = sourceStack.split(amount);
            if (!insertItem(destination, splitStack)) {
                sourceStack.grow(splitStack.getCount());
            }
            transferred |= splitStack.getCount() < amount;
            if (countTotalAmmo(target) >= LOW_AMMO_THRESHOLD) break;
        }

        if (transferred) {
            syncInventory();
            NetworkHandler.sendToTracking(target,
                new SyncSoldierInventoryPacket(target.getId(), destination.save()));
        }
        return transferred;
    }

    private boolean insertItem(SoldierInventory inventory, ItemStack stack) {
        if (stack.isEmpty()) return true;

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)
                && existing.getCount() < existing.getMaxStackSize()) {
                int amount = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(amount);
                stack.shrink(amount);
                if (stack.isEmpty()) return true;
            }
        }

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack);
                return true;
            }
        }
        return false;
    }

    private void navigateToTarget() {
        if (currentTarget != null) {
            soldier.getNavigation().moveTo(
                currentTarget.getX(), currentTarget.getY(), currentTarget.getZ(), MOVE_SPEED);
        }
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

    private void releaseClaim(LivingEntity target) {
        ClaimEntry entry = CLAIMS.get(target.getUUID());
        if (entry != null && entry.claimerId().equals(soldier.getUUID())) {
            CLAIMS.remove(target.getUUID());
        }
    }

    private void finish(int cooldown) {
        if (currentTarget != null) {
            releaseClaim(currentTarget);
        }
        currentTask = SupportTask.IDLE;
        currentTarget = null;
        taskCooldownTicks = cooldown;
        reviveProgressTicks = 0;
        dutyTicks = 0;
    }
}

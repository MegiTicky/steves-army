package com.stevesarmy.entity;

import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thrown resupply pouch that homes toward a target and delivers ammo/healing
 * items on proximity. Ignores block collisions to guarantee delivery.
 * Falls back to "magic give" if the pouch expires before reaching its target.
 */
public class ResupplyPouchEntity extends Entity {
    private static final double HOMING_STRENGTH = 0.35;
    private static final int MAX_LIFETIME_TICKS = 80;
    private static final double DELIVERY_DISTANCE = 1.5;
    private static final double MAX_SPEED = 1.6;

    private final List<ItemStack> payload = new ArrayList<>();
    private int targetId = -1;
    private UUID targetUuid;

    public ResupplyPouchEntity(EntityType<? extends ResupplyPouchEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static ResupplyPouchEntity forTarget(ServerLevel level, Vec3 pos, LivingEntity target, List<ItemStack> payload) {
        ResupplyPouchEntity pouch = new ResupplyPouchEntity(ModEntities.RESUPPLY_POUCH.get(), level);
        pouch.setPos(pos);
        pouch.targetId = target.getId();
        pouch.targetUuid = target.getUUID();
        pouch.payload.addAll(payload);
        return pouch;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public void tick() {
        super.tick();
        if (tickCount > MAX_LIFETIME_TICKS) {
            deliver(false);
            return;
        }
        if (level().isClientSide()) return;

        LivingEntity target = findTarget();
        if (target == null || !target.isAlive()) {
            deliver(false);
            return;
        }

        Vec3 from = position().add(0, getBbHeight() / 2, 0);
        Vec3 to = target.getEyePosition();
        Vec3 delta = to.subtract(from);
        double dist = delta.length();

        Vec3 current = getDeltaMovement();
        Vec3 desired = delta.normalize().scale(Math.min(MAX_SPEED, 0.4 + dist * 0.05));
        Vec3 homed = current.lerp(desired, HOMING_STRENGTH);
        setDeltaMovement(homed);
        move(MoverType.SELF, homed);

        Vec3 afterPos = position().add(0, getBbHeight() / 2, 0);
        double distAfter = afterPos.distanceTo(target.getEyePosition());
        if (distAfter < DELIVERY_DISTANCE) {
            deliver(true);
        }
    }

    @Nullable
    private LivingEntity findTarget() {
        if (level().isClientSide()) return null;

        if (targetId != -1) {
            Entity e = level().getEntity(targetId);
            if (e instanceof LivingEntity le) return le;
        }

        if (targetUuid != null) {
            if (level() instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.getUUID().equals(targetUuid)) return player;
                }
            }
            List<LivingEntity> candidates = level().getEntitiesOfClass(LivingEntity.class,
                getBoundingBox().inflate(64), e -> e.getUUID().equals(targetUuid));
            return candidates.isEmpty() ? null : candidates.get(0);
        }
        return null;
    }

    private void deliver(boolean reached) {
        if (!level().isClientSide()) {
            LivingEntity target = findTarget();
            if (target != null && target.isAlive()) {
                giveToTarget(target);
            } else {
                dropContents();
            }
        }
        discard();
    }

    private void giveToTarget(LivingEntity target) {
        if (target instanceof SoldierEntity soldier) {
            SoldierInventory inv = soldier.getSoldierInventory();
            for (ItemStack stack : payload) {
                ItemStack leftover = insertIntoSoldierInventory(inv, stack);
                if (!leftover.isEmpty()) dropItem(leftover);
            }
            NetworkHandler.sendToTracking(soldier,
                new SyncSoldierInventoryPacket(soldier.getId(), inv.save()));
        } else if (target instanceof Player player) {
            for (ItemStack stack : payload) {
                ItemStack toGive = stack.copy();
                player.getInventory().add(toGive);
                if (!toGive.isEmpty()) {
                    dropItem(toGive);
                }
            }
        } else {
            dropContents();
        }
    }

    private ItemStack insertIntoSoldierInventory(SoldierInventory inv, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)
                && existing.getCount() < existing.getMaxStackSize()) {
                int amount = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(amount);
                remaining.shrink(amount);
                if (remaining.isEmpty()) return ItemStack.EMPTY;
            }
        }

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (inv.getItem(slot).isEmpty()) {
                inv.setItem(slot, remaining);
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    private void dropContents() {
        for (ItemStack stack : payload) {
            dropItem(stack);
        }
    }

    private void dropItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        net.minecraft.world.entity.item.ItemEntity itemEntity =
            new net.minecraft.world.entity.item.ItemEntity(level(), getX(), getY(), getZ(), stack);
        itemEntity.setDeltaMovement(getDeltaMovement().scale(0.5));
        level().addFreshEntity(itemEntity);
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Target")) {
            targetUuid = tag.getUUID("Target");
        }
        targetId = tag.getInt("TargetId");
        ListTag list = tag.getList("Payload", Tag.TAG_COMPOUND);
        payload.clear();
        for (int i = 0; i < list.size(); i++) {
            payload.add(ItemStack.of(list.getCompound(i)));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (targetUuid != null) tag.putUUID("Target", targetUuid);
        tag.putInt("TargetId", targetId);
        ListTag list = new ListTag();
        for (ItemStack stack : payload) {
            list.add(stack.save(new CompoundTag()));
        }
        tag.put("Payload", list);
    }
}

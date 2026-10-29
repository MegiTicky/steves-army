package com.stevesarmy.entity;

import com.stevesarmy.inventory.PouchContainer;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Player-thrown pouches use physical collisions. Support throws are cosmetic;
 * their contents are managed independently by SupportDeliveryQueue.
 */
public class SupplyPouchProjectile extends ThrowableItemProjectile {
    /** Homing assist constants, carried over from the retired ResupplyPouchEntity. */
    private static final double HOMING_STRENGTH = 0.35;
    private static final double HOMING_MAX_SPEED = 1.6;
    private static final double HOMING_MAX_RANGE = 32.0;

    private boolean delivered;
    /** Set for medic throws: gently steer toward the staged ally. */
    @Nullable
    private UUID homingTargetId;

    public SupplyPouchProjectile(EntityType<? extends SupplyPouchProjectile> type, Level level) {
        super(type, level);
    }

    public SupplyPouchProjectile(Level level, LivingEntity thrower) {
        super(ModEntities.SUPPLY_POUCH.get(), thrower, level);
    }

    /** Medic throw: the pouch homes toward the staged ally until delivered. */
    public static SupplyPouchProjectile forTarget(Level level, LivingEntity thrower, LivingEntity target) {
        SupplyPouchProjectile pouch = new SupplyPouchProjectile(level, thrower);
        pouch.setItem(new ItemStack(ModItems.SUPPLY_POUCH.get()));
        pouch.homingTargetId = target.getUUID();
        pouch.setNoGravity(true);
        return pouch;
    }

    @Override
    public void tick() {
        if (!level().isClientSide) {
            if (homingTargetId != null && tickCount >= SupportDeliveryQueue.DELAY_TICKS) {
                discard();
                return;
            }
            homeTowardTarget();
        }
        super.tick();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (homingTargetId != null) tag.putUUID("SupportVisualTarget", homingTargetId);
        tag.putInt("SupportVisualAge", tickCount);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("SupportVisualTarget")) {
            homingTargetId = tag.getUUID("SupportVisualTarget");
            tickCount = tag.getInt("SupportVisualAge");
        }
    }

    private void homeTowardTarget() {
        if (homingTargetId == null || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(serverLevel.getEntity(homingTargetId) instanceof LivingEntity target) || !target.isAlive()) {
            return; // target gone: keep flying, land as a ground pouch
        }
        Vec3 from = position().add(0, getBbHeight() / 2, 0);
        Vec3 delta = target.getEyePosition().subtract(from);
        double dist = delta.length();
        if (dist > HOMING_MAX_RANGE || dist < 0.01) {
            return;
        }
        Vec3 desired = delta.normalize().scale(Math.min(HOMING_MAX_SPEED, 0.4 + dist * 0.05));
        setDeltaMovement(getDeltaMovement().lerp(desired, HOMING_STRENGTH));
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.SUPPLY_POUCH.get();
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return (homingTargetId == null || homingTargetId.equals(entity.getUUID()))
            && super.canHitEntity(entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!level().isClientSide) {
            Entity target = result.getEntity();
            if (homingTargetId == null || (homingTargetId.equals(target.getUUID())
                && target instanceof LivingEntity living
                && com.stevesarmy.entity.ai.SupportDutyController.canAcceptPayload(
                    living, PouchContainer.unpackContents(getItem())))) {
                delivered = deliverTo(target);
            }
        }
    }

    @Override
    protected void onHit(HitResult result) {
        if (homingTargetId != null) return;
        super.onHit(result);
        if (level().isClientSide) {
            return;
        }
        if (result.getType() == HitResult.Type.ENTITY && delivered) {
            discard();
            return;
        }
        // Non-deliverable entity or block: land as a ground pouch anyone can pick up.
        Vec3 dropPos = result.getType() == HitResult.Type.BLOCK
            ? ((BlockHitResult) result).getLocation()
            : position();
        ItemEntity entity = new ItemEntity(level(), dropPos.x, dropPos.y, dropPos.z, getItem().copy());
        entity.setPickUpDelay(10);
        level().addFreshEntity(entity);
        discard();
    }

    private boolean deliverTo(Entity target) {
        return deliverContents(target, getItem());
    }

    public static boolean deliverContents(Entity target, ItemStack pouch) {
        var contents = PouchContainer.unpackContents(pouch);
        if (contents.isEmpty()) {
            // Nothing to give — report as non-delivery so the pouch lands on
            // the ground instead of vanishing against the target.
            return false;
        }
        if (target instanceof Player player) {
            for (ItemStack stack : contents) {
                ItemStack toGive = stack.copy();
                player.getInventory().add(toGive);
                if (!toGive.isEmpty()) {
                    dropBeside(toGive, player);
                }
            }
            return true;
        }
        if (target instanceof SoldierEntity soldier) {
            PouchContainer.restoreIntoSoldier(soldier, pouch);
            return true;
        }
        return false;
    }

    private static void dropBeside(ItemStack stack, Entity target) {
        ItemEntity entity = new ItemEntity(target.level(), target.getX(), target.getY() + 0.5, target.getZ(), stack);
        target.level().addFreshEntity(entity);
    }
}

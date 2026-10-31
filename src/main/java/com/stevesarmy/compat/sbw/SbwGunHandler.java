package com.stevesarmy.compat.sbw;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.FireMode;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.GunType;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.data.gun.value.ReloadState;
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType;
import com.atsuishio.superbwarfare.item.ammo.AmmoSupplierItem;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.GunIntegration.GunshotSignature;
import com.stevesarmy.combat.GunIntegration.ShootResult;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class SbwGunHandler implements GunIntegration.GunHandler {
    private static final int DRAW_TICKS = 15;
    private static final float AIM_RAMP_TICKS = 10.0f;
    private static final double DEFAULT_GUN_RANGE = 64.0;

    @Override
    public boolean hasGun(LivingEntity entity) {
        if (entity == null) return false;
        return SbwCompat.isGun(entity.getMainHandItem());
    }

    @Override
    public void tick(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        data.tick(entity, true);
        SbwEntityState.get(entity.getUUID()).tick(entity, data);
    }

    @Override
    public ShootResult shoot(LivingEntity shooter, LivingEntity target) {
        if (!hasGun(shooter)) return ShootResult.NOT_GUN;
        if (target == null || !target.isAlive()) return ShootResult.NO_TARGET;
        return shootAt(shooter, target.getEyePosition(), 0.0f, 0.0f, target);
    }

    @Override
    public ShootResult shootWithDeviation(LivingEntity shooter, ExposureCalculator.AimPointResult aimPoint, float pitchDeviation, float yawDeviation) {
        if (!hasGun(shooter)) return ShootResult.NOT_GUN;
        if (aimPoint == null) return ShootResult.NO_TARGET;
        if (!aimPoint.canShoot()) return ShootResult.PATH_BLOCKED;
        return shootAt(shooter, aimPoint.position, pitchDeviation, yawDeviation, null);
    }

    @Override
    public ShootResult shootAtPosition(LivingEntity shooter, Vec3 targetPosition) {
        if (!hasGun(shooter)) return ShootResult.NOT_GUN;
        if (targetPosition == null) return ShootResult.NO_TARGET;
        return shootAt(shooter, targetPosition, 0.0f, 0.0f, null);
    }

    private ShootResult shootAt(LivingEntity shooter, Vec3 aimPos, float pitchDeviation, float yawDeviation, @Nullable LivingEntity targetEntity) {
        if (!hasGun(shooter)) return ShootResult.NOT_GUN;
        ItemStack gunStack = shooter.getMainHandItem();
        GunData data = GunData.from(gunStack);
        GunItem item = data.item;

        if (!data.initialized()) {
            data.initialize();
        }

        data.tick(shooter, true);
        SbwEntityState state = SbwEntityState.get(shooter.getUUID());
        state.tick(shooter, data);

        int tick = shooter.tickCount;
        if (data.reloading() || state.isReloading(tick)) {
            return ShootResult.IS_RELOADING;
        }
        if (data.bolt.actionTimer.get() > 0 || state.isBolting(tick)) {
            return ShootResult.IS_BOLTING;
        }
        if (data.bolt.needed.get()) {
            return ShootResult.NEED_BOLT;
        }
        if (data.charge.time() > 0 || data.overHeat.get() || data.heat.get() >= 100.0) {
            return ShootResult.COOLDOWN;
        }
        if (state.isDrawing(tick)) {
            return ShootResult.IS_DRAWING;
        }

        long now = System.currentTimeMillis();
        if (state.getRemainingShootCooldownMs(now) > 0) {
            return ShootResult.COOLDOWN;
        }

        if (!data.hasEnoughAmmoToShoot(shooter) || (data.ammo.get() <= 0 && !data.useBackpackAmmo())) {
            return ShootResult.NO_AMMO;
        }

        if (!data.canShoot(shooter)) {
            if (data.ammo.get() <= 0) return ShootResult.NO_AMMO;
            if (data.bolt.needed.get()) return ShootResult.NEED_BOLT;
            if (data.reloading()) return ShootResult.IS_RELOADING;
            return ShootResult.COOLDOWN;
        }

        Vec3 origin = new Vec3(shooter.getX(), shooter.getEyeY(), shooter.getZ());
        double dx = aimPos.x - origin.x;
        double dz = aimPos.z - origin.z;
        float basePitch = getAimPitch(shooter, aimPos);
        float baseYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

        float finalPitch = basePitch + pitchDeviation;
        float finalYaw = baseYaw + yawDeviation;

        float yawRad = (float) Math.toRadians(finalYaw);
        float pitchRad = (float) Math.toRadians(finalPitch);
        double dirX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = Math.cos(yawRad) * Math.cos(pitchRad);
        Vec3 shootDirection = new Vec3(dirX, dirY, dirZ).normalize();

        UUID targetUUID = targetEntity != null ? targetEntity.getUUID() : null;
        boolean zoom = data.zooming.get();

        if (!(shooter.level() instanceof ServerLevel serverLevel)) {
            return ShootResult.UNKNOWN;
        }

        ShootParameters parameters = new ShootParameters(
            shooter,
            shooter,
            serverLevel,
            origin,
            shootDirection,
            data,
            0.0,
            zoom,
            targetUUID,
            aimPos
        );

        try {
            item.shoot(parameters);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[SBW] Shoot failed: {}", e.toString());
            return ShootResult.UNKNOWN;
        }

        int rpm = data.get(GunProp.RPM);
        long cooldownMs = rpm > 0 ? Math.max(25L, 60000L / rpm) : 100L;
        if (data.selectedFireModeInfo().mode == FireMode.BURST && data.burstAmount.get() == 0) {
            cooldownMs = Math.max(cooldownMs, (long) (data.get(GunProp.BURST_COOLDOWN) * 50L));
        }
        state.setShootCooldown(now, cooldownMs);

        return ShootResult.SUCCESS;
    }

    @Override
    public boolean canReload(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        if (data.reloading()) return false;
        if (data.useBackpackAmmo()) return false;

        int mag = data.get(GunProp.MAGAZINE);
        int current = data.ammo.get();
        if (current >= mag) return false;

        if (entity instanceof SoldierEntity soldier && soldier.hasInfiniteReserveAmmo()) {
            return true;
        }
        if (data.hasBackupAmmo(entity)) return true;
        if (entity instanceof SoldierEntity soldier) {
            return countAmmoInInventory(soldier, data) > 0;
        }
        return false;
    }

    @Override
    public void reload(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        if (data.reloading()) return;

        int mag = data.get(GunProp.MAGAZINE);
        int current = data.ammo.get();
        int needed = Math.max(0, mag - current);
        if (needed <= 0) return;

        boolean infinite = (entity instanceof SoldierEntity s && s.hasInfiniteReserveAmmo());
        int available = 0;
        if (infinite) {
            available = needed;
        } else if (entity instanceof SoldierEntity soldier) {
            available = countAmmoInInventory(soldier, data);
        } else {
            available = data.countBackupAmmo(entity);
        }

        if (available <= 0) return;
        int toAdd = Math.min(needed, available);

        if (!infinite && entity instanceof SoldierEntity soldier) {
            consumeAmmoFromInventory(soldier, data, toAdd);
        }
        data.virtualAmmo.set(toAdd);

        int reloadTicks;
        if (current == 0) {
            reloadTicks = data.get(GunProp.EMPTY_RELOAD_TIME);
            if (reloadTicks <= 0) reloadTicks = data.get(GunProp.NORMAL_RELOAD_TIME);
        } else {
            reloadTicks = data.get(GunProp.NORMAL_RELOAD_TIME);
        }
        if (reloadTicks <= 0) reloadTicks = 50;

        SbwEntityState state = SbwEntityState.get(entity.getUUID());
        state.setReloading(entity.tickCount, reloadTicks, toAdd, gunStack);
        data.startReload();
    }

    @Override
    public void refillMagazine(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        int mag = data.get(GunProp.MAGAZINE);
        if (mag > 0) {
            data.ammo.set(mag);
            data.virtualAmmo.set(0);
            data.bolt.needed.set(false);
            data.reload.setState(ReloadState.NOT_RELOADING);
            data.save();
        }
        SbwEntityState.get(entity.getUUID()).cancelReload(entity, data);
    }

    @Override
    public void cancelReload(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        SbwEntityState.get(entity.getUUID()).cancelReload(entity, data);
    }

    @Override
    public void bolt(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        data.startBolt();
        int boltTime = data.get(GunProp.BOLT_ACTION_TIME);
        SbwEntityState.get(entity.getUUID()).setBolting(entity.tickCount, boltTime > 0 ? boltTime : 20);
    }

    @Override
    public void aim(LivingEntity entity, boolean isAiming) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        data.zooming.set(isAiming);
        SbwEntityState.get(entity.getUUID()).setAiming(isAiming, entity.tickCount);
    }

    @Override
    public boolean isBolting(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        if (data.bolt.actionTimer.get() > 0) return true;
        return SbwEntityState.get(entity.getUUID()).isBolting(entity.tickCount);
    }

    @Override
    public boolean isReloading(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        if (data.reloading()) return true;
        return SbwEntityState.get(entity.getUUID()).isReloading(entity.tickCount);
    }

    @Override
    public float getAimProgress(LivingEntity entity) {
        SbwEntityState state = SbwEntityState.get(entity.getUUID());
        if (!state.isAiming()) return 0.0f;
        int elapsed = entity.tickCount - state.getAimStartTick();
        return Math.min(1.0f, elapsed / AIM_RAMP_TICKS);
    }

    @Override
    public long getShootCoolDown(LivingEntity entity) {
        return SbwEntityState.get(entity.getUUID()).getRemainingShootCooldownMs(System.currentTimeMillis());
    }

    @Override
    public boolean isDrawing(LivingEntity entity) {
        return SbwEntityState.get(entity.getUUID()).isDrawing(entity.tickCount);
    }

    @Override
    public double getEffectiveRange(LivingEntity entity) {
        if (!hasGun(entity)) return DEFAULT_GUN_RANGE;
        GunData data = GunData.from(entity.getMainHandItem());
        int range = data.get(GunProp.RANGE);
        return range > 0 ? range : DEFAULT_GUN_RANGE;
    }

    @Override
    public Optional<ItemStack> getGunStack(LivingEntity entity) {
        ItemStack main = entity.getMainHandItem();
        return SbwCompat.isGun(main) ? Optional.of(main) : Optional.empty();
    }

    @Override
    public void initialData(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        if (!data.initialized()) {
            data.initialize();
            data.save();
        }
    }

    @Override
    public void draw(LivingEntity entity) {
        if (!hasGun(entity)) return;
        ItemStack gunStack = entity.getMainHandItem();
        GunData data = GunData.from(gunStack);
        data.resetStatus();
        SbwEntityState.get(entity.getUUID()).setDrawing(entity.tickCount, DRAW_TICKS);
    }

    @Override
    public int getMagazineSize(LivingEntity entity) {
        if (!hasGun(entity)) return 30;
        GunData data = GunData.from(entity.getMainHandItem());
        return data.get(GunProp.MAGAZINE);
    }

    @Override
    public int getCurrentAmmo(LivingEntity entity) {
        return getCurrentAmmo(entity.getMainHandItem());
    }

    @Override
    public boolean hasAmmoInBarrel(LivingEntity entity) {
        return getCurrentAmmo(entity) > 0;
    }

    @Override
    public boolean isManualBolt(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        GunData data = GunData.from(entity.getMainHandItem());
        return data.get(GunProp.BOLT_ACTION_TIME) > 0;
    }

    @Override
    public boolean useInventoryAmmo(LivingEntity entity) {
        if (!hasGun(entity)) return false;
        GunData data = GunData.from(entity.getMainHandItem());
        return data.useBackpackAmmo();
    }

    @Override
    public String getGunId(LivingEntity entity) {
        return getGunId(entity.getMainHandItem());
    }

    @Override
    public String getAmmoId(LivingEntity entity) {
        return getAmmoId(entity.getMainHandItem());
    }

    @Override
    public int getCurrentAmmo(ItemStack gunStack) {
        if (!SbwCompat.isGun(gunStack)) return 0;
        return GunData.from(gunStack).ammo.get();
    }

    @Override
    public String getAmmoId(ItemStack gunStack) {
        if (!SbwCompat.isGun(gunStack)) return "";
        GunData data = GunData.from(gunStack);
        AmmoConsumer consumer = data.selectedAmmoConsumer();
        if (consumer.getPlayerAmmoType() != null) {
            return consumer.getPlayerAmmoType().name;
        }
        return consumer.getAmmo() != null ? consumer.getAmmo() : "";
    }

    @Override
    public int getAmmoCountForGun(ItemStack gunStack, ItemStack ammoStack) {
        if (!SbwCompat.isGun(gunStack) || ammoStack == null || ammoStack.isEmpty()) return 0;
        GunData data = GunData.from(gunStack);
        return getAmmoCountForGun(data, ammoStack);
    }

    public static int getAmmoCountForGun(GunData data, ItemStack ammoStack) {
        if (ammoStack == null || ammoStack.isEmpty()) return 0;
        AmmoConsumer consumer = data.selectedAmmoConsumer();
        int loadAmount = Math.max(1, consumer.getLoadAmount());

        if (consumer.isAmmoItem(ammoStack)) {
            return ammoStack.getCount() * loadAmount;
        }

        Ammo ammoType = consumer.getPlayerAmmoType();
        if (ammoType == null && consumer.getAmmo() != null && consumer.getAmmo().contains("@")) {
            String typeName = consumer.getAmmo().substring(consumer.getAmmo().indexOf('@') + 1).trim();
            ammoType = Ammo.getType(typeName);
        }

        if (ammoType != null) {
            if (ammoStack.getItem() == ammoType.getItem()) {
                return ammoStack.getCount() * loadAmount;
            }

            int inTag = ammoType.get(ammoStack);
            if (inTag > 0) {
                return inTag;
            }

            ResourceLocation reg = ForgeRegistries.ITEMS.getKey(ammoStack.getItem());
            if (reg != null && "superbwarfare".equals(reg.getNamespace())) {
                String path = reg.getPath();
                if (path.contains(ammoType.name) && path.contains("ammo")) {
                    return ammoStack.getCount() * loadAmount;
                }
            }
        }

        return 0;
    }

    public static int countAmmoInInventory(SoldierEntity soldier, GunData data) {
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) return 0;
        int total = 0;
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                total += getAmmoCountForGun(data, stack);
            }
        }
        return total;
    }

    public static int consumeAmmoFromInventory(SoldierEntity soldier, GunData data, int needed) {
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null || needed <= 0) return 0;

        int remaining = needed;
        int consumed = 0;
        AmmoConsumer consumer = data.selectedAmmoConsumer();

        for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            int perItemRounds = getAmmoCountForGun(data, stack.copyWithCount(1));
            if (perItemRounds <= 0) continue;

            Ammo ammoType = consumer.getPlayerAmmoType();
            if (ammoType != null && ammoType.get(stack) > 0) {
                int inBox = ammoType.get(stack);
                int take = Math.min(inBox, remaining);
                ammoType.set(stack, inBox - take);
                consumed += take;
                remaining -= take;
                inv.setChanged();
                continue;
            }

            int itemsNeeded = (int) Math.ceil((double) remaining / perItemRounds);
            int itemsToTake = Math.min(stack.getCount(), itemsNeeded);
            int roundsGained = itemsToTake * perItemRounds;

            inv.removeItem(i, itemsToTake);
            consumed += Math.min(remaining, roundsGained);
            remaining -= roundsGained;
        }

        return consumed;
    }

    public static void refundAmmoToInventory(SoldierEntity soldier, GunData data, int count) {
        if (count <= 0) return;
        AmmoConsumer consumer = data.selectedAmmoConsumer();
        Ammo ammoType = consumer.getPlayerAmmoType();
        ItemStack returnStack = ItemStack.EMPTY;

        if (ammoType != null) {
            int loadAmount = Math.max(1, consumer.getLoadAmount());
            int itemCount = (int) Math.ceil((double) count / loadAmount);
            returnStack = ammoType.getItemStack(itemCount);
        } else if (consumer.getStack() != null && !consumer.getStack().isEmpty()) {
            returnStack = consumer.getStack().copyWithCount(Math.max(1, count / consumer.getLoadAmount()));
        }

        if (!returnStack.isEmpty()) {
            SoldierInventory inv = soldier.getSoldierInventory();
            for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE && !returnStack.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot.isEmpty()) {
                    inv.setItem(i, returnStack.copy());
                    returnStack = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameTags(slot, returnStack)) {
                    int space = slot.getMaxStackSize() - slot.getCount();
                    int add = Math.min(space, returnStack.getCount());
                    slot.grow(add);
                    returnStack.shrink(add);
                }
            }
            if (!returnStack.isEmpty()) {
                soldier.spawnAtLocation(returnStack);
            }
            inv.setChanged();
        }
    }

    private String getGunId(ItemStack stack) {
        if (!SbwCompat.isGun(stack)) return "";
        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return rl != null ? rl.toString() : "";
    }

    @Override
    public void lowCrouch(LivingEntity entity, boolean isLowCrouch) {
        if (entity instanceof SoldierEntity soldier) {
            soldier.setLowCrouching(isLowCrouch);
        } else {
            if (isLowCrouch) {
                entity.setPose(Pose.SWIMMING);
            } else if (entity.getPose() == Pose.SWIMMING) {
                entity.setPose(Pose.STANDING);
            }
        }
    }

    @Override
    public boolean isLowCrouching(LivingEntity entity) {
        if (entity instanceof SoldierEntity soldier) {
            return soldier.isLowCrouching();
        }
        return entity.getPose() == Pose.SWIMMING && !entity.isInWater();
    }

    @Override
    public float[] getGunRecoil(LivingEntity entity) {
        if (!hasGun(entity)) return new float[]{0.5f, 0.25f};
        GunData data = GunData.from(entity.getMainHandItem());
        double rx = data.get(GunProp.RECOIL_X);
        double ry = data.get(GunProp.RECOIL_Y);
        double r = data.get(GunProp.RECOIL);
        float pitch = (float) Math.abs(ry != 0.0 ? ry : r);
        float yaw = (float) Math.abs(rx != 0.0 ? rx : r * 0.5);
        return new float[]{Math.max(0.1f, pitch), Math.max(0.05f, yaw)};
    }

    @Override
    public int getRPM(LivingEntity entity) {
        if (!hasGun(entity)) return 600;
        GunData data = GunData.from(entity.getMainHandItem());
        int rpm = data.get(GunProp.RPM);
        return rpm > 0 ? rpm : 600;
    }

    @Override
    public float getBurstMinInterval(LivingEntity entity) {
        if (!hasGun(entity)) return 0.8f;
        GunData data = GunData.from(entity.getMainHandItem());
        int burstCooldown = data.get(GunProp.BURST_COOLDOWN);
        if (burstCooldown > 0) {
            return burstCooldown / 20.0f;
        }
        int rpm = getRPM(entity);
        return rpm > 0 ? Math.max(0.2f, 60.0f / rpm * 3.0f) : 0.8f;
    }

    @Override
    public float getAimInaccuracy(LivingEntity entity) {
        if (!hasGun(entity)) return 0.15f;
        GunData data = GunData.from(entity.getMainHandItem());
        double spread = data.get(GunProp.SPREAD);
        return (float) Math.max(0.02, Math.min(spread, 1.0));
    }

    @Override
    public float getAimPitch(LivingEntity shooter, Vec3 targetPosition) {
        float fallback = straightPitch(shooter, targetPosition);
        if (!hasGun(shooter) || targetPosition == null) return fallback;

        ItemStack gunStack = shooter.getMainHandItem();
        GunData data = GunData.from(gunStack);

        String projectileType = data.get(GunProp.PROJECTILE).getItemId();
        if ("ray".equalsIgnoreCase(projectileType) || "empty".equalsIgnoreCase(projectileType)) {
            return fallback;
        }

        double velocity = data.get(GunProp.VELOCITY);
        double gravity = data.get(GunProp.GRAVITY);
        if (velocity <= 0.0 || gravity <= 0.0) {
            return fallback;
        }

        Vec3 origin = shooter.getEyePosition();
        double dx = targetPosition.x - origin.x;
        double dz = targetPosition.z - origin.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.05 || !Double.isFinite(horizontalDistance)) {
            return fallback;
        }

        double verticalDistance = targetPosition.y - origin.y;
        Double elevation = solveBallisticElevation(horizontalDistance, verticalDistance, velocity, gravity);
        if (elevation == null) {
            return fallback;
        }

        float pitch = (float) -Math.toDegrees(elevation);
        return Float.isFinite(pitch) ? pitch : fallback;
    }

    private static Double solveBallisticElevation(double horizontalDistance, double verticalDistance, double speed, double gravity) {
        final double minimumElevation = Math.toRadians(-30.0);
        final double maximumElevation = Math.toRadians(75.0);
        final int samples = 42;
        double previousAngle = minimumElevation;
        double previousError = trajectoryHeightAtDistance(horizontalDistance, previousAngle, speed, gravity) - verticalDistance;
        if (!Double.isFinite(previousError)) return null;

        for (int i = 1; i <= samples; i++) {
            double angle = minimumElevation + (maximumElevation - minimumElevation) * i / samples;
            double error = trajectoryHeightAtDistance(horizontalDistance, angle, speed, gravity) - verticalDistance;
            if (!Double.isFinite(error)) {
                previousAngle = angle;
                previousError = error;
                continue;
            }
            if (Math.abs(error) < 0.02) return angle;
            if (previousError * error < 0.0) {
                double low = previousAngle;
                double high = angle;
                double lowError = previousError;
                for (int iteration = 0; iteration < 18; iteration++) {
                    double midpoint = (low + high) * 0.5;
                    double midpointError = trajectoryHeightAtDistance(horizontalDistance, midpoint, speed, gravity) - verticalDistance;
                    if (!Double.isFinite(midpointError)) return null;
                    if (Math.abs(midpointError) < 0.005) return midpoint;
                    if (lowError * midpointError <= 0.0) {
                        high = midpoint;
                    } else {
                        low = midpoint;
                        lowError = midpointError;
                    }
                }
                return (low + high) * 0.5;
            }
            previousAngle = angle;
            previousError = error;
        }
        return null;
    }

    private static double trajectoryHeightAtDistance(double horizontalDistance, double elevation, double speed, double gravity) {
        double horizontalVelocity = speed * Math.cos(elevation);
        double verticalVelocity = speed * Math.sin(elevation);
        double horizontalPosition = 0.0;
        double verticalPosition = 0.0;
        double previousHorizontal = 0.0;
        double previousVertical = 0.0;

        for (int tick = 0; tick < 200; tick++) {
            previousHorizontal = horizontalPosition;
            previousVertical = verticalPosition;
            horizontalPosition += horizontalVelocity;
            verticalPosition += verticalVelocity;
            if (horizontalPosition >= horizontalDistance) {
                double fraction = (horizontalDistance - previousHorizontal)
                    / Math.max(horizontalPosition - previousHorizontal, 1.0E-9);
                return previousVertical + (verticalPosition - previousVertical) * fraction;
            }
            verticalVelocity -= gravity;
        }
        return Double.NaN;
    }

    private static float straightPitch(LivingEntity shooter, Vec3 targetPosition) {
        Vec3 origin = shooter.getEyePosition();
        double dx = targetPosition.x - origin.x;
        double dy = targetPosition.y - origin.y;
        double dz = targetPosition.z - origin.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
    }

    @Override
    public GunshotSignature getGunshotSignature(LivingEntity entity) {
        if (!hasGun(entity)) return GunshotSignature.UNSUPPRESSED;
        GunData data = GunData.from(entity.getMainHandItem());
        boolean suppressed = data.attachment.get(AttachmentType.BARREL) == 2;
        int distanceAdjustment = suppressed ? -32 : 0;
        return new GunshotSignature(suppressed, distanceAdjustment);
    }

    @Override
    public String getGunTabType(LivingEntity entity) {
        if (!hasGun(entity)) return "rifle";
        GunData data = GunData.from(entity.getMainHandItem());
        GunType type = data.get(GunProp.GUN_TYPE);
        if (type != null) {
            return switch (type) {
                case MACHINE_GUN -> "machine_gun";
                case RIFLE -> "rifle";
                case SHOTGUN -> "shotgun";
                case SNIPER -> "sniper";
                case SMG -> "smg";
                case HANDGUN -> "handgun";
                case DIRECT_LAUNCHER, CURVED_LAUNCHER -> "launcher";
                default -> "special";
            };
        }
        return "rifle";
    }

    @Override
    public boolean isMachineGun(LivingEntity entity) {
        String tabType = getGunTabType(entity);
        return "machine_gun".equals(tabType) || "mg".equals(tabType) || "smg".equals(tabType);
    }
}

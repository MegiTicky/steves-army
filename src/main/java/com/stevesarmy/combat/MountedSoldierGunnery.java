package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Handheld weapon support for SBW passengers in seats that allow hand use. */
public final class MountedSoldierGunnery {
    private static final int RETARGET_INTERVAL_TICKS = 10;

    private MountedSoldierGunnery() {}

    public static void tickMounted(SoldierEntity soldier) {
        Entity vehicle = soldier.getVehicle();
        if (!SbwCompat.isVehicle(vehicle) || SbwVehicles.isDrone(vehicle)) return;

        int seat = SbwVehicles.seatIndexOf(vehicle, soldier);
        boolean emptyWeaponFallback = seat >= 0
            && SbwVehicles.seatHasWeapon(vehicle, seat)
            && !SbwVehicles.seatHasLoadedWeapon(vehicle, seat)
            && SbwVehicles.seatAllowsRifleWhenWeaponEmpty(vehicle, seat)
            && !SbwVehicles.hidesPassenger(vehicle, soldier);
        if (seat < 0 || SbwVehicles.isDriver(vehicle, soldier)
            || SbwVehicles.hidesPassenger(vehicle, soldier)
            || (!SbwVehicles.seatAllowsHandheld(vehicle, seat) && !emptyWeaponFallback)
            || !GunIntegration.isAnyGunLoaded() || !GunIntegration.hasGun(soldier)
            || ArmorRoleManager.isAtGunStack(soldier.getMainHandItem())) {
            soldier.setTarget(null);
            GunIntegration.aim(soldier, false);
            return;
        }

        double range = Math.min(StevesArmyConfig.getBasicDetectionDistance(),
            Math.max(8.0, GunIntegration.getEffectiveRange(soldier)));
        LivingEntity target = soldier.getTarget();
        if (!isValidTarget(soldier, vehicle, target, range)) {
            if (target != null) soldier.setTarget(null);
            target = null;
            int scanOffset = Math.floorMod(soldier.getUUID().hashCode(), RETARGET_INTERVAL_TICKS);
            if (Math.floorMod(soldier.tickCount, RETARGET_INTERVAL_TICKS) == scanOffset) {
                List<LivingEntity> candidates = soldier.level().getEntitiesOfClass(
                    LivingEntity.class, soldier.getBoundingBox().inflate(range),
                    candidate -> isValidTarget(soldier, vehicle, candidate, range));
                target = candidates.stream()
                    .min(Comparator.comparingDouble(soldier::distanceToSqr))
                    .orElse(null);
                soldier.setTarget(target);
            }
        }

        if (target == null) {
            GunIntegration.aim(soldier, false);
            return;
        }

        Vec3 head = SbwVehicles.isExposedRider(target)
            ? ExposureCalculator.getVisibleHeadPoint(soldier, target) : null;
        Vec3 aimPoint = head != null ? head : target.getEyePosition();
        // VehicleCrewPose owns the head rotation and moves it with the mounted
        // saccade/pursuit model. Do not steer the head here as well: that made
        // the shot gate inspect a temporary yaw which the pose writer replaced
        // at the end of the same server tick.
        GunIntegration.aim(soldier, true);
        if (GunIntegration.isDrawing(soldier)
            || GunIntegration.isReloading(soldier)
            || GunIntegration.isBolting(soldier)) {
            return;
        }

        Vec3 toTarget = aimPoint.subtract(soldier.getEyePosition());
        float targetYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
        float yawError = Mth.wrapDegrees(targetYaw - soldier.getYHeadRot());
        if (Math.abs(yawError) > 9.0f) return;

        if (GunIntegration.getCurrentAmmo(soldier) <= 0) {
            if (GunIntegration.canReload(soldier)) GunIntegration.reload(soldier);
            return;
        }
        if (!FriendlyFireChecker.isSafeToShoot(soldier, aimPoint, 0.65f)) return;

        GunIntegration.ShootResult result;
        if (head != null) {
            if (!VisibilityRay.traceFresh(soldier.level(), soldier.getEyePosition(), head, soldier).hasContact()) return;
            var aimed = new ExposureCalculator.AimPointResult(head,
                ExposureCalculator.AimPointType.HEAD, true, true, 0.0);
            float pitchDeviation = (float) (soldier.getRandom().nextGaussian() * 0.35);
            float yawDeviation = (float) (soldier.getRandom().nextGaussian() * 0.35);
            result = GunIntegration.shootWithDeviation(soldier, aimed, pitchDeviation, yawDeviation);
        } else {
            result = GunIntegration.shoot(soldier, target);
        }
        switch (result) {
            case NO_AMMO -> {
                if (GunIntegration.canReload(soldier)) GunIntegration.reload(soldier);
            }
            case NEED_BOLT -> GunIntegration.bolt(soldier);
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
    }

    private static boolean isValidTarget(SoldierEntity soldier, Entity vehicle,
                                         LivingEntity target, double range) {
        return target != null && target != soldier && target.isAlive()
            && target.level() == soldier.level() && target.getVehicle() != vehicle
            && !target.isSpectator() && soldier.isProactiveTarget(target)
            && !soldier.isFriendlyTo(target)
            && soldier.distanceToSqr(target) <= range * range
            && TargetAcquisition.hasLineOfSight(soldier, target);
    }

}

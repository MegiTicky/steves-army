package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class FriendlyFireChecker {

    private static final float MIN_SPREAD_ANGLE = 5.0f;
    private static final float MAX_SPREAD_ANGLE = 10.0f;
    private static final double SEARCH_RADIUS = 25.0;

    /** Compatibility path for existing non-mobile callers. */
    public static boolean isSafeToShoot(SoldierEntity shooter, Vec3 aimPoint, float accuracy) {
        List<LivingEntity> friendlies = getNearbyFriendlies(shooter);
        if (friendlies.isEmpty()) return true;
        Vec3 origin = shooter.getEyePosition();
        float spreadAngle = calculateSpreadAngle(accuracy);
        for (LivingEntity friendly : friendlies) {
            if (isInLegacyFiringLane(shooter, origin, aimPoint, spreadAngle, friendly)) return false;
        }
        return true;
    }

    /**
     * Checks the bounded custom spread and estimated native angular envelope, plus the
     * exact custom-sampled direction that is about to be supplied to TaCZ.
     * All angular values are degrees; sigma inputs are converted with the
     * same 2.5-sigma clamp used by the shot sampler.
     */
    public static boolean isSafeToShoot(SoldierEntity shooter, Vec3 aimPoint, float accuracy,
                                        float yawSigma, float pitchSigma,
                                        float sampledPitchDeviation, float sampledYawDeviation,
                                        boolean aiming) {
        double customYaw = Math.max(Math.abs(sampledYawDeviation), Math.max(0.0f, yawSigma) * 2.5);
        double customPitch = Math.max(Math.abs(sampledPitchDeviation), Math.max(0.0f, pitchSigma) * 2.5);
        double nativeSpread = Math.max(0.0f, GunIntegration.getNativeInaccuracy(shooter, aiming));
        return isSafeToShoot(shooter, aimPoint, customYaw + nativeSpread,
            customPitch + nativeSpread, sampledPitchDeviation, sampledYawDeviation, true, nativeSpread);
    }

    private static boolean isSafeToShoot(SoldierEntity shooter, Vec3 aimPoint,
                                         double yawEnvelope, double pitchEnvelope,
                                         double sampledPitchDeviation, double sampledYawDeviation,
                                         boolean checkSampledDirection, double nativeSpread) {
        List<LivingEntity> friendlies = getNearbyFriendlies(shooter);
        if (friendlies.isEmpty()) return true;

        Vec3 origin = shooter.getEyePosition();
        Vec3 toAim = aimPoint.subtract(origin);
        double targetDistance = toAim.length();
        if (targetDistance < 1.0e-4) return false;
        double aimYaw = yaw(toAim);
        double aimPitch = pitch(toAim);

        for (LivingEntity friendly : friendlies) {
            AABB box = friendly.getBoundingBox();
            Vec3 closest = closestPoint(box, origin);
            double distance = origin.distanceTo(closest);
            if (distance > targetDistance + 2.0) continue;

            Vec3 toFriendly = box.getCenter().subtract(origin);
            double centerDistance = toFriendly.length();
            if (centerDistance < 1.0e-4) return false;
            double radius = Math.sqrt(box.getXsize() * box.getXsize()
                + box.getYsize() * box.getYsize() + box.getZsize() * box.getZsize()) * 0.5;
            double angularRadius = Math.toDegrees(Math.asin(Math.min(1.0, radius / centerDistance)));
            double yawDelta = Math.abs(wrapDegrees(yaw(toFriendly) - aimYaw));
            double pitchDelta = Math.abs(pitch(toFriendly) - aimPitch);

            // Bounding-sphere angular expansion makes this a conservative
            // full-box cone test instead of a few representative vertical rays.
            if (yawDelta <= yawEnvelope + angularRadius
                && pitchDelta <= pitchEnvelope + angularRadius
                && VisibilityRay.trace(shooter.level(), origin, closest, shooter).hasContact()) {
                return false;
            }

            if (checkSampledDirection) {
                Vec3 sampled = direction(aimYaw + sampledYawDeviation,
                    aimPitch + sampledPitchDeviation);
                double rayLength = targetDistance + nativeSpread * Math.PI / 180.0 * targetDistance + 2.0;
                Vec3 rayEnd = origin.add(sampled.scale(rayLength));
                if (box.clip(origin, rayEnd).isPresent()) {
                    Vec3 hit = box.clip(origin, rayEnd).orElseThrow();
                    if (VisibilityRay.trace(shooter.level(), origin, hit, shooter).hasContact()) return false;
                }
            }
        }
        return true;
    }

    private static List<LivingEntity> getNearbyFriendlies(SoldierEntity shooter) {
        List<LivingEntity> friendlies = new ArrayList<>();
        net.minecraft.world.entity.Entity mount = shooter.getVehicle();
        LivingEntity owner = shooter.getOwner();
        if (owner != null && owner.isAlive() && (mount == null || owner.getVehicle() != mount)) {
            friendlies.add(owner);
        }

        AABB searchBox = shooter.getBoundingBox().inflate(SEARCH_RADIUS);
        for (SoldierEntity soldier : shooter.level().getEntitiesOfClass(SoldierEntity.class, searchBox)) {
            if (soldier != shooter && shooter.isFriendlyTo(soldier)
                && (mount == null || soldier.getVehicle() != mount)) {
                friendlies.add(soldier);
            }
        }
        return friendlies;
    }

    private static Vec3 closestPoint(AABB box, Vec3 point) {
        return new Vec3(Math.max(box.minX, Math.min(box.maxX, point.x)),
            Math.max(box.minY, Math.min(box.maxY, point.y)),
            Math.max(box.minZ, Math.min(box.maxZ, point.z)));
    }

    private static boolean isInLegacyFiringLane(SoldierEntity shooter, Vec3 origin,
                                                 Vec3 targetPoint, float spreadAngleDegrees,
                                                 LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        double distanceToTarget = origin.distanceTo(targetPoint);
        if (origin.distanceTo(entity.position()) > distanceToTarget + 2.0) return false;

        Vec3 toTarget = targetPoint.subtract(origin).normalize();
        Vec3 right = toTarget.cross(new Vec3(0, 1, 0)).normalize();
        if (right.lengthSqr() < 0.001) right = new Vec3(1, 0, 0);
        Vec3 vertical = right.cross(toTarget).normalize();
        double offset = Math.sin(Math.toRadians(spreadAngleDegrees / 2.0));
        Vec3[] directions = {toTarget, toTarget.add(vertical.scale(offset)).normalize(),
            toTarget.subtract(vertical.scale(offset)).normalize()};
        for (Vec3 direction : directions) {
            Vec3 end = origin.add(direction.scale(distanceToTarget + 2.0));
            var hit = box.clip(origin, end);
            if (hit.isPresent()
                && VisibilityRay.trace(shooter.level(), origin, hit.get(), shooter).hasContact()) return true;
        }
        return false;
    }

    private static Vec3 direction(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad),
            Math.cos(yawRad) * horizontal).normalize();
    }

    private static double yaw(Vec3 vector) {
        return Math.toDegrees(Math.atan2(-vector.x, vector.z));
    }

    private static double pitch(Vec3 vector) {
        return -Math.toDegrees(Math.atan2(vector.y, Math.sqrt(vector.x * vector.x + vector.z * vector.z)));
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static float calculateSpreadAngle(float accuracy) {
        return MIN_SPREAD_ANGLE + (1.0f - accuracy) * (MAX_SPREAD_ANGLE - MIN_SPREAD_ANGLE);
    }
}

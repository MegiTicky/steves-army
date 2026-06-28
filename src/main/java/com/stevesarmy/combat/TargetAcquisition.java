package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class TargetAcquisition {
    public static final double BASE_DETECTION_RANGE = 64.0;
    public static final double NIGHT_RANGE_MULTIPLIER = 0.5;
    public static final double RAIN_RANGE_MULTIPLIER = 0.75;
    public static final double FRONT_ARC_DEGREES = 180.0;

    public static boolean canSeeTarget(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return false;
        if (!target.isAlive()) return false;

        double distance = observer.distanceTo(target);
        double maxRange = getEffectiveDetectionRange(observer);
        if (distance > maxRange) return false;

        if (!isInFrontArc(observer, target)) return false;

        return hasLineOfSight(observer, target);
    }

    public static double getEffectiveDetectionRange(LivingEntity entity) {
        double range = BASE_DETECTION_RANGE;
        
        Level level = entity.level();
        if (level.isNight()) {
            range *= NIGHT_RANGE_MULTIPLIER;
        }
        if (level.isRaining()) {
            range *= RAIN_RANGE_MULTIPLIER;
        }
        
        return range;
    }

    public static boolean isInFrontArc(LivingEntity observer, LivingEntity target) {
        Vec3 observerLook = observer.getLookAngle();
        Vec3 toTarget = target.position().subtract(observer.position()).normalize();
        
        double dot = observerLook.dot(toTarget);
        double angleRadians = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double angleDegrees = Math.toDegrees(angleRadians);
        
        return angleDegrees <= FRONT_ARC_DEGREES / 2.0;
    }

    public static boolean hasLineOfSight(LivingEntity observer, LivingEntity target) {
        Vec3 observerEye = observer.getEyePosition();
        Vec3 targetEye = target.getEyePosition();
        
        ClipContext context = new ClipContext(
            observerEye,
            targetEye,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            observer
        );
        
        HitResult result = observer.level().clip(context);
        return result.getType() == HitResult.Type.MISS;
    }

    public static boolean isValidTarget(LivingEntity observer, LivingEntity target) {
        if (target == observer) return false;
        if (!target.isAlive()) return false;
        if (target.isSpectator()) return false;
        return true;
    }

    public static BlockPos getEstimatedPosition(LivingEntity target, double accuracy) {
        if (accuracy >= 1.0) {
            return target.blockPosition();
        }
        
        double maxOffset = 10.0 * (1.0 - accuracy);
        double offsetX = (target.level().random.nextDouble() - 0.5) * maxOffset * 2;
        double offsetZ = (target.level().random.nextDouble() - 0.5) * maxOffset * 2;
        
        return target.blockPosition().offset(
            (int) Math.round(offsetX),
            0,
            (int) Math.round(offsetZ)
        );
    }
}
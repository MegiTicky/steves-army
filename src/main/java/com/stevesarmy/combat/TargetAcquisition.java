package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TargetAcquisition {
    
    private static final Map<Long, VisibilityRay.Result> losCache = new ConcurrentHashMap<>();
    private static long lastCacheClearTick = -1;
    
    public static boolean canSeeTarget(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return false;
        if (!target.isAlive()) return false;
        
        if (!hasLineOfSight(observer, target)) return false;
        
        return true;
    }
    
    public static boolean isInFocusedArc(LivingEntity observer, LivingEntity target) {
        return isInArc(observer, target, DetectionSystem.FOCUSED_ARC_DEGREES, "FOCUSED");
    }
    
    public static boolean isInPeripheralArc(LivingEntity observer, LivingEntity target) {
        return isInArc(observer, target, DetectionSystem.PERIPHERAL_ARC_DEGREES, "PERIPHERAL");
    }
    
    private static boolean isInArc(LivingEntity observer, LivingEntity target, double arcDegrees, String arcName) {
        float headYaw = observer.getYHeadRot();
        float yawRad = (float) Math.toRadians(-headYaw);
        Vec3 observerLook = new Vec3(Mth.sin(yawRad), 0, Mth.cos(yawRad));
        
        Vec3 toTarget = target.position().subtract(observer.position()).normalize();
        
        double dot = observerLook.dot(toTarget);
        double angleRadians = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double angleDegrees = Math.toDegrees(angleRadians);
        
        double threshold = arcDegrees / 2.0;
        return angleDegrees <= threshold;
    }
    
    public static boolean hasLineOfSight(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return false;
        
        long currentTick = observer.tickCount;
        if (lastCacheClearTick != currentTick) {
            losCache.clear();
            lastCacheClearTick = currentTick;
        }
        
        long key = ((long) observer.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        
        return losCache.computeIfAbsent(key, k -> computeVisibility(observer, target)).hasContact();
    }

    private static VisibilityRay.Result computeVisibility(LivingEntity observer, LivingEntity target) {
        return VisibilityRay.trace(observer.level(), observer.getEyePosition(), target.getEyePosition(), observer);
    }

    public static VisibilityRay.Result getVisibility(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) {
            return new VisibilityRay.Result(false, 1.0, 0.0);
        }

        long currentTick = observer.tickCount;
        if (lastCacheClearTick != currentTick) {
            losCache.clear();
            lastCacheClearTick = currentTick;
        }

        long key = ((long) observer.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        return losCache.computeIfAbsent(key, k -> computeVisibility(observer, target));
    }

    public static boolean hasLineOfSightToPosition(LivingEntity observer, Vec3 targetPos) {
        return VisibilityRay.trace(observer.level(), observer.getEyePosition(), targetPos, observer).hasContact();
    }

    public static boolean hasLineOfSightToPositionIgnoringSmoke(LivingEntity observer, Vec3 targetPos) {
        return VisibilityRay.traceIgnoringSmoke(
            observer.level(), observer.getEyePosition(), targetPos, observer).hasContact();
    }

    public static boolean hasNearLineOfSightToPosition(LivingEntity observer, Vec3 targetPos, double distanceThreshold) {
        VisibilityRay.Result visibility = VisibilityRay.trace(
            observer.level(), observer.getEyePosition(), targetPos, observer);
        double targetDistance = observer.getEyePosition().distanceTo(targetPos);
        return visibility.hasContact()
            || (!visibility.clear() && targetDistance - visibility.blockedDistance() <= distanceThreshold);
    }
    
    public static boolean isValidTarget(LivingEntity observer, LivingEntity target) {
        if (target == observer) return false;
        if (!target.isAlive()) return false;
        if (target.isSpectator()) return false;
        if (target instanceof Player player && player.isCreative()) return false;
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

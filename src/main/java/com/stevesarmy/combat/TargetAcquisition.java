package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TargetAcquisition {
    
    /** Visibility results are only reused during one level game tick. */
    private static final Map<Level, TickVisibilityCache> losCaches =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());
    
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

        return getVisibility(observer, target).hasContact();
    }

    private static VisibilityRay.Result computeVisibility(LivingEntity observer, LivingEntity target) {
        return VisibilityRay.trace(observer.level(), observer.getEyePosition(), target.getEyePosition(), observer);
    }

    public static VisibilityRay.Result getVisibility(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) {
            return new VisibilityRay.Result(false, 1.0, 0.0);
        }

        long key = ((long) observer.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        TickVisibilityCache cache = getVisibilityCache(observer.level());
        return cache.results.computeIfAbsent(key, k -> computeVisibility(observer, target));
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

    private static TickVisibilityCache getVisibilityCache(Level level) {
        long currentTick = level.getGameTime();
        synchronized (losCaches) {
            TickVisibilityCache cache = losCaches.get(level);
            if (cache == null) {
                cache = new TickVisibilityCache(currentTick);
                losCaches.put(level, cache);
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.results.clear();
            }
            return cache;
        }
    }

    private static final class TickVisibilityCache {
        private long tick;
        private final Map<Long, VisibilityRay.Result> results = new ConcurrentHashMap<>();

        private TickVisibilityCache(long tick) {
            this.tick = tick;
        }
    }
}

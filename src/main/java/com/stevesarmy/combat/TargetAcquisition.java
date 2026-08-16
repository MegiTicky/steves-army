package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.stevesarmy.debug.PerformanceMetrics;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TargetAcquisition {
    
    /** Exact entity-pair visibility results are reused according to the server profile. */
    private static final Map<Level, TickVisibilityCache> losCaches =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Level, PositionVisibilityCache> positionCaches =
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

    public static void invalidateCaches(Level level) {
        synchronized (losCaches) {
            losCaches.remove(level);
        }
        synchronized (positionCaches) {
            positionCaches.remove(level);
        }
    }

    private static VisibilityRay.Result computeVisibility(LivingEntity observer, LivingEntity target) {
        return VisibilityRay.trace(observer.level(), observer.getEyePosition(), target.getEyePosition(), observer);
    }

    public static VisibilityRay.Result getVisibility(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) {
            return new VisibilityRay.Result(false, 1.0, 0.0);
        }

        // Entity-pair LOS has always had a one-tick cache. Higher profiles only
        // extend it when the exact endpoint positions remain unchanged.
        int cacheTicks = Math.max(1, StevesArmyConfig.getPositionVisibilityCacheTicks());

        long key = ((long) observer.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        TickVisibilityCache cache = getVisibilityCache(observer.level());
        long currentTick = observer.level().getGameTime();
        Vec3 from = observer.getEyePosition();
        Vec3 to = target.getEyePosition();
        CachedVisibility cached = cache.results.get(key);
        if (cached != null && cached.expiresAt >= currentTick
            && cached.from.equals(from) && cached.to.equals(to)) {
            PerformanceMetrics.recordVisibilityCacheHit();
            return cached.result;
        }

        PerformanceMetrics.recordVisibilityCacheMiss();
        VisibilityRay.Result result = VisibilityRay.trace(observer.level(), from, to, observer);
        cache.results.put(key, new CachedVisibility(result, from, to, currentTick + cacheTicks - 1));
        return result;
    }

    public static boolean hasLineOfSightToPosition(LivingEntity observer, Vec3 targetPos) {
        return getPositionVisibility(observer, targetPos, false).hasContact();
    }

    public static boolean hasLineOfSightToPositionIgnoringSmoke(LivingEntity observer, Vec3 targetPos) {
        return getPositionVisibility(observer, targetPos, true).hasContact();
    }

    public static boolean hasNearLineOfSightToPosition(LivingEntity observer, Vec3 targetPos, double distanceThreshold) {
        VisibilityRay.Result visibility = getPositionVisibility(observer, targetPos, false);
        double targetDistance = observer.getEyePosition().distanceTo(targetPos);
        return visibility.hasContact()
            || (!visibility.clear() && targetDistance - visibility.blockedDistance() <= distanceThreshold);
    }

    private static VisibilityRay.Result getPositionVisibility(LivingEntity observer, Vec3 targetPos,
                                                               boolean ignoreSmoke) {
        Vec3 from = observer.getEyePosition();
        int cacheTicks = StevesArmyConfig.getPositionVisibilityCacheTicks();
        if (cacheTicks <= 0) {
            PerformanceMetrics.recordVisibilityCacheMiss();
            return tracePosition(observer, from, targetPos, ignoreSmoke);
        }

        long currentTick = observer.level().getGameTime();
        PositionVisibilityCache cache = getPositionCache(observer.level());
        PositionKey key = new PositionKey(observer.getId(), from, targetPos, ignoreSmoke);
        CachedVisibility cached = cache.results.get(key);
        if (cached != null && cached.expiresAt >= currentTick) {
            PerformanceMetrics.recordVisibilityCacheHit();
            return cached.result;
        }

        PerformanceMetrics.recordVisibilityCacheMiss();
        VisibilityRay.Result result = tracePosition(observer, from, targetPos, ignoreSmoke);
        if (cache.results.size() >= 4096) {
            cache.results.clear();
        }
        cache.results.put(key, new CachedVisibility(result, from, targetPos, currentTick + cacheTicks - 1));
        return result;
    }

    private static VisibilityRay.Result tracePosition(LivingEntity observer, Vec3 from, Vec3 targetPos,
                                                       boolean ignoreSmoke) {
        return ignoreSmoke
            ? VisibilityRay.traceIgnoringSmoke(observer.level(), from, targetPos, observer)
            : VisibilityRay.trace(observer.level(), from, targetPos, observer);
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
            } else if (cache.tick != currentTick && StevesArmyConfig.getPositionVisibilityCacheTicks() <= 1) {
                cache.tick = currentTick;
                cache.results.clear();
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.results.entrySet().removeIf(entry -> entry.getValue().expiresAt < currentTick);
            }
            return cache;
        }
    }

    private static PositionVisibilityCache getPositionCache(Level level) {
        long currentTick = level.getGameTime();
        synchronized (positionCaches) {
            PositionVisibilityCache cache = positionCaches.get(level);
            if (cache == null) {
                cache = new PositionVisibilityCache(currentTick);
                positionCaches.put(level, cache);
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.results.entrySet().removeIf(entry -> entry.getValue().expiresAt < currentTick);
            }
            return cache;
        }
    }

    private static final class TickVisibilityCache {
        private long tick;
        private final Map<Long, CachedVisibility> results = new ConcurrentHashMap<>();

        private TickVisibilityCache(long tick) {
            this.tick = tick;
        }
    }

    private static final class PositionVisibilityCache {
        private long tick;
        private final Map<PositionKey, CachedVisibility> results = new ConcurrentHashMap<>();

        private PositionVisibilityCache(long tick) {
            this.tick = tick;
        }
    }

    private record PositionKey(long observerId, Vec3 from, Vec3 to, boolean ignoreSmoke) {}

    private record CachedVisibility(VisibilityRay.Result result, Vec3 from, Vec3 to, long expiresAt) {}
}

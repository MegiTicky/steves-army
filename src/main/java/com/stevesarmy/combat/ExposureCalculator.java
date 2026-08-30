package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ExposureCalculator {
    private static final Map<Level, TickExposureCache> exposureCaches =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Level, AimPointCache> aimPointCaches =
        Collections.synchronizedMap(new WeakHashMap<>());

    public enum AimPointType {
        HEAD(4, "HEAD"),
        NECK(3, "NECK"),
        CENTER_MASS(8, "CENTER"),
        UPPER_TORSO(9, "UPPER"),
        LOWER_TORSO(6, "LOWER"),
        HIP(5, "HIP"),
        FEET(3, "FEET"),
        FALLBACK(0, "FALLBACK");

        public final int priority;
        public final String displayName;

        AimPointType(int priority, String displayName) {
            this.priority = priority;
            this.displayName = displayName;
        }
    }

    public static class AimPointResult {
        public final Vec3 position;
        public final AimPointType type;
        public final boolean bulletPathClear;
        public final boolean pointVisible;
        public final double concealment;

        public AimPointResult(Vec3 position, AimPointType type, boolean bulletPathClear,
                              boolean pointVisible, double concealment) {
            this.position = position;
            this.type = type;
            this.bulletPathClear = bulletPathClear;
            this.pointVisible = pointVisible;
            this.concealment = concealment;
        }

        public boolean canShoot() {
            return pointVisible && bulletPathClear;
        }
    }

    public static int calculateExposure(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return 0;

        Level level = observer.level();
        TickExposureCache cache = getExposureCache(level);
        long key = ((long) observer.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        int cacheTicks = StevesArmyConfig.getExposureCacheTicks();
        long currentTick = level.getGameTime();
        Vec3 observerEye = observer.getEyePosition();
        Vec3 targetPosition = target.position();
        CachedExposure cached = cache.exposureByPair.get(key);
        if (cached != null && cached.expiresAt >= currentTick
            && cached.observerEye.equals(observerEye)
            && cached.targetPosition.equals(targetPosition)
            && cached.targetPose == target.getPose()
            && cached.targetWidth == target.getBbWidth()
            && cached.targetHeight == target.getBbHeight()) {
            PerformanceMetrics.recordExposureCacheHit();
            return cached.visiblePoints;
        }

        PerformanceMetrics.recordExposureCacheMiss();
        int visiblePoints = calculateExposureUncached(observer, target);
        cache.exposureByPair.put(key, new CachedExposure(visiblePoints, observerEye, targetPosition,
            target.getPose(), target.getBbWidth(), target.getBbHeight(),
            currentTick + cacheTicks - 1));
        return visiblePoints;
    }

    public static void invalidateCaches(Level level) {
        synchronized (exposureCaches) {
            exposureCaches.remove(level);
        }
        synchronized (aimPointCaches) {
            aimPointCaches.remove(level);
        }
    }

    private static int calculateExposureUncached(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return 0;

        PerformanceMetrics.recordExposureCalculation();

        Level level = observer.level();
        Vec3 observerEye = observer.getEyePosition();

        Vec3[] targetPoints = getTargetPoints(target);

        long exposureStart = System.nanoTime();
        int visiblePoints = 0;
        for (Vec3 point : targetPoints) {
            if (VisibilityRay.traceContactOnly(level, observerEye, point, observer).hasContact()) {
                PerformanceMetrics.recordDetectionExposureRay(false);
                visiblePoints++;
            } else {
                PerformanceMetrics.recordDetectionExposureRay(true);
            }
        }
        PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.EXPOSURE,
            System.nanoTime() - exposureStart);

        return visiblePoints;
    }

    private static TickExposureCache getExposureCache(Level level) {
        long currentTick = level.getGameTime();
        synchronized (exposureCaches) {
            TickExposureCache cache = exposureCaches.get(level);
            if (cache == null) {
                cache = new TickExposureCache(currentTick);
                exposureCaches.put(level, cache);
            } else if (cache.tick != currentTick && StevesArmyConfig.getExposureCacheTicks() <= 1) {
                cache.tick = currentTick;
                cache.exposureByPair.clear();
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.exposureByPair.entrySet().removeIf(entry -> entry.getValue().expiresAt < currentTick);
            }
            return cache;
        }
    }

    private static AimPointCache getAimPointCache(Level level, long currentTick) {
        synchronized (aimPointCaches) {
            AimPointCache cache = aimPointCaches.get(level);
            if (cache == null) {
                cache = new AimPointCache(currentTick);
                aimPointCaches.put(level, cache);
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.results.entrySet().removeIf(entry -> entry.getValue().expiresAt < currentTick);
            }
            return cache;
        }
    }

    private static final class TickExposureCache {
        private long tick;
        private final Map<Long, CachedExposure> exposureByPair = new ConcurrentHashMap<>();

        private TickExposureCache(long tick) {
            this.tick = tick;
        }
    }

    private record CachedExposure(int visiblePoints, Vec3 observerEye, Vec3 targetPosition,
                                  net.minecraft.world.entity.Pose targetPose,
                                  float targetWidth, float targetHeight, long expiresAt) {}

    public static double getExposureFactor(LivingEntity observer, LivingEntity target) {
        int visiblePoints = calculateExposure(observer, target);
        return Math.sqrt(visiblePoints / 8.0);
    }

    public static AimPointResult getBestAimPoint(LivingEntity observer, LivingEntity target) {
        return getBestAimPoint(observer, target, null);
    }

    @javax.annotation.Nullable
    public static Vec3 getVisibleHeadPoint(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) {
            return null;
        }

        Vec3 observerEye = observer.getEyePosition();
        for (TargetPoint point : getTargetPointsWithPriority(target)) {
            if (point.type != AimPointType.HEAD) {
                continue;
            }
            if (VisibilityRay.trace(target.level(), observerEye, point.position, observer).hasContact()) {
                return point.position;
            }
        }
        return null;
    }

    public static AimPointResult getBestAimPoint(LivingEntity observer, LivingEntity target,
                                                 net.minecraft.core.BlockPos skipBlock) {
        if (observer.level() != target.level()) {
            return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false, 1.0);
        }

        int cacheTicks = StevesArmyConfig.getAimPointCacheTicks();
        Vec3 observerEye = observer.getEyePosition();
        Vec3 targetPosition = target.position();
        net.minecraft.world.entity.Pose targetPose = target.getPose();
        float targetWidth = target.getBbWidth();
        float targetHeight = target.getBbHeight();
        long currentTick = observer.level().getGameTime();
        AimPointCache cache = getAimPointCache(observer.level(), currentTick);
        AimPointKey key = new AimPointKey(observer.getId(), target.getUUID(), observerEye,
            targetPosition, targetPose, targetWidth, targetHeight, skipBlock);
        if (cacheTicks > 0) {
            CachedAimPoint cached = cache.results.get(key);
            if (cached != null && cached.expiresAt >= currentTick) {
                PerformanceMetrics.recordAimPointCacheHit();
                return cached.result;
            }
            PerformanceMetrics.recordAimPointCacheMiss();
        }

        AimPointResult result = getBestAimPointFrom(observerEye, target, observer, skipBlock);
        if (cacheTicks > 0) {
            if (cache.results.size() >= 4096) {
                cache.results.clear();
            }
            cache.results.put(key, new CachedAimPoint(result, currentTick + cacheTicks - 1));
        }
        return result;
    }

    public static AimPointResult getBestAimPointFrom(Vec3 observerEye, LivingEntity target) {
        return getBestAimPointFrom(observerEye, target, null, null);
    }

    public static boolean hasAnyAimPointFrom(Vec3 observerEye, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        for (TargetPoint point : getTargetPointsWithPriority(target)) {
            if (VisibilityRay.trace(target.level(), observerEye, point.position, null).hasContact()) {
                return true;
            }
        }
        return false;
    }

    public static double getBestFiringLaneQualityFrom(Vec3 observerEye, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return 0.0;
        }

        double bestQuality = 0.0;
        for (TargetPoint point : getTargetPointsWithPriority(target)) {
            bestQuality = Math.max(bestQuality,
                VisibilityRay.trace(target.level(), observerEye, point.position, null).firingLaneQuality());
        }
        return bestQuality;
    }

    private static AimPointResult getBestAimPointFrom(Vec3 observerEye, LivingEntity target,
                                                       LivingEntity observer, net.minecraft.core.BlockPos skipBlock) {
        net.minecraft.world.level.Level level = target.level();

        TargetPoint[] targetPoints = getTargetPointsWithPriority(target);

        TargetPoint bestVisible = null;

        for (TargetPoint point : targetPoints) {
            VisibilityRay.Result visibility = getVisibility(level, observerEye, point.position, observer, skipBlock);
            if (visibility.hasContact()) {
                if (bestVisible == null || point.type.priority > bestVisible.type.priority) {
                    bestVisible = point;
                }
            }
        }

        if (bestVisible != null) {
            VisibilityRay.Result visibility = getVisibility(
                level, observerEye, bestVisible.position, observer, skipBlock);
            return new AimPointResult(bestVisible.position, bestVisible.type, true, true, visibility.concealment());
        }

        return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false, 1.0);
    }

    private static TargetPoint[] getTargetPointsWithPriority(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();

        double headY = basePos.y + height * 0.92;
        double neckY = basePos.y + height * 0.82;
        double midTorsoY = basePos.y + height * 0.55;
        double upperTorsoY = basePos.y + height * 0.68;
        double lowerTorsoY = basePos.y + height * 0.40;
        double hipY = basePos.y + height * 0.25;
        double feetY = basePos.y + height * 0.10;

        double halfWidth = width * 0.35;
        double quarterWidth = width * 0.15;

        return new TargetPoint[] {
            new TargetPoint(new Vec3(basePos.x, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x, neckY, basePos.z), AimPointType.NECK),
            new TargetPoint(new Vec3(basePos.x, midTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
            new TargetPoint(new Vec3(basePos.x, lowerTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, hipY, basePos.z), AimPointType.HIP),
            new TargetPoint(new Vec3(basePos.x - quarterWidth, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x + quarterWidth, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x - halfWidth, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
            new TargetPoint(new Vec3(basePos.x + halfWidth, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
            new TargetPoint(new Vec3(basePos.x - halfWidth, lowerTorsoY, basePos.z), AimPointType.LOWER_TORSO),
            new TargetPoint(new Vec3(basePos.x + halfWidth, lowerTorsoY, basePos.z), AimPointType.LOWER_TORSO),
            new TargetPoint(new Vec3(basePos.x - halfWidth, feetY, basePos.z), AimPointType.FEET),
            new TargetPoint(new Vec3(basePos.x + halfWidth, feetY, basePos.z), AimPointType.FEET),
        };
    }

    private static Vec3[] getTargetPoints(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();

        double headY = basePos.y + height * 0.85;
        double upperTorsoY = basePos.y + height * 0.65;
        double lowerTorsoY = basePos.y + height * 0.35;
        double feetY = basePos.y + height * 0.1;

        double halfWidth = width * 0.45;

        return new Vec3[] {
            new Vec3(basePos.x - halfWidth, headY, basePos.z),
            new Vec3(basePos.x + halfWidth, headY, basePos.z),
            new Vec3(basePos.x - halfWidth, upperTorsoY, basePos.z),
            new Vec3(basePos.x + halfWidth, upperTorsoY, basePos.z),
            new Vec3(basePos.x - halfWidth, lowerTorsoY, basePos.z),
            new Vec3(basePos.x + halfWidth, lowerTorsoY, basePos.z),
            new Vec3(basePos.x - halfWidth, feetY, basePos.z),
            new Vec3(basePos.x + halfWidth, feetY, basePos.z)
        };
    }

    private static VisibilityRay.Result getVisibility(net.minecraft.world.level.Level level, Vec3 from, Vec3 to,
                                                       LivingEntity observer, net.minecraft.core.BlockPos skipBlock) {
        if (skipBlock == null) {
            return VisibilityRay.trace(level, from, to, observer);
        }

        net.minecraft.core.BlockPos first = skipBlock;
        net.minecraft.core.BlockPos second = skipBlock.above();
        return VisibilityRay.trace(level, from, to, observer, first, second);
    }

    private static VisibilityRay.Result getVisibility(net.minecraft.world.level.Level level, Vec3 from, Vec3 to,
                                                       LivingEntity observer) {
        return VisibilityRay.trace(level, from, to, observer);
    }

    private static class TargetPoint {
        final Vec3 position;
        final AimPointType type;

        TargetPoint(Vec3 position, AimPointType type) {
            this.position = position;
            this.type = type;
        }
    }

    private record AimPointKey(int observerId, java.util.UUID targetId,
                               Vec3 observerEye, Vec3 targetPosition,
                               net.minecraft.world.entity.Pose targetPose,
                               float targetWidth, float targetHeight,
                               net.minecraft.core.BlockPos skipBlock) {}

    private record CachedAimPoint(AimPointResult result, long expiresAt) {}

    private static final class AimPointCache {
        private long tick;
        private final Map<AimPointKey, CachedAimPoint> results = new ConcurrentHashMap<>();

        private AimPointCache(long tick) {
            this.tick = tick;
        }
    }
}

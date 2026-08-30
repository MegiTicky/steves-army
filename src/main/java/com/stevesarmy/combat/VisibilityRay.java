package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.combat.smoke.SmokeSourceRegistry;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class VisibilityRay {
    private static final double EPSILON = 1.0e-7;
    private static final double MAX_CONCEALMENT = 1.0;
    private static final double LEAF_CONCEALMENT = 0.75;

    private static final Map<Level, TraceCache> TRACE_CACHES =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private VisibilityRay() {}

    public enum SmokePolicy { BLOCK, IGNORE }

    public enum TracePurpose {
        FULL_VISIBILITY,
        CONTACT_ONLY
    }

    public record Result(boolean clear, double concealment, double blockedDistance) {
        public boolean hasContact() {
            return clear && concealment < MAX_CONCEALMENT;
        }

        public double firingLaneQuality() {
            return clear ? Math.max(0.0, 1.0 - concealment) : 0.0;
        }

        public double spottingMultiplier() {
            return Math.max(0.05, 1.0 - 1.6 * concealment);
        }
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return trace(level, from, to, observer, SmokePolicy.BLOCK, Set.of());
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                               BlockPos... ignoredBlocks) {
        Set<BlockPos> ignored = new java.util.HashSet<>();
        for (BlockPos ignoredBlock : ignoredBlocks) {
            if (ignoredBlock != null) {
                ignored.add(ignoredBlock);
            }
        }
        return trace(level, from, to, observer, SmokePolicy.BLOCK, ignored);
    }

    public static void invalidateCache(Level level) {
        SameTickPerceptionFrame.invalidate(level);
        synchronized (TRACE_CACHES) {
            TRACE_CACHES.remove(level);
        }
    }

    public static Result traceIgnoringSmoke(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return trace(level, from, to, observer, SmokePolicy.IGNORE, Set.of());
    }

    public static Result traceFresh(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return traceFresh(level, from, to, observer, new BlockPos[0]);
    }

    public static Result traceFresh(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                                    BlockPos... ignoredBlocks) {
        PerformanceMetrics.recordVisibilityRay();
        Set<BlockPos> ignored = new java.util.HashSet<>();
        for (BlockPos ignoredBlock : ignoredBlocks) {
            if (ignoredBlock != null) ignored.add(ignoredBlock);
        }
        return traceUncached(level, from, to, observer, SmokePolicy.BLOCK,
            Set.copyOf(ignored), TracePurpose.FULL_VISIBILITY);
    }

    public static Result traceFreshIgnoringSmoke(Level level, Vec3 from, Vec3 to,
                                                  LivingEntity observer) {
        PerformanceMetrics.recordVisibilityRay();
        return traceUncached(level, from, to, observer, SmokePolicy.IGNORE,
            Set.of(), TracePurpose.FULL_VISIBILITY);
    }

    public static Result traceContactOnly(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        if (StevesArmyConfig.isPerceptionFrameEnabled()) {
            Set<BlockPos> empty = Set.of();
            Result cached = SameTickPerceptionFrame.getVisibility(level,
                observer == null ? 0 : observer.getId(), from, to,
                SmokePolicy.BLOCK, empty);
            if (cached != null) {
                PerformanceMetrics.recordSameTickVisibilityFrameHit();
                PerformanceMetrics.recordVisibilityRayCacheHit();
                return cached;
            }
            PerformanceMetrics.recordSameTickVisibilityFrameMiss();
            PerformanceMetrics.recordVisibilityRayCacheMiss();
            Result result = traceUncached(level, from, to, observer,
                SmokePolicy.BLOCK, empty, TracePurpose.CONTACT_ONLY);
            SameTickPerceptionFrame.putVisibility(level, observer == null ? 0 : observer.getId(),
                from, to, SmokePolicy.BLOCK, empty, result);
            return result;
        }

        return traceUncached(level, from, to, observer,
            SmokePolicy.BLOCK, Set.of(), TracePurpose.CONTACT_ONLY);
    }

    private static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                                SmokePolicy smokePolicy, Set<BlockPos> ignoredBlocks) {
        Set<BlockPos> immutableIgnored = Set.copyOf(ignoredBlocks);
        if (StevesArmyConfig.isPerceptionFrameEnabled()) {
            Result cached = SameTickPerceptionFrame.getVisibility(level,
                observer == null ? 0 : observer.getId(), from, to, smokePolicy, immutableIgnored);
            if (cached != null) {
                PerformanceMetrics.recordSameTickVisibilityFrameHit();
                PerformanceMetrics.recordVisibilityRayCacheHit();
                return cached;
            }

            PerformanceMetrics.recordSameTickVisibilityFrameMiss();
            PerformanceMetrics.recordVisibilityRayCacheMiss();
            Result result = traceUncached(level, from, to, observer, smokePolicy,
                immutableIgnored, TracePurpose.FULL_VISIBILITY);
            SameTickPerceptionFrame.putVisibility(level, observer == null ? 0 : observer.getId(),
                from, to, smokePolicy, immutableIgnored, result);
            return result;
        }

        int cacheTicks = StevesArmyConfig.getPositionVisibilityCacheTicks();
        if (cacheTicks <= 0) {
            PerformanceMetrics.recordVisibilityRay();
            return traceUncached(level, from, to, observer, smokePolicy,
                immutableIgnored, TracePurpose.FULL_VISIBILITY);
        }

        long currentTick = level.getGameTime();
        TraceCache cache = getTraceCache(level, currentTick);
        TraceKey key = new TraceKey(observer == null ? 0 : observer.getId(), from, to,
            smokePolicy, immutableIgnored);
        CachedTrace cached = cache.results.get(key);
        if (cached != null && cached.expiresAt >= currentTick) {
            PerformanceMetrics.recordVisibilityRayCacheHit();
            return cached.result;
        }

        PerformanceMetrics.recordVisibilityRayCacheMiss();
        Result result = traceUncached(level, from, to, observer, smokePolicy,
            immutableIgnored, TracePurpose.FULL_VISIBILITY);
        if (cache.results.size() >= 8192) {
            cache.results.clear();
        }
        cache.results.put(key, new CachedTrace(result, currentTick + cacheTicks - 1));
        return result;
    }

    private static Result traceUncached(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                                         SmokePolicy smokePolicy, Set<BlockPos> ignoredBlocks,
                                         TracePurpose purpose) {
        if (from.distanceToSqr(to) < EPSILON) {
            return new Result(true, 0.0, Double.POSITIVE_INFINITY);
        }

        Vec3 direction = to.subtract(from);
        double length = direction.length();
        Vec3 unit = direction.scale(1.0 / length);

        final boolean contactOnly = (purpose == TracePurpose.CONTACT_ONLY);

        double shipObstruction;
        if (contactOnly && !VS2Compat.isEnabled()) {
            shipObstruction = Double.POSITIVE_INFINITY;
        } else {
            shipObstruction = VS2Compat.getShipAwareBlockHitDistance(level, from, to, observer);
        }

        double concealment = 0.0;
        double nearestObstruction = Double.POSITIVE_INFINITY;

        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);
        int endX = floor(to.x);
        int endY = floor(to.y);
        int endZ = floor(to.z);

        long blockStart = System.nanoTime();
        double t = 0.0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        while (t <= length + EPSILON) {
            mutablePos.set(x, y, z);
            BlockState state = level.getBlockState(mutablePos);

            if (ignoredBlocks.contains(mutablePos)) {
                // Skip - cover-peek callers explicitly ignore this block.
            } else if (contactOnly) {
                // Fast path: any solid collision blocks the ray immediately.
                if (!isLeaf(state) && !isTransparent(state) && !isConcealment(state)) {
                    VoxelShape shape = state.getCollisionShape(level, mutablePos);
                    if (!shape.isEmpty() && shape.clip(from, to, mutablePos) != null) {
                        PerformanceMetrics.recordStageTime(
                            PerformanceMetrics.Stage.LOS_BLOCK_TRAVERSAL,
                            System.nanoTime() - blockStart);
                        return new Result(false, 0.0, Math.min(shipObstruction, shipObstruction));
                    }
                }
            } else {
                // Full path: accumulate concealment, track nearest obstruction.
                if (isLeaf(state)) {
                    if (outlineIntersectsRay(level, state, mutablePos, from, to)) {
                        concealment = Math.min(MAX_CONCEALMENT, concealment + LEAF_CONCEALMENT);
                    }
                } else if (isTransparent(state)) {
                    // Transparent blocks are ignored.
                } else if (isConcealment(state)) {
                    if (outlineIntersectsRay(level, state, mutablePos, from, to)) {
                        concealment = Math.min(MAX_CONCEALMENT, concealment + concealmentWeight(state));
                    }
                } else if (intersectsBlock(level, state, mutablePos, from, to)) {
                    BlockHitResult hit = blockHit(level, state, mutablePos, from, to);
                    double blocked = hit == null ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
                    nearestObstruction = Math.min(nearestObstruction, blocked);
                    if (smokePolicy == SmokePolicy.IGNORE) {
                        PerformanceMetrics.recordStageTime(
                            PerformanceMetrics.Stage.LOS_BLOCK_TRAVERSAL,
                            System.nanoTime() - blockStart);
                        return new Result(false, concealment, Math.min(blocked, shipObstruction));
                    }
                }
            }

            if (x == endX && y == endY && z == endZ) {
                break;
            }

            double nextX = nextBoundary(from.x, unit.x, x);
            double nextY = nextBoundary(from.y, unit.y, y);
            double nextZ = nextBoundary(from.z, unit.z, z);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next == Double.POSITIVE_INFINITY) {
                break;
            }

            if (nextX <= next + EPSILON) x += step(unit.x);
            if (nextY <= next + EPSILON) y += step(unit.y);
            if (nextZ <= next + EPSILON) z += step(unit.z);
            t = next;
        }

        PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.LOS_BLOCK_TRAVERSAL,
            System.nanoTime() - blockStart);

        nearestObstruction = Math.min(nearestObstruction, shipObstruction);

        if (contactOnly) {
            if (smokePolicy == SmokePolicy.BLOCK) {
                long smokeStart = System.nanoTime();
                double smokeEntry = findSmokeIntersection(level, from, to);
                PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.SMOKE_LOOKUP,
                    System.nanoTime() - smokeStart);
                if (smokeEntry >= 0) {
                    return new Result(false, 0.0, smokeEntry);
                }
            }
            return new Result(true, 0.0, Double.POSITIVE_INFINITY);
        }

        if (smokePolicy == SmokePolicy.BLOCK) {
            long smokeStart = System.nanoTime();
            double smokeEntry = findSmokeIntersection(level, from, to);
            PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.SMOKE_LOOKUP,
                System.nanoTime() - smokeStart);
            if (smokeEntry >= 0) {
                if (smokeEntry < nearestObstruction) {
                    return new Result(false, MAX_CONCEALMENT, smokeEntry);
                }
                return new Result(false, concealment, nearestObstruction);
            }
        }

        if (nearestObstruction < Double.POSITIVE_INFINITY) {
            return new Result(false, concealment, nearestObstruction);
        }

        return new Result(true, concealment, Double.POSITIVE_INFINITY);
    }

    private static TraceCache getTraceCache(Level level, long currentTick) {
        synchronized (TRACE_CACHES) {
            TraceCache cache = TRACE_CACHES.get(level);
            if (cache == null) {
                cache = new TraceCache(currentTick);
                TRACE_CACHES.put(level, cache);
            } else if (cache.tick != currentTick) {
                cache.tick = currentTick;
                cache.results.entrySet().removeIf(entry -> entry.getValue().expiresAt < currentTick);
            }
            return cache;
        }
    }

    private static double findSmokeIntersection(Level level, Vec3 from, Vec3 to) {
        if (!SmokeSourceRegistry.hasActiveSmoke(level)) {
            return -1;
        }
        long smokeStart = System.nanoTime();
        double result = SmokeSourceRegistry.findSmokeIntersection(level, from, to);
        PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.SMOKE_LOOKUP,
            System.nanoTime() - smokeStart);
        if (result >= 0) {
            PerformanceMetrics.recordSmokeHit();
        }
        return result;
    }

    private static boolean intersectsBlock(Level level, BlockState state, BlockPos pos,
                                           Vec3 from, Vec3 to) {
        return blockHit(level, state, pos, from, to) != null;
    }

    private static BlockHitResult blockHit(Level level, BlockState state, BlockPos pos,
                                           Vec3 from, Vec3 to) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        BlockHitResult hit = shape.clip(from, to, pos);
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean outlineIntersectsRay(Level level, BlockState state, BlockPos pos,
                                                Vec3 from, Vec3 to) {
        VoxelShape shape = state.getShape(level, pos);
        return !shape.isEmpty() && shape.clip(from, to, pos) != null;
    }

    private static boolean isTransparent(BlockState state) {
        return state.is(ModBlockTags.TRANSPARENT_PENETRABLE);
    }

    private static boolean isLeaf(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    private static boolean isConcealment(BlockState state) {
        return state.is(ModBlockTags.VISION_CONCEALMENT);
    }

    private static double concealmentWeight(BlockState state) {
        if (state.is(ModBlockTags.VISION_CONCEALMENT_MEDIUM)) return 0.30;
        return 0.20;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int step(double component) {
        return component > 0.0 ? 1 : -1;
    }

    private static double nextBoundary(double coordinate, double direction, int block) {
        if (Math.abs(direction) < EPSILON) return Double.POSITIVE_INFINITY;
        double boundary = direction > 0.0 ? block + 1.0 : block;
        return (boundary - coordinate) / direction;
    }

    private record TraceKey(int observerId, Vec3 from, Vec3 to,
                            SmokePolicy smokePolicy, Set<BlockPos> ignoredBlocks) {}

    private record CachedTrace(Result result, long expiresAt) {}

    private static final class TraceCache {
        private long tick;
        private final Map<TraceKey, CachedTrace> results = new java.util.HashMap<>();

        private TraceCache(long tick) {
            this.tick = tick;
        }
    }
}

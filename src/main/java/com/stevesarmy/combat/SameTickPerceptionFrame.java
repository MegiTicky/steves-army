package com.stevesarmy.combat;

import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-thread-only perception snapshots shared within one level tick.
 * Entries contain live entities and must never cross a tick boundary.
 */
public final class SameTickPerceptionFrame {
    private static final int CELL_SIZE = 16;
    private static final int MAX_LIVING_QUERIES = 2048;
    private static final int MAX_VISIBILITY_RESULTS = 16384;
    private static final Map<Level, Frame> FRAMES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private SameTickPerceptionFrame() {}

    public static List<LivingEntity> getNearbyLivingEntities(Level level, Vec3 center,
                                                               double radius) {
        long tick = level.getGameTime();
        CellKey cell = new CellKey(floorCell(center.x), floorCell(center.y), floorCell(center.z),
            (int) Math.ceil(radius));
        Frame frame = getFrame(level, tick);
        List<LivingEntity> cached = frame.livingQueries.get(cell);
        if (cached != null) {
            PerformanceMetrics.recordTargetQueryCacheHit(cached.size());
            return cached;
        }

        int cellX = cell.x;
        int cellY = cell.y;
        int cellZ = cell.z;
        double minX = cellX * CELL_SIZE;
        double minY = cellY * CELL_SIZE;
        double minZ = cellZ * CELL_SIZE;
        AABB cellBounds = new AABB(minX, minY, minZ,
            minX + CELL_SIZE, minY + CELL_SIZE, minZ + CELL_SIZE);
        AABB queryBounds = cellBounds.inflate(radius + 1.0);
        List<LivingEntity> result = List.copyOf(new ArrayList<>(
            level.getEntitiesOfClass(LivingEntity.class, queryBounds)));
        if (frame.livingQueries.size() >= MAX_LIVING_QUERIES) {
            frame.livingQueries.clear();
        }
        frame.livingQueries.put(cell, result);
        PerformanceMetrics.recordTargetQueryCacheMiss(result.size());
        return result;
    }

    public static VisibilityRay.Result getVisibility(Level level, int observerId,
                                                       Vec3 from, Vec3 to,
                                                       VisibilityRay.SmokePolicy smokePolicy,
                                                       Set<net.minecraft.core.BlockPos> ignoredBlocks) {
        Frame frame = getFrame(level, level.getGameTime());
        VisibilityKey key = quantizedKey(observerId, from, to, smokePolicy, ignoredBlocks);
        return frame.visibilityResults.get(key);
    }

    public static void putVisibility(Level level, int observerId, Vec3 from, Vec3 to,
                                     VisibilityRay.SmokePolicy smokePolicy,
                                     Set<net.minecraft.core.BlockPos> ignoredBlocks,
                                     VisibilityRay.Result result) {
        Frame frame = getFrame(level, level.getGameTime());
        VisibilityKey key = quantizedKey(observerId, from, to, smokePolicy, ignoredBlocks);
        if (frame.visibilityResults.size() >= MAX_VISIBILITY_RESULTS) {
            frame.visibilityResults.clear();
        }
        frame.visibilityResults.put(key, result);
    }

    /** Quantizes Vec3 coordinates to half-block resolution for cache key stability. */
    private static VisibilityKey quantizedKey(int observerId, Vec3 from, Vec3 to,
                                               VisibilityRay.SmokePolicy smokePolicy,
                                               Set<net.minecraft.core.BlockPos> ignoredBlocks) {
        return new VisibilityKey(observerId,
            (int) Math.floor(from.x * 2), (int) Math.floor(from.y * 2), (int) Math.floor(from.z * 2),
            (int) Math.floor(to.x * 2), (int) Math.floor(to.y * 2), (int) Math.floor(to.z * 2),
            smokePolicy, Set.copyOf(ignoredBlocks));
    }

    public static void invalidate(Level level) {
        synchronized (FRAMES) {
            FRAMES.remove(level);
        }
    }

    private static Frame getFrame(Level level, long tick) {
        synchronized (FRAMES) {
            Frame frame = FRAMES.get(level);
            if (frame == null || frame.tick != tick) {
                frame = new Frame(tick);
                FRAMES.put(level, frame);
            }
            return frame;
        }
    }

    private static int floorCell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private record CellKey(int x, int y, int z, int radius) {}

    private record VisibilityKey(int observerId,
                                 int fromX, int fromY, int fromZ,
                                 int toX, int toY, int toZ,
                                 VisibilityRay.SmokePolicy smokePolicy,
                                 Set<net.minecraft.core.BlockPos> ignoredBlocks) {}

    private static final class Frame {
        private final long tick;
        private final Map<CellKey, List<LivingEntity>> livingQueries = new HashMap<>();
        private final Map<VisibilityKey, VisibilityRay.Result> visibilityResults = new HashMap<>();

        private Frame(long tick) {
            this.tick = tick;
        }
    }
}

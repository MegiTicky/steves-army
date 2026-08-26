package com.stevesarmy.combat;

import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
    private static final double ENTITY_CELL_MARGIN = 8.0;
    private static final int MAX_LIVING_QUERIES = 2048;
    private static final int MAX_SMOKE_CELLS = 4096;
    private static final int MAX_VISIBILITY_RESULTS = 16384;
    private static final int MAX_SMOKE_QUERY_CELLS = 512;
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

    public static List<? extends Entity> getSmokeEntities(Level level, AABB bounds,
                                                            EntityType<?> smokeType) {
        if (bounds.minX > bounds.maxX || bounds.minY > bounds.maxY || bounds.minZ > bounds.maxZ) {
            return List.of();
        }

        Frame frame = getFrame(level, level.getGameTime());
        Set<Entity> result = Collections.newSetFromMap(new IdentityHashMap<>());
        int minX = floorCell(bounds.minX);
        int minY = floorCell(bounds.minY);
        int minZ = floorCell(bounds.minZ);
        int maxX = floorCell(bounds.maxX);
        int maxY = floorCell(bounds.maxY);
        int maxZ = floorCell(bounds.maxZ);

        long cellCount = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cellCount > MAX_SMOKE_QUERY_CELLS) {
            return List.copyOf(new ArrayList<>(level.getEntities(
                (Entity) null, bounds,
                entity -> entity.getType() == smokeType && entity.isAlive())));
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    SmokeCellKey key = new SmokeCellKey(x, y, z, smokeType);
                    List<Entity> cellEntities = frame.smokeCells.get(key);
                    if (cellEntities == null) {
                        cellEntities = querySmokeCell(level, x, y, z, smokeType);
                        if (frame.smokeCells.size() >= MAX_SMOKE_CELLS) {
                            frame.smokeCells.clear();
                        }
                        frame.smokeCells.put(key, cellEntities);
                        PerformanceMetrics.recordSameTickSmokeFrameMiss();
                    } else {
                        PerformanceMetrics.recordSameTickSmokeFrameHit();
                    }
                    result.addAll(cellEntities);
                }
            }
        }
        return List.copyOf(new ArrayList<>(result));
    }

    public static VisibilityRay.Result getVisibility(Level level, int observerId,
                                                       Vec3 from, Vec3 to,
                                                       VisibilityRay.SmokePolicy smokePolicy,
                                                       Set<net.minecraft.core.BlockPos> ignoredBlocks) {
        Frame frame = getFrame(level, level.getGameTime());
        VisibilityKey key = new VisibilityKey(observerId, from, to, smokePolicy,
            Set.copyOf(ignoredBlocks));
        return frame.visibilityResults.get(key);
    }

    public static void putVisibility(Level level, int observerId, Vec3 from, Vec3 to,
                                     VisibilityRay.SmokePolicy smokePolicy,
                                     Set<net.minecraft.core.BlockPos> ignoredBlocks,
                                     VisibilityRay.Result result) {
        Frame frame = getFrame(level, level.getGameTime());
        VisibilityKey key = new VisibilityKey(observerId, from, to, smokePolicy,
            Set.copyOf(ignoredBlocks));
        if (frame.visibilityResults.size() >= MAX_VISIBILITY_RESULTS) {
            frame.visibilityResults.clear();
        }
        frame.visibilityResults.put(key, result);
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

    private static List<Entity> querySmokeCell(Level level, int x, int y, int z,
                                                EntityType<?> smokeType) {
        double minX = x * CELL_SIZE;
        double minY = y * CELL_SIZE;
        double minZ = z * CELL_SIZE;
        AABB cellBounds = new AABB(minX, minY, minZ,
            minX + CELL_SIZE, minY + CELL_SIZE, minZ + CELL_SIZE)
            .inflate(ENTITY_CELL_MARGIN);
        return List.copyOf(new ArrayList<>(level.getEntities(
            (Entity) null, cellBounds,
            entity -> entity.getType() == smokeType && entity.isAlive())));
    }

    private static int floorCell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private record CellKey(int x, int y, int z, int radius) {}

    private record SmokeCellKey(int x, int y, int z, EntityType<?> type) {}

    private record VisibilityKey(int observerId, Vec3 from, Vec3 to,
                                 VisibilityRay.SmokePolicy smokePolicy,
                                 Set<net.minecraft.core.BlockPos> ignoredBlocks) {}

    private static final class Frame {
        private final long tick;
        private final Map<CellKey, List<LivingEntity>> livingQueries = new HashMap<>();
        private final Map<SmokeCellKey, List<Entity>> smokeCells = new HashMap<>();
        private final Map<VisibilityKey, VisibilityRay.Result> visibilityResults = new HashMap<>();

        private Frame(long tick) {
            this.tick = tick;
        }
    }
}

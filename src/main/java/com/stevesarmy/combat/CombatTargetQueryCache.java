package com.stevesarmy.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shares broad-phase living-entity queries between nearby soldiers. Faction,
 * target-type, and exact-range filtering remains observer-specific.
 */
public final class CombatTargetQueryCache {
    private static final int CELL_SIZE = 16;
    private static final int MAX_ENTRIES = 2048;
    private static final Map<Level, LevelCache> CACHES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private CombatTargetQueryCache() {}

    public static List<LivingEntity> getNearbyLivingEntities(Level level, Vec3 center,
                                                              double radius, int ttlTicks) {
        if (ttlTicks <= 0) {
            return query(level, center, radius, 0);
        }

        long gameTime = level.getGameTime();
        CellKey cell = new CellKey(
            floorCell(center.x), floorCell(center.y), floorCell(center.z),
            (int) Math.ceil(radius));

        LevelCache cache;
        synchronized (CACHES) {
            cache = CACHES.computeIfAbsent(level, ignored -> new LevelCache());
        }

        synchronized (cache) {
            cache.entries.entrySet().removeIf(entry -> entry.getValue().expiresAt < gameTime);
            Entry cached = cache.entries.get(cell);
            if (cached != null) {
                return cached.entities;
            }

            if (cache.entries.size() >= MAX_ENTRIES) {
                cache.entries.clear();
            }

            Entry fresh = new Entry(
                query(level, center, radius, ttlTicks),
                gameTime + Math.max(1, ttlTicks) - 1);
            cache.entries.put(cell, fresh);
            return fresh.entities;
        }
    }

    public static void invalidate(Level level) {
        synchronized (CACHES) {
            CACHES.remove(level);
        }
    }

    private static List<LivingEntity> query(Level level, Vec3 center, double radius, int ttlTicks) {
        int cellX = floorCell(center.x);
        int cellY = floorCell(center.y);
        int cellZ = floorCell(center.z);
        double minX = cellX * CELL_SIZE;
        double minY = cellY * CELL_SIZE;
        double minZ = cellZ * CELL_SIZE;
        AABB cellBounds = new AABB(
            minX, minY, minZ,
            minX + CELL_SIZE, minY + CELL_SIZE, minZ + CELL_SIZE);

        // Cover movement between refreshes so a target entering the exact search
        // box is not missed while the broad-phase snapshot is still valid.
        double movementMargin = Math.max(1.0, Math.min(20.0, ttlTicks * 1.5));
        List<LivingEntity> result = level.getEntitiesOfClass(
            LivingEntity.class, cellBounds.inflate(radius + movementMargin));
        return List.copyOf(new ArrayList<>(result));
    }

    private static int floorCell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private record CellKey(int x, int y, int z, int radius) {}

    private static final class Entry {
        private final List<LivingEntity> entities;
        private final long expiresAt;

        private Entry(List<LivingEntity> entities, long expiresAt) {
            this.entities = entities;
            this.expiresAt = expiresAt;
        }
    }

    private static final class LevelCache {
        private final Map<CellKey, Entry> entries = new HashMap<>();
    }
}

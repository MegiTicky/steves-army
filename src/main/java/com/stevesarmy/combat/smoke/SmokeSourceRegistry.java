package com.stevesarmy.combat.smoke;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Central registry for smoke sources. Adapters for each supported smoke mod
 * are registered here. All smoke detection flows through this facade.
 *
 * <p>Tracks per-level active-smoke counts so that {@link #findSmokeIntersections}
 * can short-circuit when no smoke entities exist in the level, avoiding
 * millions of pointless entity queries per session.</p>
 */
public final class SmokeSourceRegistry {

    private static final List<SmokeSource> SOURCES = new ArrayList<>();
    private static final Map<Level, Integer> ACTIVE_SMOKE_COUNTS =
        new WeakHashMap<>();

    private SmokeSourceRegistry() {}

    static {
        SOURCES.add(new CbcSmokeSource());
        SOURCES.add(new LrtacticalSmokeSource());
    }

    /** Returns all registered smoke sources (unmodifiable view). */
    public static List<SmokeSource> getSources() {
        return List.copyOf(SOURCES);
    }

    /** Returns true if any registered source can handle the entity. */
    public static boolean isSmokeEntity(Entity entity) {
        for (SmokeSource source : SOURCES) {
            if (source.isSmokeEntity(entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the effective smoke bounding box for the given entity.
     * The caller must have already verified {@link #isSmokeEntity(Entity)}.
     */
    public static AABB getSmokeBounds(Entity entity) {
        for (SmokeSource source : SOURCES) {
            if (source.isSmokeEntity(entity)) {
                return source.getSmokeBounds(entity);
            }
        }
        return entity.getBoundingBox();
    }

    /** Increments the active smoke count for the level. */
    public static void onSmokeEntityJoin(Level level) {
        synchronized (ACTIVE_SMOKE_COUNTS) {
            ACTIVE_SMOKE_COUNTS.merge(level, 1, Integer::sum);
        }
    }

    /** Decrements the active smoke count for the level. */
    public static void onSmokeEntityLeave(Level level) {
        synchronized (ACTIVE_SMOKE_COUNTS) {
            ACTIVE_SMOKE_COUNTS.merge(level, -1, Integer::sum);
        }
    }

    /** Returns true if any smoke entities are active in the level. */
    public static boolean hasActiveSmoke(Level level) {
        synchronized (ACTIVE_SMOKE_COUNTS) {
            return ACTIVE_SMOKE_COUNTS.getOrDefault(level, 0) > 0;
        }
    }

    /**
     * Finds all smoke-producing entities within the given search bounds across
     * all registered sources. Each source's {@link SmokeSource#findSmokeEntities}
     * is called with the source-specific inflated bounds.
     */
    public static List<Entity> findSmokeEntities(Level level, AABB searchBounds) {
        List<Entity> all = new ArrayList<>();
        for (SmokeSource source : SOURCES) {
            all.addAll(source.findSmokeEntities(level, searchBounds));
        }
        return all;
    }

    /**
     * Tests ray intersection against all smoke sources. Returns the nearest
     * smoke entry distance, or -1 if no smoke blocks the ray.
     */
    public static double findSmokeIntersection(Level level, Vec3 from, Vec3 to) {
        double nearest = Double.POSITIVE_INFINITY;
        for (SmokeSource source : SOURCES) {
            double entry = findSmokeInSource(source, level, from, to);
            if (entry >= 0 && entry < nearest) {
                nearest = entry;
            }
        }
        return nearest < Double.POSITIVE_INFINITY ? nearest : -1;
    }

    private static double findSmokeInSource(SmokeSource source, Level level,
                                             Vec3 from, Vec3 to) {
        // Use a search AABB from the ray, inflated enough to catch nearby smoke.
        AABB searchBounds = new AABB(from, to).inflate(6.0);
        List<? extends Entity> clouds = source.findSmokeEntities(level, searchBounds);
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity cloud : clouds) {
            AABB bounds = source.getSmokeBounds(cloud);
            var hit = bounds.clip(from, to);
            if (hit.isPresent()) {
                double dist = from.distanceTo(hit.get());
                if (dist < nearest) {
                    nearest = dist;
                }
            }
        }
        return nearest < Double.POSITIVE_INFINITY ? nearest : -1;
    }

    /** Resets all source caches. Called on level unload. */
    public static void reset() {
        for (SmokeSource source : SOURCES) {
            if (source instanceof CbcSmokeSource cbc) {
                cbc.reset();
            } else if (source instanceof LrtacticalSmokeSource lr) {
                lr.reset();
            }
        }
        synchronized (ACTIVE_SMOKE_COUNTS) {
            ACTIVE_SMOKE_COUNTS.clear();
        }
    }
}

package com.stevesarmy.combat.smoke;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Adapter interface for smoke-producing entities from different mods.
 * Each implementation knows how to detect its mod's smoke entities and
 * provide an effective bounding box for ray-intersection testing.
 */
public interface SmokeSource {

    /** True if the entity is a smoke source managed by this adapter. */
    boolean isSmokeEntity(Entity entity);

    /**
     * Returns the effective smoke volume for the given entity.
     * May be larger than the entity's own bounding box (e.g., for
     * grenade-based smoke where the cloud extends beyond the item).
     */
    AABB getSmokeBounds(Entity entity);

    /**
     * Queries the level for smoke entities within the given search bounds.
     * Called during visibility ray-tracing to find candidate smoke clouds.
     */
    List<? extends Entity> findSmokeEntities(Level level, AABB searchBounds);
}

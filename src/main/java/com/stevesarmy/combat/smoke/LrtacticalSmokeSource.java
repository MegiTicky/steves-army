package com.stevesarmy.combat.smoke;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Smoke source adapter for LesRaisins Tactical Equipment smoke grenades.
 * LesRaisins smoke is particle-based with no server-side cloud entity, so
 * we synthesize a smoke volume from the grenade entity's position using
 * the known particle spread dimensions (5.5 / 4.5 / 5.5 blocks).
 */
public class LrtacticalSmokeSource implements SmokeSource {

    private static final ResourceLocation SMOKE_GRENADE_ID =
        new ResourceLocation("lrtactical", "smoke_grenade");

    /** Approximate half-extents of the LesRaisins smoke particle cloud. */
    private static final double HALF_EXTENT_X = 5.5;
    private static final double HALF_EXTENT_Y = 4.5;
    private static final double HALF_EXTENT_Z = 5.5;

    private EntityType<?> resolvedType;
    private boolean typeResolved;

    @Override
    public boolean isSmokeEntity(Entity entity) {
        EntityType<?> type = getEntityType();
        return type != null && entity.getType() == type;
    }

    @Override
    public AABB getSmokeBounds(Entity entity) {
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        return new AABB(
            x - HALF_EXTENT_X, y - HALF_EXTENT_Y, z - HALF_EXTENT_Z,
            x + HALF_EXTENT_X, y + HALF_EXTENT_Y, z + HALF_EXTENT_Z);
    }

    @Override
    public List<? extends Entity> findSmokeEntities(Level level, AABB searchBounds) {
        EntityType<?> type = getEntityType();
        if (type == null) {
            return List.of();
        }
        return level.getEntities((Entity) null, searchBounds,
            e -> e.getType() == type && e.isAlive());
    }

    public EntityType<?> getEntityType() {
        if (!typeResolved) {
            typeResolved = true;
            EntityType<?> resolved = BuiltInRegistries.ENTITY_TYPE.get(SMOKE_GRENADE_ID);
            if (resolved != null && resolved != BuiltInRegistries.ENTITY_TYPE.get(
                    new ResourceLocation("air"))) {
                resolvedType = resolved;
            }
        }
        return resolvedType;
    }

    public void reset() {
        typeResolved = false;
        resolvedType = null;
    }
}

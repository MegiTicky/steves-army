package com.stevesarmy.combat.smoke;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Smoke source adapter for Create Big Cannons smoke emitters.
 * CBC smoke entities use a dynamic synched bounding box that grows and merges.
 */
public class CbcSmokeSource implements SmokeSource {

    private static final ResourceLocation SMOKE_EMITTER_ID =
        new ResourceLocation("createbigcannons", "smoke_emitter");

    /** Padding in blocks added around each emitter AABB to close micro-gaps. */
    private static final double BOUNDS_PADDING = 1.0;

    private EntityType<?> resolvedType;
    private boolean typeResolved;

    @Override
    public boolean isSmokeEntity(Entity entity) {
        EntityType<?> type = getEntityType();
        return type != null && entity.getType() == type;
    }

    @Override
    public AABB getSmokeBounds(Entity entity) {
        return entity.getBoundingBox().inflate(BOUNDS_PADDING);
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
            EntityType<?> resolved = BuiltInRegistries.ENTITY_TYPE.get(SMOKE_EMITTER_ID);
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

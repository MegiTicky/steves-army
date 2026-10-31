package com.stevesarmy.combat.smoke;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwReflection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Smoke source adapter for Superb Warfare M18 smoke grenades.
 * Synthesizes a smoke volume from the M18 smoke grenade entity's position
 * so VisibilityRay can trace line-of-sight blockage through SBW smoke.
 */
public class SbwSmokeSource implements SmokeSource {

    private static final String NAMESPACE = "superbwarfare";
    private static final String ENTITY_CLASS_NAME =
        "com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity";

    private static final ResourceLocation[] CANDIDATE_IDS = new ResourceLocation[] {
        new ResourceLocation(NAMESPACE, "m18_smoke_grenade"),
        new ResourceLocation(NAMESPACE, "m18"),
        new ResourceLocation(NAMESPACE, "smoke_grenade")
    };

    /** Approximate half-extents of the SBW smoke cloud. */
    private static final double HALF_EXTENT_X = 5.5;
    private static final double HALF_EXTENT_Y = 4.5;
    private static final double HALF_EXTENT_Z = 5.5;

    private Class<?> entityClass;
    private EntityType<?> resolvedType;
    private boolean initialized;

    @Override
    public boolean isSmokeEntity(Entity entity) {
        if (entity == null || !SbwCompat.isLoaded()) {
            return false;
        }
        ensureInit();
        if (entityClass != null && entityClass.isInstance(entity)) {
            return true;
        }
        if (resolvedType != null && entity.getType() == resolvedType) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && NAMESPACE.equals(key.getNamespace()) && key.getPath().contains("smoke");
    }

    @Override
    public AABB getSmokeBounds(Entity entity) {
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        return new AABB(
            x - HALF_EXTENT_X, y - HALF_EXTENT_Y, z - HALF_EXTENT_Z,
            x + HALF_EXTENT_X, y + HALF_EXTENT_Y, z + HALF_EXTENT_Z
        );
    }

    @Override
    public List<? extends Entity> findSmokeEntities(Level level, AABB searchBounds) {
        if (!SbwCompat.isLoaded()) {
            return List.of();
        }
        return level.getEntities((Entity) null, searchBounds,
            e -> e.isAlive() && isSmokeEntity(e));
    }

    private void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        entityClass = SbwReflection.cls(ENTITY_CLASS_NAME);
        for (ResourceLocation id : CANDIDATE_IDS) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type != null && type != BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation("air"))) {
                resolvedType = type;
                break;
            }
        }
    }

    public void reset() {
        initialized = false;
        entityClass = null;
        resolvedType = null;
    }
}
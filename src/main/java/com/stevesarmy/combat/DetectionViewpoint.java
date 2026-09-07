package com.stevesarmy.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional detection-sensor override for a soldier. The vehicle crew goal points
 * it at the hull MG / periscope camera the soldier is manning, so line-of-sight,
 * exposure, and arc checks run from the optic instead of the soldier's own eyes.
 * Code paths without an override keep the entity's eye position and head yaw,
 * so infantry behavior is unchanged.
 */
public final class DetectionViewpoint {
    private static final Map<Integer, Viewpoint> overrides = new ConcurrentHashMap<>();

    public record Viewpoint(Vec3 position, Vec3 look) {}

    private DetectionViewpoint() {}

    public static void set(LivingEntity observer, Vec3 position, Vec3 look) {
        overrides.put(observer.getId(), new Viewpoint(position, look));
    }

    public static void clear(LivingEntity observer) {
        overrides.remove(observer.getId());
    }

    public static boolean hasOverride(LivingEntity observer) {
        return overrides.containsKey(observer.getId());
    }

    public static Vec3 getEyePosition(LivingEntity observer) {
        Viewpoint viewpoint = overrides.get(observer.getId());
        return viewpoint != null ? viewpoint.position : observer.getEyePosition();
    }

    public static Vec3 getLook(LivingEntity observer) {
        Viewpoint viewpoint = overrides.get(observer.getId());
        return viewpoint != null ? viewpoint.look : observer.getLookAngle();
    }
}

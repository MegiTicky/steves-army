package com.stevesarmy.compat.sbw;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.List;
import java.util.Optional;

/** Segment tests against SBW's world-space projectile hitboxes, without importing SBW classes. */
public final class SbwVehicleOcclusion {
    private SbwVehicleOcclusion() {}

    /** Nearest hit on any vehicle along the segment, excluding the observer's own mount. */
    public static double nearest(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        if (!SbwCompat.hasVehicles()) return Double.POSITIVE_INFINITY;
        AABB area = new AABB(from, to).inflate(0.25);
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity vehicle : level.getEntitiesOfClass(Entity.class, area,
            e -> SbwCompat.isVehicle(e) && !SbwVehicles.isDrone(e) && !e.isRemoved())) {
            if (observer != null && vehicle == observer.getVehicle()) continue;
            nearest = Math.min(nearest, hitDistance(vehicle, from, to));
        }
        return nearest;
    }

    /** Match SBW projectiles: prefer part OBBs, with collision OBB/AABB fallback. */
    public static double hitDistance(Entity vehicle, Vec3 from, Vec3 to) {
        Object useBox = SbwReflection.call(vehicle, new String[]{"enableAABB"});
        Object boxes = SbwReflection.call(vehicle, new String[]{"getOBBs"});
        if (!Boolean.TRUE.equals(useBox) && boxes instanceof List<?> list && !list.isEmpty()) {
            Vector3d start = new Vector3d(from.x, from.y, from.z);
            Vector3d end = new Vector3d(to.x, to.y, to.z);
            double partHit = Double.POSITIVE_INFINITY;
            double collisionHit = Double.POSITIVE_INFINITY;
            boolean supported = false;
            for (Object obb : list) {
                Object result = SbwReflection.call(obb, new String[]{"clip"}, start, end);
                if (!(result instanceof Optional<?> optional)) continue;
                supported = true;
                if (optional.isEmpty() || !(optional.get() instanceof Vector3d point)) continue;
                double distance = from.distanceTo(new Vec3(point.x, point.y, point.z));
                Object part = SbwReflection.get(obb, "part", "getPart");
                if (part instanceof Enum<?> type && "COLLISION".equals(type.name())) {
                    collisionHit = Math.min(collisionHit, distance);
                } else {
                    partHit = Math.min(partHit, distance);
                }
            }
            if (supported) return Double.isFinite(partHit) ? partHit : collisionHit;
        }
        AABB box = vehicle.getBoundingBox();
        if (box.contains(from)) return 0.0;
        return box.clip(from, to).map(from::distanceTo).orElse(Double.POSITIVE_INFINITY);
    }

    /** An aim point just before this particular vehicle's first surface, if visible. */
    public static Vec3 visibleSurface(Level level, Vec3 from, Vec3 inside, Entity vehicle, LivingEntity observer) {
        double distance = hitDistance(vehicle, from, inside);
        double length = from.distanceTo(inside);
        if (!Double.isFinite(distance) || length < 1.0e-6 || distance < 0.1 || distance > length) return null;
        Vec3 surface = from.add(inside.subtract(from).scale(Math.max(0.0, distance - 0.06) / length));
        return com.stevesarmy.combat.VisibilityRay.traceFresh(level, from, surface, observer).hasContact()
            ? surface : null;
    }
}

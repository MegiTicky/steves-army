package com.stevesarmy.combat;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A thing a soldier can shoot at: either a {@link LivingEntity} or a damageable
 * vehicle. Combat code aims at {@link #aimPoint()} without needing to know which.
 */
public final class CombatTarget {

    public enum Kind { LIVING, VEHICLE }

    private final Kind kind;
    private final LivingEntity living;
    private final Entity vehicle;

    private CombatTarget(Kind kind, LivingEntity living, Entity vehicle) {
        this.kind = kind;
        this.living = living;
        this.vehicle = vehicle;
    }

    public static CombatTarget of(LivingEntity living) {
        return living == null ? null : new CombatTarget(Kind.LIVING, living, null);
    }

    public static CombatTarget ofVehicle(Entity vehicle) {
        return vehicle == null ? null : new CombatTarget(Kind.VEHICLE, null, vehicle);
    }

    public static CombatTarget wrap(Entity entity) {
        if (entity == null) return null;
        if (SbwCompat.isVehicle(entity)) return ofVehicle(entity);
        if (entity instanceof LivingEntity le) return of(le);
        return null;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isVehicle() {
        return kind == Kind.VEHICLE;
    }

    public boolean isLiving() {
        return kind == Kind.LIVING;
    }

    public LivingEntity living() {
        return living;
    }

    public Entity entity() {
        return kind == Kind.LIVING ? living : vehicle;
    }

    public UUID uuid() {
        Entity e = entity();
        return e == null ? null : e.getUUID();
    }

    public int id() {
        Entity e = entity();
        return e == null ? -1 : e.getId();
    }

    public boolean isValid() {
        Entity e = entity();
        if (e == null || e.isRemoved()) return false;
        if (kind == Kind.LIVING) return living.isAlive();
        return !SbwVehicles.isWrecked(vehicle);
    }

    public Vec3 position() {
        Entity e = entity();
        return e == null ? Vec3.ZERO : e.position();
    }

    public AABB boundingBox() {
        Entity e = entity();
        return e == null ? new AABB(Vec3.ZERO, Vec3.ZERO) : e.getBoundingBox();
    }

    public Vec3 aimPoint() {
        Entity e = entity();
        if (e == null) return Vec3.ZERO;
        if (kind == Kind.LIVING) return living.getEyePosition();
        AABB box = e.getBoundingBox();
        double y = box.minY + (box.getYsize() * 0.6);
        return new Vec3(box.getCenter().x, y, box.getCenter().z);
    }

    public double distanceToSqr(Entity from) {
        Entity e = entity();
        return e == null ? Double.MAX_VALUE : from.distanceToSqr(e);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CombatTarget other)) return false;
        return kind == other.kind && java.util.Objects.equals(uuid(), other.uuid());
    }

    @Override
    public int hashCode() {
        UUID u = uuid();
        return 31 * kind.hashCode() + (u == null ? 0 : u.hashCode());
    }

    @Override
    public String toString() {
        return "CombatTarget[" + kind + " " + (entity() == null ? "null" : entity().getName().getString()) + "]";
    }
}
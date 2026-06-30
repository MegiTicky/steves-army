package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import java.util.Set;

public class CoverPoint {
    private final BlockPos position;
    private float quality;
    private CoverType type;
    private Set<Direction> protectedDirections;
    private boolean canShootFrom;
    private float coverHeight;
    private LivingEntity reservedBy;
    private String debugInfo;

    public CoverPoint(BlockPos position) {
        this.position = position;
        this.quality = 0.0f;
        this.type = CoverType.NONE;
        this.protectedDirections = Set.of();
        this.canShootFrom = false;
        this.coverHeight = 0.0f;
        this.reservedBy = null;
        this.debugInfo = "";
    }

    public BlockPos getPosition() {
        return position;
    }

    public float getQuality() {
        return quality;
    }

    public void setQuality(float quality) {
        this.quality = Math.max(0.0f, Math.min(1.0f, quality));
    }

    public CoverType getType() {
        return type;
    }

    public void setType(CoverType type) {
        this.type = type;
    }

    public Set<Direction> getProtectedDirections() {
        return protectedDirections;
    }

    public void setProtectedDirections(Set<Direction> directions) {
        this.protectedDirections = directions;
    }

    public boolean canShootFrom() {
        return canShootFrom;
    }

    public void setCanShootFrom(boolean canShoot) {
        this.canShootFrom = canShoot;
    }

    public float getCoverHeight() {
        return coverHeight;
    }

    public void setCoverHeight(float height) {
        this.coverHeight = height;
    }

    public LivingEntity getReservedBy() {
        return reservedBy;
    }

    public void setReservedBy(LivingEntity entity) {
        this.reservedBy = entity;
    }

    public boolean isReserved() {
        return reservedBy != null && reservedBy.isAlive();
    }

    public boolean isReservedBy(LivingEntity entity) {
        return reservedBy != null && reservedBy.equals(entity);
    }

    public double distanceTo(LivingEntity entity) {
        return position.distSqr(entity.blockPosition());
    }

    public double distanceTo(BlockPos other) {
        return position.distSqr(other);
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(String debugInfo) {
        this.debugInfo = debugInfo;
    }
}
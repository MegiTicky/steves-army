package com.stevesarmy.entity.ai.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/** Immutable mob inputs used by the worker-side node evaluator. */
final class PathfindingMobSnapshot extends Mob {
    private final BlockPos sourcePosition;
    private final Vec3 sourceVector;
    private final Map<BlockPathTypes, Float> pathfindingMalus;
    private final int maxFallDistance;
    private final float stepHeight;

    private PathfindingMobSnapshot(Mob source) {
        // Zombie dimensions produce the same integer evaluator dimensions as soldiers.
        super(EntityType.ZOMBIE, source.level());
        this.sourcePosition = source.blockPosition().immutable();
        this.sourceVector = source.position();
        this.pathfindingMalus = new EnumMap<>(BlockPathTypes.class);
        for (BlockPathTypes type : BlockPathTypes.values()) {
            this.pathfindingMalus.put(type, source.getPathfindingMalus(type));
        }
        this.maxFallDistance = source.getMaxFallDistance();
        this.stepHeight = source.getStepHeight();
        this.setPos(sourceVector);
    }

    static PathfindingMobSnapshot capture(Mob source) {
        return new PathfindingMobSnapshot(source);
    }

    @Override
    public BlockPos blockPosition() {
        return sourcePosition;
    }

    @Override
    public Vec3 position() {
        return sourceVector;
    }

    @Override
    public float getPathfindingMalus(BlockPathTypes type) {
        return pathfindingMalus.getOrDefault(type, 0.0F);
    }

    @Override
    public int getMaxFallDistance() {
        return maxFallDistance;
    }

    @Override
    public float getStepHeight() {
        return stepHeight;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
    }
}

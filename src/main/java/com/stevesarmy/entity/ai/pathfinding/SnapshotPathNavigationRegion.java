package com.stevesarmy.entity.ai.pathfinding;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Main-thread capture of the block states read by an async path search. Only non-air
 * states are stored; missing entries are immutable air and never consult the live world.
 */
final class SnapshotPathNavigationRegion extends PathNavigationRegion {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<BlockState> states;
    private final int minBuildHeight;
    private final int height;
    private final WorldBorder border;

    private SnapshotPathNavigationRegion(Level level, BlockPos min, BlockPos max,
                                         Long2ObjectOpenHashMap<BlockState> states) {
        super(level, min, max);
        this.states = states;
        this.minBuildHeight = level.getMinBuildHeight();
        this.height = level.getHeight();
        this.border = new WorldBorder();
    }

    static SnapshotPathNavigationRegion capture(Level level, PathNavigationRegion source,
                                                Mob mob, float maxDistance, int reachRange) {
        int radius = Math.max(1, (int) (maxDistance + reachRange));
        BlockPos center = mob.blockPosition();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();

        int minY = Math.max(level.getMinBuildHeight(), min.getY());
        int maxY = Math.min(level.getMaxBuildHeight() - 1, max.getY());
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState state = source.getBlockState(position);
                    if (!state.isAir()) {
                        states.put(position.asLong(), state);
                    }
                }
            }
        }
        SnapshotPathNavigationRegion snapshot = new SnapshotPathNavigationRegion(level, min, max, states);
        snapshot.border.applySettings(level.getWorldBorder().createSettings());
        return snapshot;
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        return states.getOrDefault(position.asLong(), AIR);
    }

    @Override
    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos position) {
        BlockState state = states.get(position.asLong());
        return state == null ? Fluids.EMPTY.defaultFluidState() : state.getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos position) {
        return null;
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
        return List.of();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    @Override
    public int getHeight() {
        return height;
    }
}

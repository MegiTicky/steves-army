package com.stevesarmy.sim.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic terrain fixture built from real blocks so the real VisibilityRay
 * DDA, real pathfinding, and real cover classification operate on real geometry.
 * Every placed block is tracked so the scenario cleanup can remove exactly what
 * it built.
 */
public final class SimTerrain {
    public final BlockPos origin;
    public final int width;
    public final int depth;
    private final ServerLevel level;
    private final List<BlockPos> placed = new ArrayList<>();

    public SimTerrain(ServerLevel level, BlockPos origin, int width, int depth) {
        this.level = level;
        this.origin = origin;
        this.width = width;
        this.depth = depth;
    }

    /** Solid slab the scenario plays on, ringed by a 3-high rim so actors can't path off the edge. */
    public void platform() {
        fill(origin.offset(-1, -1, -1), origin.offset(width + 1, -1, depth + 1), Blocks.SMOOTH_STONE.defaultBlockState());
        wall(-1, -1, width + 1, -1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(-1, depth + 1, width + 1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(-1, -1, -1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(width + 1, -1, width + 1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** Ground-level box wall: x1..x2 × z1..z2 footprint, h tall, hollow opening-free. */
    public void wall(int x1, int z1, int x2, int z2, int h, BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = 0; y < h; y++) {
                    block(x, y, z, state);
                }
            }
        }
    }

    /** Three-sided pocket open toward openDir ("N"=-z, "S"=+z, "W"=-x, "E"=+x). */
    public void pocket(int cx, int cz, int half, int h, String openDir) {
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                boolean edge = x == cx - half || x == cx + half || z == cz - half || z == cz + half;
                if (!edge) continue;
                if ("N".equals(openDir) && z == cz - half) continue;
                if ("S".equals(openDir) && z == cz + half) continue;
                if ("W".equals(openDir) && x == cx - half) continue;
                if ("E".equals(openDir) && x == cx + half) continue;
                for (int y = 0; y < h; y++) block(x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    /** Hard-cover blocks in a row (waist-high wall of full blocks). */
    public void coverRow(int x1, int z1, int x2, int z2, int h) {
        wall(x1, z1, x2, z2, h, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** Single hard-cover stack (h tall) at one cell. */
    public void coverBlock(int x, int z, int h) {
        wall(x, z, x, z, h, Blocks.STONE_BRICKS.defaultBlockState());
    }

    public void block(int x, int y, int z, BlockState state) {
        BlockPos pos = origin.offset(x, y, z);
        // The world silently rejects blocks above the build limit; surface that
        // here because a rejected cover block turns a scenario into an
        // un diagnosable empty-air run.
        if (pos.getY() > level.getMaxBuildHeight()) {
            throw new IllegalStateException("Sim fixture block above build limit at " + pos
                + " — lower the scenario yLevel (SimManager Y_SEPARATION/Y_BASE)");
        }
        level.setBlockAndUpdate(pos, state);
        placed.add(pos);
    }

    public void fill(BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            block(pos.getX() - origin.getX(), pos.getY() - origin.getY(),
                pos.getZ() - origin.getZ(), state);
        }
    }

    /** World position for a fixture-local coordinate (y=0 is standing surface). */
    public BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    /** Blocks placed so far (used by the recording height map). */
    public List<BlockPos> placedBlocks() {
        return placed;
    }

    /** Removes exactly the blocks this fixture placed. */
    public void clear() {
        for (BlockPos pos : placed) {
            level.removeBlock(pos, false);
        }
        placed.clear();
    }
}

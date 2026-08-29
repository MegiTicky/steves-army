package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.util.HazardBlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Custom node evaluator that returns BLOCKED for any block position
 * matching a configured hazard block, a partial obstacle, or intersecting
 * a VS2 ship bounding box, preventing A* path generation from routing
 * soldiers through these obstacles.
 */
public class HazardAwareWalkNodeEvaluator extends WalkNodeEvaluator {

    private static final double PARTIAL_BLOCK_THRESHOLD = 6.0 / 16.0;
    private final boolean asyncSafe;

    public HazardAwareWalkNodeEvaluator() {
        this(false);
    }

    /** Async evaluators omit live VS2 queries; navigation validates VS2 on the server thread. */
    public HazardAwareWalkNodeEvaluator(boolean asyncSafe) {
        super();
        this.asyncSafe = asyncSafe;
    }

    /**
     * Called by the pathfinder during A* neighbor evaluation.
     * Delegates to the parent, then overrides to BLOCKED if the position
     * is a configured hazard block, a partial obstacle, or inside a VS2 ship.
     */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        BlockPathTypes type = super.getBlockPathType(level, x, y, z, mob);
        if (type != BlockPathTypes.BLOCKED && HazardBlockHelper.isHazardBlockAt(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        if (type != BlockPathTypes.BLOCKED && isPartialObstacle(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        if (!asyncSafe && type != BlockPathTypes.BLOCKED
            && VS2Compat.isNodeBlockedByShip(mob.level(), new BlockPos(x, y, z), mob)) {
            return BlockPathTypes.BLOCKED;
        }
        return type;
    }

    /**
     * Called during floor-level and neighbor-block checks. If the block
     * at this position is a hazard or a partial obstacle, return BLOCKED
     * so the pathfinder doesn't treat it as a valid floor tile.
     */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        if (HazardBlockHelper.isHazardBlockAt(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        if (isPartialObstacle(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        return super.getBlockPathType(level, x, y, z);
    }

    /**
     * True if the block at (x, y, z) has a collision shape with height
     * between PARTIAL_BLOCK_THRESHOLD and 1.0 (exclusive). These are
     * partial blocks like Copycat layers (>= 3 layers) that the
     * pathfinder incorrectly treats as walkable, causing soldiers to
     * get stuck trying to jump over them.
     */
    private static boolean isPartialObstacle(BlockGetter level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        VoxelShape shape = state.getCollisionShape(level, new BlockPos(x, y, z));
        if (!shape.isEmpty()) {
            double maxY = shape.max(Direction.Axis.Y);
            if (maxY >= PARTIAL_BLOCK_THRESHOLD && maxY < 1.0) {
                return true;
            }
        }
        return false;
    }
}

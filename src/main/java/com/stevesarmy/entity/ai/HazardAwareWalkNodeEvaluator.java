package com.stevesarmy.entity.ai;

import com.stevesarmy.util.HazardBlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Custom node evaluator that returns BLOCKED for any block position
 * matching a configured hazard block, preventing A* path generation from
 * routing soldiers through hazard blocks.
 */
public class HazardAwareWalkNodeEvaluator extends WalkNodeEvaluator {

    public HazardAwareWalkNodeEvaluator() {
        super();
    }

    /**
     * Called by the pathfinder during A* neighbor evaluation.
     * Delegates to the parent, then overrides to BLOCKED if the position
     * is a configured hazard block.
     */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        BlockPathTypes type = super.getBlockPathType(level, x, y, z, mob);
        if (type != BlockPathTypes.BLOCKED && HazardBlockHelper.isHazardBlockAt(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        return type;
    }

    /**
     * Called during floor-level and neighbor-block checks. If the block
     * at this position is a hazard, return BLOCKED so the pathfinder
     * doesn't treat it as a valid floor tile.
     */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        if (HazardBlockHelper.isHazardBlockAt(level, x, y, z)) {
            return BlockPathTypes.BLOCKED;
        }
        return super.getBlockPathType(level, x, y, z);
    }
}
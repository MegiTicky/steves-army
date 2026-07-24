package com.stevesarmy.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

public class SoldierGroundNavigation extends GroundPathNavigation {

    public SoldierGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setCanOpenDoors(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new HazardAwareWalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    public Path createPathToBlock(BlockPos target, int accuracy) {
        return this.createPath(target, accuracy);
    }
}
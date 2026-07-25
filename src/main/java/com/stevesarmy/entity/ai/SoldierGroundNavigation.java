package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.VS2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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

    @Override
    public boolean moveTo(double x, double y, double z, double speedModifier) {
        if (VS2Compat.shouldRejectNavigation(this.level, BlockPos.containing(x, y, z))) {
            this.stop();
            return false;
        }
        return super.moveTo(x, y, z, speedModifier);
    }

    @Override
    public boolean moveTo(Entity entity, double speedModifier) {
        if (VS2Compat.shouldRejectNavigation(entity)) {
            this.stop();
            return false;
        }
        return super.moveTo(entity, speedModifier);
    }

    @Override
    public boolean moveTo(Path path, double speedModifier) {
        if (path != null) {
            for (int index = 0; index < path.getNodeCount(); index++) {
                if (VS2Compat.shouldRejectNavigation(this.level, path.getNode(index).asBlockPos())) {
                    this.stop();
                    return false;
                }
            }
        }
        return super.moveTo(path, speedModifier);
    }
}

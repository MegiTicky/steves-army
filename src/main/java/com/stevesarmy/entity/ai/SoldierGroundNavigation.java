package com.stevesarmy.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

public class SoldierGroundNavigation extends GroundPathNavigation {

    public SoldierGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setCanOpenDoors(true);
    }

    public Path createPathToBlock(BlockPos target, int accuracy) {
        return this.createPath(target, accuracy);
    }
}
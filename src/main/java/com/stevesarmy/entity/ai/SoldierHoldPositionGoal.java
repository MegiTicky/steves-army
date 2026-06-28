package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierHoldPositionGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos holdPos;
    private final double speedModifier;
    private final float closeDistance;

    public SoldierHoldPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.0D;
        this.closeDistance = 2.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }
        
        this.holdPos = soldier.getHoldPosition();
        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return false;
        }
        
        return soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ()) > (closeDistance * closeDistance);
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }
        return soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ()) > (closeDistance * closeDistance);
    }

    @Override
    public void start() {
        soldier.getNavigation().moveTo(holdPos.getX(), holdPos.getY(), holdPos.getZ(), speedModifier);
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ()) > (closeDistance * closeDistance)) {
            soldier.getNavigation().moveTo(holdPos.getX(), holdPos.getY(), holdPos.getZ(), speedModifier);
        } else {
            soldier.getNavigation().stop();
        }
    }
}
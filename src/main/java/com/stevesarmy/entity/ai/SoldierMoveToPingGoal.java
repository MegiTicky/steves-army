package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierMoveToPingGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos targetPos;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;

    public SoldierMoveToPingGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.2D;
        this.closeDistance = 2.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        
        this.targetPos = soldier.getPingMoveTarget();
        boolean result = targetPos != null;
        com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] canUse={}, targetPos={}", result, targetPos);
        return result;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        
        return soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ()) > (closeDistance * closeDistance);
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        boolean ok = soldier.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier);
        com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] start: nav.moveTo result={}", ok);
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        
        soldier.clearPingMoveTarget();
        targetPos = null;
    }

    @Override
    public void tick() {
        soldier.getLookControl().setLookAt(
            targetPos.getX() + 0.5,
            targetPos.getY() + 1.0,
            targetPos.getZ() + 0.5,
            30.0F, 30.0F
        );
        
        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = adjustTicks(10);
            
            double distance = soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (distance > closeDistance * closeDistance) {
                soldier.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier);
                
                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.clearPingMoveTarget();
                soldier.getNavigation().stop();
            }
        }
    }

    private int adjustTicks(int ticks) {
        return ticks;
    }
}
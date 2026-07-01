package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

public class SoldierStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final SoldierEntity soldier;

    public SoldierStrollGoal(SoldierEntity soldier, double speedModifier) {
        super(soldier, speedModifier);
        this.soldier = soldier;
    }

    @Override
    public boolean canUse() {
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return false;
        }
        
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return false;
        }
        
        if (soldier.getCombatGoal() != null && soldier.getCombatGoal().hasDetectedTargets()) {
            return false;
        }
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            var holdPos = soldier.getHoldPosition();
            if (holdPos != null && !holdPos.equals(net.minecraft.core.BlockPos.ZERO)) {
                if (soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ()) < 100.0) {
                    return false;
                }
            }
        }
        
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return false;
        }
        
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return false;
        }
        
        if (soldier.getCombatGoal() != null && soldier.getCombatGoal().hasDetectedTargets()) {
            return false;
        }
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            var holdPos = soldier.getHoldPosition();
            if (holdPos != null && !holdPos.equals(net.minecraft.core.BlockPos.ZERO)) {
                if (soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ()) < 100.0) {
                    return false;
                }
            }
        }
        
        return super.canContinueToUse();
    }
}

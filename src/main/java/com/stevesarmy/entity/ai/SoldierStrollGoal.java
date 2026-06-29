package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

public class SoldierStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final SoldierEntity soldier;

    public SoldierStrollGoal(SoldierEntity soldier, double speedModifier) {
        super(soldier, speedModifier);
        this.soldier = soldier;
    }

    @Override
    public boolean canUse() {
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return false;
        }
        
        if (soldier.getCombatGoal() != null && soldier.getCombatGoal().hasDetectedTargets()) {
            return false;
        }
        
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return false;
        }
        
        if (soldier.getCombatGoal() != null && soldier.getCombatGoal().hasDetectedTargets()) {
            return false;
        }
        
        return super.canContinueToUse();
    }
}

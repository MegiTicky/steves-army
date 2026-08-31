package com.stevesarmy.entity;

import com.stevesarmy.entity.ai.SupportCoverGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

/**
 * Support role. Uses the rifleman pipeline: identical goal layout, state
 * machine, ping handling, and movement. Support-specific behavior is layered
 * on as small, isolated additions (rear positioning, no peek, support tasks).
 */
public class SupportEntity extends SoldierEntity {

    public SupportEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        getCoverBehaviorManager().setProtectedMachineGunnerPolicy(true);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.SUPPORT;
    }

    @Override
    public boolean isPeekDisabled() {
        return true;
    }

    @Override
    protected Goal initializeCombatGoal() {
        SoldierCombatGoal goal = new SoldierCombatGoal(this);
        this.combatGoal = goal;
        this.combatGoalTask = goal;
        return goal;
    }

    @Override
    protected Goal initializeCoverTacticalGoal() {
        SupportCoverGoal goal = new SupportCoverGoal(this);
        this.coverTacticalGoal = goal;
        this.coverTacticalGoalTask = goal;
        return goal;
    }
}

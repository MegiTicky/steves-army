package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Holds a soldier near its defend position, patrolling the immediate area while idle. */
public class DefendPositionGoal extends Goal {

    private final SoldierEntity soldier;
    private BlockPos defendPosition;
    private int cooldown = 0;

    public DefendPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (soldier.getThreatAwareness().hasActiveThreat()) return false;
        if (soldier.getTarget() != null) return false;

        defendPosition = soldier.getDefendPosition();
        return defendPosition != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getTarget() != null) return false;
        return defendPosition != null;
    }

    @Override
    public void tick() {
        if (defendPosition == null) return;

        double distSqr = soldier.position().distanceToSqr(defendPosition.getX() + 0.5, soldier.getY(), defendPosition.getZ() + 0.5);
        double maxDistSqr = soldier.getDefendRadius() * soldier.getDefendRadius();

        if (distSqr > maxDistSqr) {
            soldier.getNavigation().moveTo(defendPosition.getX(), defendPosition.getY(), defendPosition.getZ(), 1.0);
        } else if (soldier.getNavigation().isDone() && --cooldown <= 0) {
            Vec3 wanderTarget = DefaultRandomPos.getPos(soldier, 8, 4);
            if (wanderTarget != null) {
                double wanderDistSqr = wanderTarget.distanceToSqr(defendPosition.getX() + 0.5, wanderTarget.y, defendPosition.getZ() + 0.5);
                if (wanderDistSqr <= maxDistSqr) {
                    soldier.getNavigation().moveTo(wanderTarget.x, wanderTarget.y, wanderTarget.z, 0.5);
                }
            }
            cooldown = 40 + soldier.getRandom().nextInt(60);
        }
    }
}

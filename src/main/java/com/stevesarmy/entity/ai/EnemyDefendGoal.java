package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

public class EnemyDefendGoal extends Goal {

    private final EnemySoldierEntity enemy;
    private BlockPos defendPosition;
    private int cooldown = 0;

    public EnemyDefendGoal(EnemySoldierEntity enemy) {
        this.enemy = enemy;
    }

    @Override
    public boolean canUse() {
        if (enemy.getThreatAwareness().hasActiveThreat()) return false;
        if (enemy.getTarget() != null) return false;

        defendPosition = enemy.getDefendPosition();
        return defendPosition != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (enemy.getTarget() != null) return false;
        return defendPosition != null;
    }

    @Override
    public void tick() {
        if (defendPosition == null) return;

        double distSqr = enemy.position().distanceToSqr(defendPosition.getX() + 0.5, enemy.getY(), defendPosition.getZ() + 0.5);
        double maxDistSqr = enemy.getDefendRadius() * enemy.getDefendRadius();

        if (distSqr > maxDistSqr) {
            enemy.getNavigation().moveTo(defendPosition.getX(), defendPosition.getY(), defendPosition.getZ(), 1.0);
        } else if (enemy.getNavigation().isDone() && --cooldown <= 0) {
            Vec3 wanderTarget = DefaultRandomPos.getPos(enemy, 8, 4);
            if (wanderTarget != null) {
                double wanderDistSqr = wanderTarget.distanceToSqr(defendPosition.getX() + 0.5, wanderTarget.y, defendPosition.getZ() + 0.5);
                if (wanderDistSqr <= maxDistSqr) {
                    enemy.getNavigation().moveTo(wanderTarget.x, wanderTarget.y, wanderTarget.z, 0.5);
                }
            }
            cooldown = 40 + enemy.getRandom().nextInt(60);
        }
    }
}
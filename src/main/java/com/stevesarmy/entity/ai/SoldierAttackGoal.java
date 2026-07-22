package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierAttackGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos rawTarget;
    private int commandGeneration;
    private final float closeDistance;
    private boolean isFinalApproach;

    public SoldierAttackGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.closeDistance = 4.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;
        rawTarget = soldier.getAttackTargetPos();
        commandGeneration = soldier.getAttackGeneration();
        // Only activate if cover goal signaled final approach fallback
        // OR if we're already within final approach distance
        isFinalApproach = soldier.isAttackFinalApproach()
            || (rawTarget != null && soldier.distanceToSqr(
                rawTarget.getX() + 0.5, rawTarget.getY() + 0.5, rawTarget.getZ() + 0.5) <= 12 * 12);
        return rawTarget != null && isFinalApproach;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;
        if (rawTarget == null) return false;
        if (soldier.getAttackGeneration() != commandGeneration) return false;
        double distSq = soldier.distanceToSqr(
            rawTarget.getX() + 0.5, rawTarget.getY() + 0.5, rawTarget.getZ() + 0.5);
        return distSq > closeDistance * closeDistance;
    }

    @Override
    public void start() {
        soldier.setAttackFinalApproach(true);
        navigateToTarget();
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        soldier.setAttackFinalApproach(false);
        // Only clear command if objective reached
        if (rawTarget != null) {
            double distSq = soldier.distanceToSqr(
                rawTarget.getX() + 0.5, rawTarget.getY() + 0.5, rawTarget.getZ() + 0.5);
            if (distSq <= closeDistance * closeDistance) {
                soldier.clearAttackTargetIfGeneration(commandGeneration);
            }
        }
        rawTarget = null;
    }

    @Override
    public void tick() {
        if (rawTarget == null) return;
        double cx = rawTarget.getX() + 0.5;
        double cy = rawTarget.getY() + 0.5;
        double cz = rawTarget.getZ() + 0.5;
        soldier.getLookControl().setLookAt(cx, cy + 0.5, cz, 30.0F, 30.0F);

        double distance = soldier.distanceToSqr(cx, cy, cz);
        if (distance > closeDistance * closeDistance) {
            if (soldier.getNavigation().isDone()) {
                navigateToTarget();
            }
            if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                soldier.getJumpControl().jump();
            }
        } else {
            soldier.getNavigation().stop();
            soldier.clearAttackTargetIfGeneration(commandGeneration);
        }
    }

    private void navigateToTarget() {
        BlockPos spaced = SpacingHelper.applySpacing(rawTarget, soldier);
        if (spaced != null) {
            soldier.getNavigation().moveTo(spaced.getX(), spaced.getY(), spaced.getZ(), 1.2D);
        }
    }
}
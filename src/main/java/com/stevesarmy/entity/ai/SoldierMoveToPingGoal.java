package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierMoveToPingGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos rawTarget;
    private BlockPos navigationTarget;
    private int commandGeneration;
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
        if (soldier.hasValidAttackTarget()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;

        captureCommand();
        return rawTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        if (navigationTarget == null) return false;

        double distSq = soldier.distanceToSqr(
            navigationTarget.getX() + 0.5,
            navigationTarget.getY() + 0.5,
            navigationTarget.getZ() + 0.5);
        return distSq > closeDistance * closeDistance;
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        soldier.cancelCoverMovement();
        soldier.clearFormationOffset();
        computeNavigationTarget();
        submitNavigation();
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        soldier.clearPingMoveTargetIfGeneration(commandGeneration);
        rawTarget = null;
        navigationTarget = null;
    }

    @Override
    public void tick() {
        if (navigationTarget == null) return;

        soldier.getLookControl().setLookAt(
            navigationTarget.getX() + 0.5,
            navigationTarget.getY() + 1.0,
            navigationTarget.getZ() + 0.5,
            30.0F, 30.0F
        );

        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = 10;

            // Detect a replacement command while running
            if (soldier.hasValidPingMoveTarget() && soldier.getPingMoveGeneration() != commandGeneration) {
                captureCommand();
                soldier.clearFormationOffset();
                computeNavigationTarget();
            }

            if (navigationTarget == null) return;

            double distance = soldier.distanceToSqr(
                navigationTarget.getX() + 0.5,
                navigationTarget.getY() + 0.5,
                navigationTarget.getZ() + 0.5);
            if (distance > closeDistance * closeDistance) {
                submitNavigation();

                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.getNavigation().stop();
                soldier.clearPingMoveTargetIfGeneration(commandGeneration);
            }
        }
    }

    private void captureCommand() {
        this.rawTarget = soldier.getPingMoveTarget();
        this.commandGeneration = soldier.getPingMoveGeneration();
    }

    private void computeNavigationTarget() {
        if (rawTarget == null) {
            navigationTarget = null;
            return;
        }
        navigationTarget = SpacingHelper.applySpacing(rawTarget, soldier);
    }

    private void submitNavigation() {
        if (navigationTarget == null) return;
        soldier.getNavigation().moveTo(
            navigationTarget.getX(), navigationTarget.getY(), navigationTarget.getZ(), speedModifier);
    }
}
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
        if (soldier.isGoToHolding()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        captureCommand();
        computeNavigationTarget();
        if (navigationTarget != null && soldier.getCoverTacticalGoal()
            .requestGoToRelocation(navigationTarget, commandGeneration)) {
            return false;
        }
        return rawTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (soldier.isGoToHolding()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        if (rawTarget == null) return false;
        if (soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) return false;
        double distSq = soldier.distanceToSqr(
            rawTarget.getX() + 0.5, rawTarget.getY() + 0.5, rawTarget.getZ() + 0.5);
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
        if (!soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) {
            soldier.getNavigation().stop();
            if (!soldier.hasPersistentGoTo()) {
                soldier.clearPingMoveTargetIfGeneration(commandGeneration);
            }
        }
        rawTarget = null;
        navigationTarget = null;
    }

    @Override
    public void tick() {
        if (rawTarget == null) return;

        double cx = rawTarget.getX() + 0.5;
        double cy = rawTarget.getY() + 0.5;
        double cz = rawTarget.getZ() + 0.5;
        if (soldier.isNavigationTraversalLocked()) {
            soldier.faceNavigationTraversal();
        } else {
            soldier.getLookControl().setLookAt(cx, cy + 0.5, cz, 30.0F, 30.0F);
        }

        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = 10;

            if (soldier.hasValidPingMoveTarget() && soldier.getPingMoveGeneration() != commandGeneration) {
                captureCommand();
                soldier.clearFormationOffset();
                computeNavigationTarget();
                if (navigationTarget != null && soldier.getCoverTacticalGoal()
                    .requestGoToRelocation(navigationTarget, commandGeneration)) {
                    soldier.getNavigation().stop();
                    return;
                }
            }

            if (rawTarget == null) return;

            double distance = soldier.distanceToSqr(cx, cy, cz);
            if (distance > closeDistance * closeDistance) {
                // Resubmit navigation — same fixed nav target, but pathfinder benefits from refresh
                submitNavigation();
                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.getNavigation().stop();
                soldier.completeGoToIfGeneration(commandGeneration);
            }
        }
    }

    private void captureCommand() {
        rawTarget = soldier.getPingMoveTarget();
        commandGeneration = soldier.getPingMoveGeneration();
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

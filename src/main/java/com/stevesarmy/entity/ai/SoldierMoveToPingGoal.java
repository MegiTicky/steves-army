package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
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
        computeNavigationTarget();
        boolean coverRelocationAccepted = navigationTarget != null && soldier.getCoverTacticalGoal()
            .requestGoToRelocation(navigationTarget, commandGeneration);
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[GoToBound] Soldier {} command={} rawTarget={} navTarget={} route={}",
                soldier.getId(), commandGeneration, rawTarget, navigationTarget,
                coverRelocationAccepted ? "cover_relocation" : "direct_navigation");
        }
        if (coverRelocationAccepted) {
            return false;
        }
        return rawTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
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
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[GoToBound] Soldier {} starting direct Go To navigation command={} target={}",
                soldier.getId(), commandGeneration, navigationTarget);
        }
        submitNavigation();
    }

    @Override
    public void stop() {
        if (!soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) {
            soldier.getNavigation().stop();
            soldier.clearPingMoveTargetIfGeneration(commandGeneration);
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
        soldier.getLookControl().setLookAt(cx, cy + 0.5, cz, 30.0F, 30.0F);

        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = 10;

            if (soldier.hasValidPingMoveTarget() && soldier.getPingMoveGeneration() != commandGeneration) {
                captureCommand();
                soldier.clearFormationOffset();
                computeNavigationTarget();
                boolean coverRelocationAccepted = navigationTarget != null && soldier.getCoverTacticalGoal()
                    .requestGoToRelocation(navigationTarget, commandGeneration);
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[GoToBound] Soldier {} replacement command={} target={} route={}",
                        soldier.getId(), commandGeneration, navigationTarget,
                        coverRelocationAccepted ? "cover_relocation" : "direct_navigation");
                }
                if (coverRelocationAccepted) {
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
                soldier.clearPingMoveTargetIfGeneration(commandGeneration);
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

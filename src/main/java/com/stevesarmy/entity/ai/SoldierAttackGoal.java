package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierAttackGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos rawTarget;
    private BlockPos navigationTarget;
    private int commandGeneration;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;

    public SoldierAttackGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 0.8D;
        this.closeDistance = 4.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;
        rawTarget = soldier.getAttackTargetPos();
        commandGeneration = soldier.getAttackGeneration();
        return rawTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;
        if (rawTarget == null) return false;
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
        soldier.getNavigation().stop();
        soldier.clearAttackTargetIfGeneration(commandGeneration);
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

            if (soldier.hasValidAttackTarget() && soldier.getAttackGeneration() != commandGeneration) {
                rawTarget = soldier.getAttackTargetPos();
                commandGeneration = soldier.getAttackGeneration();
                soldier.clearFormationOffset();
                computeNavigationTarget();
            }

            if (rawTarget == null) return;

            double distance = soldier.distanceToSqr(cx, cy, cz);
            if (distance > closeDistance * closeDistance) {
                CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
                boolean coverIsMoving = coverState == CoverBehaviorManager.CoverState.SEEKING_COVER ||
                                        coverState == CoverBehaviorManager.CoverState.REPOSITIONING;
                if (!coverIsMoving) {
                    submitNavigation();
                }
                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.getNavigation().stop();
                soldier.clearAttackTargetIfGeneration(commandGeneration);
            }
        }
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
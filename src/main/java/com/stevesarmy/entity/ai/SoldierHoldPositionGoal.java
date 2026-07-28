package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class SoldierHoldPositionGoal extends Goal {
    private static final float HOLD_RADIUS_SQ = 100.0f;
    private static final float RETURN_TO_COVER_DISTANCE_SQ = 9.0f;
    private final SoldierEntity soldier;
    private BlockPos holdPos;
    private final double speedModifier;

    public SoldierHoldPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.0D;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // ATTACK uses CoverTacticalGoal as its sole movement owner.
        if (soldier.hasValidAttackTarget()) {
            return false;
        }

        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                return false;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        this.holdPos = soldier.getHoldPosition();
        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return false;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        return distToHold > HOLD_RADIUS_SQ;
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.hasValidAttackTarget()) {
            return false;
        }

        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                return false;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return false;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        return distToHold > HOLD_RADIUS_SQ;
    }

    @Override
    public void start() {
        soldier.clearFormationOffset();
        navigateToTarget(holdPos);
    }

    @Override
    public void stop() {
        CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
        if (CoverTacticalGoal.isDebugLoggingEnabled()
            && (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER
                || coverState == CoverBehaviorManager.CoverState.REPOSITIONING)) {
            StevesArmyMod.LOGGER.warn("[CoverOwnership] HOLD stopping navigation during {} for soldier {} ({}) targetCover={} navDone={}",
                coverState, soldier.getId(), soldier.getName().getString(),
                soldier.getCoverBehaviorManager().getTargetCover() != null
                    ? soldier.getCoverBehaviorManager().getTargetCover().getPosition() : null,
                soldier.getNavigation().isDone());
        }
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (soldier.hasValidAttackTarget()) {
            return;
        }

        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                soldier.getNavigation().stop();
                return;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        if (distToHold > HOLD_RADIUS_SQ) {
            navigateToTarget(holdPos);
        } else {
            soldier.getNavigation().stop();
        }
    }

    private void navigateToTarget(BlockPos target) {
        if (target == null) return;
        BlockPos spaced = SpacingHelper.applySpacing(target, soldier);
        soldier.getNavigation().moveTo(spaced.getX(), spaced.getY(), spaced.getZ(), speedModifier);
    }
}

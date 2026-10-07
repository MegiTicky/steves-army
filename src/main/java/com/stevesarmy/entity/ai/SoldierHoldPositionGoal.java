package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.StevesArmyConfig;
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
    /** Retry interval for a request whose path never closed meaningful distance (failed, unreachable, or stale-cancelled). */
    private static final int HOLD_REPATH_FAILURE_INTERVAL_TICKS = 100;
    /** Distance the soldier must close per finished path before the retry is treated as a failure. */
    private static final double HOLD_PATH_PROGRESS_SQ = 2.25D;
    private final SoldierEntity soldier;
    private BlockPos holdPos;
    private final double speedModifier;
    private int repathTimer;
    private double holdDistAtRequestSq;

    public SoldierHoldPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.0D;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * True when the soldier has a hold objective beyond the hold radius, i.e. it
     * is marching there rather than holding. Drives the suppressed-traveler
     * exemption in CoverTacticalGoal.shouldSeekCover.
     */
    public static boolean isTravelingToObjective(SoldierEntity soldier) {
        BlockPos hold = soldier.getHoldPosition();
        if (hold == null || hold.equals(BlockPos.ZERO)) return false;
        return soldier.distanceToSqr(hold.getX(), hold.getY(), hold.getZ()) > HOLD_RADIUS_SQ;
    }

    @Override
    public boolean canUse() {
        if (soldier.hasValidPingSuppressPos()) return false;
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
        if (soldier.hasValidPingSuppressPos()) return false;
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
        this.repathTimer = 0;
        this.holdDistAtRequestSq = 0.0D;
        navigateToTarget(holdPos);
        this.holdDistAtRequestSq = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        this.repathTimer = StevesArmyConfig.getMoveGoalRepathTicks();
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
            // Re-path only when the previous path is finished (or failed) and the
            // interval has elapsed. isDone() is false while an async request is
            // pending or its path is still being walked, so this follows the path
            // instead of re-requesting — and recapturing a pathfinding snapshot —
            // every tick.
            if (repathTimer > 0) {
                repathTimer--;
            }
            if (repathTimer <= 0 && soldier.getNavigation().isDone()) {
                boolean stalled = holdDistAtRequestSq > 0.0D
                    && holdDistAtRequestSq - distToHold < HOLD_PATH_PROGRESS_SQ;
                navigateToTarget(holdPos);
                holdDistAtRequestSq = distToHold;
                repathTimer = stalled
                    ? HOLD_REPATH_FAILURE_INTERVAL_TICKS
                    : StevesArmyConfig.getMoveGoalRepathTicks();
            }
            tickTravelResponse(coverManager);
        } else {
            soldier.getNavigation().stop();
        }
    }

    /**
     * Suppressed-traveler response while marching (dangerTravelResponse): consumes
     * a reroute request raised by CoverBehaviorManager on near-misses, recomputing
     * the path so the squad map's fresh shadow bends the route. The travel crawl
     * posture is handled goal-independently by SoldierEntity's
     * TravelCrawlController, so it covers cover-approaches and ping moves too.
     */
    private void tickTravelResponse(CoverBehaviorManager coverManager) {
        if (!StevesArmyConfig.isDangerTravelResponseEnabled()) return;

        if (coverManager.consumeTravelRerouteRequest() && soldier.getNavigation().getPath() != null) {
            navigateToTarget(holdPos);
            holdDistAtRequestSq = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
            repathTimer = StevesArmyConfig.getMoveGoalRepathTicks();
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[TravelResponse] Soldier {} re-routing march to {} under fire (suppression={})",
                    soldier.getId(), holdPos, String.format("%.2f", coverManager.getSuppressionLevel()));
            }
        }
    }

    private void navigateToTarget(BlockPos target) {
        if (target == null) return;
        BlockPos spaced = SpacingHelper.applySpacing(target, soldier);
        soldier.getNavigation().moveTo(spaced.getX(), spaced.getY(), spaced.getZ(), speedModifier);
    }
}

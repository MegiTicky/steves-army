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
    /** A dock only yields to the march when the hold is this far past the hold
     * radius; a dock within the margin is the terminus, not a pause - yielding
     * there just marches the soldier across the radius line and straight back
     * to the same cover (the dock/re-dock pendulum). */
    private static final float MARCH_RESUME_DIST_SQ = HOLD_RADIUS_SQ + 50.0f;
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

    /**
     * Quiet-dwell clock per docked soldier (uuid -> first quiet dock tick). A
     * dock is a pause under fire, not a terminus: once the soldier has sat out
     * holdMarchDwellTicks without suppression or a close-combat context and the
     * hold objective is still beyond the hold radius, the march resumes and the
     * docked cover is released (street-crossing doctrine - cover use during the
     * march, not instead of it). Lives outside the goal instance because the
     * goal is not running while docked; canUse() owns the clock.
     */
    private static final java.util.Map<java.util.UUID, Long> DOCK_SINCE = new java.util.HashMap<>();

    /** True while the soldier is inside the quiet-dock dwell window. */
    private static boolean dockDwellActive(SoldierEntity soldier, CoverBehaviorManager coverManager) {
        // Only active fire resets the clock. The close-combat context latch
        // (16/20 hysteresis) persists while a live threat merely lurks within
        // 20 blocks - gating the march on it recreates the freeze the
        // street-crossing doctrine removes; the suppressed-traveler doctrine
        // governs marching in threat areas.
        if (coverManager.isSuppressed()) {
            DOCK_SINCE.remove(soldier.getUUID());
            return true;
        }
        long now = soldier.tickCount;
        Long since = DOCK_SINCE.get(soldier.getUUID());
        if (since == null) {
            DOCK_SINCE.put(soldier.getUUID(), now);
            if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[MarchDwell] S{} dock clock started at t{}", soldier.getId(), now);
            }
            return true;
        }
        boolean dwelling = now - since < StevesArmyConfig.getHoldMarchDwellTicks();
        if (!dwelling && com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[MarchDwell] S{} dwell expired (t{} - t{} >= {}), resuming march",
                soldier.getId(), since, now, StevesArmyConfig.getHoldMarchDwellTicks());
        }
        return dwelling;
    }

    /** True when the hold objective is far enough past the hold radius that
     * resuming the march moves the soldier meaningfully (street-crossing and
     * GO_TO marches clear this easily; a dock a block outside the radius
     * does not - see {@link #MARCH_RESUME_DIST_SQ}). */
    private static boolean marchWorthwhile(SoldierEntity soldier) {
        BlockPos hold = soldier.getHoldPosition();
        if (hold == null || hold.equals(BlockPos.ZERO)) return false;
        return soldier.distanceToSqr(hold.getX(), hold.getY(), hold.getZ()) > MARCH_RESUME_DIST_SQ;
    }

    /**
     * Street-crossing doctrine: true when a docked soldier must yield the legs
     * and resume the march - hold objective still beyond the hold radius, quiet
     * (no suppression, no close-combat context), not the bounder and not the
     * overwatch anchor, and the quiet dwell has sat out. Consumed by
     * CoverTacticalGoal.canContinueToUse so the cover goal releases MOVE and
     * SoldierHoldPositionGoal can take over.
     */
    public static boolean shouldResumeMarch(SoldierEntity soldier, CoverBehaviorManager coverManager) {
        // A live fight owns the soldier - the cover goal keeps him docked and
        // firing, and the hold goal would not take over anyway (its canUse
        // refuses while a target is valid). Yielding here would just bounce
        // the soldier between seek rounds.
        if (soldier.hasValidAttackTarget()) return false;
        CoverPoint currentCover = coverManager.getCurrentCover();
        String why = null;
        if (currentCover == null) why = "no-cover";
        else if (soldier.position().distanceToSqr(currentCover.getPosition().getCenter())
            > RETURN_TO_COVER_DISTANCE_SQ) why = "off-cover";
        if (why == null && (com.stevesarmy.squad.SquadMovementCoordinator.isBounder(soldier)
            || com.stevesarmy.squad.SquadMovementCoordinator.isOverwatching(soldier))) why = "bound-role";
        if (why == null && coverManager.isSuppressed()) why = "pressured";
        BlockPos hold = why == null ? soldier.getHoldPosition() : null;
        if (why == null && (hold == null || hold.equals(BlockPos.ZERO))) why = "no-hold";
        if (why == null && soldier.distanceToSqr(hold.getX(), hold.getY(), hold.getZ()) <= MARCH_RESUME_DIST_SQ) why = "arrived";
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()
            && soldier.tickCount % 100 == 0) {
            StevesArmyMod.LOGGER.info("[MarchDwell] S{} resume? t{}: why={}", soldier.getId(), soldier.tickCount, why != null ? why : "dwell-pending-or-go");
        }
        return why == null && !dockDwellActive(soldier, coverManager);
    }

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
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()
            && soldier.tickCount % 100 == 0) {
            CoverBehaviorManager cm = soldier.getCoverBehaviorManager();
            CoverPoint cc = cm.getCurrentCover();
            StevesArmyMod.LOGGER.info("[MarchDwell] S{} use? t{}: mode={} tgt={} ping={} cover={} state={} hold={}",
                soldier.getId(), soldier.tickCount, soldier.getSquadMode(),
                soldier.hasValidAttackTarget(), soldier.hasValidPingSuppressPos(),
                cc != null ? cc.getPosition() : "null", cm.getState(), soldier.getHoldPosition());
        }
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
                // Docked: pause here through the quiet-dwell window, then resume
                // the march and release the cover. Suppression or the
                // close-combat context keeps the dock (dockDwellActive resets
                // the clock); the active bounder never docks at all.
                if (com.stevesarmy.squad.SquadMovementCoordinator.isBounder(soldier)) {
                    // fall through: the bounder crosses
                } else if (com.stevesarmy.squad.SquadMovementCoordinator.isOverwatching(soldier)) {
                    // the overwatcher anchors his lane while the bounder crosses
                    return false;
                } else if (dockDwellActive(soldier, coverManager)) {
                    return false;
                } else if (!marchWorthwhile(soldier)) {
                    // dwell sat out but the hold is just past the radius - the
                    // "march" would cross the radius line and re-dock on the
                    // same cover; this dock is the terminus
                    return false;
                }
            } else {
                DOCK_SINCE.remove(soldier.getUUID());
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
        DOCK_SINCE.remove(soldier.getUUID());
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
            // The active bounder does not dock mid-crossing: the street-crossing
            // doctrine's whole point is reaching the far side; en-route cover
            // waits for the overwatchers and the rotation. Everyone else docked
            // here has already sat out the quiet dwell (canUse clock) - release
            // the cover so its anchor stops pulling the soldier back, and let
            // the march below continue. Active fire re-docks instead (the
            // clock restarts there); the close-combat context latch does not -
            // it persists while a live threat merely lurks within 20 blocks.
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ
                && !com.stevesarmy.squad.SquadMovementCoordinator.isBounder(soldier)) {
                if (com.stevesarmy.squad.SquadMovementCoordinator.isOverwatching(soldier)) {
                    soldier.getNavigation().stop();
                    return;
                }
                if (coverManager.isSuppressed()) {
                    DOCK_SINCE.put(soldier.getUUID(), (long) soldier.tickCount);
                    soldier.getNavigation().stop();
                    return;
                }
                if (!marchWorthwhile(soldier)) {
                    soldier.getNavigation().stop();
                    return;
                }
                if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[MarchDwell] S{} releasing docked cover {}, resuming march to hold",
                        soldier.getId(), currentCover.getPosition());
                }
                coverManager.clearCover();
                // fall through: the march below continues toward the hold point
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        if (distToHold > HOLD_RADIUS_SQ) {
            // Squad street-crossing doctrine: an overwatcher anchors its lane
            // and lets the bounder cross first. The coordinator's bound
            // timeout and rotation keep this from becoming a permanent stop,
            // and combat (fire/aim/peek) above is unaffected.
            if (StevesArmyConfig.isSquadBoundingEnabled()
                && com.stevesarmy.squad.SquadMovementCoordinator.isOverwatching(soldier)) {
                soldier.getNavigation().stop();
                return;
            }
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

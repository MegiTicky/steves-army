package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

public class SoldierMoveToPingGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos rawTarget;
    private BlockPos navigationTarget;
    private int commandGeneration;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;
    // Lens-bound stall tracking: consecutive 10-tick re-issues that closed less
    // than one block mean the FOLLOW_RANGE partial-path search cannot route the
    // remaining detour and its best-effort stub ends at the obstacle face.
    private static final int STALLED_REISSUES_BEFORE_ESCAPE = 3;
    private static final double MIN_REISSUE_PROGRESS_SQ = 1.0D;
    private static final double ESCAPE_MIN_DISTANCE_SQ = 30.0D * 30.0D;
    /** Short enough that the plain lens routes the leg exactly, with headroom. */
    private static final double ESCAPE_LEG_LENGTH = 20.0D;
    /** A probe's path may fall a few blocks short of the waypoint and still count. */
    private static final double ESCAPE_ENDPOINT_TOLERANCE_SQ = 36.0D;
    /** Probe rounds allowed to fail before wall-following hands the march back. */
    private static final int MAX_FAILED_ESCAPE_ROUNDS = 12;
    private int stalledReissues;
    private double lastReissueDistanceSq = -1.0D;
    private boolean detourLegActive;
    private int failedEscapeRounds;
    /** Persistent turning side while wall-following an obstacle (0 = not following). */
    private int detourFlank;

    public SoldierMoveToPingGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.2D;
        this.closeDistance = 2.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        // Crew soldiers ignore move pings while seated at their station.
        if (soldier.isVehicleCrewActive() || soldier.isCrewSeated()
            || soldier.getRole() == SoldierRole.VEHICLE_CREW) return false;
        if (soldier.hasValidPingSuppressPos()) return false;
        if (soldier.hasValidAttackTarget()) return false;
        if (soldier.isGoToHolding()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        captureCommand();
        // The cover endpoint owns this order, including a route through the
        // raw ping radius. Centre arrival is only for the direct-walk fallback.
        if (soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) return false;
        if (soldier.hasGoToNavigationOwnership() && reachedDestination()) {
            // An already-arrived re-ping must not start another relocation
            // search and bypass the failed-search pacing at a coverless site.
            soldier.completeGoToIfGeneration(commandGeneration);
            return false;
        }
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
        if (soldier.isVehicleCrewActive() || soldier.isCrewSeated()
            || soldier.getRole() == SoldierRole.VEHICLE_CREW) return false;
        if (soldier.hasValidPingSuppressPos()) return false;
        if (soldier.isGoToHolding()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        if (rawTarget == null) return false;
        if (soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) return false;
        if (reachedDestination()) {
            // Continuation is checked before tick(), including its slower
            // path-refresh branch. Finish the order before yielding the legs.
            soldier.completeGoToIfGeneration(commandGeneration);
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        stalledReissues = 0;
        lastReissueDistanceSq = -1.0D;
        detourLegActive = false;
        detourFlank = 0;
        failedEscapeRounds = 0;
        soldier.cancelCoverMovement();
        soldier.clearFormationOffset();
        // A starved GO_TO cover seek can hand off a prone-latched soldier;
        // the walk starts upright and TravelCrawlController re-crawls it
        // when suppression warrants.
        if (soldier.isFiringProne()) {
            soldier.setFiringProne(false);
        }
        computeNavigationTarget();
        submitNavigation();
    }

    @Override
    public void stop() {
        if (!soldier.hasValidPingSuppressPos()
            && !soldier.getCoverTacticalGoal().isHandlingGoToRelocation(commandGeneration)) {
            soldier.getNavigation().stop();
            // Completion clears the move target, so hasPersistentGoTo() is
            // already false. Keep the completed order's holding state intact.
            if (!soldier.hasGoToNavigationOwnership()) {
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
                stalledReissues = 0;
                lastReissueDistanceSq = -1.0D;
                detourLegActive = false;
                detourFlank = 0;
                failedEscapeRounds = 0;
                if (navigationTarget != null && soldier.getCoverTacticalGoal()
                    .requestGoToRelocation(navigationTarget, commandGeneration)) {
                    soldier.getNavigation().stop();
                    return;
                }
            }

            if (rawTarget == null) return;

            if (!reachedDestination()) {
                if (detourLegActive && !soldier.getNavigation().isDone()) {
                    // Still following an escape leg; a plain re-issue here would
                    // replace it with the same lens-bound stub that stalled at
                    // the obstacle face.
                } else {
                    detourLegActive = false;
                    boolean stalled = trackStalledReissue();
                    if (detourFlank != 0) {
                        // Wall-following owns the march until the direct line
                        // clears: a plain re-issue would best-effort straight
                        // back to the obstacle face and undo the escape.
                        if (tryDetourLegNavigation()) {
                            stalledReissues = 0;
                            failedEscapeRounds = 0;
                            detourLegActive = true;
                        } else if (++failedEscapeRounds > MAX_FAILED_ESCAPE_ROUNDS) {
                            detourFlank = 0;
                            failedEscapeRounds = 0;
                            submitNavigation();
                        }
                    } else if (stalled && tryDetourLegNavigation()) {
                        stalledReissues = 0;
                        detourLegActive = true;
                    } else {
                        // Resubmit navigation — same fixed nav target, but pathfinder benefits from refresh
                        submitNavigation();
                    }
                }
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

    private boolean reachedDestination() {
        if (rawTarget == null) return false;
        return soldier.distanceToSqr(rawTarget.getX() + 0.5, rawTarget.getY() + 0.5,
            rawTarget.getZ() + 0.5) <= closeDistance * closeDistance;
    }

    private void submitNavigation() {
        if (navigationTarget == null) return;
        soldier.getNavigation().moveTo(navigationTarget.getX(), navigationTarget.getY(),
            navigationTarget.getZ(), speedModifier);
    }

    /**
     * Counts consecutive re-issues that closed less than one block of
     * straight-line distance. True while the march is stalled far enough from
     * the objective that the FOLLOW_RANGE lens, not arrival, is the binding
     * constraint — the halfway stop in front of a large obstacle.
     */
    private boolean trackStalledReissue() {
        if (navigationTarget == null) return false;
        double distSq = soldier.distanceToSqr(navigationTarget.getX() + 0.5,
            navigationTarget.getY() + 0.5, navigationTarget.getZ() + 0.5);
        boolean stalledNow = lastReissueDistanceSq >= 0.0D
            && lastReissueDistanceSq - distSq < MIN_REISSUE_PROGRESS_SQ;
        stalledReissues = stalledNow ? stalledReissues + 1 : 0;
        lastReissueDistanceSq = distSq;
        return stalledReissues >= STALLED_REISSUES_BEFORE_ESCAPE
            && distSq > ESCAPE_MIN_DISTANCE_SQ;
    }

    /**
     * Escapes a lens-bound stall with one short detour leg, wall-following:
     * once a flank side is chosen it is kept (direct line tried first each
     * time, so the march rejoins the straight route the moment it clears the
     * obstacle). Each leg is short enough for the normal partial-path lens to
     * route exactly, so the march gets around obstacles whose full detour
     * exceeds any single search's walked-path cap — a stranded stub at the
     * obstacle face can never make that progress on its own.
     */
    private boolean tryDetourLegNavigation() {
        double dx = navigationTarget.getX() + 0.5 - soldier.getX();
        double dz = navigationTarget.getZ() + 0.5 - soldier.getZ();
        double directBearing = Math.atan2(dz, dx);
        double legLength = Math.min(ESCAPE_LEG_LENGTH, Math.hypot(dx, dz));
        // Flank unknown: probe both sides and commit to the first open one.
        // While following: direct first (clearance check), then the flank
        // bearings outward. A symmetric ring here ping-pongs the soldier
        // between the two sides of the obstacle.
        int[] offsets = detourFlank == 0
            ? new int[] {90, -90}
            : new int[] {0, detourFlank * 45, detourFlank * 90, detourFlank * 135};
        for (int offset : offsets) {
            if (probeDetourLeg(directBearing + Math.toRadians(offset), legLength)) {
                if (detourFlank == 0) {
                    detourFlank = offset > 0 ? 1 : -1;
                } else if (offset == 0) {
                    detourFlank = 0;
                }
                return true;
            }
        }
        if (detourFlank != 0) {
            // Dead end on the followed flank: try the other side before giving
            // this escape attempt up (the cooldown paces the next one).
            for (int offset : new int[] {-detourFlank * 45, -detourFlank * 90, -detourFlank * 135}) {
                if (probeDetourLeg(directBearing + Math.toRadians(offset), legLength)) {
                    detourFlank = -detourFlank;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Probes one escape leg: a short waypoint whose path the plain lens can
     * route exactly (endpoint within 3 blocks of the waypoint). Direct
     * createPath call: synchronous, so the probe's verdict is the real path,
     * not an async placeholder.
     */
    private boolean probeDetourLeg(double bearing, double legLength) {
        BlockPos waypoint = BlockPos.containing(
            soldier.getX() + Math.cos(bearing) * legLength,
            soldier.blockPosition().getY(),
            soldier.getZ() + Math.sin(bearing) * legLength);
        Path path = soldier.getNavigation().createPath(waypoint, 1);
        if (path == null || path.getNodeCount() < 2) return false;
        BlockPos end = path.getNode(path.getNodeCount() - 1).asBlockPos();
        if (waypoint.distSqr(end) > ESCAPE_ENDPOINT_TOLERANCE_SQ) return false;
        return soldier.getNavigation().moveTo(path, speedModifier);
    }
}

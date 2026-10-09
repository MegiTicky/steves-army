package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Per-soldier snapshot of the cover/peek/suppression state machine, shared by
 * the {@code /stevesarmy_debug coverstate} render ({@code CoverStateDebugPacket})
 * and the simulation harness ({@code sim/}). Built by
 * {@code CoverTacticalGoal.captureStateSnapshot} so both surfaces see exactly
 * the same decision inputs, including the loop counters where strands hide.
 * Plain data: no behavior, no world access.
 */
public final class CombatStateSnapshot {
    public final UUID soldierId;
    public final String role;
    public final Vec3 position;

    public final String coverState;
    public final String peekState;
    public final String squadMode;
    public final float suppressionLevel;
    public final boolean pinned;

    public final boolean wantCover;
    public final String wantCoverReason;
    public final int goalCooldown;

    public final int seekingTicks;
    public final int seekingTimeout;
    public final int stuckTicks;
    public final int maxStuckTicks;
    public final int noProgressTicks;
    public final int searchExhaustionTicks;
    public final int failedSearches;
    public final int blacklistSize;
    public final int blacklistClearInSec;

    public final boolean searchPending;
    public final String queuedMode;
    public final int schedulerPending;

    public final String relocType;
    public final Vec3 relocCenter;
    public final int relocBackoffTicks;

    public final String followBlockedBy;
    public final float ownerDist;

    public final boolean mgPipeline;
    public final Vec3 mgAnchor;
    public final int mgAnchorAgeTicks;
    public final Vec3 suppressionCenter;
    public final int supCenterFreshTicks;
    public final Vec3 laneTarget;

    /** Claimed / pursued cover positions; null when none. Extra beyond the debug packet. */
    public final BlockPos currentCover;
    public final BlockPos targetCover;
    /** CoverType name (HALF/FULL/...) of the claimed / pursued cover; null when none. */
    public final String currentCoverType;
    public final String targetCoverType;

    /** Outcome of the most recent cover search; null before the first search. */
    public final LastSearch lastSearch;

    /** Server-side rendered pose name (STANDING/CROUCHING/SWIMMING); the card's
     * posture segment exists to expose pose-vs-visual disagreements in-game. */
    public final String posture;
    /** LOW_CROUCHING entity flag (the prone/low-crouch state the pose gate obeys). */
    public final boolean lowCrouching;
    /** Emergency engagement posture window active (vetoed the suppression prone). */
    public final boolean emergencyPosture;
    /** Synced cover height the pose gate reads; -1 when no cover claimed. */
    public final float currentCoverHeight;

    /** Suppressed-traveler doctrine: executing a long move (hold march / GO_TO). */
    public final boolean traveling;
    /** A live ATTACK order is driving this soldier — cover claims are transient advance bounds. */
    public final boolean attacking;
    /** Universal travel crawl engaged (movement posture from TravelCrawlController). */
    public final boolean travelCrawl;
    /** The ground underfoot currently reads "crawling hides" on the squad danger map. */
    public final boolean crawlHidesHere;
    /** Head yaw in degrees at sampling time (aim orientation for CQB doctrine assertions). */
    public final float headYaw;
    /** Served travel re-routes (near-miss-driven path recomputes) since spawn. */
    public final int travelReroutesServed;

    /** Fire-pipeline gate wire name at sampling time ("none" = shot fired / lane clear). */
    public final String fireGate;
    /** Ticks the current gate reason has held continuously. */
    public final int fireGateTicks;
    /** Ticks since the last direct-fire shot; -1 when none was ever fired. */
    public final int ticksSinceLastShot;
    /** A live target is selected (LoS is sampled separately as losToEnemy). */
    public final boolean targetVisible;
    /** Gun functional: not reloading, drawing or bolting. */
    public final boolean weaponReady;
    /** Body-yaw error vs the known threat in degrees; -1 when no threat known. */
    public final float facingErrorDeg;
    /** Body yaw in degrees at sampling time (rotation-oscillation checks). */
    public final float bodyYaw;
    /** Contact-plan movement permission (INC 2 WP2); true until the plan denies. */
    public final boolean movingSupported;
    public final String movementBlockReason;
    public final String movementOwner;

    /** A known threat sits inside engagement range (24 blocks). */
    public final boolean threatInRange;
    /** Continuous standing-still counter sampled by the state machine. */
    public final int stationaryTicks;
    /** The current movement objective is still far away (>12 blocks). */
    public final boolean objectiveFar;
    /** Why the legs are legitimately held (follow gate, pin, contact plan); "" = no excuse. */
    public final String blockedExcuse;

    /** Telemetry for one executed cover search — where it looked and what came back. */
    public static final class LastSearch {
        public final String mode;
        public final String result;
        public final String phase;
        public final Vec3 center;
        public final int radius;
        public final int tick;
        /** Consecutive failed (no-cover) searches without an intervening success. */
        public final int failStreak;

        public LastSearch(String mode, String result, String phase,
                          Vec3 center, int radius, int tick, int failStreak) {
            this.mode = mode;
            this.result = result;
            this.phase = phase;
            this.center = center;
            this.radius = radius;
            this.tick = tick;
            this.failStreak = failStreak;
        }
    }

    public CombatStateSnapshot(UUID soldierId, String role, Vec3 position,
                               String coverState, String peekState, String squadMode,
                               float suppressionLevel, boolean pinned,
                               boolean wantCover, String wantCoverReason, int goalCooldown,
                               int seekingTicks, int seekingTimeout, int stuckTicks, int maxStuckTicks,
                               int noProgressTicks, int searchExhaustionTicks, int failedSearches,
                               int blacklistSize, int blacklistClearInSec,
                               boolean searchPending, String queuedMode, int schedulerPending,
                               String relocType, Vec3 relocCenter, int relocBackoffTicks,
                               String followBlockedBy, float ownerDist,
                               boolean mgPipeline, Vec3 mgAnchor, int mgAnchorAgeTicks,
                               Vec3 suppressionCenter, int supCenterFreshTicks, Vec3 laneTarget,
                               BlockPos currentCover, BlockPos targetCover,
                               String currentCoverType, String targetCoverType,
                               LastSearch lastSearch,
                               String posture, boolean lowCrouching, boolean emergencyPosture,
                               float currentCoverHeight,
                               boolean traveling, boolean attacking, boolean travelCrawl, boolean crawlHidesHere,
                               int travelReroutesServed, float headYaw,
                               String fireGate, int fireGateTicks, int ticksSinceLastShot,
                               boolean targetVisible, boolean weaponReady, float facingErrorDeg,
                               float bodyYaw,
                               boolean movingSupported, String movementBlockReason, String movementOwner,
                               boolean threatInRange, int stationaryTicks,
                               boolean objectiveFar, String blockedExcuse) {
        this.soldierId = soldierId;
        this.role = role;
        this.position = position;
        this.coverState = coverState;
        this.peekState = peekState;
        this.squadMode = squadMode;
        this.suppressionLevel = suppressionLevel;
        this.pinned = pinned;
        this.wantCover = wantCover;
        this.wantCoverReason = wantCoverReason;
        this.goalCooldown = goalCooldown;
        this.seekingTicks = seekingTicks;
        this.seekingTimeout = seekingTimeout;
        this.stuckTicks = stuckTicks;
        this.maxStuckTicks = maxStuckTicks;
        this.noProgressTicks = noProgressTicks;
        this.searchExhaustionTicks = searchExhaustionTicks;
        this.failedSearches = failedSearches;
        this.blacklistSize = blacklistSize;
        this.blacklistClearInSec = blacklistClearInSec;
        this.searchPending = searchPending;
        this.queuedMode = queuedMode;
        this.schedulerPending = schedulerPending;
        this.relocType = relocType;
        this.relocCenter = relocCenter;
        this.relocBackoffTicks = relocBackoffTicks;
        this.followBlockedBy = followBlockedBy;
        this.ownerDist = ownerDist;
        this.mgPipeline = mgPipeline;
        this.mgAnchor = mgAnchor;
        this.mgAnchorAgeTicks = mgAnchorAgeTicks;
        this.suppressionCenter = suppressionCenter;
        this.supCenterFreshTicks = supCenterFreshTicks;
        this.laneTarget = laneTarget;
        this.currentCover = currentCover;
        this.targetCover = targetCover;
        this.currentCoverType = currentCoverType;
        this.targetCoverType = targetCoverType;
        this.lastSearch = lastSearch;
        this.posture = posture;
        this.lowCrouching = lowCrouching;
        this.emergencyPosture = emergencyPosture;
        this.currentCoverHeight = currentCoverHeight;
        this.traveling = traveling;
        this.attacking = attacking;
        this.travelCrawl = travelCrawl;
        this.crawlHidesHere = crawlHidesHere;
        this.headYaw = headYaw;
        this.travelReroutesServed = travelReroutesServed;
        this.fireGate = fireGate;
        this.fireGateTicks = fireGateTicks;
        this.ticksSinceLastShot = ticksSinceLastShot;
        this.targetVisible = targetVisible;
        this.weaponReady = weaponReady;
        this.facingErrorDeg = facingErrorDeg;
        this.bodyYaw = bodyYaw;
        this.movingSupported = movingSupported;
        this.movementBlockReason = movementBlockReason;
        this.movementOwner = movementOwner;
        this.threatInRange = threatInRange;
        this.stationaryTicks = stationaryTicks;
        this.objectiveFar = objectiveFar;
        this.blockedExcuse = blockedExcuse;
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.combat.cover.pure.CoverSearchResult;
import com.stevesarmy.combat.cover.pure.AsyncCoverShadowService;
import com.stevesarmy.combat.cover.pure.CoverSnapshotCapture;
import com.stevesarmy.combat.cover.pure.PureCoverEvaluator;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.squad.SquadCoverContext;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CoverTacticalGoal extends Goal implements CoverGoalController {
    // Attack phase enum - what the soldier is trying to do
    public enum AttackPhase {
        NONE,
        SELECTING_COVER,
        MOVING_TO_COVER,
        OCCUPYING_COVER,
        COMPLETE
    }

    // Result of a cover search operation
    private enum CoverMoveResult {
        COVER_STARTED,
        NO_COVER_FOUND,
        NO_ELIGIBLE_COVER,
        ASYNC_PENDING
    }

    private enum QueuedSearchMode {
        NORMAL,
        REPOSITION,
        ATTACK_SELECTING,
        SHOT_IN_COVER,
        CONTINUOUS_SUPPRESSION,
        SUPPRESSION_REPOSITION
    }

    private final SoldierEntity soldier;
    private final PathNavigation navigation;
    private final boolean machineGunnerPipeline;
    private CoverSelectionStrategy coverSelectionStrategy;
    
    private int cooldown = 0;
    private int stuckTicks = 0;
    private int reevaluateCounter = 0;
    private int noProgressTicks = 0;
    private int seekingTicks = 0;
    private int goToRelocationTicks = 0;
    private Vec3 lastSeekingPosition = null;
    // A prone lane is intentionally not a CoverPoint: no reservation, peek state, or cover bonus.
    private BlockPos proneFiringDestination;
    private final ProneFiringController proneFiringController;
    
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int REEVALUATE_INTERVAL_TICKS = 60;
    
    private static final double COVER_REACHED_DISTANCE = 1.5D;
    private static final double COVER_VALID_DISTANCE = 1.6D;
    private static final double COMBAT_COVER_VALID_DISTANCE = 6.0D;
    private static final double COVER_ABANDON_DISTANCE = 8.0D;
    
    private static final int SEARCH_RADIUS = 12;
    private static final long MIN_COVER_DWELL_TIME_MS = 4000;
    private static final int SHOT_IN_COVER_RETRY_TICKS = 20;
    private static final long MIN_SUPPRESSED_DWELL_TIME_MS = 6000;
    private static final float HYSTERESIS_THRESHOLD = 0.20f;
    private static final float BACKWARD_HYSTERESIS_THRESHOLD = 0.35f;
    private static final long MIN_PEEK_INTERVAL_MS = 2000;
    private static final int MAX_SEEKING_TICKS = 200;
    // GO_TO must fall back to direct navigation promptly if cover search is
    // queued behind a large squad or repeatedly fails to produce a route.
    private static final int MAX_GO_TO_RELOCATION_TICKS = MAX_SEEKING_TICKS;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    private static final float FOLLOW_COVER_DISTANCE = 15.0f;
    private static final int REPEATED_SUPPRESSION_EPISODE_THRESHOLD = 3;
    private static final int PRESSURED_PEEK_DECISION_INTERVAL_TICKS = 10;
    private static final double PRESSURED_PEEK_RADIUS = 8.0D;
    private static final float PRESSURED_PEEK_MIN_CHANCE = 0.10f;
    private static final float PRESSURED_PEEK_RECOVERY_WEIGHT = 0.40f;
    private static final float PRESSURED_PEEK_ALLY_WEIGHT = 0.20f;
    private static final float PRESSURED_PEEK_MAX_CHANCE = 0.90f;
    
    private static final double FOLLOW_COVER_SEARCH_RADIUS = 15.0D;
    private static final double FOLLOW_REGROUP_DISTANCE = 10.0D;
    private static final int RELOCATION_SEARCH_RADIUS = 12;
    private static final int FOLLOW_COVER_RETRY_TICKS = 40;
    private static final long CONTINUOUS_SUPPRESSION_REPOSITION_DELAY_MS = 8000L;
    private static final int SUPPRESSION_ROUTE_CANDIDATE_LIMIT = 8;
    private static final double CRAWL_ROUTE_SPEED = 0.65D;
    // Vanilla pathfinding is bounded by FOLLOW_RANGE. Far relocation targets need
    // progressive paths until they enter this exact-path validation radius.
    private static final double RELOCATION_EXACT_PATH_DISTANCE = 32.0D;
    private static final double MIN_STAGED_PATH_PROGRESS = 1.0D;
    // FOLLOW cover target is replaced when the owner has moved far enough
    // from the selected cover's position (18 blocks ~= FOLLOW_COVER_SEARCH_RADIUS + margin).
    private static final double FOLLOW_TARGET_STALE_DISTANCE = 18.0D;
    private static final int FOLLOW_REPLAN_COOLDOWN_TICKS = 40;
    
    private static final double POSITIONING_TOLERANCE = 0.05;
    private static final double POSITIONING_SPEED = 1.0;
    private static final long BLACKLIST_CLEAR_INTERVAL_MS = 15000;
    
    private static final double THREAT_ANGLE_REPOSITION_THRESHOLD = 2.09;
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 40;

    private static final float FLANKING_PROTECTION_THRESHOLD = 0.7f;
    private static final float MIN_FLANKING_IMPROVEMENT = 0.1f;
    private static final long FLANK_REPOSITION_COOLDOWN_MS = 5000;
    private static final int FLANK_SEARCH_RETRY_TICKS = 40;
    private static final double MID_MOVE_ANGLE_THRESHOLD = 1.05;

    // Attack-mode constants
    private static final int ATTACK_FORWARD_BIAS_BLOCKS = 6;
    private static final double ATTACK_MIN_FORWARD_PROGRESS = 2.0;
    private static final long ATTACK_MIN_DWELL_MS = 4000;
    private static final long ATTACK_MAX_DWELL_MS = 8000;
    private static final double ATTACK_OBJECTIVE_RADIUS = 4.0;

    // Attack corridor constants (search rectangle along objective direction)
    private static final int ATTACK_CORRIDOR_FORWARD_LENGTH = 24;
    private static final int ATTACK_CORRIDOR_HALF_WIDTH = 6;
    private static final int ATTACK_WIDE_SECTOR_HALF_WIDTH = 16;
    private static final int ATTACK_CORRIDOR_SEARCH_RADIUS = 24;
    private static final double ATTACK_FRONTIER_TOLERANCE = 2.0;
    private static final int ATTACK_CORRIDOR_REFRESH_TICKS = 15;
    // Fallback advance when no cover found in corridor
    private static final double ATTACK_FALLBACK_ADVANCE_LENGTH = 4.0;
    private static final int ATTACK_FALLBACK_STUCK_TICKS = 60;

    // Attack phase state
    private AttackPhase attackPhase = AttackPhase.NONE;
    private int attackCommandGeneration = -1;
    private long attackPhaseStartTime = 0;
    private long attackCoverArrivalTime = 0;
    private BlockPos attackExpectedCover = null;
    private boolean attackDwellEligible = false;
    private long attackDwellDelay = 0;
    private int attackAdvanceStaggerTicks = 0;
    private boolean attackHasPeekedThisCover = false;

    // Attack progress tracking: best (closest) distance to objective this command
    private double attackBestObjectiveDist = Double.MAX_VALUE;
    // Forward frontier: any cover closer to objective than this is acceptable
    private double attackFrontierDistance = Double.MAX_VALUE;

    private long lastFlankRepositionTime = 0;
    private long failedFlankSearchFingerprint = Long.MIN_VALUE;
    private int nextFlankSearchTick = 0;

    // Fallback advance state (no cover found — walk forward and re-search)
    private BlockPos fallbackAdvanceTarget = null;
    private int fallbackStuckTicks = 0;
    private Vec3 fallbackLastPosition = null;
    private int fallbackNoProgressResets = 0;

    private final Set<BlockPos> failedCoverPositions = new HashSet<>();
    private final java.util.Map<BlockPos, BlacklistEntry> blacklistReasons = new java.util.HashMap<>();
    private long lastBlacklistClearTime = 0;
    private BlockPos lastFailedCover = null;
    private int nonPeekableTicks = 0;
    private int nextShotInCoverSearchTick = 0;
    private BlockPos compromisedCoverPosition = null;
    private boolean emergencyCoverSearchActive = false;
    private BlockPos suppressionEpisodeCover = null;
    private int suppressionEpisodeCount = 0;
    private boolean suppressionEpisodeActive = false;
    private long continuousSuppressionStartTime = 0L;
    private boolean continuousSuppressionRepositionStarted = false;
    private int nextPressuredPeekDecisionTick = 0;
    private boolean suppressionRouteSearchActive = false;
    private Vec3 suppressionRouteFiringOrigin = null;
    private SuppressionRoutePlan selectedSuppressionRoute = null;
    private RouteMovement activeSuppressionRouteMovement = RouteMovement.NORMAL;
    private int nextSuppressionRouteSearchTick = 0;
    private int peekCycleLogTick = 0;
    
    private CoverPoint pendingRetryCover = null;
    private boolean isRetryAttempt = false;
    private boolean reloadHoldActive;
    private int reloadMovementLogCooldown;
    private boolean healingPosturePending;

    private enum RelocationType { NONE, GO_TO, FOLLOW }

    private enum RouteNodeExposure { PROTECTED, CRAWL_SAFE, EXPOSED }

    private enum RouteMovement { NORMAL, CRAWL }

    private record SuppressionRoutePlan(CoverPoint cover, Path path, RouteMovement movement,
                                        Vec3 firingOrigin) {}

    private RelocationType relocationType = RelocationType.NONE;
    private BlockPos relocationCenter = null;
    private int relocationCommandGeneration = -1;
    private int failedGoToRelocationGeneration = -1;
    private int nextFollowRelocationSearchTick = 0;
    private int followReplanCooldownTicks = 0;

    private boolean coverSearchPending;
    private boolean asyncPilotPending;
    private long asyncPilotSubmittedTick = Long.MIN_VALUE;
    private boolean asyncPilotFallback;
    private QueuedSearchMode queuedSearchMode;
    private int queuedAttackGeneration = -1;
    private RelocationType queuedRelocationType = RelocationType.NONE;
    private BlockPos queuedRelocationCenter;

    private BlockPos movementAttemptTarget = null;
    private int movementAttemptCount = 0;
    private final Map<BlockPos, Path> validatedCoverPaths = new HashMap<>();
    private BlockPos validatedCoverPathSource = null;
    private long validatedCoverPathTick = Long.MIN_VALUE;

    private CoverFinder.ScoredCover[] cachedTopCovers = new CoverFinder.ScoredCover[0];
    private BlockPos debugSearchCenter = null;

    // Per-soldier rate limiters (ticks between repeated log lines)
    private static final int SNAPSHOT_INTERVAL = 20; // 1 second at 20 TPS
    private int snapshotCounter = 0;
    private int tickLogCounter = 0;
    private int peekLogCounter = 0;
    private int suppressionLogCounter = 0;
    private int threatReportLogCounter = 0;
    private int findNewTargetLogCounter = 0;
    private int moveCtlLogCounter = 0;

    // Debug logging gates — delegates to server-wide DiagnosticLogManager.
    public static void setAttackDebugLogging(boolean enabled) {
        DiagnosticLogManager.setAttackLoggingEnabled(enabled);
    }

    public static boolean isAttackDebugLoggingEnabled() {
        return DiagnosticLogManager.isAttackLoggingEnabled();
    }

    private boolean attackDebugLog() {
        return DiagnosticLogManager.isAttackLoggingEnabled() && soldier.hasValidAttackTarget();
    }

    public static void setDebugLogging(boolean enabled) {
        DiagnosticLogManager.setCoverLoggingEnabled(enabled);
    }

    public static boolean isDebugLoggingEnabled() {
        return DiagnosticLogManager.isCoverLoggingEnabled();
    }

    public static float calculatePressuredPeekChance(float suppression, int nearbyPeekers) {
        float recovery = Math.max(0.0f, Math.min(1.0f, (0.90f - suppression) / 0.40f));
        return Math.min(PRESSURED_PEEK_MAX_CHANCE,
            PRESSURED_PEEK_MIN_CHANCE
                + PRESSURED_PEEK_RECOVERY_WEIGHT * recovery
                + PRESSURED_PEEK_ALLY_WEIGHT * Math.max(0, nearbyPeekers));
    }

    public enum BlacklistReason {
        PATH_FAILED("PATH FAILED"),
        STUCK_SEEKING("STUCK SEEKING"),
        STUCK_REPOSITIONING("STUCK REPOS"),
        SHOT_IN_COVER("SHOT IN COVER"),
        POSITIONING_BLOCKED("POSITION BLOCKED");
        
        public final String label;
        BlacklistReason(String label) { this.label = label; }
    }
    
    public static class BlacklistEntry {
        public final BlacklistReason reason;
        public final long timestamp;
        
        public BlacklistEntry(BlacklistReason reason, long timestamp) {
            this.reason = reason;
            this.timestamp = timestamp;
        }
        
        public long getAgeMs(long now) {
            return now - timestamp;
        }
    }
    
    public java.util.Map<BlockPos, BlacklistEntry> getBlacklistReasons() {
        return blacklistReasons;
    }
    
    public CoverTacticalGoal(SoldierEntity soldier) {
        this(soldier, false);
    }

    public CoverTacticalGoal(SoldierEntity soldier, boolean machineGunnerPipeline) {
        this.soldier = soldier;
        this.navigation = soldier.getNavigation();
        this.machineGunnerPipeline = machineGunnerPipeline;
        this.proneFiringController = new ProneFiringController(soldier);
        // This goal remains active while occupying cover, so it must retain the
        // scheduler's movement lock across every cover-state transition.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Installs a role-specific cover-selection override (e.g. the MG's fire
     * lane first selection). Riflemen never set this, so their base selection
     * is unchanged. Null clears the override.
     */
    public void setCoverSelectionStrategy(@javax.annotation.Nullable CoverSelectionStrategy strategy) {
        this.coverSelectionStrategy = strategy;
    }

    /**
     * Queues routine cover work without allowing a command burst to execute
     * every search in the same server tick. Emergency suppression searches use
     * their existing synchronous path until they can be integrated separately.
     */
    private void requestCoverSearch(QueuedSearchMode mode) {
        if (!soldier.isAlive() || soldier.level().isClientSide) {
            return;
        }

        if (coverSearchPending) {
            if (searchPriority(mode) < searchPriority(queuedSearchMode)) {
                queuedSearchMode = mode;
                queuedAttackGeneration = soldier.getAttackGeneration();
                queuedRelocationType = relocationType;
                queuedRelocationCenter = relocationCenter;
            }
            CoverSearchScheduler.request(this, searchPriority(queuedSearchMode),
                CoverSearchScheduler.currentServerTick(this), isEmergencySearchMode(queuedSearchMode));
            return;
        }

        int priority = searchPriority(mode);
        int stagger = priority == 2
            ? Math.floorMod(soldier.getUUID().hashCode(), CoverSearchScheduler.ROUTINE_STAGGER_TICKS)
            : 0;
        coverSearchPending = true;
        queuedSearchMode = mode;
        queuedAttackGeneration = soldier.getAttackGeneration();
        queuedRelocationType = relocationType;
        queuedRelocationCenter = relocationCenter;
        CoverSearchScheduler.request(this, priority,
            CoverSearchScheduler.currentServerTick(this) + stagger, isEmergencySearchMode(mode));
    }

    private int searchPriority(QueuedSearchMode mode) {
        return isEmergencySearchMode(mode) ? 0
            : mode == QueuedSearchMode.ATTACK_SELECTING ? 1
            : mode == QueuedSearchMode.REPOSITION || relocationType != RelocationType.NONE ? 0 : 2;
    }

    private boolean isEmergencySearchMode(QueuedSearchMode mode) {
        return mode == QueuedSearchMode.SHOT_IN_COVER
            || mode == QueuedSearchMode.CONTINUOUS_SUPPRESSION
            || mode == QueuedSearchMode.SUPPRESSION_REPOSITION;
    }

    private void requestEmergencySearch(QueuedSearchMode mode) {
        if (!StevesArmyConfig.isRetryPolicyEnabled()) {
            executeQueuedEmergencySearch(mode);
            return;
        }
        if (!coverSearchPending || queuedSearchMode != mode) {
            requestCoverSearch(mode);
        }
    }

    int getSoldierTickCount() {
        return soldier.tickCount;
    }

    ServerLevel getServerLevel() {
        return soldier.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    /** Called only by CoverSearchScheduler on the server thread. */
    void executeQueuedCoverSearch() {
        if (!coverSearchPending) {
            return;
        }

        QueuedSearchMode mode = queuedSearchMode;
        coverSearchPending = false;
        queuedSearchMode = null;
        int expectedAttackGeneration = queuedAttackGeneration;
        RelocationType expectedRelocationType = queuedRelocationType;
        BlockPos expectedRelocationCenter = queuedRelocationCenter;
        queuedAttackGeneration = -1;
        queuedRelocationType = RelocationType.NONE;
        queuedRelocationCenter = null;

        if (!soldier.isAlive() || soldier.level().isClientSide) {
            return;
        }
        if (isEmergencySearchMode(mode)) {
            executeQueuedEmergencySearch(mode);
            return;
        }
        if (mode == QueuedSearchMode.ATTACK_SELECTING
            && (!soldier.hasValidAttackTarget() || expectedAttackGeneration != soldier.getAttackGeneration())) {
            PerformanceMetrics.recordCoverSearchRequestStale();
            return;
        }
        if (expectedRelocationType != RelocationType.NONE
            && (relocationType != expectedRelocationType
                || !Objects.equals(relocationCenter, expectedRelocationCenter)
                || !isRelocationStillValid())) {
            PerformanceMetrics.recordCoverSearchRequestStale();
            return;
        }

        CoverMoveResult result = findAndMoveToCover();
        if (mode == QueuedSearchMode.ATTACK_SELECTING && attackPhase == AttackPhase.SELECTING_COVER) {
            handleQueuedAttackSearchResult(result);
        } else if ((mode == QueuedSearchMode.REPOSITION || relocationType != RelocationType.NONE)
            && getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
        }
    }

    @Override
    public void applyAsyncCoverPilotResult(CoverSearchResult result, BlockPos sourcePosition, long sourceTick) {
        if (!asyncPilotPending) {
            PerformanceMetrics.recordPhase6StaleResult();
            return;
        }
        asyncPilotPending = false;
        asyncPilotSubmittedTick = Long.MIN_VALUE;
        if (!StevesArmyConfig.isAsyncCoverPilotEnabled()
            || asyncPilotFallback
            || !isAsyncCoverPilotEligible()
            || !soldier.blockPosition().equals(sourcePosition)
            || result == null) {
            runAsyncPilotFallback();
            return;
        }

        CoverFinder finder = new CoverFinder(soldier.level());
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        for (CoverSearchResult.RankedCandidate ranked : result.rankedCandidates()) {
            BlockPos position = ranked.candidate().position();
            if (failedCoverPositions.contains(position)
                || (currentCover != null && currentCover.getPosition().equals(position))
                || !CoverReservationManager.isAvailableFor(position, soldier)) {
                continue;
            }
            CoverPoint cover = finder.evaluatePosition(position, null);
            if (cover == null || cover.getType() == CoverType.NONE
                || !cover.getProtectedDirections().equals(ranked.candidate().protectedDirections())) {
                PerformanceMetrics.recordPhase6ValidationReject();
                continue;
            }
            cover.setQuality(ranked.score());
            cover.setCombatScore(ranked.score());
            if (currentCover != null
                && soldier.position().distanceTo(cover.getPosition().getCenter()) < COVER_REACHED_DISTANCE) {
                blacklistCover(cover.getPosition(), BlacklistReason.STUCK_REPOSITIONING);
                continue;
            }
            getCoverManager().clearCoverQualityPenalty();
            cancelProneFiringPlan();
            if (!CoverReservationManager.reserve(position, soldier)) {
                PerformanceMetrics.recordPhase6ReservationReject();
                continue;
            }
            getCoverManager().setTargetCover(cover);
            if (!moveToCover(cover)) {
                CoverReservationManager.release(position, soldier);
                getCoverManager().clearTargetCover();
                PerformanceMetrics.recordPhase6PathReject();
                continue;
            }
            PerformanceMetrics.recordPhase6Selection();
            return;
        }
        runAsyncPilotFallback();
    }

    @Override
    public void rejectAsyncCoverPilot() {
        if (!asyncPilotPending) {
            return;
        }
        asyncPilotPending = false;
        asyncPilotSubmittedTick = Long.MIN_VALUE;
        runAsyncPilotFallback();
    }

    private boolean isAsyncCoverPilotEligible() {
        return !machineGunnerPipeline && !soldier.hasValidAttackTarget()
            && relocationType == RelocationType.NONE && !suppressionRouteSearchActive
            && getCoverManager().getState() == CoverBehaviorManager.CoverState.SEEKING_COVER;
    }

    private void runAsyncPilotFallback() {
        PerformanceMetrics.recordPhase6Fallback();
        asyncPilotFallback = true;
        try {
            if (soldier.isAlive() && isAsyncCoverPilotEligible()) {
                findAndMoveToCover();
            }
        } finally {
            asyncPilotFallback = false;
        }
    }

    private void executeQueuedEmergencySearch(QueuedSearchMode mode) {
        CoverBehaviorManager coverManager = getCoverManager();
        if (mode == QueuedSearchMode.SHOT_IN_COVER) {
            CoverPoint currentCover = coverManager.getCurrentCover();
            if (!coverManager.isShotInCoverRepositionRequested() || currentCover == null) {
                PerformanceMetrics.recordEmergencyCoverRequestStale();
                return;
            }
            if (currentCover != null) {
                blacklistCover(currentCover.getPosition(), BlacklistReason.SHOT_IN_COVER);
                compromisedCoverPosition = currentCover.getPosition();
            }
            coverManager.resetPeekState();
            coverManager.setPeekPosition(null);
            getPositionController().clear();
            emergencyCoverSearchActive = true;
            CoverMoveResult result;
            try {
                result = findSuppressionMoveToCover();
            } finally {
                emergencyCoverSearchActive = false;
            }
            if (result == CoverMoveResult.COVER_STARTED && coverManager.getTargetCover() != null) {
                coverManager.clearShotInCoverRepositionRequest();
                nextShotInCoverSearchTick = 0;
                compromisedCoverPosition = null;
                coverManager.setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            } else {
                nextShotInCoverSearchTick = soldier.tickCount + SHOT_IN_COVER_RETRY_TICKS;
                coverManager.setState(coverManager.isSuppressed()
                    ? CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER
                    : CoverBehaviorManager.CoverState.IN_COVER);
            }
            return;
        }

        if (mode == QueuedSearchMode.CONTINUOUS_SUPPRESSION
            && !coverManager.isContinuousSuppressionRepositionRequested()) {
            PerformanceMetrics.recordEmergencyCoverRequestStale();
            return;
        }
        if (mode == QueuedSearchMode.SUPPRESSION_REPOSITION
            && !coverManager.isRepositionRequested()) {
            PerformanceMetrics.recordEmergencyCoverRequestStale();
            return;
        }
        if (mode == QueuedSearchMode.SUPPRESSION_REPOSITION && !canLeaveCoverNow()) {
            PerformanceMetrics.recordEmergencyCoverRequestStale();
            return;
        }

        boolean started = startSuppressionRepositioning();
        if (started) {
            if (mode == QueuedSearchMode.CONTINUOUS_SUPPRESSION) {
                coverManager.clearContinuousSuppressionRepositionRequest();
            } else {
                coverManager.clearRepositionRequest();
            }
            resetSuppressionEpisodes();
        } else {
            nextSuppressionRouteSearchTick = soldier.tickCount + SHOT_IN_COVER_RETRY_TICKS;
        }
    }

    private void handleQueuedAttackSearchResult(CoverMoveResult result) {
        if (result == CoverMoveResult.NO_COVER_FOUND || result == CoverMoveResult.NO_ELIGIBLE_COVER) {
            startFallbackAdvance();
            attackPhase = AttackPhase.SELECTING_COVER;
            return;
        }

        attackPhase = AttackPhase.MOVING_TO_COVER;
        if (getCoverManager().getCurrentCover() == null && getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        } else if (getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
        }
    }

    /**
     * Consults the installed cover-selection strategy over the path's
     * already-scored candidates. Returns empty when no strategy is installed or
     * when the strategy defers, so callers always fall back to the base
     * selection.
     */
    private Optional<CoverPoint> selectPreferredCover(List<CoverFinder.ScoredCover> candidates) {
        if (coverSelectionStrategy == null) {
            return Optional.empty();
        }
        return coverSelectionStrategy.select(soldier, candidates);
    }
    
    private CoverBehaviorManager getCoverManager() {
        return soldier.getCoverBehaviorManager();
    }

    /**
     * Gives a GO_TO command first claim on reachable cover near its assigned
     * formation slot. False leaves the command's normal location movement intact.
     */
    public boolean requestGoToRelocation(BlockPos destination, int commandGeneration) {
        if (destination == null || !soldier.hasValidPingMoveTarget()
            || soldier.getPingMoveGeneration() != commandGeneration
            || failedGoToRelocationGeneration == commandGeneration) {
            return false;
        }
        if (relocationType == RelocationType.GO_TO
            && relocationCommandGeneration == commandGeneration) {
            return true;
        }

        clearRelocationTarget();
        relocationType = RelocationType.GO_TO;
        relocationCenter = destination.immutable();
        relocationCommandGeneration = commandGeneration;
        goToRelocationTicks = 0;
        return true;
    }

    public boolean isHandlingGoToRelocation(int commandGeneration) {
        return relocationType == RelocationType.GO_TO
            && relocationCommandGeneration == commandGeneration;
    }

    private boolean beginFollowRelocationIfNeeded() {
        if (relocationType != RelocationType.NONE || soldier.getSquadMode() != SquadMode.FOLLOW
            || getCoverManager().isSuppressed() || soldier.tickCount < nextFollowRelocationSearchTick) {
            return relocationType == RelocationType.FOLLOW;
        }

        LivingEntity owner = soldier.getOwner();
        if (owner == null || !owner.isAlive() || owner.isSpectator()
            || soldier.distanceToSqr(owner) < FOLLOW_COVER_DISTANCE * FOLLOW_COVER_DISTANCE) {
            return false;
        }

        relocationType = RelocationType.FOLLOW;
        relocationCenter = owner.blockPosition().immutable();
        relocationCommandGeneration = -1;
        return true;
    }

    private boolean isRelocationStillValid() {
        if (relocationType == RelocationType.GO_TO) {
            return soldier.hasValidPingMoveTarget()
                && soldier.getPingMoveGeneration() == relocationCommandGeneration;
        }
        if (relocationType == RelocationType.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            return soldier.getSquadMode() == SquadMode.FOLLOW && owner != null
                && owner.isAlive() && !owner.isSpectator();
        }
        return false;
    }

    private void clearRelocationTarget() {
        CoverPoint targetCover = getCoverManager().getTargetCover();
        if (targetCover != null && relocationType != RelocationType.NONE) {
            CoverReservationManager.release(targetCover.getPosition(), soldier);
            getCoverManager().clearTargetCover();
        }
        relocationType = RelocationType.NONE;
        relocationCenter = null;
        relocationCommandGeneration = -1;
        goToRelocationTicks = 0;
    }

    private void failGoToRelocation() {
        if (relocationType != RelocationType.GO_TO) {
            return;
        }

        failedGoToRelocationGeneration = relocationCommandGeneration;
        CoverSearchScheduler.cancel(this);
        coverSearchPending = false;
        queuedSearchMode = null;
        queuedAttackGeneration = -1;
        queuedRelocationType = RelocationType.NONE;
        queuedRelocationCenter = null;
        pendingRetryCover = null;
        isRetryAttempt = false;
        navigation.stop();
        getPositionController().clear();
        clearRelocationTarget();
        getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
    }
    
    private PeekController getPeekController() {
        return soldier.getPeekController();
    }
    
    private ThreatAwareness getThreats() {
        return soldier.getThreatAwareness();
    }

    /**
     * Direction used to score cover candidates. Roles may aim cover selection
     * via SoldierEntity.getPreferredCoverEvaluationDirection(); the default is
     * the primary threat direction (rifleman behavior).
     */
    private Vec3 getCoverSearchDirection() {
        Vec3 preferred = soldier.getPreferredCoverEvaluationDirection();
        if (preferred != null) {
            return preferred;
        }
        return getThreats().getPrimaryDirection(soldier.position());
    }

    /**
     * Uses a small rearward squad anchor for MG cover searches in FOLLOW and
     * ATTACK. Other roles and HOLD mode retain the existing search centers.
     */
    private BlockPos getMachineGunnerSearchCenter() {
        if (!(soldier instanceof MachineGunnerEntity machineGunner)) {
            return null;
        }
        if (!soldier.hasValidAttackTarget() && soldier.getSquadMode() != SquadMode.FOLLOW) {
            return null;
        }
        BlockPos target = soldier.hasValidAttackTarget()
            ? soldier.getAttackTargetPos() : machineGunner.getSuppressionCenter();
        return SupportPositionFinder.findRearAnchor(machineGunner, target);
    }

    /**
     * Counts entries into suppression while occupying the same cover. Individual
     * rounds and ticks inside one suppressed state do not create extra episodes.
     */
    private void trackSuppressionEpisode() {
        CoverBehaviorManager coverManager = getCoverManager();
        CoverPoint currentCover = coverManager.getCurrentCover();
        if (currentCover == null || !coverManager.isInCover()) {
            resetSuppressionEpisodes();
            return;
        }

        BlockPos coverPosition = currentCover.getPosition();
        if (suppressionEpisodeCover == null || !suppressionEpisodeCover.equals(coverPosition)) {
            suppressionEpisodeCover = coverPosition;
            suppressionEpisodeCount = 0;
            suppressionEpisodeActive = false;
        }

        boolean pressured = coverManager.isSuppressed();
        boolean pinned = coverManager.isPinned();
        if (!pressured) {
            suppressionEpisodeActive = false;
            continuousSuppressionStartTime = 0L;
            continuousSuppressionRepositionStarted = false;
            return;
        }

        if (!pinned) {
            continuousSuppressionStartTime = 0L;
            continuousSuppressionRepositionStarted = false;
        } else {
            if (continuousSuppressionStartTime == 0L) {
                continuousSuppressionStartTime = System.currentTimeMillis();
            } else if (!continuousSuppressionRepositionStarted
                && System.currentTimeMillis() - continuousSuppressionStartTime >= CONTINUOUS_SUPPRESSION_REPOSITION_DELAY_MS
                && !coverManager.isContinuousSuppressionRepositionRequested()) {
                continuousSuppressionRepositionStarted = true;
                coverManager.requestContinuousSuppressionReposition();
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} continuously pinned at {} for {}ms, queued immediate reposition",
                        soldier.getId(), coverPosition, CONTINUOUS_SUPPRESSION_REPOSITION_DELAY_MS);
                }
            }
        }

        if (suppressionEpisodeActive) {
            return;
        }

        suppressionEpisodeActive = true;
        suppressionEpisodeCount++;
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} suppression episode {}/{} at cover {}",
                soldier.getId(), suppressionEpisodeCount, REPEATED_SUPPRESSION_EPISODE_THRESHOLD, coverPosition);
        }

        if (suppressionEpisodeCount >= REPEATED_SUPPRESSION_EPISODE_THRESHOLD
            && !coverManager.isRepositionRequested()) {
            coverManager.requestReposition();
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} repeatedly suppressed at {}, queued reposition after recovery",
                    soldier.getId(), coverPosition);
            }
        }
    }

    private void resetSuppressionEpisodes() {
        suppressionEpisodeCover = null;
        suppressionEpisodeCount = 0;
        suppressionEpisodeActive = false;
        continuousSuppressionStartTime = 0L;
        continuousSuppressionRepositionStarted = false;
        nextPressuredPeekDecisionTick = 0;
    }

    /**
     * Centralized policy: may the soldier leave its occupied cover?
     * A suppressed soldier must remain in cover until fully recovered
     * (suppression below threshold + 2.5s since last event).
     * Exceptions: emergency flank movement while pinned may be added
     * as an explicit named policy later.
     */
    private boolean canLeaveCoverNow() {
        return getCoverManager().getSuppressionTracker().isRecovered();
    }

    private boolean isExposedToFlank() {
        if (soldier.isCQB() || soldier.hasCloseRangeTarget()) return false;
        if (System.currentTimeMillis() - lastFlankRepositionTime < FLANK_REPOSITION_COOLDOWN_MS) {
            PerformanceMetrics.recordCoverSearchCooldownSkip();
            return false;
        }

        CoverPoint cover = getCoverManager().getCurrentCover();
        if (cover == null) return false;

        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) return true;

        List<ThreatAwareness.ThreatInfo> threats = getThreats().getThreatInfos();
        if (threats.isEmpty()) return false;

        for (ThreatAwareness.ThreatInfo info : threats) {
            Vec3 toThreat = Vec3.atCenterOf(info.position).subtract(soldier.position());
            Direction threatDir = CoverFinder.getDirectionFromVector(toThreat);
            if (!protectedDirs.contains(threatDir)) {
                return true;
            }
        }
        return false;
    }

    private float getWeightedFlankingProtection(CoverPoint cover) {
        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) return 0.0f;

        List<ThreatAwareness.ThreatInfo> threats = getThreats().getThreatInfos();
        if (threats.isEmpty()) return 1.0f;

        float protectedWeight = 0.0f;
        float totalWeight = 0.0f;

        for (ThreatAwareness.ThreatInfo info : threats) {
            Vec3 toThreat = Vec3.atCenterOf(info.position).subtract(cover.getPosition().getCenter());
            Direction threatDir = CoverFinder.getDirectionFromVector(toThreat);

            if (protectedDirs.contains(threatDir)) {
                protectedWeight += info.weight;
            }
            totalWeight += info.weight;
        }

        return totalWeight > 0 ? protectedWeight / totalWeight : 1.0f;
    }

    /**
     * Find a flank-reposition cover using the correct search center and squad context.
     * Returns the best candidate that improves weighted flank protection by at least MIN_FLANKING_IMPROVEMENT.
     */
    private Optional<CoverPoint> findBetterCoverForFlank() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);

        Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        int searchRadius = SEARCH_RADIUS;
        BlockPos searchCenter = soldier.blockPosition();

        if (soldier.hasValidAttackTarget()) {
            BlockPos attackPos = soldier.getAttackTargetPos();
            Vec3 toTarget = new Vec3(
                attackPos.getX() - soldier.getX(),
                0,
                attackPos.getZ() - soldier.getZ()
            );
            double distToTarget = toTarget.length();
            if (distToTarget > 1.0) {
                toTarget = toTarget.normalize();
                double ahead = Math.min(distToTarget * 0.5, 10.0);
                searchCenter = soldier.blockPosition().offset(
                    (int)(toTarget.x * ahead),
                    0,
                    (int)(toTarget.z * ahead)
                );
            }
        } else if (soldier.getSquadMode() == SquadMode.HOLD) {
            BlockPos holdPos = soldier.getHoldPosition();
            if (holdPos != null && !holdPos.equals(BlockPos.ZERO)) {
                searchCenter = holdPos;
            }
        } else if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner instanceof Player) {
                searchCenter = owner.blockPosition();
                searchRadius = (int) FOLLOW_COVER_SEARCH_RADIUS;
            }
        }

        BlockPos machineGunnerAnchor = getMachineGunnerSearchCenter();
        if (machineGunnerAnchor != null) {
            searchCenter = machineGunnerAnchor;
            if (soldier.hasValidAttackTarget()) {
                searchRadius = ATTACK_CORRIDOR_SEARCH_RADIUS;
            }
        }

        // Use squad-aware evaluation from the correct center, excluding other soldiers' reservations
        List<CoverFinder.ScoredCover> scored;
        if (!searchCenter.equals(soldier.blockPosition())) {
            scored = finder.evaluateAndScoreAllFromCenter(
                searchCenter, soldier, threatDirection, threats, ATTACK_CORRIDOR_SEARCH_RADIUS, squadCtx);
        } else {
            scored = finder.evaluateAndScoreAll(
                soldier, threatDirection, threats, searchRadius, false, squadCtx);
        }

        CoverPoint currentCover = getCoverManager().getCurrentCover();
        float currentProtection = currentCover != null ? getWeightedFlankingProtection(currentCover) : 0.0f;

        for (CoverFinder.ScoredCover sc : scored) {
            if (currentCover != null && sc.cover.getPosition().equals(currentCover.getPosition())) {
                continue;
            }
            float newProtection = getWeightedFlankingProtection(sc.cover);
            if (newProtection - currentProtection >= MIN_FLANKING_IMPROVEMENT) {
                return Optional.of(sc.cover);
            }
        }

        return Optional.empty();
    }

    /**
     * Check if the soldier should reposition for flank reasons.
     * Returns an Optional containing the target cover if repositioning is warranted.
     */
    private Optional<CoverPoint> shouldRepositionForFlank() {
        if (!isExposedToFlank()) return Optional.empty();

        // Suppressed soldiers stay in cover — don't allow flank repositioning
        if (!canLeaveCoverNow()) return Optional.empty();

        float currentProtection = getWeightedFlankingProtection(getCoverManager().getCurrentCover());
        if (currentProtection >= FLANKING_PROTECTION_THRESHOLD) {
            return Optional.empty();
        }

        long fingerprint = getFlankSearchFingerprint();
        if (StevesArmyConfig.isRetryPolicyEnabled()
            && failedFlankSearchFingerprint == fingerprint
            && soldier.tickCount < nextFlankSearchTick) {
            PerformanceMetrics.recordFlankSearchRetrySkip();
            return Optional.empty();
        }
        if (StevesArmyConfig.isRetryPolicyEnabled()
            && failedFlankSearchFingerprint != Long.MIN_VALUE
            && failedFlankSearchFingerprint != fingerprint) {
            PerformanceMetrics.recordFlankSearchFingerprintChange();
            failedFlankSearchFingerprint = Long.MIN_VALUE;
            nextFlankSearchTick = 0;
        }

        PerformanceMetrics.recordFlankSearchAttempt();
        Optional<CoverPoint> betterCover = findBetterCoverForFlank();
        if (betterCover.isEmpty()) {
            PerformanceMetrics.recordFlankSearchFailure();
            if (StevesArmyConfig.isRetryPolicyEnabled()) {
                failedFlankSearchFingerprint = fingerprint;
                nextFlankSearchTick = soldier.tickCount + FLANK_SEARCH_RETRY_TICKS;
            }
            return Optional.empty();
        }

        failedFlankSearchFingerprint = Long.MIN_VALUE;
        nextFlankSearchTick = 0;

        float newProtection = getWeightedFlankingProtection(betterCover.get());
        if (newProtection - currentProtection < MIN_FLANKING_IMPROVEMENT) {
            return Optional.empty();
        }

        return betterCover;
    }

    /**
     * Identifies the live inputs that can change a failed flank-search result.
     * Threat weights are intentionally omitted because they decay every tick;
     * block positions and the quantized primary direction capture meaningful
     * geometry changes without defeating the retry policy on every tick.
     */
    private long getFlankSearchFingerprint() {
        long fingerprint = 0xcbf29ce484222325L;
        fingerprint = mixFingerprint(fingerprint, getCoverManager().getCurrentCover().getPosition().asLong());
        fingerprint = mixFingerprint(fingerprint, soldier.blockPosition().asLong());
        fingerprint = mixFingerprint(fingerprint, soldier.getSquadMode().ordinal());

        LivingEntity owner = soldier.getOwner();
        if (owner != null) {
            fingerprint = mixFingerprint(fingerprint, owner.blockPosition().asLong());
        }

        BlockPos holdPosition = soldier.getHoldPosition();
        if (holdPosition != null) {
            fingerprint = mixFingerprint(fingerprint, holdPosition.asLong());
        }

        Vec3 primaryDirection = getThreats().getPrimaryDirection(soldier.position());
        if (primaryDirection != null && primaryDirection.lengthSqr() > 0.001) {
            fingerprint = mixFingerprint(fingerprint,
                CoverFinder.getDirectionFromVector(primaryDirection).ordinal());
        }

        for (ThreatAwareness.ThreatInfo threat : getThreats().getThreatInfos()) {
            fingerprint = mixFingerprint(fingerprint, threat.position.asLong());
        }
        return fingerprint;
    }

    private static long mixFingerprint(long fingerprint, long value) {
        fingerprint ^= value;
        return fingerprint * 0x100000001b3L;
    }
    
    @Override
    public boolean canUse() {
        if (soldier.isHealing()) {
            return canContinueHealingInCover();
        }
        if (!soldier.isAlive()) return false;
        
        // ATTACK mode: always try to use cover (cover-to-cover advance)
        if (soldier.hasValidAttackTarget()) {
            return true;
        }

        if (relocationType != RelocationType.NONE && !isRelocationStillValid()) {
            clearRelocationTarget();
        }
        beginFollowRelocationIfNeeded();
        if (relocationType != RelocationType.NONE) {
            return true;
        }

        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        
        if (soldier.hasValidPingMoveTarget() && !soldier.hasValidAttackTarget()) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverGoal] canUse=false: hasValidPingMoveTarget");
            }
            return false;
        }
        
        getCoverManager().tickSuppression(getCoverManager().isInCover());
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        boolean result;
        switch (state) {
            case NO_COVER: {
                result = shouldSeekCover();
                break;
            }
            case SEEKING_COVER:
                result = true;
                break;
            case IN_COVER:
            case SUPPRESSED_IN_COVER: {
                CoverPoint cover = getCoverManager().getCurrentCover();
                if (cover != null) {
                    double distance = soldier.position().distanceTo(cover.getPosition().getCenter());
                    if (distance > COVER_ABANDON_DISTANCE) {
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] canUse=false: cover abandoned (dist={})", distance);
                        }
                        getCoverManager().resetPeekState();
                        getPositionController().clear();
                        getCoverManager().clearCover();
                        cooldown = COOLDOWN_TICKS;
                        result = false;
                        break;
                    }
                }
                result = true;
                break;
            }
            case REPOSITIONING:
                result = true;
                break;
            default:
                result = false;
                break;
        }
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] canUse={}, state={}, hasThreat={}, suppressed={}, health={}",
                result, state, getThreats().hasActiveThreat(),
                getCoverManager().isSuppressed(),
                String.format("%.2f", soldier.getHealth() / soldier.getMaxHealth()));
        }
        return result;
    }
    
    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (soldier.isHealing()) {
            return canContinueHealingInCover();
        }

        if (relocationType != RelocationType.NONE && !isRelocationStillValid()) {
            navigation.stop();
            getPositionController().clear();
            clearRelocationTarget();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            return false;
        }
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        if (state == CoverBehaviorManager.CoverState.IN_COVER && !getCoverManager().isSuppressed()) {
            if (soldier.getSquadMode() == SquadMode.FOLLOW) {
                LivingEntity owner = soldier.getOwner();
                if (owner != null && soldier.distanceToSqr(owner) > 15 * 15) {
                    getCoverManager().clearCover();
                    getCoverManager().resetPeekState();
                    getPositionController().clear();
                    cooldown = COOLDOWN_TICKS;
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] canContinueToUse=false: FOLLOW mode, not suppressed, far from owner");
                    }
                    return false;
                }
            }
        }
        
        boolean result = state != CoverBehaviorManager.CoverState.NO_COVER || 
                         soldier.hasValidAttackTarget();
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] canContinueToUse={}, state={}", result, state);
        }
        return result;
    }
    
    @Override
    public void start() {
        stuckTicks = 0;
        reevaluateCounter = 0;
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        // Initialize attack phase if we have a valid attack target
        if (soldier.hasValidAttackTarget()) {
            initAttackPhase();
        }
        
        if (state == CoverBehaviorManager.CoverState.NO_COVER) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            requestCoverSearch(soldier.hasValidAttackTarget()
                ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.NORMAL);
        } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER || 
                   state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            if (getCoverManager().getTargetCover() == null) {
                requestCoverSearch(soldier.hasValidAttackTarget()
                    ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.NORMAL);
            } else {
                moveToCover(getCoverManager().getTargetCover());
            }
        }
    }
    
    @Override
    public void stop() {
        CoverSearchScheduler.cancel(this);
        coverSearchPending = false;
        asyncPilotPending = false;
        asyncPilotSubmittedTick = Long.MIN_VALUE;
        queuedSearchMode = null;
        queuedAttackGeneration = -1;
        queuedRelocationType = RelocationType.NONE;
        queuedRelocationCenter = null;
        boolean hadRelocation = relocationType != RelocationType.NONE;
        if (hadRelocation) {
            clearRelocationTarget();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
        }
        boolean wasHealing = soldier.isHealing();
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        boolean preserveCoverForHealing = wasHealing
            && (state == CoverBehaviorManager.CoverState.IN_COVER
                || state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER)
            && getCoverManager().getCurrentCover() != null;

        if (wasHealing) {
            soldier.getHealController().stop();
        }

        if (preserveCoverForHealing) {
            healingPosturePending = false;
            pendingRetryCover = null;
            isRetryAttempt = false;
            stuckTicks = 0;
            return;
        }
        
        // Don't clear cover if we're in ATTACK mode - preserve state across interruptions
        if (soldier.hasValidAttackTarget()) {
            return;
        }

        // A higher-priority behavior is taking over. Do not leave an orphaned
        // prone plan active after this goal stops being ticked.
        cancelProneFiringPlan();
        
        if (state == CoverBehaviorManager.CoverState.IN_COVER ||
            state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
            getCoverManager().clearCover();
        } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER ||
                   state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            getCoverManager().clearTargetCover();
        }
        
        pendingRetryCover = null;
        isRetryAttempt = false;
        healingPosturePending = false;
        cooldown = COOLDOWN_TICKS;
        stuckTicks = 0;
    }
    
    @Override
    public void tick() {
        PerformanceMetrics.recordCoverTick(getCoverManager().getState().name(), machineGunnerPipeline);
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        getCoverManager().tickSuppression(getCoverManager().isInCover());
        trackSuppressionEpisode();

        if (soldier.isHealing()) {
            if (canContinueHealingInCover()) {
                soldier.getHealController().tick();
                CoverPoint currentCover = getCoverManager().getCurrentCover();
                if (currentCover != null) {
                    maintainCoverAnchorIfHiding(currentCover);
                }
                tickProneFiringPlan();
                populateCoverDebugData();
                return;
            }
            soldier.getHealController().stop();
        }

        if (relocationType != RelocationType.NONE && !isRelocationStillValid()) {
            navigation.stop();
            getPositionController().clear();
            clearRelocationTarget();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            return;
        }

        if (relocationType == RelocationType.GO_TO
            && ++goToRelocationTicks > MAX_GO_TO_RELOCATION_TICKS) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverRelocation] Soldier {} GO_TO relocation timed out after {} ticks",
                    soldier.getId(), goToRelocationTicks);
            }
            failGoToRelocation();
            return;
        }

        if (relocationType == RelocationType.FOLLOW && relocationCenter != null) {
            LivingEntity owner = soldier.getOwner();
            if (owner != null) {
                BlockPos ownerPos = owner.blockPosition().immutable();
                relocationCenter = ownerPos;

                CoverPoint targetCover = getCoverManager().getTargetCover();
                if (targetCover != null && !getCoverManager().isInCover()
                    && followReplanCooldownTicks <= 0
                    && ownerPos.distSqr(targetCover.getPosition()) > FOLLOW_TARGET_STALE_DISTANCE * FOLLOW_TARGET_STALE_DISTANCE) {
                    CoverReservationManager.release(targetCover.getPosition(), soldier);
                    getCoverManager().clearTargetCover();
                    getPositionController().clear();
                    followReplanCooldownTicks = FOLLOW_REPLAN_COOLDOWN_TICKS;
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverRelocation] Soldier {} FOLLOW target stale (owner moved), replacing",
                            soldier.getId());
                    }
                }
                if (followReplanCooldownTicks > 0) {
                    followReplanCooldownTicks--;
                }
            }
        }
        
        // Sync threat direction to client for debug rendering
        Vec3 threatDir = getThreats().getThreatDirectionForProactivePeek(soldier.position());
        soldier.syncThreatDirection(threatDir);
        
        PeekController peekCtrl = getPeekController();

        // Reloading keeps a soldier protected in occupied cover, but must not
        // strand one in the open after a cover destination has been chosen.
        if (soldier.isPreparingOrReloading()) {
            reloadHoldActive = true;
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            if (currentCover != null) {
                holdForReload(currentCover);
            } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER) {
                continueCoverMovementDuringReload(state);
                tickSeekingCover();
            } else if (state == CoverBehaviorManager.CoverState.REPOSITIONING) {
                continueCoverMovementDuringReload(state);
                tickRepositioning();
            } else {
                holdForReload(null);
            }
            tickProneFiringPlan();
            populateCoverDebugData();
            return;
        }

        if (reloadHoldActive) {
            reloadHoldActive = false;
            resumeMovementAfterReload();
        }

        if (state != CoverBehaviorManager.CoverState.SEEKING_COVER) {
            seekingTicks = 0;
        }
        
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            tickLogCounter++;
            if (tickLogCounter >= SNAPSHOT_INTERVAL) {
                tickLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tick: coverState={}, peekState={}, hasThreat={}, suppression={}",
                    soldier.getId(), state, peekCtrl.getState(),
                    getThreats().hasActiveThreat(),
                    String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));
            }

            peekLogCounter++;
            if (peekLogCounter >= SNAPSHOT_INTERVAL) {
                peekLogCounter = 0;
                CoverPositionController ctrl = getPositionController();
                CoverPoint cover = getCoverManager().getCurrentCover();
                double distToCover = cover != null ? soldier.position().distanceTo(cover.getPosition().getCenter()) : -1;
                Vec3 vel = soldier.getDeltaMovement();
                double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                Vec3 ctrlTarget = ctrl.getDebugTargetPos();
                double ctrlDist = ctrlTarget != null ?
                    Math.sqrt(Math.pow(ctrlTarget.x - soldier.getX(), 2) + Math.pow(ctrlTarget.z - soldier.getZ(), 2)) : -1;
                StevesArmyMod.LOGGER.info("[PeekCycle] Soldier {} state={} peek={} ctrlResult={} ctrlDist={} distToCover={} speed={} nonPeekable={} peekCount={}",
                    soldier.getId(), state, peekCtrl.getState(),
                    ctrl.getLastResult(), String.format("%.2f", ctrlDist), String.format("%.2f", distToCover), String.format("%.4f", speed),
                    getCoverManager().isNonPeekableCover(), peekCtrl.getPeekCountSameCover());
            }
        }
        
        switch (state) {
            case SEEKING_COVER:
                tickSeekingCover();
                break;
            case REPOSITIONING:
                tickRepositioning();
                break;
            case IN_COVER:
                tickInCover();
                break;
            case SUPPRESSED_IN_COVER:
                tickSuppressedInCover();
                break;
            case NO_COVER:
                // Attack mode: trigger immediate cover search
                if (soldier.hasValidAttackTarget() && attackPhase == AttackPhase.NONE) {
                    initAttackPhase();
                }
                break;
        }

        // Cover owns the defensive position, so its prone settling timer must
        // continue even when CombatGoal has temporarily stopped for lack of a target.
        tickProneFiringPlan();
        
        // Attack mode: run phase logic after state handling
        if (soldier.hasValidAttackTarget()) {
            // Periodic attack-mode snapshot every 20 ticks
            if (soldier.tickCount % 20 == 0) {
                CoverPoint tgt = getCoverManager().getTargetCover();
                CoverPoint cur = getCoverManager().getCurrentCover();
                BlockPos targetPos = tgt != null ? tgt.getPosition() : null;
                double dist = targetPos != null ? soldier.position().distanceTo(targetPos.getCenter()) : -1;
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) phase={} state={} targetCover={} currentCover={} distToTarget={} isCQB={}",
                    soldier.getId(), soldier.getName().getString(), attackPhase, getCoverManager().getState(),
                    targetPos, cur != null ? cur.getPosition() : null,
                    String.format("%.2f", dist), soldier.isCQB());
            }
            tickAttackPhase();
        } else {
            // Non-ATTACK: log structured transition if debug enabled (rate-limited)
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                snapshotCounter++;
                if (snapshotCounter >= SNAPSHOT_INTERVAL) {
                    snapshotCounter = 0;
                    CoverPoint cur = getCoverManager().getCurrentCover();
                    CoverPoint tgt = getCoverManager().getTargetCover();
                    StevesArmyMod.LOGGER.info("[CoverSnapshot] Soldier {} state={} attackPhase={} current={} target={} sup={} recovered={}",
                        soldier.getId(), state, attackPhase,
                        cur != null ? cur.getPosition() : "null",
                        tgt != null ? tgt.getPosition() : "null",
                        String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()),
                        getCoverManager().getSuppressionTracker().isRecovered());
                }
            }
        }
        
        populateCoverDebugData();
    }
    
    private void tickSeekingCover() {
        if (proneFiringDestination != null) {
            tickProneFiringMovement();
            return;
        }
        // Handle pending retry from previous tick
        if (pendingRetryCover != null) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} retrying path to cover {}", 
                    soldier.getId(), pendingRetryCover.getPosition());
            }
            isRetryAttempt = true;
            boolean movementStarted = moveToCover(pendingRetryCover);
            isRetryAttempt = false;
            pendingRetryCover = null;
            if (!movementStarted && relocationType == RelocationType.GO_TO) {
                failGoToRelocation();
            }
            return;
        }
        
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        if (targetCover == null) {
            if (asyncPilotPending) {
                if (soldier.level().getGameTime() - asyncPilotSubmittedTick > 40L) {
                    asyncPilotPending = false;
                    asyncPilotSubmittedTick = Long.MIN_VALUE;
                    runAsyncPilotFallback();
                }
                return;
            }
            requestCoverSearch(soldier.hasValidAttackTarget()
                ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.NORMAL);
            seekingTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }

        ensureMovementAttemptTarget(targetCover);
        if (movementAttemptCount == 0) {
            moveToCover(targetCover);
            return;
        }

        seekingTicks++;
        
        CoverPositionController moveControl = getPositionController();
        CoverPositionController.MovementResult moveResult = moveControl.getLastResult();
        Vec3 standingPos = getCoverStandingPosition(targetCover.getPosition());
        double horizontalDist = Math.sqrt(
            Math.pow(soldier.position().x - standingPos.x, 2) +
            Math.pow(soldier.position().z - standingPos.z, 2));
        
        // 1. Position controller reached the target or soldier is already at standing pos
        if (moveResult == CoverPositionController.MovementResult.REACHED_TARGET
            || horizontalDist < POSITIONING_TOLERANCE) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) reached cover at hDist={} cover={}",
                    soldier.getId(), soldier.getName().getString(), String.format("%.2f", horizontalDist), targetCover.getPosition());
            }
            onCoverReached(targetCover);
            seekingTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        // 2. Position controller is still working — wait
        if (moveResult == CoverPositionController.MovementResult.IN_PROGRESS) {
            return;
        }
        
        // 3. Position controller failed — retry navigation once
        if (moveResult == CoverPositionController.MovementResult.FAILED) {
            CoverPositionController.FailureReason failReason = moveControl.getLastFailureReason();
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) position controller FAILED (reason={}) for cover={}, retrying navigation",
                    soldier.getId(), soldier.getName().getString(), failReason, targetCover.getPosition());
            }
            moveControl.clear();
            navigation.stop();
            moveToCover(targetCover);
            return;
        }
        
        // Measure distance to the standing block position, not just the cover center
        if (horizontalDist < COVER_REACHED_DISTANCE) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) reached standing pos at hDist={} cover={}",
                    soldier.getId(), soldier.getName().getString(), String.format("%.2f", horizontalDist), targetCover.getPosition());
            }
            onCoverReached(targetCover);
            seekingTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        // Handoff to position controller when close to standing position
        if (horizontalDist < COVER_VALID_DISTANCE) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) handoff to position controller at hDist={} cover={}",
                    soldier.getId(), soldier.getName().getString(), String.format("%.2f", horizontalDist), targetCover.getPosition());
            }
            navigation.stop();
            moveControl.moveTo(standingPos, POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickSeekingCover", "recenter to target cover");
            return;
        }
        
        // Normal navigation-driven approach
        Vec3 currentPos = soldier.position();
        
        if (navigation.isDone()) {
            if (relocationType != RelocationType.NONE) {
                // A staged path has ended. Recalculate immediately: the next route
                // may now be short enough to require exact cover validation.
                moveToCover(targetCover);
                stuckTicks = 0;
                noProgressTicks = 0;
                lastSeekingPosition = currentPos;
                return;
            }
            if (movementAttemptCount == 1) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} retrying cover path after it ended before arrival: target={}",
                        soldier.getId(), targetCover.getPosition());
                }
                moveToCover(targetCover);
                stuckTicks = 0;
                return;
            }
            stuckTicks++;
            noProgressTicks = 0;
            lastSeekingPosition = null;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (targetCover != null) {
                    if (soldier.hasValidAttackTarget()) {
                        StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) stuck seeking cover, blacklisting. cover={}",
                            soldier.getId(), soldier.getName().getString(), targetCover.getPosition());
                    }
                    blacklistCover(targetCover.getPosition(), BlacklistReason.STUCK_SEEKING);
                }
                getCoverManager().clearTargetCover();
                stuckTicks = 0;
                requestCoverSearch(soldier.hasValidAttackTarget()
                    ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.NORMAL);
            }
        } else {
            stuckTicks = 0;
            
            if (lastSeekingPosition != null) {
                double moved = currentPos.distanceTo(lastSeekingPosition);
                if (moved < 0.1) {
                    noProgressTicks++;
                    if (noProgressTicks > 40) {
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} not progressing toward cover ({} ticks, moved {}), retrying navigation",
                                soldier.getId(), noProgressTicks, String.format("%.2f", moved));
                        }
                        navigation.stop();
                        moveToCover(targetCover);
                        noProgressTicks = 0;
                        lastSeekingPosition = currentPos;
                    }
                } else {
                    noProgressTicks = 0;
                    lastSeekingPosition = currentPos;
                }
            } else {
                lastSeekingPosition = currentPos;
            }
        }
        
        int seekingTimeout = relocationType != RelocationType.NONE ? MAX_SEEKING_TICKS * 3 : MAX_SEEKING_TICKS;
        if (seekingTicks > seekingTimeout) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) ATTACK seeking timeout, resetting. cover={}",
                    soldier.getId(), soldier.getName().getString(), targetCover != null ? targetCover.getPosition() : "null");
            }
            getCoverManager().resetPeekState();
            getPositionController().clear();
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            seekingTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
        }
    }
    
private void tickRepositioning() {
        // Handle pending retry from previous tick
        if (pendingRetryCover != null) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} retrying path to cover {}",
                    soldier.getId(), pendingRetryCover.getPosition());
            }
            isRetryAttempt = true;
            boolean movementStarted = moveToCover(pendingRetryCover);
            isRetryAttempt = false;
            pendingRetryCover = null;
            if (!movementStarted && relocationType == RelocationType.GO_TO) {
                failGoToRelocation();
            }
            return;
        }

        CoverPoint targetCover = getCoverManager().getTargetCover();
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        // ATTACK owns the chosen bound until deterministic path recovery rejects it.
        // Its path may temporarily lead away from the objective to exit a structure,
        // so the generic threat-shift reconsideration must not replace it mid-route.
        if (!soldier.hasValidAttackTarget() && soldier.getRandom().nextFloat() < 0.5f) {
            Vec3 currentThreatDir = getThreats().getPrimaryDirection(soldier.position());
            Vec3 entryThreatDir = getCoverManager().getEntryThreatDirection();
            
            if (currentThreatDir != null && entryThreatDir != null && 
                currentThreatDir.lengthSqr() > 0.01 && entryThreatDir.lengthSqr() > 0.01) {
                double dot = currentThreatDir.dot(entryThreatDir) / (currentThreatDir.length() * entryThreatDir.length());
                double angle = Math.acos(net.minecraft.util.Mth.clamp(dot, -1.0, 1.0));
                
                if (angle > MID_MOVE_ANGLE_THRESHOLD) {
                    if (soldier.getRandom().nextFloat() < 0.5f) {
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} mid-move threat shift, cancelling reposition",
                                soldier.getId());
                        }
                        getCoverManager().clearTargetCover();
                        requestCoverSearch(soldier.hasValidAttackTarget()
                            ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.NORMAL);
                        return;
                    } else {
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} mid-move threat shift, committing to move",
                                soldier.getId());
                        }
                    }
                }
            }
        }
        
        if (targetCover == null) {
            if (currentCover != null) {
                getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
            } else {
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            }
            return;
        }
        
        if (currentCover != null && targetCover.getPosition().equals(currentCover.getPosition())) {
            onCoverReached(targetCover);
            return;
        }

        ensureMovementAttemptTarget(targetCover);

        if (movementAttemptCount == 0) {
            moveToCover(targetCover);
            return;
        }

        CoverPositionController moveControl = getPositionController();
        CoverPositionController.MovementResult moveResult = moveControl.getLastResult();
        Vec3 standingPos = getCoverStandingPosition(targetCover.getPosition());
        double horizontalDist = Math.sqrt(
            Math.pow(soldier.position().x - standingPos.x, 2) +
            Math.pow(soldier.position().z - standingPos.z, 2));

        // 1. Position controller reached the target or soldier is already at standing pos
        if (moveResult == CoverPositionController.MovementResult.REACHED_TARGET
            || horizontalDist < POSITIONING_TOLERANCE) {
            onCoverReached(targetCover);
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }

        // 2. Position controller is still working — wait
        if (moveResult == CoverPositionController.MovementResult.IN_PROGRESS) {
            return;
        }

        // 3. Position controller failed — retry navigation once
        if (moveResult == CoverPositionController.MovementResult.FAILED) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) reposition controller FAILED (reason={}) for cover={}, retrying nav",
                    soldier.getId(), soldier.getName().getString(), moveControl.getLastFailureReason(), targetCover.getPosition());
            }
            moveControl.clear();
            navigation.stop();
            moveToCover(targetCover);
            return;
        }
        
        if (horizontalDist < COVER_REACHED_DISTANCE) {
            onCoverReached(targetCover);
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        // Handoff to position controller when close to standing position
        if (horizontalDist < COVER_VALID_DISTANCE) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) reposition handoff to position controller at hDist={} cover={}",
                    soldier.getId(), soldier.getName().getString(), String.format("%.2f", horizontalDist), targetCover.getPosition());
            }
            navigation.stop();
            moveControl.moveTo(standingPos, POSITIONING_TOLERANCE,
                activeSuppressionRouteMovement == RouteMovement.CRAWL ? CRAWL_ROUTE_SPEED : POSITIONING_SPEED,
                "tickRepositioning", "recenter to target cover");
            return;
        }
        
        // Normal navigation-driven approach
        Vec3 currentPos = soldier.position();
        
        if (navigation.isDone()) {
            if (movementAttemptCount == 1) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} retrying cover path after it ended before arrival: target={}",
                        soldier.getId(), targetCover.getPosition());
                }
                moveToCover(targetCover);
                stuckTicks = 0;
                return;
            }
            stuckTicks++;
            noProgressTicks = 0;
            lastSeekingPosition = null;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (targetCover != null) {
                    blacklistCover(targetCover.getPosition(), BlacklistReason.STUCK_REPOSITIONING);
                }
                getCoverManager().clearTargetCover();
                if (currentCover != null) {
                    getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
                } else {
                    getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                }
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
            
            if (lastSeekingPosition != null) {
                double moved = currentPos.distanceTo(lastSeekingPosition);
                if (moved < 0.1) {
                    noProgressTicks++;
                    if (noProgressTicks > 40) {
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} not progressing toward target cover ({} ticks, moved {}), retrying navigation",
                                soldier.getId(), noProgressTicks, String.format("%.2f", moved));
                        }
                        navigation.stop();
                        moveToCover(targetCover);
                        noProgressTicks = 0;
                        lastSeekingPosition = currentPos;
                    }
                } else {
                    noProgressTicks = 0;
                    lastSeekingPosition = currentPos;
                }
            } else {
                lastSeekingPosition = currentPos;
            }
        }
    }

    private void tickProneFiringMovement() {
        Vec3 destination = proneFiringDestination.getCenter();
        if (soldier.position().distanceToSqr(destination) <= POSITIONING_TOLERANCE * POSITIONING_TOLERANCE) {
            navigation.stop();
            proneFiringController.onReached();
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[DefensivePosition] Soldier {} reached prone lane {}", soldier.getId(), proneFiringDestination);
            }
            proneFiringDestination = null;
            return;
        }
        if (navigation.isDone()) {
            proneFiringController.cancel("path_failed");
            proneFiringDestination = null;
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        }
    }

    private void tickProneFiringPlan() {
        if (proneFiringDestination == null) {
            proneFiringController.tick(soldier.getTarget());
        }
    }
    
    private void tickInCover() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (soldier.isPreparingOrReloading()) {
            holdForReload(currentCover);
            return;
        }

        if (currentCover != null && currentCover.getType() == CoverType.HALF
            && soldier.isLowCrouching() && !getCoverManager().isSuppressed()
            && !healingPosturePending) {
            getPeekController().recoverStandingInHalfCover(soldier, "unsuppressed-fallback");
        }

        if (currentCover != null) {
            Vec3 standingPos = getCoverStandingPosition(currentCover.getPosition());
            double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
            double standingDist = soldier.position().distanceTo(standingPos);
            PeekController peekCtrl = getPeekController();
            boolean peeking = peekCtrl.isExposed() || peekCtrl.isMovingToPeek() || peekCtrl.isReturning();
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                tickLogCounter++;
                if (tickLogCounter >= SNAPSHOT_INTERVAL) {
                    tickLogCounter = 0;
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tickInCover: dist={}, standingDist={}, abandon={}, valid={}, peeking={}, target={}",
                        soldier.getId(), String.format("%.2f", distance), String.format("%.2f", standingDist),
                        distance > COVER_ABANDON_DISTANCE,
                        standingDist > COVER_VALID_DISTANCE,
                        peeking,
                        (soldier.getTarget() != null ? soldier.getTarget().getName().getString() : "null"));
                }
            }
            if (distance > COVER_ABANDON_DISTANCE) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} drifted too far from cover ({} > {}), abandoning",
                        soldier.getId(), String.format("%.1f", distance), COVER_ABANDON_DISTANCE);
                }
                getCoverManager().clearCover();
                getPositionController().clear();
                return;
            }
            if (standingDist > COVER_VALID_DISTANCE && !peeking) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} got pushed from cover standing pos ({} > {}), re-seeking",
                        soldier.getId(), String.format("%.1f", standingDist), COVER_VALID_DISTANCE);
                }
                getCoverManager().clearCover();
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                getPositionController().clear();
                return;
            }
            
            // Recenter to cover when idle
            CoverPositionController.MovementResult recenterResult = getPositionController().getLastResult();
            if (!peeking && recenterResult != CoverPositionController.MovementResult.IN_PROGRESS) {
                if (recenterResult == CoverPositionController.MovementResult.FAILED) {
                    CoverPositionController.FailureReason failReason = getPositionController().getLastFailureReason();
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tickInCover recenter FAILED (reason={}) for cover={}, blacklisting and re-seeking",
                            soldier.getId(), failReason, currentCover.getPosition());
                    }
                    blacklistCover(currentCover.getPosition(), BlacklistReason.POSITIONING_BLOCKED);
                    getPositionController().clear();
                    getCoverManager().clearCover();
                    getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                    return;
                }
                if (!getPositionController().isWithinCoverAnchorDeadzone(standingPos)) {
                    navigation.stop();
                    getPositionController().moveTo(getCoverStandingPosition(currentCover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickInCover", "recenter to cover");
                }
            }
// Renew current cover reservation every 5 seconds so it doesn't expire
            // while the soldier is still occupying it
            if (soldier.tickCount % 100 == 0) {
                CoverReservationManager.reserve(currentCover.getPosition(), soldier);
            }
        }

// Flank detection (skip during ATTACK — attack phase owns movement decisions)
        if (!soldier.hasValidAttackTarget()) {
            Optional<CoverPoint> flankCover = shouldRepositionForFlank();
            if (flankCover.isPresent()) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} flanked, repositioning to {}",
                        soldier.getId(), flankCover.get().getPosition());
                }
                lastFlankRepositionTime = System.currentTimeMillis();
                startRepositioning(flankCover.get());
                return;
            }
        }

// Suppression transition must run BEFORE pending reposition requests,
        // so that a suppressed soldier immediately gets the correct posture
        // and state transition even if a reposition request is pending.
        if (getCoverManager().isSuppressed()) {
            soldier.tracePeek("cover-suppression", "tickInCover transition");
            enforceSuppressedHalfCoverPosture(currentCover);
            if (getPeekController().isExposed() && !soldier.hasEmergencyEngagementPosture()) {
                getPeekController().tick(soldier, currentCover, getPositionController());
            } else if (getPeekController().isExposed()) {
                soldier.tracePeek("suppression-override", "reason=emergency-engagement blocks exposed tick");
            }
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            if (currentCover != null && currentCover.getType() == CoverType.HALF
                && getPeekController().isStandingInHalfCover()) {
                getPeekController().enterHiding(soldier);
            }
            return;
        }

        // Process pending reposition requests. A compromised-cover request may move
        // immediately; routine requests still wait for full suppression recovery.
        PendingRepositionResult requestResult = processPendingRepositionRequests();
        if (requestResult == PendingRepositionResult.MOVEMENT_STARTED) {
            return;
        }
        // BLOCKED: fall through to allow peek/external evaluation

        if (shouldExitCoverForFollow()) {
            getCoverManager().resetPeekState();
            getPositionController().clear();
            getCoverManager().clearCover();
            return;
        }

        // Delegate peek to PeekController
        if (currentCover != null) {
            if (tryStartHealing(currentCover)) {
                return;
            }
            getPeekController().tick(soldier, currentCover, getPositionController());
            maintainCoverAnchorIfHiding(currentCover);
        }

        reevaluateCounter++;
        if (reevaluateCounter >= REEVALUATE_INTERVAL_TICKS) {
            reevaluateCounter = 0;
            evaluateCoverState();
        }
    }

    private void holdForReload(CoverPoint currentCover) {
        if (currentCover == null) {
            // A soldier is not protected until a cover point is physically
            // reached. Never enter the prone pose while reloading in the open.
            soldier.setLowCrouching(false);
            soldier.holdMovementForReload();
            return;
        }

        PeekController peekCtrl = getPeekController();
        if (peekCtrl.isExposed() || peekCtrl.isMovingToPeek()) {
            peekCtrl.forceReturnToCoverDuringReload(soldier, currentCover, getPositionController());
        }

        if (peekCtrl.isReturning()) {
            peekCtrl.forceReturnToCoverDuringReload(soldier, currentCover, getPositionController());
            peekCtrl.tick(soldier, currentCover, getPositionController());
        }

        if (currentCover.getType() == CoverType.HALF) {
            soldier.clearEmergencyEngagementPosture();
            soldier.setLowCrouching(true);
            peekCtrl.enterHiding(soldier);
        } else {
            soldier.setLowCrouching(soldier.isFiringProne());
        }

        soldier.holdMovementForReload();
    }

    private void resumeMovementAfterReload() {
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        if (state != CoverBehaviorManager.CoverState.SEEKING_COVER &&
            state != CoverBehaviorManager.CoverState.REPOSITIONING) {
            return;
        }

        soldier.setLowCrouching(false);
        CoverPoint targetCover = getCoverManager().getTargetCover();
        if (targetCover != null) {
            moveToCover(targetCover);
        }
    }

    private void continueCoverMovementDuringReload(CoverBehaviorManager.CoverState state) {
        // Keep the approach upright. Firing-prone posture is only valid after
        // onCoverReached() has promoted the target to current cover.
        soldier.setLowCrouching(false);
        if (DiagnosticLogManager.isCoverLoggingEnabled() && reloadMovementLogCooldown-- <= 0) {
            reloadMovementLogCooldown = 20;
            CoverPoint targetCover = getCoverManager().getTargetCover();
            StevesArmyMod.LOGGER.info("[ReloadCover] Soldier {} continuing {} during reload, target={}",
                soldier.getId(), state, targetCover != null ? targetCover.getPosition() : "searching");
        }
    }
    
    private void tickSuppressedInCover() {
        CoverBehaviorManager coverManager = getCoverManager();
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        enforceSuppressedHalfCoverPosture(currentCover);

        // A hit while hidden means this cover is compromised. Unlike routine cover
        // changes, this relocation may start while suppressed.
        if (processPendingRepositionRequests() == PendingRepositionResult.MOVEMENT_STARTED) {
            return;
        }

        if (!coverManager.isSuppressed()) {
            if (currentCover != null && currentCover.getType() == CoverType.HALF) {
                getPeekController().recoverStandingInHalfCover(soldier, "suppression-recovery");
            }
            coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
            return;
        }

        // Handle non-peekable cover reposition request — also keep pending while suppressed
        if (getCoverManager().isRepositionRequested()) {
            // Request stays pending; do nothing
        }

        // Force duck-back if soldier was exposed or moving to peek when suppressed
        PeekController peekCtrl = getPeekController();
        if (coverManager.isPinned()
            && (peekCtrl.isExposed() || peekCtrl.isMovingToPeek())) {
            if (!soldier.hasEmergencyEngagementPosture()) {
                soldier.tracePeek("pinned-return", "action=force-return");
                peekCtrl.forceReturnToCover(soldier, currentCover, getPositionController());
            } else {
                soldier.tracePeek("pinned-return", "action=blocked, reason=emergency-engagement");
            }
        }

        // Let peek controller handle ongoing duck back
        if (peekCtrl.isReturning()) {
            peekCtrl.tick(soldier, currentCover, getPositionController());
        }

        maintainCoverAnchorIfHiding(currentCover);

        // Flank detection is deferred while suppressed: the soldier will
        // evaluate flank repositioning after recovery in tickInCover()
        // (recovery transitions us from SUPPRESSED_IN_COVER back to IN_COVER)

        if (currentCover != null && tryStartHealing(currentCover)) {
            return;
        }

        if (!coverManager.isPinned() && peekCtrl.isHiding()) {
            boolean allowPressuredPeek = shouldAllowPressuredPeek();
            if (currentCover != null && currentCover.getType() == CoverType.HALF) {
                soldier.setLowCrouching(!allowPressuredPeek);
            }
            peekCtrl.tick(soldier, currentCover, getPositionController(), allowPressuredPeek);
        } else if (!coverManager.isPinned()) {
            peekCtrl.tick(soldier, currentCover, getPositionController());
        }
    }

    private boolean canContinueHealingInCover() {
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        if (state != CoverBehaviorManager.CoverState.IN_COVER
            && state != CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
            return false;
        }

        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover == null || !getCoverManager().isInCover()) return false;
        if (!soldier.getHealController().canContinue()) return false;
        if (!isHealingPosture(currentCover)) return false;
        if (relocationType != RelocationType.NONE || hasPendingCoverReposition()) return false;
        if (!isCoverStillValid()) return false;
        return isCoverAnchorSettled(currentCover);
    }

    private boolean tryStartHealing(CoverPoint currentCover) {
        if (!canAttemptHealingInCover(currentCover)) {
            healingPosturePending = false;
            return false;
        }

        // Eligibility must be checked before changing half-cover posture. An
        // available item alone must not keep a healthy soldier hidden forever.
        if (!soldier.getHealController().canStart()) {
            healingPosturePending = false;
            return false;
        }

        PeekController peekController = getPeekController();
        if (currentCover.getType() == CoverType.HALF && peekController.isStandingInHalfCover()) {
            soldier.clearEmergencyEngagementPosture();
            soldier.setLowCrouching(true);
            peekController.enterHiding(soldier);
            healingPosturePending = true;
            return true;
        }

        if (!peekController.isHiding()) {
            healingPosturePending = false;
            return false;
        }
        if (currentCover.getType() == CoverType.HALF && !soldier.isLowCrouching()) {
            soldier.setLowCrouching(true);
            healingPosturePending = true;
            return true;
        }

        healingPosturePending = false;
        return soldier.getHealController().start();
    }

    private boolean canAttemptHealingInCover(CoverPoint currentCover) {
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        if (state != CoverBehaviorManager.CoverState.IN_COVER
            && state != CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
            return false;
        }
        if (currentCover == null || !getCoverManager().isInCover()) return false;
        if (relocationType != RelocationType.NONE || hasPendingCoverReposition()) return false;
        if (!isCoverStillValid() || !isCoverAnchorSettled(currentCover)) return false;
        PeekController.State peekState = getPeekController().getState();
        return peekState == PeekController.State.HIDING
            || (currentCover.getType() == CoverType.HALF
                && peekState == PeekController.State.STANDING_IN_HALF_COVER);
    }

    private boolean isHealingPosture(CoverPoint currentCover) {
        if (!getPeekController().isHiding()) return false;
        return currentCover.getType() != CoverType.HALF || soldier.isLowCrouching();
    }

    private boolean isCoverAnchorSettled(CoverPoint currentCover) {
        if (getPositionController().getLastResult() == CoverPositionController.MovementResult.IN_PROGRESS) {
            return false;
        }
        return getPositionController().isWithinCoverAnchorDeadzone(
            getCoverStandingPosition(currentCover.getPosition()));
    }

    private boolean hasPendingCoverReposition() {
        CoverBehaviorManager manager = getCoverManager();
        return manager.isRepositionRequested()
            || manager.isShotInCoverRepositionRequested()
            || manager.isContinuousSuppressionRepositionRequested();
    }

    /** Keeps defensive low-crouch authoritative without cancelling an allowed pressured peek. */
    private void enforceSuppressedHalfCoverPosture(CoverPoint currentCover) {
        if (currentCover == null || currentCover.getType() != CoverType.HALF) {
            return;
        }
        if (!getCoverManager().isSuppressed()) {
            return;
        }
        if (soldier.hasEmergencyEngagementPosture()) {
            soldier.tracePeek("suppression-override", "reason=emergency-engagement blocks low-crouch");
            return;
        }

        PeekController peekController = getPeekController();
        if (!getCoverManager().isPinned()
            && (peekController.isExposed() || peekController.isMovingToPeek())) {
            return;
        }

        soldier.setLowCrouching(true);
        if (peekController.isStandingInHalfCover()) {
            peekController.enterHiding(soldier);
        }
    }

    private boolean shouldAllowPressuredPeek() {
        if (soldier.tickCount < nextPressuredPeekDecisionTick) {
            return false;
        }
        nextPressuredPeekDecisionTick = soldier.tickCount + PRESSURED_PEEK_DECISION_INTERVAL_TICKS;

        float suppression = getCoverManager().getSuppressionTracker().getSuppressionLevel();
        float recovery = Math.max(0.0f, Math.min(1.0f, (0.90f - suppression) / 0.40f));
        int nearbyPeekers = countNearbyPeekers();
        float chance = calculatePressuredPeekChance(suppression, nearbyPeekers);
        float roll = soldier.getRandom().nextFloat();
        boolean allowed = roll < chance;

        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[SuppressionPeek] Soldier {} pressured peek: suppression={}, recovery={}, nearby={}, chance={}, roll={}, allowed={}",
                soldier.getId(), String.format("%.2f", suppression), String.format("%.2f", recovery),
                nearbyPeekers, String.format("%.2f", chance), String.format("%.2f", roll), allowed);
        }
        return allowed;
    }

    private int countNearbyPeekers() {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return 0;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return 0;
        }

        double radiusSqr = PRESSURED_PEEK_RADIUS * PRESSURED_PEEK_RADIUS;
        return (int) SquadManager.get(serverLevel)
            .getSquadMembers(serverLevel, squadId, soldier.getUUID()).stream()
            .filter(SoldierEntity.class::isInstance)
            .map(SoldierEntity.class::cast)
            .filter(member -> member.isAlive() && member.distanceToSqr(soldier) <= radiusSqr)
            .filter(member -> {
                PeekController.State state = member.getPeekController().getState();
                return state == PeekController.State.MOVING_TO_PEEK
                    || state == PeekController.State.EXPOSED;
            })
            .count();
    }

    /**
     * Consolidated handling of pending reposition requests (shot-in-cover, non-peekable).
     * Returns NONE, BLOCKED (still waiting, but allow other cover processing),
     * or MOVEMENT_STARTED (soldier is now repositioning).
     * Routine requests remain pending while suppressed; a compromised cover is an
     * emergency exception because remaining there has already failed.
     */
    private enum PendingRepositionResult { NONE, BLOCKED, MOVEMENT_STARTED }

    private PendingRepositionResult processPendingRepositionRequests() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        // Shot-in-cover request
        if (getCoverManager().isShotInCoverRepositionRequested()) {
            if (soldier.tickCount < nextShotInCoverSearchTick) {
                return PendingRepositionResult.BLOCKED;
            }
            requestEmergencySearch(QueuedSearchMode.SHOT_IN_COVER);
            return PendingRepositionResult.BLOCKED;
        }

        // Continuous suppression is an emergency response. Unlike the normal
        // repeated-episode request, it may begin while the soldier is pinned.
        if (getCoverManager().isContinuousSuppressionRepositionRequested()) {
            if (soldier.tickCount < nextSuppressionRouteSearchTick) {
                return PendingRepositionResult.BLOCKED;
            }
            requestEmergencySearch(QueuedSearchMode.CONTINUOUS_SUPPRESSION);
            return PendingRepositionResult.BLOCKED;
        }

        // Non-peekable cover reposition request
        if (getCoverManager().isRepositionRequested()) {
            if (!canLeaveCoverNow()) {
                return PendingRepositionResult.BLOCKED;
            }
            if (soldier.tickCount < nextSuppressionRouteSearchTick) {
                return PendingRepositionResult.BLOCKED;
            }
            requestEmergencySearch(QueuedSearchMode.SUPPRESSION_REPOSITION);
            return PendingRepositionResult.BLOCKED;
        }

        return PendingRepositionResult.NONE;
    }
    
    private boolean shouldSeekCover() {
        ThreatAwareness threats = getThreats();

        if (relocationType != RelocationType.NONE) {
            return true;
        }

        if (soldier.hasValidAttackTarget()) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover=true (ATTACK mode)");
            }
            return true;
        }

        if (soldier.isCQB() || soldier.hasCloseRangeTarget()) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover=false (CQB mode={}, closeRangeTarget={})",
                    soldier.isCQB(), soldier.hasCloseRangeTarget());
            }
            return false;
        }
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            boolean hasValid = getCoverManager().getCurrentCover() != null && isCoverStillValid();
            boolean result = !hasValid;
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (HOLD mode, hasValidCover={})", result, hasValid);
            }
            return result;
        }
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            boolean suppressed = getCoverManager().isSuppressed();
            float healthRatio = soldier.getHealth() / soldier.getMaxHealth();
            boolean lowHealth = healthRatio < LOW_HEALTH_THRESHOLD;
            
            LivingEntity owner = soldier.getOwner();
            boolean closeToOwner = owner != null && 
                soldier.distanceToSqr(owner) < FOLLOW_COVER_DISTANCE * FOLLOW_COVER_DISTANCE;
            
            boolean result;
            if (closeToOwner) {
                boolean hasThreat = threats.hasActiveThreat();
                result = (suppressed || lowHealth) || hasThreat;
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (FOLLOW close, suppressed={}, lowHealth={} health={}, hasThreat={})",
                        result, suppressed, lowHealth, String.format("%.2f", healthRatio), hasThreat);
                }
            } else {
                result = suppressed || lowHealth;
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (FOLLOW far, suppressed={}, lowHealth={} health={})",
                        result, suppressed, lowHealth, String.format("%.2f", healthRatio));
                }
            }
            return result;
        }
        
        boolean suppressed = getCoverManager().isSuppressed();
        float healthRatio = soldier.getHealth() / soldier.getMaxHealth();
        boolean lowHealth = healthRatio < LOW_HEALTH_THRESHOLD;
        boolean hasThreat = threats.hasActiveThreat();
        
        boolean result = (suppressed || lowHealth) || hasThreat;
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (suppressed={}, lowHealth={} health={}, hasThreat={})",
                result, suppressed, lowHealth, String.format("%.2f", healthRatio), hasThreat);
        }
        return result;
    }
    
    private boolean isCoverStillValid() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover == null) return false;

        double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
        if (distance > COVER_ABANDON_DISTANCE) return false;

        PeekController peekCtrl = getPeekController();
        boolean peeking = peekCtrl.isExposed() || peekCtrl.isMovingToPeek() || peekCtrl.isReturning();
        if (peeking) return true;

        if (getCoverManager().getTimeInCover() >= MIN_COVER_DWELL_TIME_MS) {
            double maxDistance = soldier.getTarget() != null ? COMBAT_COVER_VALID_DISTANCE : COVER_VALID_DISTANCE;
            if (distance > maxDistance) return false;
        }

        // FOLLOW mode: invalidate cover if player has moved too far away
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner != null) {
                double distToOwner = currentCover.getPosition().distSqr(owner.blockPosition());
                if (distToOwner > 20 * 20) {
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Cover invalid: too far from owner (dist={})",
                            String.format("%.1f", Math.sqrt(distToOwner)));
                    }
                    return false;
                }
            }
        }

        return true;
    }
    
    private void evaluateCoverState() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover == null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            return;
        }

        // Skip when peeking
        PeekController peekCtrl = getPeekController();
        boolean peeking = peekCtrl.isExposed() || peekCtrl.isMovingToPeek() || peekCtrl.isReturning();
        if (peeking) return;

        if (!isCoverStillValid()) {
            startRepositioning();
            return;
        }

        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) return;

        // Delegate pending reposition requests to the consolidated handler
        PendingRepositionResult requestResult = processPendingRepositionRequests();
        if (requestResult == PendingRepositionResult.MOVEMENT_STARTED) return;

        // Threat direction change (only when recovered)
        if (canLeaveCoverNow()) {
            Vec3 currentThreatDir = getThreats().getPrimaryDirection(soldier.position());
            Vec3 entryThreatDir = getCoverManager().getEntryThreatDirection();
            if (currentThreatDir != null && entryThreatDir != null && currentThreatDir.lengthSqr() > 0.01 && entryThreatDir.lengthSqr() > 0.01) {
                double dot = currentThreatDir.dot(entryThreatDir) / (currentThreatDir.length() * entryThreatDir.length());
                double angle = Math.acos(net.minecraft.util.Mth.clamp(dot, -1.0, 1.0));
                if (angle > THREAT_ANGLE_REPOSITION_THRESHOLD) {
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} threat direction changed, repositioning",
                            soldier.getId());
                    }
                    startRepositioning();
                    return;
                }
            }
        }

        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            tickLogCounter++;
            if (tickLogCounter >= SNAPSHOT_INTERVAL) {
                tickLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tick: coverState={}, peekState={}, hasThreat={}, suppression={}",
                    soldier.getId(), getCoverManager().getState(), soldier.getPeekController().getState(),
                    soldier.getThreatAwareness().hasActiveThreat(), String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));
            }
        }

        // Check for better cover (only when recovered from suppression)
        if (canLeaveCoverNow()) {
            Optional<CoverPoint> betterCover = findBetterCover();
            if (betterCover.isPresent()) {
                CoverPoint newCover = betterCover.get();

                if (newCover.getPosition().equals(currentCover.getPosition())) return;

                float penalty = getCoverManager().getCoverQualityPenalty();
                float hysteresis = penalty > 0 ? 1.0f : 1.0f + HYSTERESIS_THRESHOLD;

                // In attack mode, penalize backward cover switching
                if (soldier.hasValidAttackTarget() && penalty <= 0) {
                    BlockPos attackPos = soldier.getAttackTargetPos();
                    Vec3 soldierPos = soldier.position();
                    Vec3 toObjective = new Vec3(
                        attackPos.getX() - soldierPos.x, 0, attackPos.getZ() - soldierPos.z);
                    Vec3 toCandidate = newCover.getPosition().getCenter().subtract(soldierPos);
                    boolean isBackward = toObjective.dot(toCandidate) < 0;
                    if (isBackward) {
                        hysteresis = 1.0f + BACKWARD_HYSTERESIS_THRESHOLD;
                    }
                }

                float currentScore = currentCover.getQuality() * hysteresis - penalty;
                float newScore = newCover.getQuality();

                if (newScore > currentScore) {
                    startRepositioning(newCover);
                }
            }
        }
    }
    
private boolean shouldExitCoverForFollow() {
        if (soldier.getSquadMode() != SquadMode.FOLLOW) return false;

        if (!canLeaveCoverNow()) return false;

        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) return false;

        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) return false;

        LivingEntity owner = soldier.getOwner();
        if (owner instanceof Player) {
            double distanceToOwner = soldier.distanceTo(owner);
            if (distanceToOwner > FOLLOW_REGROUP_DISTANCE) return true;
        }

        if (getThreats().hasActiveThreat()) return false;

        return true;
    }

    private void startRepositioning() {
        // Normal cover navigation uses standing/crouching dimensions. Do not
        // carry the suppressed half-cover prone posture into full-speed travel.
        activeSuppressionRouteMovement = RouteMovement.NORMAL;
        selectedSuppressionRoute = null;
        soldier.setLowCrouching(false);
        getCoverManager().resetPeekState();
        getCoverManager().setPeekPosition(null);
        getPositionController().clear();
        requestCoverSearch(soldier.hasValidAttackTarget()
            ? QueuedSearchMode.ATTACK_SELECTING : QueuedSearchMode.REPOSITION);
        if (getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        }
    }
    
    private boolean startRepositioning(CoverPoint newCover) {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        
        if (currentCover != null && newCover.getPosition().equals(currentCover.getPosition())) return false;

        // This is the ordinary cover-to-cover path, not a protected low-crouch
        // trench shift. Restore normal movement dimensions before pathfinding.
        activeSuppressionRouteMovement = RouteMovement.NORMAL;
        selectedSuppressionRoute = null;
        soldier.setLowCrouching(false);
        
        // Release old target cover reservation (we're choosing a new target)
        CoverPoint oldTarget = getCoverManager().getTargetCover();
        if (oldTarget != null) {
            CoverReservationManager.release(oldTarget.getPosition(), soldier);
        }
        getCoverManager().clearTargetCover();
        getCoverManager().resetPeekState();
        getCoverManager().setPeekPosition(null);
        getPositionController().clear();
        
        if (CoverReservationManager.reserve(newCover.getPosition(), soldier)) {
            if (currentCover != null) {
                getCoverManager().setLastCover(currentCover);
            }
            getCoverManager().setTargetCover(newCover);
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            boolean movementStarted = moveToCover(newCover);
            if (!movementStarted && pendingRetryCover == null) {
                if (getCoverManager().getTargetCover() != null) {
                    blacklistCover(newCover.getPosition(), BlacklistReason.PATH_FAILED);
                }
                getCoverManager().setState(currentCover != null
                    ? CoverBehaviorManager.CoverState.IN_COVER
                    : CoverBehaviorManager.CoverState.SEEKING_COVER);
                return false;
            }
            return true;
        }
        return false;
    }
    private CoverMoveResult findAndMoveToCover() {
        PerformanceMetrics.recordRoleCoverSearch(machineGunnerPipeline);
        long searchStarted = System.nanoTime();
        validatedCoverPaths.clear();
        validatedCoverPathSource = null;
        validatedCoverPathTick = Long.MIN_VALUE;
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        long now = System.currentTimeMillis();
        if (now - lastBlacklistClearTime > BLACKLIST_CLEAR_INTERVAL_MS) {
            failedCoverPositions.removeIf(pos -> !pos.equals(compromisedCoverPosition));
            blacklistReasons.entrySet().removeIf(entry -> !entry.getKey().equals(compromisedCoverPosition));
            lastBlacklistClearTime = now;
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} cleared failed cover blacklist", soldier.getId());
            }
        }
        
        Vec3 threatDirection = getCoverSearchDirection();
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        if (relocationType != RelocationType.NONE && relocationCenter != null) {
            debugSearchCenter = relocationCenter;
            List<CoverFinder.ScoredCover> relocationCovers = finder.evaluateAndScoreAllFromCenter(
                relocationCenter, soldier, threatDirection, threats,
                relocationType == RelocationType.FOLLOW ? (int) FOLLOW_COVER_SEARCH_RADIUS : RELOCATION_SEARCH_RADIUS,
                squadCtx);

            CoverPoint currentCover = getCoverManager().getCurrentCover();
            for (CoverFinder.ScoredCover scoredCover : relocationCovers) {
                CoverPoint cover = scoredCover.cover;
                if (failedCoverPositions.contains(cover.getPosition())
                    || (currentCover != null && cover.getPosition().equals(currentCover.getPosition()))) {
                    continue;
                }
                if (!isDistantRelocationCover(cover) && !isExactCoverPathReachable(cover)) {
                    continue;
                }
                if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                    getCoverManager().clearCoverQualityPenalty();
                    getCoverManager().setTargetCover(cover);
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverRelocation] Soldier {} selected {} cover={} score={} distance={} pathMode={}",
                            soldier.getId(), relocationType, cover.getPosition(), String.format("%.2f", scoredCover.score),
                            String.format("%.1f", horizontalDistanceToCover(cover)),
                            isDistantRelocationCover(cover) ? "staged" : "exact");
                    }
                    boolean movementStarted = moveToCover(cover);
                    if (!movementStarted && pendingRetryCover == null) {
                        failGoToRelocation();
                        logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND,
                            "relocation-path-failed");
                        return CoverMoveResult.NO_COVER_FOUND;
                    }
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.COVER_STARTED, "relocation");
                    return CoverMoveResult.COVER_STARTED;
                }
            }

            if (relocationType == RelocationType.GO_TO) {
                failedGoToRelocationGeneration = relocationCommandGeneration;
            } else {
                nextFollowRelocationSearchTick = soldier.tickCount + FOLLOW_COVER_RETRY_TICKS;
            }
            clearRelocationTarget();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND, "relocation-none");
            return CoverMoveResult.NO_COVER_FOUND;
        }
        int searchRadius = SEARCH_RADIUS;
        BlockPos searchCenter = soldier.blockPosition();

        if (soldier.hasValidAttackTarget()) {
            BlockPos attackPos = soldier.getAttackTargetPos();
            Vec3 toTarget = new Vec3(
                attackPos.getX() - soldier.getX(),
                0,
                attackPos.getZ() - soldier.getZ());
            double distToTarget = toTarget.length();
            if (distToTarget > 1.0) {
                toTarget = toTarget.normalize();
                double ahead = Math.min(distToTarget * 0.25, 6.0);
                searchCenter = soldier.blockPosition().offset(
                    (int)(toTarget.x * ahead), 0, (int)(toTarget.z * ahead));
            }
        } else if (soldier.getSquadMode() == SquadMode.HOLD) {
            BlockPos holdPos = soldier.getHoldPosition();
            if (holdPos != null && !holdPos.equals(BlockPos.ZERO)) {
                searchCenter = holdPos;
            }
        } else if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner instanceof Player) {
                if (getCoverManager().isSuppressed()) {
                    searchRadius = SEARCH_RADIUS;
                    searchCenter = soldier.blockPosition();
                } else {
                    searchCenter = owner.blockPosition();
                    searchRadius = (int) FOLLOW_COVER_SEARCH_RADIUS;
                }
            }
        }

        BlockPos machineGunnerAnchor = getMachineGunnerSearchCenter();
        if (machineGunnerAnchor != null) {
            searchCenter = machineGunnerAnchor;
            if (soldier.hasValidAttackTarget()) {
                searchRadius = ATTACK_CORRIDOR_SEARCH_RADIUS;
            }
        }
        this.debugSearchCenter = searchCenter;
        
        Optional<CoverPoint> bestCover = Optional.empty();
        List<CoverFinder.ScoredCover> reusableScored = null;

        if (suppressionRouteSearchActive && !soldier.hasValidAttackTarget()) {
            if (suppressionRouteFiringOrigin == null) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} withheld reposition: no firing origin is available",
                        soldier.getId());
                }
                logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_ELIGIBLE_COVER, "suppression-no-origin");
                return CoverMoveResult.NO_ELIGIBLE_COVER;
            }
            SuppressionRoutePlan routePlan = selectSuppressionRoute(finder, threatDirection, threats,
                searchRadius, squadCtx, suppressionRouteFiringOrigin);
            if (routePlan != null) {
                selectedSuppressionRoute = routePlan;
                bestCover = Optional.of(routePlan.cover());
            } else {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} withheld reposition: no protected or crawl-safe route found",
                        soldier.getId());
                }
                logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_ELIGIBLE_COVER, "suppression-no-safe-route");
                return CoverMoveResult.NO_ELIGIBLE_COVER;
            }
        }

        if (bestCover.isPresent()) {
            // The suppression route pass has already selected and path-validated this cover.
        } else if (soldier.hasValidAttackTarget()) {
            // ATTACK mode: forward-biased search from the beginning
            List<CoverFinder.ScoredCover> scored = finder.evaluateAndScoreAllFromCenter(
                searchCenter, soldier, threatDirection, threats, ATTACK_CORRIDOR_SEARCH_RADIUS, squadCtx);

            CoverPoint currentCover = getCoverManager().getCurrentCover();
            Vec3 soldierPos = soldier.position();
            Vec3 objectiveDir = null;
            if (soldier.hasValidAttackTarget()) {
                BlockPos obj = soldier.getAttackTargetPos();
                objectiveDir = new Vec3(obj.getX() - soldierPos.x, 0, obj.getZ() - soldierPos.z).normalize();
            }

            Direction threatDir = (threatDirection != null && threatDirection.lengthSqr() > 0.001)
                ? CoverFinder.getDirectionFromVector(threatDirection) : null;

            // Filter: require forward progress AND primary-threat protection,
            // skip current/blacklisted covers. Collect every eligible cover so a
            // role strategy can pick the best fire lane; the first eligible is
            // the unchanged base fallback.
            List<CoverFinder.ScoredCover> eligible = new ArrayList<>();
            for (CoverFinder.ScoredCover sc : scored) {
                CoverPoint cover = sc.cover;
                if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;
                if (failedCoverPositions.contains(cover.getPosition())) continue;

                if (!isAttackCorridorCandidate(cover.getPosition(), soldier.getAttackTargetPos(), objectiveDir)) {
                    logRejectedAttackCover(cover, soldier.getAttackTargetPos(), objectiveDir);
                    continue;
                }

                // Hard primary-threat protection preference
                if (!finder.isPrimaryThreatProtected(cover, soldier, threatDirection)) {
                    continue;
                }

                eligible.add(sc);
            }

            bestCover = selectPreferredCover(eligible);
            if (bestCover.isEmpty() && !eligible.isEmpty()) {
                bestCover = Optional.of(eligible.get(0).cover);
            }

            // If forward-biased found nothing protected, search from soldier position as fallback
            // (still requires primary-threat protection)
            if (bestCover.isEmpty()) {
                List<CoverFinder.ScoredCover> localScored = finder.evaluateAndScoreAll(
                    soldier, threatDirection, threats, ATTACK_CORRIDOR_SEARCH_RADIUS, true, squadCtx);
                for (CoverFinder.ScoredCover sc : localScored) {
                    CoverPoint cover = sc.cover;
                    if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;
                    if (failedCoverPositions.contains(cover.getPosition())) continue;

                    if (!isAttackCorridorCandidate(cover.getPosition(), soldier.getAttackTargetPos(), objectiveDir)) {
                        logRejectedAttackCover(cover, soldier.getAttackTargetPos(), objectiveDir);
                        continue;
                    }

                    // Fallback still requires primary-threat protection
                    if (!finder.isPrimaryThreatProtected(cover, soldier, threatDirection)) {
                        continue;
                    }

                    bestCover = Optional.of(cover);
                    break;
                }

                // The preferred assault lane may be blocked by a structure.
                // Before walking uncovered, accept a reachable forward cover in
                // a wider sector even when its protection is not an exact match.
                if (bestCover.isEmpty()) {
                    bestCover = findWideForwardAttackCover(localScored, currentCover,
                        soldier.getAttackTargetPos(), objectiveDir, threatDir);
                }
            }
        } else {
            if (!asyncPilotFallback && StevesArmyConfig.isAsyncCoverPilotEnabled()
                && isAsyncCoverPilotEligible()) {
                List<CoverPoint> discovered = finder.discoverCoverPoints(searchCenter, searchRadius);
                if (discovered.isEmpty()) {
                    PerformanceMetrics.recordPhase6Fallback();
                } else {
                    CoverProtectionContext protection = soldier.getCombatGoal() != null
                        ? soldier.getCombatGoal().resolveCoverProtectionContext()
                        : CoverProtectionContext.NONE;
                    long captureStarted = System.nanoTime();
                    try {
                        CoverSnapshotCapture.Capture capture = CoverSnapshotCapture.captureRaw(
                            soldier.level(), soldier, threatDirection, protection, threats, squadCtx,
                            discovered, searchCenter, searchRadius);
                        PerformanceMetrics.recordPhase6Snapshot(System.nanoTime() - captureStarted);
                        if (soldier.level() instanceof ServerLevel serverLevel
                            && AsyncCoverShadowService.submitPilot(serverLevel, soldier.getUUID(), capture)) {
                            asyncPilotPending = true;
                            asyncPilotSubmittedTick = soldier.level().getGameTime();
                            return CoverMoveResult.ASYNC_PENDING;
                        }
                    } catch (RuntimeException exception) {
                        PerformanceMetrics.recordPhase6SnapshotFailure();
                        StevesArmyMod.LOGGER.warn("Cover pilot snapshot failed for soldier {}", soldier.getId(), exception);
                    }
                    PerformanceMetrics.recordPhase6Fallback();
                }
            }
            reusableScored = finder.evaluateAndScoreAll(
                soldier, threatDirection, threats, searchRadius, true, squadCtx);
            runPureCoverShadow(threatDirection, threats, squadCtx, reusableScored,
                searchCenter, searchRadius);
            bestCover = selectPreferredCover(reusableScored);
            if (bestCover.isEmpty()) {
                bestCover = selectBestAvailableCover(reusableScored, threatDirection);
            }

            if (bestCover.isEmpty()) {
                PerformanceMetrics.recordCoverFullSearchAttempt();
                bestCover = finder.findBestCover(
                    searchCenter,
                    searchRadius,
                    threats.isEmpty() ? null : threats.get(0),
                    threatDirection
                );
            }

            if (bestCover.isEmpty() && squadCtx.inSquad()) {
                PerformanceMetrics.recordCoverFullSearchAttempt();
                bestCover = finder.findBestCover(
                    soldier,
                    threatDirection,
                    threats,
                    searchRadius
                );
            }
        }
        
        if (bestCover.isPresent()) {
            CoverPoint cover = bestCover.get();

            if (reusableScored == null) {
                reusableScored = finder.evaluateAndScoreAll(
                    soldier, threatDirection, threats, searchRadius, true, squadCtx);
            }
            List<CoverFinder.ScoredCover> tacticalCovers = reusableScored.stream()
                .filter(sc -> isExactCoverPathReachable(sc.cover))
                .collect(java.util.stream.Collectors.toList());
            Optional<DefensivePositionCandidate.ProneFiringCandidate> prone =
                DefensivePositionSelector.selectProne(soldier, soldier.getTarget(), getThreats(), tacticalCovers, squadCtx);
            if (prone.isPresent() && relocationType == RelocationType.NONE) {
                startProneFiringMovement(prone.get());
                logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.COVER_STARTED, "prone-lane");
                return CoverMoveResult.COVER_STARTED;
            }
            
            boolean wantsDebug = DiagnosticLogManager.isCoverScoreLoggingEnabled()
                || CoverDebugManager.isShowSoldierCover();
            if (wantsDebug) {
                cachedTopCovers = reusableScored.stream().limit(5)
                    .toArray(CoverFinder.ScoredCover[]::new);
            }
            
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            boolean excludesCurrentCover = emergencyCoverSearchActive && currentCover != null
                && cover.getPosition().equals(currentCover.getPosition());
            if (failedCoverPositions.contains(cover.getPosition()) || excludesCurrentCover) {
                List<CoverFinder.ScoredCover> scored = reusableScored;
                
                scored = scored.stream()
                    .filter(sc -> !failedCoverPositions.contains(sc.cover.getPosition()))
                    .filter(sc -> !emergencyCoverSearchActive || currentCover == null
                        || !sc.cover.getPosition().equals(currentCover.getPosition()))
                    .collect(java.util.stream.Collectors.toList());
                
                if (scored.isEmpty()) {
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} no valid covers after filtering blacklist", soldier.getId());
                    }
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_ELIGIBLE_COVER, "blacklist");
                    return CoverMoveResult.NO_ELIGIBLE_COVER;
                }
                
                cover = scored.get(0).cover;
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} selected fallback cover {} (score={})",
                        soldier.getId(), cover.getPosition(), String.format("%.2f", scored.get(0).score));
                }
            }
            
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) {
                if (emergencyCoverSearchActive) {
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_ELIGIBLE_COVER, "emergency-current");
                    return CoverMoveResult.NO_ELIGIBLE_COVER;
                }
                onCoverReached(cover);
                logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.COVER_STARTED, "already-current");
                return CoverMoveResult.COVER_STARTED;
            }
            
            if (currentCover != null) {
                double distToSoldier = soldier.position().distanceTo(cover.getPosition().getCenter());
                if (distToSoldier < COVER_REACHED_DISTANCE) {
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} selected cover {} too close, blacklisting and falling back to seek",
                            soldier.getId(), cover.getPosition());
                    }
                    blacklistCover(cover.getPosition(), BlacklistReason.STUCK_REPOSITIONING);
                    getCoverManager().clearTargetCover();
                    getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND, "too-close");
                    return CoverMoveResult.NO_COVER_FOUND;
                }
            }
            
            getCoverManager().clearCoverQualityPenalty();
            cancelProneFiringPlan();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                getCoverManager().setTargetCover(cover);
                if (soldier.hasValidAttackTarget()) {
                    attackExpectedCover = cover.getPosition();
                }
                boolean movementStarted = moveToCover(cover);
                if (emergencyCoverSearchActive && !movementStarted) {
                    blacklistCover(cover.getPosition(), BlacklistReason.PATH_FAILED);
                    CoverReservationManager.release(cover.getPosition(), soldier);
                    getCoverManager().clearTargetCover();
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND, "emergency-path");
                    return CoverMoveResult.NO_COVER_FOUND;
                }
                if (!movementStarted && pendingRetryCover == null) {
                    if (getCoverManager().getTargetCover() != null) {
                        blacklistCover(cover.getPosition(), BlacklistReason.PATH_FAILED);
                    }
                    getCoverManager().setState(getCoverManager().getCurrentCover() != null
                        ? CoverBehaviorManager.CoverState.IN_COVER
                        : CoverBehaviorManager.CoverState.NO_COVER);
                    logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND,
                        "path-failed");
                    return CoverMoveResult.NO_COVER_FOUND;
                }
                logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.COVER_STARTED, "selected");
                return CoverMoveResult.COVER_STARTED;
            }
            logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND, "reservation");
            return CoverMoveResult.NO_COVER_FOUND;
        }
        if (reusableScored == null) {
            reusableScored = finder.evaluateAndScoreAll(
                soldier, threatDirection, threats, searchRadius, true, squadCtx);
        }
        List<CoverFinder.ScoredCover> tacticalCovers = reusableScored.stream()
            .filter(sc -> isExactCoverPathReachable(sc.cover))
            .collect(java.util.stream.Collectors.toList());
        Optional<DefensivePositionCandidate.ProneFiringCandidate> prone =
            DefensivePositionSelector.selectProne(soldier, soldier.getTarget(), getThreats(), tacticalCovers, squadCtx);
        if (prone.isPresent() && relocationType == RelocationType.NONE) {
            startProneFiringMovement(prone.get());
            logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.COVER_STARTED, "prone-no-cover");
            return CoverMoveResult.COVER_STARTED;
        }
        // Leave the active seeking state when this search found no route. The goal's
        // normal stop/cooldown lifecycle prevents a stationary soldier from rebuilding
        // the same failed candidate paths every tick.
        getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
        logCoverSearchPerformance(finder, searchStarted, CoverMoveResult.NO_COVER_FOUND, "none");
        return CoverMoveResult.NO_COVER_FOUND;
    }

    private Optional<CoverPoint> selectBestAvailableCover(List<CoverFinder.ScoredCover> scored,
                                                            Vec3 threatDirection) {
        if (scored == null || scored.isEmpty()) {
            return Optional.empty();
        }

        Direction threatDir = threatDirection != null && threatDirection.lengthSqr() > 0.001
            ? CoverFinder.getDirectionFromVector(threatDirection) : null;

        if (threatDir != null) {
            Optional<CoverPoint> protectedCover = scored.stream()
                .filter(sc -> CoverReservationManager.isAvailable(sc.cover.getPosition()))
                .filter(sc -> sc.cover.getProtectedDirections().contains(threatDir))
                .map(sc -> sc.cover)
                .findFirst();
            if (protectedCover.isPresent()) {
                return protectedCover;
            }
        }

        return scored.stream()
            .filter(sc -> CoverReservationManager.isAvailable(sc.cover.getPosition()))
            .map(sc -> sc.cover)
            .findFirst();
    }

    private void startProneFiringMovement(DefensivePositionCandidate.ProneFiringCandidate candidate) {
        if (candidate.destination().equals(soldier.blockPosition())) {
            navigation.stop();
            proneFiringDestination = null;
            if (proneFiringController.begin(candidate, true) && DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[DefensivePosition] Soldier {} selected current prone lane {} ({})",
                    soldier.getId(), candidate.destination(), candidate.diagnostics());
            }
            return;
        }
        if (!proneFiringController.begin(candidate, false)) {
            return;
        }
        proneFiringDestination = candidate.destination();
        Path path = navigation.createPath(candidate.destination(), 0);
        if (path == null || !path.canReach()) {
            proneFiringController.cancel("path_failed");
            proneFiringDestination = null;
            return;
        }
        navigation.moveTo(path, 1.0D);
        getCoverManager().clearTargetCover();
        getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[DefensivePosition] Soldier {} selected prone lane {} access={} protection={} cost={} ({})",
                soldier.getId(), candidate.destination(), candidate.firingAccess(), candidate.protection(),
                candidate.movementCost(), candidate.diagnostics());
        }
    }

    private void cancelProneFiringPlan() {
        proneFiringDestination = null;
        proneFiringController.cancel("physical_cover_selected");
    }

    /** Exposes the current non-reservable lane to squadmate cover searches. */
    public BlockPos getProneDefensivePosition() {
        return proneFiringDestination != null ? proneFiringDestination : proneFiringController.getDestination();
    }

    private void logCoverSearchPerformance(CoverFinder finder, long started,
                                            CoverMoveResult result, String phase) {
        if (!DiagnosticLogManager.isCoverPerformanceLoggingEnabled()) return;

        long totalNanos = System.nanoTime() - started;
        StevesArmyMod.LOGGER.info(
            "[CoverPerf] soldier={} name={} tick={} phase={} result={} totalMs={} discoveryMs={} scoringMs={} candidates={} evaluated={} target={} pos={}",
            soldier.getId(), soldier.getName().getString(), soldier.tickCount, phase, result,
            formatMillis(totalNanos), formatMillis(finder.getCandidateDiscoveryNanos()),
            formatMillis(finder.getTacticalScoringNanos()), finder.getCandidatesDiscovered(),
            finder.getCandidatesEvaluated(),
            getCoverManager().getTargetCover() != null ? getCoverManager().getTargetCover().getPosition() : "null",
            soldier.blockPosition());
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    /** Runs Phase 4 and/or Phase 5 beside routine rifleman NORMAL searches. */
    private void runPureCoverShadow(Vec3 threatDirection, List<LivingEntity> threats,
                                    SquadCoverContext squadContext,
                                    List<CoverFinder.ScoredCover> legacyScored,
                                    BlockPos searchCenter, int searchRadius) {
        boolean phase4Enabled = StevesArmyConfig.isPureEvaluatorShadowEnabled();
        boolean phase5Enabled = StevesArmyConfig.isAsyncCoverShadowEnabled() && !machineGunnerPipeline;
        if (!phase4Enabled && !phase5Enabled) {
            PerformanceMetrics.recordPhase4Skip();
            return;
        }
        if (legacyScored == null || legacyScored.isEmpty()) {
            if (phase4Enabled) {
                PerformanceMetrics.recordPhase4Skip();
            }
            return;
        }

        CoverProtectionContext protection = soldier.getCombatGoal() != null
            ? soldier.getCombatGoal().resolveCoverProtectionContext()
            : CoverProtectionContext.NONE;
        long captureStarted = System.nanoTime();
        CoverSnapshotCapture.Capture capture;
        try {
            capture = CoverSnapshotCapture.capture(
                soldier.level(), soldier, threatDirection, protection, threats, squadContext,
                legacyScored, searchCenter, searchRadius);
        } catch (RuntimeException exception) {
            if (phase4Enabled) {
                PerformanceMetrics.recordPhase4Skip();
            }
            if (phase5Enabled) {
                PerformanceMetrics.recordPhase5SnapshotFailure();
            }
            StevesArmyMod.LOGGER.warn("Cover shadow snapshot failed for soldier {}", soldier.getId(), exception);
            return;
        }
        long captureNanos = System.nanoTime() - captureStarted;
        if (phase4Enabled) {
            PerformanceMetrics.recordPhase4Capture(captureNanos,
                capture.input().candidates().size());
        }
        if (phase5Enabled) {
            PerformanceMetrics.recordPhase5Snapshot(captureNanos);
        }

        if (phase4Enabled) {
            List<BlockPos> legacyPositions = legacyScored.stream()
                .map(scored -> scored.cover.getPosition()).toList();
            long evaluationStarted = System.nanoTime();
            CoverSearchResult pure = PureCoverEvaluator.evaluate(capture.input(), capture.terrain());
            long evaluationNanos = System.nanoTime() - evaluationStarted;
            PerformanceMetrics.recordPhase4Evaluation(evaluationNanos);

            CoverSearchResult.RankedCandidate pureTop = pure.top();
            BlockPos legacyTop = legacyPositions.get(0);
            boolean topMatches = pureTop != null && legacyTop.equals(pureTop.candidate().position());
            PerformanceMetrics.recordPhase4Top1Comparison(topMatches);

            int comparableCount = Math.min(legacyPositions.size(), pure.positions().size());
            boolean orderingMatches = comparableCount > 0
                && legacyPositions.subList(0, comparableCount).equals(pure.positions().subList(0, comparableCount));
            PerformanceMetrics.recordPhase4OrderingComparison(orderingMatches);
        }

        if (phase5Enabled && soldier.level() instanceof ServerLevel serverLevel) {
            List<BlockPos> legacyPositions = legacyScored.stream()
                .map(scored -> scored.cover.getPosition()).toList();
            AsyncCoverShadowService.submit(serverLevel, soldier.getUUID(), capture, legacyPositions);
        }
    }
    
private Optional<CoverPoint> findBetterCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);

        Vec3 threatDirection = getCoverSearchDirection();
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} finding cover: soldierPos={}, threatDirection=({}, {}, {}), threats={}",
                soldier.getId(),
                soldier.blockPosition(),
                threatDirection != null ? String.format("%.2f", threatDirection.x) : "null",
                threatDirection != null ? String.format("%.2f", threatDirection.y) : "null",
                threatDirection != null ? String.format("%.2f", threatDirection.z) : "null",
                threats.size());
        }

        CoverPoint currentCover = getCoverManager().getCurrentCover();

        Optional<CoverPoint> result = finder.findBestCover(soldier, threatDirection, threats, SEARCH_RADIUS, squadCtx);

        if (result.isPresent() && currentCover != null && result.get().getPosition().equals(currentCover.getPosition())) {
            List<CoverFinder.ScoredCover> top2 = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 2, false);
            if (top2.size() >= 2) {
                result = Optional.of(top2.get(1).cover);
            } else {
                result = Optional.empty();
            }
        }

        boolean wantsDebug = DiagnosticLogManager.isCoverLoggingEnabled() || CoverDebugManager.isShowSoldierCover();
        if (wantsDebug) {
            List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 5, true);
            cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
        }

        return result;
    }

    private void populateCoverDebugData() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        boolean wantsDebug = DiagnosticLogManager.isCoverScoreLoggingEnabled()
            || CoverDebugManager.isShowSoldierCover() || CoverDebugManager.isVisualizationEnabled();
        if (wantsDebug && (cachedTopCovers.length == 0 || soldier.tickCount % 10 == 0)) {
            CoverFinder finder = new CoverFinder(soldier.level());
Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();
            List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 5, true);
            cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
        }
        
        if (cachedTopCovers.length == 0) return;
        
        BlockPos chosenPos = targetCover != null ? targetCover.getPosition() : 
                            (currentCover != null ? currentCover.getPosition() : null);
        
        int[] rejectionReasons = new int[cachedTopCovers.length];
        for (int i = 0; i < cachedTopCovers.length; i++) {
            BlockPos pos = cachedTopCovers[i].cover.getPosition();
            if (chosenPos != null && pos.equals(chosenPos)) {
                rejectionReasons[i] = CoverDebugManager.TopCoversDebugData.REASON_CHOSEN;
            } else if (currentCover != null && pos.equals(currentCover.getPosition())) {
                rejectionReasons[i] = CoverDebugManager.TopCoversDebugData.REASON_ALREADY_CURRENT;
            } else if (failedCoverPositions.contains(pos)) {
                rejectionReasons[i] = CoverDebugManager.TopCoversDebugData.REASON_BLACKLISTED;
            } else if (!CoverReservationManager.isAvailable(pos)) {
                rejectionReasons[i] = CoverDebugManager.TopCoversDebugData.REASON_RESERVED;
            } else {
                rejectionReasons[i] = CoverDebugManager.TopCoversDebugData.REASON_NONE;
            }
        }
        
        long now = System.currentTimeMillis();
        java.util.Map<BlockPos, CoverDebugManager.BlacklistDebugEntry> blacklistInfo = new java.util.HashMap<>();
        for (java.util.Map.Entry<BlockPos, BlacklistEntry> entry : blacklistReasons.entrySet()) {
            int ageSeconds = (int)(entry.getValue().getAgeMs(now) / 1000);
            blacklistInfo.put(entry.getKey(), new CoverDebugManager.BlacklistDebugEntry(entry.getValue().reason.label, ageSeconds));
        }
        
        CoverDebugManager.setSoldierTopCovers(soldier.getId(),
            new CoverDebugManager.TopCoversDebugData(
                cachedTopCovers,
                rejectionReasons,
                chosenPos,
                currentCover != null ? currentCover.getCombatScore() : 0,
                getCoverManager().getCoverQualityPenalty(),
                getCoverManager().getPeekCountSameCover(),
                blacklistInfo
            ));
        
        if (CoverDebugManager.isShowSearchCenter() && debugSearchCenter != null) {
            CoverDebugManager.setSearchCenterPos(debugSearchCenter);
        }
    }
    
    // --- Attack Phase Methods ---

    /**
     * Unified forward-progress predicate for attack-mode cover candidates.
     * A candidate is valid only if it moves the soldier toward the objective
     * and does not regress behind the best progress made this command.
     */
    private boolean isForwardCoverCandidate(BlockPos candidatePos, BlockPos objective, Vec3 objectiveDir) {
        if (objectiveDir == null) return false;

        // Minimum projected forward progress from current position
        Vec3 candidateCenter = candidatePos.getCenter();
        Vec3 disp = candidateCenter.subtract(soldier.position());
        double forwardProgress = disp.x * objectiveDir.x + disp.z * objectiveDir.z;
        if (forwardProgress < ATTACK_MIN_FORWARD_PROGRESS) {
            return false;
        }

        // Must be closer to objective than soldier currently is
        double distToObj = candidatePos.distSqr(objective);
        double soldierDist = soldier.blockPosition().distSqr(objective);
        if (distToObj >= soldierDist - 0.01) {
            return false;
        }

        // Must not regress behind the forward frontier (best progress this command)
        if (attackFrontierDistance < Double.MAX_VALUE && distToObj > attackFrontierDistance + ATTACK_FRONTIER_TOLERANCE * ATTACK_FRONTIER_TOLERANCE) {
            return false;
        }

        return true;
    }

    private boolean isAttackCorridorCandidate(BlockPos candidatePos, BlockPos objective, Vec3 objectiveDir) {
        if (!isForwardCoverCandidate(candidatePos, objective, objectiveDir)) return false;

        Vec3 displacement = candidatePos.getCenter().subtract(soldier.position());
        double forward = displacement.x * objectiveDir.x + displacement.z * objectiveDir.z;
        double lateral = Math.abs(displacement.x * objectiveDir.z - displacement.z * objectiveDir.x);
        return forward <= ATTACK_CORRIDOR_FORWARD_LENGTH && lateral <= ATTACK_CORRIDOR_HALF_WIDTH;
    }

    /**
     * Finds a path-reachable forward cover outside the preferred narrow corridor.
     * Protection is a preference here so a structure cannot trap an ATTACK soldier
     * when its usable exit cover is not aligned with the current threat direction.
     */
    private Optional<CoverPoint> findWideForwardAttackCover(List<CoverFinder.ScoredCover> scored,
                                                              CoverPoint currentCover, BlockPos objective,
                                                              Vec3 objectiveDir, Direction threatDir) {
        CoverPoint bestCover = null;
        int bestPathNodes = Integer.MAX_VALUE;
        boolean bestProtected = false;
        CoverFinder protectionFinder = new CoverFinder(soldier.level());
        boolean requiresProtection = protectionFinder.hasPrimaryThreat(soldier,
            getThreats().getPrimaryDirection(soldier.position()));

        for (CoverFinder.ScoredCover sc : scored) {
            CoverPoint cover = sc.cover;
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;
            if (failedCoverPositions.contains(cover.getPosition())) continue;
            if (!isForwardCoverCandidate(cover.getPosition(), objective, objectiveDir)) continue;
            if (cover.getPosition().distSqr(objective) <= ATTACK_OBJECTIVE_RADIUS * ATTACK_OBJECTIVE_RADIUS) continue;

            Vec3 displacement = cover.getPosition().getCenter().subtract(soldier.position());
            double forward = displacement.x * objectiveDir.x + displacement.z * objectiveDir.z;
            double lateral = Math.abs(displacement.x * objectiveDir.z - displacement.z * objectiveDir.x);
            if (forward > ATTACK_CORRIDOR_FORWARD_LENGTH || lateral > ATTACK_WIDE_SECTOR_HALF_WIDTH) continue;

            Vec3 standingPos = getCoverStandingPosition(cover.getPosition());
            Path path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 1);
            if (path == null || !path.canReach()) continue;

            boolean protectedFromThreat = protectionFinder
                .isPrimaryThreatProtected(cover, soldier, getThreats().getPrimaryDirection(soldier.position()));
            int pathNodes = path.getNodeCount();
            if (bestCover == null || (protectedFromThreat && !bestProtected)
                || (protectedFromThreat == bestProtected && pathNodes < bestPathNodes)) {
                bestCover = cover;
                bestPathNodes = pathNodes;
                bestProtected = protectedFromThreat;
            }
        }

        return requiresProtection && !bestProtected ? Optional.empty() : Optional.ofNullable(bestCover);
    }

    private void logRejectedAttackCover(CoverPoint cover, BlockPos objective, Vec3 objectiveDir) {
        if (!attackDebugLog() || objectiveDir == null) return;

        Vec3 displacement = cover.getPosition().getCenter().subtract(soldier.position());
        double forwardProgress = displacement.x * objectiveDir.x + displacement.z * objectiveDir.z;
        double candidateDistance = cover.getPosition().distSqr(objective);
        double soldierDistance = soldier.blockPosition().distSqr(objective);
        boolean regressesFrontier = attackFrontierDistance < Double.MAX_VALUE
            && candidateDistance > attackFrontierDistance + ATTACK_FRONTIER_TOLERANCE * ATTACK_FRONTIER_TOLERANCE;

        StevesArmyMod.LOGGER.info("[AttackForward] Soldier {} ({}) rejected cover {}: progress={}, candidateDist={}, soldierDist={}, frontier={}, frontierRegression={}",
            soldier.getId(), soldier.getName().getString(), cover.getPosition(),
            String.format("%.1f", forwardProgress), String.format("%.1f", candidateDistance),
            String.format("%.1f", soldierDistance), String.format("%.1f", attackFrontierDistance),
            regressesFrontier);
    }

    /**
     * Update attack progress frontier when the soldier arrives at a cover
     * or completes a direct bound. This prevents regression behind this point.
     */
    private void updateAttackProgress(BlockPos objective) {
        double distSq = soldier.blockPosition().distSqr(objective);
        if (distSq < attackBestObjectiveDist) {
            attackBestObjectiveDist = distSq;
        }
        // Frontier is slightly ahead of best progress to allow some lateral movement
        attackFrontierDistance = attackBestObjectiveDist;
    }

    /**
     * Full reset for a new attack command. Called when attackGeneration changes
     * while the goal is already running. Cancels old movement, clears stale
     * state, but preserves current occupied cover if any so the soldier
     * doesn't abandon protection unnecessarily.
     */
    private void resetAttackCommand() {
        CoverSearchScheduler.cancel(this);
        coverSearchPending = false;
        asyncPilotPending = false;
        asyncPilotSubmittedTick = Long.MIN_VALUE;
        queuedSearchMode = null;
        queuedAttackGeneration = -1;
        queuedRelocationType = RelocationType.NONE;
        queuedRelocationCenter = null;
        // Cancel old movement
        navigation.stop();
        getPositionController().clear();

        // Cancel fallback advance
        fallbackAdvanceTarget = null;
        fallbackStuckTicks = 0;
        fallbackLastPosition = null;
        fallbackNoProgressResets = 0;

        // Cancel pending retry
        pendingRetryCover = null;
        isRetryAttempt = false;

        // Clear old target-cover reservation and stale cover state
        CoverPoint oldTarget = getCoverManager().getTargetCover();
        if (oldTarget != null) {
            CoverReservationManager.release(oldTarget.getPosition(), soldier);
        }
        getCoverManager().clearTargetCover();

        // Reset attack phase fields (same as initAttackPhase but
        // we do NOT change attackCommandGeneration — that's done by caller)
        attackPhase = AttackPhase.SELECTING_COVER;
        attackPhaseStartTime = System.currentTimeMillis();
        attackCoverArrivalTime = 0;
        attackExpectedCover = null;
        attackDwellEligible = false;
        attackHasPeekedThisCover = false;
        attackBestObjectiveDist = Double.MAX_VALUE;
        attackFrontierDistance = Double.MAX_VALUE;

        // Stagger for this command
        attackAdvanceStaggerTicks = Math.abs(soldier.getUUID().hashCode() % 60);

        // Preserve current occupied cover — don't call clearCover() here.
        // The soldier can start from where they are. If the current cover
        // is invalid for the new objective, SELECTING_COVER will find a new one.

        // Reset progress/stuck trackers
        noProgressTicks = 0;
        lastSeekingPosition = null;
        stuckTicks = 0;
        nonPeekableTicks = 0;

        if (attackDebugLog()) {
            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} reset attack command gen {}, phase={}, coverState={}",
                soldier.getId(), soldier.getAttackGeneration(), attackPhase, getCoverManager().getState());
        }
    }

    private void initAttackPhase() {
        int gen = soldier.getAttackGeneration();
        if (attackCommandGeneration != gen) {
            attackCommandGeneration = gen;
            attackPhase = AttackPhase.SELECTING_COVER;
            attackPhaseStartTime = System.currentTimeMillis();
            attackCoverArrivalTime = 0;
            attackExpectedCover = null;
            attackDwellEligible = false;
            attackHasPeekedThisCover = false;
            attackBestObjectiveDist = Double.MAX_VALUE;
            attackFrontierDistance = Double.MAX_VALUE;
            fallbackAdvanceTarget = null;
            fallbackStuckTicks = 0;
            fallbackLastPosition = null;
            fallbackNoProgressResets = 0;
            // Stagger: deterministic delay based on UUID
            attackAdvanceStaggerTicks = Math.abs(soldier.getUUID().hashCode() % 60); // 0-3 seconds
            if (attackDebugLog()) {
                StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} init attack gen {} stagger={}",
                    soldier.getId(), gen, attackAdvanceStaggerTicks);
            }
        }
    }

    private void tickAttackPhase() {
        if (!soldier.hasValidAttackTarget()) {
            attackPhase = AttackPhase.NONE;
            return;
        }

        // Detect new attack command while goal is already running
        int currentGen = soldier.getAttackGeneration();
        if (attackCommandGeneration != currentGen) {
            attackCommandGeneration = currentGen;
            resetAttackCommand();
            if (attackDebugLog()) {
                StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} new attack generation {} detected, resetting command",
                    soldier.getId(), currentGen);
            }
        }

        BlockPos objective = soldier.getAttackTargetPos();
        double distToObjective = soldier.distanceToSqr(
            objective.getX() + 0.5, objective.getY() + 0.5, objective.getZ() + 0.5);
        double objRadiusSq = ATTACK_OBJECTIVE_RADIUS * ATTACK_OBJECTIVE_RADIUS;

        // Objective reached — cover-to-cover advance is complete. No further
        // final approach; soldiers hold the last cover near the objective.
        if (distToObjective <= objRadiusSq) {
            if (attackPhase != AttackPhase.COMPLETE) {
                attackPhase = AttackPhase.COMPLETE;
                updateAttackProgress(objective);
                if (attackDebugLog()) {
                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} objective reached, completing", soldier.getId());
                }
            }
            return;
        }

        CoverBehaviorManager.CoverState coverState = getCoverManager().getState();
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        if (attackPhase == AttackPhase.SELECTING_COVER && fallbackAdvanceTarget != null) {
            tickFallbackAdvance();
            if (fallbackAdvanceTarget != null && soldier.tickCount % ATTACK_CORRIDOR_REFRESH_TICKS != 0) {
                return;
            }
            fallbackAdvanceTarget = null;
        }

        switch (attackPhase) {
            case SELECTING_COVER: {
                // If suppressed or not recovered, don't start a new move yet
                if (!canLeaveCoverNow() && currentCover != null) {
                    attackPhase = AttackPhase.OCCUPYING_COVER;
                    attackCoverArrivalTime = System.currentTimeMillis();
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} SELECTING_COVER -> OCCUPYING_COVER: suppressed, waiting at {}",
                            soldier.getId(), currentCover.getPosition());
                    }
                    break;
                }

                // Search execution is budgeted by CoverSearchScheduler. Do not
                // treat a queued search as a failed search and advance exposed.
                requestCoverSearch(QueuedSearchMode.ATTACK_SELECTING);
                break;
            }

            case MOVING_TO_COVER: {
                CoverPoint targetCover = getCoverManager().getTargetCover();

                if (targetCover != null && attackExpectedCover != null
                    && !attackExpectedCover.equals(targetCover.getPosition())) {
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} destination changed: {} -> {}",
                            soldier.getId(), attackExpectedCover, targetCover.getPosition());
                    }
                    attackExpectedCover = targetCover.getPosition().immutable();
                }

                // A fresh attack can clear the manager to NO_COVER after the
                // target was selected. Restore the movement state so the
                // normal cover navigation handlers can process arrival.
                if (coverState == CoverBehaviorManager.CoverState.NO_COVER &&
                    targetCover != null && currentCover == null) {
                    getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} MOVING_TO_COVER restored NO_COVER -> SEEKING_COVER for target {}",
                            soldier.getId(), targetCover.getPosition());
                    }
                }

                // Reconciliation: if the cover system has parked us in a cover
                // (e.g., suppression pulled us back while we were advancing),
                // treat it as arrival so the attack phase can recover.
                boolean currentCoverIsExpected = currentCover != null
                    && (attackExpectedCover == null || currentCover.getPosition().equals(attackExpectedCover));
                if ((coverState == CoverBehaviorManager.CoverState.IN_COVER ||
                     coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) &&
                    currentCoverIsExpected) {
                    attackPhase = AttackPhase.OCCUPYING_COVER;
                    attackCoverArrivalTime = System.currentTimeMillis();
                    attackExpectedCover = null;
                    attackDwellEligible = false;
                    attackHasPeekedThisCover = false;
                    updateAttackProgress(objective);
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} ({}) MOVING_TO_COVER -> OCCUPYING_COVER: cover state {} at {}",
                            soldier.getId(), soldier.getName().getString(), coverState, currentCover.getPosition());
                    }
                    break;
                }

                // CHECK ARRIVAL FIRST: promotion clears targetCover, so we must
                // detect arrival by currentCover matching expectedCover BEFORE
                // treating a null target as movement failure.
                boolean arrivedAtExpected = false;
                if (attackExpectedCover != null && currentCover != null) {
                    arrivedAtExpected = currentCover.getPosition().equals(attackExpectedCover);
                } else if (attackExpectedCover == null && currentCover != null && targetCover == null) {
                    // Expected not set (initial selection via findAndMoveToCover),
                    // and target was just promoted → we just arrived
                    arrivedAtExpected = true;
                }

                if (arrivedAtExpected) {
                    attackPhase = AttackPhase.OCCUPYING_COVER;
                    attackCoverArrivalTime = System.currentTimeMillis();
                    attackExpectedCover = null;
                    attackDwellEligible = false;
                    attackHasPeekedThisCover = false;
                    updateAttackProgress(objective);
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} MOVING_TO_COVER -> OCCUPYING_COVER: reached cover {}, dwell timer starts",
                            soldier.getId(), currentCover.getPosition());
                    }
                    break;
                }

                // Recovery: target cover was lost (e.g., blacklisted during navigation)
                if (targetCover == null) {
                    attackPhase = AttackPhase.SELECTING_COVER;
                    attackExpectedCover = null;
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} MOVING_TO_COVER -> SELECTING_COVER: target cover lost",
                            soldier.getId());
                    }
                    break;
                }

                // Movement is owned by tickSeekingCover() / tickRepositioning().
                // They handle navigation, micro-positioning, stuck detection,
                // retries, blacklisting, and timeout. ATTACK does NOT interpret
                // navigation.isDone() — that signal is unreliable because the
                // cover system intentionally stops vanilla navigation during the
                // micro-positioning handoff at COVER_VALID_DISTANCE.
                break;
            }

            case OCCUPYING_COVER: {
                if (coverState == CoverBehaviorManager.CoverState.NO_COVER) {
                    attackPhase = AttackPhase.SELECTING_COVER;
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} OCCUPYING_COVER -> SELECTING_COVER: cover state is NO_COVER",
                            soldier.getId());
                    }
                    break;
                }

                // Reconcile: if cover state indicates we're moving again (e.g., deferred
                // reposition request activated after recovery), don't try to advance here
                if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER ||
                    coverState == CoverBehaviorManager.CoverState.REPOSITIONING) {
                    attackPhase = AttackPhase.MOVING_TO_COVER;
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} OCCUPYING_COVER -> MOVING_TO_COVER: cover state changed to {}",
                            soldier.getId(), coverState);
                    }
                    break;
                }

                long dwellTime = System.currentTimeMillis() - attackCoverArrivalTime;
                boolean suppressed = getCoverManager().isSuppressed();
                boolean pinned = getCoverManager().isPinned();
                boolean recovered = getCoverManager().getSuppressionTracker().isRecovered();
                PeekController peekCtrl = getPeekController();
                boolean peeking = peekCtrl.isExposed() || peekCtrl.isMovingToPeek() || peekCtrl.isReturning();

                // Check if we've completed a peek cycle
                if (!attackHasPeekedThisCover && !peeking && peekCtrl.isIdleInCover()
                    && getCoverManager().getTimeSinceLastPeek() > 500) {
                    if (peekCtrl.getPeekCountSameCover() > 0) {
                        attackHasPeekedThisCover = true;
                        if (attackDebugLog()) {
                            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} completed first peek cycle this cover",
                                soldier.getId());
                        }
                    }
                }

                // Advance only when fully recovered from suppression, not peeking, and dwell met
                long minDwell = ATTACK_MIN_DWELL_MS;
                long maxDwell = ATTACK_MAX_DWELL_MS + (attackAdvanceStaggerTicks * 50L);
                boolean dwellMet = dwellTime >= minDwell;
                boolean maxDwellReached = dwellTime >= maxDwell && recovered && !peeking;
                boolean canAdvance = dwellMet && recovered && !peeking;

                // The next bound must begin from cover. A delayed peek ducks back
                // at maximum dwell instead of blocking attack progression forever.
                if (dwellTime >= maxDwell && recovered && peeking) {
                    if (!peekCtrl.isReturning()) {
                        peekCtrl.forceReturnToCover(soldier, currentCover, getPositionController());
                    }
                    break;
                }

                if (attackDebugLog() && dwellMet && (!recovered || peeking)) {
                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} dwell met but blocked: recovered={}, peeking={}, suppression={}",
                        soldier.getId(), recovered, peeking,
                        String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));
                }

                if (maxDwellReached || (canAdvance && attackHasPeekedThisCover)) {
                    // The next forward-cover search is budgeted just like the
                    // initial attack search. Waiting here must not be treated
                    // as a failed search and trigger exposed fallback travel.
                    attackPhase = AttackPhase.SELECTING_COVER;
                    requestCoverSearch(QueuedSearchMode.ATTACK_SELECTING);
                }

                // Handle non-peekable cover reposition (only when recovered)
                if (getCoverManager().isRepositionRequested() && recovered) {
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} OCCUPYING_COVER -> SELECTING_COVER: reposition requested, dwell={}ms",
                            soldier.getId(), dwellTime);
                    }
                    getCoverManager().clearRepositionRequest();
                    attackPhase = AttackPhase.SELECTING_COVER;
                }
                break;
            }

            case COMPLETE: {
                // Attack goal handles final approach
                break;
            }

            case NONE: {
                initAttackPhase();
                break;
            }
        }
    }

    /**
     * Select forward cover biased toward the objective.
     * Returns true if cover was found and navigation started.
     */
    private boolean selectForwardCover() {
        if (!soldier.hasValidAttackTarget()) return false;
        BlockPos objective = soldier.getAttackTargetPos();

        // Compute forward-biased search center
        Vec3 toTarget = new Vec3(
            objective.getX() - soldier.getX(),
            0,
            objective.getZ() - soldier.getZ());
        double distToTarget = toTarget.length();
        if (distToTarget < 1.0) return false;

        toTarget = toTarget.normalize();
        double ahead = Math.min(distToTarget * 0.25, ATTACK_FORWARD_BIAS_BLOCKS);
        BlockPos searchCenter = soldier.blockPosition().offset(
            (int)(toTarget.x * ahead), 0, (int)(toTarget.z * ahead));

        BlockPos machineGunnerAnchor = getMachineGunnerSearchCenter();
        if (machineGunnerAnchor != null) {
            searchCenter = machineGunnerAnchor;
        }

        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        List<CoverFinder.ScoredCover> scored = finder.evaluateAndScoreAllFromCenter(
            searchCenter, soldier, threatDirection, threats, ATTACK_CORRIDOR_SEARCH_RADIUS, squadCtx);

        CoverPoint currentCover = getCoverManager().getCurrentCover();
        Vec3 soldierPos = soldier.position();
        Vec3 objectiveDir = new Vec3(
            objective.getX() - soldierPos.x, 0, objective.getZ() - soldierPos.z).normalize();

        Direction threatDir = (threatDirection != null && threatDirection.lengthSqr() > 0.001)
            ? CoverFinder.getDirectionFromVector(threatDirection) : null;

        // Filter: require forward progress AND primary-threat protection,
        // reject current/blacklisted covers
        for (CoverFinder.ScoredCover sc : scored) {
            CoverPoint cover = sc.cover;

            // Skip current cover
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;

            // Skip blacklisted
            if (failedCoverPositions.contains(cover.getPosition())) continue;

            // Hard forward progress filter
            Vec3 candidateDisp = cover.getPosition().getCenter().subtract(soldierPos);
            double forwardProgress = candidateDisp.x * objectiveDir.x + candidateDisp.z * objectiveDir.z;
            if (!isAttackCorridorCandidate(cover.getPosition(), objective, objectiveDir)) continue;

            // Don't go beyond the objective
            double distToObj = cover.getPosition().distSqr(objective);
            if (distToObj <= ATTACK_OBJECTIVE_RADIUS * ATTACK_OBJECTIVE_RADIUS) continue;

            // Hard primary-threat protection preference
            if (!finder.isPrimaryThreatProtected(cover, soldier, threatDirection)) {
                continue;
            }

            // Use canonical repositioning transition instead of direct moveToCover
            if (startRepositioning(cover)) {
                attackExpectedCover = cover.getPosition();
                if (attackDebugLog()) {
                    StevesArmyMod.LOGGER.info("[AttackForward] Soldier {} selected forward cover {} progress={}",
                        soldier.getId(), cover.getPosition(), String.format("%.1f", forwardProgress));
                }
                return true;
            }
        }

        List<CoverFinder.ScoredCover> localScored = finder.evaluateAndScoreAll(
            soldier, threatDirection, threats, ATTACK_CORRIDOR_SEARCH_RADIUS, true, squadCtx);
        Optional<CoverPoint> wideCover = findWideForwardAttackCover(localScored, currentCover,
            objective, objectiveDir, threatDir);
        if (wideCover.isPresent() && startRepositioning(wideCover.get())) {
            attackExpectedCover = wideCover.get().getPosition();
            if (attackDebugLog()) {
                StevesArmyMod.LOGGER.info("[AttackForward] Soldier {} selected wide-sector cover {}",
                    soldier.getId(), wideCover.get().getPosition());
            }
            return true;
        }

        return false;
    }

    /**
     * Advance through a short uncovered segment using the same navigation owner
     * as cover movement. The next attack tick immediately searches the corridor
     * again, so a newly available cover can interrupt this fallback naturally.
     */
    private void startFallbackAdvance() {
        if (!soldier.hasValidAttackTarget()) return;
        BlockPos objective = soldier.getAttackTargetPos();

        Vec3 toTarget = new Vec3(
            objective.getX() - soldier.getX(),
            0,
            objective.getZ() - soldier.getZ());
        double dist = toTarget.length();
        if (dist < ATTACK_OBJECTIVE_RADIUS) return;

        Vec3 dir = toTarget.normalize();
        double advanceLen = Math.min(ATTACK_FALLBACK_ADVANCE_LENGTH, dist - ATTACK_OBJECTIVE_RADIUS);
        if (advanceLen < 1.0) return;

        Vec3 dest = soldier.position().add(dir.x * advanceLen, 0, dir.z * advanceLen);
        BlockPos destBlock = new BlockPos((int)Math.floor(dest.x), soldier.blockPosition().getY(), (int)Math.floor(dest.z));

        Path path = soldier.getNavigation().createPath(destBlock.getX() + 0.5, destBlock.getY(), destBlock.getZ() + 0.5, 1);
        if (path == null || !path.canReach()) {
            fallbackAdvanceTarget = null;
            return;
        }

        fallbackAdvanceTarget = destBlock;
        fallbackStuckTicks = 0;
        fallbackLastPosition = soldier.position();
        fallbackNoProgressResets = 0;
        soldier.getNavigation().moveTo(path, 1.2D);

        if (attackDebugLog()) {
            StevesArmyMod.LOGGER.info("[AttackAdvance] Soldier {} advancing to {} (len={}, objDist={})",
                soldier.getId(), destBlock, String.format("%.1f", advanceLen), String.format("%.1f", dist));
        }
    }

    private void tickFallbackAdvance() {
        if (fallbackAdvanceTarget == null) return;

        double distance = soldier.distanceToSqr(
            fallbackAdvanceTarget.getX() + 0.5,
            fallbackAdvanceTarget.getY() + 0.5,
            fallbackAdvanceTarget.getZ() + 0.5);
        if (distance <= 4.0) {
            fallbackAdvanceTarget = null;
            fallbackLastPosition = null;
            fallbackStuckTicks = 0;
            return;
        }

        if (fallbackLastPosition != null && soldier.position().distanceToSqr(fallbackLastPosition) < 0.01) {
            fallbackStuckTicks++;
        } else {
            fallbackStuckTicks = 0;
        }
        fallbackLastPosition = soldier.position();

        if (fallbackStuckTicks >= ATTACK_FALLBACK_STUCK_TICKS) {
            fallbackAdvanceTarget = null;
            fallbackLastPosition = null;
            fallbackStuckTicks = 0;
            fallbackNoProgressResets++;
            navigation.stop();
            if (attackDebugLog()) {
                StevesArmyMod.LOGGER.info("[AttackAdvance] Soldier {} fallback advance stopped after no progress (reset {})",
                    soldier.getId(), fallbackNoProgressResets);
            }
            return;
        }

        if (navigation.isDone()) {
            navigation.moveTo(
                fallbackAdvanceTarget.getX() + 0.5,
                fallbackAdvanceTarget.getY(),
                fallbackAdvanceTarget.getZ() + 0.5,
                1.2D);
        }
    }

    private CoverPositionController getPositionController() {
        return (CoverPositionController) soldier.getMoveControl();
    }

    private void maintainCoverAnchorIfHiding(CoverPoint cover) {
        if (cover != null && getPeekController().isIdleInCover()) {
            getPositionController().maintainCoverAnchor(getCoverStandingPosition(cover.getPosition()));
        }
    }
    
public static Vec3 getCoverStandingPositionStatic(BlockPos coverPos) {
        return new Vec3(coverPos.getX() + 0.5, coverPos.getY(), coverPos.getZ() + 0.5);
    }

    private Vec3 getCoverStandingPosition(BlockPos coverPos) {
        return getCoverStandingPositionStatic(coverPos);
    }

    private double horizontalDistanceToCover(CoverPoint cover) {
        Vec3 standingPos = getCoverStandingPosition(cover.getPosition());
        double x = soldier.getX() - standingPos.x;
        double z = soldier.getZ() - standingPos.z;
        return Math.sqrt(x * x + z * z);
    }

    private boolean isDistantRelocationCover(CoverPoint cover) {
        return relocationType != RelocationType.NONE
            && horizontalDistanceToCover(cover) > RELOCATION_EXACT_PATH_DISTANCE;
    }

    private boolean isExactCoverPathReachable(CoverPoint cover) {
        BlockPos source = soldier.blockPosition();
        BlockPos target = cover.getPosition();
        if (soldier.tickCount != validatedCoverPathTick
            || !source.equals(validatedCoverPathSource)) {
            validatedCoverPaths.clear();
            validatedCoverPathSource = source.immutable();
            validatedCoverPathTick = soldier.tickCount;
        }
        if (validatedCoverPaths.containsKey(target)) {
            return true;
        }

        Vec3 standingPos = getCoverStandingPosition(cover.getPosition());
        long pathStarted = System.nanoTime();
        Path path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 0);
        PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.PATH_REQUEST,
            System.nanoTime() - pathStarted);
        boolean reachable = path != null && path.canReach() && path.getNodeCount() > 0
            && path.getNode(path.getNodeCount() - 1).asBlockPos().equals(cover.getPosition());
        if (reachable) {
            validatedCoverPaths.put(target.immutable(), path);
        }
        return reachable;
    }

    /**
     * Evaluates only the highest-scoring suppression candidates. The exposure cache
     * belongs to this search, so it is discarded before terrain or fire sources can
     * make it stale.
     */
    @javax.annotation.Nullable
    private SuppressionRoutePlan selectSuppressionRoute(CoverFinder finder, Vec3 threatDirection,
                                                         List<LivingEntity> threats, int searchRadius,
                                                         SquadCoverContext squadCtx, Vec3 firingOrigin) {
        List<CoverFinder.ScoredCover> candidates = finder.evaluateAndScoreAll(
            soldier, threatDirection, threats, searchRadius, true, squadCtx);
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        Map<BlockPos, RouteNodeExposure> exposureCache = new HashMap<>();
        SuppressionRoutePlan protectedPlan = null;
        SuppressionRoutePlan crawlPlan = null;
        int validPathsEvaluated = 0;

        for (CoverFinder.ScoredCover scored : candidates) {
            if (validPathsEvaluated >= SUPPRESSION_ROUTE_CANDIDATE_LIMIT) {
                break;
            }
            CoverPoint cover = scored.cover;
            if (failedCoverPositions.contains(cover.getPosition())
                || (currentCover != null && cover.getPosition().equals(currentCover.getPosition()))
                || (emergencyCoverSearchActive && currentCover != null
                    && cover.getPosition().equals(currentCover.getPosition()))) {
                continue;
            }

            Vec3 standingPos = getCoverStandingPosition(cover.getPosition());
            Path path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 0);
            if (path == null || !path.canReach() || path.getNodeCount() == 0
                || !path.getNode(path.getNodeCount() - 1).asBlockPos().equals(cover.getPosition())) {
                continue;
            }
            validPathsEvaluated++;

            RouteNodeExposure exposure = classifySuppressionRoute(path, firingOrigin, exposureCache);
            SuppressionRoutePlan plan = new SuppressionRoutePlan(cover, path,
                exposure == RouteNodeExposure.CRAWL_SAFE ? RouteMovement.CRAWL : RouteMovement.NORMAL,
                firingOrigin);
            if (exposure == RouteNodeExposure.PROTECTED && protectedPlan == null) {
                protectedPlan = plan;
            } else if (exposure == RouteNodeExposure.CRAWL_SAFE && crawlPlan == null) {
                crawlPlan = plan;
            }
        }

        SuppressionRoutePlan selected = protectedPlan != null ? protectedPlan
            : crawlPlan;
        if (selected != null && DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} selected {} route to {} after evaluating {} candidates",
                soldier.getId(), selected.movement().name().toLowerCase(java.util.Locale.ROOT),
                selected.cover().getPosition(), validPathsEvaluated);
        }
        return selected;
    }

    private RouteNodeExposure classifySuppressionRoute(Path path, Vec3 firingOrigin,
                                                        Map<BlockPos, RouteNodeExposure> exposureCache) {
        boolean hasCrawlSafeNode = false;
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos nodePos = path.getNode(i).asBlockPos();
            RouteNodeExposure nodeExposure = exposureCache.computeIfAbsent(nodePos,
                pos -> classifySuppressionNode(Vec3.atCenterOf(pos), firingOrigin));
            if (nodeExposure == RouteNodeExposure.EXPOSED) {
                return RouteNodeExposure.EXPOSED;
            }
            hasCrawlSafeNode |= nodeExposure == RouteNodeExposure.CRAWL_SAFE;
            if (i > 0) {
                Vec3 previous = Vec3.atCenterOf(path.getNode(i - 1).asBlockPos());
                Vec3 current = Vec3.atCenterOf(nodePos);
                Vec3 midpoint = previous.lerp(current, 0.5);
                RouteNodeExposure midpointExposure = classifySuppressionNode(midpoint, firingOrigin);
                if (midpointExposure == RouteNodeExposure.EXPOSED) {
                    return RouteNodeExposure.EXPOSED;
                }
                if (midpointExposure == RouteNodeExposure.CRAWL_SAFE) {
                    hasCrawlSafeNode = true;
                }
            }
        }
        return hasCrawlSafeNode ? RouteNodeExposure.CRAWL_SAFE : RouteNodeExposure.PROTECTED;
    }

    private RouteNodeExposure classifySuppressionNode(Vec3 pathPosition, Vec3 firingOrigin) {
        Vec3 horizontal = new Vec3(pathPosition.x - firingOrigin.x, 0, pathPosition.z - firingOrigin.z);
        Vec3 lateral = horizontal.lengthSqr() > 0.001
            ? new Vec3(-horizontal.z, 0, horizontal.x).normalize().scale(0.25)
            : Vec3.ZERO;
        boolean allLowerBlocked = true;
        boolean allEyeBlocked = true;
        for (double offset : new double[] { -1.0, 0.0, 1.0 }) {
            Vec3 sample = pathPosition.add(lateral.scale(offset));
            Vec3 lowerBody = sample.add(0, -0.15, 0);
            Vec3 standingEye = sample.add(0, 1.05, 0);
            allLowerBlocked &= !VisibilityRay.trace(soldier.level(), firingOrigin, lowerBody, soldier).clear();
            allEyeBlocked &= !VisibilityRay.trace(soldier.level(), firingOrigin, standingEye, soldier).clear();
        }
        if (allLowerBlocked && allEyeBlocked) {
            return RouteNodeExposure.PROTECTED;
        }
        return allLowerBlocked ? RouteNodeExposure.CRAWL_SAFE : RouteNodeExposure.EXPOSED;
    }

    private void ensureMovementAttemptTarget(CoverPoint cover) {
        BlockPos target = cover.getPosition();
        if (!target.equals(movementAttemptTarget)) {
            movementAttemptTarget = target.immutable();
            movementAttemptCount = 0;
        }
    }

    private boolean moveToCover(CoverPoint cover) {
        // A concrete physical-cover destination always outranks prone firing.
        cancelProneFiringPlan();
        return startCoverPath(cover);
    }

    /** Starts navigation without re-entering tactical-bound admission. */
    private boolean startCoverPath(CoverPoint cover) {
        PerformanceMetrics.recordRolePathRequest(machineGunnerPipeline);
        PerformanceMetrics.recordCoverPathRequest();
        BlockPos wallPos = cover.getPosition();
        ensureMovementAttemptTarget(cover);
        movementAttemptCount++;

        long pathStarted = System.nanoTime();
        
        if (StevesArmyMod.teleportOnlyMode) {
            soldier.moveTo(wallPos.getX() + 0.5, wallPos.getY(), wallPos.getZ() + 0.5, soldier.getYRot(), soldier.getXRot());
            onCoverReached(cover);
            if (DiagnosticLogManager.isCoverPerformanceLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverPerf] soldier={} tick={} path=teleport result=REACHED totalMs={} cover={}",
                    soldier.getId(), soldier.tickCount, formatMillis(System.nanoTime() - pathStarted), wallPos);
            }
            return true;
        }
        
        Vec3 standingPos = getCoverStandingPosition(wallPos);
        // Use tolerance 0 so the pathfinder must route to the exact standing block,
        // not to an adjacent block that may be on the wrong side of cover.
        SuppressionRoutePlan routePlan = selectedSuppressionRoute != null
            && selectedSuppressionRoute.cover().getPosition().equals(wallPos)
            ? selectedSuppressionRoute : null;
        Path path;
        if (routePlan != null) {
            path = routePlan.path();
        } else if (soldier.blockPosition().equals(validatedCoverPathSource)
            && soldier.tickCount == validatedCoverPathTick
            && (path = validatedCoverPaths.get(wallPos)) != null) {
            PerformanceMetrics.recordCoverPathReuseHit();
        } else {
            long routePathStart = System.nanoTime();
            path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 0);
            PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.PATH_REQUEST,
                System.nanoTime() - routePathStart);
        }

        if (routePlan != null
            && classifySuppressionRoute(path, routePlan.firingOrigin(), new HashMap<>()) == RouteNodeExposure.EXPOSED) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverSuppression] Soldier {} withheld retry to {}: route is now exposed",
                    soldier.getId(), wallPos);
            }
            selectedSuppressionRoute = null;
            activeSuppressionRouteMovement = RouteMovement.NORMAL;
            soldier.setLowCrouching(false);
            PerformanceMetrics.recordRolePathFailure(machineGunnerPipeline);
            PerformanceMetrics.recordCoverPathFailure();
            return false;
        }
        
        boolean isReachable = false;
        boolean isStagedPath = false;
        String failReason = "null path";

        if (isDistantRelocationCover(cover)
            && (path == null || !path.canReach() || path.getNodeCount() == 0
                || !path.getNode(path.getNodeCount() - 1).asBlockPos().equals(wallPos))) {
            Path stagedPath = createRelocationStagingPath(standingPos);
            if (stagedPath != null && stagedPath.canReach() && stagedPath.getNodeCount() > 0) {
                path = stagedPath;
                isReachable = true;
                isStagedPath = true;
                BlockPos endPos = path.getNode(path.getNodeCount() - 1).asBlockPos();
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverRelocation] Soldier {} staging toward {} via {}",
                        soldier.getId(), wallPos, endPos);
                }
            }
        }
        
        if (!isReachable && path != null) {
            if (path.canReach()) {
                // Verify the final node is the intended standing block, not an adjacent one
                BlockPos endPos = path.getNode(path.getNodeCount() - 1).asBlockPos();
                if (endPos.equals(wallPos)) {
                    isReachable = true;
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path REACHED standing {} (canReach=true, endpoint=wallPos)",
                            soldier.getId(), soldier.blockPosition(), standingPos);
                    }
                } else {
                    failReason = String.format("canReach=true but endpoint %s != wallPos %s", endPos, wallPos);
                    if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path REJECTED: {}", 
                            soldier.getId(), soldier.blockPosition(), failReason);
                    }
                }
            } else {
                failReason = "path cannot reach";
                if (isDistantRelocationCover(cover) && path.getNodeCount() > 0) {
                    BlockPos endPos = path.getNode(path.getNodeCount() - 1).asBlockPos();
                    double currentDistance = horizontalDistanceToCover(cover);
                    double endpointDistance = Math.sqrt(endPos.distSqr(wallPos));
                    if (currentDistance - endpointDistance >= MIN_STAGED_PATH_PROGRESS) {
                        isReachable = true;
                        isStagedPath = true;
                        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                            StevesArmyMod.LOGGER.info("[CoverRelocation] Soldier {} staged path to {} endpoint={} distance={} -> {}",
                                soldier.getId(), wallPos, endPos, String.format("%.1f", currentDistance),
                                String.format("%.1f", endpointDistance));
                        }
                    } else {
                        failReason = String.format("partial path endpoint %s makes insufficient progress (%.1f -> %.1f)",
                            endPos, currentDistance, endpointDistance);
                    }
                }
                if (!isReachable && DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path FAILED: {} for standing {}",
                        soldier.getId(), soldier.blockPosition(), failReason, standingPos);
                }
            }
        }
        
        if (isReachable) {
            pendingRetryCover = null; // Clear any pending retry on success
            activeSuppressionRouteMovement = routePlan != null ? routePlan.movement() : RouteMovement.NORMAL;
            soldier.setLowCrouching(activeSuppressionRouteMovement == RouteMovement.CRAWL);
            boolean accepted = navigation.moveTo(path, activeSuppressionRouteMovement == RouteMovement.CRAWL
                ? CRAWL_ROUTE_SPEED : 1.2D);
            if (DiagnosticLogManager.isCoverPerformanceLoggingEnabled()) {
                StevesArmyMod.LOGGER.info(
                    "[CoverPerf] soldier={} tick={} path={} accepted={} nodes={} buildMs={} cover={} from={}",
                    soldier.getId(), soldier.tickCount, isStagedPath ? "staged" : "exact", accepted, path.getNodeCount(),
                    formatMillis(System.nanoTime() - pathStarted), wallPos, soldier.blockPosition());
            }
            if (accepted && soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) ATTACK nav to cover {} from pos {} dist={}",
                    soldier.getId(), soldier.getName().getString(), wallPos, soldier.blockPosition(),
                    String.format("%.2f", soldier.position().distanceTo(standingPos)));
            }
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} navigation {} {} path to cover {} from pos {} (path nodes={})",
                    soldier.getId(), accepted ? "started" : "rejected", isStagedPath ? "staged" : "exact",
                    wallPos, soldier.blockPosition(), path.getNodeCount());
            }
            if (!accepted) {
                PerformanceMetrics.recordRolePathFailure(machineGunnerPipeline);
                PerformanceMetrics.recordCoverPathFailure();
            }
            return accepted;
        } else {
            if (DiagnosticLogManager.isCoverPerformanceLoggingEnabled()) {
                StevesArmyMod.LOGGER.info(
                    "[CoverPerf] soldier={} tick={} path=REJECTED reason={} buildMs={} cover={} from={}",
                    soldier.getId(), soldier.tickCount, failReason,
                    formatMillis(System.nanoTime() - pathStarted), wallPos, soldier.blockPosition());
            }
            // If this is a null path and NOT already a retry, schedule retry for next tick
            if (path == null && !isRetryAttempt && !emergencyCoverSearchActive) {
                if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} null path to cover {}, scheduling retry next tick", 
                        soldier.getId(), soldier.blockPosition(), wallPos);
                }
                pendingRetryCover = cover;
                getCoverManager().setTargetCover(cover);
                PerformanceMetrics.recordRolePathRetry(machineGunnerPipeline);
                PerformanceMetrics.recordCoverPathRetry();
                return false;
            }
            
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} BLACKLISTED cover {} reason: {}{}", 
                    soldier.getId(), soldier.blockPosition(), wallPos, failReason, isRetryAttempt ? " (after retry)" : "");
            }
            blacklistCover(wallPos, BlacklistReason.PATH_FAILED);
            PerformanceMetrics.recordRolePathFailure(machineGunnerPipeline);
            PerformanceMetrics.recordCoverPathFailure();
        }
        return false;
    }

    private boolean startSuppressionRepositioning() {
        CoverMoveResult result = findSuppressionMoveToCover();
        if (getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            return true;
        }
        getCoverManager().setState(getCoverManager().isSuppressed()
            ? CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER
            : CoverBehaviorManager.CoverState.IN_COVER);
        return result == CoverMoveResult.COVER_STARTED;
    }

    private CoverMoveResult findSuppressionMoveToCover() {
        suppressionRouteFiringOrigin = getCoverManager().getRecentSuppressionFiringOrigin();
        suppressionRouteSearchActive = true;
        activeSuppressionRouteMovement = RouteMovement.NORMAL;
        selectedSuppressionRoute = null;
        soldier.setLowCrouching(false);
        getCoverManager().resetPeekState();
        getCoverManager().setPeekPosition(null);
        getPositionController().clear();
        try {
            return findAndMoveToCover();
        } finally {
            suppressionRouteSearchActive = false;
            suppressionRouteFiringOrigin = null;
        }
    }
    
    private void onCoverReached(CoverPoint cover) {
        selectedSuppressionRoute = null;
        activeSuppressionRouteMovement = RouteMovement.NORMAL;
        soldier.setLowCrouching(false);
        // Promote target to current — releases old reservation, sets metadata
        getCoverManager().promoteTargetToCurrentCover();
        
        navigation.stop();
        
        getCoverManager().resetPeekState();
        getCoverManager().setNonPeekableCover(false);
        getCoverManager().clearRepositionRequest();
        getCoverManager().clearShotInCoverRepositionRequest();
        getCoverManager().clearContinuousSuppressionRepositionRequest();
        nonPeekableTicks = 0;
        
        // Renew reservation for the new current cover
        CoverReservationManager.reserve(cover.getPosition(), soldier);
        
        getPositionController().moveTo(getCoverStandingPosition(cover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "onCoverReached", "initial cover positioning");
        
        // Reset peek controller for new cover and seed the peek cooldown
        // so soldier settles before peeking — prevents immediate "jump out"
        getPeekController().resetForNewCover(cover.getPosition());
        getPeekController().setLastPeekEndTime(System.currentTimeMillis());
        resetSuppressionEpisodes();
        
        // Compute peek position with LOS validation for full cover
        if (cover.getType() == CoverType.FULL) {
            Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
            LivingEntity target = soldier.getTarget();
            if (threatDirection != null && threatDirection.lengthSqr() > 0.001) {
                BlockPos peekPos = computePeekPosition(cover, threatDirection, target);
                getCoverManager().setPeekPosition(peekPos);
                if (peekPos == null && DiagnosticLogManager.isCoverLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} full cover has no peekable adjacent block with LOS", soldier.getId());
                }
            }
        } else {
            getCoverManager().setPeekPosition(null);
        }
        
        if (getCoverManager().isSuppressed()) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        soldier.refreshDimensions();
        doLowCrouchIfHalfCover();
        if (cover.getType() == CoverType.HALF && !getCoverManager().isSuppressed()) {
            getPeekController().enterStandingInHalfCover(soldier, "cover-arrival");
        }

        if (relocationType == RelocationType.GO_TO) {
            soldier.completeGoToIfGeneration(relocationCommandGeneration);
        }
        if (relocationType != RelocationType.NONE) {
            relocationType = RelocationType.NONE;
            relocationCenter = null;
            relocationCommandGeneration = -1;
        }
    }

    private Path createRelocationStagingPath(Vec3 destination) {
        Vec3 offset = destination.subtract(soldier.position());
        double horizontalDistance = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (horizontalDistance <= RELOCATION_EXACT_PATH_DISTANCE) {
            return null;
        }

        double stageDistance = RELOCATION_EXACT_PATH_DISTANCE * 0.75D;
        double scale = stageDistance / horizontalDistance;
        Vec3 stage = soldier.position().add(offset.x * scale, offset.y * scale, offset.z * scale);
        return navigation.createPath(stage.x, stage.y, stage.z, 1);
    }
    
    private void blacklistCover(BlockPos pos, BlacklistReason reason) {
        failedCoverPositions.add(pos);
        lastFailedCover = pos;
        blacklistReasons.put(pos, new BlacklistEntry(reason, System.currentTimeMillis()));
        // Release the old target cover reservation explicitly (clearTargetCover no longer releases)
        CoverPoint oldTarget = getCoverManager().getTargetCover();
        if (oldTarget != null) {
            CoverReservationManager.release(oldTarget.getPosition(), soldier);
        }
        // If the blacklisted cover matches the expected attack target, clear it
        if (attackExpectedCover != null && pos.equals(attackExpectedCover)) {
            attackExpectedCover = null;
        }
        getCoverManager().clearTargetCover();
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} blacklisted cover at {} reason={}",
                soldier.getId(), pos, reason.label);
        }
    }
    
    private void doLowCrouchIfHalfCover() {
        CoverPoint cover = getCoverManager().getCurrentCover();
        if (cover != null && cover.getType() == CoverType.HALF) {
            if (!getCoverManager().isSuppressed()) {
                soldier.setLowCrouching(false);
            } else if (!soldier.hasEmergencyEngagementPosture()) {
                soldier.setLowCrouching(true);
            }
            soldier.refreshDimensions();
        }
    }
    
    private List<LivingEntity> getThreatList() {
        List<LivingEntity> list = new ArrayList<>();
        LivingEntity target = soldier.getTarget();
        if (target != null && target.isAlive()) {
            list.add(target);
            return list;
        }

        ThreatAwareness threats = getThreats();
        if (!threats.hasActiveThreat()) return list;

        BlockPos threatPos = threats.getPrimaryThreatPosition();
        if (threatPos == null) return list;

        double searchRadius = 32.0;
        Vec3 center = Vec3.atCenterOf(threatPos);
        for (LivingEntity entity : soldier.level().getEntitiesOfClass(
                LivingEntity.class,
                soldier.getBoundingBox().inflate(searchRadius),
                e -> e.isAlive() && !e.is(soldier) && !(e instanceof SoldierEntity))) {
            if (entity.position().distanceToSqr(center) < searchRadius * searchRadius) {
                list.add(entity);
            }
        }

        return list;
    }
    
    public static BlockPos computePeekPositionStatic(CoverPoint cover, Vec3 threatDirection, LivingEntity target, net.minecraft.world.level.Level level, double soldierY) {
        return computePeekPositionStatic(cover, threatDirection, target, level, soldierY, -1);
    }
    
    public static BlockPos computePeekPositionStatic(CoverPoint cover, Vec3 threatDirection, LivingEntity target, 
                                                      net.minecraft.world.level.Level level, double soldierY, int debugSoldierId) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return null;
        }

        java.util.Set<net.minecraft.core.Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return null;
        }
        
        BlockPos coverPos = cover.getPosition();
        BlockPos bestPeekPos = null;
        float bestPeekScore = 0.0f;
        
        List<BlockPos> debugCandidatePositions = new ArrayList<>();
        List<Integer> debugRejectionReasons = new ArrayList<>();
        List<Double> debugAngleScores = new ArrayList<>();
        List<Boolean> debugLosResults = new ArrayList<>();
        List<Vec3> debugPeekEyePositions = new ArrayList<>();
        List<Float> debugConeCoverageScores = new ArrayList<>();
        Vec3 debugTargetEye = null;
        
        for (net.minecraft.core.Direction peekDir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (protectedDirs.contains(peekDir)) {
                debugCandidatePositions.add(coverPos.relative(peekDir));
                debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_PROTECTED_DIR);
                debugAngleScores.add(0.0);
                debugLosResults.add(false);
                debugPeekEyePositions.add(null);
                debugConeCoverageScores.add(0.0f);
                continue;
            }
            
            BlockPos peekPos = coverPos.relative(peekDir);
            debugCandidatePositions.add(peekPos);
            
            if (!com.stevesarmy.combat.cover.CoverFinder.isValidPeekPosition(peekPos, level)) {
                debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_INVALID_POS);
                debugAngleScores.add(0.0);
                debugLosResults.add(false);
                debugPeekEyePositions.add(null);
                debugConeCoverageScores.add(0.0f);
                continue;
            }
            
            boolean losOk = true;
            float coneCoverageScore = 1.0f;
            Vec3 peekEye = null;
            Vec3 targetEye = null;
            
            if (target != null && target.isAlive()) {
                peekEye = new Vec3(peekPos.getX() + 0.5, soldierY + 1.62, peekPos.getZ() + 0.5);
                targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                if (debugTargetEye == null) {
                    debugTargetEye = targetEye;
                }
                losOk = com.stevesarmy.combat.cover.CoverFinder.hasLineOfSightStatic(peekEye, targetEye, level);
                if (!losOk) {
                    coneCoverageScore = com.stevesarmy.combat.cover.CoverFinder.calculateConeCoverage(peekPos, threatDirection, level);
                    if (coneCoverageScore <= 0.01f) {
                        debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_NO_LOS);
                        debugAngleScores.add(0.0);
                        debugLosResults.add(false);
                        debugPeekEyePositions.add(peekEye);
                        debugConeCoverageScores.add(coneCoverageScore);
                        continue;
                    }
                }
            } else {
                coneCoverageScore = com.stevesarmy.combat.cover.CoverFinder.calculateConeCoverage(peekPos, threatDirection, level);
                if (coneCoverageScore <= 0.01f) {
                    debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_NO_LOS);
                    debugAngleScores.add(0.0);
                    debugLosResults.add(false);
                    debugPeekEyePositions.add(null);
                    debugConeCoverageScores.add(coneCoverageScore);
                    continue;
                }
            }
            
            Vec3 peekCenter = peekPos.getCenter();
            Vec3 toThreat = threatDirection.normalize();
            Vec3 fromPeekToCover = new Vec3(
                coverPos.getX() + 0.5 - peekCenter.x,
                0,
                coverPos.getZ() + 0.5 - peekCenter.z
            ).normalize();
            
            double dot = toThreat.dot(fromPeekToCover);
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double angleBetween = Math.toDegrees(Math.acos(dot));
            
            if (angleBetween >= 45 && angleBetween <= 135) {
                float angleScore = 1.0f - (float)Math.abs(angleBetween - 90) / 90;
                float finalScore = angleScore * coneCoverageScore;
                
                debugLosResults.add(losOk);
                debugPeekEyePositions.add(peekEye);
                debugConeCoverageScores.add(coneCoverageScore);
                
                if (finalScore > bestPeekScore) {
                    bestPeekScore = finalScore;
                    bestPeekPos = peekPos;
                    debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_CHOSEN);
                    debugAngleScores.add((double)angleScore);
                } else {
                    debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_ACCEPTED);
                    debugAngleScores.add((double)angleScore);
                }
            } else {
                debugRejectionReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_BAD_ANGLE);
                debugAngleScores.add(0.0);
                debugLosResults.add(losOk);
                debugPeekEyePositions.add(peekEye);
                debugConeCoverageScores.add(coneCoverageScore);
            }
        }
        
        if (debugSoldierId >= 0) {
            CoverDebugManager.setSoldierPeekCandidates(debugSoldierId, 
                new CoverDebugManager.PeekCandidateDebugData(
                    coverPos, debugCandidatePositions, debugRejectionReasons, debugAngleScores,
                    debugLosResults, bestPeekPos, debugTargetEye, debugPeekEyePositions,
                    debugConeCoverageScores, soldierY
                ));
        }
        
        return bestPeekPos;
    }
    
    private BlockPos computePeekPosition(CoverPoint cover, Vec3 threatDirection, LivingEntity target) {
        return computePeekPositionStatic(cover, threatDirection, target, soldier.level(), soldier.getY(), soldier.getId());
    }
    
    public static boolean isPathClearStatic(BlockPos from, BlockPos to, net.minecraft.world.level.Level level) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        
        if (steps == 0) return true;
        
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + (dx * i) / steps;
            int z = from.getZ() + (dz * i) / steps;
            BlockPos checkPos = new BlockPos(x, from.getY(), z);
            
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(checkPos);
            if (!state.isAir() && !state.getCollisionShape(level, checkPos).isEmpty()) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isPathClear(BlockPos from, BlockPos to) {
        return isPathClearStatic(from, to, soldier.level());
    }

    private SquadCoverContext buildSquadCoverContext() {
        Level level = soldier.level();
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(level instanceof ServerLevel serverLevel)) {
            return new SquadCoverContext(false, 0, 0, List.of(), List.of(), List.of(), List.of(), null, null);
        }

        SquadManager mgr = SquadManager.get(serverLevel);

        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, soldier.getUUID());

        List<BlockPos> occupiedCovers = new ArrayList<>();
        List<BlockPos> defensivePositions = new ArrayList<>();
        List<Vec3> threatDirs = new ArrayList<>();
        List<SquadCoverContext.FiringContact> firingContacts = new ArrayList<>();
        com.stevesarmy.squad.SquadCoverPeekabilityCache peekabilityCache = null;

        com.stevesarmy.squad.SquadData squadData = mgr.getSquadById(squadId).orElse(null);
        if (squadData != null) {
            peekabilityCache = squadData.getCoverPeekabilityCache();
            long now = level.getGameTime();
            squadData.getThreatIntel().getAllThreats().stream()
                .filter(threat -> threat.isAlive && threat.lastVisibleAimPoint != null)
                .filter(threat -> now - threat.lastSeenTime >= 0
                    && now - threat.lastSeenTime <= SquadCoverContext.FiringContact.MAX_AGE_TICKS)
                .sorted(java.util.Comparator.comparingLong((com.stevesarmy.squad.SquadThreatIntel.ThreatKnowledge threat)
                    -> threat.lastSeenTime).reversed())
                .limit(SquadCoverContext.FiringContact.MAX_CONTACTS)
                .forEach(threat -> firingContacts.add(new SquadCoverContext.FiringContact(
                    threat.threatEntityId, threat.lastVisibleAimPoint, threat.lastSeenTime)));
        }

        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity ms) {
                CoverBehaviorManager cbm = ms.getCoverBehaviorManager();
                CoverPoint current = cbm.getCurrentCover();
                if (current != null) {
                    addSquadPosition(occupiedCovers, current.getPosition());
                    addSquadPosition(defensivePositions, current.getPosition());
                }
                CoverPoint target = cbm.getTargetCover();
                if (target != null) {
                    addSquadPosition(occupiedCovers, target.getPosition());
                    addSquadPosition(defensivePositions, target.getPosition());
                }
                CoverGoalController peerGoal = ms.getCoverTacticalGoal();
                BlockPos proneLane = peerGoal != null ? peerGoal.getProneDefensivePosition() : null;
                if (proneLane != null) {
                    addSquadPosition(defensivePositions, proneLane);
                } else if (current == null && target == null) {
                    // In open ground, a squadmate's current location is a temporary
                    // spacing signal until it selects its own prone lane this tick.
                    addSquadPosition(defensivePositions, ms.blockPosition());
                }
                Vec3 dir = ms.getThreatAwareness().getPrimaryDirection(member.position());
                if (dir != null && dir.lengthSqr() > 0.001) {
                    threatDirs.add(dir);
                }
            }
        }

        Vec3 ownerPos = null;
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner != null) {
                ownerPos = owner.position();
            }
        }

        return new SquadCoverContext(true, 0, 0, occupiedCovers, defensivePositions, threatDirs,
            firingContacts, peekabilityCache, ownerPos);
    }

    private static void addSquadPosition(List<BlockPos> positions, BlockPos position) {
        if (!positions.contains(position)) {
            positions.add(position.immutable());
        }
    }
}

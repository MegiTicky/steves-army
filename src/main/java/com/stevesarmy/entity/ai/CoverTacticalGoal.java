package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
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

public class CoverTacticalGoal extends Goal {
    // Attack phase enum - what the soldier is trying to do
    public enum AttackPhase {
        NONE,
        SELECTING_COVER,
        MOVING_TO_COVER,
        OCCUPYING_COVER,
        DIRECT_BOUND,
        COMPLETE
    }

    // Result of a cover search operation
    private enum CoverMoveResult {
        COVER_STARTED,
        NO_COVER_FOUND,
        NO_ELIGIBLE_COVER
    }

    private final SoldierEntity soldier;
    private final PathNavigation navigation;
    
    private int cooldown = 0;
    private int stuckTicks = 0;
    private int reevaluateCounter = 0;
    private int noProgressTicks = 0;
    private Vec3 lastSeekingPosition = null;
    
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int REEVALUATE_INTERVAL_TICKS = 60;
    
    private static final double COVER_REACHED_DISTANCE = 1.5D;
    private static final double COVER_VALID_DISTANCE = 2.0D;
    private static final double COMBAT_COVER_VALID_DISTANCE = 6.0D;
    private static final double COVER_ABANDON_DISTANCE = 8.0D;
    
    private static final int SEARCH_RADIUS = 12;
    private static final long MIN_COVER_DWELL_TIME_MS = 4000;
    private static final long MIN_COVER_DWELL_TIME_DAMAGE_MS = 2000;
    private static final long MIN_SUPPRESSED_DWELL_TIME_MS = 6000;
    private static final float HYSTERESIS_THRESHOLD = 0.20f;
    private static final float BACKWARD_HYSTERESIS_THRESHOLD = 0.35f;
    private static final long MIN_PEEK_INTERVAL_MS = 2000;
    private static final long MAX_SEEKING_TIME_MS = 10000;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    private static final float FOLLOW_COVER_DISTANCE = 15.0f;
    
    private static final double FOLLOW_COVER_SEARCH_RADIUS = 15.0D;
    private static final double FOLLOW_REGROUP_DISTANCE = 10.0D;
    
    private static final double POSITIONING_TOLERANCE = 0.05;
    private static final double POSITIONING_SPEED = 1.0;
    private static final long BLACKLIST_CLEAR_INTERVAL_MS = 15000;
    
    private static final double THREAT_ANGLE_REPOSITION_THRESHOLD = 2.09;
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 40;

    private static final float FLANKING_PROTECTION_THRESHOLD = 0.7f;
    private static final float MIN_FLANKING_IMPROVEMENT = 0.1f;
    private static final long FLANK_REPOSITION_COOLDOWN_MS = 5000;
    private static final double MID_MOVE_ANGLE_THRESHOLD = 1.05;

    // Attack-mode constants
    private static final int ATTACK_FORWARD_BIAS_BLOCKS = 6;
    private static final double ATTACK_MIN_FORWARD_PROGRESS = 2.0;
    private static final double ATTACK_DIRECT_BOUND_LENGTH = 6.0;
    private static final long ATTACK_MIN_DWELL_MS = 4000;
    private static final long ATTACK_MAX_DWELL_MS = 8000;
    private static final double ATTACK_OBJECTIVE_RADIUS = 4.0;

    // Direct-bound constants
    private static final double DIRECT_BOUND_ARRIVAL_RADIUS = 6.25; // 2.5 blocks^2
    private static final double DIRECT_BOUND_MIN_DISTANCE = 2.0;
    private static final int DIRECT_BOUND_STUCK_TICKS = 80;
    private static final long DIRECT_BOUND_TIMEOUT_MS = 8000;
    private static final int DIRECT_BOUND_COVER_RECHECK_TICKS = 40;
    private static final int DIRECT_BOUND_MAX_NO_PROGRESS_RESETS = 3;

    // Attack phase state
    private AttackPhase attackPhase = AttackPhase.NONE;
    private int attackCommandGeneration = -1;
    private long attackPhaseStartTime = 0;
    private long attackCoverArrivalTime = 0;
    private BlockPos attackDirectBoundTarget = null;
    private BlockPos attackExpectedCover = null;
    private boolean attackDwellEligible = false;
    private long attackDwellDelay = 0;
    private int attackAdvanceStaggerTicks = 0;
    private boolean attackHasPeekedThisCover = false;

    // Direct-bound state
    private int directBoundStuckTicks = 0;
    private Vec3 directBoundLastPosition = null;
    private long directBoundStartTime = 0;
    private int directBoundCoverRecheckTimer = 0;
    private int directBoundNoProgressResets = 0;
    private boolean directBoundHadValidPath = false;

    private long lastFlankRepositionTime = 0;

    private final Set<BlockPos> failedCoverPositions = new HashSet<>();
    private final java.util.Map<BlockPos, BlacklistEntry> blacklistReasons = new java.util.HashMap<>();
    private long lastBlacklistClearTime = 0;
    private BlockPos lastFailedCover = null;
    private int nonPeekableTicks = 0;
    private int peekCycleLogTick = 0;
    
    private CoverPoint pendingRetryCover = null;
    private boolean isRetryAttempt = false;

    private CoverFinder.ScoredCover[] cachedTopCovers = new CoverFinder.ScoredCover[0];
    private BlockPos debugSearchCenter = null;

    private static boolean debugLoggingEnabled = false;
    private static boolean attackDebugLoggingEnabled = false;

    // Per-soldier rate limiters (ticks between repeated log lines)
    private static final int SNAPSHOT_INTERVAL = 20; // 1 second at 20 TPS
    private int snapshotCounter = 0;
    private int tickLogCounter = 0;
    private int peekLogCounter = 0;
    private int suppressionLogCounter = 0;
    private int threatReportLogCounter = 0;
    private int findNewTargetLogCounter = 0;
    private int moveCtlLogCounter = 0;

    public static void setAttackDebugLogging(boolean enabled) {
        attackDebugLoggingEnabled = enabled;
    }

    public static boolean isAttackDebugLoggingEnabled() {
        return attackDebugLoggingEnabled;
    }

    private boolean attackDebugLog() {
        return attackDebugLoggingEnabled && soldier.hasValidAttackTarget();
    }

    public AttackPhase getAttackPhase() {
        return attackPhase;
    }
    
    public enum BlacklistReason {
        PATH_FAILED("PATH FAILED"),
        STUCK_SEEKING("STUCK SEEKING"),
        STUCK_REPOSITIONING("STUCK REPOS"),
        SHOT_IN_COVER("SHOT IN COVER");
        
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
    
    public static void setDebugLogging(boolean enabled) {
        debugLoggingEnabled = enabled;
    }
    
    public static boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }
    
    public CoverTacticalGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.navigation = soldier.getNavigation();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    
    private CoverBehaviorManager getCoverManager() {
        return soldier.getCoverBehaviorManager();
    }
    
    private PeekController getPeekController() {
        return soldier.getPeekController();
    }
    
    private ThreatAwareness getThreats() {
        return soldier.getThreatAwareness();
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
        if (System.currentTimeMillis() - lastFlankRepositionTime < FLANK_REPOSITION_COOLDOWN_MS) return false;

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

        // Use squad-aware evaluation from the correct center, excluding other soldiers' reservations
        List<CoverFinder.ScoredCover> scored;
        if (!searchCenter.equals(soldier.blockPosition())) {
            scored = finder.evaluateAndScoreAllFromCenter(
                searchCenter, soldier, threatDirection, threats, searchRadius, squadCtx);
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

        Optional<CoverPoint> betterCover = findBetterCoverForFlank();
        if (betterCover.isEmpty()) {
            return Optional.empty();
        }

        float newProtection = getWeightedFlankingProtection(betterCover.get());
        if (newProtection - currentProtection < MIN_FLANKING_IMPROVEMENT) {
            return Optional.empty();
        }

        return betterCover;
    }
    
    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        
        if (!soldier.isAlive()) return false;
        
        // ATTACK mode: always try to use cover (cover-to-cover advance)
        if (soldier.hasValidAttackTarget()) {
            return true;
        }
        
        if (soldier.hasValidPingMoveTarget() && !soldier.hasValidAttackTarget()) {
            if (debugLoggingEnabled) {
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
                        if (debugLoggingEnabled) {
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
        if (debugLoggingEnabled) {
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
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        if (state == CoverBehaviorManager.CoverState.IN_COVER && !getCoverManager().isSuppressed()) {
            if (soldier.getSquadMode() == SquadMode.FOLLOW) {
                LivingEntity owner = soldier.getOwner();
                if (owner != null && soldier.distanceToSqr(owner) > 15 * 15) {
                    getCoverManager().clearCover();
                    getCoverManager().resetPeekState();
                    getPositionController().clear();
                    cooldown = COOLDOWN_TICKS;
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] canContinueToUse=false: FOLLOW mode, not suppressed, far from owner");
                    }
                    return false;
                }
            }
        }
        
        boolean result = state != CoverBehaviorManager.CoverState.NO_COVER || 
                         soldier.hasValidAttackTarget();
        if (debugLoggingEnabled) {
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
            findAndMoveToCover();
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER || 
                   state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            if (getCoverManager().getTargetCover() == null) {
                findAndMoveToCover();
            } else {
                moveToCover(getCoverManager().getTargetCover());
            }
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        } else if (state == CoverBehaviorManager.CoverState.IN_COVER ||
                   state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
    }
    
    @Override
    public void stop() {
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        // Don't clear cover if we're in ATTACK mode - preserve state across interruptions
        if (soldier.hasValidAttackTarget()) {
            return;
        }
        
        if (state == CoverBehaviorManager.CoverState.IN_COVER ||
            state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
            getCoverManager().clearCover();
        } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER ||
                   state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            getCoverManager().clearTargetCover();
        }
        
        pendingRetryCover = null;
        isRetryAttempt = false;
        cooldown = COOLDOWN_TICKS;
        stuckTicks = 0;
    }
    
    @Override
    public void tick() {
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        getCoverManager().tickSuppression(getCoverManager().isInCover());
        
        // Sync threat direction to client for debug rendering
        Vec3 threatDir = getThreats().getThreatDirectionForProactivePeek(soldier.position());
        soldier.syncThreatDirection(threatDir);
        
        PeekController peekCtrl = getPeekController();
        
        if (debugLoggingEnabled) {
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
                setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                tickSeekingCover();
                break;
            case REPOSITIONING:
                setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                tickRepositioning();
                break;
            case IN_COVER:
                // Keep MOVE flags during ATTACK mode so cover goal owns movement
                if (soldier.hasValidAttackTarget()) {
                    setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                } else {
                    setFlags(EnumSet.noneOf(Flag.class));
                }
                tickInCover();
                break;
            case SUPPRESSED_IN_COVER:
                if (soldier.hasValidAttackTarget()) {
                    setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                } else {
                    setFlags(EnumSet.noneOf(Flag.class));
                }
                tickSuppressedInCover();
                break;
            case NO_COVER:
                // Attack mode: trigger immediate cover search
                if (soldier.hasValidAttackTarget() && attackPhase == AttackPhase.NONE) {
                    setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                    initAttackPhase();
                }
                break;
        }
        
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
            if (debugLoggingEnabled) {
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
        // Handle pending retry from previous tick
        if (pendingRetryCover != null) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} retrying path to cover {}", 
                    soldier.getId(), pendingRetryCover.getPosition());
            }
            isRetryAttempt = true;
            moveToCover(pendingRetryCover);
            isRetryAttempt = false;
            pendingRetryCover = null;
            return;
        }
        
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        if (targetCover == null) {
            findAndMoveToCover();
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) reached cover at dist={} cover={}",
                    soldier.getId(), soldier.getName().getString(), String.format("%.2f", distance), targetCover.getPosition());
            }
            onCoverReached(targetCover);
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        if (distance < COVER_VALID_DISTANCE) {
            CoverPositionController moveControl = getPositionController();
            if (moveControl.getLastResult() != CoverPositionController.MovementResult.IN_PROGRESS) {
                if (soldier.hasValidAttackTarget()) {
                    StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) handoff to position controller at dist={} cover={}",
                        soldier.getId(), soldier.getName().getString(), String.format("%.2f", distance), targetCover.getPosition());
                }
                navigation.stop();
                moveControl.moveTo(getCoverStandingPosition(targetCover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickSeekingCover", "recenter to target cover");
            }
            stuckTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
        } else {
            Vec3 currentPos = soldier.position();
            
            if (navigation.isDone()) {
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
                    findAndMoveToCover();
                }
            } else {
                stuckTicks = 0;
                
                if (lastSeekingPosition != null) {
                    double moved = currentPos.distanceTo(lastSeekingPosition);
                    if (moved < 0.1) {
                        noProgressTicks++;
                        if (noProgressTicks > 40) {
                            if (debugLoggingEnabled) {
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
        }
        
        if (getCoverManager().getTimeSeeking() > MAX_SEEKING_TIME_MS) {
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) ATTACK seeking timeout, resetting. cover={}",
                    soldier.getId(), soldier.getName().getString(), targetCover != null ? targetCover.getPosition() : "null");
            }
            getCoverManager().resetPeekState();
            getPositionController().clear();
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            noProgressTicks = 0;
            lastSeekingPosition = null;
        }
    }
    
    private void tickRepositioning() {
        // Handle pending retry from previous tick
        if (pendingRetryCover != null) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} retrying path to cover {}",
                    soldier.getId(), pendingRetryCover.getPosition());
            }
            isRetryAttempt = true;
            moveToCover(pendingRetryCover);
            isRetryAttempt = false;
            pendingRetryCover = null;
            return;
        }

        CoverPoint targetCover = getCoverManager().getTargetCover();
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        // Mid-move organic decision making (50% chance to even consider, 50% chance to cancel if threat shifted)
        if (soldier.getRandom().nextFloat() < 0.5f) {
            Vec3 currentThreatDir = getThreats().getPrimaryDirection(soldier.position());
            Vec3 entryThreatDir = getCoverManager().getEntryThreatDirection();
            
            if (currentThreatDir != null && entryThreatDir != null && 
                currentThreatDir.lengthSqr() > 0.01 && entryThreatDir.lengthSqr() > 0.01) {
                double dot = currentThreatDir.dot(entryThreatDir) / (currentThreatDir.length() * entryThreatDir.length());
                double angle = Math.acos(net.minecraft.util.Mth.clamp(dot, -1.0, 1.0));
                
                if (angle > MID_MOVE_ANGLE_THRESHOLD) {
                    if (soldier.getRandom().nextFloat() < 0.5f) {
                        if (debugLoggingEnabled) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} mid-move threat shift, cancelling reposition",
                                soldier.getId());
                        }
                        getCoverManager().clearTargetCover();
                        findAndMoveToCover();
                        return;
                    } else {
                        if (debugLoggingEnabled) {
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
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            onCoverReached(targetCover);
            noProgressTicks = 0;
            lastSeekingPosition = null;
            return;
        }
        
        if (distance < COVER_VALID_DISTANCE) {
            CoverPositionController moveControl = getPositionController();
            if (moveControl.getLastResult() != CoverPositionController.MovementResult.IN_PROGRESS) {
                navigation.stop();
                moveControl.moveTo(getCoverStandingPosition(targetCover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickRepositioning", "recenter to target cover");
            }
            stuckTicks = 0;
            noProgressTicks = 0;
            lastSeekingPosition = null;
        } else {
            Vec3 currentPos = soldier.position();
            
            if (navigation.isDone()) {
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
                            if (debugLoggingEnabled) {
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
    }
    
    private void tickInCover() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover != null) {
            double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
            PeekController peekCtrl = getPeekController();
            boolean peeking = peekCtrl.isExposed() || peekCtrl.isMovingToPeek() || peekCtrl.isReturning();
            
            if (debugLoggingEnabled) {
                tickLogCounter++;
                if (tickLogCounter >= SNAPSHOT_INTERVAL) {
                    tickLogCounter = 0;
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tickInCover: dist={}, abandon={}, valid={}, peeking={}, target={}",
                        soldier.getId(), String.format("%.2f", distance),
                        distance > COVER_ABANDON_DISTANCE,
                        distance > COVER_VALID_DISTANCE,
                        peeking,
                        (soldier.getTarget() != null ? soldier.getTarget().getName().getString() : "null"));
                }
            }
            if (distance > COVER_ABANDON_DISTANCE) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} drifted too far from cover ({} > {}), abandoning",
                        soldier.getId(), String.format("%.1f", distance), COVER_ABANDON_DISTANCE);
                }
                getCoverManager().clearCover();
                getPositionController().clear();
                return;
            }
            if (distance > COVER_VALID_DISTANCE && !peeking) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} got pushed from cover ({} > {}), re-seeking",
                        soldier.getId(), String.format("%.1f", distance), COVER_VALID_DISTANCE);
                }
                getCoverManager().clearCover();
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                getPositionController().clear();
                return;
            }
            
            // Recenter to cover when idle
            if (!peeking && getPositionController().getLastResult() != CoverPositionController.MovementResult.IN_PROGRESS) {
                navigation.stop();
                getPositionController().moveTo(getCoverStandingPosition(currentCover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickInCover", "recenter to cover");
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
                if (debugLoggingEnabled) {
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
            if (currentCover != null && currentCover.getType() == CoverType.HALF) {
                soldier.setLowCrouching(true);
            }
            if (getPeekController().isExposed()) {
                getPeekController().tick(soldier, currentCover, getPositionController());
            }
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            return;
        }

        // Process pending reposition requests (shot-in-cover, non-peekable)
        // Only acts when fully recovered — while suppressed, stays pending as BLOCKED.
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
            getPeekController().tick(soldier, currentCover, getPositionController());
        }

        reevaluateCounter++;
        if (reevaluateCounter >= REEVALUATE_INTERVAL_TICKS) {
            reevaluateCounter = 0;
            evaluateCoverState();
        }
    }
    
    private void tickSuppressedInCover() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        // Shot-in-cover trigger — keep the request pending while suppressed
        // act on it after recovery (when transition to IN_COVER)
        if (getCoverManager().isShotInCoverRepositionRequested()) {
            // Request stays pending; do nothing
        }

        // Handle non-peekable cover reposition request — also keep pending while suppressed
        if (getCoverManager().isRepositionRequested()) {
            // Request stays pending; do nothing
        }

        // Force duck-back if soldier was exposed or moving to peek when suppressed
        PeekController peekCtrl = getPeekController();
        if (peekCtrl.isExposed() || peekCtrl.isMovingToPeek()) {
            peekCtrl.forceReturnToCover(soldier, currentCover, getPositionController());
        }

        // Let peek controller handle ongoing duck back
        if (peekCtrl.isReturning()) {
            peekCtrl.tick(soldier, currentCover, getPositionController());
        }

// Flank detection is deferred while suppressed: the soldier will
        // evaluate flank repositioning after recovery in tickInCover()
        // (recovery transitions us from SUPPRESSED_IN_COVER back to IN_COVER)

        float sup = getCoverManager().getSuppressionTracker().getSuppressionLevel();
        boolean canPeek = getCoverManager().getSuppressionTracker().canPeek();
        
        if (canPeek) {
            if (currentCover != null && currentCover.getType() == CoverType.HALF) {
                soldier.setLowCrouching(false);
            }
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        if (shouldExitCoverForFollow() && !getCoverManager().isSuppressed()) {
            getCoverManager().clearCover();
        }
    }

    /**
     * Consolidated handling of pending reposition requests (shot-in-cover, non-peekable).
     * Returns NONE, BLOCKED (still waiting, but allow other cover processing),
     * or MOVEMENT_STARTED (soldier is now repositioning).
     * While suppressed or not fully recovered, requests remain pending as BLOCKED.
     */
    private enum PendingRepositionResult { NONE, BLOCKED, MOVEMENT_STARTED }

    private PendingRepositionResult processPendingRepositionRequests() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();

        // Shot-in-cover request
        if (getCoverManager().isShotInCoverRepositionRequested()) {
            if (!canLeaveCoverNow()) {
                return PendingRepositionResult.BLOCKED;
            }
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} shot while hiding in cover, repositioning",
                    soldier.getId());
            }
            if (currentCover != null) {
                blacklistCover(currentCover.getPosition(), BlacklistReason.SHOT_IN_COVER);
            }
            getCoverManager().clearShotInCoverRepositionRequest();
            startRepositioning();
            return PendingRepositionResult.MOVEMENT_STARTED;
        }

        // Non-peekable cover reposition request
        if (getCoverManager().isRepositionRequested()) {
            if (!canLeaveCoverNow()) {
                return PendingRepositionResult.BLOCKED;
            }
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} reposition requested, acting on it",
                    soldier.getId());
            }
            getCoverManager().clearRepositionRequest();
            startRepositioning();
            return PendingRepositionResult.MOVEMENT_STARTED;
        }

        return PendingRepositionResult.NONE;
    }
    
private boolean shouldSeekCover() {
        ThreatAwareness threats = getThreats();

        if (soldier.hasValidAttackTarget()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover=true (ATTACK mode)");
            return true;
        }

        if (soldier.isCQB() || soldier.hasCloseRangeTarget()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover=false (CQB mode={}, closeRangeTarget={})",
                soldier.isCQB(), soldier.hasCloseRangeTarget());
            return false;
        }
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            boolean hasValid = getCoverManager().getCurrentCover() != null && isCoverStillValid();
            boolean result = !hasValid;
            StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (HOLD mode, hasValidCover={})", result, hasValid);
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
                StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (FOLLOW close, suppressed={}, lowHealth={} health={}, hasThreat={})",
                    result, suppressed, lowHealth, String.format("%.2f", healthRatio), hasThreat);
            } else {
                result = suppressed || lowHealth;
                StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (FOLLOW far, suppressed={}, lowHealth={} health={})",
                    result, suppressed, lowHealth, String.format("%.2f", healthRatio));
            }
            return result;
        }
        
        boolean suppressed = getCoverManager().isSuppressed();
        float healthRatio = soldier.getHealth() / soldier.getMaxHealth();
        boolean lowHealth = healthRatio < LOW_HEALTH_THRESHOLD;
        boolean hasThreat = threats.hasActiveThreat();
        
        boolean result = (suppressed || lowHealth) || hasThreat;
        StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (suppressed={}, lowHealth={} health={}, hasThreat={})",
            result, suppressed, lowHealth, String.format("%.2f", healthRatio), hasThreat);
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
                    StevesArmyMod.LOGGER.info("[CoverGoal] Cover invalid: too far from owner (dist={})",
                        String.format("%.1f", Math.sqrt(distToOwner)));
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
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
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
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} threat direction changed, repositioning",
                            soldier.getId());
                    }
                    startRepositioning();
                    return;
                }
            }
        }

        if (debugLoggingEnabled) {
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
        getCoverManager().resetPeekState();
        getCoverManager().setPeekPosition(null);
        getPositionController().clear();
        findAndMoveToCover();
        if (getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
    }
    
    private boolean startRepositioning(CoverPoint newCover) {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        
        if (currentCover != null && newCover.getPosition().equals(currentCover.getPosition())) return false;
        
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
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
            moveToCover(newCover);
            return true;
        }
        return false;
    }
    
    private CoverMoveResult findAndMoveToCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        
        long now = System.currentTimeMillis();
        if (now - lastBlacklistClearTime > BLACKLIST_CLEAR_INTERVAL_MS) {
            failedCoverPositions.clear();
            blacklistReasons.clear();
            lastBlacklistClearTime = now;
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} cleared failed cover blacklist", soldier.getId());
            }
        }
        
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
this.debugSearchCenter = searchCenter;
        
        Optional<CoverPoint> bestCover = Optional.empty();

        if (soldier.hasValidAttackTarget()) {
            // ATTACK mode: forward-biased search from the beginning
            List<CoverFinder.ScoredCover> scored = finder.evaluateAndScoreAllFromCenter(
                searchCenter, soldier, threatDirection, threats, searchRadius, squadCtx);

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
            // skip current/blacklisted covers
            for (CoverFinder.ScoredCover sc : scored) {
                CoverPoint cover = sc.cover;
                if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;
                if (failedCoverPositions.contains(cover.getPosition())) continue;

                // Hard forward progress filter
                if (objectiveDir != null) {
                    Vec3 candidateDisp = cover.getPosition().getCenter().subtract(soldierPos);
                    double forwardProgress = candidateDisp.x * objectiveDir.x + candidateDisp.z * objectiveDir.z;
                    if (forwardProgress < ATTACK_MIN_FORWARD_PROGRESS) continue;
                }

                // Hard primary-threat protection preference
                if (threatDir != null) {
                    Set<Direction> protectedDirs = cover.getProtectedDirections();
                    if (protectedDirs == null || !protectedDirs.contains(threatDir)) {
                        continue;
                    }
                }

                bestCover = Optional.of(cover);
                break;
            }

            // If forward-biased found nothing protected, search from soldier position as fallback
            // (still requires primary-threat protection)
            if (bestCover.isEmpty()) {
                List<CoverFinder.ScoredCover> localScored = finder.evaluateAndScoreAll(
                    soldier, threatDirection, threats, searchRadius, true, squadCtx);
                for (CoverFinder.ScoredCover sc : localScored) {
                    CoverPoint cover = sc.cover;
                    if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) continue;
                    if (failedCoverPositions.contains(cover.getPosition())) continue;

                    // Fallback still requires primary-threat protection
                    if (threatDir != null) {
                        Set<Direction> protectedDirs = cover.getProtectedDirections();
                        if (protectedDirs == null || !protectedDirs.contains(threatDir)) {
                            continue;
                        }
                    }

                    bestCover = Optional.of(cover);
                    break;
                }
            }
        } else {
            bestCover = finder.findBestCover(
                soldier,
                threatDirection,
                threats,
                searchRadius,
                squadCtx
            );

            if (bestCover.isEmpty()) {
                bestCover = finder.findBestCover(
                    searchCenter,
                    searchRadius,
                    threats.isEmpty() ? null : threats.get(0),
                    threatDirection
                );
            }

            if (bestCover.isEmpty() && squadCtx.inSquad()) {
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
            
            boolean wantsDebug = debugLoggingEnabled || CoverDebugManager.isShowSoldierCover();
            if (wantsDebug) {
                List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, searchRadius, 5, true);
                cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
            }
            
            if (failedCoverPositions.contains(cover.getPosition())) {
                List<CoverFinder.ScoredCover> scored = finder.evaluateAndScoreAll(
                    soldier, threatDirection, threats, searchRadius, true);
                
                scored = scored.stream()
                    .filter(sc -> !failedCoverPositions.contains(sc.cover.getPosition()))
                    .collect(java.util.stream.Collectors.toList());
                
                if (scored.isEmpty()) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} no valid covers after filtering blacklist", soldier.getId());
                    return CoverMoveResult.NO_ELIGIBLE_COVER;
                }
                
                cover = scored.get(0).cover;
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} selected fallback cover {} (score={})", 
                    soldier.getId(), cover.getPosition(), String.format("%.2f", scored.get(0).score));
            }
            
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) {
                onCoverReached(cover);
                return CoverMoveResult.COVER_STARTED;
            }
            
            if (currentCover != null) {
                double distToSoldier = soldier.position().distanceTo(cover.getPosition().getCenter());
                if (distToSoldier < COVER_REACHED_DISTANCE) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} selected cover {} too close, blacklisting and falling back to seek",
                            soldier.getId(), cover.getPosition());
                    }
                    blacklistCover(cover.getPosition(), BlacklistReason.STUCK_REPOSITIONING);
                    getCoverManager().clearTargetCover();
                    getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                    return CoverMoveResult.NO_COVER_FOUND;
                }
            }
            
            getCoverManager().clearCoverQualityPenalty();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                getCoverManager().setTargetCover(cover);
                if (soldier.hasValidAttackTarget()) {
                    attackExpectedCover = cover.getPosition();
                }
                moveToCover(cover);
                return CoverMoveResult.COVER_STARTED;
            }
            return CoverMoveResult.NO_COVER_FOUND;
        }
        return CoverMoveResult.NO_COVER_FOUND;
    }
    
private Optional<CoverPoint> findBetterCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);

        Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} finding cover: soldierPos={}, threatDirection=({}, {}, {}), threats={}",
            soldier.getId(),
            soldier.blockPosition(),
            threatDirection != null ? String.format("%.2f", threatDirection.x) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.y) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.z) : "null",
            threats.size());

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

        boolean wantsDebug = debugLoggingEnabled || CoverDebugManager.isShowSoldierCover();
        if (wantsDebug) {
            List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 5, true);
            cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
        }

        return result;
    }

    private void populateCoverDebugData() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        boolean wantsDebug = debugLoggingEnabled || CoverDebugManager.isShowSoldierCover() || CoverDebugManager.isVisualizationEnabled();
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

    private void resetDirectBoundState() {
        attackDirectBoundTarget = null;
        directBoundStuckTicks = 0;
        directBoundLastPosition = null;
        directBoundStartTime = 0;
        directBoundCoverRecheckTimer = 0;
        directBoundNoProgressResets = 0;
        directBoundHadValidPath = false;
    }

    private void initAttackPhase() {
        int gen = soldier.getAttackGeneration();
        if (attackCommandGeneration != gen) {
            attackCommandGeneration = gen;
            attackPhase = AttackPhase.SELECTING_COVER;
            attackPhaseStartTime = System.currentTimeMillis();
            attackCoverArrivalTime = 0;
            attackDirectBoundTarget = null;
            attackExpectedCover = null;
            attackDwellEligible = false;
            attackHasPeekedThisCover = false;
            resetDirectBoundState();
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

        BlockPos objective = soldier.getAttackTargetPos();
        double distToObjective = soldier.distanceToSqr(
            objective.getX() + 0.5, objective.getY() + 0.5, objective.getZ() + 0.5);
        double objRadiusSq = ATTACK_OBJECTIVE_RADIUS * ATTACK_OBJECTIVE_RADIUS;

        // Objective reached
        if (distToObjective <= objRadiusSq) {
            if (attackPhase != AttackPhase.COMPLETE) {
                attackPhase = AttackPhase.COMPLETE;
                soldier.setAttackFinalApproach(true);
                if (attackDebugLog()) {
                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} objective reached, completing", soldier.getId());
                }
            }
            return;
        }

        CoverBehaviorManager.CoverState coverState = getCoverManager().getState();
        CoverPoint currentCover = getCoverManager().getCurrentCover();

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

                CoverMoveResult result;
                if (currentCover != null) {
                    // Already in a cover — use canonical repositioning to synchronize cover state
                    result = findAndMoveToCover();
                    if (getCoverManager().getTargetCover() != null) {
                        getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
                        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                    }
                } else {
                    result = findAndMoveToCover();
                }

                if (result == CoverMoveResult.NO_COVER_FOUND || result == CoverMoveResult.NO_ELIGIBLE_COVER) {
                    // No cover available — fall back to direct bound
                    startDirectBound();
                    attackPhase = AttackPhase.DIRECT_BOUND;
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} SELECTING_COVER -> DIRECT_BOUND: no cover available (result={})",
                            soldier.getId(), result);
                    }
                } else {
                    attackPhase = AttackPhase.MOVING_TO_COVER;
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} SELECTING_COVER -> MOVING_TO_COVER: cover found",
                            soldier.getId());
                    }
                }
                break;
            }

            case MOVING_TO_COVER: {
                CoverPoint targetCover = getCoverManager().getTargetCover();

                // Reconciliation: if the cover system has parked us in a cover
                // (e.g., suppression pulled us back while we were advancing),
                // treat it as arrival so the attack phase can recover.
                if ((coverState == CoverBehaviorManager.CoverState.IN_COVER ||
                     coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) &&
                    currentCover != null) {
                    attackPhase = AttackPhase.OCCUPYING_COVER;
                    attackCoverArrivalTime = System.currentTimeMillis();
                    attackExpectedCover = null;
                    attackDwellEligible = false;
                    attackHasPeekedThisCover = false;
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
                    if (!currentCover.getPosition().equals(attackExpectedCover)) {
                        StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) attackExpectedCover mismatch! expected={} actual={}",
                            soldier.getId(), soldier.getName().getString(), attackExpectedCover, currentCover.getPosition());
                    }
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
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} MOVING_TO_COVER -> OCCUPYING_COVER: reached cover {}, dwell timer starts",
                            soldier.getId(), currentCover.getPosition());
                    }
                    break;
                }

                // Recovery: target cover was lost (e.g., blacklisted during navigation)
                if (targetCover == null) {
                    attackPhase = AttackPhase.SELECTING_COVER;
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
                if (!attackHasPeekedThisCover && !peeking && peekCtrl.getState() == PeekController.State.HIDING
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

                if (attackDebugLog() && dwellMet && (!recovered || peeking)) {
                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} dwell met but blocked: recovered={}, peeking={}, suppression={}",
                        soldier.getId(), recovered, peeking,
                        String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));
                }

                if (maxDwellReached || (canAdvance && attackHasPeekedThisCover)) {
                    if (selectForwardCover()) {
                        attackPhase = AttackPhase.MOVING_TO_COVER;
                        if (attackDebugLog()) {
                            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} {} -> MOVING_TO_COVER: advancing to next cover",
                                soldier.getId(), maxDwellReached ? "max dwell" : "advance");
                        }
                    } else {
                        startDirectBound();
                        attackPhase = AttackPhase.DIRECT_BOUND;
                        if (attackDebugLog()) {
                            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} {} -> DIRECT_BOUND: no forward cover",
                                soldier.getId(), maxDwellReached ? "max dwell" : "advance");
                        }
                    }
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

            case DIRECT_BOUND: {
                // If cover appeared (e.g., cover system found one), preempt
                if (coverState == CoverBehaviorManager.CoverState.IN_COVER ||
                    coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
                    resetDirectBoundState();
                    attackPhase = AttackPhase.OCCUPYING_COVER;
                    attackCoverArrivalTime = System.currentTimeMillis();
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} DIRECT_BOUND -> OCCUPYING_COVER: cover preempted bound",
                            soldier.getId());
                    }
                    break;
                }

                // Periodic cover recheck while bounding
                if (++directBoundCoverRecheckTimer >= DIRECT_BOUND_COVER_RECHECK_TICKS) {
                    directBoundCoverRecheckTimer = 0;
                    CoverMoveResult recheck = findAndMoveToCover();
                    if (recheck == CoverMoveResult.COVER_STARTED) {
                        resetDirectBoundState();
                        attackPhase = AttackPhase.MOVING_TO_COVER;
                        if (attackDebugLog()) {
                            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} DIRECT_BOUND -> MOVING_TO_COVER: cover found during bound recheck",
                                soldier.getId());
                        }
                        break;
                    }
                }

                // Check arrival at bound target
                if (attackDirectBoundTarget != null) {
                    double distToBound = soldier.distanceToSqr(
                        attackDirectBoundTarget.getX() + 0.5,
                        attackDirectBoundTarget.getY() + 0.5,
                        attackDirectBoundTarget.getZ() + 0.5);
                    if (distToBound <= DIRECT_BOUND_ARRIVAL_RADIUS) {
                        resetDirectBoundState();
                        attackPhase = AttackPhase.SELECTING_COVER;
                        if (attackDebugLog()) {
                            StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} direct bound complete, re-selecting cover", soldier.getId());
                        }
                        break;
                    }
                }

                // Stuck detection
                Vec3 currentPos = soldier.position();
                if (directBoundLastPosition != null) {
                    double moved = currentPos.distanceToSqr(directBoundLastPosition);
                    if (moved < 0.01) {
                        if (++directBoundStuckTicks >= DIRECT_BOUND_STUCK_TICKS) {
                            if (directBoundNoProgressResets < DIRECT_BOUND_MAX_NO_PROGRESS_RESETS) {
                                directBoundNoProgressResets++;
                                directBoundStuckTicks = 0;
                                directBoundLastPosition = null;
                                if (attackDebugLog()) {
                                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} direct bound stuck, retrying bound (attempt {}/{})",
                                        soldier.getId(), directBoundNoProgressResets, DIRECT_BOUND_MAX_NO_PROGRESS_RESETS);
                                }
                                startDirectBound();
                            } else {
                                if (attackDebugLog()) {
                                    StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} direct bound failed after {} retries, falling back to SELECTING_COVER",
                                        soldier.getId(), DIRECT_BOUND_MAX_NO_PROGRESS_RESETS);
                                }
                                resetDirectBoundState();
                                attackPhase = AttackPhase.SELECTING_COVER;
                            }
                            break;
                        }
                    } else {
                        directBoundStuckTicks = 0;
                    }
                }
                directBoundLastPosition = currentPos;

                // Timeout
                long boundTime = System.currentTimeMillis() - directBoundStartTime;
                if (boundTime > DIRECT_BOUND_TIMEOUT_MS) {
                    if (attackDebugLog()) {
                        StevesArmyMod.LOGGER.info("[AttackPhase] Soldier {} direct bound timed out ({}ms), re-selecting cover",
                            soldier.getId(), boundTime);
                    }
                    resetDirectBoundState();
                    attackPhase = AttackPhase.SELECTING_COVER;
                    break;
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

        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        SquadCoverContext squadCtx = buildSquadCoverContext();

        List<CoverFinder.ScoredCover> scored = finder.evaluateAndScoreAllFromCenter(
            searchCenter, soldier, threatDirection, threats, SEARCH_RADIUS, squadCtx);

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
            if (forwardProgress < ATTACK_MIN_FORWARD_PROGRESS) continue;

            // Don't go beyond the objective
            double distToObj = cover.getPosition().distSqr(objective);
            if (distToObj <= ATTACK_OBJECTIVE_RADIUS * ATTACK_OBJECTIVE_RADIUS) continue;

            // Hard primary-threat protection preference
            if (threatDir != null) {
                Set<Direction> protectedDirs = cover.getProtectedDirections();
                if (protectedDirs == null || !protectedDirs.contains(threatDir)) {
                    continue;
                }
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

        return false;
    }

    /**
     * Start a short direct bound toward the objective when no cover is available.
     */
    private void startDirectBound() {
        if (!soldier.hasValidAttackTarget()) return;
        BlockPos objective = soldier.getAttackTargetPos();

        Vec3 toTarget = new Vec3(
            objective.getX() - soldier.getX(),
            0,
            objective.getZ() - soldier.getZ());
        double dist = toTarget.length();
        if (dist < DIRECT_BOUND_MIN_DISTANCE) return;

        Vec3 dir = toTarget.normalize();
        double boundLen = Math.min(ATTACK_DIRECT_BOUND_LENGTH, dist - ATTACK_OBJECTIVE_RADIUS);
        if (boundLen < 1.0) return; // skip zero-length bounds

        // Compute destination as a real-world position, not just BlockPos offset
        Vec3 dest = soldier.position().add(dir.x * boundLen, 0, dir.z * boundLen);
        BlockPos destBlock = new BlockPos((int)Math.floor(dest.x), soldier.blockPosition().getY(), (int)Math.floor(dest.z));

        // Validate path before clearing cover state
        Path path = soldier.getNavigation().createPath(destBlock.getX() + 0.5, destBlock.getY(), destBlock.getZ() + 0.5, 1);
        if (path == null || !path.canReach()) {
            // Try a shorter bound
            double shortLen = Math.min(boundLen * 0.5, 3.0);
            if (shortLen < 1.0) return;
            dest = soldier.position().add(dir.x * shortLen, 0, dir.z * shortLen);
            destBlock = new BlockPos((int)Math.floor(dest.x), soldier.blockPosition().getY(), (int)Math.floor(dest.z));
            path = soldier.getNavigation().createPath(destBlock.getX() + 0.5, destBlock.getY(), destBlock.getZ() + 0.5, 1);
            if (path == null || !path.canReach()) return;
            boundLen = shortLen;
        }

        // Only now clear cover and start the bound
        getCoverManager().clearCover();
        attackDirectBoundTarget = destBlock;
        directBoundStartTime = System.currentTimeMillis();
        directBoundLastPosition = soldier.position();
        directBoundStuckTicks = 0;
        directBoundCoverRecheckTimer = 0;
        directBoundHadValidPath = true;

        soldier.getNavigation().moveTo(path, 1.2D);

        if (attackDebugLog()) {
            StevesArmyMod.LOGGER.info("[AttackDirectBound] Soldier {} bounding to {} (len={}, objDist={})",
                soldier.getId(), destBlock, String.format("%.1f", boundLen), String.format("%.1f", dist));
        }
    }

    private CoverPositionController getPositionController() {
        return (CoverPositionController) soldier.getMoveControl();
    }
    
public static Vec3 getCoverStandingPositionStatic(BlockPos coverPos) {
        return new Vec3(coverPos.getX() + 0.5, coverPos.getY(), coverPos.getZ() + 0.5);
    }

    private Vec3 getCoverStandingPosition(BlockPos coverPos) {
        return getCoverStandingPositionStatic(coverPos);
    }
    
    private void moveToCover(CoverPoint cover) {
        BlockPos wallPos = cover.getPosition();
        
        if (StevesArmyMod.teleportOnlyMode) {
            soldier.moveTo(wallPos.getX() + 0.5, wallPos.getY(), wallPos.getZ() + 0.5, soldier.getYRot(), soldier.getXRot());
            onCoverReached(cover);
            return;
        }
        
        Vec3 standingPos = getCoverStandingPosition(wallPos);
        Path path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 1);
        
        boolean isReachable = false;
        String failReason = "null path";
        
        if (path != null) {
            if (path.canReach()) {
                isReachable = true;
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path REACHED standing {} (canReach=true)", 
                        soldier.getId(), soldier.blockPosition(), standingPos);
                }
            } else if (path.getNodeCount() > 0) {
                net.minecraft.world.level.pathfinder.Node endNode = path.getNode(path.getNodeCount() - 1);
                BlockPos endPos = endNode.asBlockPos();
                
                double distSq = endPos.distSqr(wallPos);
                int yDiff = Math.abs(endPos.getY() - wallPos.getY());
                double dist = Math.sqrt(distSq);
                
                if (distSq <= 4.0 && yDiff <= 1) {
                    isReachable = true;
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path ACCEPTED: wall={}, end={}, dist={}, yDiff={}", 
                            soldier.getId(), soldier.blockPosition(), wallPos, endPos, String.format("%.2f", dist), yDiff);
                    }
                } else {
                    failReason = String.format("endpoint too far: dist=%.2f (>2.0), yDiff=%d (>1)", dist, yDiff);
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} path FAILED endpoint check: wall={}, end={}, dist={}, yDiff={}", 
                            soldier.getId(), soldier.blockPosition(), wallPos, endPos, String.format("%.2f", dist), yDiff);
                    }
                }
            } else {
                failReason = "path has no nodes";
            }
        }
        
        if (isReachable) {
            pendingRetryCover = null; // Clear any pending retry on success
            navigation.moveTo(path, 1.2);
            if (soldier.hasValidAttackTarget()) {
                StevesArmyMod.LOGGER.info("[CoverNav] Soldier {} ({}) ATTACK nav to cover {} from pos {} dist={}",
                    soldier.getId(), soldier.getName().getString(), wallPos, soldier.blockPosition(),
                    String.format("%.2f", soldier.position().distanceTo(standingPos)));
            }
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} started navigation to cover {} from pos {} (path nodes={})", 
                    soldier.getId(), wallPos, soldier.blockPosition(), path.getNodeCount());
            }
        } else {
            // If this is a null path and NOT already a retry, schedule retry for next tick
            if (path == null && !isRetryAttempt) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} null path to cover {}, scheduling retry next tick", 
                        soldier.getId(), soldier.blockPosition(), wallPos);
                }
                pendingRetryCover = cover;
                getCoverManager().setTargetCover(cover);
                return;
            }
            
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} BLACKLISTED cover {} reason: {}{}", 
                    soldier.getId(), soldier.blockPosition(), wallPos, failReason, isRetryAttempt ? " (after retry)" : "");
            }
            blacklistCover(wallPos, BlacklistReason.PATH_FAILED);
        }
    }
    
    private void onCoverReached(CoverPoint cover) {
        // Promote target to current — releases old reservation, sets metadata
        getCoverManager().promoteTargetToCurrentCover();
        
        navigation.stop();
        
        getCoverManager().resetPeekState();
        getCoverManager().setNonPeekableCover(false);
        getCoverManager().clearRepositionRequest();
        getCoverManager().clearShotInCoverRepositionRequest();
        nonPeekableTicks = 0;
        
        // Renew reservation for the new current cover
        CoverReservationManager.reserve(cover.getPosition(), soldier);
        
        getPositionController().moveTo(getCoverStandingPosition(cover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "onCoverReached", "initial cover positioning");
        
        // Reset peek controller for new cover and seed the peek cooldown
        // so soldier settles before peeking — prevents immediate "jump out"
        getPeekController().resetForNewCover(cover.getPosition());
        getPeekController().setLastPeekEndTime(System.currentTimeMillis());
        
        // Compute peek position with LOS validation for full cover
        if (cover.getType() == CoverType.FULL) {
            Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
            LivingEntity target = soldier.getTarget();
            if (threatDirection != null && threatDirection.lengthSqr() > 0.001) {
                BlockPos peekPos = computePeekPosition(cover, threatDirection, target);
                getCoverManager().setPeekPosition(peekPos);
                if (peekPos == null && debugLoggingEnabled) {
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
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} blacklisted cover at {} reason={}",
                soldier.getId(), pos, reason.label);
        }
    }
    
    private void doLowCrouchIfHalfCover() {
        CoverPoint cover = getCoverManager().getCurrentCover();
        if (cover != null && cover.getType() == CoverType.HALF) {
            if (!getCoverManager().isSuppressed()) {
                soldier.setLowCrouching(false);
            } else {
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
            return new SquadCoverContext(false, 0, 0, List.of(), List.of(), null);
        }

        SquadManager mgr = SquadManager.get(serverLevel);

        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, soldier.getUUID());

        List<BlockPos> occupiedCovers = new ArrayList<>();
        List<Vec3> threatDirs = new ArrayList<>();

        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity ms) {
                CoverBehaviorManager cbm = ms.getCoverBehaviorManager();
                CoverPoint current = cbm.getCurrentCover();
                if (current != null) occupiedCovers.add(current.getPosition());
                CoverPoint target = cbm.getTargetCover();
                if (target != null && !occupiedCovers.contains(target.getPosition())) {
                    occupiedCovers.add(target.getPosition());
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

        return new SquadCoverContext(true, 0, 0, occupiedCovers, threatDirs, ownerPos);
    }
}
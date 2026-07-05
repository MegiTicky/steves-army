package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CoverTacticalGoal extends Goal {
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
    private static final long MIN_SUPPRESSED_DWELL_TIME_MS = 6000;
    private static final float HYSTERESIS_THRESHOLD = 0.35f;
    private static final long MIN_PEEK_INTERVAL_MS = 2000;
    private static final long MAX_SEEKING_TIME_MS = 10000;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    
    private static final double FOLLOW_COVER_SEARCH_RADIUS = 15.0D;
    private static final double FOLLOW_REGROUP_DISTANCE = 10.0D;
    
    private static final double POSITIONING_TOLERANCE = 0.05;
    private static final double POSITIONING_SPEED = 1.0;
    private static final long BLACKLIST_CLEAR_INTERVAL_MS = 15000;
    
    private static final double THREAT_ANGLE_REPOSITION_THRESHOLD = 2.09;
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 60;

    private final Set<BlockPos> failedCoverPositions = new HashSet<>();
    private final java.util.Map<BlockPos, BlacklistEntry> blacklistReasons = new java.util.HashMap<>();
    private long lastBlacklistClearTime = 0;
    private BlockPos lastFailedCover = null;
    private int nonPeekableTicks = 0;
    private int peekCycleLogTick = 0;

    private CoverFinder.ScoredCover[] cachedTopCovers = new CoverFinder.ScoredCover[0];

    private static boolean debugLoggingEnabled = false;
    
    public enum BlacklistReason {
        PATH_FAILED("PATH FAILED"),
        STUCK_SEEKING("STUCK SEEKING"),
        STUCK_REPOSITIONING("STUCK REPOS");
        
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
    
    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        
        if (!soldier.isAlive()) return false;
        
        if (soldier.hasValidPingMoveTarget()) {
            StevesArmyMod.LOGGER.info("[CoverGoal] canUse=false: hasValidPingMoveTarget");
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
                        StevesArmyMod.LOGGER.info("[CoverGoal] canUse=false: cover abandoned (dist={})", distance);
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
        StevesArmyMod.LOGGER.info("[CoverGoal] canUse={}, state={}, hasThreat={}, suppressed={}, health={}",
            result, state, getThreats().hasActiveThreat(),
            getCoverManager().isSuppressed(),
            String.format("%.2f", soldier.getHealth() / soldier.getMaxHealth()));
        return result;
    }
    
    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        boolean result = getCoverManager().getState() != CoverBehaviorManager.CoverState.NO_COVER;
        StevesArmyMod.LOGGER.info("[CoverGoal] canContinueToUse={}, state={}", result, getCoverManager().getState());
        return result;
    }
    
    @Override
    public void start() {
        stuckTicks = 0;
        reevaluateCounter = 0;
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
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
        
        if (state == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            getCoverManager().clearTargetCover();
        }
        
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
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tick: coverState={}, peekState={}, hasThreat={}, suppression={}",
                soldier.getId(), state, peekCtrl.getState(),
                getThreats().hasActiveThreat(),
                String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));

            peekCycleLogTick++;
            if (peekCycleLogTick >= 10) {
                peekCycleLogTick = 0;
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
                setFlags(EnumSet.noneOf(Flag.class));
                tickInCover();
                break;
            case SUPPRESSED_IN_COVER:
                setFlags(EnumSet.noneOf(Flag.class));
                tickSuppressedInCover();
                break;
            case NO_COVER:
                break;
        }
        
        populateCoverDebugData();
    }
    
    private void tickSeekingCover() {
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        if (targetCover == null) {
            findAndMoveToCover();
            noProgressTicks = 0;
            lastSeekingPosition = null;
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
            getCoverManager().resetPeekState();
            getPositionController().clear();
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
            noProgressTicks = 0;
            lastSeekingPosition = null;
        }
    }
    
    private void tickRepositioning() {
        CoverPoint targetCover = getCoverManager().getTargetCover();
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        
        if (targetCover == null) {
            if (currentCover != null) {
                getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
            } else {
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            }
            return;
        }
        
        if (currentCover != null && targetCover.getPosition().equals(currentCover.getPosition())) {
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
            return;
        }
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            if (currentCover != null) {
                getCoverManager().setLastCover(currentCover);
            }
            getCoverManager().setCurrentCover(targetCover);
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
            return;
        }
        
        if (distance < COVER_VALID_DISTANCE) {
            CoverPositionController moveControl = getPositionController();
            if (moveControl.getLastResult() != CoverPositionController.MovementResult.IN_PROGRESS) {
                navigation.stop();
                moveControl.moveTo(getCoverStandingPosition(targetCover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickRepositioning", "recenter to target cover");
            }
            stuckTicks = 0;
        } else {
            if (navigation.isDone()) {
                stuckTicks++;
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
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tickInCover: dist={}, abandon={}, valid={}, peeking={}, target={}",
                    soldier.getId(), String.format("%.2f", distance),
                    distance > COVER_ABANDON_DISTANCE,
                    distance > COVER_VALID_DISTANCE,
                    peeking,
                    (soldier.getTarget() != null ? soldier.getTarget().getName().getString() : "null"));
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
        }

        // Suppressed → transition state
        if (getCoverManager().isSuppressed()) {
            if (getPeekController().isExposed()) {
                getPeekController().tick(soldier, currentCover, getPositionController());
                // PeekController will handle duck back
            }
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            return;
        }

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
        
        // Let peek controller handle ongoing duck back
        if (getPeekController().isReturning()) {
            getPeekController().tick(soldier, currentCover, getPositionController());
        }

        float sup = getCoverManager().getSuppressionTracker().getSuppressionLevel();
        boolean canPeek = getCoverManager().getSuppressionTracker().canPeek();
        boolean pinned = getCoverManager().isPinned();
        
        if (!pinned && canPeek) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        if (shouldExitCoverForFollow() && !getCoverManager().isSuppressed()) {
            getCoverManager().clearCover();
        }
    }
    
    private boolean shouldSeekCover() {
        ThreatAwareness threats = getThreats();
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            boolean hasValid = getCoverManager().getCurrentCover() != null && isCoverStillValid();
            boolean result = !hasValid;
            StevesArmyMod.LOGGER.info("[CoverGoal] shouldSeekCover={} (HOLD mode, hasValidCover={})", result, hasValid);
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

        // Non-peekable safety net
        if (getCoverManager().isNonPeekableCover()) {
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS * 2) {
                nonPeekableTicks = 0;
                getCoverManager().setNonPeekableCover(false);
                getCoverManager().clearCover();
                return;
            }
        }

        // Threat direction change
        Vec3 currentThreatDir = getThreats().getPrimaryDirection(soldier.position());
        Vec3 entryThreatDir = getCoverManager().getEntryThreatDirection();
        if (currentThreatDir != null && entryThreatDir != null && currentThreatDir.lengthSqr() > 0.01 && entryThreatDir.lengthSqr() > 0.01) {
            double dot = currentThreatDir.dot(entryThreatDir) / (currentThreatDir.length() * entryThreatDir.length());
            double angle = Math.acos(net.minecraft.util.Mth.clamp(dot, -1.0, 1.0));
            if (angle > THREAT_ANGLE_REPOSITION_THRESHOLD) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} threat direction changed {:.1f}°, repositioning",
                        soldier.getId(), Math.toDegrees(angle));
                }
                startRepositioning();
                return;
            }
        }

        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tick: coverState={}, peekState={}, hasThreat={}, suppression={}",
                soldier.getId(), getCoverManager().getState(), soldier.getPeekController().getState(),
                soldier.getThreatAwareness().hasActiveThreat(), String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));
        }

        // Check for better cover
        if (!getCoverManager().isSuppressed()) {
            Optional<CoverPoint> betterCover = findBetterCover();
            if (betterCover.isPresent()) {
                CoverPoint newCover = betterCover.get();

                if (newCover.getPosition().equals(currentCover.getPosition())) return;

                float penalty = getCoverManager().getCoverQualityPenalty();
                float hysteresis = penalty > 0 ? 1.0f : 1.0f + HYSTERESIS_THRESHOLD;
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
        
        if (getCoverManager().isSuppressed()) return false;
        
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
        findAndMoveToCover();
        getCoverManager().resetPeekState();
        getCoverManager().setPeekPosition(null);
        getPositionController().clear();
        if (getCoverManager().getTargetCover() != null) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
    }
    
    private void startRepositioning(CoverPoint newCover) {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        
        if (currentCover != null && newCover.getPosition().equals(currentCover.getPosition())) return;
        
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
        }
    }
    
    private void findAndMoveToCover() {
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
        
        int searchRadius = SEARCH_RADIUS;
        BlockPos searchCenter = soldier.blockPosition();
        
        if (soldier.getSquadMode() == SquadMode.HOLD) {
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
        
        Optional<CoverPoint> bestCover = finder.findBestCover(
            soldier,
            threatDirection,
            threats,
            searchRadius
        );
        
        if (bestCover.isEmpty()) {
            bestCover = finder.findBestCover(
                searchCenter,
                searchRadius,
                threats.isEmpty() ? null : threats.get(0),
                threatDirection
            );
        }
        
        if (bestCover.isPresent()) {
            CoverPoint cover = bestCover.get();
            
            boolean wantsDebug = debugLoggingEnabled || CoverDebugManager.isShowSoldierCover();
            if (wantsDebug) {
                List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, searchRadius, 5, true);
                cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
            }
            
            if (failedCoverPositions.contains(cover.getPosition())) {
                List<CoverPoint> allPoints = finder.findCoverPoints(searchCenter, searchRadius);
                allPoints.removeIf(cp -> failedCoverPositions.contains(cp.getPosition()));
                
                for (CoverPoint cp : allPoints) {
                    if (!threats.isEmpty()) {
                        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
                        evaluator.evaluateWithRaycast(cp, threats.get(0));
                    }
                }
                
                bestCover = allPoints.stream()
                    .filter(cp -> CoverReservationManager.isAvailable(cp.getPosition()))
                    .filter(cp -> cp.getType() != CoverType.NONE)
                    .max(Comparator.comparingDouble(CoverPoint::getQuality));
                
                if (bestCover.isEmpty()) return;
                cover = bestCover.get();
            }
            
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) {
                onCoverReached(cover);
                return;
            }
            
            getCoverManager().clearCoverQualityPenalty();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                getCoverManager().setTargetCover(cover);
                moveToCover(cover);
            }
        }
    }
    
    private Optional<CoverPoint> findBetterCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);

        Vec3 threatDirection = getThreats().getPrimaryDirection(soldier.position());
        List<LivingEntity> threats = getThreatList();
        
        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} finding cover: soldierPos={}, threatDirection=({}, {}, {}), threats={}",
            soldier.getId(),
            soldier.blockPosition(),
            threatDirection != null ? String.format("%.2f", threatDirection.x) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.y) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.z) : "null",
            threats.size());

        CoverPoint currentCover = getCoverManager().getCurrentCover();

        Optional<CoverPoint> result = finder.findBestCover(soldier, threatDirection, threats, SEARCH_RADIUS);

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
        Path path = navigation.createPath(standingPos.x, standingPos.y, standingPos.z, 0);
        
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
            navigation.moveTo(path, 1.2);
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} started navigation to cover {} from pos {} (path nodes={})", 
                    soldier.getId(), wallPos, soldier.blockPosition(), path.getNodeCount());
            }
        } else {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[PathDebug] Soldier {} at {} BLACKLISTED cover {} reason: {}", 
                    soldier.getId(), soldier.blockPosition(), wallPos, failReason);
            }
            blacklistCover(wallPos, BlacklistReason.PATH_FAILED);
        }
    }
    
    private void onCoverReached(CoverPoint cover) {
        CoverPoint previousCover = getCoverManager().getCurrentCover();
        if (previousCover != null) {
            getCoverManager().setLastCover(previousCover);
        }
        
        getCoverManager().setCurrentCover(cover);
        getCoverManager().clearTargetCover();
        
        navigation.stop();
        
        getCoverManager().resetPeekState();
        getCoverManager().setNonPeekableCover(false);
        nonPeekableTicks = 0;
        
        getPositionController().moveTo(getCoverStandingPosition(cover.getPosition()), POSITIONING_TOLERANCE, POSITIONING_SPEED, "onCoverReached", "initial cover positioning");
        
        // Reset peek controller for new cover
        getPeekController().resetForNewCover(cover.getPosition());
        
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
        
        if (getCoverManager().isPinned()) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        soldier.refreshDimensions();
        doCrawlIfHalfCover();
    }
    
    private void blacklistCover(BlockPos pos, BlacklistReason reason) {
        failedCoverPositions.add(pos);
        lastFailedCover = pos;
        blacklistReasons.put(pos, new BlacklistEntry(reason, System.currentTimeMillis()));
        getCoverManager().clearTargetCover();
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} blacklisted cover at {} reason={}",
                soldier.getId(), pos, reason.label);
        }
    }
    
    private void doCrawlIfHalfCover() {
        CoverPoint cover = getCoverManager().getCurrentCover();
        if (cover != null && cover.getType() == CoverType.HALF) {
            com.stevesarmy.combat.GunIntegration.crawl(soldier, true);
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
    
    private BlockPos computePeekPosition(CoverPoint cover, Vec3 threatDirection, LivingEntity target) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return null;
        }

        if (target == null || !target.isAlive()) {
            return null;
        }

        java.util.Set<net.minecraft.core.Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return null;
        }
        
        BlockPos coverPos = cover.getPosition();
        
        BlockPos bestPeekPos = null;
        double bestAngleScore = -1;
        int bestDistance = Integer.MAX_VALUE;
        
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                int distSq = dx * dx + dz * dz;
                if (distSq > 4) continue;
                
                BlockPos candidate = coverPos.offset(dx, 0, dz);
                
                // Check if path from cover to peek position crosses solid blocks
                if (!isPathClear(coverPos, candidate)) {
                    continue;
                }
                
                Vec3 candidateCenter = candidate.getCenter();
                Vec3 candidateEye = new Vec3(candidateCenter.x, soldier.getY() + 1.62, candidateCenter.z);
                
                Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                
                net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
                    candidateEye, targetEye,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, soldier);
                net.minecraft.world.phys.HitResult result = soldier.level().clip(context);
                if (result.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                    Vec3 toTarget = new Vec3(target.getX() - candidate.getX(), 0, target.getZ() - candidate.getZ()).normalize();
                    double alignment = Math.abs(toTarget.dot(threatDirection));
                    double dist = Math.sqrt(distSq);
                    double angleScore = alignment * 2.0 - dist * 0.5;
                    
                    if (angleScore > bestAngleScore || (Math.abs(angleScore - bestAngleScore) < 0.01 && dist < bestDistance)) {
                        bestAngleScore = angleScore;
                        bestPeekPos = candidate;
                        bestDistance = (int) dist;
                    }
                }
            }
        }
        
        return bestPeekPos;
    }
    
    private boolean isPathClear(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        
        if (steps == 0) return true;
        
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + (dx * i) / steps;
            int z = from.getZ() + (dz * i) / steps;
            BlockPos checkPos = new BlockPos(x, from.getY(), z);
            
            net.minecraft.world.level.block.state.BlockState state = soldier.level().getBlockState(checkPos);
            if (!state.isAir() && !state.getCollisionShape(soldier.level(), checkPos).isEmpty()) {
                return false;
            }
        }
        
        return true;
    }
}
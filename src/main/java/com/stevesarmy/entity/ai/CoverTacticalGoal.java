package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.ai.CoverPositionController.MovementIntent;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.AimAccuracyManager;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

public class CoverTacticalGoal extends Goal {
    private final SoldierEntity soldier;
    private final PathNavigation navigation;
    
    private int cooldown = 0;
    private int stuckTicks = 0;
    private int reevaluateCounter = 0;
    
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
    
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 60; // ~3 seconds
    private int nonPeekableTicks = 0;

    private static final int PEEK_NO_LOS_REPOSITION_TICKS = 100; // ~5 seconds of no LOS
    private static final double FULL_COVER_PEEK_SPEED = 0.15;
    private static final double PEEK_POSITION_REACHED_DISTANCE = 0.3;
    private static final double PEEK_RESTART_DISTANCE = 0.6; // Hysteresis: require larger dist to restart slide
    private static final double POSITIONING_TOLERANCE = 0.3;
    private static final double POSITIONING_SPEED = 0.3;
    private static final int PEEK_SEARCH_RADIUS = 2;
    private static final long BLACKLIST_CLEAR_INTERVAL_MS = 15000;
    
    private static long tickCounter = 0;
    private long lastTickId = 0;
    private boolean slideJustFinished = false; // Hysteresis flag
    private static final double THREAT_ANGLE_REPOSITION_THRESHOLD = 2.09; // ~120° in radians
    private static final int EXPOSED_LOS_CHECK_INTERVAL = 10;
    private static final int PEEK_REVALIDATE_INTERVAL = 30;

    private final Set<BlockPos> failedCoverPositions = new HashSet<>();
    private long lastBlacklistClearTime = 0;
    private BlockPos lastFailedCover = null;
    private int exposedTickCounter = 0;
    private int peekRevalidateCounter = 0;

    private CoverFinder.ScoredCover[] cachedTopCovers = new CoverFinder.ScoredCover[0];
    private int peekCycleLogTick = 0;

    private static boolean debugLoggingEnabled = false;
    
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
                        getPositionController().clearIntent();
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
        
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} tick: coverState={}, peekState={}, hasThreat={}, suppression={}",
                soldier.getId(), state, getCoverManager().getPeekState(),
                getThreats().hasActiveThreat(),
                String.format("%.2f", getCoverManager().getSuppressionTracker().getSuppressionLevel()));

            peekCycleLogTick++;
            if (peekCycleLogTick >= 10) {
                peekCycleLogTick = 0;
                CoverPositionController ctrl = (CoverPositionController) soldier.getMoveControl();
                CoverPoint cover = getCoverManager().getCurrentCover();
                double distToCover = cover != null ? soldier.position().distanceTo(cover.getPosition().getCenter()) : -1;
                Vec3 vel = soldier.getDeltaMovement();
                double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                Vec3 ctrlTarget = ctrl.getDebugTargetPos();
                double ctrlDist = ctrlTarget != null ?
                    Math.sqrt(Math.pow(ctrlTarget.x - soldier.getX(), 2) + Math.pow(ctrlTarget.z - soldier.getZ(), 2)) : -1;
                CoverBehaviorManager.PeekState peekState = getCoverManager().getPeekState();
                long peekTime = getCoverManager().getTimeInCurrentPeekState();
                long sinceLastPeek = getCoverManager().getTimeSinceLastPeek();
                StevesArmyMod.LOGGER.info("[PeekCycle] Soldier {} state={} peek={}({}ms,sinceLast={}ms) intent={} ctrlDist={} distToCover={} speed={} nonPeekable={} peekCount={}",
                    soldier.getId(), state, peekState, peekTime, sinceLastPeek,
                    ctrl.getIntent(), String.format("%.2f", ctrlDist), String.format("%.2f", distToCover), String.format("%.4f", speed),
                    getCoverManager().isNonPeekableCover(), getCoverManager().getPeekCountSameCover());
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
    }
    
    private void tickSeekingCover() {
        CoverPoint targetCover = getCoverManager().getTargetCover();
        
        if (targetCover == null) {
            findAndMoveToCover();
            return;
        }
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            onCoverReached(targetCover);
            return;
        }
        
        if (distance < COVER_VALID_DISTANCE) {
            CoverPositionController moveControl = getPositionController();
            if (moveControl.getIntent() == CoverPositionController.MovementIntent.NONE) {
                navigation.stop();
                moveControl.setTarget(getCoverStandingPosition(targetCover.getPosition()), CoverPositionController.MovementIntent.POSITIONING, POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickSeekingCover", "recenter to target cover");
            }
        }
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (targetCover != null) {
                    failedCoverPositions.add(targetCover.getPosition());
                    lastFailedCover = targetCover.getPosition();
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} blacklisted unreachable cover at {}",
                            soldier.getId(), targetCover.getPosition());
                    }
                }
                getCoverManager().clearTargetCover();
                stuckTicks = 0;
                findAndMoveToCover();
            }
        } else {
            stuckTicks = 0;
        }
        
        if (getCoverManager().getTimeSeeking() > MAX_SEEKING_TIME_MS) {
            getCoverManager().resetPeekState();
            getPositionController().clearIntent();
            getCoverManager().clearTargetCover();
            getCoverManager().setState(CoverBehaviorManager.CoverState.NO_COVER);
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
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (targetCover != null) {
                    failedCoverPositions.add(targetCover.getPosition());
                    lastFailedCover = targetCover.getPosition();
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} blacklisted unreachable cover at {}",
                            soldier.getId(), targetCover.getPosition());
                    }
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
    
    private void tickInCover() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover != null) {
            double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
            boolean peeking = isPeeking();
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
                getPositionController().clearIntent();
                return;
            }
            if (distance > COVER_VALID_DISTANCE && !isPeeking() && !getCoverManager().isPeekSlideActive()) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} got pushed from cover ({} > {}), re-seeking",
                        soldier.getId(), String.format("%.1f", distance), COVER_VALID_DISTANCE);
                }
                getCoverManager().clearCover();
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                getPositionController().clearIntent();
                return;
            }
            
            boolean isDuckingBack = getCoverManager().getPeekState() == CoverBehaviorManager.PeekState.DUCKING_BACK;
            boolean isPeekSlideActive = getCoverManager().isPeekSlideActive();
            if (!peeking && !isDuckingBack && !isPeekSlideActive && getPositionController().getIntent() == CoverPositionController.MovementIntent.NONE) {
                navigation.stop();
                getPositionController().setTarget(getCoverStandingPosition(currentCover.getPosition()), CoverPositionController.MovementIntent.POSITIONING, POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickInCover", "recenter to cover");
            }
        }

        if (getCoverManager().isSuppressed()) {
            if (getCoverManager().getPeekState() == CoverBehaviorManager.PeekState.EXPOSED) {
                getCoverManager().setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} suppressed while exposed, ducking back", soldier.getId());
                }
            }
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            return;
        }

        if (shouldExitCoverForFollow()) {
            getCoverManager().resetPeekState();
            getPositionController().clearIntent();
            getCoverManager().clearCover();
            return;
        }

        tickPeekState();

        reevaluateCounter++;
        if (reevaluateCounter >= REEVALUATE_INTERVAL_TICKS) {
            reevaluateCounter = 0;
            evaluateCoverState();
        }
    }

    private void tickPeekState() {
        CoverBehaviorManager manager = getCoverManager();
        CoverBehaviorManager.PeekState peekState = manager.getPeekState();
        long timeInState = manager.getTimeInCurrentPeekState();

        switch (peekState) {
            case HIDING:
                tickHiding();
                break;
            case EXPOSED:
                tickExposed(timeInState);
                break;
            case DUCKING_BACK:
                tickDuckingBack();
                break;
        }
    }

    private void tickHiding() {
        CoverBehaviorManager manager = getCoverManager();
        CoverPoint cover = manager.getCurrentCover();
        
        if (cover == null) return;

        // Non-peekable reposition timer — check BEFORE any early returns
        if (manager.isNonPeekableCover()) {
            nonPeekableTicks++;
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} non-peekable cover for {} ticks, repositioning",
                        soldier.getId(), nonPeekableTicks);
                }
                nonPeekableTicks = 0;
                manager.setNonPeekableCover(false);
                startRepositioning();
                return;
            }
        } else {
            nonPeekableTicks = 0;
        }

        doCrawlIfHalfCover();

        boolean isHalfCover = cover.getType() == CoverType.HALF;
        boolean isFullCover = cover.getType() == CoverType.FULL;

        long timeSinceLastPeek = manager.getTimeSinceLastPeek();
        long cooldown = manager.isSuppressed() ?
            CoverBehaviorManager.getDuckCooldownMs() + CoverBehaviorManager.getSuppressedHideExtraMs() :
            CoverBehaviorManager.getDuckCooldownMs();

        if (timeSinceLastPeek < cooldown) {
            lookTowardThreat();
            if (debugLoggingEnabled) {
                populateCoverDebugData();
            }
            return;
        }

        // HALF cover: stand-in-place peek
        if (isHalfCover) {
            List<LivingEntity> potentials = soldier.getCombatGoal().getPotentialTargets();
            LivingEntity target = evaluateHalfCoverTargets(potentials);
            if (target == null) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} half-cover peek: no target with LOS from standing position",
                        soldier.getId());
                }
                manager.setNonPeekableCover(true);
                lookTowardThreat();
                return;
            }
            soldier.getCombatGoal().setTarget(target);
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} half-cover peek: LOS clear to {}, exposing",
                    soldier.getId(), target.getName().getString());
            }
            getPositionController().clearIntent();
            manager.setPeekState(CoverBehaviorManager.PeekState.EXPOSED);
            soldier.refreshDimensions();
            GunIntegration.crawl(soldier, false);
            return;
        }

        // FULL cover: side-step peek
        if (isFullCover) {
            BlockPos peekPos = manager.getPeekPosition();
            LivingEntity target = soldier.getTarget();

            // Periodically re-evaluate targets and peek position
            peekRevalidateCounter++;
            if (peekPos != null && peekRevalidateCounter >= PEEK_REVALIDATE_INTERVAL) {
                peekRevalidateCounter = 0;
                Vec3 threatDir = getThreats().getPrimaryDirection(soldier.position());
                if (threatDir != null && threatDir.lengthSqr() > 0.001) {
                    List<LivingEntity> potentials = soldier.getCombatGoal().getPotentialTargets();
                    TargetPeekResult result = evaluateFullCoverTargets(potentials, cover, threatDir);
                    if (result != null) {
                        target = result.target;
                        peekPos = result.peekPos;
                        soldier.getCombatGoal().setTarget(target);
                        manager.setPeekPosition(peekPos);
                    }
                }
            }

            if (peekPos == null || target == null || !target.isAlive()) {
                Vec3 threatDir = getThreats().getPrimaryDirection(soldier.position());
                if (threatDir != null && threatDir.lengthSqr() > 0.001) {
                    List<LivingEntity> potentials = soldier.getCombatGoal().getPotentialTargets();
                    TargetPeekResult result = evaluateFullCoverTargets(potentials, cover, threatDir);
                    if (result != null) {
                        target = result.target;
                        peekPos = result.peekPos;
                        soldier.getCombatGoal().setTarget(target);
                        manager.setPeekPosition(peekPos);
                    }
                }
            }

            if (peekPos != null && target != null && target.isAlive()) {
                Vec3 targetPos = peekPos.getCenter();
                Vec3 currentPos = soldier.position();
                double dx = targetPos.x - currentPos.x;
                double dz = targetPos.z - currentPos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                boolean peekSlideActive = manager.isPeekSlideActive();
                MovementIntent currentIntent = getPositionController().getIntent();
                
                long currentTickId = ++tickCounter;
                
                StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} peekSlideActive={} intent={} dist={} slideJustFinished={}",
                    currentTickId, soldier.getId(), peekSlideActive, currentIntent, String.format("%.2f", dist), slideJustFinished);
                lastTickId = currentTickId;
                
                // Check if slide finished (controller intent changed from PEEKING)
                if (peekSlideActive && currentIntent != CoverPositionController.MovementIntent.PEEKING) {
                    StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} slide finished, clearing peekSlideActive", currentTickId, soldier.getId());
                    manager.setPeekSlideActive(false);
                    peekSlideActive = false;
                    slideJustFinished = true; // Set hysteresis flag
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} peek slide finished, checking LOS", soldier.getId());
                    }
                }

                if (!peekSlideActive && dist < PEEK_POSITION_REACHED_DISTANCE) {
                    // Re-check LOS from peek position before exposing
                    Vec3 peekEye = new Vec3(targetPos.x, currentPos.y + 1.62, targetPos.z);
                    Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                    if (hasLineOfSight(peekEye, targetEye)) {
                        soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
                        slideJustFinished = false; // Clear flag on successful expose
                        if (debugLoggingEnabled) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} reached full-cover peek position, exposing", soldier.getId());
                        }
                        StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} expose at peekPos dist={}", soldier.getId(), String.format("%.2f", dist));
                        manager.setPeekSlideActive(false);
                        manager.setPeekState(CoverBehaviorManager.PeekState.EXPOSED);
                        soldier.refreshDimensions();
                    } else {
                        if (debugLoggingEnabled) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} at peek position but LOS lost, staying hidden", soldier.getId());
                        }
                        slideJustFinished = false; // Clear flag
                        manager.setPeekSlideActive(false);
                        manager.setPeekState(CoverBehaviorManager.PeekState.HIDING);
                        manager.setLastPeekEndTime(System.currentTimeMillis());
                    }
                } else if (!peekSlideActive && dist >= PEEK_RESTART_DISTANCE && !slideJustFinished) {
                    // Only restart slide if dist is significantly larger (hysteresis) and slide didn't just finish
                    StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} STARTING peek slide (dist {} >= {})", lastTickId, soldier.getId(), String.format("%.2f", dist), PEEK_RESTART_DISTANCE);
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} starting full-cover peek slide to {}", soldier.getId(), peekPos);
                    }
                    manager.setPeekSlideActive(true);
                    StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} set peekSlideActive=true", lastTickId, soldier.getId());
                    getPositionController().startPeekAt(targetPos);
                    StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} called startPeekAt, intent now={}", lastTickId, soldier.getId(), getPositionController().getIntent());
                } else if (slideJustFinished && dist >= PEEK_POSITION_REACHED_DISTANCE && dist < PEEK_RESTART_DISTANCE) {
                    // Slide just finished but soldier drifted slightly past threshold - wait for stabilization
                    StevesArmyMod.LOGGER.info("[PeekTrace] tickId={} soldier={} waiting for stabilization (slideJustFinished=true, dist={})", lastTickId, soldier.getId(), String.format("%.2f", dist));
                } else if (!peekSlideActive && dist >= PEEK_POSITION_REACHED_DISTANCE && dist < PEEK_RESTART_DISTANCE) {
                    // In the hysteresis zone but not from a just-finished slide - this means we're drifting
                    // Clear the flag after a bit of drift to allow restart if needed
                    slideJustFinished = false;
                }
                // If peekSlideActive, controller handles the slide — wait for it to finish
            } else {
                manager.setNonPeekableCover(true);
            }
        }
    }

    private void tickExposed(long timeInState) {
        CoverBehaviorManager manager = getCoverManager();

        if (timeInState > CoverBehaviorManager.getExposureTimeMs()) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} exposure time exceeded, ducking back", soldier.getId());
            }
            manager.setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
            soldier.refreshDimensions();
            doCrawlIfHalfCover();
            return;
        }

        LivingEntity target = soldier.getTarget();
        CoverPoint cover = manager.getCurrentCover();

        // If target is dead, try to re-evaluate
        if (target == null || !target.isAlive()) {
            if (timeInState < CoverBehaviorManager.getMinExposureTimeMs()) return;
            if (trySwitchTargetWhileExposed(cover)) return;
            manager.setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
            soldier.refreshDimensions();
            doCrawlIfHalfCover();
            return;
        }

        // Periodically check LOS and re-evaluate targets
        exposedTickCounter++;
        if (exposedTickCounter >= EXPOSED_LOS_CHECK_INTERVAL) {
            exposedTickCounter = 0;
            Vec3 eyePos = soldier.getEyePosition();
            Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
            if (!hasLineOfSight(eyePos, targetEye)) {
                if (timeInState > 200) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} lost LOS while exposed, trying to switch targets", soldier.getId());
                    }
                    // Try to find a different target with LOS before ducking back
                    if (!trySwitchTargetWhileExposed(cover)) {
                        manager.setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
                        soldier.refreshDimensions();
                        doCrawlIfHalfCover();
                    }
                }
            }
        }

        faceTarget();
    }

    private boolean trySwitchTargetWhileExposed(CoverPoint cover) {
        List<LivingEntity> potentials = soldier.getCombatGoal().getPotentialTargets();
        boolean isHalfCover = cover != null && cover.getType() == CoverType.HALF;

        if (isHalfCover) {
            LivingEntity newTarget = evaluateHalfCoverTargets(potentials);
            if (newTarget != null) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} switched target while exposed to {}",
                        soldier.getId(), newTarget.getName().getString());
                }
                soldier.getCombatGoal().setTarget(newTarget);
                return true;
            }
        } else {
            Vec3 threatDir = getThreats().getPrimaryDirection(soldier.position());
            if (threatDir != null && threatDir.lengthSqr() > 0.001) {
                TargetPeekResult result = evaluateFullCoverTargets(potentials, cover, threatDir);
                if (result != null) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} switched target while exposed to {}",
                            soldier.getId(), result.target.getName().getString());
                    }
                    soldier.getCombatGoal().setTarget(result.target);
                    return true;
                }
            }
        }
        return false;
    }

    private void tickDuckingBack() {
        CoverBehaviorManager manager = getCoverManager();
        CoverPoint cover = manager.getCurrentCover();

        if (timeToReturnToCover(cover)) {
            manager.setLastPeekEndTime(System.currentTimeMillis());
            manager.resetPeekState();
            manager.recordPeekCycle();
            if (debugLoggingEnabled) {
                populateCoverDebugData();
            }
            soldier.refreshDimensions();
            doCrawlIfHalfCover();
if (cover != null) {
                getPositionController().setTarget(getCoverStandingPosition(cover.getPosition()), CoverPositionController.MovementIntent.POSITIONING, POSITIONING_TOLERANCE, POSITIONING_SPEED, "tickDuckingBack", "post-return recenter");
            }
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} returned to cover, back to HIDING", soldier.getId());
            }
        }
    }

    private void doCrawlIfHalfCover() {
        CoverPoint cover = getCoverManager().getCurrentCover();
        if (cover != null && cover.getType() == CoverType.HALF) {
            GunIntegration.crawl(soldier, true);
        }
    }

    private boolean timeToReturnToCover(CoverPoint cover) {
        if (cover == null) return true;

        CoverType type = cover.getType();
        if (type == CoverType.HALF) {
            return getCoverManager().getTimeInCurrentPeekState() > 200;
        }

        // Full cover: return to cover center (same target as tickInCover line 407)
        Vec3 targetPos = getCoverStandingPosition(cover.getPosition());
        Vec3 currentPos = soldier.position();
        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        double fullDist = currentPos.distanceTo(targetPos);
        double dy = targetPos.y - currentPos.y;
        CoverPositionController controller = getPositionController();
        MovementIntent currentIntent = controller.getIntent();

        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[DuckReturn] pos={} target={} hDist={} fullDist={} dy={} intent={}",
                String.format("(%.2f,%.2f,%.2f)", currentPos.x, currentPos.y, currentPos.z),
                String.format("(%.2f,%.2f,%.2f)", targetPos.x, targetPos.y, targetPos.z),
                String.format("%.2f", hDist), String.format("%.2f", fullDist),
                String.format("%.2f", dy), currentIntent);
        }

        // Already at cover position (horizontal check only)
        if (hDist <= PEEK_POSITION_REACHED_DISTANCE) {
            soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
            return true;
        }

        // Start or continue positioning back to cover — always re-set so we're the sole authority
        controller.setTarget(targetPos, CoverPositionController.MovementIntent.POSITIONING, POSITIONING_TOLERANCE, POSITIONING_SPEED, "timeToReturnToCover", "duck back to cover");
        faceTarget();
        return false;
    }
    
    private boolean isPeeking() {
        return getCoverManager().getPeekState() == CoverBehaviorManager.PeekState.EXPOSED ||
               getPositionController().getIntent() == CoverPositionController.MovementIntent.PEEKING;
    }
    
    private void tickSuppressedInCover() {
        if (getCoverManager().getPeekState() == CoverBehaviorManager.PeekState.DUCKING_BACK) {
            tickDuckingBack();
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
        
        if (isPeeking()) return true;
        
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

        // Skip all checks when peeking — soldier is legitimately away from cover
        if (isPeeking()) return;

        if (!isCoverStillValid()) {
            startRepositioning();
            return;
        }

        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) return;

        // Non-peekable safety net — primary timer is in tickHiding()
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
            double angle = Math.acos(Mth.clamp(dot, -1.0, 1.0));
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
            populateCoverDebugData();
        }

        // Check for better cover (skip while suppressed to avoid leaving cover under fire)
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
        getPositionController().clearIntent();
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
        getPositionController().clearIntent();
        
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
            
            CoverPoint lastCover = getCoverManager().getLastCover();
            if (lastCover != null && cover.getPosition().equals(lastCover.getPosition())) {
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

        CoverPoint currentCover = getCoverManager().getCurrentCover();

        Optional<CoverPoint> result = finder.findBestCover(soldier, threatDirection, threats, SEARCH_RADIUS);

        // If the best cover is the current one, try the second-best instead
        if (result.isPresent() && currentCover != null && result.get().getPosition().equals(currentCover.getPosition())) {
            List<CoverFinder.ScoredCover> top2 = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 2);
            if (top2.size() >= 2) {
                result = Optional.of(top2.get(1).cover);
            } else {
                result = Optional.empty();
            }
        }

        if (debugLoggingEnabled && currentCover != null) {
            List<CoverFinder.ScoredCover> top = finder.findTopCovers(soldier, threatDirection, threats, SEARCH_RADIUS, 3);
            cachedTopCovers = top.toArray(new CoverFinder.ScoredCover[0]);
        }

        return result;
    }

    private void populateCoverDebugData() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        CoverDebugManager.setSoldierTopCovers(soldier.getId(),
            new CoverDebugManager.TopCoversDebugData(
                cachedTopCovers,
                currentCover != null ? currentCover.getQuality() : 0,
                getCoverManager().getCoverQualityPenalty(),
                getCoverManager().getPeekCountSameCover()
            ));
    }
    
    private CoverPositionController getPositionController() {
        return (CoverPositionController) soldier.getMoveControl();
    }
    
    private Vec3 getCoverStandingPosition(BlockPos coverPos) {
        return new Vec3(coverPos.getX() + 0.5, coverPos.getY(), coverPos.getZ() + 0.5);
    }
    
    
    
    private void moveToCover(CoverPoint cover) {
        BlockPos pos = cover.getPosition();
        
        if (StevesArmyMod.teleportOnlyMode) {
            soldier.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, soldier.getYRot(), soldier.getXRot());
            onCoverReached(cover);
            return;
        }
        
        SoldierGroundNavigation groundNav = (SoldierGroundNavigation) navigation;
        Path path = groundNav.createPathToBlock(pos, 0);
        if (path != null && path.canReach()) {
            groundNav.moveTo(path, 1.2);
        } else {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} no valid path to cover at {}, abandoning", soldier.getId(), pos);
            }
            failedCoverPositions.add(pos);
            lastFailedCover = pos;
            getCoverManager().clearTargetCover();
        }
    }
    
    private void onCoverReached(CoverPoint cover) {
        StevesArmyMod.LOGGER.info("[PeekTrace] onCoverReached soldier={} START", soldier.getId());
        CoverPoint previousCover = getCoverManager().getCurrentCover();
        if (previousCover != null) {
            getCoverManager().setLastCover(previousCover);
        }
        
        getCoverManager().setCurrentCover(cover);
        getCoverManager().clearTargetCover();
        
        navigation.stop();
        
        StevesArmyMod.LOGGER.info("[PeekTrace] onCoverReached soldier={} calling resetPeekState (peekSlideActive will be false)", soldier.getId());
        getCoverManager().resetPeekState();
        getCoverManager().setNonPeekableCover(false);
        nonPeekableTicks = 0;
        slideJustFinished = false; // Clear hysteresis flag on new cover
        
        StevesArmyMod.LOGGER.info("[PeekTrace] onCoverReached soldier={} calling setTarget POSITIONING", soldier.getId());
getPositionController().setTarget(getCoverStandingPosition(cover.getPosition()), CoverPositionController.MovementIntent.POSITIONING, POSITIONING_TOLERANCE, POSITIONING_SPEED, "onCoverReached", "initial cover positioning");
        
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
        
        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return null;
        }
        
        BlockPos coverPos = cover.getPosition();
        
        BlockPos bestPeekPos = null;
        double bestAngleScore = -1;
        int bestDistance = Integer.MAX_VALUE;
        
        List<BlockPos> debugPositions = new ArrayList<>();
        List<Integer> debugReasons = new ArrayList<>();
        List<Double> debugScores = new ArrayList<>();
        List<Boolean> debugLosResults = new ArrayList<>();
        
        for (int dx = -PEEK_SEARCH_RADIUS; dx <= PEEK_SEARCH_RADIUS; dx++) {
            for (int dz = -PEEK_SEARCH_RADIUS; dz <= PEEK_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                int distSq = dx * dx + dz * dz;
                if (distSq > PEEK_SEARCH_RADIUS * PEEK_SEARCH_RADIUS) continue;
                
                BlockPos peekPos = coverPos.offset(dx, 0, dz);
                
                Direction moveDir = getDirectionFromOffset(dx, dz);
                if (moveDir != null && protectedDirs.contains(moveDir)) {
                    debugPositions.add(peekPos);
                    debugReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_PROTECTED_DIR);
                    debugScores.add(0.0);
                    debugLosResults.add(false);
                    continue;
                }
                
                if (!isValidPeekPosition(peekPos)) {
                    debugPositions.add(peekPos);
                    debugReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_INVALID_POS);
                    debugScores.add(0.0);
                    debugLosResults.add(false);
                    continue;
                }
                
                boolean hasLos = true;
                if (target != null && target.isAlive()) {
                    Vec3 peekCenter = peekPos.getCenter();
                    Vec3 eyePos = new Vec3(peekCenter.x, peekPos.getY() + 1.62, peekCenter.z);
                    Vec3 targetEyePos = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                    if (!hasLineOfSight(eyePos, targetEyePos)) {
                        hasLos = false;
                        debugPositions.add(peekPos);
                        debugReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_NO_LOS);
                        debugScores.add(0.0);
                        debugLosResults.add(false);
                        continue;
                    }
                }
                
                Vec3 peekCenter = peekPos.getCenter();
                Vec3 fromPeekToCover = new Vec3(
                    coverPos.getX() + 0.5 - peekCenter.x,
                    0,
                    coverPos.getZ() + 0.5 - peekCenter.z
                ).normalize();
                
                double dot = threatDirection.normalize().dot(fromPeekToCover);
                dot = Math.max(-1.0, Math.min(1.0, dot));
                double angle = Math.toDegrees(Math.acos(dot));
                
                if (angle >= 45 && angle <= 135) {
                    double score = 1.0 - Math.abs(angle - 90) / 90;
                    if (score > bestAngleScore || (score == bestAngleScore && distSq < bestDistance)) {
                        bestAngleScore = score;
                        bestDistance = distSq;
                        bestPeekPos = peekPos;
                    }
                    debugPositions.add(peekPos);
                    debugReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_ACCEPTED);
                    debugScores.add(score);
                    debugLosResults.add(true);
                } else {
                    debugPositions.add(peekPos);
                    debugReasons.add(CoverDebugManager.PeekCandidateDebugData.REASON_BAD_ANGLE);
                    debugScores.add(0.0);
                    debugLosResults.add(true);
                }
            }
        }
        
        Vec3 targetEyePos = target != null && target.isAlive() ?
            new Vec3(target.getX(), target.getEyeY(), target.getZ()) : Vec3.ZERO;
        
        CoverDebugManager.setSoldierPeekCandidates(soldier.getId(),
            new CoverDebugManager.PeekCandidateDebugData(coverPos, debugPositions, debugReasons, debugScores, debugLosResults, bestPeekPos, targetEyePos));
        
        return bestPeekPos;
    }
    
    private Direction getDirectionFromOffset(int dx, int dz) {
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    
    private boolean isValidPeekPosition(BlockPos pos) {
        Level level = soldier.level();
        if (!level.isLoaded(pos)) return false;
        
        BlockState groundState = level.getBlockState(pos.below());
        if (!groundState.isSolid()) return false;
        
        BlockState standingState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        
        return (standingState.isAir() || standingState.getCollisionShape(level, pos).isEmpty()) &&
               (headState.isAir() || headState.getCollisionShape(level, pos.above()).isEmpty());
    }
    
    private Direction getDirectionFromVector(Vec3 vec) {
        if (vec == null) return Direction.NORTH;
        
        double absX = Math.abs(vec.x);
        double absZ = Math.abs(vec.z);
        
        if (absX > absZ) {
            return vec.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return vec.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    
    private boolean hasLineOfSight(Vec3 from, Vec3 to) {
        Level level = soldier.level();
        ClipContext context = new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            soldier
        );
        HitResult result = level.clip(context);
        return result.getType() == HitResult.Type.MISS;
}

    private record TargetPeekResult(LivingEntity target, BlockPos peekPos) {}

    @Nullable
    private LivingEntity evaluateHalfCoverTargets(List<LivingEntity> potentials) {
        if (potentials == null || potentials.isEmpty()) return null;

        Vec3 standingEye = new Vec3(soldier.getX(), soldier.getY() + 1.62, soldier.getZ());
        LivingEntity best = null;
        double bestScore = -1;

        for (LivingEntity potential : potentials) {
            if (!potential.isAlive()) continue;
            Vec3 targetEye = new Vec3(potential.getX(), potential.getEyeY(), potential.getZ());
            if (!hasLineOfSight(standingEye, targetEye)) continue;

            double distance = soldier.distanceTo(potential);
            float hitProb = AimAccuracyManager.calculateHitProbability(soldier, potential);
            double score = hitProb * 5.0 + (1.0 / Math.max(1.0, distance)) * 10.0;
            if (score > bestScore) {
                bestScore = score;
                best = potential;
            }
        }

        return best;
    }

    @Nullable
    private TargetPeekResult evaluateFullCoverTargets(List<LivingEntity> potentials, CoverPoint cover, Vec3 threatDirection) {
        if (potentials == null || potentials.isEmpty() || threatDirection == null) return null;

        Set<Direction> protectedDirs = cover.getProtectedDirections();
        BlockPos coverPos = cover.getPosition();
        BlockPos coverCenter = coverPos.offset(0, 0, 0);

        // First, find the best target
        LivingEntity bestTarget = null;
        BlockPos bestPeekPos = null;
        double bestScore = -1;

        for (LivingEntity potential : potentials) {
            if (!potential.isAlive()) continue;

            // For each target, find the best peek position
            BlockPos candidatePeekPos = computePeekPositionForTarget(cover, threatDirection, potential);
            if (candidatePeekPos == null) continue;

            // Check LOS from that position
            Vec3 peekCenter = candidatePeekPos.getCenter();
            Vec3 eyePos = new Vec3(peekCenter.x, candidatePeekPos.getY() + 1.62, peekCenter.z);
            Vec3 targetEye = new Vec3(potential.getX(), potential.getEyeY(), potential.getZ());
            if (!hasLineOfSight(eyePos, targetEye)) continue;

            double distance = soldier.distanceTo(potential);
            float hitProb = AimAccuracyManager.calculateHitProbability(soldier, potential);
            double score = hitProb * 5.0 + (1.0 / Math.max(1.0, distance)) * 10.0;
            if (score > bestScore) {
                bestScore = score;
                bestTarget = potential;
                bestPeekPos = candidatePeekPos;
            }
        }

        if (bestTarget == null) return null;
        return new TargetPeekResult(bestTarget, bestPeekPos);
    }

    private BlockPos computePeekPositionForTarget(CoverPoint cover, Vec3 threatDirection, LivingEntity target) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) return null;

        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) return null;

        BlockPos coverPos = cover.getPosition();

        BlockPos bestPeekPos = null;
        double bestAngleScore = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int dx = -PEEK_SEARCH_RADIUS; dx <= PEEK_SEARCH_RADIUS; dx++) {
            for (int dz = -PEEK_SEARCH_RADIUS; dz <= PEEK_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;

                int distSq = dx * dx + dz * dz;
                if (distSq > PEEK_SEARCH_RADIUS * PEEK_SEARCH_RADIUS) continue;

                BlockPos peekPos = coverPos.offset(dx, 0, dz);

                Direction moveDir = getDirectionFromOffset(dx, dz);
                if (moveDir != null && protectedDirs.contains(moveDir)) continue;

                if (!isValidPeekPosition(peekPos)) continue;

                Vec3 peekCenter = peekPos.getCenter();
                Vec3 eyePos = new Vec3(peekCenter.x, peekPos.getY() + 1.62, peekCenter.z);
                Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                if (!hasLineOfSight(eyePos, targetEye)) continue;

                Vec3 fromPeekToCover = new Vec3(
                    coverPos.getX() + 0.5 - peekCenter.x,
                    0,
                    coverPos.getZ() + 0.5 - peekCenter.z
                ).normalize();

                double dot = threatDirection.normalize().dot(fromPeekToCover);
                dot = Math.max(-1.0, Math.min(1.0, dot));
                double angle = Math.toDegrees(Math.acos(dot));

                if (angle >= 45 && angle <= 135) {
                    double score = 1.0 - Math.abs(angle - 90) / 90;
                    if (score > bestAngleScore || (score == bestAngleScore && distSq < bestDistance)) {
                        bestAngleScore = score;
                        bestDistance = distSq;
                        bestPeekPos = peekPos;
                    }
                }
            }
        }

        return bestPeekPos;
    }

    private void lookTowardThreat() {
        LivingEntity target = soldier.getTarget();
        if (target != null && target.isAlive()) {
            soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
            return;
        }
        BlockPos threatPos = getThreats().getPrimaryThreatPosition();
        if (threatPos != null) {
            soldier.getLookControl().setLookAt(
                threatPos.getX() + 0.5,
                soldier.getEyeY(),
                threatPos.getZ() + 0.5,
                30.0F, 30.0F
            );
        }
    }

    private void faceTarget() {
        LivingEntity target = soldier.getTarget();
        if (target != null && target.isAlive()) {
            double dx = target.getX() - soldier.getX();
            double dz = target.getZ() - soldier.getZ();
            float targetYaw = (float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            soldier.setYRot(targetYaw);
            soldier.yBodyRot = targetYaw;
            soldier.yHeadRot = targetYaw;
        }
    }
}
package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
    private static final double LOCK_DISTANCE = 2.5D;
    
    private static final int SEARCH_RADIUS = 12;
    private static final long MIN_COVER_DWELL_TIME_MS = 4000;
    private static final long MIN_SUPPRESSED_DWELL_TIME_MS = 6000;
    private static final float HYSTERESIS_THRESHOLD = 0.35f;
    private static final long MIN_PEEK_INTERVAL_MS = 2000;
    private static final long MAX_SEEKING_TIME_MS = 10000;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    
    private static final double FOLLOW_COVER_SEARCH_RADIUS = 15.0D;
    private static final double FOLLOW_REGROUP_DISTANCE = 10.0D;
    
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 160; // ~8 seconds
    private int nonPeekableTicks = 0;

    private static final int PEEK_NO_LOS_REPOSITION_TICKS = 100; // ~5 seconds of no LOS
    private static final double FULL_COVER_PEEK_SPEED = 0.15;
    private static final double PEEK_POSITION_REACHED_DISTANCE = 0.3;
    private static final int PEEK_SEARCH_RADIUS = 2;
    private static final long BLACKLIST_CLEAR_INTERVAL_MS = 15000;
    private static final int EXPOSED_LOS_CHECK_INTERVAL = 10;
    private static final int PEEK_REVALIDATE_INTERVAL = 30;

    private final Set<BlockPos> failedCoverPositions = new HashSet<>();
    private long lastBlacklistClearTime = 0;
    private BlockPos lastFailedCover = null;
    private int exposedTickCounter = 0;
    private int peekRevalidateCounter = 0;

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
        
        if (soldier.hasValidPingMoveTarget()) return false;
        
        getCoverManager().tickSuppression(getCoverManager().isInCover());
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        switch (state) {
            case NO_COVER: {
                return shouldSeekCover();
            }
            case SEEKING_COVER:
                return true;
            case IN_COVER:
            case SUPPRESSED_IN_COVER: {
                CoverPoint cover = getCoverManager().getCurrentCover();
                if (cover != null) {
                    double distance = soldier.position().distanceTo(cover.getPosition().getCenter());
                    if (distance > COVER_ABANDON_DISTANCE) {
                        getCoverManager().clearCover();
                        cooldown = COOLDOWN_TICKS;
                        return false;
                    }
                }
                return true;
            }
            case REPOSITIONING:
                return true;
            default:
                return false;
        }
    }
    
    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        return getCoverManager().getState() != CoverBehaviorManager.CoverState.NO_COVER;
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
            ExactCoverMoveControl moveControl = getExactMoveControl();
            if (!moveControl.isLocked()) {
                moveControl.lockToBlock(targetCover.getPosition(), getProtectedDirection(targetCover));
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
                getExactMoveControl().clearLock();
                return;
            }
            if (distance > COVER_VALID_DISTANCE && !isPeeking()) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} got pushed from cover ({} > {}), re-seeking",
                        soldier.getId(), String.format("%.1f", distance), COVER_VALID_DISTANCE);
                }
                getCoverManager().clearCover();
                getCoverManager().setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
                setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
                getExactMoveControl().clearLock();
                return;
            }
            
            if (!peeking && !getExactMoveControl().isLocked()) {
                getExactMoveControl().lockToBlock(currentCover.getPosition(), getProtectedDirection(currentCover));
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

        doCrawlIfHalfCover();

        boolean isHalfCover = cover.getType() == CoverType.HALF;
        boolean isFullCover = cover.getType() == CoverType.FULL;

        long timeSinceLastPeek = manager.getTimeSinceLastPeek();
        long cooldown = manager.isSuppressed() ?
            CoverBehaviorManager.getDuckCooldownMs() + CoverBehaviorManager.getSuppressedHideExtraMs() :
            CoverBehaviorManager.getDuckCooldownMs();

        if (timeSinceLastPeek < cooldown) {
            lookTowardThreat();
            return;
        }

        LivingEntity target = soldier.getTarget();
        if (target == null || !target.isAlive()) {
            lookTowardThreat();
            return;
        }

        // HALF cover: stand-in-place peek
        if (isHalfCover) {
            Vec3 standingEye = new Vec3(soldier.getX(), soldier.getY() + 1.62, soldier.getZ());
            Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
            if (hasLineOfSight(standingEye, targetEye)) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} half-cover peek: LOS clear, exposing", soldier.getId());
                }
                getExactMoveControl().clearLock();
                manager.setPeekState(CoverBehaviorManager.PeekState.EXPOSED);
                soldier.refreshDimensions();
                GunIntegration.crawl(soldier, false);
            } else {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} half-cover peek: no LOS from soldier position {}",
                        soldier.getId(), String.format("%.2f,%.2f,%.2f", standingEye.x, standingEye.y, standingEye.z));
                }
                manager.setNonPeekableCover(true);
            }
            return;
        }

        // FULL cover: side-step peek
        if (isFullCover) {
            BlockPos peekPos = manager.getPeekPosition();
            
            // Periodically re-validate and re-compute peek position
            peekRevalidateCounter++;
            if (peekRevalidateCounter >= PEEK_REVALIDATE_INTERVAL) {
                peekRevalidateCounter = 0;
                Vec3 threatDir = getThreats().getPrimaryDirection(soldier.position());
                if (threatDir != null && threatDir.lengthSqr() > 0.001) {
                    BlockPos recomputed = computePeekPosition(cover, threatDir, target);
                    if (recomputed != null) {
                        peekPos = recomputed;
                        manager.setPeekPosition(peekPos);
                    }
                }
            }
            
            if (peekPos == null) {
                // Try to compute peek position
                Vec3 threatDir = getThreats().getPrimaryDirection(soldier.position());
                if (threatDir != null && threatDir.lengthSqr() > 0.001) {
                    peekPos = computePeekPosition(cover, threatDir, target);
                    manager.setPeekPosition(peekPos);
                }
            }

            if (peekPos != null) {
                Vec3 targetPos = peekPos.getCenter();
                Vec3 currentPos = soldier.position();
                double dx = targetPos.x - currentPos.x;
                double dz = targetPos.z - currentPos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist < PEEK_POSITION_REACHED_DISTANCE) {
                    // Re-check LOS from peek position before exposing
                    Vec3 peekEye = new Vec3(targetPos.x, currentPos.y + 1.62, targetPos.z);
                    Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                    if (hasLineOfSight(peekEye, targetEye)) {
                        soldier.setPos(targetPos.x, currentPos.y, targetPos.z);
                        soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
                        if (debugLoggingEnabled) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} reached full-cover peek position, exposing", soldier.getId());
                        }
                        getExactMoveControl().clearLock();
                        manager.setPeekState(CoverBehaviorManager.PeekState.EXPOSED);
                        soldier.refreshDimensions();
                    } else {
                        if (debugLoggingEnabled) {
                            StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} at peek position but LOS lost, staying hidden", soldier.getId());
                        }
                        manager.setPeekState(CoverBehaviorManager.PeekState.HIDING);
                        manager.setLastPeekEndTime(System.currentTimeMillis());
                    }
                } else {
                    getExactMoveControl().clearLock();
                    double speed = FULL_COVER_PEEK_SPEED;
                    soldier.setDeltaMovement((dx / dist) * speed, soldier.getDeltaMovement().y, (dz / dist) * speed);
                    soldier.setYya(0);
                }
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
        if (target == null || !target.isAlive()) {
            if (timeInState > CoverBehaviorManager.getMinExposureTimeMs()) {
                manager.setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
                soldier.refreshDimensions();
                doCrawlIfHalfCover();
            }
            return;
        }

        exposedTickCounter++;
        if (exposedTickCounter >= EXPOSED_LOS_CHECK_INTERVAL) {
            exposedTickCounter = 0;
            Vec3 eyePos = soldier.getEyePosition();
            Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
            if (!hasLineOfSight(eyePos, targetEye)) {
                if (timeInState > 200) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} lost LOS while exposed, ducking back early", soldier.getId());
                    }
                    manager.setPeekState(CoverBehaviorManager.PeekState.DUCKING_BACK);
                    soldier.refreshDimensions();
                    doCrawlIfHalfCover();
                }
            }
        }
    }

    private void tickDuckingBack() {
        CoverBehaviorManager manager = getCoverManager();
        CoverPoint cover = manager.getCurrentCover();

        if (timeToReturnToCover(cover)) {
            manager.setLastPeekEndTime(System.currentTimeMillis());
            manager.resetPeekState();
            soldier.refreshDimensions();
            doCrawlIfHalfCover();
            if (cover != null) {
                getExactMoveControl().lockToBlock(cover.getPosition(), getProtectedDirection(cover));
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

        BlockPos coverPos = cover.getPosition();
        Vec3 targetPos = coverPos.getCenter();
        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs != null && !protectedDirs.isEmpty()) {
            Direction protectDir = protectedDirs.iterator().next();
            targetPos = targetPos.add(-protectDir.getStepX() * 0.5, 0, -protectDir.getStepZ() * 0.5);
        }

        Vec3 currentPos = soldier.position();
        double dx = targetPos.x - currentPos.x;
        double dz = targetPos.z - currentPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < PEEK_POSITION_REACHED_DISTANCE) {
            soldier.setPos(targetPos.x, currentPos.y, targetPos.z);
            soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
            return true;
        } else {
            double speed = FULL_COVER_PEEK_SPEED;
            soldier.setDeltaMovement((dx / dist) * speed, soldier.getDeltaMovement().y, (dz / dist) * speed);
            soldier.setYya(0);
            return false;
        }
    }
    
    private boolean isPeeking() {
        return getCoverManager().getPeekState() == CoverBehaviorManager.PeekState.EXPOSED;
    }
    
    private void tickSuppressedInCover() {
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
            if (hasValid) return false;
            return true;
        }
        
        if (getCoverManager().isSuppressed() || soldier.getHealth() / soldier.getMaxHealth() < LOW_HEALTH_THRESHOLD) {
            return true;
        }
        
        return threats.hasActiveThreat();
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
        
        if (!isCoverStillValid()) {
            startRepositioning();
            return;
        }
        
        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) return;
        
        if (getCoverManager().isNonPeekableCover()) {
            nonPeekableTicks++;
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverGoal] Soldier {} non-peekable cover for {} ticks, repositioning",
                        soldier.getId(), nonPeekableTicks);
                }
                nonPeekableTicks = 0;
                getCoverManager().setNonPeekableCover(false);
                getCoverManager().clearCover();
                return;
            }
        } else {
            nonPeekableTicks = 0;
        }
        
        Optional<CoverPoint> betterCover = findBetterCover();
        if (betterCover.isPresent()) {
            CoverPoint newCover = betterCover.get();
            
            if (newCover.getPosition().equals(currentCover.getPosition())) return;
            
            float currentScore = currentCover.getQuality() * (1.0f - getCoverManager().getCoverQualityPenalty());
            float newScore = newCover.getQuality();
            
            if (newScore > currentScore * (1.0f + HYSTERESIS_THRESHOLD)) {
                startRepositioning(newCover);
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
        
        return finder.findBestCover(soldier, threatDirection, threats, SEARCH_RADIUS);
    }
    
    private ExactCoverMoveControl getExactMoveControl() {
        return (ExactCoverMoveControl) soldier.getMoveControl();
    }
    
    @Nullable
    private Direction getProtectedDirection(CoverPoint cover) {
        Set<Direction> dirs = cover.getProtectedDirections();
        if (dirs != null && !dirs.isEmpty()) {
            return dirs.iterator().next();
        }
        return null;
    }
    
    private void moveToCover(CoverPoint cover) {
        BlockPos pos = cover.getPosition();
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
        
        getExactMoveControl().lockToBlock(cover.getPosition(), getProtectedDirection(cover));
        
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
}
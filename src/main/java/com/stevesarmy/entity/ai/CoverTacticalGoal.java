package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class CoverTacticalGoal extends Goal {
    private final SoldierEntity soldier;
    private final PathNavigation navigation;
    private final CoverBehaviorManager coverManager;
    
    private int cooldown = 0;
    private int stuckTicks = 0;
    private int reevaluateCounter = 0;
    
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_STUCK_TICKS = 60;
    private static final int REEVALUATE_INTERVAL_TICKS = 60;
    
    private static final double COVER_REACHED_DISTANCE = 1.5D;
    private static final double COVER_VALID_DISTANCE = 5.0D;
    private static final double COMBAT_COVER_VALID_DISTANCE = 6.0D;
    private static final double COVER_ABANDON_DISTANCE = 8.0D;
    
    private static final int SEARCH_RADIUS = 12;
    private static final long MIN_COVER_DWELL_TIME_MS = 4000;
    private static final long MIN_SUPPRESSED_DWELL_TIME_MS = 6000;
    private static final float HYSTERESIS_THRESHOLD = 0.35f;
    private static final long MIN_PEEK_INTERVAL_MS = 2000;
    private static final long MAX_SEEKING_TIME_MS = 10000;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    
    private static final double FOLLOW_MAX_COMBAT_DISTANCE = 20.0D;
    private static final double FOLLOW_COVER_SEARCH_RADIUS = 15.0D;
    private static final double FOLLOW_REGROUP_DISTANCE = 10.0D;
    
    private static final double THREAT_DIRECTION_CHANGE_THRESHOLD = 60.0;
    
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
        this.coverManager = soldier.getCoverBehaviorManager();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    
    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        
        if (!soldier.isAlive()) {
            return false;
        }
        
        coverManager.tickSuppression(coverManager.isInCover());
        
        CoverBehaviorManager.CoverState state = coverManager.getState();
        
        switch (state) {
            case NO_COVER:
                return shouldSeekCover();
            case SEEKING_COVER:
                return true;
            case IN_COVER:
                return shouldContinueInCover();
            case SUPPRESSED_IN_COVER:
                return true;
            case REPOSITIONING:
                return true;
            default:
                return false;
        }
    }
    
    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        
        CoverBehaviorManager.CoverState state = coverManager.getState();
        
        return state == CoverBehaviorManager.CoverState.SEEKING_COVER ||
               state == CoverBehaviorManager.CoverState.IN_COVER ||
               state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER ||
               state == CoverBehaviorManager.CoverState.REPOSITIONING;
    }
    
    @Override
    public void start() {
        stuckTicks = 0;
        reevaluateCounter = 0;
        
        CoverBehaviorManager.CoverState state = coverManager.getState();
        
        if (state == CoverBehaviorManager.CoverState.NO_COVER) {
            coverManager.setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            findAndMoveToCover();
        } else if (state == CoverBehaviorManager.CoverState.SEEKING_COVER || 
                   state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            if (coverManager.getTargetCover() == null) {
                findAndMoveToCover();
            } else {
                moveToCover(coverManager.getTargetCover());
            }
        }
        
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    
    @Override
    public void stop() {
        CoverBehaviorManager.CoverState state = coverManager.getState();
        
        if (state == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            state == CoverBehaviorManager.CoverState.REPOSITIONING) {
            coverManager.clearTargetCover();
        }
        
        cooldown = COOLDOWN_TICKS;
        stuckTicks = 0;
    }
    
    @Override
    public void tick() {
        CoverBehaviorManager.CoverState state = coverManager.getState();
        coverManager.tickSuppression(coverManager.isInCover());
        
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
                break;
        }
    }
    
    private void tickSeekingCover() {
        CoverPoint targetCover = coverManager.getTargetCover();
        
        if (targetCover == null) {
            findAndMoveToCover();
            return;
        }
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            onCoverReached(targetCover);
            return;
        }
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} stuck, re-seeking", soldier.getId());
                }
                coverManager.clearTargetCover();
                stuckTicks = 0;
                findAndMoveToCover();
            }
        } else {
            stuckTicks = 0;
        }
        
        if (coverManager.getTimeSeeking() > MAX_SEEKING_TIME_MS) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} seeking timeout", soldier.getId());
            }
            coverManager.clearTargetCover();
            coverManager.setState(CoverBehaviorManager.CoverState.NO_COVER);
        }
    }
    
    private void tickRepositioning() {
        CoverPoint targetCover = coverManager.getTargetCover();
        
        if (targetCover == null) {
            coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
            return;
        }
        
        double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
        
        if (distance < COVER_REACHED_DISTANCE) {
            coverManager.setCurrentCover(targetCover);
            coverManager.clearTargetCover();
            coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
            
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} repositioned to {}", 
                    soldier.getId(), targetCover.getPosition());
            }
            return;
        }
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                coverManager.clearTargetCover();
                coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }
    
    private void tickInCover() {
        setFlags(EnumSet.of(Flag.LOOK));
        
        reevaluateCounter++;
        
        if (reevaluateCounter >= REEVALUATE_INTERVAL_TICKS) {
            reevaluateCounter = 0;
            evaluateCoverState();
        }
        
        if (coverManager.isSuppressed()) {
            coverManager.setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            return;
        }
        
        if (shouldExitCoverForFollow()) {
            coverManager.clearCover();
            return;
        }
        
        if (canPeekAndShoot()) {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
    }
    
    private void tickSuppressedInCover() {
        setFlags(EnumSet.of(Flag.LOOK));
        
        if (!coverManager.isPinned() && coverManager.getSuppressionTracker().canPeek()) {
            coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        if (shouldExitCoverForFollow() && !coverManager.isSuppressed()) {
            coverManager.clearCover();
        }
    }
    
    private boolean shouldSeekCover() {
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            return true;
        }
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            if (!coverManager.isSuppressed() && soldier.getHealth() / soldier.getMaxHealth() >= LOW_HEALTH_THRESHOLD) {
                return false;
            }
        }
        
        if (coverManager.isSuppressed()) {
            return true;
        }
        
        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) {
            return true;
        }
        
        return soldier.getTarget() != null && soldier.getTarget().isAlive();
    }
    
    private boolean shouldContinueInCover() {
        if (coverManager.getTimeInCover() < MIN_COVER_DWELL_TIME_MS) {
            return true;
        }
        
        if (!isCoverStillValid()) {
            return shouldSeekCover();
        }
        
        if (coverManager.isSuppressed()) {
            return true;
        }
        
        return true;
    }
    
    private boolean isCoverStillValid() {
        CoverPoint currentCover = coverManager.getCurrentCover();
        if (currentCover == null) {
            return false;
        }
        
        double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
        
        double maxDistance = soldier.getTarget() != null ? COMBAT_COVER_VALID_DISTANCE : COVER_VALID_DISTANCE;
        
        if (distance > COVER_ABANDON_DISTANCE) {
            return false;
        }
        
        if (distance > maxDistance && coverManager.getTimeInCover() >= MIN_COVER_DWELL_TIME_MS) {
            return false;
        }
        
        return true;
    }
    
    private void evaluateCoverState() {
        CoverPoint currentCover = coverManager.getCurrentCover();
        if (currentCover == null) {
            coverManager.setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            return;
        }
        
        if (!isCoverStillValid()) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} cover invalid, seeking new", 
                    soldier.getId());
            }
            startRepositioning();
            return;
        }
        
        Optional<CoverPoint> betterCover = findBetterCover();
        if (betterCover.isPresent()) {
            CoverPoint newCover = betterCover.get();
            float currentScore = currentCover.getQuality() * (1.0f - coverManager.getCoverQualityPenalty());
            float newScore = newCover.getQuality();
            
            if (newScore > currentScore * (1.0f + HYSTERESIS_THRESHOLD)) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} found better cover: {} (score {:.2f}) > current (score {:.2f})", 
                        soldier.getId(), newCover.getPosition(), newScore, currentScore);
                }
                startRepositioning(newCover);
            }
        }
    }
    
    private void startRepositioning() {
        findAndMoveToCover();
        if (coverManager.getTargetCover() != null) {
            coverManager.setState(CoverBehaviorManager.CoverState.REPOSITIONING);
        } else {
            coverManager.setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        }
    }
    
    private void startRepositioning(CoverPoint newCover) {
        coverManager.clearTargetCover();
        
        if (CoverReservationManager.reserve(newCover.getPosition(), soldier)) {
            coverManager.setTargetCover(newCover);
            coverManager.setState(CoverBehaviorManager.CoverState.REPOSITIONING);
            moveToCover(newCover);
        }
    }
    
    private boolean shouldExitCoverForFollow() {
        if (soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }
        
        if (coverManager.isSuppressed()) {
            return false;
        }
        
        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) {
            return false;
        }
        
        if (coverManager.getTimeInCover() < MIN_COVER_DWELL_TIME_MS) {
            return false;
        }
        
        LivingEntity owner = soldier.getOwner();
        if (owner instanceof Player) {
            double distanceToOwner = soldier.distanceTo(owner);
            if (distanceToOwner > FOLLOW_REGROUP_DISTANCE) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} exiting cover to follow owner (distance {:.1f})", 
                        soldier.getId(), distanceToOwner);
                }
                return true;
            }
        }
        
        if (soldier.getTarget() == null || !soldier.getTarget().isAlive()) {
            return true;
        }
        
        return false;
    }
    
    private boolean canPeekAndShoot() {
        if (coverManager.isPinned()) {
            return false;
        }
        
        if (!coverManager.getSuppressionTracker().canPeek()) {
            return false;
        }
        
        long timeSincePeek = System.currentTimeMillis() - coverManager.getLastPeekTime();
        return timeSincePeek > MIN_PEEK_INTERVAL_MS;
    }
    
    private void findAndMoveToCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        
        List<LivingEntity> threats = getThreats();
        ThreatDirectionCalculator.ThreatAnalysis analysis = coverManager.analyzeThreats(soldier, threats);
        
        int searchRadius = SEARCH_RADIUS;
        BlockPos searchCenter = soldier.blockPosition();
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner instanceof Player) {
                searchCenter = owner.blockPosition();
                searchRadius = (int) FOLLOW_COVER_SEARCH_RADIUS;
                
                if (coverManager.isSuppressed()) {
                    searchRadius = SEARCH_RADIUS;
                    searchCenter = soldier.blockPosition();
                }
            }
        }
        
        Optional<CoverPoint> bestCover = finder.findBestCover(
            soldier,
            analysis.primaryDirection,
            threats,
            searchRadius
        );
        
        if (bestCover.isEmpty()) {
            bestCover = finder.findBestCover(
                searchCenter,
                searchRadius,
                threats.isEmpty() ? null : threats.get(0)
            );
        }
        
        if (bestCover.isPresent()) {
            CoverPoint cover = bestCover.get();
            
            CoverPoint lastCover = coverManager.getLastCover();
            if (lastCover != null) {
                if (cover.getPosition().equals(lastCover.getPosition())) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} avoiding recent cover {}", 
                            soldier.getId(), lastCover.getPosition());
                    }
                    return;
                }
            }
            
            coverManager.clearCoverQualityPenalty();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                coverManager.setTargetCover(cover);
                moveToCover(cover);
                
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} navigating to {} (type={}, score={:.2f})", 
                        soldier.getId(), cover.getPosition(), cover.getType(), cover.getQuality());
                }
            } else if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} failed to reserve {}", 
                    soldier.getId(), cover.getPosition());
            }
        } else if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} found no valid cover", soldier.getId());
        }
    }
    
    private Optional<CoverPoint> findBetterCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        
        List<LivingEntity> threats = getThreats();
        ThreatDirectionCalculator.ThreatAnalysis analysis = coverManager.analyzeThreats(soldier, threats);
        
        Optional<CoverPoint> bestCover = finder.findBestCover(
            soldier,
            analysis.primaryDirection,
            threats,
            SEARCH_RADIUS
        );
        
        return bestCover;
    }
    
    private void moveToCover(CoverPoint cover) {
        BlockPos pos = cover.getPosition();
        boolean success = navigation.moveTo(pos.getX(), pos.getY(), pos.getZ(), 1.2);
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} moveTo {} (success={})", 
                soldier.getId(), pos, success);
        }
    }
    
    private void onCoverReached(CoverPoint cover) {
        coverManager.setCurrentCover(cover);
        coverManager.clearTargetCover();
        
        if (coverManager.isPinned()) {
            coverManager.setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
        } else {
            coverManager.setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} reached cover at {} (type={}, score={:.2f})", 
                soldier.getId(), cover.getPosition(), cover.getType(), cover.getQuality());
        }
    }
    
    private List<LivingEntity> getThreats() {
        List<LivingEntity> threats = new ArrayList<>();
        
        LivingEntity currentTarget = soldier.getTarget();
        if (currentTarget != null && currentTarget.isAlive()) {
            threats.add(currentTarget);
        }
        
        return threats;
    }
}
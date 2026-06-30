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
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    
    private CoverBehaviorManager getCoverManager() {
        return soldier.getCoverBehaviorManager();
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
        
        getCoverManager().tickSuppression(getCoverManager().isInCover());
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        switch (state) {
            case NO_COVER:
                return shouldSeekCover();
            case SEEKING_COVER:
                return true;
            case IN_COVER:
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
        
        CoverBehaviorManager.CoverState state = getCoverManager().getState();
        
        return state != CoverBehaviorManager.CoverState.NO_COVER;
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
            setFlags(EnumSet.noneOf(Flag.class));
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
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} stuck, re-seeking", soldier.getId());
                }
                getCoverManager().clearTargetCover();
                stuckTicks = 0;
                findAndMoveToCover();
            }
        } else {
            stuckTicks = 0;
        }
        
        if (getCoverManager().getTimeSeeking() > MAX_SEEKING_TIME_MS) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} seeking timeout", soldier.getId());
            }
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
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} target same as current, canceling reposition", 
                    soldier.getId());
            }
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
            
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} repositioned to {} (score={})", 
                    soldier.getId(), targetCover.getPosition(), String.format("%.2f", targetCover.getQuality()));
            }
            return;
        }
        
        if (navigation.isDone()) {
            stuckTicks++;
            if (stuckTicks > MAX_STUCK_TICKS) {
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
        if (getCoverManager().isSuppressed()) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
            return;
        }
        
        if (shouldExitCoverForFollow()) {
            getCoverManager().clearCover();
            return;
        }
        
        reevaluateCounter++;
        
        if (reevaluateCounter >= REEVALUATE_INTERVAL_TICKS) {
            reevaluateCounter = 0;
            evaluateCoverState();
        }
    }
    
    private void tickSuppressedInCover() {
        if (!getCoverManager().isPinned() && getCoverManager().getSuppressionTracker().canPeek()) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        if (shouldExitCoverForFollow() && !getCoverManager().isSuppressed()) {
            getCoverManager().clearCover();
        }
    }
    
    private boolean shouldSeekCover() {
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            return true;
        }
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            if (!getCoverManager().isSuppressed() && soldier.getHealth() / soldier.getMaxHealth() >= LOW_HEALTH_THRESHOLD) {
                return false;
            }
        }
        
        if (getCoverManager().isSuppressed()) {
            return true;
        }
        
        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) {
            return true;
        }
        
        return soldier.getTarget() != null && soldier.getTarget().isAlive();
    }
    
    private boolean isCoverStillValid() {
        CoverPoint currentCover = getCoverManager().getCurrentCover();
        if (currentCover == null) {
            return false;
        }
        
        double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
        
        if (distance > COVER_ABANDON_DISTANCE) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} cover abandoned (distance={})", 
                    soldier.getId(), String.format("%.1f", distance));
            }
            return false;
        }
        
        double maxDistance = soldier.getTarget() != null ? COMBAT_COVER_VALID_DISTANCE : COVER_VALID_DISTANCE;
        
        if (distance > maxDistance && getCoverManager().getTimeInCover() >= MIN_COVER_DWELL_TIME_MS) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} too far from cover (distance={}, max={})", 
                    soldier.getId(), String.format("%.1f", distance), String.format("%.1f", maxDistance));
            }
            return false;
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
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} cover invalid, seeking new", 
                    soldier.getId());
            }
            startRepositioning();
            return;
        }
        
        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) {
            return;
        }
        
        Optional<CoverPoint> betterCover = findBetterCover();
        if (betterCover.isPresent()) {
            CoverPoint newCover = betterCover.get();
            
            if (newCover.getPosition().equals(currentCover.getPosition())) {
                return;
            }
            
            float currentScore = currentCover.getQuality() * (1.0f - getCoverManager().getCoverQualityPenalty());
            float newScore = newCover.getQuality();
            
            if (newScore > currentScore * (1.0f + HYSTERESIS_THRESHOLD)) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} found better cover: {} (score={}) > current {} (score={})", 
                        soldier.getId(), newCover.getPosition(), String.format("%.2f", newScore), 
                        currentCover.getPosition(), String.format("%.2f", currentScore));
                }
                startRepositioning(newCover);
            }
        }
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
        
        if (currentCover != null && newCover.getPosition().equals(currentCover.getPosition())) {
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} rejecting reposition to same cover", 
                    soldier.getId());
            }
            return;
        }
        
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
    
    private boolean shouldExitCoverForFollow() {
        if (soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }
        
        if (getCoverManager().isSuppressed()) {
            return false;
        }
        
        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) {
            return false;
        }
        
        if (getCoverManager().getTimeInCover() < MIN_COVER_DWELL_TIME_MS) {
            return false;
        }
        
        LivingEntity owner = soldier.getOwner();
        if (owner instanceof Player) {
            double distanceToOwner = soldier.distanceTo(owner);
            if (distanceToOwner > FOLLOW_REGROUP_DISTANCE) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} exiting cover to follow owner (distance={})", 
                        soldier.getId(), String.format("%.1f", distanceToOwner));
                }
                return true;
            }
        }
        
        if (soldier.getTarget() == null || !soldier.getTarget().isAlive()) {
            return true;
        }
        
        return false;
    }
    
    private void findAndMoveToCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        
        List<LivingEntity> threats = getThreats();
        ThreatDirectionCalculator.ThreatAnalysis analysis = getCoverManager().analyzeThreats(soldier, threats);
        
        int searchRadius = SEARCH_RADIUS;
        BlockPos searchCenter = soldier.blockPosition();
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            LivingEntity owner = soldier.getOwner();
            if (owner instanceof Player) {
                searchCenter = owner.blockPosition();
                searchRadius = (int) FOLLOW_COVER_SEARCH_RADIUS;
                
                if (getCoverManager().isSuppressed()) {
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
            
            CoverPoint lastCover = getCoverManager().getLastCover();
            if (lastCover != null && cover.getPosition().equals(lastCover.getPosition())) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} avoiding recent cover {}", 
                        soldier.getId(), lastCover.getPosition());
                }
                return;
            }
            
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            if (currentCover != null && cover.getPosition().equals(currentCover.getPosition())) {
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} already at best cover {}", 
                        soldier.getId(), currentCover.getPosition());
                }
                return;
            }
            
            getCoverManager().clearCoverQualityPenalty();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                getCoverManager().setTargetCover(cover);
                moveToCover(cover);
                
                if (debugLoggingEnabled) {
                    StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} navigating to {} (type={}, score={})", 
                        soldier.getId(), cover.getPosition(), cover.getType(), String.format("%.2f", cover.getQuality()));
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
        ThreatDirectionCalculator.ThreatAnalysis analysis = getCoverManager().analyzeThreats(soldier, threats);
        
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
        CoverPoint previousCover = getCoverManager().getCurrentCover();
        if (previousCover != null) {
            getCoverManager().setLastCover(previousCover);
        }
        
        getCoverManager().setCurrentCover(cover);
        getCoverManager().clearTargetCover();
        
        if (getCoverManager().isPinned()) {
            getCoverManager().setState(CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
        } else {
            getCoverManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        }
        
        setFlags(EnumSet.noneOf(Flag.class));
        
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[CoverTacticalGoal] Soldier {} reached cover at {} (type={}, score={})", 
                soldier.getId(), cover.getPosition(), cover.getType(), String.format("%.2f", cover.getQuality()));
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
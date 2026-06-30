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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class SeekCoverGoal extends Goal {
    private final SoldierEntity soldier;
    private final PathNavigation navigation;
    
    private CoverPoint targetCover = null;
    private float currentCoverScore = 0.0f;
    private int cooldown = 0;
    private int stuckTicks = 0;
    
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_STUCK_TICKS = 60;
    private static final double COVER_REACHED_THRESHOLD = 1.5;
    private static final int SEARCH_RADIUS = 12;
    private static final long MIN_COVER_DWELL_TIME_MS = 3000;
    private static final float HYSTERESIS_THRESHOLD = 0.25f;
    
    private static boolean debugLoggingEnabled = false;
    
    public static void setDebugLogging(boolean enabled) {
        debugLoggingEnabled = enabled;
    }
    
    public static boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }
    
    public SeekCoverGoal(SoldierEntity soldier) {
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
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            SuppressionTracker suppression = getCoverManager().getSuppressionTracker();
            float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
            
            if (!suppression.isSuppressed() && healthPercent >= 0.3f) {
                return false;
            }
        }
        
        if (targetCover != null && !navigation.isDone()) {
            return false;
        }
        
        if (getCoverManager().isInCover()) {
            long timeInCover = getCoverManager().getTimeInCover();
            if (timeInCover < MIN_COVER_DWELL_TIME_MS) {
                return false;
            }
            
            if (getCoverManager().isCoverStillValid(soldier)) {
                return false;
            }
        }
        
        if (!getCoverManager().shouldGoalSeekCover(soldier)) {
            return false;
        }
        
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} can seek cover (state={}, mode={}, seeking={}ms, suppressed={})", 
                soldier.getId(), 
                getCoverManager().getState(),
                soldier.getSquadMode(),
                getCoverManager().getTimeSeeking(),
                getCoverManager().getSuppressionTracker().isSuppressed());
        }
        
        return true;
    }
    
    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        
        if (targetCover == null) return false;
        
        if (getCoverManager().isInCover()) {
            return false;
        }
        
        return true;
    }
    
    @Override
    public void start() {
        stuckTicks = 0;
        findAndMoveToCover();
    }
    
    @Override
    public void stop() {
        if (targetCover != null && !getCoverManager().isInCover()) {
            CoverReservationManager.release(targetCover.getPosition(), soldier);
            if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} released cover reservation at {} (stopped)", 
                    soldier.getId(), targetCover.getPosition());
            }
        }
        targetCover = null;
        currentCoverScore = 0.0f;
        cooldown = COOLDOWN_TICKS;
    }
    
    @Override
    public void tick() {
        if (targetCover != null) {
            double distance = soldier.position().distanceTo(targetCover.getPosition().getCenter());
            
            if (distance < COVER_REACHED_THRESHOLD) {
                if (!getCoverManager().isInCover()) {
                    onCoverReached();
                }
                return;
            }
            
            if (navigation.isDone()) {
                stuckTicks++;
                if (stuckTicks > MAX_STUCK_TICKS) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} stuck for {} ticks, searching for new cover", 
                            soldier.getId(), stuckTicks);
                    }
                    CoverReservationManager.release(targetCover.getPosition(), soldier);
                    targetCover = null;
                    stuckTicks = 0;
                    findAndMoveToCover();
                }
            } else {
                stuckTicks = 0;
            }
        }
    }
    
    private void findAndMoveToCover() {
        Level level = soldier.level();
        CoverFinder finder = new CoverFinder(level);
        
        List<LivingEntity> threats = getThreats();
        
        ThreatDirectionCalculator.ThreatAnalysis analysis = 
            getCoverManager().analyzeThreats(soldier, threats);
        
        Optional<CoverPoint> bestCover = finder.findBestCover(
            soldier,
            analysis.primaryDirection,
            threats,
            SEARCH_RADIUS
        );
        
        if (bestCover.isEmpty()) {
            bestCover = finder.findBestCover(
                soldier.blockPosition(),
                SEARCH_RADIUS,
                threats.isEmpty() ? null : threats.get(0)
            );
        }
        
        if (bestCover.isPresent()) {
            CoverPoint cover = bestCover.get();
            
            CoverPoint currentCover = getCoverManager().getCurrentCover();
            if (currentCover != null) {
                float penalty = getCoverManager().getCoverQualityPenalty();
                float currentScore = currentCover.getQuality() * (1.0f - penalty);
                float newScore = cover.getQuality();
                
                if (newScore <= currentScore * (1.0f + HYSTERESIS_THRESHOLD)) {
                    if (debugLoggingEnabled) {
                        StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} rejected new cover (hysteresis): current={} (score={:.2f}, penalty={:.0%}), new={} (score={:.2f})", 
                            soldier.getId(), currentCover.getPosition(), currentScore, penalty, cover.getPosition(), newScore);
                    }
                    return;
                }
            }
            
            getCoverManager().clearCoverQualityPenalty();
            
            if (CoverReservationManager.reserve(cover.getPosition(), soldier)) {
                if (debugLoggingEnabled && targetCover != null && !targetCover.getPosition().equals(cover.getPosition())) {
                    StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} switching cover: {} (score={:.2f}) -> {} (score={:.2f})", 
                        soldier.getId(), 
                        targetCover.getPosition(), 
                        targetCover.getQuality(),
                        cover.getPosition(),
                        cover.getQuality());
                }
                
                this.targetCover = cover;
                this.currentCoverScore = cover.getQuality();
                moveToCover(cover);
            } else if (debugLoggingEnabled) {
                StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} failed to reserve cover at {}", 
                    soldier.getId(), cover.getPosition());
            }
        } else if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} found no valid cover", soldier.getId());
        }
    }
    
    private void moveToCover(CoverPoint cover) {
        BlockPos pos = cover.getPosition();
        boolean success = navigation.moveTo(pos.getX(), pos.getY(), pos.getZ(), 1.2);
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} navigating to {} (success={})", 
                soldier.getId(), pos, success);
        }
    }
    
    private void onCoverReached() {
        getCoverManager().setCover(targetCover);
        
        if (debugLoggingEnabled) {
            StevesArmyMod.LOGGER.info("[SeekCoverGoal] Soldier {} reached cover at {} (type={}, score={})", 
                soldier.getId(), targetCover.getPosition(), targetCover.getType(), targetCover.getQuality());
        }
        
        SuppressionTracker suppression = getCoverManager().getSuppressionTracker();
        if (suppression.isPinned()) {
        } else {
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

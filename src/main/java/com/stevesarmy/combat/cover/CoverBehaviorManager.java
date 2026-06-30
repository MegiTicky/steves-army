package com.stevesarmy.combat.cover;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class CoverBehaviorManager {
    
    public enum CoverState {
        NO_COVER,
        SEEKING_COVER,
        IN_COVER,
        SUPPRESSED_IN_COVER
    }
    
    private CoverState state = CoverState.NO_COVER;
    private CoverPoint currentCover = null;
    private long coverEntryTime = 0;
    private long seekingStartTime = 0;
    private long lastPeekTime = 0;
    private final SuppressionTracker suppressionTracker;
    private final ThreatDirectionCalculator threatCalculator;
    
    private int lastThreatCount = 0;
    private Vec3 lastPrimaryThreatDirection = null;
    private float coverQualityPenalty = 0.0f;
    
    private static final long MIN_COVER_TIME_MS = 2500;
    private static final long MIN_PEEK_INTERVAL_MS = 1500;
    private static final long MAX_SEEKING_TIME_MS = 10000;
    private static final float LOW_HEALTH_THRESHOLD = 0.3f;
    private static final double MAX_DISTANCE_TO_COVER = 2.0;
    private static final double THREAT_DIRECTION_CHANGE_THRESHOLD = 60.0;
    private static final float THREAT_CHANGE_PENALTY = 0.2f;
    
    public CoverBehaviorManager() {
        this.suppressionTracker = new SuppressionTracker();
        this.threatCalculator = new ThreatDirectionCalculator();
    }
    
    public void tick(SoldierEntity soldier) {
        boolean inCover = state == CoverState.IN_COVER || state == CoverState.SUPPRESSED_IN_COVER;
        suppressionTracker.tick(inCover);
        
        switch (state) {
            case NO_COVER -> handleNoCover(soldier);
            case SEEKING_COVER -> handleSeekingCover(soldier);
            case IN_COVER -> handleInCover(soldier);
            case SUPPRESSED_IN_COVER -> handleSuppressed(soldier);
        }
    }
    
    private void handleNoCover(SoldierEntity soldier) {
        if (shouldSeekCover(soldier)) {
            transitionTo(CoverState.SEEKING_COVER);
        }
    }
    
    private void handleSeekingCover(SoldierEntity soldier) {
        long seekingTime = System.currentTimeMillis() - seekingStartTime;
        if (seekingTime > MAX_SEEKING_TIME_MS) {
            transitionTo(CoverState.NO_COVER);
            return;
        }
        
        if (currentCover != null) {
            double distanceToCover = soldier.position().distanceTo(currentCover.getPosition().getCenter());
            
            if (distanceToCover < MAX_DISTANCE_TO_COVER) {
                if (suppressionTracker.isPinned()) {
                    transitionTo(CoverState.SUPPRESSED_IN_COVER);
                } else {
                    transitionTo(CoverState.IN_COVER);
                }
            }
        }
    }
    
    private void handleInCover(SoldierEntity soldier) {
        if (soldier.getSquadMode() == com.stevesarmy.squad.SquadMode.FOLLOW) {
            SuppressionTracker suppression = getSuppressionTracker();
            float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
            
            if (!suppression.isSuppressed() && healthPercent >= LOW_HEALTH_THRESHOLD) {
                clearCover();
                return;
            }
        }
        
        if (suppressionTracker.isPinned()) {
            transitionTo(CoverState.SUPPRESSED_IN_COVER);
            return;
        }
        
        if (!isCoverStillValid(soldier)) {
            transitionTo(CoverState.SEEKING_COVER);
            return;
        }
        
        applyCrawlForCover(soldier);
    }
    
    private void handleSuppressed(SoldierEntity soldier) {
        if (!suppressionTracker.isPinned() && suppressionTracker.canPeek()) {
            transitionTo(CoverState.IN_COVER);
        }
        
        if (currentCover != null && GunIntegration.isTaczLoaded()) {
            GunIntegration.crawl(soldier, true);
        }
    }
    
    private boolean shouldSeekCover(SoldierEntity soldier) {
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            return true;
        }
        
        if (suppressionTracker.isSuppressed()) {
            return true;
        }
        
        float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
        if (healthPercent < LOW_HEALTH_THRESHOLD) {
            return true;
        }
        
        return false;
    }
    
    public boolean shouldGoalSeekCover(SoldierEntity soldier) {
        if (state == CoverState.SEEKING_COVER) {
            return true;
        }
        return shouldSeekCover(soldier);
    }
    
    public boolean isCoverStillValid(SoldierEntity soldier) {
        if (currentCover == null) {
            return false;
        }
        
        double distance = soldier.position().distanceTo(currentCover.getPosition().getCenter());
        if (distance > MAX_DISTANCE_TO_COVER * 1.5) {
            return false;
        }
        
        if (soldier.getSquadMode() == SquadMode.FOLLOW && !suppressionTracker.isSuppressed()) {
            float healthPercent = soldier.getHealth() / soldier.getMaxHealth();
            if (healthPercent >= LOW_HEALTH_THRESHOLD) {
                return false;
            }
        }
        
        return true;
    }
    
    private void applyCrawlForCover(SoldierEntity soldier) {
        if (currentCover == null) return;
        
        if (!GunIntegration.isTaczLoaded() || !GunIntegration.hasGun(soldier)) {
            return;
        }
        
        CoverType type = currentCover.getType();
        if (type == CoverType.HALF || type == CoverType.FULL) {
            GunIntegration.crawl(soldier, true);
        }
    }
    
    private void transitionTo(CoverState newState) {
        CoverState oldState = this.state;
        this.state = newState;
        
        if (newState == CoverState.SEEKING_COVER) {
            this.seekingStartTime = System.currentTimeMillis();
            this.coverEntryTime = 0;
            if (currentCover != null) {
                CoverReservationManager.release(currentCover.getPosition(), null);
            }
            this.currentCover = null;
        }
        
        if (newState == CoverState.IN_COVER || newState == CoverState.SUPPRESSED_IN_COVER) {
            this.coverEntryTime = System.currentTimeMillis();
        }
        
        if (newState == CoverState.NO_COVER) {
            this.coverEntryTime = 0;
            this.seekingStartTime = 0;
            if (currentCover != null) {
                CoverReservationManager.release(currentCover.getPosition(), null);
            }
            this.currentCover = null;
        }
    }
    
    public void setCover(CoverPoint cover) {
        this.currentCover = cover;
        if (cover != null) {
            this.coverEntryTime = System.currentTimeMillis();
        }
    }
    
    public void clearCover() {
        if (currentCover != null) {
            CoverReservationManager.release(currentCover.getPosition(), null);
        }
        this.currentCover = null;
        this.coverEntryTime = 0;
        transitionTo(CoverState.NO_COVER);
    }
    
    public boolean canPeekAndShoot() {
        if (state == CoverState.SUPPRESSED_IN_COVER) {
            return false;
        }
        
        if (!suppressionTracker.canPeek()) {
            return false;
        }
        
        long timeSincePeek = System.currentTimeMillis() - lastPeekTime;
        return timeSincePeek > MIN_PEEK_INTERVAL_MS;
    }
    
    public void onPeekShot() {
        this.lastPeekTime = System.currentTimeMillis();
    }
    
    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier) {
        suppressionTracker.onNearMiss(bulletPath, soldier);
    }
    
    public void onIncomingFire(LivingEntity shooter) {
        suppressionTracker.onIncomingFire(shooter);
    }
    
    public void onTakeDamage() {
        suppressionTracker.onTakeDamage();
    }
    
    public CoverState getState() {
        return state;
    }
    
    public CoverPoint getCurrentCover() {
        return currentCover;
    }
    
    public SuppressionTracker getSuppressionTracker() {
        return suppressionTracker;
    }
    
    public ThreatDirectionCalculator getThreatCalculator() {
        return threatCalculator;
    }
    
    public boolean isInCover() {
        return state == CoverState.IN_COVER || state == CoverState.SUPPRESSED_IN_COVER;
    }
    
    public boolean isSeekingCover() {
        return state == CoverState.SEEKING_COVER;
    }
    
    public long getTimeInCover() {
        if (coverEntryTime == 0) return 0;
        return System.currentTimeMillis() - coverEntryTime;
    }
    
    public long getTimeSeeking() {
        if (seekingStartTime == 0) return 0;
        return System.currentTimeMillis() - seekingStartTime;
    }
    
    public boolean shouldExitCrawlForShot() {
        if (currentCover == null) return false;
        
        CoverType type = currentCover.getType();
        if (type != CoverType.HALF && type != CoverType.FULL) {
            return false;
        }
        
        return canPeekAndShoot();
    }
    
    public ThreatDirectionCalculator.ThreatAnalysis analyzeThreats(SoldierEntity soldier, List<LivingEntity> threats) {
        ThreatDirectionCalculator.ThreatAnalysis analysis = threatCalculator.analyzeThreats(soldier, threats);
        
        updateThreatTracking(threats, analysis);
        
        return analysis;
    }
    
    private void updateThreatTracking(List<LivingEntity> threats, ThreatDirectionCalculator.ThreatAnalysis analysis) {
        int currentThreatCount = threats.size();
        Vec3 currentPrimaryDirection = analysis.primaryDirection;
        
        boolean threatCountChanged = (currentThreatCount != lastThreatCount);
        
        boolean directionChanged = false;
        if (currentPrimaryDirection != null && lastPrimaryThreatDirection != null) {
            double angle = Math.toDegrees(Math.acos(currentPrimaryDirection.dot(lastPrimaryThreatDirection)));
            directionChanged = (angle > THREAT_DIRECTION_CHANGE_THRESHOLD);
        } else if ((currentPrimaryDirection == null) != (lastPrimaryThreatDirection == null)) {
            directionChanged = true;
        }
        
        if (threatCountChanged || directionChanged) {
            coverQualityPenalty = THREAT_CHANGE_PENALTY;
        }
        
        lastThreatCount = currentThreatCount;
        lastPrimaryThreatDirection = currentPrimaryDirection;
    }
    
    public float getCoverQualityPenalty() {
        return coverQualityPenalty;
    }
    
    public void clearCoverQualityPenalty() {
        coverQualityPenalty = 0.0f;
    }
    
    public int getLastThreatCount() {
        return lastThreatCount;
    }
    
    public Vec3 getLastPrimaryThreatDirection() {
        return lastPrimaryThreatDirection;
    }
}

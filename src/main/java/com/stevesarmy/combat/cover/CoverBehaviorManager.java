package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class CoverBehaviorManager {
    
    public enum CoverState {
        NO_COVER,
        SEEKING_COVER,
        IN_COVER,
        SUPPRESSED_IN_COVER,
        REPOSITIONING
    }
    
    private CoverState state = CoverState.NO_COVER;
    private CoverPoint currentCover = null;
    private CoverPoint targetCover = null;
    private CoverPoint lastCover = null;
    private long coverEntryTime = 0;
    private long seekingStartTime = 0;
    private long lastPeekTime = 0;
    private final SuppressionTracker suppressionTracker;
    private final ThreatDirectionCalculator threatCalculator;
    
    private int lastThreatCount = 0;
    private Vec3 lastPrimaryThreatDirection = null;
    private float lastCoverQuality = 0.0f;
    private float coverQualityPenalty = 0.0f;
    
    public CoverBehaviorManager() {
        this.suppressionTracker = new SuppressionTracker();
        this.threatCalculator = new ThreatDirectionCalculator();
    }
    
    public CoverState getState() {
        return state;
    }
    
    public void setState(CoverState state) {
        this.state = state;
        
        if (state == CoverState.SEEKING_COVER || state == CoverState.REPOSITIONING) {
            this.seekingStartTime = System.currentTimeMillis();
        }
        
        if (state == CoverState.IN_COVER || state == CoverState.SUPPRESSED_IN_COVER) {
            this.coverEntryTime = System.currentTimeMillis();
            if (currentCover != null) {
                this.lastCoverQuality = currentCover.getQuality();
            }
        }
        
        if (state == CoverState.NO_COVER) {
            this.coverEntryTime = 0;
            this.seekingStartTime = 0;
        }
    }
    
    public CoverPoint getCurrentCover() {
        return currentCover;
    }
    
    public void setCurrentCover(CoverPoint cover) {
        this.currentCover = cover;
        if (cover != null) {
            this.coverEntryTime = System.currentTimeMillis();
            this.lastCoverQuality = cover.getQuality();
        }
    }
    
    public CoverPoint getTargetCover() {
        return targetCover;
    }
    
    public void setTargetCover(CoverPoint cover) {
        this.targetCover = cover;
    }
    
    public CoverPoint getLastCover() {
        return lastCover;
    }
    
    public void setLastCover(CoverPoint cover) {
        this.lastCover = cover;
    }
    
    public void clearCover() {
        if (currentCover != null) {
            CoverReservationManager.release(currentCover.getPosition(), null);
            this.lastCover = currentCover;
        }
        this.currentCover = null;
        this.coverEntryTime = 0;
        this.state = CoverState.NO_COVER;
    }
    
    public void clearTargetCover() {
        if (targetCover != null) {
            CoverReservationManager.release(targetCover.getPosition(), null);
        }
        this.targetCover = null;
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
        return state == CoverState.SEEKING_COVER || state == CoverState.REPOSITIONING;
    }
    
    public boolean isPinned() {
        return suppressionTracker.isPinned();
    }
    
    public boolean isSuppressed() {
        return suppressionTracker.isSuppressed();
    }
    
    public long getTimeInCover() {
        if (coverEntryTime == 0) return 0;
        return System.currentTimeMillis() - coverEntryTime;
    }
    
    public long getTimeSeeking() {
        if (seekingStartTime == 0) return 0;
        return System.currentTimeMillis() - seekingStartTime;
    }
    
    public long getLastPeekTime() {
        return lastPeekTime;
    }
    
    public void onPeekShot() {
        this.lastPeekTime = System.currentTimeMillis();
    }
    
    public float getLastCoverQuality() {
        return lastCoverQuality;
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
    
    public ThreatDirectionCalculator.ThreatAnalysis analyzeThreats(SoldierEntity soldier, java.util.List<LivingEntity> threats) {
        ThreatDirectionCalculator.ThreatAnalysis analysis = threatCalculator.analyzeThreats(soldier, threats);
        updateThreatTracking(threats, analysis);
        return analysis;
    }
    
    private void updateThreatTracking(java.util.List<LivingEntity> threats, ThreatDirectionCalculator.ThreatAnalysis analysis) {
        int currentThreatCount = threats.size();
        Vec3 currentPrimaryDirection = analysis.primaryDirection;
        
        boolean threatCountChanged = (currentThreatCount != lastThreatCount);
        
        boolean directionChanged = false;
        if (currentPrimaryDirection != null && lastPrimaryThreatDirection != null) {
            double angle = Math.toDegrees(Math.acos(currentPrimaryDirection.dot(lastPrimaryThreatDirection)));
            directionChanged = (angle > 60.0);
        } else if ((currentPrimaryDirection == null) != (lastPrimaryThreatDirection == null)) {
            directionChanged = true;
        }
        
        if (threatCountChanged || directionChanged) {
            coverQualityPenalty = 0.2f;
        }
        
        lastThreatCount = currentThreatCount;
        lastPrimaryThreatDirection = currentPrimaryDirection;
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
    
    public void tickSuppression(boolean inCover) {
        suppressionTracker.tick(inCover);
    }
}
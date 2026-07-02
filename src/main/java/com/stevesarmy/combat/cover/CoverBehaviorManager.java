package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class CoverBehaviorManager {
    
    public enum CoverState {
        NO_COVER,
        SEEKING_COVER,
        IN_COVER,
        SUPPRESSED_IN_COVER,
        REPOSITIONING
    }
    
    public enum PeekState {
        HIDING,
        EXPOSED,
        DUCKING_BACK
    }
    
    private static final long EXPOSURE_TIME_MS = 1500;
    private static final long MIN_EXPOSURE_TIME_MS = 800;
    private static final long DUCK_COOLDOWN_MS = 1000;
    private static final long SUPPRESSED_HIDE_EXTRA_MS = 2000;
    
    private SoldierEntity soldier;
    private CoverState state = CoverState.NO_COVER;
    private CoverPoint currentCover = null;
    private CoverPoint targetCover = null;
    private CoverPoint lastCover = null;
    private long coverEntryTime = 0;
    private long seekingStartTime = 0;
    private long lastPeekTime = 0;
    private final SuppressionTracker suppressionTracker;
    
    private PeekState peekState = PeekState.HIDING;
    private BlockPos peekPosition = null;
    private long peekStartTime = 0;
    private long lastPeekEndTime = 0;
    private boolean nonPeekableCover = false;
    
    private float lastCoverQuality = 0.0f;
    private float coverQualityPenalty = 0.0f;
    private Vec3 entryThreatDirection = null;
    
    public CoverBehaviorManager(SoldierEntity soldier) {
        this.soldier = soldier;
        this.suppressionTracker = new SuppressionTracker();
    }
    
    private void syncState() {
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncCoverState(state.ordinal());
        }
    }
    
    private void syncCurrentCover() {
        if (soldier != null && !soldier.level().isClientSide) {
            if (currentCover != null) {
                soldier.syncCoverCurrent(
                    currentCover.getPosition(),
                    currentCover.getType().ordinal(),
                    currentCover.getQuality()
                );
            } else {
                soldier.syncCoverCurrent(BlockPos.ZERO, 0, 0f);
            }
        }
    }
    
    private void syncTargetCover() {
        if (soldier != null && !soldier.level().isClientSide) {
            if (targetCover != null) {
                soldier.syncCoverTarget(
                    targetCover.getPosition(),
                    targetCover.getType().ordinal(),
                    targetCover.getQuality()
                );
            } else {
                soldier.syncCoverTarget(BlockPos.ZERO, 0, 0f);
            }
        }
    }
    
    private void syncLastCover() {
        if (soldier != null && !soldier.level().isClientSide) {
            if (lastCover != null) {
                soldier.syncCoverLast(lastCover.getPosition());
            } else {
                soldier.syncCoverLast(BlockPos.ZERO);
            }
        }
    }
    
    private void syncSuppression() {
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncSuppressionLevel(suppressionTracker.getSuppressionLevel());
        }
    }
    
    public CoverState getState() {
        return state;
    }
    
    public void setState(CoverState state) {
        CoverState oldState = this.state;
        this.state = state;
        syncState();
        
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} state: {} -> {}", soldier.getId(), oldState, state);
        }
        
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
        CoverPoint oldCover = this.currentCover;
        this.currentCover = cover;
        syncCurrentCover();
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} currentCover: {} -> {}", 
                soldier.getId(), 
                oldCover != null ? oldCover.getPosition() + " type=" + oldCover.getType() : "null",
                cover != null ? cover.getPosition() + " type=" + cover.getType() + " score=" + String.format("%.2f", cover.getQuality()) : "null");
        }
        if (cover != null) {
            this.coverEntryTime = System.currentTimeMillis();
            this.lastCoverQuality = cover.getQuality();
            this.entryThreatDirection = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
        }
    }
    
    public CoverPoint getTargetCover() {
        return targetCover;
    }
    
    public void setTargetCover(CoverPoint cover) {
        CoverPoint oldTarget = this.targetCover;
        this.targetCover = cover;
        syncTargetCover();
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} targetCover: {} -> {}", 
                soldier.getId(),
                oldTarget != null ? oldTarget.getPosition().toString() : "null",
                cover != null ? cover.getPosition().toString() : "null");
        }
    }
    
    public CoverPoint getLastCover() {
        return lastCover;
    }
    
    public void setLastCover(CoverPoint cover) {
        this.lastCover = cover;
        syncLastCover();
    }
    
    public void clearCover() {
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} clearCover: current={}, state={}->NO_COVER", 
                soldier.getId(),
                currentCover != null ? currentCover.getPosition().toString() : "null",
                state);
        }
        if (currentCover != null) {
            CoverReservationManager.release(currentCover.getPosition(), null);
            this.lastCover = currentCover;
            syncLastCover();
        }
        this.currentCover = null;
        syncCurrentCover();
        this.coverEntryTime = 0;
        this.entryThreatDirection = null;
        this.state = CoverState.NO_COVER;
        syncState();
        soldier.refreshDimensions();
        GunIntegration.crawl(soldier, false);
    }
    
    public void clearTargetCover() {
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} clearTargetCover: target={}", 
                soldier.getId(),
                targetCover != null ? targetCover.getPosition().toString() : "null");
        }
        if (targetCover != null) {
            CoverReservationManager.release(targetCover.getPosition(), null);
        }
        this.targetCover = null;
        syncTargetCover();
    }
    
    public SuppressionTracker getSuppressionTracker() {
        return suppressionTracker;
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
    
    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier) {
        suppressionTracker.onNearMiss(bulletPath, soldier);
    }

    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier, float bulletSpeed) {
        suppressionTracker.onNearMiss(bulletPath, soldier, bulletSpeed);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter) {
        suppressionTracker.onIncomingFire(shooter);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter, float bulletSpeed) {
        suppressionTracker.onIncomingFire(shooter, bulletSpeed);
    }

    public void onTakeDamage() {
        suppressionTracker.onTakeDamage();
    }
    
    public void tickSuppression(boolean inCover) {
        suppressionTracker.tick(inCover);
        syncSuppression();
    }
    
    private boolean debugLog() {
        return soldier != null && com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled();
    }
    
    private void syncPeekState() {
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekState(peekState.ordinal());
        }
    }
    
    private void syncPeekPosition() {
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekPosition(peekPosition != null ? peekPosition : BlockPos.ZERO);
        }
    }
    
    public PeekState getPeekState() {
        return peekState;
    }
    
    public void setPeekState(PeekState state) {
        this.peekState = state;
        this.peekStartTime = System.currentTimeMillis();
        syncPeekState();
    }
    
    public BlockPos getPeekPosition() {
        return peekPosition;
    }
    
    public void setPeekPosition(BlockPos pos) {
        this.peekPosition = pos;
        syncPeekPosition();
    }
    
    public long getPeekStartTime() {
        return peekStartTime;
    }
    
    public long getLastPeekEndTime() {
        return lastPeekEndTime;
    }
    
    public void setLastPeekEndTime(long time) {
        this.lastPeekEndTime = time;
    }
    
    public long getTimeSinceLastPeek() {
        if (lastPeekEndTime == 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastPeekEndTime;
    }
    
    public long getTimeInCurrentPeekState() {
        if (peekStartTime == 0) return 0;
        return System.currentTimeMillis() - peekStartTime;
    }
    
    public void resetPeekState() {
        this.peekState = PeekState.HIDING;
        this.peekStartTime = 0;
        syncPeekState();
    }
    
    public boolean isNonPeekableCover() {
        return nonPeekableCover;
    }
    
    public void setNonPeekableCover(boolean nonPeekable) {
        this.nonPeekableCover = nonPeekable;
    }
    
    public static long getExposureTimeMs() {
        return EXPOSURE_TIME_MS;
    }
    
    public static long getMinExposureTimeMs() {
        return MIN_EXPOSURE_TIME_MS;
    }
    
    public static long getDuckCooldownMs() {
        return DUCK_COOLDOWN_MS;
    }
    
    public static long getSuppressedHideExtraMs() {
        return SUPPRESSED_HIDE_EXTRA_MS;
    }
    
    public boolean hasCurrentCover() {
        return currentCover != null;
    }

    public Vec3 getEntryThreatDirection() {
        return entryThreatDirection;
    }
}
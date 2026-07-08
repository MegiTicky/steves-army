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
    
    private static final int PEEK_COUNT_PENALTY_THRESHOLD = 4;
    private static final float MAX_COVER_PENALTY = 0.60f;
    
    private SoldierEntity soldier;
    private CoverState state = CoverState.NO_COVER;
    private CoverPoint currentCover = null;
    private CoverPoint targetCover = null;
    private CoverPoint lastCover = null;
    private long coverEntryTime = 0;
    private long seekingStartTime = 0;
    private long lastPeekTime = 0;
    private final SuppressionTracker suppressionTracker;
    
    private BlockPos peekPosition = null;
    private long peekStartTime = 0;
    private long lastPeekEndTime = 0;
    private boolean nonPeekableCover = false;
    
    private float lastCoverQuality = 0.0f;
    private float coverQualityPenalty = 0.0f;
    private Vec3 entryThreatDirection = null;
    private Vec3 coverFacingDirection = null;
    
    private int peekCountSameCover = 0;
    private int savedPeekCount = 0;
    private BlockPos savedCoverPosition = null;
    private boolean repositionRequested = false;
    
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
                    currentCover.getCombatScore()
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
                    targetCover.getCombatScore()
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
    
    private void syncPeekPosition() {
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekPosition(peekPosition != null ? peekPosition : BlockPos.ZERO);
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
                cover != null ? cover.getPosition() + " type=" + cover.getType() + " combatScore=" + String.format("%.2f", cover.getCombatScore()) + " quality=" + String.format("%.2f", cover.getQuality()) : "null");
        }
        if (cover != null) {
            this.coverEntryTime = System.currentTimeMillis();
            this.lastCoverQuality = cover.getCombatScore();
            this.entryThreatDirection = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
            
            setCoverFacingDirectionFromCover(cover.getProtectedDirections());
            
            boolean samePosition = (oldCover != null && cover.getPosition().equals(oldCover.getPosition()))
                || (oldCover == null && savedCoverPosition != null && cover.getPosition().equals(savedCoverPosition));
            if (samePosition) {
                this.peekCountSameCover = Math.max(this.peekCountSameCover, this.savedPeekCount);
                if (debugLog()) {
                    StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} re-entered same cover, peek count restored to {}", soldier.getId(), peekCountSameCover);
                }
            } else {
                this.peekCountSameCover = 0;
                if (debugLog()) {
                    StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} new cover, peek count reset from {} to 0", soldier.getId(), savedPeekCount);
                }
            }
            this.savedPeekCount = 0;
            this.savedCoverPosition = null;
        } else {
            this.coverFacingDirection = null;
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
    
    public void setCoverFacingDirection(Vec3 direction) {
        this.coverFacingDirection = direction;
    }
    
    public void setCoverFacingDirectionFromCover(java.util.Set<Direction> protectedDirs) {
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            this.coverFacingDirection = null;
            return;
        }
        
        Direction wallDir = protectedDirs.iterator().next();
        Vec3 threatDir = new Vec3(wallDir.getOpposite().getStepX(), 0, wallDir.getOpposite().getStepZ()).normalize();
        this.coverFacingDirection = threatDir;
        
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Cover facing direction from wall {}: ({}, {}, {})",
                wallDir, 
                String.format("%.2f", threatDir.x),
                String.format("%.2f", threatDir.y),
                String.format("%.2f", threatDir.z));
        }
    }
    
    public void clearCoverFacingDirection() {
        this.coverFacingDirection = null;
    }
    
    public Vec3 getCoverFacingDirection() {
        return coverFacingDirection;
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
        this.savedCoverPosition = currentCover != null ? currentCover.getPosition() : null;
        this.currentCover = null;
        syncCurrentCover();
        this.coverEntryTime = 0;
        this.entryThreatDirection = null;
        this.coverFacingDirection = null;
        this.savedPeekCount = this.peekCountSameCover;
        this.peekCountSameCover = 0;
        this.state = CoverState.NO_COVER;
        syncState();
        
        soldier.getPeekController().reset();
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
    
    public void recordPeekCycle() {
        peekCountSameCover++;
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} recordPeekCycle: count={}", soldier.getId(), peekCountSameCover);
        }
    }
    
    public int getPeekCountSameCover() {
        return peekCountSameCover;
    }
    
    public float getRecentCoverPenalty() {
        if (peekCountSameCover < PEEK_COUNT_PENALTY_THRESHOLD) return 0.0f;
        int extraPeeks = peekCountSameCover - PEEK_COUNT_PENALTY_THRESHOLD + 1;
        return Math.min(MAX_COVER_PENALTY, extraPeeks * 0.15f);
    }
    
    public float getLastCoverQuality() {
        return lastCoverQuality;
    }
    
    public float getCoverQualityPenalty() {
        return getRecentCoverPenalty();
    }

    public void clearCoverQualityPenalty() {
        coverQualityPenalty = 0.0f;
        peekCountSameCover = 0;
    }
    
    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier) {
        onNearMiss(bulletPath, soldier, 1.0f, null);
    }

    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier, float bulletSpeed) {
        onNearMiss(bulletPath, soldier, bulletSpeed, null);
    }

    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier, float bulletSpeed, @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity shooter) {
        if (shooter != null && soldier instanceof com.stevesarmy.entity.SoldierEntity s && s.isFriendlyTo(shooter)) {
            return;
        }
        suppressionTracker.onNearMiss(bulletPath, soldier, bulletSpeed);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter) {
        if (soldier.isFriendlyTo(shooter)) {
            return;
        }
        suppressionTracker.onIncomingFire(shooter);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter, float bulletSpeed) {
        if (soldier.isFriendlyTo(shooter)) {
            return;
        }
        suppressionTracker.onIncomingFire(shooter, bulletSpeed);
    }

    public void onTakeDamage() {
        onTakeDamage(null);
    }

    public void onTakeDamage(@javax.annotation.Nullable net.minecraft.world.entity.LivingEntity attacker) {
        if (attacker != null && soldier.isFriendlyTo(attacker)) {
            return;
        }
        suppressionTracker.onTakeDamage();
    }
    
    public void tickSuppression(boolean inCover) {
        suppressionTracker.tick(inCover);
        syncSuppression();
    }
    
    private boolean debugLog() {
        return soldier != null && com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled();
    }
    
    // --- Peek position storage (used by PeekController) ---
    
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
        this.peekStartTime = 0;
    }
    
    public boolean isNonPeekableCover() {
        return nonPeekableCover;
    }
    
    public void setNonPeekableCover(boolean nonPeekable) {
        this.nonPeekableCover = nonPeekable;
    }
    
    public boolean isRepositionRequested() {
        return repositionRequested;
    }
    
    public void requestReposition() {
        this.repositionRequested = true;
    }
    
    public void clearRepositionRequest() {
        this.repositionRequested = false;
    }
    
    private boolean shotInCoverRepositionRequested = false;
    
    public boolean isShotInCoverRepositionRequested() {
        return shotInCoverRepositionRequested;
    }
    
    public void requestShotInCoverReposition() {
        this.shotInCoverRepositionRequested = true;
    }
    
    public void clearShotInCoverRepositionRequest() {
        this.shotInCoverRepositionRequested = false;
    }
    
    public boolean hasCurrentCover() {
        return currentCover != null;
    }

    public Vec3 getEntryThreatDirection() {
        return entryThreatDirection;
    }
}
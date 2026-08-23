package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.debug.PerformanceMetrics;
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
    private Vec3 recentSuppressionFiringOrigin = null;
    private long recentSuppressionFiringOriginTime = 0L;

    private static final long SUPPRESSION_FIRING_ORIGIN_MEMORY_MS = 15000L;
    private static final float PROTECTED_MG_NEAR_MISS_MULTIPLIER = 0.50f;
    private static final float PROTECTED_MG_DIRECT_FIRE_MULTIPLIER = 0.65f;
    private static final float PROTECTED_MG_RECOVERY_MULTIPLIER = 1.25f;
    private static final float PROTECTED_MG_PEAK_SLOWDOWN_MULTIPLIER = 0.75f;
    
    private int peekCountSameCover = 0;
    private int savedPeekCount = 0;
    private BlockPos savedCoverPosition = null;
    private boolean repositionRequested = false;
    private long tacticalRevision = 0L;
    private boolean protectedMachineGunnerPolicy;
    
    private float lastSyncedSuppression = -1f;
    private static final float SUPPRESSION_SYNC_THRESHOLD = 0.5f;
    private static final float SUPPRESSION_SYNC_DELTA = 0.02f;
    
    public CoverBehaviorManager(SoldierEntity soldier) {
        this.soldier = soldier;
        this.suppressionTracker = new SuppressionTracker();
    }

    /** Set once by a role-specific entity; never inferred in the tick path. */
    public void setProtectedMachineGunnerPolicy(boolean enabled) {
        this.protectedMachineGunnerPolicy = enabled;
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
                    currentCover.getCombatScore(),
                    currentCover.getCoverHeight()
                );
            } else {
                soldier.syncCoverCurrent(BlockPos.ZERO, 0, 0f, 0f);
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
            soldier.syncSuppressionEventSequence((int) Math.min(Integer.MAX_VALUE,
                suppressionTracker.getSuppressionEventSequence()));
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

    public long getTacticalRevision() {
        return tacticalRevision;
    }

    /** Marks a tactical change that can invalidate passive cover maintenance. */
    public void markTacticalChange(String reason) {
        tacticalRevision++;
        PerformanceMetrics.recordCoverInvalidation(reason);
    }

    private static boolean sameCover(CoverPoint first, CoverPoint second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.getPosition().equals(second.getPosition())
            && first.getType() == second.getType();
    }
    
    public void setState(CoverState state) {
        CoverState oldState = this.state;
        boolean changed = oldState != state;
        this.state = state;
        if (changed) {
            markTacticalChange("state_change");
        }
        syncState();
        
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} state: {} -> {}", soldier.getId(), oldState, state);
        }
        
        if (state == CoverState.SEEKING_COVER || state == CoverState.REPOSITIONING) {
            this.seekingStartTime = System.currentTimeMillis();
        }
        
        // Only set coverEntryTime when entering a cover state from a non-cover state.
        // Transitions between IN_COVER and SUPPRESSED_IN_COVER preserve the physical
        // cover-entry time so suppression/recovery cycles don't reset the dwell clock.
        if ((state == CoverState.IN_COVER || state == CoverState.SUPPRESSED_IN_COVER)) {
            if (oldState != CoverState.IN_COVER && oldState != CoverState.SUPPRESSED_IN_COVER) {
                this.coverEntryTime = System.currentTimeMillis();
            }
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
        boolean changed = !sameCover(oldCover, cover);
        this.currentCover = cover;
        if (changed) {
            markTacticalChange(cover == null ? "cover_invalidated" : "current_cover_changed");
        }
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
        boolean changed = !sameCover(oldTarget, cover);
        this.targetCover = cover;
        if (changed) {
            markTacticalChange(cover == null ? "target_cover_cleared" : "target_cover_changed");
        }
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

        boolean changed = currentCover != null || targetCover != null || state != CoverState.NO_COVER;
        if (changed) {
            markTacticalChange("cover_invalidated");
        }
        if (currentCover != null) {
            CoverReservationManager.release(currentCover.getPosition(), soldier);
            this.lastCover = currentCover;
            syncLastCover();
        }
        if (targetCover != null) {
            CoverReservationManager.release(targetCover.getPosition(), soldier);
            this.targetCover = null;
            syncTargetCover();
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

        this.continuousSuppressionRepositionRequested = false;

        soldier.getPeekController().reset();
        // Preserve suppression during cover transitions - don't reset
        soldier.setLowCrouching(false);
        soldier.cancelHalfCoverRise();
        soldier.refreshDimensions();
        soldier.setPose(net.minecraft.world.entity.Pose.STANDING);
        soldier.refreshDimensions();
    }
    
    public void clearTargetCover() {
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} clearTargetCover: target={}", 
                soldier.getId(),
                targetCover != null ? targetCover.getPosition().toString() : "null");
        }
        if (this.targetCover != null) {
            markTacticalChange("target_cover_cleared");
        }
        this.targetCover = null;
        syncTargetCover();
    }
    
    /**
     * Promote the target cover to current cover.
     * Releases the old cover reservation, keeps the new one, initializes
     * all current-cover metadata via setCurrentCover, and sets lastCover.
     */
    public void promoteTargetToCurrentCover() {
        if (targetCover == null) return;
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} promoteTargetToCurrentCover: {} -> {}",
                soldier.getId(),
                currentCover != null ? currentCover.getPosition().toString() : "null",
                targetCover.getPosition().toString());
        }
        // Release old current cover reservation
        if (currentCover != null) {
            CoverReservationManager.release(currentCover.getPosition(), soldier);
            this.lastCover = currentCover;
            syncLastCover();
        }
        // Set new current cover — this initializes all metadata (entryTime,
        // threatDirection, facingDirection, peekCounts) via setCurrentCover
        setCurrentCover(targetCover);
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
        onNearMiss(bulletPath, soldier, bulletSpeed, shooter, null);
    }

    public void onNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier,
                           float bulletSpeed, @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity shooter,
                           @javax.annotation.Nullable Vec3 firingOrigin) {
        if (shooter != null && soldier instanceof com.stevesarmy.entity.SoldierEntity s && s.isFriendlyTo(shooter)) {
            return;
        }
        recordSuppressionFiringOrigin(firingOrigin);
        suppressionTracker.onNearMiss(bulletPath, soldier, bulletSpeed, shooter,
            isProtectedMachineGunner() ? PROTECTED_MG_NEAR_MISS_MULTIPLIER : 1.0f);
    }

    /**
     * CBC near-miss: always drives suppression to 1.0.
     */
    public void onCbcNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier, @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity shooter) {
        onCbcNearMiss(bulletPath, soldier, shooter, null);
    }

    public void onCbcNearMiss(net.minecraft.world.phys.Vec3 bulletPath, net.minecraft.world.entity.LivingEntity soldier,
                              @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity shooter,
                              @javax.annotation.Nullable Vec3 firingOrigin) {
        if (shooter != null && soldier instanceof com.stevesarmy.entity.SoldierEntity s && s.isFriendlyTo(shooter)) {
            return;
        }
        suppressionTracker.onCbcNearMiss(soldier);
        recordSuppressionFiringOrigin(firingOrigin);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter) {
        if (soldier.isFriendlyTo(shooter)) {
            return;
        }
        recordSuppressionFiringOrigin(shooter.getEyePosition());
        suppressionTracker.onIncomingFire(shooter, 1.0f,
            isProtectedMachineGunner() ? PROTECTED_MG_DIRECT_FIRE_MULTIPLIER : 1.0f);
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter, float bulletSpeed) {
        if (soldier.isFriendlyTo(shooter)) {
            return;
        }
        recordSuppressionFiringOrigin(shooter.getEyePosition());
        suppressionTracker.onIncomingFire(shooter, bulletSpeed,
            isProtectedMachineGunner() ? PROTECTED_MG_DIRECT_FIRE_MULTIPLIER : 1.0f);
    }

    public void onTakeDamage() {
        onTakeDamage(null);
    }

    public void onTakeDamage(@javax.annotation.Nullable net.minecraft.world.entity.LivingEntity attacker) {
        if (attacker != null && soldier.isFriendlyTo(attacker)) {
            return;
        }
        suppressionTracker.onTakeDamage();
        if (attacker != null) {
            recordSuppressionFiringOrigin(attacker.getEyePosition());
        }
    }

    @javax.annotation.Nullable
    public Vec3 getRecentSuppressionFiringOrigin() {
        if (recentSuppressionFiringOrigin == null
            || System.currentTimeMillis() - recentSuppressionFiringOriginTime > SUPPRESSION_FIRING_ORIGIN_MEMORY_MS) {
            return null;
        }
        return recentSuppressionFiringOrigin;
    }

    private void recordSuppressionFiringOrigin(@javax.annotation.Nullable Vec3 firingOrigin) {
        if (firingOrigin != null) {
            recentSuppressionFiringOrigin = firingOrigin;
            recentSuppressionFiringOriginTime = System.currentTimeMillis();
        }
    }

    public void onExplosion(Vec3 explosionPosition, float exposure) {
        float previousLevel = suppressionTracker.getSuppressionLevel();
        suppressionTracker.onExplosion(explosionPosition, soldier.position(), exposure);

        float currentLevel = suppressionTracker.getSuppressionLevel();
        if (currentLevel == previousLevel) {
            return;
        }

        // Explosions are event-driven, so do not wait for CoverTacticalGoal to run
        // before the client and an already-covered soldier see the suppression state.
        syncSuppression();
        lastSyncedSuppression = currentLevel;

        if (suppressionDebugLog()) {
            StevesArmyMod.LOGGER.info("[ExplosionSuppression] soldier={} synced {} -> {}, suppressed={}, coverState={}, hasCover={}",
                soldier.getId(), String.format("%.2f", previousLevel), String.format("%.2f", currentLevel),
                suppressionTracker.isSuppressed(), state, currentCover != null);
        }

        if (suppressionTracker.isSuppressed() && currentCover != null) {
            if (currentCover.getType() == CoverType.HALF) {
                if (!soldier.hasEmergencyEngagementPosture()) {
                    soldier.setLowCrouching(true);
                }
            }
            setState(CoverState.SUPPRESSED_IN_COVER);
            if (suppressionDebugLog()) {
                StevesArmyMod.LOGGER.info("[ExplosionSuppression] soldier={} entered SUPPRESSED_IN_COVER", soldier.getId());
            }
        }
    }
    
    public void tickSuppression(boolean inCover) {
        boolean protectedMachineGunner = inCover && isProtectedMachineGunner();
        suppressionTracker.tick(inCover,
            protectedMachineGunner ? PROTECTED_MG_RECOVERY_MULTIPLIER : 1.0f,
            protectedMachineGunner ? PROTECTED_MG_PEAK_SLOWDOWN_MULTIPLIER : 1.0f);

        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncSuppressionEventSequence((int) Math.min(Integer.MAX_VALUE,
                suppressionTracker.getSuppressionEventSequence()));
        }
        
        float currentLevel = suppressionTracker.getSuppressionLevel();
        boolean crossedThreshold = (lastSyncedSuppression >= SUPPRESSION_SYNC_THRESHOLD) != (currentLevel >= SUPPRESSION_SYNC_THRESHOLD);
        float delta = Math.abs(currentLevel - lastSyncedSuppression);
        
        if (crossedThreshold || delta > SUPPRESSION_SYNC_DELTA || lastSyncedSuppression < 0) {
            syncSuppression();
            lastSyncedSuppression = currentLevel;
        }
    }
    
    private boolean debugLog() {
        return soldier != null && com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled();
    }

    private boolean suppressionDebugLog() {
        return soldier != null && com.stevesarmy.debug.DiagnosticLogManager.isSuppressionLoggingEnabled();
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
        if (!this.repositionRequested) {
            this.repositionRequested = true;
            markTacticalChange("reposition_requested");
        }
    }
    
    public void clearRepositionRequest() {
        if (this.repositionRequested) {
            this.repositionRequested = false;
            markTacticalChange("reposition_request_cleared");
        }
    }
    
    private boolean shotInCoverRepositionRequested = false;
    
    public boolean isShotInCoverRepositionRequested() {
        return shotInCoverRepositionRequested;
    }
    
    public void requestShotInCoverReposition() {
        if (!this.shotInCoverRepositionRequested) {
            this.shotInCoverRepositionRequested = true;
            markTacticalChange("damage_cover_invalidation");
        }
    }
    
    public void clearShotInCoverRepositionRequest() {
        if (this.shotInCoverRepositionRequested) {
            this.shotInCoverRepositionRequested = false;
            markTacticalChange("damage_reposition_cleared");
        }
    }

    private boolean continuousSuppressionRepositionRequested = false;

    public boolean isContinuousSuppressionRepositionRequested() {
        return continuousSuppressionRepositionRequested;
    }

    public void requestContinuousSuppressionReposition() {
        if (!this.continuousSuppressionRepositionRequested) {
            this.continuousSuppressionRepositionRequested = true;
            markTacticalChange("suppression_reposition_requested");
        }
    }

    public void clearContinuousSuppressionRepositionRequest() {
        if (this.continuousSuppressionRepositionRequested) {
            this.continuousSuppressionRepositionRequested = false;
            markTacticalChange("suppression_reposition_cleared");
        }
    }
    
    public boolean hasCurrentCover() {
        return currentCover != null;
    }

    /** True when recent fire came from a direction this dedicated MG's cover protects. */
    public boolean isProtectedMachineGunner() {
        if (!protectedMachineGunnerPolicy
            || currentCover == null
            || !isInCover()) {
            return false;
        }
        Vec3 firingOrigin = getRecentSuppressionFiringOrigin();
        if (firingOrigin == null) return false;
        Vec3 direction = firingOrigin.subtract(currentCover.getPosition().getCenter());
        return !direction.equals(Vec3.ZERO)
            && new CoverFinder(soldier.level()).isDirectionProtected(currentCover, direction);
    }

    public Vec3 getEntryThreatDirection() {
        return entryThreatDirection;
    }
}

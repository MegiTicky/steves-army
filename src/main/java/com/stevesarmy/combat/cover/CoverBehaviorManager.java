package com.stevesarmy.combat.cover;

import com.stevesarmy.GameClock;
import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

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
    /** "Physically in this cover" radius for keeping the reservation on clearCover (matches the manager's sweep). */
    private static final double OCCUPIED_COVER_RADIUS_SQ = 6.25;
    
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

    // Suppressed-traveler doctrine (dangerTravelResponse): see handleSuppressedTravel.
    /** Squad intel report cadence for near-miss shooters (one per sustained burst). */
    private static final int TRAVEL_INTEL_FEED_INTERVAL_TICKS = 40;
    private boolean travelReroutePending = false;
    private int travelRerouteCooldownUntilTick = 0;
    private int travelReroutesServed = 0;
    /** Not Long.MIN_VALUE: (tickCount - MIN_VALUE) overflows and silently disables the feed. */
    private int lastTravelIntelFeedTick = -TRAVEL_INTEL_FEED_INTERVAL_TICKS;
    /** A GO_TO ping relocation is in flight (set/cleared by CoverTacticalGoal). */
    private boolean goToRelocationActive = false;
    
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

    /** Bounds-safe ordinal decode for client renderers. Returns NO_COVER for unknown ordinals. */
    public static CoverState stateFromOrdinal(int ordinal) {
        CoverState[] values = CoverState.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : CoverState.NO_COVER;
    }

    /** Revision used by the isolated machine-gunner support evaluator. */
    public long getTacticalRevision() {
        return tacticalRevision;
    }
    
    public void setState(CoverState state) {
        CoverState oldState = this.state;
        this.state = state;
        if (oldState != state && soldier != null && SoldierFlightRecorder.isRecording()) {
            SoldierFlightRecorder.event(soldier, "cover_state", "setState", oldState.name() + " -> " + state.name());
        }
        syncState();
        
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} state: {} -> {}", soldier.getId(), oldState, state);
        }
        
        if (state == CoverState.SEEKING_COVER || state == CoverState.REPOSITIONING) {
            this.seekingStartTime = GameClock.nowMs();
        }
        
        // Only set coverEntryTime when entering a cover state from a non-cover state.
        // Transitions between IN_COVER and SUPPRESSED_IN_COVER preserve the physical
        // cover-entry time so suppression/recovery cycles don't reset the dwell clock.
        if ((state == CoverState.IN_COVER || state == CoverState.SUPPRESSED_IN_COVER)) {
            if (oldState != CoverState.IN_COVER && oldState != CoverState.SUPPRESSED_IN_COVER) {
                this.coverEntryTime = GameClock.nowMs();
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
        this.currentCover = cover;
        syncCurrentCover();
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} currentCover: {} -> {}", 
                soldier.getId(), 
                oldCover != null ? oldCover.getPosition() + " type=" + oldCover.getType() : "null",
                cover != null ? cover.getPosition() + " type=" + cover.getType() + " combatScore=" + String.format("%.2f", cover.getCombatScore()) + " quality=" + String.format("%.2f", cover.getQuality()) : "null");
        }
        if (cover != null) {
            this.coverEntryTime = GameClock.nowMs();
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

        // Releasing the claim of a soldier still physically standing at the
        // spot lets the next searcher (any role) reserve it and walk in. Keep
        // the claim instead: the reservation manager's occupancy sweep keeps
        // it alive while they stand there and frees it once they leave.
        if (currentCover != null) {
            if (!isPhysicallyOccupying(currentCover)) {
                CoverReservationManager.release(currentCover.getPosition(), soldier);
            }
            this.lastCover = currentCover;
            syncLastCover();
        }
        if (targetCover != null) {
            if (!isPhysicallyOccupying(targetCover)) {
                CoverReservationManager.release(targetCover.getPosition(), soldier);
            }
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
        // Route through setState so any future invariant/cleanup logic applies
        setState(CoverState.NO_COVER);

        // All reposition latches are tied to the abandoned cover. Stale requests
        // must never survive into the next cover, where they would blacklist a
        // fresh position on its first tick.
        this.continuousSuppressionRepositionRequested = false;
        this.repositionRequested = false;
        this.shotInCoverRepositionRequested = false;

        soldier.getPeekController().resetToInactive(soldier);
        // Preserve suppression during cover transitions - don't reset
        soldier.setLowCrouching(false);
        soldier.cancelHalfCoverRise();
        soldier.refreshDimensions();
        soldier.setPose(net.minecraft.world.entity.Pose.STANDING);
        soldier.refreshDimensions();
    }
    
    private boolean isPhysicallyOccupying(CoverPoint cover) {
        return soldier != null && cover != null
            && soldier.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(cover.getPosition()))
                <= OCCUPIED_COVER_RADIUS_SQ;
    }

    public void clearTargetCover() {
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[CoverBehaviorManager] Soldier {} clearTargetCover: target={}", 
                soldier.getId(),
                targetCover != null ? targetCover.getPosition().toString() : "null");
        }
        if (targetCover != null) {
            CoverReservationManager.release(targetCover.getPosition(), soldier);
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
        return GameClock.nowMs() - coverEntryTime;
    }
    
    public long getTimeSeeking() {
        if (seekingStartTime == 0) return 0;
        return GameClock.nowMs() - seekingStartTime;
    }
    
    public long getLastPeekTime() {
        return lastPeekTime;
    }
    
    public void onPeekShot() {
        this.lastPeekTime = GameClock.nowMs();
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
        // Travel suppression resistance: a moving soldier is a harder target to
        // suppress, so the pressured band (re-route / no-cover-dive doctrine)
        // survives sustained real fire instead of being blown through in ~0.3 s.
        float gainScale = 1.0f;
        if (StevesArmyConfig.isDangerTravelResponseEnabled() && isActivelyTraveling(soldier)) {
            gainScale = (float) StevesArmyConfig.getDangerTravelSuppressionGain();
        }
        suppressionTracker.onNearMiss(bulletPath, soldier, bulletSpeed, shooter, gainScale);
        recordSuppressionFiringOrigin(firingOrigin);
        handleSuppressedTravel(soldier, shooter, firingOrigin);
    }

    /** True while the soldier is executing a long move: hold-march or GO_TO ping relocation. */
    private boolean isActivelyTraveling(net.minecraft.world.entity.LivingEntity subject) {
        if (!(subject instanceof SoldierEntity se)) return false;
        if (goToRelocationActive) return true;
        return com.stevesarmy.entity.ai.SoldierHoldPositionGoal.isTravelingToObjective(se);
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
        suppressionTracker.onIncomingFire(shooter);
        recordSuppressionFiringOrigin(shooter.getEyePosition());
    }

    public void onIncomingFire(net.minecraft.world.entity.LivingEntity shooter, float bulletSpeed) {
        if (soldier.isFriendlyTo(shooter)) {
            return;
        }
        suppressionTracker.onIncomingFire(shooter, bulletSpeed);
        recordSuppressionFiringOrigin(shooter.getEyePosition());
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
            || GameClock.nowMs() - recentSuppressionFiringOriginTime > SUPPRESSION_FIRING_ORIGIN_MEMORY_MS) {
            return null;
        }
        return recentSuppressionFiringOrigin;
    }

    private void recordSuppressionFiringOrigin(@javax.annotation.Nullable Vec3 firingOrigin) {
        if (firingOrigin != null) {
            recentSuppressionFiringOrigin = firingOrigin;
            recentSuppressionFiringOriginTime = GameClock.nowMs();
        }
    }

    /**
     * Suppressed-traveler doctrine (dangerTravelResponse): near-misses against a
     * soldier who is marching in the open do two things beyond personal
     * suppression - they report the shooter to squad intel so the danger map
     * bakes the new shadow, and they raise a travel-reroute flag that
     * SoldierHoldPositionGoal consumes to recompute the march path around it.
     * Cover-seek itself keeps waiting until pinned (CoverTacticalGoal decides).
     */
    private void handleSuppressedTravel(net.minecraft.world.entity.LivingEntity suppressed,
                                        @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity shooter,
                                        @javax.annotation.Nullable Vec3 firingOrigin) {
        if (!StevesArmyConfig.isDangerTravelResponseEnabled()) return;
        if (!(suppressed instanceof SoldierEntity traveler)) return;
        if (!(traveler.level() instanceof ServerLevel serverLevel)) return;
        UUID squadId = traveler.getSquadId();
        if (squadId == null) return;

        int now = traveler.tickCount;

        // 1. Intel feed: the squad learns where fire is coming from even though
        //    nobody has spotted the shooter. Rate-limited to one report per
        //    burst-sized window; ThreatKnowledge dedupes by threat id.
        if (now - lastTravelIntelFeedTick >= TRAVEL_INTEL_FEED_INTERVAL_TICKS
                && (shooter != null || firingOrigin != null)) {
            lastTravelIntelFeedTick = now;
            UUID threatId;
            BlockPos reportPos;
            if (shooter != null) {
                threatId = shooter.getUUID();
                // Eye position, not block center: a shooter hugging low cover
                // has his block center below the obstacle line, and bakes from
                // there read every cell as safe. Rays must start at eye height.
                reportPos = BlockPos.containing(shooter.getEyePosition());
            } else {
                // Unknown shooter: key the threat by the rounded firing origin so
                // consecutive shots from the same spot stay one shadow.
                reportPos = BlockPos.containing(firingOrigin);
                threatId = UUID.nameUUIDFromBytes(
                    ("sa-suppression|" + reportPos.getX() + "," + reportPos.getY() + "," + reportPos.getZ())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            SquadThreatIntel intel = SquadManager.get(serverLevel).getSquadById(squadId)
                .map(SquadData::getThreatIntel).orElse(null);
            if (intel != null) {
                intel.reportThreatPosition(traveler.getUUID(), threatId, reportPos,
                    0.5f, serverLevel);
            }
        }

        // 2. Reroute request: only while actually marching (no cover claim, or a
        //    GO_TO ping relocation in flight) and pressured but not yet pinned -
        //    pinned hands control back to the cover goal. The cooldown keeps
        //    sustained fire from re-pathing on every individual miss.
        if (now < travelRerouteCooldownUntilTick) return;
        float suppression = suppressionTracker.getSuppressionLevel();
        if (suppression < SuppressionTracker.PRESSURED_THRESHOLD
            || suppression >= SuppressionTracker.PINNED_THRESHOLD) {
            return;
        }
        if (state != CoverState.NO_COVER && !goToRelocationActive) return;
        if (traveler.getNavigation().isDone()) return;
        travelReroutePending = true;
        travelRerouteCooldownUntilTick = now + StevesArmyConfig.getDangerTravelRerouteCooldownTicks();
    }

    /** Current personal suppression level (0..1) for travel-posture decisions. */
    public float getSuppressionLevel() {
        return suppressionTracker.getSuppressionLevel();
    }

    /** CoverTacticalGoal marks GO_TO ping relocations so travel logic can see them. */
    public void setGoToRelocationActive(boolean active) {
        this.goToRelocationActive = active;
    }

    public boolean isGoToRelocationActive() {
        return goToRelocationActive;
    }

    /** One-shot: a marching soldier under fire asked the hold goal to re-route. */
    public boolean consumeTravelRerouteRequest() {
        boolean pending = travelReroutePending;
        travelReroutePending = false;
        if (pending) {
            travelReroutesServed++;
        }
        return pending;
    }

    /** Sim/debug telemetry: how many travel re-routes this soldier has served. */
    public int getTravelReroutesServed() {
        return travelReroutesServed;
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
        suppressionTracker.tick(inCover);

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
        return GameClock.nowMs() - lastPeekEndTime;
    }
    
    public long getTimeInCurrentPeekState() {
        if (peekStartTime == 0) return 0;
        return GameClock.nowMs() - peekStartTime;
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

    private boolean continuousSuppressionRepositionRequested = false;

    public boolean isContinuousSuppressionRepositionRequested() {
        return continuousSuppressionRepositionRequested;
    }

    public void requestContinuousSuppressionReposition() {
        this.continuousSuppressionRepositionRequested = true;
    }

    public void clearContinuousSuppressionRepositionRequest() {
        this.continuousSuppressionRepositionRequested = false;
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

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.AimAccuracyManager;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.FriendlyFireChecker;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.ThreatTracker;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PotentialTargetsDebugMessage;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import com.stevesarmy.squad.SuppressireAssignmentManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SoldierCombatGoal extends Goal {
    private final SoldierEntity soldier;
    private final ThreatTracker threatTracker;
    private final DetectionSystem detectionSystem;
    private LivingEntity target;
    
    private boolean gunInitialized = false;
    private ItemStack lastGunStack = ItemStack.EMPTY;
    private boolean wasAiming = false;
    private boolean wasReloading = false;
    
    private float aimQuality = 0.0f;
    private UUID trackedTargetUUID = null;
    private boolean lastShotNeededBolt = false;
    private ExposureCalculator.AimPointResult currentAimPoint = null;
    
    private static final float ADS_THRESHOLD = 0.8f;
    private static final int PATH_BLOCKED_SWITCH_TICKS = 40;
    private static final int CQB_PATH_BLOCKED_SWITCH_TICKS = 10;
    private int pathBlockedCounter = 0;
    private int debugSyncTickCounter = 0;
    private static final int DEBUG_SYNC_INTERVAL = 20;
    private int targetReevaluateCounter = 0;
    
    private int findNewTargetLogCounter = 0;

    private boolean isSuppressing = false;
    private boolean reloadPending = false;
    private boolean tacticalReloadPending = false;
    private boolean reloadStartRequested = false;
    private int reloadRetryTicks = 0;
    private BlockPos suppressionTargetPos = null;
    private UUID suppressionTargetUUID = null;
    private SquadThreatIntel.ThreatKnowledge pendingSuppressionThreat = null;
    private static final float SUPPRESSION_ADS_THRESHOLD = 0.5f;
    private static final double SUPPRESSION_MAX_RANGE = 128.0;
    private static final long STALE_TIMEOUT_TICKS = 60;
    private static final int SUPPRESSION_MIN_DURATION_TICKS = 100;  // 5 seconds
    private static final int SUPPRESSION_MAX_DURATION_TICKS = 160;  // 8 seconds
    private static final double SUPPRESSION_LOS_TOLERANCE = 2.0;  // blocks
    private static final double SUPPRESSION_SPREAD_MIN_RADIUS = 0.12;
    private static final double SUPPRESSION_SPREAD_PER_BLOCK = 0.0075;
    private static final double SUPPRESSION_SPREAD_MAX_RADIUS = 0.85;
    private static final double SUPPRESSION_VERTICAL_SPREAD_RATIO = 0.45;
    private static final float PRONE_FIRING_ARC_DEGREES = 30.0f;
    private static final float FIRING_ALIGNMENT_DEGREES = 7.0f;
    private static final float TURN_RATE_DEGREES = 30.0f;
    private static final double EMERGENCY_FLANK_DISTANCE = 6.0;
    private int suppressionDurationTicks = 0;
    private int suppressionRemainingTicks = 0;

    private enum EngagementPostureState {
        READY,
        EXITING_LOW_CROUCH,
        ROTATING
    }

    private EngagementPostureState engagementPostureState = EngagementPostureState.READY;
    
    private boolean isPingSuppressing = false;
    private int pingSuppressDurationTicks = 0;
    private int pingSuppressRemainingTicks = 0;
    private static final int PING_SUPPRESS_MIN_DURATION_TICKS = 80;   // 4 seconds
    private static final int PING_SUPPRESS_MAX_DURATION_TICKS = 200; // 10 seconds
    
    private List<LivingEntity> cachedPotentialTargets = null;
    private long cachedPotentialTargetsTick = -1;

    private ExposureCalculator.AimPointResult cachedAimPoint = null;
    private int cachedAimPointTick = -1;
    private UUID cachedAimPointTargetUUID = null;

    private int getBurstTarget() {
        if (GunIntegration.isMachineGun(soldier)) {
            return soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
                ? MACHINE_GUN_SUPPRESSIVE_BURST_SHOTS : MACHINE_GUN_STANDARD_BURST_SHOTS;
        }
        return soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
            ? SUPPRESSIVE_BURST_SHOTS : BURST_SHOTS_TARGET;
    }

    private static final int BURST_SHOTS_TARGET = 3;
    private static final int SUPPRESSIVE_BURST_SHOTS = 6;
    private static final int MACHINE_GUN_STANDARD_BURST_SHOTS = 8;
    private static final int MACHINE_GUN_SUPPRESSIVE_BURST_SHOTS = 12;
    private static final float BURST_INTERVAL_RIFLE_SECONDS = 0.8f;
    private static final float BURST_INTERVAL_MG_SECONDS = 0.35f;
    private int burstShotsFired = 0;
    private int burstCooldownTicks = 0;
    private int ticksSinceLastBurstShot = 0;
    private boolean burstWaitingForBolt = false;
    private int burstInitialDelayTicks = 0;

    public static void setDebugLoggingEnabled(boolean enabled) {
        DiagnosticLogManager.setAttackLoggingEnabled(enabled);
    }

    private static boolean isDebugLogging() {
        return DiagnosticLogManager.isAttackLoggingEnabled();
    }

    private static boolean isDamageDebugLogging() {
        return DiagnosticLogManager.isDamageLoggingEnabled();
    }

    private static boolean isSuppressionDebugLogging() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }

    public SoldierCombatGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.threatTracker = new ThreatTracker();
        this.detectionSystem = new DetectionSystem(soldier.getUUID());
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive() || soldier.isHealing()) return false;

        if (soldier.hasValidPingThreatPos() || soldier.hasValidPingSuppressPos()) {
            return true;
        }
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return true;
        }
        if (hasPotentialTargets()) {
            return true;
        }
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive() || soldier.isHealing()) return false;

        if (target != null && target.isAlive() && TargetAcquisition.isValidTarget(soldier, target)) {
            if (TargetAcquisition.hasLineOfSight(soldier, target)) {
                return true;
            }
        }
        if (hasPotentialTargets()) {
            return true;
        }
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return true;
        }
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            if (reloadPending || GunIntegration.isReloading(soldier)) {
                return true;
            }
            if (GunIntegration.getCurrentAmmo(soldier) == 0) {
                return true;
            }
        }
        if (soldier.hasValidPingSuppressPos()) {
            return true;
        }

        return false;
    }

    @Override
    public void start() {
        if (target == null) {
            findNewTarget();
        }

        if (target != null) {
            soldier.setTarget(target);
            threatTracker.reportThreatDirect(target);
            resetAim(target);
        }
        wasAiming = false;
    }

    @Override
    public void stop() {
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }
        soldier.setTarget(null);
        this.target = null;
        this.wasAiming = false;
        this.engagementPostureState = EngagementPostureState.READY;
        soldier.clearEmergencyEngagementPosture();
        resetAim(null);
    }
    
    private void resetAim(LivingEntity newTarget) {
        if (newTarget == null) {
            aimQuality = 0.0f;
            trackedTargetUUID = null;
            return;
        }
        if (trackedTargetUUID == null || !trackedTargetUUID.equals(newTarget.getUUID())) {
            trackedTargetUUID = newTarget.getUUID();
            float switchReset = StevesArmyConfig.getAimQualitySwitchReset();
            aimQuality *= switchReset;
        }
    }
    
    private ExposureCalculator.AimPointResult getOrComputeAimPoint() {
        if (target == null) {
            currentAimPoint = null;
            return null;
        }
        
        int currentTick = soldier.tickCount;
        UUID targetUUID = target.getUUID();
        
        if (cachedAimPoint != null && cachedAimPointTick == currentTick && cachedAimPointTargetUUID.equals(targetUUID)) {
            currentAimPoint = cachedAimPoint;
            return cachedAimPoint;
        }
        
        currentAimPoint = ExposureCalculator.getBestAimPoint(soldier, target, getCoverBlockPos());
        cachedAimPoint = currentAimPoint;
        cachedAimPointTick = currentTick;
        cachedAimPointTargetUUID = targetUUID;
        return currentAimPoint;
    }

    @Override
    public void tick() {
        if (soldier.isHealing()) return;
        threatTracker.update(soldier);
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            if (threatPos != null) {
                threatTracker.reportThreatAtPosition(threatPos);
            }
        }
        
        List<LivingEntity> potentialTargets = getPotentialTargets();
        detectionSystem.tick(soldier, potentialTargets);
        
        // Feed detected entities into ThreatAwareness
        ThreatAwareness threats = soldier.getThreatAwareness();
        for (LivingEntity potential : potentialTargets) {
            if (detectionSystem.isTargetDetected(potential)) {
                threats.onEntityDetected(potential, soldier.position());
            }
        }
        
        // Feed ping threat positions into ThreatAwareness if no entity found
        if (soldier.hasValidPingThreatPos() && !threats.hasActiveThreat()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            if (threatPos != null) {
                // Already handled in receivePing via onEnemyPing/onPingDirection
            }
        }
        
        boolean hasGun = GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier);
        if (!hasGun && reloadPending) {
            clearReloadStatus();
        }
        
        if (hasGun) {
            handleGunInitialization();
        }
        
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        
        if (target == null || !target.isAlive()) {
            if (inCover && target == null) {
                targetReevaluateCounter = 0;
                findNewTarget();
            } else {
                findNewTarget();
            }
        } else if (!TargetAcquisition.hasLineOfSight(soldier, target)) {
            if (!inCover || soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
                if (inCover) {
                    if (++targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                        targetReevaluateCounter = 0;
                        findNewTarget();
                    }
                } else {
                    findNewTarget();
                }
            }
        }

        if (hasGun && tickReloadState()) {
            updateDebugSync();
            return;
        }
        
        if (target != null && target.isAlive()) {
            tickCombat(hasGun);
            updateDebugSync();
            
            if (TargetAcquisition.hasLineOfSight(soldier, target)) {
                reportThreatToSquadIntel(target, 1.0f);
            }
        } else {
            tickScanning(potentialTargets);
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (DiagnosticLogManager.isAttackLoggingEnabled() && soldier.tickCount % 100 == 0) {
                StevesArmyMod.LOGGER.info("[CombatGoal] Soldier {} tick: target=null, inCover={}, peekState={}, coverState={}",
                    soldier.getId(), inCover, soldier.getPeekController().getState(), coverManager.getState());
            }
            if (inCover) {
                tickCoverPeekCycle(coverManager);
            }
            
            if (hasGun && shouldSuppressPingTarget()) {
                trySuppressPingFire();
            } else {
                isPingSuppressing = false;
            }
            
            if (isPingSuppressing) {
                updateDebugSync();
            } else {
                updateDebugSync();
            }
        }
        
        debugSyncTickCounter++;
        if (debugSyncTickCounter >= DEBUG_SYNC_INTERVAL) {
            debugSyncTickCounter = 0;
            sendDebugPacketToOwner();
        }
    }
    
    private void handleGunInitialization() {
        ItemStack currentGun = soldier.getMainHandItem();
        boolean gunChanged = !lastGunStack.isEmpty() && !ItemStack.isSameItem(lastGunStack, currentGun);
        
        if (gunChanged) {
            gunInitialized = false;
        }
        
        if (!gunInitialized) {
            if (!GunIntegration.isReloading(soldier) && !GunIntegration.isBolting(soldier)) {
                GunIntegration.initialData(soldier);
                GunIntegration.draw(soldier);
            }
            gunInitialized = true;
            lastGunStack = currentGun.copy();
        }
        
    }

    private boolean tickReloadState() {
        if (reloadRetryTicks > 0) {
            reloadRetryTicks--;
        }

        if (GunIntegration.isReloading(soldier)) {
            reloadPending = false;
            reloadStartRequested = false;
            soldier.setReloadStatus(false, tacticalReloadPending);
            wasReloading = true;
            return true;
        }

        if (wasReloading) {
            GunIntegration.initialData(soldier);
            GunIntegration.draw(soldier);
            clearReloadStatus();
            wasReloading = false;
            return true;
        }

        // TaCZ syncs reload state at the end of the entity tick. Confirm the
        // request on the following tick; otherwise release cover immediately.
        if (reloadStartRequested) {
            clearReloadStatus();
            reloadRetryTicks = 20;
            return false;
        }

        if (reloadPending) {
            if (tacticalReloadPending && isDirectlyEngaging()) {
                clearReloadStatus();
                return false;
            }

            if (isReadyToReload()) {
                prepareEnemyReserveAmmo();
                GunIntegration.reload(soldier);
                reloadStartRequested = true;
            }
            return true;
        }

        if (reloadRetryTicks > 0 || usesInventoryAmmoWithoutReserve()) {
            return false;
        }

        if (GunIntegration.getCurrentAmmo(soldier) == 0) {
            requestReload(false);
            return reloadPending;
        }

        if (shouldTacticalReload()) {
            requestReload(true);
            return true;
        }

        return false;
    }

    private void requestReload(boolean tactical) {
        prepareEnemyReserveAmmo();
        if (reloadPending || GunIntegration.isReloading(soldier)
            || usesInventoryAmmoWithoutReserve()
            || !GunIntegration.canReload(soldier)) {
            return;
        }

        cancelAllSuppression();
        reloadPending = true;
        tacticalReloadPending = tactical;
        soldier.setReloadStatus(true, tactical);
    }

    private void clearReloadStatus() {
        reloadPending = false;
        tacticalReloadPending = false;
        reloadStartRequested = false;
        soldier.setReloadStatus(false, false);
    }

    private boolean isReadyToReload() {
        if (GunIntegration.isBolting(soldier) || GunIntegration.isDrawing(soldier)
            || GunIntegration.getShootCoolDown(soldier) != 0) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        return !coverManager.isInCover() || soldier.getPeekController().isHiding();
    }

    private boolean shouldTacticalReload() {
        if (usesInventoryAmmoWithoutReserve()
            || !GunIntegration.canReload(soldier)
            || !soldier.getCoverBehaviorManager().isInCover() || isDirectlyEngaging()) {
            return false;
        }

        int magazineSize = GunIntegration.getMagazineSize(soldier);
        int currentAmmo = GunIntegration.getCurrentAmmo(soldier);
        return magazineSize > 0
            && currentAmmo * 2 < magazineSize
            && getTotalAmmo() > currentAmmo;
    }

    private boolean usesInventoryAmmoWithoutReserve() {
        return GunIntegration.useInventoryAmmo(soldier)
            && !(soldier instanceof EnemySoldierEntity enemy && enemy.hasInfiniteReserveAmmo());
    }

    private void prepareEnemyReserveAmmo() {
        if (soldier instanceof EnemySoldierEntity enemy) {
            enemy.ensureInfiniteReserveAmmo();
        }
    }

    private boolean isDirectlyEngaging() {
        return (target != null && target.isAlive() && TargetAcquisition.hasLineOfSight(soldier, target))
            || isSuppressing
            || suppressionTargetPos != null
            || isPingSuppressing
            || pingSuppressRemainingTicks > 0
            || soldier.hasValidPingSuppressPos();
    }
    
    private void tickCombat(boolean hasGun) {
        boolean canSee = TargetAcquisition.hasLineOfSight(soldier, target);
        
        if (canSee) {
            cancelAllSuppression();
            threatTracker.reportThreatDirect(target);
            resetAim(target);
        }
        
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            tickCoverPeekCycle(coverManager);
        }
        
        PeekController.State peekState = soldier.getPeekController().getState();
        boolean isDuckedInHalfCover = coverManager.isInCover()
            && coverManager.getCurrentCover() != null
            && coverManager.getCurrentCover().getType() == CoverType.HALF
            && (peekState == PeekController.State.HIDING 
                || peekState == PeekController.State.RETURNING_TO_COVER);
        
        if (!isDuckedInHalfCover) {
            soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        
        if (hasGun) {
            // Suppressed soldiers remain hidden in cover but can return fire when
            // they have direct LOS; suppression controls posture and movement.
            boolean shouldShoot = canSee;
            if (shouldShoot) {
                tickGunCombat();
            } else if (shouldSuppressTarget()) {
                isSuppressing = true;
                trySuppressireFire();
            } else if (shouldSuppressPingTarget()) {
                trySuppressPingFire();
            } else {
                isSuppressing = false;
                isPingSuppressing = false;
            }
        }
        
        if (soldier.isCQB() && target != null && target.isAlive()) {
            double distSq = soldier.distanceToSqr(target);
            if (!soldier.getCoverBehaviorManager().isInCover() && distSq > SoldierEntity.CQB_RANGE * SoldierEntity.CQB_RANGE) {
                // Don't overwrite cover/direct-bound navigation
                if (soldier.getCoverBehaviorManager().isSeekingCover()) {
                    // Only log once
                } else {
                    soldier.getNavigation().moveTo(target, 1.0);
                }
            }
        }
    }
    
    private void cancelAllSuppression() {
        if (isSuppressing) {
            SquadThreatIntel intel = getSquadIntel();
            if (intel != null && suppressionTargetUUID != null) {
                intel.clearThreatSuppression(suppressionTargetUUID);
            }
            suppressionTargetUUID = null;
            suppressionTargetPos = null;
            pendingSuppressionThreat = null;
            isSuppressing = false;
        }
        
        if (isPingSuppressing || soldier.hasValidPingSuppressPos()) {
            soldier.clearPingSuppressPos();
            isPingSuppressing = false;
            pingSuppressRemainingTicks = 0;
        }
        
        resetBurstState();
    }

    private void tickGunCombat() {
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();

        if (coverManager.isInCover() && soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
            // A close visible flanker may interrupt a suppressed half-cover posture,
            // but still has to transition through the exposed state before firing.
            if (target != null && target.isAlive()) {
                prepareToFire(target.getEyePosition(), true);
            }
            return;
        }
        
        boolean isDrawing = GunIntegration.isDrawing(soldier);
        boolean isBolting = GunIntegration.isBolting(soldier);
        boolean isReloading = GunIntegration.isReloading(soldier);
        
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: soldier={}({}) target={}({}) isDrawing={} isBolting={} isReloading={}",
                soldier.getName().getString(), soldier.getId(),
                target != null ? target.getName().getString() + "(" + target.getId() + ")" : "null",
                target != null ? target.getClass().getSimpleName() : "null",
                isDrawing, isBolting, isReloading);
        }
        
        if (isReloading) {
            wasReloading = true;
            return;
        }
        
        if (isDrawing || isBolting) {
            return;
        }

        ExposureCalculator.AimPointResult aimPoint = getOrComputeAimPoint();
        if (aimPoint == null) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: aimPoint is null, can't shoot");
            }
            return;
        }
        
        if (!aimPoint.canShoot()) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: canShoot=false (pointVisible={} bulletPathClear={}) aimType={}", 
                    aimPoint.pointVisible, aimPoint.bulletPathClear, aimPoint.type.displayName);
            }
            if (shouldSuppressTarget()) {
                isSuppressing = true;
                trySuppressireFire();
                return;
            }
            
            int maxBlockedTicks = (soldier.isCQB() || soldier.hasCloseRangeTarget())
                ? CQB_PATH_BLOCKED_SWITCH_TICKS : PATH_BLOCKED_SWITCH_TICKS;
            pathBlockedCounter++;
            if (pathBlockedCounter >= maxBlockedTicks) {
                if (isDamageDebugLogging()) {
                    StevesArmyMod.LOGGER.info("Path blocked for {} ticks (CQB={}), switching target", pathBlockedCounter, soldier.isCQB() || soldier.hasCloseRangeTarget());
                }
                pathBlockedCounter = 0;
                if (findNewTarget()) {
                    resetAim(target);
                }
            }
            return;
        }

        if (!prepareToFire(aimPoint.position, true)) {
            return;
        }
        
        if (!FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality)) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[FriendlyFire] Soldier {} blocked shot - friendly in cone", 
                    soldier.getId());
            }
            return;
        }
        
        pathBlockedCounter = 0;
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        float adsThreshold = ADS_THRESHOLD;
        if (soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE) {
            adsThreshold = 0.40f;
        }
        if (adsProgress < adsThreshold) {
            return;
        }
        
        updateAimQuality();
        
        float targetAimQ = AimAccuracyManager.getTargetAimQuality(soldier, target);
        float thresholdScale = lastShotNeededBolt || GunIntegration.isBolting(soldier) 
            ? StevesArmyConfig.getAimQualitySlowGunThresholdScale() 
            : StevesArmyConfig.getAimQualityThresholdScale();
        FireDiscipline discipline = soldier.getFireDiscipline();
        if (discipline == FireDiscipline.CONSERVE) {
            thresholdScale = Math.max(thresholdScale, 0.55f);
        } else if (discipline == FireDiscipline.SUPPRESSIVE) {
            thresholdScale = Math.min(thresholdScale, 0.20f);
        }
        float shotThreshold = Math.max(0.15f, targetAimQ * thresholdScale);
        
        if (aimQuality < shotThreshold) {
            targetReevaluateCounter++;
            if (targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                targetReevaluateCounter = 0;
                Optional<LivingEntity> betterTarget = findBetterTarget(aimQuality);
                if (betterTarget.isPresent()) {
                    this.target = betterTarget.get();
                    soldier.setTarget(target);
                    threatTracker.reportThreatDirect(target);
                    detectionSystem.forceDetect(target);
                    resetAim(target);
                    if (isDamageDebugLogging()) {
                        StevesArmyMod.LOGGER.info("Switched to better target: {} (higher hit probability)", 
                            target.getName().getString());
                    }
                    return;
                }
            }
            return;
        }
        
        GunIntegration.ShootResult result;
        
        // Always fire through the aim point with angular deviation.
        // The accuracy model is angular dispersion, not a hit/miss roll.
        float yawSigma = AimAccuracyManager.getYawSigma(aimQuality);
        float pitchSigma = AimAccuracyManager.getPitchSigma(aimQuality);
        // Vegetation makes a partly visible target difficult to track rather
        // than treating every visible silhouette as a clean shooting solution.
        yawSigma += (float) aimPoint.concealment * 2.00f;
        pitchSigma += (float) aimPoint.concealment * 0.75f;
        float[] deviation = AimAccuracyManager.sampleGaussianDeviation(aimQuality, yawSigma, pitchSigma, soldier.level());
        float pitchDev = deviation[0];
        float yawDev = deviation[1];
        
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: SHOOT target={}({}) aimPoint=({},{},{}) aimType={} aimQuality={} yawSigma={} pitchSigma={} yawDev={} pitchDev={}",
                target.getName().getString(), target.getId(),
                String.format("%.2f", aimPoint.position.x),
                String.format("%.2f", aimPoint.position.y),
                String.format("%.2f", aimPoint.position.z),
                aimPoint.type.displayName,
                String.format("%.3f", aimQuality),
                String.format("%.3f", yawSigma),
                String.format("%.3f", pitchSigma),
                String.format("%.3f", yawDev),
                String.format("%.3f", pitchDev));
        }
        
        result = GunIntegration.shootWithDeviation(soldier, aimPoint, pitchDev, yawDev);
        
        if (result == GunIntegration.ShootResult.SUCCESS) {
            if (coverManager.isInCover()) {
                coverManager.onPeekShot();
            }

            if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                float recoilLoss = recoilMagnitude * StevesArmyConfig.getAimQualityRecoilScale();
                aimQuality = Math.max(0.0f, aimQuality - recoilLoss);
                if (isDamageDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Recoil] pitch={}, yaw={}, magnitude={}, recoilLoss={}, aimQuality={}",
                        String.format("%.3f", recoil[0]), String.format("%.3f", recoil[1]),
                        String.format("%.3f", recoilMagnitude), String.format("%.3f", recoilLoss),
                        String.format("%.3f", aimQuality));
                }
            }
        }
        
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: ShootResult={}", result);
        }
        switch (result) {
            case SUCCESS -> lastShotNeededBolt = false;
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                lastShotNeededBolt = true;
            }
            case NO_AMMO -> requestReload(false);
            case COOLDOWN -> {}
            case IS_BOLTING, IS_RELOADING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            case PATH_BLOCKED -> {
                pathBlockedCounter++;
                if (pathBlockedCounter >= PATH_BLOCKED_SWITCH_TICKS) {
                    StevesArmyMod.LOGGER.info("PATH_BLOCKED result, switching target");
                    pathBlockedCounter = 0;
                    if (findNewTarget()) {
                        resetAim(target);
                    }
                }
            }
            default -> {}
        }
    }
    
    private void tickCoverPeekCycle(CoverBehaviorManager coverManager) {
        PeekController peekCtrl = soldier.getPeekController();
        PeekController.State peekState = peekCtrl.getState();
        
        if (target != null && target.isAlive()) {
            soldier.setTarget(target);
        }
        
        if (peekState == PeekController.State.EXPOSED 
            || peekState == PeekController.State.MOVING_TO_PEEK
            || peekState == PeekController.State.RETURNING_TO_COVER) {
            lookTowardThreat();
        }
    }
    
    @javax.annotation.Nullable
    private BlockPos getCoverBlockPos() {
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            CoverPoint cover = coverManager.getCurrentCover();
            if (cover != null) {
                return cover.getPosition();
            }
        }
        return null;
    }

    private void lookTowardThreat() {
        if (target != null && target.isAlive()) {
            soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        } else {
            BlockPos threatPos = soldier.getThreatAwareness().getPrimaryThreatPosition();
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
    
    private Optional<LivingEntity> findBetterTarget(float currentAimQuality) {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        
        List<LivingEntity> detectedTargets = potentialTargets.stream()
            .filter(detectionSystem::isTargetDetected)
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .filter(e -> !e.getUUID().equals(target.getUUID()))
            .collect(Collectors.toList());
        
        if (detectedTargets.isEmpty()) {
            return Optional.empty();
        }
        
        float improvementThreshold = StevesArmyConfig.getTargetSwitchImprovement();
        
        Optional<LivingEntity> betterTarget = detectedTargets.stream()
            .map(e -> new TargetScore(e, AimAccuracyManager.calculateHitProbability(soldier, e)))
            .filter(ts -> ts.hitProbability > currentAimQuality + improvementThreshold)
            .max(Comparator.comparingDouble(ts -> ts.hitProbability))
            .map(ts -> ts.target);
        
        return betterTarget;
    }
    
    private static class TargetScore {
        final LivingEntity target;
        final float hitProbability;
        
        TargetScore(LivingEntity target, float hitProbability) {
            this.target = target;
            this.hitProbability = hitProbability;
        }
    }
    
    private void updateAimQuality() {
        if (target == null) return;

        boolean inLOS = TargetAcquisition.hasLineOfSight(soldier, target);

        if (inLOS) {
            float targetAimQuality = AimAccuracyManager.getTargetAimQuality(soldier, target);
            float buildRate = AimAccuracyManager.getBuildRate(soldier, target);

            if (soldier.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
                aimQuality -= StevesArmyConfig.getAimQualityMoveDecayRate();
            }

            double targetSpeed = target.getDeltaMovement().horizontalDistanceSqr();
            if (targetSpeed > 0.01) {
                aimQuality -= StevesArmyConfig.getAimQualityTargetMovePenalty();
            }

            if (aimQuality < targetAimQuality) {
                aimQuality = Mth.lerp(buildRate, aimQuality, targetAimQuality);
            } else if (aimQuality > targetAimQuality) {
                aimQuality = Mth.lerp(buildRate * 0.5f, aimQuality, targetAimQuality);
            }
        } else {
            aimQuality -= StevesArmyConfig.getAimQualityLosDecayRate();
        }

        aimQuality = Mth.clamp(aimQuality, 0.0f, 1.0f);
    }

    private void tickScanning(List<LivingEntity> potentialTargets) {
        if (wasAiming) {
            if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
                GunIntegration.aim(soldier, false);
            }
            wasAiming = false;
        }
        
        Optional<LivingEntity> suspectedTarget = detectionSystem.getHighestProgressTarget(potentialTargets);
        
        if (suspectedTarget.isPresent()) {
            soldier.getLookControl().setLookAt(suspectedTarget.get(), 30.0F, 30.0F);
            return;
        }
        
        if (soldier.hasValidForcedTarget()) {
            BlockPos forcedPos = soldier.getForcedTargetPos();
            soldier.getLookControl().setLookAt(
                forcedPos.getX() + 0.5, forcedPos.getY() + 0.5, forcedPos.getZ() + 0.5, 
                30.0F, 30.0F
            );
            return;
        }
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            soldier.getLookControl().setLookAt(
                threatPos.getX() + 0.5, threatPos.getY() + 0.5, threatPos.getZ() + 0.5,
                30.0F, 30.0F
            );
            return;
        }
        
        Optional<BlockPos> lastKnownPos = threatTracker.getLastKnownPosition();
        if (lastKnownPos.isPresent()) {
            BlockPos pos = lastKnownPos.get();
            soldier.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30.0F, 30.0F);
            return;
        }
        
        Optional<LivingEntity> nearestTarget = potentialTargets.stream()
            .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
        
        if (nearestTarget.isPresent()) {
            soldier.getLookControl().setLookAt(nearestTarget.get(), 30.0F, 30.0F);
        }
    }

    public List<LivingEntity> getPotentialTargets() {
        long currentTick = soldier.tickCount;
        
        if (cachedPotentialTargets != null && (currentTick - cachedPotentialTargetsTick < 5)) {
            return cachedPotentialTargets;
        }
        
        cachedPotentialTargets = computePotentialTargets();
        cachedPotentialTargetsTick = currentTick;
        return cachedPotentialTargets;
    }
    
    private List<LivingEntity> computePotentialTargets() {
        List<LivingEntity> potentialTargets = new ArrayList<>();

        double maxRange = Math.max(DetectionSystem.FOCUSED_RANGE, DetectionSystem.PERIPHERAL_RANGE);

        if (StevesArmyConfig.shouldTargetMonsters()) {
            List<Monster> nearbyMonsters = soldier.level().getEntitiesOfClass(
                Monster.class,
                soldier.getBoundingBox().inflate(maxRange)
            );

            for (Monster monster : nearbyMonsters) {
                if (TargetAcquisition.isValidTarget(soldier, monster) && !soldier.isFriendlyTo(monster)) {
                    potentialTargets.add(monster);
                }
            }
        }

        if (StevesArmyConfig.shouldTargetTargetEntities()) {
            List<TargetEntity> nearbyTargets = soldier.level().getEntitiesOfClass(
                TargetEntity.class,
                soldier.getBoundingBox().inflate(maxRange)
            );

            for (TargetEntity targetEntity : nearbyTargets) {
                if (TargetAcquisition.isValidTarget(soldier, targetEntity) && !soldier.isFriendlyTo(targetEntity)) {
                    potentialTargets.add(targetEntity);
                }
            }
        }

        List<Player> nearbyPlayers = soldier.level().getEntitiesOfClass(
            Player.class,
            soldier.getBoundingBox().inflate(maxRange)
        );

        for (Player player : nearbyPlayers) {
            if (TargetAcquisition.isValidTarget(soldier, player) && !soldier.isFriendlyTo(player)) {
                potentialTargets.add(player);
            }
        }

        List<SoldierEntity> nearbySoldiers = soldier.level().getEntitiesOfClass(
            SoldierEntity.class,
            soldier.getBoundingBox().inflate(maxRange)
        );

        for (SoldierEntity otherSoldier : nearbySoldiers) {
            if (otherSoldier == soldier) continue;
            if (TargetAcquisition.isValidTarget(soldier, otherSoldier) && !soldier.isFriendlyTo(otherSoldier)) {
                potentialTargets.add(otherSoldier);
            }
        }

        return potentialTargets;
    }

    private boolean hasPotentialTargets() {
        return !getPotentialTargets().isEmpty();
    }
    
    public boolean hasDetectedTargets() {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        return potentialTargets.stream().anyMatch(detectionSystem::isTargetDetected);
    }

    private void onTargetAcquiredDuringPeek() {
        PeekController peekCtrl = soldier.getPeekController();
        if (peekCtrl.isMovingToPeek()) {
            // Target acquired during progressive peek - shortcut to exposed
            if (peekCtrl.getState() == PeekController.State.HIDING) {
                // This is a no-op in the new system; PeekController handles its own timing
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Soldier {} acquired target during peek", soldier.getId());
                }
            }
        }
    }

    private boolean findNewTarget() {
        boolean found = findNewTargetInternal();
        if (found) {
            onTargetAcquiredDuringPeek();
        }
        return found;
    }

    private boolean findNewTargetInternal() {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        ThreatAwareness threats = soldier.getThreatAwareness();

        if (isDebugLogging()) {
            findNewTargetLogCounter++;
            if (findNewTargetLogCounter >= 20) {
                findNewTargetLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: {} potential targets, inCover={}", 
                    potentialTargets.size(), soldier.getCoverBehaviorManager().isInCover());
            }
        }
        
        if (soldier.hasValidForcedTarget()) {
            BlockPos forcedPos = soldier.getForcedTargetPos();
            double radius = 20.0;
            
            Optional<LivingEntity> forcedEntity = potentialTargets.stream()
                .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
                .filter(e -> e.distanceToSqr(forcedPos.getX(), forcedPos.getY(), forcedPos.getZ()) < radius * radius)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(forcedPos.getX(), forcedPos.getY(), forcedPos.getZ())));
            
            if (forcedEntity.isPresent()) {
                this.target = forcedEntity.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearForcedTarget();
                soldier.clearPingThreatPos();
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired forced target: {}", target.getName().getString());
                }
                return true;
            }
        }
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            double threatRadius = 20.0;
            
            Optional<LivingEntity> pingTarget = potentialTargets.stream()
                .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
                .filter(e -> e.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ()) < threatRadius * threatRadius)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ())));
            
            if (pingTarget.isPresent()) {
                this.target = pingTarget.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearPingThreatPos();
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired ping target: {}", target.getName().getString());
                }
                return true;
            }
        }
        
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        
        List<LivingEntity> losTargets = potentialTargets.stream()
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .collect(Collectors.toList());
        
        if (!losTargets.isEmpty()) {
            Optional<LivingEntity> best = losTargets.stream()
                .map(e -> new TargetScore(e, AimAccuracyManager.calculateHitProbability(soldier, e)))
                .max(Comparator.comparingDouble(ts -> ts.hitProbability))
                .map(ts -> ts.target);
            
            if (best.isPresent()) {
                this.target = best.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired LOS target: {}", target.getName().getString());
                }
                return true;
            }
            
            this.target = losTargets.get(0);
            soldier.setTarget(target);
            threats.onEntityDetected(target, soldier.position());
            threatTracker.reportThreatDirect(target);
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[CombatGoal] Acquired nearest LOS target: {}", target.getName().getString());
            }
            return true;
        }
        
        if (!potentialTargets.isEmpty()) {
            Vec3 primaryDir = threats.getPrimaryDirection(soldier.position());
            if (primaryDir != null && primaryDir.lengthSqr() > 0.001) {
                Optional<LivingEntity> threatDirTarget = potentialTargets.stream()
                    .min(Comparator.comparingDouble(e -> {
                        Vec3 toTarget = e.position().subtract(soldier.position()).normalize();
                        return toTarget.distanceToSqr(primaryDir);
                    }));
                
                if (threatDirTarget.isPresent()) {
                    this.target = threatDirTarget.get();
                    soldier.setTarget(target);
                    threats.onEntityDetected(target, soldier.position());
                    threatTracker.reportThreatDirect(target);
                    if (isDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[CombatGoal] Acquired threat-direction target (cover fallback): {}", 
                            target.getName().getString());
                    }
                    return true;
                }
            }
            
            Optional<BlockPos> lastKnown = threatTracker.getLastKnownPosition();
            if (lastKnown.isPresent()) {
                BlockPos lk = lastKnown.get();
                Optional<LivingEntity> nearLastKnown = potentialTargets.stream()
                    .filter(e -> e.distanceToSqr(lk.getX(), lk.getY(), lk.getZ()) < 400.0)
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(lk.getX(), lk.getY(), lk.getZ())));
                
                if (nearLastKnown.isPresent()) {
                    this.target = nearLastKnown.get();
                    soldier.setTarget(target);
                    threats.onEntityDetected(target, soldier.position());
                    threatTracker.reportThreatDirect(target);
                    if (isDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[CombatGoal] Acquired target near last known position (cover fallback): {}", 
                            target.getName().getString());
                    }
                    return true;
                }
            }
            
            Optional<LivingEntity> closest = potentialTargets.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
            
            if (closest.isPresent()) {
                this.target = closest.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired closest target (cover fallback): {}", 
                        target.getName().getString());
                }
                return true;
            }
        }
        
        if (isDebugLogging()) {
            findNewTargetLogCounter++;
            if (findNewTargetLogCounter >= 20) {
                findNewTargetLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: no target found ({} potential, {} LOS, inCover={})",
                    potentialTargets.size(), losTargets.size(), inCover);
            }
        }
        
        this.target = null;
        return false;
    }
    
    public LivingEntity getCurrentTarget() {
        return target;
    }
    
    public DetectionSystem getDetectionSystem() {
        return detectionSystem;
    }
    
    public void setTarget(LivingEntity newTarget) {
        this.target = newTarget;
        soldier.setTarget(newTarget);
    }
    
    private void updateDebugSync() {
        if (target != null && target.isAlive()) {
            if (!soldier.level().isClientSide) {
                double distance = soldier.distanceTo(target);
                boolean hasLOS = TargetAcquisition.hasLineOfSight(soldier, target);
                boolean inFocused = TargetAcquisition.isInFocusedArc(soldier, target);
                double detectionProgress = detectionSystem.getDetectionProgress(target);
                boolean isDetected = detectionSystem.isTargetDetected(target);
                
                soldier.updateDebugData((float)detectionProgress, isDetected, (float)distance, hasLOS, inFocused);
                soldier.setDebugTargetUUID(target.getUUID());
            }
            return;
        }
        
        if (!soldier.level().isClientSide) {
            ThreatAwareness threats = soldier.getThreatAwareness();
            if (threats.hasActiveThreat()) {
                Vec3 threatPos = threats.getWeightedAveragePosition();
                if (threatPos.lengthSqr() > 0.001) {
                    soldier.updateDebugData(0, false, (float)soldier.position().distanceTo(threatPos), false, false);
                    soldier.setDebugTargetUUID(soldier.getUUID());
                }
            } else {
                soldier.setDebugTargetUUID(null);
            }
        }
    }
    
    private void sendDebugPacketToOwner() {
        if (soldier.level().isClientSide) return;
        
        LivingEntity owner = soldier.getOwner();
        if (owner instanceof ServerPlayer serverPlayer) {
            List<PotentialTargetInfo> potentialTargetsDebug = getPotentialTargetsForDebug(5);
            
            List<PotentialTargetsDebugMessage.PotentialTargetEntry> entries = new ArrayList<>();
            for (PotentialTargetInfo info : potentialTargetsDebug) {
                entries.add(new PotentialTargetsDebugMessage.PotentialTargetEntry(
                    info.uuid, info.position, info.detectionPoints, info.distance,
                    info.hasLOS, info.inFocused, info.inPeripheral,
                    info.distanceFactor, info.exposureFactor, info.movementFactor, info.brightnessFactor
                ));
            }
            
            UUID lockedTargetUUID = target != null ? target.getUUID() : null;
            Vec3 lockedTargetPos = target != null ? target.position() : Vec3.ZERO;
            
            if (target == null) {
                ThreatAwareness threats = soldier.getThreatAwareness();
                if (threats.hasActiveThreat()) {
                    Vec3 threatPos = threats.getWeightedAveragePosition();
                    if (threatPos.lengthSqr() > 0.001) {
                        lockedTargetUUID = soldier.getUUID();
                        lockedTargetPos = threatPos;
                    }
                }
            }
            
            double lockedDetectionPoints = target != null ? 
                (detectionSystem.getDetectionState(target.getUUID()) != null ? 
                    detectionSystem.getDetectionState(target.getUUID()).accumulatedPoints : 0) : 0;
            double lockedDistance = target != null ? soldier.distanceTo(target) : 0;
            boolean lockedHasLOS = target != null ? TargetAcquisition.hasLineOfSight(soldier, target) : false;
            boolean lockedInFocused = target != null ? TargetAcquisition.isInFocusedArc(soldier, target) : false;
            boolean lockedInPeripheral = target != null ? TargetAcquisition.isInPeripheralArc(soldier, target) : false;
            boolean lockedIsDetected = target != null ? detectionSystem.isTargetDetected(target) : false;
            double lockedDistanceFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastDistanceFactor : 0;
            double lockedExposureFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastExposureFactor : 0;
            double lockedMovementFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastMovementFactor : 0;
            double lockedBrightnessFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastBrightnessFactor : 0;
            float lockedAimQuality = target != null ? aimQuality : 0;
            float lockedTargetAimQuality = target != null ? AimAccuracyManager.getTargetAimQuality(soldier, target) : 0;
            float lockedSuppressiveMin = lastShotNeededBolt || GunIntegration.isBolting(soldier) 
                ? StevesArmyConfig.getAimQualitySlowGunThresholdScale() 
                : StevesArmyConfig.getAimQualityThresholdScale();
            FireDiscipline debugDisc = soldier.getFireDiscipline();
            if (debugDisc == FireDiscipline.CONSERVE) {
                lockedSuppressiveMin = Math.max(lockedSuppressiveMin, 0.55f);
            } else if (debugDisc == FireDiscipline.SUPPRESSIVE) {
                lockedSuppressiveMin = Math.min(lockedSuppressiveMin, 0.20f);
            }
            float lockedAdsProgress = target != null ? GunIntegration.getAimProgress(soldier) : 0;
            String lockedAimPointType = target != null && currentAimPoint != null ? 
                currentAimPoint.type.displayName : "";
            boolean lockedBulletPathClear = target != null && currentAimPoint != null ? 
                currentAimPoint.bulletPathClear : false;
            
            Vec3 suppressionPos = suppressionTargetPos != null ? 
                Vec3.atCenterOf(suppressionTargetPos) : null;
            
            PotentialTargetsDebugMessage msg = new PotentialTargetsDebugMessage(
                soldier.getUUID(), lockedTargetUUID, soldier.position(), lockedTargetPos,
                lockedDetectionPoints, lockedDistance, lockedHasLOS, lockedInFocused,
                lockedInPeripheral, lockedIsDetected,
                lockedDistanceFactor, lockedExposureFactor, lockedMovementFactor, lockedBrightnessFactor,
                lockedAimQuality, lockedTargetAimQuality, lockedSuppressiveMin, lockedAdsProgress,
                lockedAimPointType, lockedBulletPathClear,
                isSuppressing, suppressionPos,
                entries
            );
            
            NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> serverPlayer), msg);
        }
    }
    
    public List<PotentialTargetInfo> getPotentialTargetsForDebug(int maxCount) {
        List<PotentialTargetInfo> result = new ArrayList<>();
        
        List<LivingEntity> allTargets = getPotentialTargets();
        
        for (LivingEntity potentialTarget : allTargets) {
            if (potentialTarget == this.target) continue;
            
            DetectionSystem.DetectionState state = detectionSystem.getDetectionState(potentialTarget.getUUID());
            if (state == null || state.accumulatedPoints <= 0) continue;
            
            double distance = soldier.distanceTo(potentialTarget);
            boolean hasLOS = TargetAcquisition.hasLineOfSight(soldier, potentialTarget);
            boolean inFocused = TargetAcquisition.isInFocusedArc(soldier, potentialTarget);
            boolean inPeripheral = TargetAcquisition.isInPeripheralArc(soldier, potentialTarget);
            double detectionPoints = state.accumulatedPoints;
            double distanceFactor = state.lastDistanceFactor;
            double exposureFactor = state.lastExposureFactor;
            double movementFactor = state.lastMovementFactor;
            double brightnessFactor = state.lastBrightnessFactor;
            
            result.add(new PotentialTargetInfo(
                potentialTarget.getUUID(),
                potentialTarget.position(),
                detectionPoints,
                distance,
                hasLOS,
                inFocused,
                inPeripheral,
                distanceFactor,
                exposureFactor,
                movementFactor,
                brightnessFactor
            ));
        }
        
        result.sort(Comparator.comparingDouble(a -> -a.detectionPoints));
        
        if (result.size() > maxCount) {
            result = result.subList(0, maxCount);
        }
        
        return result;
    }
    
    public static class PotentialTargetInfo {
        public final UUID uuid;
        public final Vec3 position;
        public final double detectionPoints;
        public final double distance;
        public final boolean hasLOS;
        public final boolean inFocused;
        public final boolean inPeripheral;
        public final double distanceFactor;
        public final double exposureFactor;
        public final double movementFactor;
        public final double brightnessFactor;
        
        public PotentialTargetInfo(UUID uuid, Vec3 position, double detectionPoints, double distance, 
                                   boolean hasLOS, boolean inFocused, boolean inPeripheral,
                                   double distanceFactor, double exposureFactor, 
                                   double movementFactor, double brightnessFactor) {
            this.uuid = uuid;
            this.position = position;
            this.detectionPoints = detectionPoints;
            this.distance = distance;
            this.hasLOS = hasLOS;
            this.inFocused = inFocused;
            this.inPeripheral = inPeripheral;
            this.distanceFactor = distanceFactor;
            this.exposureFactor = exposureFactor;
            this.movementFactor = movementFactor;
            this.brightnessFactor = brightnessFactor;
        }
    }

    private SquadThreatIntel getSquadIntel() {
        UUID squadId = soldier.getSquadId();
        if (squadId == null) return null;
        
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        
        SquadManager manager = SquadManager.get(serverLevel);
        Optional<SquadData> squad = manager.getSquadById(squadId);
        return squad.map(SquadData::getThreatIntel).orElse(null);
    }

    private void reportThreatToSquadIntel(LivingEntity threat, float accuracy) {
        SquadThreatIntel intel = getSquadIntel();
        if (intel == null) {
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[ThreatReport] Soldier {} cannot report - no squad intel (squadId={})", 
                    soldier.getId(), soldier.getSquadId());
            }
            return;
        }
        
        if (isDebugLogging()) {
            StevesArmyMod.LOGGER.info("[ThreatReport] Soldier {} reporting threat {} to squad intel", 
                soldier.getId(), threat.getName().getString());
        }
        intel.reportThreat(soldier.getUUID(), threat, threat.blockPosition(), accuracy);
    }

    private boolean shouldSuppressTarget() {
        pendingSuppressionThreat = null;

        if (soldier.getFireDiscipline() == FireDiscipline.CONSERVE) {
            return false;
        }
        
        SquadThreatIntel intel = getSquadIntel();
        if (intel == null) {
            return false;
        }
        
        if (soldier.level() instanceof ServerLevel serverLevel) {
            UUID squadId = soldier.getSquadId();
            if (squadId != null) {
                SquadManager manager = SquadManager.get(serverLevel);
                Optional<SquadData> squadOpt = manager.getSquadById(squadId);
                if (squadOpt.isPresent()) {
                    SuppressireAssignmentManager.assignSuppressionTargets(squadOpt.get(), intel, serverLevel, soldier.getUUID());
                }
            }
        }
        
        if (GunIntegration.isReloading(soldier) || 
            GunIntegration.isBolting(soldier) || 
            GunIntegration.isDrawing(soldier)) {
            return false;
        }
        
        if (target != null && TargetAcquisition.hasLineOfSight(soldier, target)) {
            return false;
        }
        
        List<SquadThreatIntel.ThreatKnowledge> unsuppressedThreats = intel.getUnsuppressedThreats();
        if (unsuppressedThreats.isEmpty()) {
            return false;
        }
        
        for (SquadThreatIntel.ThreatKnowledge threat : unsuppressedThreats) {
            if (!threat.isAlive) continue;
            if (intel.isThreatStale(threat.threatEntityId, soldier.level().getGameTime())) continue;
            if (threat.lastKnownPosition == null) continue;
            
            double dist = soldier.position().distanceTo(threat.lastKnownPosition.getCenter());
            if (dist > SUPPRESSION_MAX_RANGE) continue;
            
            if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
                int totalAmmo = getTotalAmmo();
                if (totalAmmo == 0) continue;
            }
            
            pendingSuppressionThreat = threat;
            return true;
        }
        
        return false;
    }

    private Vec3 calculateSuppressionSpread(Vec3 targetPos, float aimInaccuracy) {
        double distance = soldier.position().distanceTo(targetPos);
        double gunSpread = distance * Math.tan(Math.toRadians(aimInaccuracy));
        double spreadRadius = Mth.clamp(
            SUPPRESSION_SPREAD_MIN_RADIUS + distance * SUPPRESSION_SPREAD_PER_BLOCK + gunSpread,
            SUPPRESSION_SPREAD_MIN_RADIUS,
            SUPPRESSION_SPREAD_MAX_RADIUS
        );

        // Spread perpendicular to the firing direction so shots form a small, believable
        // beaten zone around the selected last-known position or cover opening.
        Vec3 toTarget = targetPos.subtract(soldier.getEyePosition());
        Vec3 horizontalDirection = new Vec3(toTarget.x, 0.0, toTarget.z).normalize();
        Vec3 lateralDirection = new Vec3(-horizontalDirection.z, 0.0, horizontalDirection.x);
        double lateralOffset = (soldier.level().random.nextDouble() - 0.5) * 2.0 * spreadRadius;
        double depthOffset = (soldier.level().random.nextDouble() - 0.5) * spreadRadius * 0.35;
        double verticalOffset = (soldier.level().random.nextDouble() - 0.5)
            * 2.0 * spreadRadius * SUPPRESSION_VERTICAL_SPREAD_RATIO;
        
        // Callers provide a complete world-space target. Do not add a generic
        // vertical offset here: half-cover opening targets already include the
        // cover top, while other suppression targets set their own height.
        return targetPos.add(lateralDirection.scale(lateralOffset))
            .add(horizontalDirection.scale(depthOffset))
            .add(0.0, verticalOffset, 0.0);
    }

    private boolean prepareToFire(Vec3 targetPos, boolean isDirectTarget) {
        float targetYaw = getYawTo(targetPos);
        float targetPitch = getPitchTo(targetPos);

        if (engagementPostureState != EngagementPostureState.READY
            && !canUseEmergencyEngagementPosture(isDirectTarget)) {
            engagementPostureState = EngagementPostureState.READY;
            soldier.clearEmergencyEngagementPosture();
            return false;
        }

        if (soldier.isLowCrouching()) {
            float proneAngle = Math.abs(Mth.wrapDegrees(targetYaw - soldier.getYRot()));
            if (proneAngle <= PRONE_FIRING_ARC_DEGREES) {
                return turnHeadToward(targetYaw, targetPitch) <= FIRING_ALIGNMENT_DEGREES;
            }

            if (!canUseEmergencyEngagementPosture(isDirectTarget)) {
                return false;
            }

            soldier.requestEmergencyEngagementPosture();
            soldier.setLowCrouching(false);
            engagementPostureState = EngagementPostureState.EXITING_LOW_CROUCH;
            return false;
        }

        if (engagementPostureState == EngagementPostureState.EXITING_LOW_CROUCH) {
            // Let the changed pose and eye height settle before calculating a shot.
            engagementPostureState = EngagementPostureState.ROTATING;
            return false;
        }

        float remainingYaw = turnToward(targetYaw, targetPitch);
        if (remainingYaw > FIRING_ALIGNMENT_DEGREES) {
            return false;
        }

        if (engagementPostureState == EngagementPostureState.ROTATING) {
            CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
            if (cover != null && cover.getType() == CoverType.HALF
                && soldier.getPeekController().exposeForEmergencyEngagement(soldier, cover)) {
                engagementPostureState = EngagementPostureState.READY;
                return false;
            }
        }

        engagementPostureState = EngagementPostureState.READY;
        return true;
    }

    private boolean canUseEmergencyEngagementPosture(boolean isDirectTarget) {
        if (!isDirectTarget || target == null || !target.isAlive()
            || soldier.distanceToSqr(target) > EMERGENCY_FLANK_DISTANCE * EMERGENCY_FLANK_DISTANCE) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint cover = coverManager.getCurrentCover();
        return coverManager.isSuppressed() && cover != null && cover.getType() == CoverType.HALF;
    }

    private float turnToward(float targetYaw, float targetPitch) {
        float yaw = approachAngle(soldier.getYRot(), targetYaw, TURN_RATE_DEGREES);
        float pitch = approachAngle(soldier.getXRot(), targetPitch, TURN_RATE_DEGREES);
        soldier.setYRot(yaw);
        soldier.setYBodyRot(yaw);
        soldier.setYHeadRot(yaw);
        soldier.setXRot(pitch);
        return Math.abs(Mth.wrapDegrees(targetYaw - yaw));
    }

    private float turnHeadToward(float targetYaw, float targetPitch) {
        float headYaw = approachAngle(soldier.getYHeadRot(), targetYaw, TURN_RATE_DEGREES);
        float pitch = approachAngle(soldier.getXRot(), targetPitch, TURN_RATE_DEGREES);
        soldier.setYHeadRot(headYaw);
        soldier.setXRot(pitch);
        return Math.abs(Mth.wrapDegrees(targetYaw - headYaw));
    }

    private static float approachAngle(float current, float target, float maxChange) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -maxChange, maxChange);
    }

    private float getYawTo(Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(soldier.getEyePosition());
        return (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
    }

    private float getPitchTo(Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(soldier.getEyePosition());
        double horizontalDistance = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        return (float) -Math.toDegrees(Math.atan2(toTarget.y, horizontalDistance));
    }

    private int getTicksBetweenBurstShots() {
        // TaCZ owns the real gun cooldown. Polling it every tick lets automatic
        // weapons fire at their native RPM instead of rounding RPM down to a
        // coarse multi-tick interval.
        if (GunIntegration.isMachineGun(soldier)) {
            return 1;
        }

        int rpm = GunIntegration.getRPM(soldier);
        double shotsPerSecond = rpm / 60.0;
        int baseTicks = (int) Math.ceil(20.0 / shotsPerSecond);
        
        if (GunIntegration.isManualBolt(soldier)) {
            baseTicks += 10;
        }
        
        return Math.max(1, baseTicks);
    }
    
    private float getBurstIntervalSeconds() {
        return GunIntegration.isMachineGun(soldier) ? BURST_INTERVAL_MG_SECONDS : BURST_INTERVAL_RIFLE_SECONDS;
    }

    private void resetBurstState() {
        burstShotsFired = 0;
        burstCooldownTicks = 0;
        ticksSinceLastBurstShot = 0;
        burstWaitingForBolt = false;
        burstInitialDelayTicks = 0;
    }

    private void trySuppressireFire() {
        if (soldier.getCoverBehaviorManager().isInCover()
            && soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
            return;
        }

        if (suppressionTargetPos == null && pendingSuppressionThreat != null) {
            SquadThreatIntel intel = getSquadIntel();
            if (intel == null) {
                pendingSuppressionThreat = null;
                return;
            }
            
            if (!intel.tryMarkThreatSuppressed(pendingSuppressionThreat.threatEntityId, soldier.getUUID())) {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} failed to claim threat {} (already suppressed by another)",
                        soldier.getId(), pendingSuppressionThreat.threatEntityId.toString().substring(0, 8));
                }
                pendingSuppressionThreat = null;
                return;
            }
            
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} claimed threat {} at {}, starting suppression",
                    soldier.getId(), pendingSuppressionThreat.threatEntityId.toString().substring(0, 8),
                    pendingSuppressionThreat.lastKnownPosition);
            }
            
            suppressionTargetUUID = pendingSuppressionThreat.threatEntityId;
            suppressionTargetPos = pendingSuppressionThreat.lastKnownPosition;
            pendingSuppressionThreat = null;
            burstShotsFired = 0;
            burstCooldownTicks = 0;
            ticksSinceLastBurstShot = 0;
            burstWaitingForBolt = false;
            suppressionDurationTicks = SUPPRESSION_MIN_DURATION_TICKS + 
                soldier.level().random.nextInt(SUPPRESSION_MAX_DURATION_TICKS - SUPPRESSION_MIN_DURATION_TICKS);
            suppressionRemainingTicks = suppressionDurationTicks;
        }
        
        if (suppressionTargetPos == null) {
            return;
        }
        
        SquadThreatIntel intel = getSquadIntel();
        if (intel != null && suppressionTargetUUID != null) {
            intel.updateSuppressionHeartbeat(suppressionTargetUUID, soldier.level().getGameTime());
        }
        
        suppressionRemainingTicks--;
        if (suppressionRemainingTicks <= 0) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} finished suppression duration ({}s), stopping",
                    soldier.getId(), String.format("%.1f", suppressionDurationTicks / 20.0));
            }
            if (intel != null && suppressionTargetUUID != null) {
                intel.clearThreatSuppression(suppressionTargetUUID);
            }
            suppressionTargetUUID = null;
            suppressionTargetPos = null;
            isSuppressing = false;
            resetBurstState();
            return;
        }
        
        Vec3 targetPos = Vec3.atCenterOf(suppressionTargetPos).add(0, 1.0, 0);
        
        if (!TargetAcquisition.hasNearLineOfSightToPosition(soldier, targetPos, SUPPRESSION_LOS_TOLERANCE)) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} has no LOS to suppression target at {}, clearing assignment",
                    soldier.getId(), suppressionTargetPos);
            }
            if (intel != null && suppressionTargetUUID != null) {
                intel.clearThreatSuppression(suppressionTargetUUID);
            }
            suppressionTargetUUID = null;
            suppressionTargetPos = null;
            isSuppressing = false;
            resetBurstState();
            return;
        }

        if (!prepareToFire(targetPos, false)) {
            return;
        }
        
        soldier.getLookControl().setLookAt(
            targetPos.x, targetPos.y, targetPos.z,
            30.0F, 30.0F
        );
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < SUPPRESSION_ADS_THRESHOLD) {
            if (isSuppressionDebugLogging() && suppressionRemainingTicks % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} waiting for ADS ({}%)", 
                    soldier.getId(), (int)(adsProgress * 100));
            }
            return;
        }
        
        if (burstCooldownTicks > 0) {
            burstCooldownTicks--;
            if (isSuppressionDebugLogging() && burstCooldownTicks % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} burst cooldown: {} ticks remaining", 
                    soldier.getId(), burstCooldownTicks);
            }
            return;
        }
        
        if (burstWaitingForBolt && GunIntegration.isBolting(soldier)) {
            if (isSuppressionDebugLogging() && suppressionRemainingTicks % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} waiting for bolt", soldier.getId());
            }
            return;
        }
        burstWaitingForBolt = false;
        
        int burstTarget = getBurstTarget();
        int ticksBetweenShots = getTicksBetweenBurstShots();
        if (burstShotsFired > 0 && burstShotsFired < burstTarget) {
            ticksSinceLastBurstShot++;
            if (ticksSinceLastBurstShot < ticksBetweenShots) {
                return;
            }
        }
        
        float aimInaccuracy = GunIntegration.getAimInaccuracy(soldier);
        // targetPos already converts the remembered feet block into a chest-height point.
        Vec3 spreadPos = calculateSuppressionSpread(targetPos, aimInaccuracy);
        GunIntegration.ShootResult result = GunIntegration.shootAtPosition(soldier, spreadPos);
        
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[Suppression] Soldier {} SHOT burst {}/{} result={}",
                soldier.getId(), burstShotsFired + 1, burstTarget, result);
        }
        
        switch (result) {
            case SUCCESS -> {
                burstShotsFired++;
                ticksSinceLastBurstShot = 0;
                
                if (soldier.getCoverBehaviorManager().isInCover()) {
                    soldier.getCoverBehaviorManager().onPeekShot();
                }
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                aimQuality = Math.max(0.0f, aimQuality - recoilMagnitude * StevesArmyConfig.getAimQualityRecoilScale());
                
                if (burstShotsFired >= burstTarget) {
                    float burstInterval = getBurstIntervalSeconds();
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[Suppression] Soldier {} burst complete, starting cooldown ({}s)",
                            soldier.getId(), burstInterval);
                    }
                    burstCooldownTicks = (int) (burstInterval * 20);
                    burstShotsFired = 0;
                }
            }
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                burstWaitingForBolt = true;
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} needs bolt", soldier.getId());
                }
            }
            case NO_AMMO -> {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} out of ammo, reloading", soldier.getId());
                }
                requestReload(false);
                return;
            }
            case IS_RELOADING, IS_BOLTING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
        
        if (intel != null && suppressionTargetUUID != null) {
            intel.markThreatSuppressed(suppressionTargetUUID, soldier.getUUID());
        }
    }

    public void onTargetKilledByTeammate(UUID killedThreatId) {
        if (suppressionTargetUUID != null && suppressionTargetUUID.equals(killedThreatId)) {
            suppressionTargetUUID = null;
            suppressionTargetPos = null;
            isSuppressing = false;
            resetBurstState();
        }
        
        SquadThreatIntel intel = getSquadIntel();
        if (intel != null) {
            intel.markThreatDead(killedThreatId);
        }
        
        if (target != null && target.getUUID().equals(killedThreatId)) {
            target = null;
            soldier.setTarget(null);
        }
    }

    public boolean isSuppressing() {
        return isSuppressing;
    }

    public BlockPos getSuppressireTargetPos() {
        return suppressionTargetPos;
    }

    public boolean canShootPrimaryTarget() {
        if (target == null || !target.isAlive()) return false;
        if (!TargetAcquisition.hasLineOfSight(soldier, target)) return false;
        
        ExposureCalculator.AimPointResult aimPoint = getOrComputeAimPoint();
        return aimPoint != null && aimPoint.canShoot();
    }
    
    private boolean shouldSuppressPingTarget() {
        if (!soldier.hasValidPingSuppressPos()) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no valid ping suppress pos", soldier.getId());
            }
            return false;
        }
        
        if (GunIntegration.isReloading(soldier) ||
            GunIntegration.isBolting(soldier) ||
            GunIntegration.isDrawing(soldier)) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: busy (reloading/bolting/drawing)", soldier.getId());
            }
            return false;
        }
        
        if (!GunIntegration.isTaczLoaded() || !GunIntegration.hasGun(soldier)) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no gun", soldier.getId());
            }
            return false;
        }
        
        int totalAmmo = getTotalAmmo();
        if (totalAmmo == 0) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no ammo", soldier.getId());
            }
            return false;
        }
        
        BlockPos suppressPos = soldier.getPingSuppressPos();
        double dist = soldier.position().distanceTo(suppressPos.getCenter());
        if (dist > SUPPRESSION_MAX_RANGE) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: too far (dist={}, max={})",
                    soldier.getId(), String.format("%.1f", dist), SUPPRESSION_MAX_RANGE);
            }
            return false;
        }
        
        if (soldier.getSuppressionAimPoints().isEmpty()) {
            CoverFinder finder = new CoverFinder(soldier.level());
            List<Vec3> aimPoints = finder.findSuppressionAimPoints(
                soldier, suppressPos, SoldierEntity.SUPPRESSION_ZONE_RADIUS);
            soldier.setSuppressionAimPoints(aimPoints);
            
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} found {} aim points in zone",
                    soldier.getId(), aimPoints.size());
            }
            
            if (aimPoints.isEmpty()) {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} no aim points found, will use horizontal spread fallback",
                        soldier.getId());
                }
                return true;
            }
        }
        
        return true;
    }
    
    public int getTotalAmmo() {
        if (soldier instanceof EnemySoldierEntity enemy && enemy.hasInfiniteReserveAmmo()) {
            return 1_000_000;
        }

        int magazineAmmo = GunIntegration.getCurrentAmmo(soldier);
        int inventoryAmmo = 0;
        
        com.stevesarmy.inventory.SoldierInventory inv = soldier.getSoldierInventory();
        if (inv != null) {
            ItemStack gunStack = inv.getItem(com.stevesarmy.inventory.SoldierInventory.SLOT_MAIN_HAND);
            for (int i = com.stevesarmy.inventory.SoldierInventory.SLOT_GENERAL_START;
                 i < com.stevesarmy.inventory.SoldierInventory.INVENTORY_SIZE; i++) {
                inventoryAmmo += GunIntegration.getAmmoCountForGun(gunStack, inv.getItem(i));
            }
        }
        
        return magazineAmmo + inventoryAmmo;
    }
    
    private void trySuppressPingFire() {
        if (pingSuppressRemainingTicks <= 0 && soldier.hasValidPingSuppressPos()) {
            isPingSuppressing = true;
            pingSuppressDurationTicks = PING_SUPPRESS_MIN_DURATION_TICKS +
                soldier.level().random.nextInt(PING_SUPPRESS_MAX_DURATION_TICKS - PING_SUPPRESS_MIN_DURATION_TICKS);
            pingSuppressRemainingTicks = pingSuppressDurationTicks;
            resetBurstState();
            
            // Add random initial delay to stagger burst timing across soldiers
            burstInitialDelayTicks = soldier.level().random.nextInt(40); // 0-2 seconds
            
            boolean isMG = GunIntegration.isMachineGun(soldier);
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} starting suppression at {} (duration={}s, isMG={}, initialDelay={}ticks)",
                    soldier.getId(), soldier.getPingSuppressPos(), pingSuppressDurationTicks / 20.0, isMG, burstInitialDelayTicks);
            }
        }
        
        pingSuppressRemainingTicks--;
        if (pingSuppressRemainingTicks <= 0 || !soldier.hasValidPingSuppressPos()) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} finished suppression", soldier.getId());
            }
            soldier.clearPingSuppressPos();
            isPingSuppressing = false;
            pingSuppressRemainingTicks = 0;
            resetBurstState();
            return;
        }
        
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            PeekController.State peekState = soldier.getPeekController().getState();
            if (peekState != PeekController.State.EXPOSED) {
                if (isSuppressionDebugLogging() && pingSuppressRemainingTicks % 20 == 0) {
                    StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} waiting for peek (state={}, remaining={}ticks)",
                        soldier.getId(), peekState, pingSuppressRemainingTicks);
                }
                return;
            }
        }
        
        Vec3 finalTarget = getClearPingSuppressionTarget();
        if (finalTarget == null) {
            return;
        }
        
        soldier.getLookControl().setLookAt(finalTarget.x, finalTarget.y, finalTarget.z, 30.0F, 30.0F);
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < SUPPRESSION_ADS_THRESHOLD) {
            return;
        }
        
        // Apply initial delay before first burst
        if (burstInitialDelayTicks > 0) {
            burstInitialDelayTicks--;
            return;
        }
        
        if (burstCooldownTicks > 0) {
            burstCooldownTicks--;
            return;
        }
        
        if (burstWaitingForBolt && GunIntegration.isBolting(soldier)) {
            return;
        }
        burstWaitingForBolt = false;
        
        int burstTarget = getBurstTarget();
        int ticksBetweenShots = getTicksBetweenBurstShots();
        if (burstShotsFired > 0 && burstShotsFired < burstTarget) {
            ticksSinceLastBurstShot++;
            if (ticksSinceLastBurstShot < ticksBetweenShots) {
                return;
            }
        }

        if (!prepareToFire(finalTarget, false)) {
            return;
        }

        // Friendly-fire check: skip this shot to avoid hitting the player or allies.
        if (!FriendlyFireChecker.isSafeToShoot(soldier, finalTarget, aimQuality)) {
            return;
        }

        GunIntegration.ShootResult result = GunIntegration.shootAtPosition(soldier, finalTarget);
        
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} SHOT burst {}/{} result={}",
                soldier.getId(), burstShotsFired + 1, burstTarget, result);
        }
        
        switch (result) {
            case SUCCESS -> {
                burstShotsFired++;
                ticksSinceLastBurstShot = 0;
                
                if (soldier.getCoverBehaviorManager().isInCover()) {
                    soldier.getCoverBehaviorManager().onPeekShot();
                }
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                aimQuality = Math.max(0.0f, aimQuality - recoilMagnitude * StevesArmyConfig.getAimQualityRecoilScale());
                
                if (burstShotsFired >= burstTarget) {
                    float burstInterval = getBurstIntervalSeconds();
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} burst complete, starting cooldown ({}s)",
                            soldier.getId(), burstInterval);
                    }
                    burstCooldownTicks = (int) (burstInterval * 20);
                    burstShotsFired = 0;
                }
            }
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                burstWaitingForBolt = true;
            }
            case NO_AMMO -> {
                requestReload(false);
            }
            case IS_RELOADING, IS_BOLTING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
    }

    /**
     * Selects a suppression target whose actual spread-adjusted shot has clear LOS.
     * The old code validated the unspread point but fired at a different point,
     * allowing horizontal or vertical spread to send bullets into cover.
     */
    private Vec3 getClearPingSuppressionTarget() {
        float aimInaccuracy = GunIntegration.getAimInaccuracy(soldier);
        Vec3 selected = soldier.getNextSuppressionAimPoint();

        if (selected == null) {
            selected = soldier.getHorizontalSpreadFallbackTarget(soldier.getPingSuppressPos());
        }

        Vec3 finalTarget = calculateSuppressionSpread(selected, aimInaccuracy);
        if (!aimPointsAreEmpty(soldier)) {
            finalTarget = clampSuppressionTargetHeight(finalTarget, selected);
        }
        if (TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, finalTarget)) {
            return finalTarget;
        }

        // Try a bounded number of alternate opening samples before giving up this
        // shot. Skipping a blocked shot is preferable to repeatedly firing into cover.
        java.util.List<Vec3> aimPoints = soldier.getSuppressionAimPoints();
        int attempts = Math.min(aimPoints.size(), 12);
        for (int i = 0; i < attempts; i++) {
            Vec3 candidate = aimPoints.get(i);
            if (candidate.equals(selected)) {
                continue;
            }
            finalTarget = calculateSuppressionSpread(candidate, aimInaccuracy);
            finalTarget = clampSuppressionTargetHeight(finalTarget, candidate);
            if (TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, finalTarget)) {
                return finalTarget;
            }
        }

        return null;
    }

    private boolean aimPointsAreEmpty(SoldierEntity soldier) {
        return soldier.getSuppressionAimPoints().isEmpty();
    }

    private Vec3 clampSuppressionTargetHeight(Vec3 target, Vec3 aimPoint) {
        return target.y < aimPoint.y
            ? new Vec3(target.x, aimPoint.y, target.z)
            : target;
    }
    
    public void forceRestartPingSuppression() {
        this.pingSuppressRemainingTicks = 0;
    }
}

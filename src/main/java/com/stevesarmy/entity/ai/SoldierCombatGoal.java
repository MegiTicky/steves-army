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
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PotentialTargetsDebugMessage;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
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
    
    private UUID trackedTargetUUID = null;
    private long trackingStartTime = 0;
    private float trackingProgress = 0.0f;
    private float currentAccuracy = 0.0f;
    private ExposureCalculator.AimPointResult currentAimPoint = null;
    
    private static final float ADS_THRESHOLD = 0.8f;
    private static final int PATH_BLOCKED_SWITCH_TICKS = 40;
    private int pathBlockedCounter = 0;
    private int debugSyncTickCounter = 0;
    private static final int DEBUG_SYNC_INTERVAL = 5;
    private int targetReevaluateCounter = 0;
    
    private static final int PEEK_CYCLE_LOG_INTERVAL = 100;
    private int peekCycleLogCounter = 0;

    private static boolean combatDebugLogging = false;
    
    public static void setDebugLoggingEnabled(boolean enabled) {
        combatDebugLogging = enabled;
    }

    private static boolean isDebugLogging() {
        return combatDebugLogging || CoverTacticalGoal.isDebugLoggingEnabled();
    }

    public SoldierCombatGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.threatTracker = new ThreatTracker();
        this.detectionSystem = new DetectionSystem(soldier.getUUID());
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        
        if (soldier.hasValidPingMoveTarget() && soldier.getTarget() == null) {
            return false;
        }
        
        if (soldier.hasValidPingThreatPos()) {
            return true;
        }
        
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            LivingEntity existingTarget = soldier.getTarget();
            if (TargetAcquisition.hasLineOfSight(soldier, existingTarget)) {
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
            if (GunIntegration.getCurrentAmmo(soldier) == 0 && !GunIntegration.isReloading(soldier)) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        
        if (soldier.hasValidPingMoveTarget() && soldier.getTarget() == null) {
            return false;
        }
        
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
            if (GunIntegration.isReloading(soldier)) {
                return true;
            }
            if (GunIntegration.getCurrentAmmo(soldier) == 0) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public void start() {
        if (target != null) {
            soldier.setTarget(target);
            threatTracker.reportThreatDirect(target);
            resetTracking(target);
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
        resetTracking(null);
    }
    
    private void resetTracking(LivingEntity newTarget) {
        if (newTarget == null) {
            trackedTargetUUID = null;
            trackingStartTime = 0;
            trackingProgress = 0.0f;
            currentAccuracy = 0.0f;
            return;
        }
        
        if (trackedTargetUUID == null || !trackedTargetUUID.equals(newTarget.getUUID())) {
            trackedTargetUUID = newTarget.getUUID();
            trackingStartTime = System.currentTimeMillis();
            trackingProgress = 0.0f;
            currentAccuracy = AimAccuracyManager.calculateAccuracy(soldier, newTarget);
        }
    }

    @Override
    public void tick() {
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
        
        if (hasGun) {
            handleGunInitialization();
        }
        
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        
        if (target == null || !target.isAlive()) {
            if (inCover && target == null) {
                if (++targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                    targetReevaluateCounter = 0;
                    findNewTarget();
                }
            } else {
                findNewTarget();
            }
        } else if (!TargetAcquisition.hasLineOfSight(soldier, target)) {
            if (inCover) {
                if (++targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                    targetReevaluateCounter = 0;
                    findNewTarget();
                }
            } else {
                findNewTarget();
            }
        }
        
        if (target != null && target.isAlive()) {
            tickCombat(hasGun);
            updateDebugSync();
        } else {
            tickScanning(potentialTargets);
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (++debugSyncTickCounter % PEEK_CYCLE_LOG_INTERVAL == 0) {
                StevesArmyMod.LOGGER.info("[CombatGoal] Soldier {} tick: target=null, inCover={}, peekState={}, coverState={}",
                    soldier.getId(), inCover, soldier.getPeekController().getState(), coverManager.getState());
            }
            if (inCover) {
                tickCoverPeekCycle(coverManager);
            }
            updateDebugSync();
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
        
        boolean isBolting = GunIntegration.isBolting(soldier);
        boolean isReloading = GunIntegration.isReloading(soldier);
        boolean isDrawing = GunIntegration.isDrawing(soldier);
        
        if (GunIntegration.getCurrentAmmo(soldier) == 0 && !isReloading && !isBolting && !isDrawing) {
            GunIntegration.reload(soldier);
        }
    }
    
    private void tickCombat(boolean hasGun) {
        boolean canSee = TargetAcquisition.hasLineOfSight(soldier, target);
        
        if (canSee) {
            threatTracker.reportThreatDirect(target);
            resetTracking(target);
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
            boolean shouldShoot = canSee || 
                (coverManager.isInCover() && soldier.getPeekController().getState() == PeekController.State.EXPOSED);
            if (shouldShoot) {
                tickGunCombat();
            }
        }
    }

    private void tickGunCombat() {
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        
        boolean isDrawing = GunIntegration.isDrawing(soldier);
        boolean isBolting = GunIntegration.isBolting(soldier);
        boolean isReloading = GunIntegration.isReloading(soldier);
        
        if (wasReloading && !isReloading) {
            GunIntegration.initialData(soldier);
            GunIntegration.draw(soldier);
            wasReloading = false;
            return;
        }
        
        if (isReloading) {
            wasReloading = true;
            return;
        }
        
        if (isDrawing || isBolting) {
            return;
        }
        
        if (coverManager.isInCover() && soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
            GunIntegration.aim(soldier, false);
            return;
        }
        
        currentAimPoint = ExposureCalculator.getBestAimPoint(soldier, target, getCoverBlockPos());
        
        if (!currentAimPoint.canShoot()) {
            pathBlockedCounter++;
            if (pathBlockedCounter >= PATH_BLOCKED_SWITCH_TICKS) {
                StevesArmyMod.LOGGER.info("Path blocked for {} ticks, switching target", pathBlockedCounter);
                pathBlockedCounter = 0;
                if (findNewTarget()) {
                    resetTracking(target);
                }
            }
            return;
        }
        
        if (!FriendlyFireChecker.isSafeToShoot(soldier, currentAimPoint.position, currentAccuracy)) {
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[FriendlyFire] Soldier {} blocked shot - friendly in cone", 
                    soldier.getId());
            }
            return;
        }
        
        pathBlockedCounter = 0;
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < ADS_THRESHOLD) {
            return;
        }
        
        updateTrackingProgress();
        
        float shotThreshold = AimAccuracyManager.calculateShotThreshold(trackingProgress, currentAccuracy);
        float configShotThreshold = StevesArmyConfig.getShotThreshold();
        
        if (shotThreshold < configShotThreshold) {
            targetReevaluateCounter++;
            if (targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                targetReevaluateCounter = 0;
                
                Optional<LivingEntity> betterTarget = findBetterTarget(currentAccuracy);
                if (betterTarget.isPresent()) {
                    this.target = betterTarget.get();
                    soldier.setTarget(target);
                    threatTracker.reportThreatDirect(target);
                    detectionSystem.forceDetect(target);
                    resetTracking(target);
                    StevesArmyMod.LOGGER.info("Switched to better target: {} (higher hit probability)", 
                        target.getName().getString());
                    return;
                }
            }
            
            if (trackingProgress >= 0.8f) {
                StevesArmyMod.LOGGER.debug("Suppressive fire at {} (accuracy: {}, threshold: {})", 
                    target.getName().getString(), currentAccuracy, shotThreshold);
            } else {
                return;
            }
        }
        
        GunIntegration.ShootResult result;
        
        if (AimAccuracyManager.rollHit(currentAccuracy, soldier.level())) {
            result = GunIntegration.shootWithDeviation(soldier, currentAimPoint, 0.0f, 0.0f);
        } else {
            Vec3 missPosition = AimAccuracyManager.calculateMissPosition(target, soldier.level());
            result = GunIntegration.shootAtPosition(soldier, missPosition);
        }
        
        if (result == GunIntegration.ShootResult.SUCCESS) {
            if (coverManager.isInCover()) {
                coverManager.onPeekShot();
            }
        }
        
        switch (result) {
            case SUCCESS -> {}
            case NEED_BOLT -> GunIntegration.bolt(soldier);
            case NO_AMMO -> GunIntegration.reload(soldier);
            case COOLDOWN -> {}
            case IS_BOLTING, IS_RELOADING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            case PATH_BLOCKED -> {
                pathBlockedCounter++;
                if (pathBlockedCounter >= PATH_BLOCKED_SWITCH_TICKS) {
                    StevesArmyMod.LOGGER.info("PATH_BLOCKED result, switching target");
                    pathBlockedCounter = 0;
                    if (findNewTarget()) {
                        resetTracking(target);
                    }
                }
            }
            default -> {}
        }
    }
    
private void tickCoverPeekCycle(CoverBehaviorManager coverManager) {
        PeekController peekCtrl = soldier.getPeekController();
        PeekController.State peekState = peekCtrl.getState();

        if (++peekCycleLogCounter >= PEEK_CYCLE_LOG_INTERVAL) {
            peekCycleLogCounter = 0;
            StevesArmyMod.LOGGER.info("[PeekCycle] Soldier {} tick: peekState={}, target={}, inCover={}, hasThreat={}",
                soldier.getId(), peekState,
                (target != null ? target.getName().getString() : "null"),
                coverManager.isInCover(), soldier.getThreatAwareness().hasActiveThreat());
        }
        
        if (isDebugLogging()) {
            StevesArmyMod.LOGGER.info("[PeekCycle] Soldier {} tick: peekState={}, target={}, isPinned={}, canPeek={}",
                soldier.getId(), peekState,
                (target != null ? target.getName().getString() : "null"),
                coverManager.isPinned(), coverManager.getSuppressionTracker().canPeek());
        }
        
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
    
    private Optional<LivingEntity> findBetterTarget(float currentAccuracy) {
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
            .filter(ts -> ts.hitProbability > currentAccuracy + improvementThreshold)
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
    
    private void updateTrackingProgress() {
        if (target == null || trackingStartTime == 0) {
            return;
        }
        
        float trackingTimeMs = AimAccuracyManager.getTrackingTimeMs(soldier, target);
        long elapsedMs = System.currentTimeMillis() - trackingStartTime;
        
        trackingProgress = Math.min(1.0f, elapsedMs / trackingTimeMs);
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
        List<LivingEntity> potentialTargets = new ArrayList<>();
        
        double maxRange = Math.max(DetectionSystem.FOCUSED_RANGE, DetectionSystem.PERIPHERAL_RANGE);
        
        List<Monster> nearbyMonsters = soldier.level().getEntitiesOfClass(
            Monster.class,
            soldier.getBoundingBox().inflate(maxRange)
        );
        
        for (Monster monster : nearbyMonsters) {
            if (TargetAcquisition.isValidTarget(soldier, monster)) {
                potentialTargets.add(monster);
            }
        }
        
        List<TargetEntity> nearbyTargets = soldier.level().getEntitiesOfClass(
            TargetEntity.class,
            soldier.getBoundingBox().inflate(maxRange)
        );
        
        for (TargetEntity targetEntity : nearbyTargets) {
            if (TargetAcquisition.isValidTarget(soldier, targetEntity)) {
                potentialTargets.add(targetEntity);
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
            StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: {} potential targets, inCover={}", 
                potentialTargets.size(), soldier.getCoverBehaviorManager().isInCover());
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
            StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: no target found ({} potential, {} LOS, inCover={})",
                potentialTargets.size(), losTargets.size(), inCover);
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
            float lockedTrackingProgress = target != null ? trackingProgress : 0;
            float lockedAccuracy = target != null ? currentAccuracy : 0;
            float lockedShotThreshold = target != null ? 
                AimAccuracyManager.calculateShotThreshold(trackingProgress, currentAccuracy) : 0;
            float lockedAdsProgress = target != null ? GunIntegration.getAimProgress(soldier) : 0;
            String lockedAimPointType = target != null && currentAimPoint != null ? 
                currentAimPoint.type.displayName : "";
            boolean lockedBulletPathClear = target != null && currentAimPoint != null ? 
                currentAimPoint.bulletPathClear : false;
            
            PotentialTargetsDebugMessage msg = new PotentialTargetsDebugMessage(
                soldier.getUUID(), lockedTargetUUID, soldier.position(), lockedTargetPos,
                lockedDetectionPoints, lockedDistance, lockedHasLOS, lockedInFocused,
                lockedInPeripheral, lockedIsDetected,
                lockedDistanceFactor, lockedExposureFactor, lockedMovementFactor, lockedBrightnessFactor,
                lockedTrackingProgress, lockedAccuracy, lockedShotThreshold, lockedAdsProgress,
                lockedAimPointType, lockedBulletPathClear,
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
}
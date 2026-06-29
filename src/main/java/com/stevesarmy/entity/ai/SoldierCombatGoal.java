package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.AimAccuracyManager;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatTracker;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PotentialTargetsDebugMessage;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
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
    private float shotDeviationYaw = 0.0f;
    private float shotDeviationPitch = 0.0f;
    private ExposureCalculator.AimPointResult currentAimPoint = null;
    
    private static final float ADS_THRESHOLD = 0.8f;
    private static final float SHOT_THRESHOLD = 0.5f;
    private static final int PATH_BLOCKED_SWITCH_TICKS = 40;
    private int pathBlockedCounter = 0;
    private int debugSyncTickCounter = 0;
    private static final int DEBUG_SYNC_INTERVAL = 5;

    public SoldierCombatGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.threatTracker = new ThreatTracker();
        this.detectionSystem = new DetectionSystem(soldier.getUUID());
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        
        if (soldier.hasValidPingMoveTarget()) {
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
        
        if (soldier.hasValidPingMoveTarget()) {
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
        
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            if (GunIntegration.isReloading(soldier)) {
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
        gunInitialized = false;
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
            shotDeviationYaw = 0.0f;
            shotDeviationPitch = 0.0f;
            return;
        }
        
        if (trackedTargetUUID == null || !trackedTargetUUID.equals(newTarget.getUUID())) {
            trackedTargetUUID = newTarget.getUUID();
            trackingStartTime = System.currentTimeMillis();
            trackingProgress = 0.0f;
            currentAccuracy = AimAccuracyManager.calculateAccuracy(soldier, newTarget);
            
            float maxDeviation = AimAccuracyManager.calculateMaxDeviation(currentAccuracy);
            shotDeviationYaw = (soldier.getRandom().nextFloat() - 0.5f) * maxDeviation;
            shotDeviationPitch = (soldier.getRandom().nextFloat() - 0.5f) * maxDeviation;
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
        
        boolean hasGun = GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier);
        
        if (hasGun) {
            handleGunInitialization();
        }
        
        if (target == null || !target.isAlive() || !TargetAcquisition.hasLineOfSight(soldier, target)) {
            findNewTarget();
        }
        
        if (target != null && target.isAlive()) {
            tickCombat(hasGun);
            updateDebugSync();
        } else {
            tickScanning(potentialTargets);
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
        
        soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        
        if (hasGun && canSee) {
            tickGunCombat();
        }
    }

    private void tickGunCombat() {
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
        
        currentAimPoint = ExposureCalculator.getBestAimPoint(soldier, target);
        
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
        pathBlockedCounter = 0;
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < ADS_THRESHOLD) {
            return;
        }
        
        updateTrackingProgress();
        
        float shotThreshold = AimAccuracyManager.calculateShotThreshold(trackingProgress, currentAccuracy);
        if (shotThreshold < SHOT_THRESHOLD) {
            return;
        }
        
        GunIntegration.ShootResult result = GunIntegration.shootWithDeviation(
            soldier, currentAimPoint, shotDeviationPitch, shotDeviationYaw
        );
        
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
                forcedPos.getX() + 0.5, forcedPos.getY() + 1.0, forcedPos.getZ() + 0.5, 
                30.0F, 30.0F
            );
            return;
        }
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            soldier.getLookControl().setLookAt(
                threatPos.getX() + 0.5, threatPos.getY() + 1.0, threatPos.getZ() + 0.5,
                30.0F, 30.0F
            );
            return;
        }
        
        Optional<BlockPos> lastKnownPos = threatTracker.getLastKnownPosition();
        if (lastKnownPos.isPresent()) {
            BlockPos pos = lastKnownPos.get();
            soldier.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 30.0F, 30.0F);
            return;
        }
        
        Optional<LivingEntity> nearestTarget = potentialTargets.stream()
            .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
        
        if (nearestTarget.isPresent()) {
            soldier.getLookControl().setLookAt(nearestTarget.get(), 30.0F, 30.0F);
        }
    }

    private List<LivingEntity> getPotentialTargets() {
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

    private boolean findNewTarget() {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        
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
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearForcedTarget();
                soldier.clearPingThreatPos();
                StevesArmyMod.LOGGER.info("Switched to forced target: {}", target.getName().getString());
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
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearPingThreatPos();
                StevesArmyMod.LOGGER.info("Found ping-designated target: {}", target.getName().getString());
                return true;
            }
        }
        
        List<LivingEntity> detectedTargets = potentialTargets.stream()
            .filter(detectionSystem::isTargetDetected)
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .collect(Collectors.toList());
        
        if (detectedTargets.isEmpty()) {
            this.target = null;
            return false;
        }
        
        detectedTargets.sort(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
        
        this.target = detectedTargets.get(0);
        soldier.setTarget(target);
        threatTracker.reportThreatDirect(target);
        return true;
    }
    
    public LivingEntity getCurrentTarget() {
        return target;
    }
    
    public DetectionSystem getDetectionSystem() {
        return detectionSystem;
    }
    
    private void updateDebugSync() {
        if (target == null || !target.isAlive()) {
            soldier.setDebugTargetUUID(null);
            return;
        }
        if (!soldier.level().isClientSide) {
            double distance = soldier.distanceTo(target);
            boolean hasLOS = TargetAcquisition.hasLineOfSight(soldier, target);
            boolean inFocused = TargetAcquisition.isInFocusedArc(soldier, target);
            double detectionProgress = detectionSystem.getDetectionProgress(target);
            boolean isDetected = detectionSystem.isTargetDetected(target);
            
            soldier.updateDebugData((float)detectionProgress, isDetected, (float)distance, hasLOS, inFocused);
            soldier.setDebugTargetUUID(target.getUUID());
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
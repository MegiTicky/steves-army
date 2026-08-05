package com.stevesarmy.combat;

import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

public class DetectionSystem {
    
    private final Map<UUID, DetectionState> detectionStates = new HashMap<>();
    private final UUID soldierId;
    
    public static final double FOCUSED_RANGE = 96.0;
    public static final double PERIPHERAL_RANGE = 32.0;
    public static final double FOCUSED_ARC_DEGREES = 90.0;
    public static final double PERIPHERAL_ARC_DEGREES = 180.0;
    
    public static final double BASE_FOCUSED_RATE = 12.0;
    public static final double BASE_PERIPHERAL_RATE = 3.0;
    
    public static final double DETECTION_THRESHOLD = 80.0;
    public static final double DECAY_RATE = 3.0;
    public static final double FIRST_CONTACT_BONUS = 20.0;
    public static final double SHARED_INTEL_FLOOR = 50.0;
    public static final double SHARED_INTEL_POSITION_RADIUS = 2.0;
    
    public DetectionSystem(UUID soldierId) {
        this.soldierId = soldierId;
    }
    
    public void tick(LivingEntity soldier, List<LivingEntity> potentialTargets,
                     SquadThreatIntel squadIntel) {
        long currentTime = soldier.level().getGameTime();
        
        Set<UUID> seenThisTick = new HashSet<>();
        
        for (LivingEntity target : potentialTargets) {
            if (!TargetAcquisition.isValidTarget(soldier, target)) continue;
            
            UUID targetId = target.getUUID();
            seenThisTick.add(targetId);
            
            DetectionState state = detectionStates.computeIfAbsent(targetId, id -> new DetectionState());
            boolean hasSharedIntel = hasFreshSharedIntel(soldier, target, squadIntel, currentTime);
            if (hasSharedIntel) {
                state.accumulatedPoints = Math.max(state.accumulatedPoints, SHARED_INTEL_FLOOR);
            }
            
            double distance = soldier.distanceTo(target);
            
            boolean wasInLOS = state.wasInLOSLastCheck;
            VisibilityRay.Result visibility = TargetAcquisition.getVisibility(soldier, target);
            boolean nowInLOS = visibility.hasContact();
            
            if (nowInLOS) {
                double detectionPoints = calculateDetectionPoints(
                    soldier, target, distance, state, wasInLOS, visibility.spottingMultiplier());
                state.accumulatedPoints += detectionPoints;
                state.ticksSinceLastSeen = 0;
            } else {
                state.accumulatedPoints -= DECAY_RATE;
                state.ticksSinceLastSeen++;
            }
            
            state.accumulatedPoints = Math.max(0, Math.min(200, state.accumulatedPoints));
            if (hasSharedIntel) {
                state.accumulatedPoints = Math.max(state.accumulatedPoints, SHARED_INTEL_FLOOR);
            }
            state.wasInLOSLastCheck = nowInLOS;
            state.lastCheckTime = currentTime;
        }
        
        detectionStates.entrySet().removeIf(entry -> {
            DetectionState state = entry.getValue();
            return !seenThisTick.contains(entry.getKey()) && state.ticksSinceLastSeen > 200;
        });
    }

    private boolean hasFreshSharedIntel(LivingEntity soldier, LivingEntity target,
                                        SquadThreatIntel squadIntel, long currentTime) {
        if (squadIntel == null || squadIntel.isThreatStale(target.getUUID(), currentTime)) {
            return false;
        }

        return squadIntel.getThreat(target.getUUID())
            .filter(knowledge -> knowledge.isAlive
                && knowledge.lastKnownPosition != null
                && knowledge.lastSeenBySoldier != null
                && !knowledge.lastSeenBySoldier.equals(soldierId)
                && target.blockPosition().distSqr(knowledge.lastKnownPosition)
                    <= SHARED_INTEL_POSITION_RADIUS * SHARED_INTEL_POSITION_RADIUS)
            .isPresent();
    }
    
    private double calculateDetectionPoints(LivingEntity soldier, LivingEntity target, double distance,
                                            DetectionState state, boolean wasInLOS, double visibilityMultiplier) {
        double baseRate;
        double maxRange;
        
        boolean inFocusedArc = TargetAcquisition.isInFocusedArc(soldier, target);
        boolean inPeripheralArc = TargetAcquisition.isInPeripheralArc(soldier, target);
        
        if (inFocusedArc && distance <= FOCUSED_RANGE) {
            baseRate = BASE_FOCUSED_RATE;
            maxRange = FOCUSED_RANGE;
        } else if (inPeripheralArc && distance <= PERIPHERAL_RANGE) {
            baseRate = BASE_PERIPHERAL_RATE;
            maxRange = PERIPHERAL_RANGE;
        } else {
            lastBaseRate = 0;
            lastDistanceFactor = 0;
            lastExposureFactor = 0;
            lastMovementFactor = 0;
            lastBrightnessFactor = 0;
            return 0;
        }
        
        double distanceFactor;
        if (distance <= 16.0) {
            distanceFactor = 1.0 + (16.0 - distance) / 16.0;
        } else {
            distanceFactor = 1.0 - Math.pow((distance - 16.0) / (maxRange - 16.0), 2);
        }
        distanceFactor = Math.max(0, distanceFactor);
        double exposureFactor = ExposureCalculator.getExposureFactor(soldier, target);
        double movementFactor = getMovementFactor(target);
        double brightnessFactor = getBrightnessFactor(target);
        
        state.lastDistanceFactor = distanceFactor;
        state.lastExposureFactor = exposureFactor;
        state.lastMovementFactor = movementFactor;
        state.lastBrightnessFactor = brightnessFactor;
        state.lastBaseRate = baseRate;
        
        lastBaseRate = baseRate;
        lastDistanceFactor = distanceFactor;
        lastExposureFactor = exposureFactor;
        lastMovementFactor = movementFactor;
        lastBrightnessFactor = brightnessFactor;
        
        double points = baseRate * distanceFactor * exposureFactor * movementFactor
            * brightnessFactor * visibilityMultiplier;
        
        points *= (0.5 + soldier.level().random.nextDouble());
        
        if (!wasInLOS && inFocusedArc) {
            points += FIRST_CONTACT_BONUS;
        }
        
        return points;
    }
    
    private double getMovementFactor(LivingEntity target) {
        double speedSqr = target.getDeltaMovement().horizontalDistanceSqr();
        
        if (target.isSprinting()) {
            return 1.5;
        } else if (target.isCrouching() || target.isShiftKeyDown()) {
            return 0.3;
        } else if (speedSqr > 0.002) {
            return 1.0;
        } else {
            return 0.7;
        }
    }
    
    private double getBrightnessFactor(LivingEntity target) {
        int lightLevel = target.level().getMaxLocalRawBrightness(target.blockPosition());
        return 0.3 + 0.7 * Math.sqrt(lightLevel / 15.0);
    }
    
    public boolean isTargetDetected(LivingEntity target) {
        DetectionState state = detectionStates.get(target.getUUID());
        return state != null && state.accumulatedPoints >= DETECTION_THRESHOLD;
    }
    
    public double getDetectionProgress(LivingEntity target) {
        DetectionState state = detectionStates.get(target.getUUID());
        if (state == null) return 0;
        return Math.min(1.0, state.accumulatedPoints / DETECTION_THRESHOLD);
    }
    
    public Optional<LivingEntity> getHighestProgressTarget(List<LivingEntity> potentialTargets) {
        LivingEntity best = null;
        double bestProgress = 0;
        
        for (LivingEntity target : potentialTargets) {
            DetectionState state = detectionStates.get(target.getUUID());
            if (state != null && state.accumulatedPoints > bestProgress) {
                bestProgress = state.accumulatedPoints;
                best = target;
            }
        }
        
        return Optional.ofNullable(best);
    }
    
    public void clearTarget(UUID targetId) {
        detectionStates.remove(targetId);
    }
    
    public void clear() {
        detectionStates.clear();
    }
    
    public void forceDetect(LivingEntity target) {
        DetectionState state = detectionStates.computeIfAbsent(target.getUUID(), id -> new DetectionState());
        state.accumulatedPoints = DETECTION_THRESHOLD;
        state.wasInLOSLastCheck = true;
        state.ticksSinceLastSeen = 0;
    }

    /** Adds a bounded detection impulse from a discrete cue such as a gunshot. */
    public void addDetectionPoints(LivingEntity target, double points) {
        if (target == null || !Double.isFinite(points) || points <= 0) return;

        DetectionState state = detectionStates.computeIfAbsent(target.getUUID(), id -> new DetectionState());
        state.accumulatedPoints = Math.min(200, state.accumulatedPoints + points);
    }
    
    public DetectionState getDetectionState(UUID targetId) {
        return detectionStates.get(targetId);
    }
    
    public Map<UUID, DetectionState> getAllDetectionStates() {
        return Collections.unmodifiableMap(detectionStates);
    }
    
    public double getLastDistanceFactor() {
        return lastDistanceFactor;
    }
    
    public double getLastExposureFactor() {
        return lastExposureFactor;
    }
    
    public double getLastMovementFactor() {
        return lastMovementFactor;
    }
    
    public double getLastBrightnessFactor() {
        return lastBrightnessFactor;
    }
    
    public double getLastBaseRate() {
        return lastBaseRate;
    }
    
    private double lastDistanceFactor = 0;
    private double lastExposureFactor = 0;
    private double lastMovementFactor = 0;
    private double lastBrightnessFactor = 0;
    private double lastBaseRate = 0;
    
    public static class DetectionState {
        public double accumulatedPoints = 0;
        public boolean wasInLOSLastCheck = false;
        public long lastCheckTime = 0;
        public int ticksSinceLastSeen = 0;
        public double lastDistanceFactor = 0;
        public double lastExposureFactor = 0;
        public double lastMovementFactor = 0;
        public double lastBrightnessFactor = 0;
        public double lastBaseRate = 0;
    }
    
    public static double computeDistanceFactor(double distance, double maxRange) {
        return 1.0 - Math.pow(distance / maxRange, 2);
    }
    
    public static double computeExposureFactor(LivingEntity soldier, LivingEntity target) {
        return ExposureCalculator.getExposureFactor(soldier, target);
    }
    
    public static double computeMovementFactor(LivingEntity target) {
        double speedSqr = target.getDeltaMovement().horizontalDistanceSqr();
        
        if (target.isSprinting()) {
            return 1.5;
        } else if (target.isCrouching() || target.isShiftKeyDown()) {
            return 0.3;
        } else if (speedSqr > 0.002) {
            return 1.0;
        } else {
            return 0.7;
        }
    }
    
    public static double computeBrightnessFactor(LivingEntity target) {
        int lightLevel = target.level().getMaxLocalRawBrightness(target.blockPosition());
        return 0.3 + 0.7 * Math.sqrt(lightLevel / 15.0);
    }
}

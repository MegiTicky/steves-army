package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadThreatIntel;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public class DetectionSystem {

    private final Map<UUID, DetectionState> detectionStates = new HashMap<>();
    private final UUID soldierId;
    private final boolean machineGunnerPipeline;
    private final Set<UUID> seenThisTick = new HashSet<>();

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

    private static final long STATE_EXPIRY_TICKS = 200;

    public DetectionSystem(UUID soldierId) {
        this(soldierId, false);
    }

    public DetectionSystem(UUID soldierId, boolean machineGunnerPipeline) {
        this.soldierId = soldierId;
        this.machineGunnerPipeline = machineGunnerPipeline;
    }

    public double getFocusedRange() {
        return machineGunnerPipeline
            ? StevesArmyConfig.getMachineGunnerDetectionDistance()
            : StevesArmyConfig.getBasicDetectionDistance();
    }

    public static double getFocusedRangeFor(SoldierEntity soldier) {
        return soldier instanceof MachineGunnerEntity
            ? StevesArmyConfig.getMachineGunnerDetectionDistance()
            : StevesArmyConfig.getBasicDetectionDistance();
    }

    public static double getMaximumConfiguredFocusedRange() {
        return Math.max(StevesArmyConfig.getBasicDetectionDistance(),
            StevesArmyConfig.getMachineGunnerDetectionDistance());
    }

    /**
     * Performs a full detection scan. Returns a coherent scan result that all
     * downstream consumers share, eliminating repeated lookups.
     */
    public DetectionScanResult tick(LivingEntity soldier, List<LivingEntity> potentialTargets,
                                   SquadThreatIntel squadIntel) {
        long currentTime = soldier.level().getGameTime();
        PerformanceMetrics.recordDetectionTick(potentialTargets.size(), machineGunnerPipeline);

        seenThisTick.clear();
        List<TargetObservation> observations = new ArrayList<>(potentialTargets.size());

        double focusedRange = getFocusedRange();
        double focusedRangeSqr = focusedRange * focusedRange;
        double peripheralRange = PERIPHERAL_RANGE;
        double peripheralRangeSqr = peripheralRange * peripheralRange;
        double maxRange = Math.max(focusedRange, peripheralRange);
        double maxRangeSqr = maxRange * maxRange;

        for (LivingEntity target : potentialTargets) {
            if (!TargetAcquisition.isValidTarget(soldier, target)) continue;

            UUID targetId = target.getUUID();
            if (!seenThisTick.add(targetId)) {
                PerformanceMetrics.recordDetectionDuplicateCandidate();
                continue;
            }

            DetectionState state = detectionStates.computeIfAbsent(targetId, id -> new DetectionState());
            boolean hasSharedIntel = hasFreshSharedIntel(soldier, target, squadIntel, currentTime);
            if (hasSharedIntel) {
                state.accumulatedPoints = Math.max(state.accumulatedPoints, SHARED_INTEL_FLOOR);
            }

            double distanceSqr = soldier.distanceToSqr(target);
            boolean wasInLOS = state.wasInLOSLastCheck;

            // --- Cheap rejection before expensive visibility tracing ---
            if (distanceSqr > maxRangeSqr) {
                PerformanceMetrics.recordDetectionSkippedOutOfRange();
                state.accumulatedPoints -= DECAY_RATE;
                state.wasInLOSLastCheck = false;
                state.lastCheckTime = currentTime;
                observations.add(new TargetObservation(target, TargetBand.OUT_OF_RANGE,
                    false, false, 0, 0, 0, 0, 0, currentTime));
                continue;
            }

            // Angular classification uses observer look direction and horizontal distance.
            boolean inFocusedArc = TargetAcquisition.isInFocusedArc(soldier, target);
            boolean inPeripheralArc = TargetAcquisition.isInPeripheralArc(soldier, target);

            TargetBand band;
            if (inFocusedArc && distanceSqr <= focusedRangeSqr) {
                band = TargetBand.FOCUSED;
            } else if (inPeripheralArc && distanceSqr <= peripheralRangeSqr) {
                band = TargetBand.PERIPHERAL;
            } else {
                // Outside detection arcs: no detection points, but we still
                // contact-report if geometric LOS holds (omnidirectional contacts).
                PerformanceMetrics.recordDetectionSkippedOutOfFOV();
                band = TargetBand.OUTSIDE_FOV;
            }

            // --- Skip expensive exposure when detection is already capped ---
            boolean skipExposure = state.accumulatedPoints >= 200 && band != TargetBand.OUTSIDE_FOV
                && band != TargetBand.OUT_OF_RANGE;

            // --- Primary visibility ray ---
            VisibilityRay.Result visibility = TargetAcquisition.getVisibility(soldier, target);
            PerformanceMetrics.recordDetectionPrimaryRay(false);
            boolean nowInLOS = visibility.hasContact();

            double exposureFactor = 0;
            double movementFactor = 0;
            double brightnessFactor = 0;
            double detectionPoints = 0;

            if (nowInLOS && band != TargetBand.OUTSIDE_FOV && band != TargetBand.OUT_OF_RANGE) {
                if (skipExposure) {
                    PerformanceMetrics.recordDetectionSkippedCapped();
                    // Movement and brightness are cheap; still compute them for debug display.
                    movementFactor = getMovementFactor(target);
                    brightnessFactor = getBrightnessFactor(target);
                    double distanceFactor = computeDistanceFactor(
                        Math.sqrt(distanceSqr), band == TargetBand.FOCUSED ? focusedRange : peripheralRange);
                    detectionPoints = computePointsForBand(band) * distanceFactor
                        * state.lastExposureFactor * movementFactor * brightnessFactor
                        * visibility.spottingMultiplier();
                    detectionPoints *= (0.5 + soldier.level().random.nextDouble());
                    if (!wasInLOS && band == TargetBand.FOCUSED) {
                        detectionPoints += FIRST_CONTACT_BONUS;
                    }
                    exposureFactor = state.lastExposureFactor;
                } else {
                    exposureFactor = ExposureCalculator.getExposureFactor(soldier, target);
                    PerformanceMetrics.recordDetectionExposureRay(false);
                    movementFactor = getMovementFactor(target);
                    brightnessFactor = getBrightnessFactor(target);

                    double distanceFactor = computeDistanceFactor(
                        Math.sqrt(distanceSqr), band == TargetBand.FOCUSED ? focusedRange : peripheralRange);
                    double baseRate = band == TargetBand.FOCUSED ? BASE_FOCUSED_RATE : BASE_PERIPHERAL_RATE;

                    state.lastDistanceFactor = distanceFactor;
                    state.lastExposureFactor = exposureFactor;
                    state.lastMovementFactor = movementFactor;
                    state.lastBrightnessFactor = brightnessFactor;
                    state.lastBaseRate = baseRate;

                    detectionPoints = baseRate * distanceFactor * exposureFactor * movementFactor
                        * brightnessFactor * visibility.spottingMultiplier();
                    detectionPoints *= (0.5 + soldier.level().random.nextDouble());
                    if (!wasInLOS && band == TargetBand.FOCUSED) {
                        detectionPoints += FIRST_CONTACT_BONUS;
                    }
                }
            } else if (!nowInLOS) {
                state.accumulatedPoints -= DECAY_RATE;
            }

            if (detectionPoints > 0) {
                state.accumulatedPoints += detectionPoints;
            }

            state.accumulatedPoints = Math.max(0, Math.min(200, state.accumulatedPoints));
            if (hasSharedIntel) {
                state.accumulatedPoints = Math.max(state.accumulatedPoints, SHARED_INTEL_FLOOR);
            }
            state.wasInLOSLastCheck = nowInLOS;
            state.lastCheckTime = currentTime;

            observations.add(new TargetObservation(target, band, nowInLOS,
                state.accumulatedPoints >= DETECTION_THRESHOLD,
                state.accumulatedPoints, exposureFactor, movementFactor,
                brightnessFactor, detectionPoints, currentTime));
        }

        // Expire states using timestamp rather than the counter-based approach.
        // Targets that leave the candidate list entirely will be cleaned up once
        // their last evaluation is older than STATE_EXPIRY_TICKS.
        long cutoff = currentTime - STATE_EXPIRY_TICKS;
        detectionStates.entrySet().removeIf(entry -> {
            DetectionState state = entry.getValue();
            UUID id = entry.getKey();
            if (seenThisTick.contains(id)) return false;
            return state.lastCheckTime > 0 && state.lastCheckTime < cutoff;
        });

        return new DetectionScanResult(currentTime, observations);
    }

    /** Applies passive decay while an optimization profile defers a scan. */
    public void advanceWithoutScan(long currentTime) {
        for (DetectionState state : detectionStates.values()) {
            if (state.lastCheckTime <= 0) {
                state.lastCheckTime = currentTime;
                continue;
            }
            long elapsed = Math.max(0, currentTime - state.lastCheckTime);
            if (elapsed == 0) continue;
            state.accumulatedPoints = Math.max(0,
                state.accumulatedPoints - DECAY_RATE * elapsed);
            state.lastCheckTime = currentTime;
        }

        long cutoff = currentTime - STATE_EXPIRY_TICKS;
        detectionStates.entrySet().removeIf(entry -> {
            DetectionState state = entry.getValue();
            return state.lastCheckTime > 0 && state.lastCheckTime < cutoff;
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

    private static double computePointsForBand(TargetBand band) {
        return band == TargetBand.FOCUSED ? BASE_FOCUSED_RATE : BASE_PERIPHERAL_RATE;
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
        state.lastCheckTime = System.currentTimeMillis();
    }

    /** Returns the most recent LOS result used by the detection state machine. */
    public boolean wasTargetInLOS(LivingEntity target) {
        DetectionState state = detectionStates.get(target.getUUID());
        return state != null && state.wasInLOSLastCheck;
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
        public double lastDistanceFactor = 0;
        public double lastExposureFactor = 0;
        public double lastMovementFactor = 0;
        public double lastBrightnessFactor = 0;
        public double lastBaseRate = 0;
    }

    public enum TargetBand {
        FOCUSED,
        PERIPHERAL,
        OUTSIDE_FOV,
        OUT_OF_RANGE
    }

    /**
     * A single observer-target observation from one scan. All downstream
     * consumers read from this instead of re-querying detection state.
     */
    public record TargetObservation(
        LivingEntity target,
        TargetBand band,
        boolean visible,
        boolean detected,
        double accumulatedPoints,
        double exposureFactor,
        double movementFactor,
        double brightnessFactor,
        double detectionPoints,
        long observationTick
    ) {}

    /**
     * The complete result of one detection scan. Returned by {@link #tick}
     * and consumed by the combat goal, squad intel, contact tracker, etc.
     */
    public record DetectionScanResult(
        long scanTick,
        List<TargetObservation> observations
    ) {
        public Optional<TargetObservation> find(LivingEntity target) {
            for (TargetObservation obs : observations) {
                if (obs.target() == target) return Optional.of(obs);
            }
            return Optional.empty();
        }
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

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.TallyhoCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.combat.AimAccuracyManager;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.DetectionViewpoint;
import com.stevesarmy.combat.EnemyContactTracker;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.FriendlyFireChecker;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.VehicleCrewManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Vehicle crew behavior. Crew soldiers board the owner's ship through the same
 * seat transport as riflemen (VS2's rider mixins handle the shipyard-space seat
 * tracking), and their AI keeps ticking while seated so they can man a tallyho
 * hull MG (aim + fire with the shared detection system, gunner-discipline
 * accuracy model) or periscope (scan + share intel with the owner's overlay).
 * Detection runs from the camera's world position via DetectionViewpoint;
 * everything tallyho-side is reflection. Without tallyho the crew simply rides
 * along like any other transported soldier.
 */
public class VehicleCrewGoal extends Goal {
    private enum Phase { SEEK, IDLE, GUNNER, OBSERVER }

    private static final float FIRE_TOLERANCE_DEGREES = 2.0F;
    private static final int SWEEP_PERIOD_TICKS = 240;
    private static final double OBSERVER_SWEEP_RANGE = 32.0;
    private static final float STATION_RELEASE_DISTANCE_FACTOR = 1.5F;

    private final SoldierEntity soldier;
    private Phase phase = Phase.SEEK;

    private DetectionSystem detectionSystem;
    private Entity station;
    /** The station camera's position in world space (entity pos is shipyard space). */
    private Vec3 stationWorldPos;
    private LivingEntity target;
    private float aimQuality;
    private float bloom;
    private int burstShots;
    private int burstPauseTicks;
    private int dutyScanCooldown;
    private int sweepTick;

    public VehicleCrewGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return soldier.isAlive() && !soldier.level().isClientSide && crewEnabled();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    private static boolean crewEnabled() {
        return StevesArmyConfig.VEHICLE_CREW_ENABLED.get() && VS2Compat.isEnabled();
    }

    /** Crewed optics detect far beyond infantry range; a hull MG engages at MG range. */
    private DetectionSystem crewDetectionSystem() {
        return new DetectionSystem(soldier.getUUID())
            .withFocusedRange(StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get());
    }

    @Override
    public void tick() {
        if (soldier.isPassenger()) {
            if (phase == Phase.SEEK) {
                enterIdle();
            }
            tickSeated();
        } else {
            // Unmounted crew: post itself where it stands and let the duty scan
            // take over until it claims a station.
            if (phase == Phase.SEEK) {
                enterIdle();
            }
            tickSeated();
        }
    }

    @Override
    public void stop() {
        releaseStation("goal stopped");
        phase = Phase.SEEK;
    }

    // --- Seated / posted phases ----------------------------------------------

    private void enterIdle() {
        phase = Phase.IDLE;
        dutyScanCooldown = 0;
        // Never crawl at a station; the gate in setLowCrouching covers aboard-ship
        // requests, this clears anything picked up before seating.
        soldier.setLowCrouching(false);
        detectionSystem = crewDetectionSystem();
    }

    private void tickSeated() {
        switch (phase) {
            case IDLE -> {
                if (dutyScanCooldown > 0) {
                    dutyScanCooldown--;
                    return;
                }
                dutyScanCooldown = StevesArmyConfig.VEHICLE_CREW_DUTY_SCAN_INTERVAL.get();
                tryAcquireDuty();
            }
            case GUNNER, OBSERVER -> {
                if (VS2Compat.isAutoTransportBlocked(soldier)) {
                    // The player just ordered this crew off the vehicle.
                    releaseStation("manual dismount");
                    return;
                }
                if (!validateStation()) {
                    return;
                }
                if (!soldier.isPassenger()) {
                    keepAtPost();
                }
                if (phase == Phase.GUNNER) {
                    tickGunner();
                } else {
                    tickObserver();
                }
            }
            default -> {
            }
        }
    }

    private void tryAcquireDuty() {
        if (!(soldier.level() instanceof ServerLevel level) || !TallyhoCompat.isAvailable()) {
            return;
        }
        // A recent manual dismount (vehicle wheel) also blocks the crew's own re-post.
        if (VS2Compat.isAutoTransportBlocked(soldier)) {
            return;
        }
        double reach = StevesArmyConfig.VEHICLE_CREW_STATION_REACH.get();
        Entity mg = findStation(level, true, reach);
        Entity choice = mg != null ? mg : findStation(level, false, reach);
        if (choice == null) {
            return;
        }
        if (!VehicleCrewManager.claim(choice.getUUID(), soldier.getUUID())) {
            return;
        }
        station = choice;
        phase = TallyhoCompat.isHullMG(choice) ? Phase.GUNNER : Phase.OBSERVER;
        soldier.setVehicleCrewActive(true);
        aimQuality = 0.0F;
        bloom = 0.0F;
        burstShots = 0;
        burstPauseTicks = 0;
        sweepTick = 0;
        if (detectionSystem == null) {
            detectionSystem = crewDetectionSystem();
        }
        if (!soldier.isPassenger()) {
            keepAtPost();
        }
        StevesArmyMod.LOGGER.info("[VehicleCrew] soldier={} claimed {} {}",
            soldier.getId(), phase == Phase.GUNNER ? "hull MG" : "periscope", choice.getId());
    }

    private record StationCandidate(Entity entity, boolean sameShip, double distance) {}

    private Entity findStation(ServerLevel level, boolean hullMg, double reach) {
        Long crewShipId = VS2Compat.getShipIdOf(VS2Compat.getMountedShip(soldier));
        List<StationCandidate> candidates = new ArrayList<>();
        double reachSqr = reach * reach;
        boolean seatedOnShip = crewShipId != null && soldier.isPassenger();
        for (Entity entity : level.getAllEntities()) {
            boolean matches = hullMg ? TallyhoCompat.isHullMG(entity) : TallyhoCompat.isPeriscope(entity);
            if (!matches || entity.isRemoved() || !entity.isAlive()) {
                continue;
            }
            if (TallyhoCompat.isPlayerPossessed(entity)) {
                continue;
            }
            UUID claimant = VehicleCrewManager.claimantOf(entity.getUUID());
            if (claimant != null && !claimant.equals(soldier.getUUID())) {
                continue;
            }
            boolean sameShip = crewShipId != null
                && crewShipId.equals(VS2Compat.getShipIdOf(VS2Compat.getMountedShip(entity)));
            double distanceSqr;
            if (seatedOnShip) {
                // A seated soldier's stored position is a stale world coordinate
                // (often the sea floor where it boarded); the live position is its
                // seat's. Both seat and station are shipyard-space, so compare
                // shipyard-to-shipyard. Same-ship stations skip the reach gate:
                // a seated gunner mans its own ship's guns wherever they are.
                distanceSqr = entity.position().distanceToSqr(soldier.getVehicle().position());
                if (!sameShip && distanceSqr > reachSqr) {
                    continue;
                }
            } else {
                // Station entities live in shipyard space; for an unmounted soldier
                // distances must use the station's world-space position.
                distanceSqr = stationWorldPosOf(entity).distanceToSqr(soldier.position());
                if (distanceSqr > reachSqr) {
                    continue;
                }
            }
            candidates.add(new StationCandidate(entity, sameShip, distanceSqr));
        }
        return candidates.stream()
            .min(Comparator.comparing((StationCandidate c) -> !c.sameShip)
                .thenComparingDouble(c -> c.distance))
            .map(StationCandidate::entity)
            .orElse(null);
    }

    private boolean validateStation() {
        double reach = StevesArmyConfig.VEHICLE_CREW_STATION_REACH.get();
        Object soldierShip = VS2Compat.getMountedShip(soldier);
        boolean seatedOnShip = soldier.isPassenger()
            && VS2Compat.getShipIdOf(soldierShip) != null;
        // Removal/possession are the only loss conditions for a seated gunner:
        // the seat can be far from the gun in shipyard space, so a reach gate here
        // (which findStation skips for same-ship stations) would re-release the
        // station every tick and the gunner phase would never run.
        boolean invalid = station == null || station.isRemoved() || !station.isAlive()
            || TallyhoCompat.isPlayerPossessed(station);
        if (!invalid && !seatedOnShip) {
            Vec3 stationWorld = stationWorldPosOf(station);
            invalid = stationWorld != null
                && soldier.distanceToSqr(stationWorld) > reach * reach * STATION_RELEASE_DISTANCE_FACTOR;
        }
        if (!invalid && !VehicleCrewManager.claim(station.getUUID(), soldier.getUUID())) {
            invalid = true;
        }
        if (invalid) {
            releaseStation(station != null && TallyhoCompat.isPlayerPossessed(station)
                ? "player took the station" : "station lost");
            dutyScanCooldown = 0;
            return false;
        }
        return true;
    }

    private void releaseStation(String reason) {
        if (station != null) {
            VehicleCrewManager.release(station.getUUID(), soldier.getUUID());
            StevesArmyMod.LOGGER.info("[VehicleCrew] soldier={} released station={} ({})",
                soldier.getId(), station.getId(), reason);
            station = null;
        }
        stationWorldPos = null;
        if (phase == Phase.GUNNER || phase == Phase.OBSERVER) {
            phase = Phase.IDLE;
            dutyScanCooldown = 0;
        }
        soldier.setVehicleCrewActive(false);
        DetectionViewpoint.clear(soldier);
        target = null;
        aimQuality = 0.0F;
        bloom = 0.0F;
        burstShots = 0;
        burstPauseTicks = 0;
    }

    // --- Gunner -------------------------------------------------------------

    private void tickGunner() {
        beginStationView();
        List<LivingEntity> candidates = computeCandidates();
        DetectionSystem.DetectionScanResult scan = detectionSystem.tick(
            soldier, candidates, squadIntel());
        LivingEntity best = pickTarget(scan);
        if (best == null) {
            target = null;
            aimQuality = Math.max(0.0F,
                aimQuality - StevesArmyConfig.getAimQualityLosDecayRate());
            decayBloom();
            return;
        }
        target = best;
        reportIntel(best);

        ExposureCalculator.AimPointResult aimPoint =
            ExposureCalculator.getBestAimPoint(soldier, best);
        boolean inLos = TargetAcquisition.hasLineOfSight(soldier, best);
        updateAimQuality(best, inLos);
        if (aimPoint == null || !aimPoint.canShoot() || !inLos) {
            decayBloom();
            return;
        }
        if (!FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality)) {
            decayBloom();
            return;
        }

            float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
            float error = TallyhoCompat.aimTowards(station, aimTargetForStation(aimPoint.position),
                traverse, 0.0F, 0.0F);
            if (soldier.tickCount % 60 == 0) {
                StevesArmyMod.LOGGER.info(
                    "[VehicleCrew] gunner soldier={} target={} detected={} aimQuality={} aimError={}deg ready={} belt={} burstPause={}",
                    soldier.getId(), best.getId(), detectionSystem.isTargetDetected(best),
                    String.format("%.2f", aimQuality), String.format("%.1f", error),
                    TallyhoCompat.isReadyToFire(station), TallyhoCompat.hasAmmo(station),
                    burstPauseTicks);
            }
            decayBloom();
        // Only open fire once the detection system has actually classified the
        // contact, mirroring infantry trigger discipline.
        if (!detectionSystem.isTargetDetected(best)
            || aimQuality < StevesArmyConfig.VEHICLE_CREW_MIN_AIM_TO_FIRE.get().floatValue()
            || error > FIRE_TOLERANCE_DEGREES) {
            return;
        }
        if (!fireBurstGate()) {
            return;
        }

        float yawSigma = AimAccuracyManager.getYawSigma(aimQuality)
            + (float) aimPoint.concealment * 2.00F;
        float pitchSigma = AimAccuracyManager.getPitchSigma(aimQuality)
            + (float) aimPoint.concealment * 0.75F;
        float bloomScale = 1.0F + bloom;
        float[] deviation = AimAccuracyManager.sampleGaussianDeviation(
            aimQuality, yawSigma * bloomScale, pitchSigma * bloomScale, soldier.level());

        // Point the gun at the deviated direction; tallyho clamps to turret limits.
        TallyhoCompat.aimTowards(station, aimTargetForStation(aimPoint.position), traverse,
            deviation[1], deviation[0]);
        TallyhoCompat.fire(station, soldier);

        bloom = Math.min(StevesArmyConfig.VEHICLE_CREW_BLOOM_MAX.get().floatValue(),
            bloom + StevesArmyConfig.VEHICLE_CREW_BLOOM_PER_SHOT.get().floatValue());
        burstShots++;
    }

    private boolean fireBurstGate() {
        if (burstPauseTicks > 0) {
            burstPauseTicks--;
            return false;
        }
        if (burstShots >= StevesArmyConfig.VEHICLE_CREW_BURST_SIZE.get()) {
            burstShots = 0;
            burstPauseTicks = StevesArmyConfig.VEHICLE_CREW_BURST_PAUSE.get();
            return false;
        }
        return TallyhoCompat.isReadyToFire(station) && TallyhoCompat.hasAmmo(station);
    }

    private void decayBloom() {
        bloom = Math.max(0.0F, bloom - StevesArmyConfig.VEHICLE_CREW_BLOOM_DECAY.get().floatValue());
    }

    private void updateAimQuality(LivingEntity currentTarget, boolean inLos) {
        if (inLos) {
            float targetQuality = AimAccuracyManager.getTargetAimQuality(soldier, currentTarget);
            float buildRate = AimAccuracyManager.getBuildRate(soldier, currentTarget);
            if (aimQuality < targetQuality) {
                aimQuality = Mth.lerp(buildRate, aimQuality, targetQuality);
            } else {
                aimQuality = Mth.lerp(buildRate * 0.5F, aimQuality, targetQuality);
            }
        } else {
            aimQuality -= StevesArmyConfig.getAimQualityLosDecayRate();
        }
        aimQuality = Mth.clamp(aimQuality, 0.0F, 1.0F);
    }

    // --- Observer -----------------------------------------------------------

    private void tickObserver() {
        beginStationView();
        List<LivingEntity> candidates = computeCandidates();
        DetectionSystem.DetectionScanResult scan = detectionSystem.tick(
            soldier, candidates, squadIntel());
        LivingEntity best = pickTarget(scan);
        float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
        if (best != null) {
            target = best;
            reportIntel(best);
            // Keep the optics on the contact so intel and detection keep refreshing.
            TallyhoCompat.aimTowards(station, aimTargetForStation(best.getEyePosition()),
                traverse, 0.0F, 0.0F);
            return;
        }
        target = null;
        sweepOptics(traverse);
    }

    private void sweepOptics(float traverse) {
        sweepTick++;
        if (sweepTick >= SWEEP_PERIOD_TICKS) {
            sweepTick = 0;
        }
        float progress = sweepTick / (float) SWEEP_PERIOD_TICKS;
        float baseYaw = TallyhoCompat.getBaseYaw(station);
        float yawLimit = TallyhoCompat.getYawLimit(station);

        float sweepYaw;
        if (yawLimit >= 359.0F) {
            // Full-circle periscope: one slow rotation per sweep period.
            sweepYaw = baseYaw + progress * 360.0F;
        } else {
            // Ping-pong sweep across the available arc.
            float halfArc = Math.max(10.0F, yawLimit) * 0.5F;
            float triangle = progress < 0.5F ? progress * 2.0F : 2.0F - progress * 2.0F;
            sweepYaw = baseYaw - halfArc + triangle * 2.0F * halfArc;
        }

        double yawRad = Math.toRadians(sweepYaw);
        Vec3 direction = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        Vec3 sweepTarget = DetectionViewpoint.getEyePosition(soldier)
            .add(direction.scale(OBSERVER_SWEEP_RANGE));
        TallyhoCompat.aimTowards(station, aimTargetForStation(sweepTarget), traverse, 0.0F, 0.0F);
    }

    // --- Shared helpers -------------------------------------------------------

    /** World-space position of a station entity (its raw position is shipyard space). */
    private static Vec3 stationWorldPosOf(Entity station) {
        Object ship = VS2Compat.getMountedShip(station);
        Vec3 world = VS2Compat.shipToWorldPosition(ship, station.position());
        return world != null ? world : station.position();
    }

    /**
     * Keeps an unmounted gunner standing on the deck beside its station. The
     * ship may drift under the soldier; if it strays, teleport back to a deck
     * surface point found in shipyard space (where the ship's blocks live).
     */
    private void keepAtPost() {
        if (!(soldier.level() instanceof ServerLevel level) || station == null) {
            return;
        }
        Vec3 post = postPosition(level);
        if (post == null) {
            return;
        }
        double dx = soldier.getX() - post.x;
        double dz = soldier.getZ() - post.z;
        if (dx * dx + dz * dz > 1.0 || Math.abs(soldier.getY() - post.y) > 2.0) {
            soldier.getNavigation().stop();
            soldier.teleportTo(post.x, post.y, post.z);
        }
    }

    @javax.annotation.Nullable
    private Vec3 postPosition(ServerLevel level) {
        BlockPos base = station.blockPosition();
        BlockPos surface = base;
        for (int dy = 0; dy <= 4; dy++) {
            BlockPos candidate = base.below(dy);
            if (!level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()) {
                surface = candidate.above();
                break;
            }
        }
        Object ship = VS2Compat.getMountedShip(station);
        Vec3 world = VS2Compat.shipToWorldPosition(ship,
            new Vec3(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5));
        return world != null ? world
            : new Vec3(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5);
    }

    private void beginStationView() {
        Object ship = VS2Compat.getMountedShip(station);
        // Ship-mounted camera entities live in shipyard coordinates server-side;
        // detection must run from the camera's true world position.
        Vec3 cameraWorld = stationWorldPosOf(station);
        stationWorldPos = cameraWorld;
        Vec3 look = VS2Compat.shipToWorldDirection(ship, station.getLookAngle());
        DetectionViewpoint.set(soldier, cameraWorld, look);
    }

    /**
     * Tallyho computes the aim direction as {@code target - camera.getPosition()},
     * mixing a world-space target with the camera's shipyard-space position. Feeding
     * it a pseudo-target makes that difference equal the true world-space direction.
     */
    private Vec3 aimTargetForStation(Vec3 worldTarget) {
        Vec3 cameraPos = stationWorldPos != null ? stationWorldPos : station.position();
        return station.position().add(worldTarget.subtract(cameraPos));
    }

    private List<LivingEntity> computeCandidates() {
        double maxRange = Math.max(detectionSystem.getFocusedRange(),
            DetectionSystem.PERIPHERAL_RANGE);
        Vec3 center = station.position();
        return soldier.level().getEntitiesOfClass(LivingEntity.class,
            new AABB(center, center).inflate(maxRange),
            entity -> entity != soldier && entity != station
                && TargetAcquisition.isValidTarget(soldier, entity)
                && !soldier.isFriendlyTo(entity));
    }

    private LivingEntity pickTarget(DetectionSystem.DetectionScanResult scan) {
        LivingEntity bestDetected = null;
        double bestDetectedPoints = 0.0;
        LivingEntity bestVisible = null;
        double bestVisiblePoints = 0.0;
        for (DetectionSystem.TargetObservation observation : scan.observations()) {
            if (!observation.target().isAlive()) {
                continue;
            }
            if (observation.detected()
                && observation.accumulatedPoints() > bestDetectedPoints) {
                bestDetectedPoints = observation.accumulatedPoints();
                bestDetected = observation.target();
            }
            if (observation.visible() && observation.accumulatedPoints() > bestVisiblePoints) {
                bestVisiblePoints = observation.accumulatedPoints();
                bestVisible = observation.target();
            }
        }
        return bestDetected != null ? bestDetected : bestVisible;
    }

    private void reportIntel(LivingEntity threat) {
        if (!threat.isAlive() || !TargetAcquisition.isValidTarget(soldier, threat)) {
            return;
        }
        SquadThreatIntel intel = squadIntel();
        if (intel != null) {
            ExposureCalculator.AimPointResult aimPoint =
                ExposureCalculator.getBestAimPoint(soldier, threat);
            intel.reportThreat(soldier.getUUID(), threat, threat.blockPosition(),
                aimPoint != null && aimPoint.canShoot() ? aimPoint.position : threat.getEyePosition(),
                threat.getEyePosition(), 1.0F);
        }
        EnemyContactTracker.reportContact(soldier, threat);
    }

    @javax.annotation.Nullable
    private SquadThreatIntel squadIntel() {
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Optional<SquadData> squad = SquadManager.get(serverLevel).getSquadById(squadId);
        return squad.map(SquadData::getThreatIntel).orElse(null);
    }
}

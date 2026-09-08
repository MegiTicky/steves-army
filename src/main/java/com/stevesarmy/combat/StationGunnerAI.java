package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.TallyhoCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AI lives on the vehicle station, not on the soldier. When a crew soldier
 * claims a tallyho hull MG or periscope, this ticker drives the gun: detection,
 * aiming, and firing all run from the station's camera in strict world space.
 *
 * The soldier itself is a pure passenger whose AI freezes while transported
 * (like every other soldier) — that is what eliminates the mixed world/shipyard
 * coordinate crashes: VS objects keep their entities in shipyard space, and any
 * soldier-side query or ray that straddles the two spaces either gets rejected
 * by VS2 or (worse) marches a ray through millions of unloaded chunks.
 *
 * Fail-closed contract: if the ship-to-world transform is unavailable, the tick
 * does nothing. The gun never aims or fires on guessed coordinates.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class StationGunnerAI {
    private static final float FIRE_TOLERANCE_DEGREES = 2.0F;
    private static final int SWEEP_PERIOD_TICKS = 240;
    private static final double OBSERVER_SWEEP_RANGE = 32.0;

    private static final Map<UUID, StationState> active = new ConcurrentHashMap<>();
    private static long lastDutyFailureLog;

    private static final class StationState {
        final Entity station;
        final SoldierEntity soldier;
        final boolean gunner;
        final DetectionSystem detection;
        Vec3 cameraWorld;
        LivingEntity target;
        float aimQuality;
        float bloom;
        int burstShots;
        int burstPauseTicks;
        int sweepTick;
        int statusTick;

        StationState(Entity station, SoldierEntity soldier, boolean gunner, Vec3 cameraWorld) {
            this.station = station;
            this.soldier = soldier;
            this.gunner = gunner;
            this.cameraWorld = cameraWorld;
            this.detection = new DetectionSystem(soldier.getUUID())
                .withFocusedRange(StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get());
        }
    }

    private StationGunnerAI() {}

    /**
     * Hands the station to the station-side AI. The soldier just owns the claim.
     * Returns false (and does not activate) when the station's world position
     * can't be resolved - a gun with no trustworthy camera must not run.
     */
    public static boolean activate(Entity station, SoldierEntity soldier, boolean gunner) {
        Object ship = VS2Compat.getShipUnder(station);
        Vec3 cameraWorld = VS2Compat.stationCameraWorldPos(ship, station);
        if (cameraWorld == null) {
            logUnusableCamera(station, "activation");
            return false;
        }
        active.put(station.getUUID(), new StationState(station, soldier, gunner, cameraWorld));
        soldier.setVehicleCrewActive(true);
        StevesArmyMod.LOGGER.info("[StationAI] soldier={} mans {} {} (gunner={}) shipyard={} cameraWorld={}",
            soldier.getId(), gunner ? "hull MG" : "periscope", station.getId(), gunner,
            station.position(), cameraWorld);
        return true;
    }

    /** True when the station-side AI already runs a gun for this soldier. */
    public static boolean soldierHasStation(SoldierEntity soldier) {
        for (StationState state : active.values()) {
            if (state.soldier == soldier) {
                return true;
            }
        }
        return false;
    }

    public static void deactivate(UUID stationId, String reason) {
        StationState state = active.remove(stationId);
        if (state == null) {
            return;
        }
        VehicleCrewManager.release(stationId, state.soldier.getUUID());
        state.soldier.setVehicleCrewActive(false);
        DetectionViewpoint.clear(state.soldier);
        StevesArmyMod.LOGGER.info("[StationAI] soldier={} left station={} ({})",
            state.soldier.getId(), state.station.getId(), reason);
    }

    /** Releases every station run for this soldier (goal stopped, role change). */
    public static void deactivateForSoldier(SoldierEntity soldier) {
        for (Map.Entry<UUID, StationState> entry : active.entrySet()) {
            if (entry.getValue().soldier == soldier) {
                deactivate(entry.getKey(), "soldier stopped");
            }
        }
    }

    /**
     * Seat-time duty assignment. Seated crew soldiers freeze (their goals never
     * tick), so the unmounted scan in {@link com.stevesarmy.entity.ai.VehicleCrewGoal}
     * never runs for them; transport instead calls this after seating. Same-ship
     * is the vehicle identity — the soldier never touches the gun (the station
     * AI runs it), so seat-to-station distance only orders preference, it does
     * not gate. Candidate positions and the seat live in shipyard space; the
     * seated soldier's own position() is a stale world coordinate.
     */
    public static void assignSeatedSoldier(SoldierEntity soldier, @Nullable Long transportShipId) {
        if (!(soldier.level() instanceof ServerLevel level)
            || soldier.isRemoved()
            || !soldier.isPassenger()
            || soldier.getRole() != SoldierRole.VEHICLE_CREW
            || !TallyhoCompat.isAvailable()
            || soldierHasStation(soldier)) {
            return;
        }
        Entity vehicle = soldier.getVehicle();
        if (vehicle == null) {
            return;
        }
        // The seat recorded its ship at mount time; fall back to a live lookup.
        Long soldierShipId = transportShipId != null
            ? transportShipId : VS2Compat.getShipIdOf(VS2Compat.getShipUnder(vehicle));
        if (soldierShipId == null) {
            logDutyScanFailure(soldier, "no resolvable ship for seat %s", vehicle.getId());
            return;
        }
        StationScan scan = scanStations(level, soldier, soldierShipId, vehicle);
        Entity choice = scan.choice;
        if (choice == null) {
            logDutyScanFailure(soldier,
                "no free station on ship %s (mg=%d scope=%d possessed=%d claimed=%d shipMismatch=%d nearest=%.1f)",
                soldierShipId, scan.mg, scan.scope, scan.possessed, scan.claimed,
                scan.shipMismatch, scan.nearestSameShip);
            return;
        }
        boolean gunner = TallyhoCompat.isHullMG(choice);
        if (!VehicleCrewManager.claim(choice.getUUID(), soldier.getUUID())) {
            return;
        }
        if (activate(choice, soldier, gunner)) {
            StevesArmyMod.LOGGER.info("[StationAI] seated soldier={} claimed {} {} shipId={}",
                soldier.getId(), gunner ? "hull MG" : "periscope", choice.getId(), soldierShipId);
        } else {
            VehicleCrewManager.release(choice.getUUID(), soldier.getUUID());
        }
    }

    /** Duty-scan failures are diagnostic gold; throttle them instead of silencing them. */
    private static void logDutyScanFailure(SoldierEntity soldier, String message, Object... args) {
        long now = soldier.level().getGameTime();
        if (now - lastDutyFailureLog < 100) {
            return;
        }
        lastDutyFailureLog = now;
        StevesArmyMod.LOGGER.info("[StationAI] seated soldier={} scan failed: {}",
            soldier.getId(), String.format(message, args));
    }

    private static long lastCameraRejectLog;

    /** A station whose camera is not at a world position cannot run; say so, throttled. */
    private static void logUnusableCamera(Entity station, String context) {
        long now = station.level().getGameTime();
        if (now - lastCameraRejectLog < 100) {
            return;
        }
        lastCameraRejectLog = now;
        StevesArmyMod.LOGGER.info("[StationAI] station {} {} refused: camera position {} is not world-space",
            station.getId(), context, station.position());
    }

    private static final class StationScan {
        @Nullable Entity choice;
        int mg;
        int scope;
        int possessed;
        int claimed;
        int shipMismatch;
        double nearestSameShip = Double.POSITIVE_INFINITY;
    }

    /**
     * Single pass over the entity list. MGs win over periscopes; nearest wins
     * within a type. Same-ship id is the only gate — the reach limit stays in
     * the unmounted VehicleCrewGoal scan, where a standing soldier really does
     * have to walk to the gun.
     */
    private static StationScan scanStations(ServerLevel level, SoldierEntity soldier,
                                            Long soldierShipId, Entity vehicle) {
        StationScan scan = new StationScan();
        Entity bestGunner = null;
        double bestGunnerDist = Double.POSITIVE_INFINITY;
        Entity bestObserver = null;
        double bestObserverDist = Double.POSITIVE_INFINITY;
        for (Entity entity : level.getAllEntities()) {
            boolean isMg = TallyhoCompat.isHullMG(entity);
            if (!isMg && !TallyhoCompat.isPeriscope(entity)) {
                continue;
            }
            if (entity.isRemoved() || !entity.isAlive()) {
                continue;
            }
            if (isMg) {
                scan.mg++;
            } else {
                scan.scope++;
            }
            if (TallyhoCompat.isPlayerPossessed(entity)) {
                scan.possessed++;
                continue;
            }
            UUID claimant = VehicleCrewManager.claimantOf(entity.getUUID());
            if (claimant != null && !claimant.equals(soldier.getUUID())) {
                scan.claimed++;
                continue;
            }
            Long stationShipId = VS2Compat.getShipIdOf(VS2Compat.getShipUnder(entity));
            if (!soldierShipId.equals(stationShipId)) {
                scan.shipMismatch++;
                continue;
            }
            double distSqr = entity.position().distanceToSqr(vehicle.position());
            if (distSqr < scan.nearestSameShip) {
                scan.nearestSameShip = distSqr;
            }
            if (isMg) {
                if (distSqr < bestGunnerDist) {
                    bestGunnerDist = distSqr;
                    bestGunner = entity;
                }
            } else if (distSqr < bestObserverDist) {
                bestObserverDist = distSqr;
                bestObserver = entity;
            }
        }
        scan.choice = bestGunner != null ? bestGunner : bestObserver;
        return scan;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active.isEmpty()) {
            return;
        }
        for (UUID stationId : List.copyOf(active.keySet())) {
            StationState state = active.get(stationId);
            if (state != null) {
                tickStation(stationId, state);
            }
        }
    }

    private static void tickStation(UUID stationId, StationState state) {
        Entity station = state.station;
        SoldierEntity soldier = state.soldier;
        if (!(station.level() instanceof ServerLevel level)
            || station.isRemoved() || !station.isAlive()
            || TallyhoCompat.isPlayerPossessed(station)) {
            deactivate(stationId, TallyhoCompat.isPlayerPossessed(station)
                ? "player took the station" : "station lost");
            return;
        }
        if (!soldier.isAlive() || soldier.isRemoved()
            || soldier.getRole() != SoldierRole.VEHICLE_CREW
            || VS2Compat.isAutoTransportBlocked(soldier)) {
            deactivate(stationId, VS2Compat.isAutoTransportBlocked(soldier)
                ? "manual dismount" : "gunner lost");
            return;
        }
        UUID claimant = VehicleCrewManager.claimantOf(stationId);
        if (claimant == null || !claimant.equals(soldier.getUUID())) {
            deactivate(stationId, "claim lost");
            return;
        }

        // A gunner that dismounted can keep its gun while it stays close in
        // world space; once it wanders off, hand the station back. Both
        // positions here are world space (VS2 drags standing entities in world
        // coordinates), so the comparison is safe.
        if (!soldier.isPassenger()
            && soldier.position().distanceToSqr(state.cameraWorld) > 64.0D * 64.0D) {
            deactivate(stationId, "gunner left");
            return;
        }

        // World-space camera position. No transform, no work: never aim, query,
        // or ray on raw shipyard coordinates.
        Object ship = VS2Compat.getShipUnder(station);
        Vec3 cameraWorld = VS2Compat.stationCameraWorldPos(ship, station);
        if (cameraWorld == null) {
            logUnusableCamera(station, "tick");
            DetectionViewpoint.clear(soldier);
            return;
        }
        state.cameraWorld = cameraWorld;
        Vec3 look = VS2Compat.shipToWorldDirection(ship, station.getLookAngle());
        DetectionViewpoint.set(soldier, cameraWorld, look);

        if (state.gunner) {
            tickGunner(state, level);
        } else {
            tickObserver(state, level);
        }
    }

    // --- Gunner ---------------------------------------------------------------

    private static void tickGunner(StationState state, ServerLevel level) {
        List<LivingEntity> candidates = computeCandidates(state, level);
        DetectionSystem.DetectionScanResult scan = state.detection.tick(
            state.soldier, candidates, squadIntel(state.soldier));
        LivingEntity best = pickTarget(scan);

        float aimError = Float.NaN;
        boolean fireGateReached = false;
        if (best == null) {
            state.target = null;
            state.aimQuality = Math.max(0.0F,
                state.aimQuality - StevesArmyConfig.getAimQualityLosDecayRate());
            decayBloom(state);
        } else {
            state.target = best;
            reportIntel(state.soldier, best);

            ExposureCalculator.AimPointResult aimPoint =
                ExposureCalculator.getBestAimPoint(state.soldier, best);
            boolean inLos = TargetAcquisition.hasLineOfSight(state.soldier, best);
            updateAimQuality(state, best, inLos);
            if (aimPoint != null && aimPoint.canShoot() && inLos
                && FriendlyFireChecker.isSafeToShoot(state.soldier, aimPoint.position, state.aimQuality)) {
                float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
                aimError = TallyhoCompat.aimTowards(state.station,
                    aimTargetForStation(state, aimPoint.position), traverse, 0.0F, 0.0F);
                fireGateReached = true;
                decayBloom(state);
                // Only open fire once the detection system has classified the
                // contact, mirroring infantry trigger discipline.
                if (state.detection.isTargetDetected(best)
                    && state.aimQuality >= StevesArmyConfig.VEHICLE_CREW_MIN_AIM_TO_FIRE.get().floatValue()
                    && aimError <= FIRE_TOLERANCE_DEGREES
                    && fireBurstGate(state)) {
                    float yawSigma = AimAccuracyManager.getYawSigma(state.aimQuality)
                        + (float) aimPoint.concealment * 2.00F;
                    float pitchSigma = AimAccuracyManager.getPitchSigma(state.aimQuality)
                        + (float) aimPoint.concealment * 0.75F;
                    float bloomScale = 1.0F + state.bloom;
                    float[] deviation = AimAccuracyManager.sampleGaussianDeviation(
                        state.aimQuality, yawSigma * bloomScale, pitchSigma * bloomScale, level);

                    // Point the gun at the deviated direction; tallyho clamps to turret limits.
                    TallyhoCompat.aimTowards(state.station, aimTargetForStation(state, aimPoint.position), traverse,
                        deviation[1], deviation[0]);
                    TallyhoCompat.fire(state.station, state.soldier);

                    state.bloom = Math.min(StevesArmyConfig.VEHICLE_CREW_BLOOM_MAX.get().floatValue(),
                        state.bloom + StevesArmyConfig.VEHICLE_CREW_BLOOM_PER_SHOT.get().floatValue());
                    state.burstShots++;
                }
            } else {
                decayBloom(state);
            }
        }

        state.statusTick++;
        if (state.statusTick >= 60) {
            state.statusTick = 0;
            logGunnerChain(state, candidates, scan, best, aimError, fireGateReached);
        }
    }

    /**
     * Stage-by-stage diagnosis for the most promising candidate. Every stage is
     * evaluated even after one fails — "assuming this passed, the next error is" —
     * so a single line names the blocker and shows what the later stages would do.
     */
    private static void logGunnerChain(StationState state, List<LivingEntity> candidates,
                                       DetectionSystem.DetectionScanResult scan, @Nullable LivingEntity picked,
                                       float aimError, boolean fireGateReached) {
        StringJoiner chain = new StringJoiner(" ");
        chain.add("cand=" + candidates.size());
        LivingEntity subject = picked;
        if (subject == null && !candidates.isEmpty()) {
            double bestDistSqr = Double.POSITIVE_INFINITY;
            for (LivingEntity candidate : candidates) {
                double distSqr = state.cameraWorld.distanceToSqr(candidate.position());
                if (distSqr < bestDistSqr) {
                    bestDistSqr = distSqr;
                    subject = candidate;
                }
            }
            chain.add("nearest=" + String.format("%.1f", Math.sqrt(bestDistSqr)) + "m");
        }
        if (subject == null) {
            StevesArmyMod.LOGGER.info("[StationAI] gunner chain soldier={} station={} {} (nothing in range)",
                state.soldier.getId(), state.station.getId(), chain);
            return;
        }
        if (picked == null) {
            chain.add("picked=none subject=" + subject.getId());
        }

        String blocker = null;
        boolean los = TargetAcquisition.hasLineOfSight(state.soldier, subject);
        chain.add("los=" + (los ? "ok" : "BLOCKED"));
        if (!los) {
            blocker = "los";
        }

        double points = -1.0;
        boolean detected = false;
        for (DetectionSystem.TargetObservation observation : scan.observations()) {
            if (observation.target() == subject) {
                points = observation.accumulatedPoints();
                detected = observation.detected();
                break;
            }
        }
        chain.add("detect=" + (points < 0 ? "?" : (int) points + "pts")
            + (detected ? "+classified" : ""));
        if (blocker == null && !detected) {
            blocker = "detect";
        }

        ExposureCalculator.AimPointResult aimPoint =
            ExposureCalculator.getBestAimPoint(state.soldier, subject);
        boolean aimOk = aimPoint != null && aimPoint.canShoot();
        chain.add("aim=" + (aimOk ? "ok" : "noShot"));
        if (blocker == null && !aimOk) {
            blocker = "aim";
        }

        boolean friendly = aimOk
            && FriendlyFireChecker.isSafeToShoot(state.soldier, aimPoint.position, state.aimQuality);
        chain.add("friendly=" + (!aimOk ? "-" : friendly ? "safe" : "VETO"));
        if (blocker == null && aimOk && !friendly) {
            blocker = "friendly";
        }

        String fireState;
        if (blocker != null) {
            fireState = "blocked";
        } else if (!detected) {
            fireState = "wait-detect";
        } else if (Float.isNaN(aimError)) {
            fireState = "not-aiming";
        } else if (aimError > FIRE_TOLERANCE_DEGREES) {
            fireState = "traversing";
        } else if (state.burstPauseTicks > 0) {
            fireState = "burst-pause";
        } else if (!TallyhoCompat.isReadyToFire(state.station)) {
            fireState = "cooldown";
        } else if (!TallyhoCompat.hasAmmo(state.station)) {
            fireState = "dry";
        } else {
            fireState = "SHOOTING";
        }
        chain.add("quality=" + String.format("%.2f", state.aimQuality))
            .add("aimErr=" + (Float.isNaN(aimError) ? "-" : String.format("%.1f", aimError) + "deg"))
            .add("fire=" + fireState)
            .add("ready=" + TallyhoCompat.isReadyToFire(state.station))
            .add("belt=" + TallyhoCompat.hasAmmo(state.station))
            .add("burstPause=" + state.burstPauseTicks);

        StevesArmyMod.LOGGER.info("[StationAI] gunner chain soldier={} station={} {}{}",
            state.soldier.getId(), state.station.getId(), chain,
            blocker == null ? "" : " blocker=" + blocker);
    }

    /** One observer status line per station per period, whatever the optics are doing. */
    private static void emitStatus(StationState state, String kind, StringBuilder status) {
        state.statusTick++;
        if (state.statusTick < 60) {
            return;
        }
        state.statusTick = 0;
        StevesArmyMod.LOGGER.info("[StationAI] {} status soldier={} station={} {}",
            kind, state.soldier.getId(), state.station.getId(), status.toString());
    }

    private static boolean fireBurstGate(StationState state) {
        if (state.burstPauseTicks > 0) {
            state.burstPauseTicks--;
            return false;
        }
        if (state.burstShots >= StevesArmyConfig.VEHICLE_CREW_BURST_SIZE.get()) {
            state.burstShots = 0;
            state.burstPauseTicks = StevesArmyConfig.VEHICLE_CREW_BURST_PAUSE.get();
            return false;
        }
        return TallyhoCompat.isReadyToFire(state.station) && TallyhoCompat.hasAmmo(state.station);
    }

    private static void decayBloom(StationState state) {
        state.bloom = Math.max(0.0F, state.bloom - StevesArmyConfig.VEHICLE_CREW_BLOOM_DECAY.get().floatValue());
    }

    private static void updateAimQuality(StationState state, LivingEntity currentTarget, boolean inLos) {
        if (inLos) {
            float targetQuality = AimAccuracyManager.getTargetAimQuality(state.soldier, currentTarget);
            float buildRate = AimAccuracyManager.getBuildRate(state.soldier, currentTarget);
            if (state.aimQuality < targetQuality) {
                state.aimQuality = Mth.lerp(buildRate, state.aimQuality, targetQuality);
            } else {
                state.aimQuality = Mth.lerp(buildRate * 0.5F, state.aimQuality, targetQuality);
            }
        } else {
            state.aimQuality -= StevesArmyConfig.getAimQualityLosDecayRate();
        }
        state.aimQuality = Mth.clamp(state.aimQuality, 0.0F, 1.0F);
    }

    // --- Observer -------------------------------------------------------------

    private static void tickObserver(StationState state, ServerLevel level) {
        List<LivingEntity> candidates = computeCandidates(state, level);
        StringBuilder status = new StringBuilder(80)
            .append("candidates=").append(candidates.size());
        DetectionSystem.DetectionScanResult scan = state.detection.tick(
            state.soldier, candidates, squadIntel(state.soldier));
        LivingEntity best = pickTarget(scan);
        float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
        if (best != null) {
            state.target = best;
            reportIntel(state.soldier, best);
            // Keep the optics on the contact so intel and detection keep refreshing.
            TallyhoCompat.aimTowards(state.station,
                aimTargetForStation(state, best.getEyePosition()), traverse, 0.0F, 0.0F);
            emitStatus(state, "observer", status.append(" tracking=").append(best.getId()));
            return;
        }
        state.target = null;
        sweepOptics(state, traverse);
        emitStatus(state, "observer", status.append(" sweeping"));
    }

    private static void sweepOptics(StationState state, float traverse) {
        state.sweepTick++;
        if (state.sweepTick >= SWEEP_PERIOD_TICKS) {
            state.sweepTick = 0;
        }
        float progress = state.sweepTick / (float) SWEEP_PERIOD_TICKS;
        float baseYaw = TallyhoCompat.getBaseYaw(state.station);
        float yawLimit = TallyhoCompat.getYawLimit(state.station);

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
        Vec3 sweepTarget = DetectionViewpoint.getEyePosition(state.soldier)
            .add(direction.scale(OBSERVER_SWEEP_RANGE));
        TallyhoCompat.aimTowards(state.station, aimTargetForStation(state, sweepTarget), traverse, 0.0F, 0.0F);
    }

    // --- Shared helpers -------------------------------------------------------

    /**
     * Tallyho computes the aim direction as {@code target - camera.getPosition()},
     * so the pseudo-target makes that difference equal the true world-space
     * direction. When the camera is already world-space (the normal case) the
     * pseudo-target is simply the world target itself.
     */
    private static Vec3 aimTargetForStation(StationState state, Vec3 worldTarget) {
        return state.station.position().add(worldTarget.subtract(state.cameraWorld));
    }

    /** World-space candidate scan around the camera's world position. */
    private static List<LivingEntity> computeCandidates(StationState state, ServerLevel level) {
        double maxRange = Math.max(state.detection.getFocusedRange(),
            DetectionSystem.PERIPHERAL_RANGE);
        Vec3 center = state.cameraWorld;
        return level.getEntitiesOfClass(LivingEntity.class,
            new AABB(center, center).inflate(maxRange),
            entity -> entity != state.soldier && entity != state.station
                && TargetAcquisition.isValidTarget(state.soldier, entity)
                && !state.soldier.isFriendlyTo(entity));
    }

    private static LivingEntity pickTarget(DetectionSystem.DetectionScanResult scan) {
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

    private static void reportIntel(SoldierEntity soldier, LivingEntity threat) {
        if (!threat.isAlive() || !TargetAcquisition.isValidTarget(soldier, threat)) {
            return;
        }
        SquadThreatIntel intel = squadIntel(soldier);
        if (intel != null) {
            ExposureCalculator.AimPointResult aimPoint =
                ExposureCalculator.getBestAimPoint(soldier, threat);
            intel.reportThreat(soldier.getUUID(), threat, threat.blockPosition(),
                aimPoint != null && aimPoint.canShoot() ? aimPoint.position : threat.getEyePosition(),
                threat.getEyePosition(), 1.0F);
        }
        EnemyContactTracker.reportContact(soldier, threat);
    }

    @Nullable
    private static SquadThreatIntel squadIntel(SoldierEntity soldier) {
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Optional<SquadData> squad = SquadManager.get(serverLevel).getSquadById(squadId);
        return squad.map(SquadData::getThreatIntel).orElse(null);
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.TallyhoCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.VehicleCrewDebugPacket;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import com.stevesarmy.squad.FireDiscipline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.stevesarmy.entity.TargetEntity;
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
    // The hull MG runs the infantry machine-gunner doctrine via FireControl; this
    // is its weapon class for both direct fire and suppression pacing.
    private static final FireControl.DirectFireWeaponProfile DIRECT_PROFILE =
        FireControl.DirectFireWeaponProfile.MACHINE_GUN;
    private static final FireControl.SuppressionWeaponProfile SUPPRESSION_PROFILE =
        FireControl.SuppressionWeaponProfile.MACHINE_GUN;
    // Bloom feeds the suppression beaten zone in place of the infantry gun's
    // TaCZ-derived aim inaccuracy: degrees of cone per bloom point.
    private static final double BLOOM_SUPPRESSION_SPREAD_DEGREES = 3.0;

    private static final Map<UUID, StationState> active = new ConcurrentHashMap<>();
    private static long lastDutyFailureLog;
    private static int debugSyncTicks;

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
        @Nullable UUID suppressionThreatId;
        int suppressionPlanTicks;
        int suppressionCooldownTicks;
        @Nullable Vec3 suppressionAimPos;
        int suppressionBurstShots;
        int suppressionBurstPauseTicks;
        float lastShotThreshold = Float.NaN;
        @Nullable DetectionSystem.DetectionScanResult lastScan;
        float lastAimError = Float.NaN;
        int debugState;
        /** Fire calls since the last 60-tick status line (direct + suppression). */
        int shotsSinceStatus;

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
        StevesArmyMod.LOGGER.info("[StationAI] soldier={} mans {} {} (gunner={}) shipyard={} cameraWorld={} {}",
            soldier.getId(), gunner ? "hull MG" : "periscope", station.getId(), gunner,
            station.position(), cameraWorld,
            soldier.isPassenger() ? "mounted"
                : String.format("REMOTE d=%.1fm (soldier standing, not seated)",
                    soldier.position().distanceTo(cameraWorld)));
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

    /** 0 = off duty, 1 = hull MG gunner, 2 = periscope observer. */
    public static int dutyTypeOf(SoldierEntity soldier) {
        for (StationState state : active.values()) {
            if (state.soldier == soldier) {
                return state.gunner ? 1 : 2;
            }
        }
        return 0;
    }

    public static void deactivate(UUID stationId, String reason) {
        StationState state = active.remove(stationId);
        if (state == null) {
            return;
        }
        VehicleCrewManager.release(stationId, state.soldier.getUUID());
        state.soldier.setVehicleCrewActive(false);
        DetectionViewpoint.clear(state.soldier);
        releaseSuppression(state, squadIntel(state.soldier));
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
     * One line per active station: who controls it, from where, in what mode, and
     * how many fire calls happened since its last status line. Answers "is any crew
     * actually controlling this gun, and who?" in one click.
     */
    public static void logActiveStations(ServerLevel level) {
        if (active.isEmpty()) {
            StevesArmyMod.LOGGER.info("[StationAI] active stations: none");
            return;
        }
        for (StationState state : active.values()) {
            if (state.station.level() != level) {
                continue;
            }
            String mode = state.suppressionThreatId != null ? "suppress(threat=" + state.suppressionThreatId + ")"
                : state.target != null ? "direct(target=" + state.target.getType() + "#" + state.target.getId() + ")"
                : "idle";
            StevesArmyMod.LOGGER.info("[StationAI] active station={} {} soldier={} {} d={}m mode={} shotsSinceStatus={}",
                state.station.getId(), state.station.getClass().getSimpleName(),
                state.soldier.getId(),
                state.soldier.isPassenger() ? "mounted" : "REMOTE-stand",
                String.format("%.1f", state.soldier.position().distanceTo(state.cameraWorld)),
                mode, state.shotsSinceStatus);
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
            if (state == null) {
                continue;
            }
            try {
                tickStation(stationId, state);
            } catch (Throwable unexpected) {
                // This ticker runs on the server thread every tick: a bug here must
                // cost the station its job, never the whole game.
                StevesArmyMod.LOGGER.error("[StationAI] station {} tick failed, releasing gunner {}: {}",
                    stationId, state.soldier.getId(), unexpected.toString(), unexpected);
                try {
                    deactivate(stationId, "tick error");
                } catch (Throwable ignored) {
                    active.remove(stationId);
                }
            }
        }
        if (!VehicleCrewDebugManager.subscribedPlayers().isEmpty() && ++debugSyncTicks >= 5) {
            debugSyncTicks = 0;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                int mode = VehicleCrewDebugManager.getMode(player.getUUID());
                if (mode != VehicleCrewDebugManager.OFF) {
                    sendDebugSnapshot(player, mode);
                }
            }
        }
    }

    public static void sendDebugSnapshot(ServerPlayer player, int mode) {
        if (mode == VehicleCrewDebugManager.OFF) {
            NetworkHandler.sendTo(player, new VehicleCrewDebugPacket(mode, List.of()));
            return;
        }
        List<VehicleCrewDebugPacket.Entry> entries = new java.util.ArrayList<>();
        for (StationState state : active.values()) {
            if (!state.soldier.isOwnedBy(player) || state.cameraWorld == null) {
                continue;
            }
            entries.add(toDebugEntry(state, mode));
        }
        NetworkHandler.sendTo(player, new VehicleCrewDebugPacket(mode, entries));
    }

    private static VehicleCrewDebugPacket.Entry toDebugEntry(StationState state, int mode) {
        Vec3 look = state.station.getLookAngle().normalize();
        UUID targetId = state.target != null ? state.target.getUUID() : null;
        Vec3 targetPos = state.target != null ? state.target.getEyePosition() : null;
        List<VehicleCrewDebugPacket.Observation> observations = new java.util.ArrayList<>();
        if (mode == VehicleCrewDebugManager.VERBOSE && state.lastScan != null) {
            for (DetectionSystem.TargetObservation observation : state.lastScan.observations()) {
                if (observations.size() >= 10) break;
                observations.add(new VehicleCrewDebugPacket.Observation(observation.target().getUUID(),
                    observation.target().getEyePosition(), observation.band().ordinal(), observation.visible(),
                    observation.detected(), (float) observation.accumulatedPoints()));
            }
        }
        int debugState = state.debugState;
        if (state.suppressionThreatId != null) debugState = 4;
        return new VehicleCrewDebugPacket.Entry(state.station.getUUID(), state.soldier.getUUID(), state.gunner,
            state.cameraWorld, look, targetId, targetPos, debugState, state.aimQuality, state.lastAimError,
            state.bloom, state.burstShots, state.burstPauseTicks, TallyhoCompat.isReadyToFire(state.station),
            TallyhoCompat.hasAmmo(state.station), state.suppressionThreatId, state.suppressionAimPos,
            (float) state.detection.getFocusedRange(), TallyhoCompat.getYawLimit(state.station),
            state.sweepTick / (float) SWEEP_PERIOD_TICKS, List.copyOf(observations));
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

        // A gunner that dismounted can keep its gun while it stays within the
        // station-reach of the camera — the same bound the duty scan uses to
        // claim it. Once it wanders off, hand the station back. Both positions
        // here are world space (VS2 drags standing entities in world
        // coordinates), so the comparison is safe.
        double leash = StevesArmyConfig.VEHICLE_CREW_STATION_REACH.get();
        if (!soldier.isPassenger()
            && soldier.position().distanceToSqr(state.cameraWorld) > leash * leash) {
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
        // VS2 already exposes a mounted camera's look angle in world space.
        // Applying the ship rotation here would rotate the detection view twice.
        Vec3 look = station.getLookAngle();
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
        state.lastScan = scan;
        state.debugState = 0;
        reportDetectedIntel(state.soldier, scan);
        LivingEntity best = pickTarget(scan);

        float aimError = Float.NaN;
        boolean fireGateReached = false;
        if (best == null) {
            state.target = null;
            state.debugState = 1;
            state.aimQuality = Math.max(0.0F,
                state.aimQuality - StevesArmyConfig.getAimQualityLosDecayRate());
            decayBloom(state);
            tickSuppression(state, level);
        } else {
            state.debugState = 2;
            if (state.suppressionThreatId != null) {
                // A precise target overrides suppression; hand the claim back.
                releaseSuppression(state, squadIntel(state.soldier));
                state.suppressionCooldownTicks = FireControl.SUPPRESSION_COOLDOWN_TICKS;
            }
            state.target = best;

            ExposureCalculator.AimPointResult aimPoint =
                ExposureCalculator.getTopmostVisibleAimPoint(state.soldier, best);
            boolean inLos = TargetAcquisition.hasLineOfSight(state.soldier, best);
            updateAimQuality(state, best, inLos);
            if (aimPoint != null && aimPoint.canShoot() && inLos
                && FriendlyFireChecker.isSafeToShoot(state.soldier, aimPoint.position, state.aimQuality)) {
                float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
                aimError = TallyhoCompat.aimTowards(state.station,
                    aimTargetForStation(state, aimPoint.position), traverse, 0.0F, 0.0F);
                // tallyho clamps out-of-arc targets to the arc edge, so a small aim
                // error does not mean the target is reachable — hold fire off-arc.
                boolean inArc = !TallyhoCompat.isTargetOutsideLimits(state.station,
                    aimTargetForStation(state, aimPoint.position));
                // The soldier's LOS is not the gun's lane: remote crews can spot a
                // target the barrel has no sight line to. Never fire from a blocked lane.
                boolean laneClear = hasFiringLane(state, level, aimPoint.position);
                fireGateReached = true;

                // Infantry-style dynamic shot threshold, scaled by the crew's fire
                // discipline, instead of a static aim gate.
                FireDiscipline discipline = state.soldier.getFireDiscipline();
                float targetAimQ = AimAccuracyManager.getTargetAimQuality(state.soldier, best);
                float thresholdScale = StevesArmyConfig.getAimQualityThresholdScale();
                if (discipline == FireDiscipline.CONSERVE) {
                    thresholdScale = Math.max(thresholdScale, 0.55F);
                } else if (discipline == FireDiscipline.SUPPRESSIVE) {
                    thresholdScale = Math.min(thresholdScale, 0.20F);
                }
                float shotThreshold = Math.max(0.15F, targetAimQ * thresholdScale);
                state.lastShotThreshold = shotThreshold;

                boolean fired = false;
                if (state.burstPauseTicks > 0) {
                    // Burst recovery from the DIRECT_PROFILE, like the infantry
                    // direct-fire burst machinery.
                    state.burstPauseTicks--;
                } else if (state.burstShots > 0 && state.aimQuality
                    < FireControl.directBurstContinuationThreshold(discipline, shotThreshold)) {
                    // Recoil-quality floor cut the burst short, as in infantry.
                    state.burstShots = 0;
                    state.burstPauseTicks = DIRECT_PROFILE.recoveryTicks;
                } else if (state.aimQuality >= shotThreshold
                    // Only open fire once the detection system has classified the
                    // contact, mirroring infantry trigger discipline.
                    && state.detection.isTargetDetected(best)
                    && aimError <= FIRE_TOLERANCE_DEGREES
                    && inArc
                    && laneClear
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
                    logFireCall(state, "direct", best.getType() + "#" + best.getId());

                    state.bloom = Math.min(StevesArmyConfig.VEHICLE_CREW_BLOOM_MAX.get().floatValue(),
                        state.bloom + StevesArmyConfig.VEHICLE_CREW_BLOOM_PER_SHOT.get().floatValue());
                    state.burstShots++;
                    if (state.burstShots >= DIRECT_PROFILE.burstShots) {
                        state.burstShots = 0;
                        state.burstPauseTicks = DIRECT_PROFILE.recoveryTicks;
                    }
                    fired = true;
                }
                if (!fired) {
                    decayBloom(state);
                }
            } else {
                decayBloom(state);
            }
        }

        state.lastAimError = aimError;
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
            StevesArmyMod.LOGGER.info("[StationAI] gunner chain soldier={} station={} {} suppress={} (nothing in range)",
                state.soldier.getId(), state.station.getId(), chain,
                state.suppressionThreatId != null ? "active" : "no");
            return;
        }
        if (picked == null) {
            chain.add("picked=none subject=" + subject.getId());
        }
        chain.add("target=" + EntityType.getKey(subject.getType())
            + "@[" + String.format("%.0f,%.0f,%.0f",
                subject.getX(), subject.getY(), subject.getZ())
            + " d=" + String.format("%.0f", state.cameraWorld.distanceTo(subject.position())) + "m");
        chain.add("suppress=" + (state.suppressionThreatId != null ? "active" : "no"));

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
            ExposureCalculator.getTopmostVisibleAimPoint(state.soldier, subject);
        boolean aimOk = aimPoint != null && aimPoint.canShoot();
        chain.add("aim=" + (aimOk ? aimPoint.type.displayName : "noShot"));
        if (blocker == null && !aimOk) {
            blocker = "aim";
        }

        boolean friendly = aimOk
            && FriendlyFireChecker.isSafeToShoot(state.soldier, aimPoint.position, state.aimQuality);
        chain.add("friendly=" + (!aimOk ? "-" : friendly ? "safe" : "VETO"));
        if (blocker == null && aimOk && !friendly) {
            blocker = "friendly";
        }

        boolean inArc = !aimOk || !TallyhoCompat.isTargetOutsideLimits(state.station,
            aimTargetForStation(state, aimPoint.position));
        chain.add("arc=" + (!aimOk ? "-" : inArc ? "ok" : "OUT"));
        if (blocker == null && aimOk && !inArc) {
            blocker = "arc";
        }

        boolean lane = !aimOk || hasFiringLane(state, state.soldier.level(), aimPoint.position);
        chain.add("lane=" + (!aimOk ? "-" : lane ? "ok" : "BLOCKED"));
        if (blocker == null && aimOk && !lane) {
            blocker = "lane";
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
        } else if (!Float.isNaN(state.lastShotThreshold)
            && state.aimQuality < state.lastShotThreshold) {
            fireState = "building";
        } else if (state.burstPauseTicks > 0) {
            fireState = "recovery";
        } else if (!TallyhoCompat.isReadyToFire(state.station)) {
            fireState = "cooldown";
        } else if (!TallyhoCompat.hasAmmo(state.station)) {
            fireState = "dry";
        } else {
            fireState = "SHOOTING";
        }
        if (state.suppressionThreatId != null) {
            // Suppression mode drives the gun; the precise-fire gates are idle.
            fireState = "suppressing";
            blocker = null;
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
        status.append(" shots=").append(state.shotsSinceStatus)
            .append(state.soldier.isPassenger() ? " mounted" : " REMOTE");
        state.shotsSinceStatus = 0;
        StevesArmyMod.LOGGER.info("[StationAI] {} status soldier={} station={} {}",
            kind, state.soldier.getId(), state.station.getId(), status.toString());
    }

    /**
     * Sustained suppressive fire at the last-known position of a squad threat the
     * gun cannot currently see. Runs the infantry suppression doctrine via
     * FireControl: MACHINE_GUN burst pacing, a distance-and-bloom beaten zone
     * re-rolled per burst, and a lane that widens as the contact ages. The fired
     * CBC rounds already apply suppression along their trajectories, so no
     * separate suppression call is needed on the victim side.
     */
    private static void tickSuppression(StationState state, ServerLevel level) {
        if (state.suppressionCooldownTicks > 0) {
            state.suppressionCooldownTicks--;
            return;
        }
        SquadThreatIntel intel = squadIntel(state.soldier);
        if (intel == null || !StevesArmyConfig.VEHICLE_CREW_SUPPRESSION_ENABLED.get()) {
            return;
        }
        long now = level.getGameTime();
        UUID soldierId = state.soldier.getUUID();

        SquadThreatIntel.ThreatKnowledge threat = null;
        if (state.suppressionThreatId != null) {
            SquadThreatIntel.ThreatKnowledge assigned =
                intel.getThreat(state.suppressionThreatId).orElse(null);
            if (assigned == null || !assigned.isAlive || assigned.lastKnownPosition == null
                || intel.isThreatStale(state.suppressionThreatId, now)
                || !isThreatEntityAlive(level, assigned)) {
                releaseSuppression(state, intel);
                state.suppressionCooldownTicks = FireControl.SUPPRESSION_COOLDOWN_TICKS;
                return;
            }
            threat = assigned;
        } else {
            double range = StevesArmyConfig.VEHICLE_CREW_SUPPRESSION_RANGE.get();
            SquadThreatIntel.ThreatKnowledge bestThreat = null;
            double bestDistSqr = range * range;
            for (SquadThreatIntel.ThreatKnowledge knowledge : intel.getUnsuppressedThreats()) {
                if (knowledge.lastKnownPosition == null || !isThreatEntityAlive(level, knowledge)) {
                    continue;
                }
                double distSqr = state.cameraWorld.distanceToSqr(
                    Vec3.atCenterOf(knowledge.lastKnownPosition));
                if (distSqr < bestDistSqr) {
                    Vec3 claimBase = knowledge.lastVisibleAimPoint != null
                        ? knowledge.lastVisibleAimPoint
                        : Vec3.atCenterOf(knowledge.lastKnownPosition).add(0.0, 1.0, 0.0);
                    if (!hasFiringLane(state, level, claimBase)) {
                        // No lane to the last-known spot: the gun would only
                        // hose terrain. Leave the threat for someone who can.
                        continue;
                    }
                    bestDistSqr = distSqr;
                    bestThreat = knowledge;
                }
            }
            if (bestThreat == null
                || !intel.tryClaimThreatSuppression(bestThreat.threatEntityId, soldierId, now, 1)) {
                state.suppressionCooldownTicks = FireControl.SUPPRESSION_COOLDOWN_TICKS;
                return;
            }
            threat = bestThreat;
            state.suppressionThreatId = bestThreat.threatEntityId;
            StevesArmyMod.LOGGER.info("[StationAI] soldier={} suppressing threat={} at {}",
                state.soldier.getId(), bestThreat.threatEntityId, bestThreat.lastKnownPosition);
        }

        intel.updateSuppressionHeartbeat(state.suppressionThreatId, soldierId, now);
        state.suppressionPlanTicks++;
        if (state.suppressionPlanTicks > FireControl.SUPPRESSION_PLAN_MAX_TICKS
            || TallyhoCompat.isPlayerPossessed(state.station)
            || !TallyhoCompat.hasAmmo(state.station)) {
            releaseSuppression(state, intel);
            state.suppressionCooldownTicks = FireControl.SUPPRESSION_COOLDOWN_TICKS;
            return;
        }

        // Re-roll the beaten zone per burst so sustained fire walks the position.
        // The null check matters: a precise target dying mid-burst hands over a
        // non-zero burstShots with no suppression aim point yet. Bloom stands in
        // for the infantry gun's TaCZ-derived aim inaccuracy. A live entity beats
        // the intel snapshot — suppressing where the threat IS, not where it was
        // when last seen (which is often a corpse's spot or empty ground).
        if (state.suppressionAimPos == null || state.suppressionBurstShots == 0) {
            Entity liveThreat = level.getEntity(threat.threatEntityId);
            Vec3 base = liveThreat != null && liveThreat.isAlive()
                ? liveThreat.getEyePosition()
                : threat.lastVisibleAimPoint != null
                    ? threat.lastVisibleAimPoint
                    : Vec3.atCenterOf(threat.lastKnownPosition).add(0.0, 1.0, 0.0);
            double distance = state.cameraWorld.distanceTo(base);
            double gunSpreadMeters = distance
                * Math.tan(Math.toRadians(state.bloom * BLOOM_SUPPRESSION_SPREAD_DEGREES));
            long contactAge = now - threat.lastSeenTime;
            state.suppressionAimPos = FireControl.calculateLastSeenSuppressionSpread(
                state.cameraWorld, state.cameraWorld, level.random, base, gunSpreadMeters, contactAge);
        }

        if (state.suppressionBurstPauseTicks > 0) {
            // Pause between suppression bursts (SUPPRESSION_PROFILE).
            state.suppressionBurstPauseTicks--;
            return;
        }
        float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
        Vec3 suppressionTarget = aimTargetForStation(state, state.suppressionAimPos);
        float error = TallyhoCompat.aimTowards(state.station, suppressionTarget, traverse, 0.0F, 0.0F);
        if (error <= FIRE_TOLERANCE_DEGREES && fireBurstGate(state)
            // Out-of-arc suppression positions clamp to the arc edge; never fire there.
            && !TallyhoCompat.isTargetOutsideLimits(state.station, suppressionTarget)
            // The beaten zone re-rolls each burst, so re-check the lane to the
            // live aim point, not just the position claimed at selection time.
            && hasFiringLane(state, level, state.suppressionAimPos)) {
            TallyhoCompat.fire(state.station, state.soldier);
            logFireCall(state, "suppress", threat.threatEntityId);
            state.bloom = Math.min(StevesArmyConfig.VEHICLE_CREW_BLOOM_MAX.get().floatValue(),
                state.bloom + StevesArmyConfig.VEHICLE_CREW_BLOOM_PER_SHOT.get().floatValue());
            state.suppressionBurstShots++;
            if (state.suppressionBurstShots >= SUPPRESSION_PROFILE.burstShots) {
                state.suppressionBurstShots = 0;
                state.suppressionBurstPauseTicks = SUPPRESSION_PROFILE.pauseTicks;
            }
        }
    }

    private static void releaseSuppression(StationState state, @Nullable SquadThreatIntel intel) {
        if (state.suppressionThreatId != null && intel != null) {
            intel.releaseThreatSuppression(state.suppressionThreatId, state.soldier.getUUID());
        }
        state.suppressionThreatId = null;
        state.suppressionPlanTicks = 0;
        state.suppressionAimPos = null;
        state.suppressionBurstShots = 0;
        state.suppressionBurstPauseTicks = 0;
    }

    /**
     * The weapon's own fire cadence: tallyho's internal cooldown plus belt check.
     * Burst sizing and recovery are the DIRECT_PROFILE's job (handled in
     * {@link #tickGunner}), matching the infantry burst machinery.
     */
    /**
     * True when the threat's actual entity is still present and alive. Intel can lag
     * reality (a threat killed out of sight stays {@code isAlive} until attribution or
     * staleness), and suppressing a corpse's last-known spot is pure ghost fire.
     */
    private static boolean isThreatEntityAlive(ServerLevel level, SquadThreatIntel.ThreatKnowledge threat) {
        Entity entity = level.getEntity(threat.threatEntityId);
        return entity != null && entity.isAlive();
    }

    private static boolean fireBurstGate(StationState state) {
        return TallyhoCompat.isReadyToFire(state.station) && TallyhoCompat.hasAmmo(state.station);
    }

    /**
     * Records and (when the station-fire diagnostic is on) logs every fire call our AI
     * makes. Tracers appearing WITHOUT these lines are not ours — tallyho's own
     * coax fires in its tick with no controller gate (see CoaxMachineGunEntity).
     */
    private static void logFireCall(StationState state, String mode, @Nullable Object subject) {
        state.shotsSinceStatus++;
        if (DiagnosticLogManager.isStationFireLoggingEnabled()) {
            Vec3 look = state.station.getLookAngle();
            StevesArmyMod.LOGGER.info("[StationAI] fire st={} soldier={} mode={} subject={} yaw={} pitch={}",
                state.station.getId(), state.soldier.getId(), mode,
                subject == null ? "none" : subject,
                String.format("%.1f", look.y), String.format("%.1f", look.x));
        }
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
        state.lastScan = scan;
        state.debugState = 0;
        reportDetectedIntel(state.soldier, scan);
        LivingEntity best = pickTarget(scan);
        float traverse = StevesArmyConfig.VEHICLE_CREW_TRAVERSE_SPEED.get().floatValue();
        if (best != null) {
            state.debugState = 2;
            state.target = best;
            // Keep the optics on the contact so intel and detection keep refreshing.
            TallyhoCompat.aimTowards(state.station,
                aimTargetForStation(state, best.getEyePosition()), traverse, 0.0F, 0.0F);
            emitStatus(state, "observer", status.append(" tracking=").append(best.getId()));
            return;
        }
        state.target = null;
        state.debugState = 3;
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

    /**
     * True when the gun's camera has a clear firing lane to a WORLD-space aim
     * position — terrain, buildings, ships, and smoke all block. The soldier is
     * passed as the observer so crew rays get ship-aware blocking plus the
     * self-hull escape (the camera sits inside its own hull). The gun can see a
     * position without seeing the enemy hiding at it: suppressing a spot the
     * barrel has no lane to is exactly the "shooting into nowhere" bug.
     */
    private static boolean hasFiringLane(StationState state, Level level, Vec3 worldAimPos) {
        if (state.cameraWorld == null) {
            return false;
        }
        return VisibilityRay.traceContactOnly(level, state.cameraWorld, worldAimPos, state.soldier)
            .hasContact();
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
                && !state.soldier.isFriendlyTo(entity)
                && isCombatTarget(entity));
    }

    /**
     * Station gunners only engage combat-relevant entities. Without this, any
     * ambient mob flying through the detection box gets classified and pulls the
     * gun's fire into the sky.
     */
    private static boolean isCombatTarget(Entity entity) {
        return entity instanceof SoldierEntity
            || entity instanceof Enemy
            || entity instanceof Player player && !player.isCreative()
            || entity instanceof TargetEntity;
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

    /**
     * Reports every contact the scan classified, matching the infantry combat
     * goal: detected-and-visible contacts feed squad intel, and any visible
     * contact reaches the owner's enemy-contact tracker. The gun still locks a
     * single target; this only spreads what the crew knows to the squad.
     */
    private static void reportDetectedIntel(SoldierEntity soldier,
                                             DetectionSystem.DetectionScanResult scan) {
        SquadThreatIntel intel = squadIntel(soldier);
        for (DetectionSystem.TargetObservation observation : scan.observations()) {
            LivingEntity threat = observation.target();
            if (!observation.visible() || !threat.isAlive()
                || !TargetAcquisition.isValidTarget(soldier, threat)) {
                continue;
            }
            if (observation.detected() && intel != null) {
                ExposureCalculator.AimPointResult aimPoint =
                    ExposureCalculator.getBestAimPoint(soldier, threat);
                intel.reportThreat(soldier.getUUID(), threat, threat.blockPosition(),
                    aimPoint != null && aimPoint.canShoot() ? aimPoint.position : threat.getEyePosition(),
                    threat.getEyePosition(), 1.0F);
            }
            EnemyContactTracker.reportContact(soldier, threat);
        }
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

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.TallyhoCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.combat.StationGunnerAI;
import com.stevesarmy.combat.VehicleCrewManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Crew duty assignment: an unmounted crew soldier claims the nearest unclaimed
 * tallyho station (hull MG or periscope) within reach and hands it to
 * {@link StationGunnerAI}, which runs the gun from the station's camera in world
 * space on its own server-tick driver. The soldier itself does no gun work at
 * all — while transported its AI freezes like any other soldier, which is what
 * keeps it from issuing queries in VS2's shipyard coordinate space.
 */
public class VehicleCrewGoal extends Goal {
    private final SoldierEntity soldier;
    private int dutyScanCooldown;

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

    @Override
    public void tick() {
        if (soldier.isPassenger()) {
            // Seated crew are frozen by prepareSoldierAi and never reach this;
            // the station AI keeps running the claimed gun either way.
            return;
        }
        if (VS2Compat.isAutoTransportBlocked(soldier)) {
            // The player just ordered this crew off the vehicle; StationGunnerAI
            // releases the claim on its own. Don't re-claim until it expires.
            return;
        }
        if (dutyScanCooldown > 0) {
            dutyScanCooldown--;
            return;
        }
        dutyScanCooldown = StevesArmyConfig.VEHICLE_CREW_DUTY_SCAN_INTERVAL.get();
        tryAcquireDuty();
    }

    @Override
    public void stop() {
        StationGunnerAI.deactivateForSoldier(soldier);
    }

    private void tryAcquireDuty() {
        if (!(soldier.level() instanceof ServerLevel level) || !TallyhoCompat.isAvailable()) {
            return;
        }
        if (StationGunnerAI.soldierHasStation(soldier)) {
            // Already running a gun; re-activating would reset its aim state.
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
        if (!StationGunnerAI.activate(choice, soldier, TallyhoCompat.isHullMG(choice))) {
            // No trustworthy camera position; don't hold the claim either.
            VehicleCrewManager.release(choice.getUUID(), soldier.getUUID());
        }
    }

    private Entity findStation(ServerLevel level, boolean hullMg, double reach) {
        List<Entity> candidates = new ArrayList<>();
        double reachSqr = reach * reach;
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
            // Station entities live in shipyard space; the unmounted soldier's
            // position is a real world position, so compare against the
            // station's world-space position.
            double distanceSqr = stationWorldPosOf(entity).distanceToSqr(soldier.position());
            if (distanceSqr > reachSqr) {
                continue;
            }
            candidates.add(entity);
        }
        return candidates.stream()
            .min(Comparator.comparingDouble(e -> stationWorldPosOf(e).distanceToSqr(soldier.position())))
            .orElse(null);
    }

    /** World-space position of a station entity (its raw position is shipyard space). */
    private static Vec3 stationWorldPosOf(Entity station) {
        Object ship = VS2Compat.getShipUnder(station);
        Vec3 world = VS2Compat.stationCameraWorldPos(ship, station);
        return world != null ? world : station.position();
    }
}

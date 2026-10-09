package com.stevesarmy.sim;

import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.entity.ai.CoverSearchScheduler;
import com.stevesarmy.squad.FireTeamSuppressionTracker;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;

/**
 * Static-state reset inventory run between scenarios so one run's residue
 * (reservations, suppression memory, pending searches, squads) cannot
 * poison the next. Each hook is sim-only and a no-op outside the harness.
 */
public final class SimIsolation {
    private SimIsolation() {}

    public static void reset(ServerLevel level) {
        CoverReservationManager.clear();
        CoverSearchScheduler.resetForSim();
        FireTeamSuppressionTracker.resetForSim();
        SquadManager.get(level).resetForSim();
        PerformanceMetrics.reset();
        // virtual magazines from the previous scenario must not leak into the next
        SimGunHandler.resetForSim();
        // per-tick danger-ray budget telemetry is per-scenario
        com.stevesarmy.squad.SquadDangerMap.resetForSim();
        // last-corridor overlay snapshots must not leak between soldiers/scenarios
        com.stevesarmy.entity.ai.pathfinding.DangerCorridor.resetForSim();
        // stale raytrace caches from the previous fixture's geometry must not
        // leak into the next scenario's LoS results
        com.stevesarmy.combat.VisibilityRay.invalidateCache(level);
        // per-soldier exposed-combat action state must not leak between scenarios
        com.stevesarmy.entity.ai.ExposedCombatController.resetAll();
        com.stevesarmy.squad.SquadMovementCoordinator.resetAll();
        // a leaked close-contact plan would gate the next scenario's advances
        // on the previous scenario's threat
        com.stevesarmy.squad.ContactPlan.resetAll();
        // orders of record are per-scenario: a leaked CLEARING state keyed by
        // the previous scenario's anchor would report phantom stages (STALLED
        // / SECURED) into the next scenario's probe and executor gates
        com.stevesarmy.squad.ClearingCoordinator.clearAll();
    }
}

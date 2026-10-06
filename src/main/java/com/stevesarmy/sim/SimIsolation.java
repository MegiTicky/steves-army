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
        // stale raytrace caches from the previous fixture's geometry must not
        // leak into the next scenario's LoS results
        com.stevesarmy.combat.VisibilityRay.invalidateCache(level);
    }
}

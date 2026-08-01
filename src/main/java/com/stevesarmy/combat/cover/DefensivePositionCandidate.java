package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;

/** Internal comparison data; prone deliberately has no CoverPoint reservation. */
public sealed interface DefensivePositionCandidate permits DefensivePositionCandidate.PhysicalCoverCandidate,
    DefensivePositionCandidate.ProneFiringCandidate {
    BlockPos destination();
    float firingAccess();
    float protection();
    float routeExposure();
    float movementCost();
    String diagnostics();

    record PhysicalCoverCandidate(BlockPos destination, float firingAccess, float protection,
                                  float routeExposure, float movementCost, String diagnostics)
        implements DefensivePositionCandidate { }

    record ProneFiringCandidate(BlockPos destination, float firingAccess, float protection,
                                float routeExposure, float movementCost, String diagnostics)
        implements DefensivePositionCandidate { }
}

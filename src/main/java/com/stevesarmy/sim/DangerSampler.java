package com.stevesarmy.sim;

/**
 * Pluggable danger-read seam for the harness: returns the danger score
 * (0..100) at a world X/Z, or a negative value where no map data exists.
 * Scenarios register stub samplers for the rig self-tests; the live run in
 * Phase 1 wires this to the soldier's real per-squad danger map.
 */
@FunctionalInterface
public interface DangerSampler {

    /** Danger score at world (x, z); negative = no data. */
    double dangerScoreAt(double x, double z);
}

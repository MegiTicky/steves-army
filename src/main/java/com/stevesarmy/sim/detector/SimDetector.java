package com.stevesarmy.sim.detector;

import com.stevesarmy.sim.SimRunResult;
import com.stevesarmy.sim.SimViolation;

import java.util.List;

/**
 * A bug-signature check evaluated over one scenario run's telemetry timeline.
 * Detectors are pure: no world access, only the sampled {@code CombatStateSnapshot}
 * series — the same fields the in-game coverstate card shows.
 */
public interface SimDetector {
    String name();

    List<SimViolation> evaluate(SimRunResult run);
}

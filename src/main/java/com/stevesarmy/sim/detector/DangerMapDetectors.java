package com.stevesarmy.sim.detector;

import com.stevesarmy.sim.DangerOracle;
import com.stevesarmy.sim.SimRunResult;
import com.stevesarmy.sim.SimSample;
import com.stevesarmy.sim.SimTuning;
import com.stevesarmy.sim.SimViolation;

import java.util.ArrayList;
import java.util.List;

/**
 * Danger-map verification detectors. The oracle comparison is fed by a
 * sweep SimManager runs at scenario end (sampler vs {@link DangerOracle});
 * the probe cross-check is pure over the sampled timeline. Both stay silent
 * when a scenario never registered a danger sampler or injected threats.
 */
public final class DangerMapDetectors {
    private DangerMapDetectors() {}

    public static List<SimDetector> all() {
        return List.of(new OracleMismatch(), new ProbeDisagreement());
    }

    // D1. danger sampler vs independent ground truth -------------------------

    static final class OracleMismatch implements SimDetector {
        @Override public String name() { return "DangerOracleMismatch"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            DangerOracle.SweepReport sweep = run.oracleSweep;
            if (sweep == null) return List.of();
            List<SimViolation> out = new ArrayList<>();
            if (sweep.compared() < 20) {
                SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), run.durationTicks, "-",
                    "oracle sweep compared only " + sweep.compared() + " cells — verification region too small to trust");
                v.with("compared", sweep.compared());
                out.add(v);
                return out;
            }
            if (sweep.mismatchFraction() > SimTuning.DANGER_ORACLE_MISMATCH_FRACTION) {
                SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), run.durationTicks, "-",
                    Math.round(sweep.mismatchFraction() * 100) + "% of cells disagree with the LoS oracle ("
                        + sweep.mismatches() + "/" + sweep.compared()
                        + ") — danger data diverges from ground truth");
                v.with("worstDelta", String.format("%.1f", sweep.worstDelta()))
                    .with("worstCell", String.format("%.0f,%.0f", sweep.worstX(), sweep.worstZ()))
                    .with("worstExpected", String.format("%.1f", sweep.worstExpected()))
                    .with("worstActual", String.format("%.1f", sweep.worstActual()))
                    .with("compared", sweep.compared())
                    .with("mismatches", sweep.mismatches());
                out.add(v);
            }
            return out;
        }
    }

    // D2. sampled map vs physical body rays at the soldier -------------------
    //
    // The map scores multi-threat exposure; the fidelity probe measures rays
    // from the single nearest enemy — so disagreement has legitimate causes
    // (other threats, smoke semantics, quantization). WARN keeps it visible
    // without failing runs on ambiguous evidence.

    static final class ProbeDisagreement implements SimDetector {
        @Override public String name() { return "DangerProbeDisagreement"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // map says safe where the body probe says heavily exposed
                for (StandardDetectors.Run r : StandardDetectors.runs(series,
                        s -> s.dangerAtSoldier >= 0
                            && s.dangerAtSoldier < SimTuning.DANGER_PROBE_SAFE_MAX
                            && s.probedBodyRays == 15
                            && s.exposedBodyRays >= SimTuning.DANGER_PROBE_RAYS_MANY)) {
                    int span = StandardDetectors.spanTicks(series, r);
                    if (span < SimTuning.DANGER_PROBE_DISAGREE_TICKS) continue;
                    SimSample end = series.get(r.toIdx());
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "map reads safe (" + String.format("%.0f", end.dangerAtSoldier) + ") while "
                            + end.exposedBodyRays + "/15 body rays are visible for " + span + "t — map underestimates danger");
                    v.with("dangerAtSoldier", String.format("%.0f", end.dangerAtSoldier))
                        .with("worstExposedRays", maxExposed(series, r))
                        .with("currentCover", end.state.currentCover)
                        .with("peekState", end.state.peekState);
                    out.add(v);
                }
                // map says hot where the body probe is fully clear. The map's
                // score is the STANDING danger (eye ray over waist-high cover)
                // while the probe measures the prone HIDING body — that gap is
                // doctrine, not error, and only fires when the LEG bit also
                // says the prone body is exposed.
                for (StandardDetectors.Run r : StandardDetectors.runs(series,
                        s -> s.dangerAtSoldier > SimTuning.DANGER_PROBE_HOT_MIN
                            && s.dangerLegExposed
                            && s.probedBodyRays == 15
                            && s.exposedBodyRays == 0)) {
                    int span = StandardDetectors.spanTicks(series, r);
                    if (span < SimTuning.DANGER_PROBE_DISAGREE_TICKS) continue;
                    SimSample end = series.get(r.toIdx());
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "map reads hot (" + String.format("%.0f", end.dangerAtSoldier)
                            + ") while all 15 body rays are blocked for " + span + "t — map overestimates danger");
                    v.with("dangerAtSoldier", String.format("%.0f", end.dangerAtSoldier))
                        .with("currentCover", end.state.currentCover)
                        .with("peekState", end.state.peekState);
                    out.add(v);
                }
            }
            return out;
        }

        private int maxExposed(List<SimSample> series, StandardDetectors.Run r) {
            int worst = 0;
            for (int i = r.fromIdx(); i <= r.toIdx(); i++) {
                worst = Math.max(worst, series.get(i).exposedBodyRays);
            }
            return worst;
        }
    }
}

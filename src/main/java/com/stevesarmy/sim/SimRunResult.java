package com.stevesarmy.sim;

import com.stevesarmy.sim.scenario.SimScenario;

import java.util.ArrayList;
import java.util.List;

/** Everything one scenario run produced: telemetry, violations, assertion outcomes. */
public final class SimRunResult {
    public final SimScenario scenario;
    public final SimTimeline timeline;
    public final int durationTicks;
    public final boolean gunsLoaded;
    public final float enemyHealthStart;
    public final float enemyHealthEnd;
    public final List<SimViolation> violations = new ArrayList<>();
    /** Empty = all explicit scenario assertions passed. */
    public final List<String> assertionFailures = new ArrayList<>();
    /** Ground-truth exposure map computed at scenario end; null when the scenario injected no threats. */
    public volatile DangerOracle.Oracle dangerOracle;
    /** Sampler-vs-oracle sweep result; null when no sampler was registered or the sweep was disabled. */
    public volatile DangerOracle.SweepReport oracleSweep;
    public final SimTickMetrics.Snapshot tickMetrics;
    public final String performanceReport;

    public SimRunResult(SimScenario scenario, int durationTicks, boolean gunsLoaded,
                        float enemyHealthStart, float enemyHealthEnd, SimTimeline timeline,
                        SimTickMetrics.Snapshot tickMetrics, String performanceReport) {
        this.scenario = scenario;
        this.durationTicks = durationTicks;
        this.gunsLoaded = gunsLoaded;
        this.enemyHealthStart = enemyHealthStart;
        this.enemyHealthEnd = enemyHealthEnd;
        this.timeline = timeline;
        this.tickMetrics = tickMetrics;
        this.performanceReport = performanceReport;
    }

    public SimRunResult(SimScenario scenario, int durationTicks, boolean gunsLoaded,
                        float enemyHealthStart, float enemyHealthEnd, SimTimeline timeline) {
        this(scenario, durationTicks, gunsLoaded, enemyHealthStart, enemyHealthEnd, timeline,
            new SimTickMetrics().snapshot(), "");
    }

    public boolean failed() {
        if (!assertionFailures.isEmpty()) return true;
        return violations.stream().anyMatch(v -> v.severity == SimViolation.Severity.FAIL);
    }

    public String summaryLine() {
        long fails = violations.stream().filter(v -> v.severity == SimViolation.Severity.FAIL).count();
        long warns = violations.size() - fails;
        return scenario.name + ": " + (failed() ? "FAIL" : "PASS")
            + " (" + fails + " fail, " + warns + " warn"
            + (assertionFailures.isEmpty() ? "" : ", " + assertionFailures.size() + " assertion(s) broken")
            + ", " + timeline.sampleCount() + " samples)";
    }
}

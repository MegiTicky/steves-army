package com.stevesarmy.sim.scenario;

import com.stevesarmy.sim.terrain.SimTerrain;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Declarative scenario definition: terrain fixture, actor setup, a scripted
 * action timeline (scenario-relative ticks), and explicit success assertions
 * on top of the generic detectors. Built once at registration, executed by
 * {@code SimManager}.
 */
public final class SimScenario {
    public record ScheduledAction(int tick, java.util.function.Consumer<SimContext> action, String describe) {}

    public record Assertion(String description, java.util.function.Predicate<SimRunResultView> predicate) {}

    /** Read-only view handed to assertions after detectors ran. */
    public interface SimRunResultView {
        com.stevesarmy.sim.SimTimeline timeline();
        int durationTicks();
        float enemyHealthStart();
        float enemyHealthEnd();
        /** Ground-truth exposure map computed at scenario end; null when no threats were injected. */
        @Nullable
        com.stevesarmy.sim.DangerOracle.Oracle dangerOracle();
        /** Violations the generic detectors produced this run (danger self-tests assert on these). */
        java.util.List<com.stevesarmy.sim.SimViolation> violations();
        /** Recorded tracer endpoints in fire order; each entry is {fromX, fromZ, toX, toZ}. */
        java.util.List<double[]> shots();
        /** Fire-ordered shot ticks, parallel to {@link #shots()} (frame-aligned, ±1t). */
        java.util.List<Long> shotTicks();
    }

    public final String name;
    public final String description;
    public final int durationTicks;
    public final boolean armed;
    public final int width;
    public final int depth;
    public final java.util.function.Consumer<SimTerrain> terrainBuilder;
    public final java.util.function.Consumer<SimContext> setup;
    public final List<ScheduledAction> actions;
    public final List<Assertion> assertions;
    /** Non-null when a FAIL from this scenario is the expected outcome (negative tests, A/B archives). */
    @Nullable
    public final String expectedFailReason;
    /** Generic detectors that are known-noisy for this scenario's geometry and are skipped. */
    public final java.util.Set<String> suppressedDetectors;
    /** Core tier: runs in the default autorun suite; unmarked scenarios are full-only. */
    public final boolean coreTier;

    private SimScenario(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.durationTicks = b.durationTicks;
        this.armed = b.armed;
        this.width = b.width;
        this.depth = b.depth;
        this.terrainBuilder = b.terrainBuilder;
        this.setup = b.setup;
        this.actions = List.copyOf(b.actions);
        this.assertions = List.copyOf(b.assertions);
        this.expectedFailReason = b.expectedFailReason;
        this.suppressedDetectors = java.util.Set.copyOf(b.suppressedDetectors);
        this.coreTier = b.coreTier;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private int durationTicks = 600;
        private boolean armed = false;
        private int width = 48;
        private int depth = 48;
        private java.util.function.Consumer<SimTerrain> terrainBuilder = t -> t.platform();
        private java.util.function.Consumer<SimContext> setup = c -> {};
        private final List<ScheduledAction> actions = new ArrayList<>();
        private final List<Assertion> assertions = new ArrayList<>();
        private String expectedFailReason = null;
        private final java.util.Set<String> suppressedDetectors = new java.util.HashSet<>();
        private boolean coreTier = false;

        private Builder(String name) {
            this.name = name;
        }

        public Builder description(String d) { this.description = d; return this; }
        public Builder duration(int ticks) { this.durationTicks = ticks; return this; }
        /** Scenario expects real gunfire (TaCZ present); firing assertions skip when absent. */
        public Builder armed() { this.armed = true; return this; }
        public Builder field(int w, int d) { this.width = w; this.depth = d; return this; }
        public Builder terrain(java.util.function.Consumer<SimTerrain> t) { this.terrainBuilder = t; return this; }
        public Builder setup(java.util.function.Consumer<SimContext> s) { this.setup = s; return this; }

        public Builder action(int tick, String describe, java.util.function.Consumer<SimContext> a) {
            actions.add(new ScheduledAction(tick, a, describe));
            return this;
        }

        /** Sustained suppression injection: an 8-round near-miss burst every interval ticks in [from, until]. */
        public Builder suppress(String labelOrNull, int from, int until, int intervalTicks) {
            for (int t = from; t <= until; t += intervalTicks) {
                final int at = t;
                actions.add(new ScheduledAction(at,
                    ctx -> ctx.injectSuppression(labelOrNull, 8),
                    (labelOrNull == null ? "all friendlies" : labelOrNull) + " pinned"));
            }
            return this;
        }

        public Builder assertThat(String description, java.util.function.Predicate<SimRunResultView> predicate) {
            assertions.add(new Assertion(description, predicate));
            return this;
        }

        /**
         * A FAIL from this scenario is the expected outcome (negative rig tests,
         * old-behavior A/B records). Reported as XFAIL and counted green; a
         * surprise PASS is reported XPASS and counted a real failure.
         */
        public Builder expectedFail(String reason) { this.expectedFailReason = reason; return this; }

        /** Skips these named generic detectors for this scenario only (known-noisy geometry). */
        public Builder suppressDetectors(String... detectorNames) {
            for (String n : detectorNames) this.suppressedDetectors.add(n);
            return this;
        }

        /** Runs in the default (core) autorun suite; unmarked scenarios only run in the full suite. */
        public Builder core() { this.coreTier = true; return this; }

        public SimScenario build() {
            return new SimScenario(this);
        }
    }
}

package com.stevesarmy.sim;

/**
 * All simulation-harness thresholds in one place so calibration is a single
 * edit. Values are in scenario ticks (1 tick = 50 ms). The machine's own
 * budgets (seeking timeout 200, stuck 60, exhaustion 60) are the reference
 * points: detectors fire at or beyond the point where the state machine should
 * have escalated on its own.
 */
public final class SimTuning {
    private SimTuning() {}

    /** Telemetry sampling cadence. */
    public static final int SAMPLE_INTERVAL_TICKS = 5;

    // --- generic detector thresholds ---------------------------------------

    /** Continuous SEEKING_COVER beyond this (== the goal's own timeout) means escalation failed. */
    public static final int STUCK_SEEK_TICKS = 220;
    /** Exhaustion-cooldown re-entries (failedSearches batch → exhausted → retry) before it is a loop. */
    public static final int SEEK_FAIL_LOOP_CYCLES = 3;
    /** Distinct claimed covers inside the oscillation window that count as churn. */
    public static final int COVER_OSCILLATION_COUNT = 4;
    public static final int COVER_OSCILLATION_WINDOW_TICKS = 200;
    /** Peek cycles (expose→return) on roughly the same cover before it is thrash. */
    public static final int PEEK_THRASH_CYCLES = 5;
    /** Thrash requires the cycles to be RAPID: all within this span (doctrine peeks run ~90-160t apart). */
    public static final int PEEK_THRASH_WINDOW_TICKS = 300;
    /** PINNED this long with no relocation/reposition queued means the escape chain is dead. */
    public static final int SUPPRESSION_STARVE_TICKS = 240;
    /** FOLLOW gate holds this long after suppression clears, or seek-blocked without progress. */
    public static final int FOLLOW_BLOCKED_TICKS = 200;
    /** failedSearches increments inside the storm window that count as a path-fail storm. */
    public static final int PATH_FAIL_STORM_HITS = 5;
    public static final int PATH_FAIL_STORM_WINDOW_TICKS = 100;
    /** Should-be-moving states with less than this much travel over the window are deadlocked. */
    public static final int POSITION_DEADLOCK_TICKS = 100;
    public static final double POSITION_DEADLOCK_MIN_TRAVEL = 2.0;
    /** Armed-only: sustained clear LoS with no enemy damage taken. */
    public static final int FIRE_STARVATION_TICKS = 200;

    // --- cover-dock fidelity thresholds -------------------------------------
    /**
     * Idle-in-cover soldiers are anchored to the slot center with a 0.08
     * deadzone, so a healthy steady-state offset is well under 0.15. This
     * FAIL bound still guarantees the soldier stands in the slot's block
     * (a half-diagonal is 0.707) while catching real drift.
     */
    public static final double DOCK_DRIFT_FAIL_OFFSET = 0.30;
    public static final int DOCK_DRIFT_TICKS = 60;
    /** Suppressed idle drift has no anchor (requests are suspended while reloading/suppressed) — only gross ejection reports. */
    public static final double DOCK_DRIFT_SUPPRESSED_OFFSET = 0.75;
    /** A hiding soldier with body rays visible to the threat this long has failed physically. */
    public static final int EXPOSED_HIDING_TICKS = 40;

    /** Follow-blocker strings that mean "cover machine owns the legs right now". */
    public static final String BLOCKED_SEEKING = "SEEKING_COVER";
    public static final String BLOCKED_REPOSITIONING = "REPOSITIONING";
}

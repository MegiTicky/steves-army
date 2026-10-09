package com.stevesarmy.sim.detector;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.sim.SimRunResult;
import com.stevesarmy.sim.SimSample;
import com.stevesarmy.sim.SimTuning;
import com.stevesarmy.sim.SimViolation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The v1 bug-signature detectors. Each one fires on a state-machine strand the
 * live mod has exhibited: stuck seeking, exhaustion loops, cover churn, peek
 * thrash, dead suppression-escape chains, follow-escape gating, path-fail
 * storms, positional deadlock, fire starvation, and claim/reservation drift.
 * All thresholds live in {@link SimTuning}.
 */
public final class StandardDetectors {
    private StandardDetectors() {}

    public static List<SimDetector> all() {
        List<SimDetector> detectors = new ArrayList<>(List.of(
            new StuckSeek(),
            new SeekFailLoop(),
            new CoverOscillation(),
            new PeekThrash(),
            new SuppressionStarve(),
            new FollowEscapeBlocked(),
            new PathFailStorm(),
            new PositionDeadlock(),
            new FireStarvation(),
            new ReservationDrift(),
            new CoverDockDrift(),
            new ExposedWhileHiding(),
            // The four wave-A TELEMETRY detectors below are diagnostics, not
            // gates: each one fires on a known fire-chain/movement family
            // that wave B (WP4 engagement arbitration, WP5 threat-facing
            // movement) exists to fix, so they report WARN until their fix
            // lands — then promote them to FAIL as that wave's acceptance.
            new FireDeadlock(),
            new FacingAwayDuringContact(),
            new RotationOscillation(),
            new InteriorStrand()));
        detectors.addAll(DangerMapDetectors.all());
        return detectors;
    }

    /** Longest run helper: indexes of a maximal run where the predicate holds. */
    record Run(int fromIdx, int toIdx) {}

    static List<Run> runs(List<SimSample> series, java.util.function.Predicate<SimSample> predicate) {
        List<Run> found = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < series.size(); i++) {
            if (predicate.test(series.get(i))) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                found.add(new Run(start, i - 1));
                start = -1;
            }
        }
        if (start >= 0) found.add(new Run(start, series.size() - 1));
        return found;
    }

    static int spanTicks(List<SimSample> series, Run run) {
        return series.get(run.toIdx).tick - series.get(run.fromIdx).tick;
    }

    // 1. SEEKING_COVER that never escalates --------------------------------

    static final class StuckSeek implements SimDetector {
        @Override public String name() { return "StuckSeek"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (Run r : runs(series, s -> "SEEKING_COVER".equals(s.state.coverState))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.STUCK_SEEK_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    // A seek that resolves into claimed cover was slow, not
                    // stranded; FAIL only when the run ends without cover.
                    boolean resolvedIntoCover = false;
                    if (r.toIdx + 1 < series.size()) {
                        SimSample next = series.get(r.toIdx + 1);
                        resolvedIntoCover = next.state.currentCover != null
                            || "IN_COVER".equals(next.state.coverState)
                            || "SUPPRESSED_IN_COVER".equals(next.state.coverState);
                    }
                    SimViolation v = new SimViolation(
                        resolvedIntoCover ? SimViolation.Severity.WARN : SimViolation.Severity.FAIL,
                        name(), end.tick, label,
                        resolvedIntoCover
                            ? "SEEKING_COVER held " + span + "t before reaching cover — slow but resolved"
                            : "SEEKING_COVER held " + span + "t without reaching cover or escalating");
                    v.with("seekingTicks", end.state.seekingTicks).with("seekingTimeout", end.state.seekingTimeout)
                        .with("failedSearches", end.state.failedSearches)
                        .with("searchExhaustionTicks", end.state.searchExhaustionTicks)
                        .with("blacklistSize", end.state.blacklistSize)
                        .with("wantCoverReason", end.state.wantCoverReason)
                        .with("targetCover", end.state.targetCover)
                        .with("searchPending", end.state.searchPending)
                        .with("queuedMode", end.state.queuedMode);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 2. exhaustion → retry → exhaustion loop -------------------------------

    static final class SeekFailLoop implements SimDetector {
        @Override public String name() { return "SeekFailLoop"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                int increments = 0;
                int exhaustionEntries = 0;
                int lastFailed = -1;
                int lastExhaustion = -1;
                int firstIncrementTick = -1;
                int lastIncrementTick = -1;
                for (SimSample s : series) {
                    if (lastFailed >= 0 && s.state.failedSearches > lastFailed) {
                        if (increments == 0) firstIncrementTick = s.tick;
                        increments += s.state.failedSearches - lastFailed;
                        lastIncrementTick = s.tick;
                    }
                    lastFailed = s.state.failedSearches;
                    if (s.state.searchExhaustionTicks > 0 && lastExhaustion <= 0 && lastFailed >= 0) {
                        exhaustionEntries++;
                    }
                    lastExhaustion = s.state.searchExhaustionTicks;
                }
                if (increments >= SimTuning.SEEK_FAIL_LOOP_CYCLES * 4) {
                    SimSample last = series.get(series.size() - 1);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), last.tick, label,
                        "cover search failed " + increments + "x across " + exhaustionEntries
                            + " exhaustion cycles (" + firstIncrementTick + "t.." + lastIncrementTick + "t) — search/exhaust loop");
                    v.with("failedSearches", last.state.failedSearches)
                        .with("searchExhaustionTicks", last.state.searchExhaustionTicks)
                        .with("coverState", last.state.coverState)
                        .with("blacklistSize", last.state.blacklistSize);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 3. cover churn ---------------------------------------------------------

    static final class CoverOscillation implements SimDetector {
        @Override public String name() { return "CoverOscillation"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (int i = 0; i < series.size(); i++) {
                    // Position -> "role@firstTick" evidence: after the verdict,
                    // the report names WHICH covers churned and in what role
                    // (current = a dock, target = an in-flight move goal).
                    java.util.LinkedHashMap<BlockPos, String> distinct =
                        new java.util.LinkedHashMap<>();
                    int windowStart = series.get(i).tick;
                    // Long + explicit flag: an Integer.MIN_VALUE sentinel would
                    // overflow the refractory subtraction (tick - MIN_VALUE
                    // wraps negative) and silently skip every quiet sample
                    // before the first suppressed one.
                    long lastSuppressedTick = Long.MIN_VALUE;
                    boolean sawSuppression = false;
                    // The ordered-attack exemption is progress-aware: cover
                    // hops while the clearing order is making observations or
                    // actively fighting are doctrine, but flapping with NO
                    // fresh observation and NO firing is churn even mid-order
                    // (the post-kill dither). Progress is tracked as CHRONOLOGICAL
                    // events (each sample whose check count or shot count rose
                    // over the previous sample); a window sample is exempt only
                    // if an event happened at or before it within the grace
                    // horizon. One shared stamp over the window would exempt
                    // churn that happened BEFORE the progress it cites.
                    long[] lastProgressBefore = new long[series.size()];
                    {
                        long lastEvent = Long.MIN_VALUE;
                        int prevChecked = Integer.MIN_VALUE;
                        int prevShots = Integer.MIN_VALUE;
                        for (int j = 0; j < series.size(); j++) {
                            SimSample s = series.get(j);
                            boolean event = false;
                            if (s.clearingChecked >= 0) {
                                if (prevChecked != Integer.MIN_VALUE && s.clearingChecked > prevChecked) {
                                    event = true;
                                }
                                prevChecked = s.clearingChecked;
                            }
                            if (prevShots != Integer.MIN_VALUE && s.shotsFired > prevShots) {
                                event = true;
                            }
                            prevShots = s.shotsFired;
                            if (event) {
                                lastEvent = s.tick;
                            }
                            lastProgressBefore[j] = lastEvent;
                        }
                    }
                    for (int j = i; j < series.size(); j++) {
                        SimSample s = series.get(j);
                        if (s.tick - windowStart > SimTuning.COVER_OSCILLATION_WINDOW_TICKS) break;
                        // Samples under active fire are the suppressed-traveler
                        // doctrine working (pin, dive, re-dive as lanes open) -
                        // only quiet claims count toward churn. The same goes for
                        // a soldier traveling to a hold objective: en-route cover
                        // claims are transient dives by design (street-crossing
                        // doctrine), and dive-release-march cycles re-claim as
                        // they advance. Ordered ATTACK advances are the same
                        // class ONLY while the order is checking new space;
                        // with no observation progress they count as churn.
                        if (s.state.suppressionLevel >= 0.30f) {
                            sawSuppression = true;
                            lastSuppressedTick = s.tick;
                            continue;
                        }
                        if (sawSuppression && s.tick - lastSuppressedTick <= 60) continue;
                        if (s.state.traveling) continue;
                        // A claim change while the soldier is executing a
                        // coordinator-driven advance (investigate walk or
                        // SECURE assemble) is the ordered move itself — the
                        // coordinator sent him there; counting it as churn
                        // condemns exactly the progress the order demands.
                        if (s.clearingAdvancing) continue;
                        long lastEvent = lastProgressBefore[j];
                        if (s.state.attacking && lastEvent != Long.MIN_VALUE
                            && s.tick - lastEvent <= 2 * SimTuning.COVER_OSCILLATION_WINDOW_TICKS) continue;
                        // Churn is measured in DOCKS: positions where the
                        // soldier actually held cover. Transient targetCover
                        // re-picks inside one long repositioning (mid-travel
                        // re-paths) are travel, not claims — counting them
                        // condemned legitimate slow approaches.
                        if (s.state.currentCover != null
                            && ("IN_COVER".equals(s.state.coverState)
                                || "SUPPRESSED_IN_COVER".equals(s.state.coverState))
                            && !distinct.containsKey(s.state.currentCover)) {
                            distinct.put(s.state.currentCover,
                                "dock" + s.state.currentCover.toShortString() + "@" + s.tick);
                        }
                    }
                    if (distinct.size() >= SimTuning.COVER_OSCILLATION_COUNT) {
                        SimSample at = series.get(Math.min(i + 1, series.size() - 1));
                        SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), at.tick, label,
                            distinct.size() + " distinct cover positions claimed within "
                                + SimTuning.COVER_OSCILLATION_WINDOW_TICKS + "t — cover churn");
                        v.with("distinctCovers", distinct.size()).with("windowStart", windowStart)
                            .with("positions", String.join(" ", distinct.values()));
                        out.add(v);
                        i = series.size(); // one report per soldier is enough
                    }
                }
            }
            return out;
        }
    }

    // 4. peek cycle churn on one cover ---------------------------------------

    static final class PeekThrash implements SimDetector {
        @Override public String name() { return "PeekThrash"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                int exposures = 0;
                int abortedPeeks = 0;
                int firstExposureTick = -1;
                int lastExposureTick = -1;
                String peekState = "";
                boolean inExposure = false;
                boolean inPeekMove = false;
                boolean peekReachedExposure = false;
                BlockPos cover = null;
                boolean coverConstant = true;
                for (SimSample s : series) {
                    boolean exposed = "EXPOSED".equals(s.state.peekState)
                        || "STANDING_IN_HALF_COVER".equals(s.state.peekState);
                    boolean peekMove = "MOVING_TO_PEEK".equals(s.state.peekState);
                    if (exposed && !inExposure) {
                        exposures++;
                        if (firstExposureTick < 0) firstExposureTick = s.tick;
                        lastExposureTick = s.tick;
                    }
                    if (peekMove && !inPeekMove) {
                        inPeekMove = true;
                        peekReachedExposure = false;
                    }
                    if (exposed && inPeekMove) peekReachedExposure = true;
                    // a peek move that folds back to cover without ever exposing is
                    // an aborted cycle — suppression re-pinned the soldier mid-peek
                    if ("RETURNING_TO_COVER".equals(s.state.peekState) && inPeekMove) {
                        inPeekMove = false;
                        if (!peekReachedExposure) abortedPeeks++;
                    }
                    inExposure = exposed;
                    if (s.state.currentCover != null) {
                        if (cover == null) cover = s.state.currentCover;
                        else if (!cover.equals(s.state.currentCover)) coverConstant = false;
                    }
                    peekState = s.state.peekState;
                }
                boolean exposureWindowThrash = firstExposureTick >= 0
                    && lastExposureTick - firstExposureTick <= SimTuning.PEEK_THRASH_WINDOW_TICKS;
                if (coverConstant && cover != null
                    && ((exposures >= SimTuning.PEEK_THRASH_CYCLES && exposureWindowThrash)
                        || abortedPeeks >= 4)) {
                    SimSample last = series.get(series.size() - 1);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), last.tick, label,
                        exposures + " peek exposures / " + abortedPeeks + " aborted peek cycles from the same cover "
                            + cover + " — peek thrash");
                    v.with("exposures", exposures).with("abortedPeeks", abortedPeeks)
                        .with("firstExposureTick", firstExposureTick)
                        .with("lastExposureTick", lastExposureTick)
                        .with("peekState", peekState);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 5. pinned with a dead escape chain --------------------------------------

    static final class SuppressionStarve implements SimDetector {
        @Override public String name() { return "SuppressionStarve"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            Set<String> escapeModes = Set.of("SUPPRESSION_REPOSITION", "CONTINUOUS_SUPPRESSION", "SUPPRESSION_POSITION");
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // Only pins in the open strand the soldier — holding claimed
                // cover through suppression is correct doctrine, not starvation.
                for (Run r : runs(series, s -> s.state.pinned
                        && s.state.currentCover == null
                        && !"IN_COVER".equals(s.state.coverState)
                        && !"SUPPRESSED_IN_COVER".equals(s.state.coverState))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.SUPPRESSION_STARVE_TICKS) continue;
                    boolean anyEscape = false;
                    Set<String> modes = new HashSet<>();
                    for (int i = r.fromIdx; i <= r.toIdx; i++) {
                        SimSample s = series.get(i);
                        modes.add(s.state.queuedMode);
                        if (!s.state.relocType.isEmpty() && !"NONE".equals(s.state.relocType)) anyEscape = true;
                        if (escapeModes.contains(s.state.queuedMode)) anyEscape = true;
                    }
                    if (!anyEscape) {
                        SimSample end = series.get(r.toIdx);
                        SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                            "PINNED in the open " + span + "t with no relocation and no suppression search ever queued — dead escape chain");
                        v.with("relocType", end.state.relocType)
                            .with("queuedModesSeen", String.join(",", modes))
                            .with("suppressionLevel", String.format("%.2f", end.state.suppressionLevel))
                            .with("coverState", end.state.coverState)
                            .with("followBlockedBy", end.state.followBlockedBy);
                        out.add(v);
                    }
                }
            }
            return out;
        }
    }

    // 6. follow march gated without progress ----------------------------------

    static final class FollowEscapeBlocked implements SimDetector {
        @Override public String name() { return "FollowEscapeBlocked"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // (a) pressure subsided but the follow gate stays shut
                for (Run r : runs(series, s -> "FOLLOW".equals(s.state.squadMode)
                        && !s.state.followBlockedBy.isEmpty()
                        && s.state.suppressionLevel < 0.5f)) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.FOLLOW_BLOCKED_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        "suppression cleared (level " + String.format("%.2f", end.state.suppressionLevel)
                            + ") but follow gate '" + end.state.followBlockedBy + "' held " + span + "t");
                    v.with("followBlockedBy", end.state.followBlockedBy)
                        .with("coverState", end.state.coverState)
                        .with("ownerDist", String.format("%.1f", end.state.ownerDist))
                        .with("wantCoverReason", end.state.wantCoverReason);
                    out.add(v);
                }
                // (b) gate held by the cover machine itself with no movement
                for (Run r : runs(series, s -> "FOLLOW".equals(s.state.squadMode)
                        && (SimTuning.BLOCKED_SEEKING.equals(s.state.followBlockedBy)
                            || SimTuning.BLOCKED_REPOSITIONING.equals(s.state.followBlockedBy)))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.FOLLOW_BLOCKED_TICKS) continue;
                    double travel = run.timeline.travel(label, series.get(r.fromIdx).tick, series.get(r.toIdx).tick);
                    if (travel >= SimTuning.POSITION_DEADLOCK_MIN_TRAVEL) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        "follow gate '" + end.state.followBlockedBy + "' held " + span
                            + "t with only " + String.format("%.2f", travel) + " blocks of travel — blocked strand");
                    v.with("coverState", end.state.coverState)
                        .with("seekingTicks", end.state.seekingTicks)
                        .with("failedSearches", end.state.failedSearches)
                        .with("searchExhaustionTicks", end.state.searchExhaustionTicks)
                        .with("ownerDist", String.format("%.1f", end.state.ownerDist));
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 7. repeated search failures ---------------------------------------------

    static final class PathFailStorm implements SimDetector {
        @Override public String name() { return "PathFailStorm"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                int stuckHits = 0;
                int firstStuckHitTick = -1;
                for (int i = 1; i < series.size(); i++) {
                    SimSample prev = series.get(i - 1);
                    SimSample s = series.get(i);
                    if (s.state.stuckTicks >= s.state.maxStuckTicks && prev.state.stuckTicks < s.state.maxStuckTicks) {
                        stuckHits++;
                        if (firstStuckHitTick < 0) firstStuckHitTick = s.tick;
                    }
                }
                if (stuckHits >= 3) {
                    SimSample last = series.get(series.size() - 1);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), last.tick, label,
                        "movement watchdog saturated " + stuckHits + "x (first at " + firstStuckHitTick
                            + "t) — repeated path/movement failure");
                    v.with("stuckHits", stuckHits)
                        .with("stuckTicks", last.state.stuckTicks)
                        .with("noProgressTicks", last.state.noProgressTicks)
                        .with("coverState", last.state.coverState);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 8. should-be-moving but standing still ----------------------------------

    static final class PositionDeadlock implements SimDetector {
        @Override public String name() { return "PositionDeadlock"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (Run r : runs(series, s -> "SEEKING_COVER".equals(s.state.coverState)
                        || "REPOSITIONING".equals(s.state.coverState))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.POSITION_DEADLOCK_TICKS) continue;
                    double travel = run.timeline.travel(label, series.get(r.fromIdx).tick, series.get(r.toIdx).tick);
                    if (travel >= SimTuning.POSITION_DEADLOCK_MIN_TRAVEL) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        end.state.coverState + " held " + span + "t with only " + String.format("%.2f", travel)
                            + " blocks of travel — legs not driven");
                    v.with("seekingTicks", end.state.seekingTicks)
                        .with("stuckTicks", end.state.stuckTicks)
                        .with("noProgressTicks", end.state.noProgressTicks)
                        .with("searchPending", end.state.searchPending)
                        .with("schedulerPending", end.state.schedulerPending)
                        .with("relocType", end.state.relocType)
                        .with("relocBackoffTicks", end.state.relocBackoffTicks);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 9. armed runs: clear LoS but the enemy never takes damage ---------------

    static final class FireStarvation implements SimDetector {
        @Override public String name() { return "FireStarvation"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            if (!run.gunsLoaded) return List.of(); // headless dev run has no guns; nothing to starve
            List<SimViolation> out = new ArrayList<>();
            boolean enemyHurt = run.enemyHealthEnd < run.enemyHealthStart - 0.01f;
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (Run r : runs(series, s -> s.hasGun && s.hasAttackTarget
                        && Boolean.TRUE.equals(s.losToEnemy)
                        && s.enemyDistance > 0 && s.enemyDistance <= 48.0f)) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.FIRE_STARVATION_TICKS || enemyHurt) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        "clear LoS to enemy for " + span + "t but the enemy took no damage — firing chain dead");
                    v.with("enemyDistance", String.format("%.1f", end.enemyDistance))
                        .with("enemyHealthStart", String.format("%.1f", run.enemyHealthStart))
                        .with("enemyHealthEnd", String.format("%.1f", run.enemyHealthEnd))
                        .with("coverState", end.state.coverState)
                        .with("pinned", end.state.pinned);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 10. cover claim without a backing reservation ----------------------------

    static final class ReservationDrift implements SimDetector {
        @Override public String name() { return "ReservationDrift"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // sustained IN_COVER whose claimed cover lost its reservation (drift)
                for (Run r : runs(series, s -> ("IN_COVER".equals(s.state.coverState)
                        || "SUPPRESSED_IN_COVER".equals(s.state.coverState))
                        && s.state.currentCover != null
                        && s.reservationCountForClaim == 0)) {
                    int span = spanTicks(series, r);
                    if (span < 60) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "occupied cover " + end.state.currentCover + " unreserved for " + span
                            + "t — claim/reservation drift");
                    v.with("currentCover", end.state.currentCover)
                        .with("coverState", end.state.coverState);
                    out.add(v);
                }
                // sustained IN_COVER with no cover position at all (ghost claim)
                for (Run r : runs(series, s -> ("IN_COVER".equals(s.state.coverState)
                        || "SUPPRESSED_IN_COVER".equals(s.state.coverState))
                        && s.state.currentCover == null)) {
                    int span = spanTicks(series, r);
                    if (span < 60) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "IN_COVER state held " + span + "t with no current cover position — ghost cover claim");
                    v.with("coverState", end.state.coverState)
                        .with("wantCoverReason", end.state.wantCoverReason);
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 11. idle-in-cover soldier sits off its cover slot ------------------------
    //
    // The anchor velocity is what docks a soldier to the exact slot center
    // (0.08 deadzone). A soldier that claims IN_COVER, is idle (HIDING), is
    // neither suppressed nor reloading, and still sits > 0.30 blocks off the
    // slot means the final-phase velocity manipulation never engaged.

    static final class CoverDockDrift implements SimDetector {
        @Override public String name() { return "CoverDockDrift"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // unsuppressed idle drift: anchor should own the position
                for (Run r : runs(series, s -> idleInCover(s)
                        && !s.reloading
                        && s.state.suppressionLevel < 0.3f
                        && s.coverSlotOffset > SimTuning.DOCK_DRIFT_FAIL_OFFSET)) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.DOCK_DRIFT_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    double maxOffset = 0;
                    for (int i = r.fromIdx; i <= r.toIdx; i++) {
                        maxOffset = Math.max(maxOffset, series.get(i).coverSlotOffset);
                    }
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        "idle in cover but sits " + String.format("%.2f", end.coverSlotOffset)
                            + " blocks off the slot center for " + span + "t — anchor velocity not docking");
                    v.with("maxSlotOffset", String.format("%.2f", maxOffset))
                        .with("currentCover", end.state.currentCover)
                        .with("peekState", end.state.peekState)
                        .with("coverState", end.state.coverState);
                    out.add(v);
                }
                // suppressed idle drift: anchor is suspended by design, but the
                // soldier must not leave the cover block entirely
                for (Run r : runs(series, s -> idleInCover(s)
                        && !s.reloading
                        && s.state.suppressionLevel >= 0.3f
                        && s.coverSlotOffset > SimTuning.DOCK_DRIFT_SUPPRESSED_OFFSET)) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.DOCK_DRIFT_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "suppressed idle soldier " + String.format("%.2f", end.coverSlotOffset)
                            + " blocks off its cover slot for " + span + "t — outside the cover block");
                    v.with("currentCover", end.state.currentCover)
                        .with("peekState", end.state.peekState);
                    out.add(v);
                }
            }
            return out;
        }

        private boolean idleInCover(SimSample s) {
            return s.state.currentCover != null
                && ("IN_COVER".equals(s.state.coverState) || "SUPPRESSED_IN_COVER".equals(s.state.coverState))
                && "HIDING".equals(s.state.peekState);
        }
    }

    // 12. "hiding" soldier physically visible to the threat --------------------
    //
    // HIDING means the 0.6-high low-crouch box: behind any cover (half or
    // full) every body ray must be blocked. A standing-height hitbox left in
    // HIDING (pose never engaged) lights this up over half cover.
    // Only docked samples count (offset <= 0.5 = inside the slot block): the
    // claim->docked transit legitimately walks exposed, and drift is the
    // CoverDockDrift detector's problem. Standing in half cover tolerates
    // HEAD exposure (you look over the wall) but feet/mid rays visible mean
    // the torso is not behind the block.

    /** Inside the claimed slot block — the exposure checks only judge docked soldiers. */
    private static boolean dockedInCover(SimSample s) {
        return s.state.currentCover != null
            && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5;
    }

    static final class ExposedWhileHiding implements SimDetector {
        @Override public String name() { return "ExposedWhileHiding"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (Run r : runs(series, s -> s.exposedBodyRays > 0
                        && "HIDING".equals(s.state.peekState)
                        && dockedInCover(s))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.EXPOSED_HIDING_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    int worst = 0;
                    int worstHead = 0;
                    for (int i = r.fromIdx; i <= r.toIdx; i++) {
                        worst = Math.max(worst, series.get(i).exposedBodyRays);
                        worstHead = Math.max(worstHead, series.get(i).exposedHeadRays);
                    }
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        worst + "/" + end.probedBodyRays + " body rays visible to the threat while HIDING in cover for "
                            + span + "t — cover claim is not physical protection");
                    v.with("worstExposedRays", worst)
                        .with("worstHeadRays", worstHead)
                        .with("probedRays", end.probedBodyRays)
                        .with("currentCover", end.state.currentCover)
                        .with("coverType", end.state.currentCoverType)
                        .with("suppressionLevel", String.format("%.2f", end.state.suppressionLevel));
                    out.add(v);
                }
                // torso (feet+mid) visible while standing in half cover — head
                // exposure is the doctrine, torso exposure is a bad slot
                for (Run r : runs(series, s -> s.exposedBodyRays - Math.max(0, s.exposedHeadRays) > 0
                        && "STANDING_IN_HALF_COVER".equals(s.state.peekState)
                        && dockedInCover(s))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.EXPOSED_HIDING_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    int worstTorso = 0;
                    for (int i = r.fromIdx; i <= r.toIdx; i++) {
                        worstTorso = Math.max(worstTorso,
                            series.get(i).exposedBodyRays - Math.max(0, series.get(i).exposedHeadRays));
                    }
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        worstTorso + " torso rays visible to the threat while standing in half cover for "
                            + span + "t — slot does not shield the body");
                    v.with("worstTorsoRays", worstTorso)
                        .with("currentCover", end.state.currentCover)
                        .with("coverType", end.state.currentCoverType)
                        .with("slotOffset", String.format("%.2f", end.coverSlotOffset));
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 13. visible + ready but the fire gate refuses, continuously ----------------------------
    //
    // FireStarvation catches the run where no shot ever LANDS; this one catches the subtler
    // strand where the gate itself is the block: the soldier sees the target, the weapon
    // reports ready, and still nothing is authorised for 100t+. Each gated tick names its
    // reason, so the histogram says WHICH link is stuck: a sustained RELOADING is a reload
    // machine bug, a sustained BULLET_PATH_BLOCKED is a lane/cover bug, an AIM_QUALITY that
    // never settles is an aim-metric bug. WEAPON_HOLD is excluded: an AT gun deliberately
    // holding on infantry is doctrine, not a stuck strand.

    static final class FireDeadlock implements SimDetector {
        @Override public String name() { return "FireDeadlock"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // One report per soldier, on the longest qualifying run; fireGate is never
                // null on a real snapshot, but the null guard keeps hand-built samples
                // from fabricating a FAIL.
                Run longest = null;
                for (Run r : runs(series, s -> s.state.targetVisible
                        && s.state.weaponReady
                        && s.state.fireGate != null
                        && !"none".equals(s.state.fireGate)
                        && !"weapon_hold".equals(s.state.fireGate))) {
                    if (longest == null || spanTicks(series, r) > spanTicks(series, longest)) {
                        longest = r;
                    }
                }
                if (longest == null) continue;
                int span = spanTicks(series, longest);
                if (span < SimTuning.FIRE_DEADLOCK_TICKS) continue;
                // Reason histogram over the run: the report must name WHICH gate reason
                // ate the run, not just that a gate did.
                java.util.LinkedHashMap<String, Integer> histogram = new java.util.LinkedHashMap<>();
                for (int i = longest.fromIdx; i <= longest.toIdx; i++) {
                    histogram.merge(series.get(i).state.fireGate, 1, Integer::sum);
                }
                StringBuilder reasons = new StringBuilder();
                histogram.forEach((reason, count) -> {
                    if (reasons.length() > 0) reasons.append(", ");
                    reasons.append(reason).append(':').append(count);
                });
                SimSample end = series.get(longest.toIdx);
                SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                    "target visible + weapon ready but the fire gate refused for " + span
                        + "t (" + reasons + ") - fire decision strand dead");
                v.with("windowStart", series.get(longest.fromIdx).tick)
                    .with("firstTick", series.get(longest.fromIdx).tick)
                    .with("lastTick", end.tick)
                    .with("reasonHistogram", reasons.toString())
                    .with("fireGateTicks", end.state.fireGateTicks)
                    .with("ticksSinceLastShot", end.state.ticksSinceLastShot)
                    .with("targetVisible", end.state.targetVisible)
                    .with("weaponReady", end.state.weaponReady);
                out.add(v);
            }
            return out;
        }
    }

    // 14. live contact in range but the body never faces it ----------------------------------
    //
    // Facing error is the angle between where the BODY points (yBodyRot) and the bearing to
    // an in-range threat. A soldier can hold targetVisible through the eye ray while the
    // torso points elsewhere - the gate then starves on ADS/aim-quality. More than a right
    // angle off for 40t+ means aim acquisition never drove the body at all: the orientation
    // chain, not the fire chain, is the dead link.

    static final class FacingAwayDuringContact implements SimDetector {
        @Override public String name() { return "FacingAwayDuringContact"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                for (Run r : runs(series, s -> s.state.threatInRange
                        && s.state.facingErrorDeg > SimTuning.FACING_AWAY_ERROR_DEG)) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.FACING_AWAY_TICKS) continue;
                    float maxError = 0;
                    float minRange = -1;
                    float maxRange = -1;
                    for (int i = r.fromIdx; i <= r.toIdx; i++) {
                        SimSample s = series.get(i);
                        maxError = Math.max(maxError, s.state.facingErrorDeg);
                        if (s.enemyDistance >= 0) {
                            minRange = minRange < 0 ? s.enemyDistance : Math.min(minRange, s.enemyDistance);
                            maxRange = Math.max(maxRange, s.enemyDistance);
                        }
                    }
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "faced " + String.format("%.0f", maxError) + " degrees off an in-range threat for "
                            + span + "t - body never settled on the contact");
                    v.with("maxFacingErrorDeg", String.format("%.1f", maxError))
                        .with("firstTick", series.get(r.fromIdx).tick)
                        .with("lastTick", end.tick)
                        .with("threatRangeBand", minRange < 0 ? "n/a"
                            : String.format("%.1f..%.1f", minRange, maxRange))
                        .with("enemyDistance", String.format("%.1f", end.enemyDistance))
                        .with("yBodyRot", String.format("%.1f", end.state.bodyYaw));
                    out.add(v);
                }
            }
            return out;
        }
    }

    // 15. body yaw flip-flops while the soldier stands still ---------------------------------
    //
    // A servo loop with sign-flipping error (or two controllers fighting over the same
    // torso) shows up as yaw direction reversals: +, -, +, - ... The stationary gate
    // matters - a walking soldier legitimately weaves. Four-plus direction flips inside
    // 60t with no locomotion is rotation thrash: the aim never converges even though the
    // target may be dead ahead.

    static final class RotationOscillation implements SimDetector {
        /** Deltas below this are aim-settle noise, not a directed move: only a real sign
         *  change between DIRECTED moves counts as a flip. A filter constant, not a
         *  scenario threshold - hence local instead of SimTuning. */
        private static final double FLIP_MIN_DELTA_DEG = 0.5;

        @Override public String name() { return "RotationOscillation"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // Chronological flip events. A flip is a reversal of the yaw delta's sign
                // between two directed moves; zero deltas neither flip nor reset the chain.
                // Both samples spanning a delta must be stationary - locomotion restarts
                // the chain.
                List<Integer> flipTicks = new ArrayList<>();
                List<String> yaws = new ArrayList<>();
                int prevSign = 0;
                for (int i = 1; i < series.size(); i++) {
                    SimSample s = series.get(i);
                    SimSample prev = series.get(i - 1);
                    if (s.state.stationaryTicks <= 0 || prev.state.stationaryTicks <= 0) {
                        prevSign = 0;
                        continue;
                    }
                    // Yaw wraps at +-180: fold the raw delta back into [-180, 180] or a
                    // rotation through the seam reads as a 359-degree reversal.
                    double delta = s.state.bodyYaw - prev.state.bodyYaw;
                    if (delta > 180.0) delta -= 360.0;
                    if (delta < -180.0) delta += 360.0;
                    int sign = delta > FLIP_MIN_DELTA_DEG ? 1 : (delta < -FLIP_MIN_DELTA_DEG ? -1 : 0);
                    if (sign != 0) {
                        if (prevSign != 0 && sign != prevSign) {
                            flipTicks.add(s.tick);
                            yaws.add(String.format("%.1f->%.1f", prev.state.bodyYaw, s.state.bodyYaw));
                        }
                        prevSign = sign;
                    }
                }
                // Densest window: two-pointer sweep over the chronological flip ticks; any
                // window of WINDOW_TICKS span holding >= FLIPS flips fires.
                int bestA = -1;
                int bestB = -1;
                int bestCount = 0;
                int a = 0;
                for (int b = 0; b < flipTicks.size(); b++) {
                    while (flipTicks.get(b) - flipTicks.get(a) > SimTuning.ROTATION_OSCILLATION_WINDOW_TICKS) a++;
                    int count = b - a + 1;
                    if (count > bestCount) {
                        bestCount = count;
                        bestA = a;
                        bestB = b;
                    }
                }
                if (bestCount < SimTuning.ROTATION_OSCILLATION_FLIPS) continue;
                SimSample last = series.get(series.size() - 1);
                StringBuilder ticks = new StringBuilder();
                StringBuilder yawList = new StringBuilder();
                for (int i = bestA; i <= bestB; i++) {
                    if (ticks.length() > 0) {
                        ticks.append(',');
                        yawList.append(',');
                    }
                    ticks.append(flipTicks.get(i));
                    yawList.append(yaws.get(i));
                }
                SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), last.tick, label,
                    bestCount + " body-yaw direction flips within " + SimTuning.ROTATION_OSCILLATION_WINDOW_TICKS
                        + "t while stationary (window " + flipTicks.get(bestA) + "t.." + flipTicks.get(bestB)
                        + "t) - rotation thrash");
                v.with("flipsInWindow", bestCount)
                    .with("windowStart", flipTicks.get(bestA))
                    .with("flipTicks", ticks.toString())
                    .with("yawAtFlips", yawList.toString())
                    .with("stationaryTicks", last.state.stationaryTicks);
                out.add(v);
            }
            return out;
        }
    }

    // 16. stuck still with a far objective and no excuse -------------------------------------
    //
    // PositionDeadlock covers soldiers inside the cover machine's should-be-moving states
    // (SEEKING_COVER / REPOSITIONING); this one covers the strand past it: the machine
    // reports none of those states, the soldier stands still, the objective is still far,
    // and the sample carries NO blocking excuse. Anything that would legitimately hold the
    // legs - a follow gate, a suppression pin, a contact-plan denial, a path wait - must
    // surface as blockedExcuse; silence plus stillness plus distance means the advance
    // strand died without admitting it.

    static final class InteriorStrand implements SimDetector {
        @Override public String name() { return "InteriorStrand"; }

        @Override
        public List<SimViolation> evaluate(SimRunResult run) {
            List<SimViolation> out = new ArrayList<>();
            for (String label : run.timeline.soldierLabels()) {
                List<SimSample> series = run.timeline.series(label);
                // The counter itself must exceed the threshold on every sample AND the run
                // must span the threshold in ticks: a counter that keeps resetting (brief
                // shuffles) never accumulates a run, which is exactly the progress this
                // detector must not condemn.
                for (Run r : runs(series, s -> s.state.stationaryTicks > SimTuning.INTERIOR_STRAND_TICKS
                        && s.state.objectiveFar
                        && (s.state.blockedExcuse == null || s.state.blockedExcuse.isEmpty()))) {
                    int span = spanTicks(series, r);
                    if (span < SimTuning.INTERIOR_STRAND_TICKS) continue;
                    SimSample end = series.get(r.toIdx);
                    SimViolation v = new SimViolation(SimViolation.Severity.WARN, name(), end.tick, label,
                        "stationary " + span + "t with the objective still far and no blocking excuse"
                            + " reported - advance strand dead");
                    v.with("position", end.state.position == null ? "n/a"
                            : String.format("%.1f,%.1f,%.1f",
                                end.state.position.x, end.state.position.y, end.state.position.z))
                        .with("stationaryTicks", end.state.stationaryTicks)
                        .with("objectiveFar", end.state.objectiveFar)
                        .with("blockedExcuse", end.state.blockedExcuse == null ? "" : end.state.blockedExcuse)
                        .with("squadMode", end.state.squadMode)
                        .with("coverState", end.state.coverState);
                    out.add(v);
                }
            }
            return out;
        }
    }
}

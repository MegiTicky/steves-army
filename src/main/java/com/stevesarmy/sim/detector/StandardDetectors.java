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
            new ExposedWhileHiding()));
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
                    Set<BlockPos> distinct = new HashSet<>();
                    int windowStart = series.get(i).tick;
                    // Long + explicit flag: an Integer.MIN_VALUE sentinel would
                    // overflow the refractory subtraction (tick - MIN_VALUE
                    // wraps negative) and silently skip every quiet sample
                    // before the first suppressed one.
                    long lastSuppressedTick = Long.MIN_VALUE;
                    boolean sawSuppression = false;
                    for (int j = i; j < series.size(); j++) {
                        SimSample s = series.get(j);
                        if (s.tick - windowStart > SimTuning.COVER_OSCILLATION_WINDOW_TICKS) break;
                        // Samples under active fire are the suppressed-traveler
                        // doctrine working (pin, dive, re-dive as lanes open) -
                        // only quiet claims count toward churn. The same goes for
                        // a soldier traveling to a hold objective: en-route cover
                        // claims are transient dives by design (street-crossing
                        // doctrine), and dive-release-march cycles re-claim as
                        // they advance. A refractory tail after the last
                        // suppressed sample covers the post-fire recovery claim.
                        // Ordered ATTACK advances are the same class: clearing
                        // doctrine hops cover toward the next unchecked spot on
                        // purpose, so a short claim chain is the order working.
                        if (s.state.suppressionLevel >= 0.30f) {
                            sawSuppression = true;
                            lastSuppressedTick = s.tick;
                            continue;
                        }
                        if (sawSuppression && s.tick - lastSuppressedTick <= 60) continue;
                        if (s.state.traveling) continue;
                        if (s.state.attacking) continue;
                        if (s.state.targetCover != null) distinct.add(s.state.targetCover);
                        if (s.state.currentCover != null) distinct.add(s.state.currentCover);
                    }
                    if (distinct.size() >= SimTuning.COVER_OSCILLATION_COUNT) {
                        SimSample at = series.get(Math.min(i + 1, series.size() - 1));
                        SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), at.tick, label,
                            distinct.size() + " distinct cover positions claimed within "
                                + SimTuning.COVER_OSCILLATION_WINDOW_TICKS + "t — cover churn");
                        v.with("distinctCovers", distinct.size()).with("windowStart", windowStart);
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
}

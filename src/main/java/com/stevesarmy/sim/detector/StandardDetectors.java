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
        return List.of(
            new StuckSeek(),
            new SeekFailLoop(),
            new CoverOscillation(),
            new PeekThrash(),
            new SuppressionStarve(),
            new FollowEscapeBlocked(),
            new PathFailStorm(),
            new PositionDeadlock(),
            new FireStarvation(),
            new ReservationDrift());
    }

    /** Longest run helper: indexes of a maximal run where the predicate holds. */
    private record Run(int fromIdx, int toIdx) {}

    private static List<Run> runs(List<SimSample> series, java.util.function.Predicate<SimSample> predicate) {
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

    private static int spanTicks(List<SimSample> series, Run run) {
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
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), end.tick, label,
                        "SEEKING_COVER held " + span + "t without reaching cover or escalating");
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
                    for (int j = i; j < series.size(); j++) {
                        SimSample s = series.get(j);
                        if (s.tick - windowStart > SimTuning.COVER_OSCILLATION_WINDOW_TICKS) break;
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
                if (coverConstant && cover != null
                    && (exposures >= SimTuning.PEEK_THRASH_CYCLES || abortedPeeks >= 4)) {
                    SimSample last = series.get(series.size() - 1);
                    SimViolation v = new SimViolation(SimViolation.Severity.FAIL, name(), last.tick, label,
                        exposures + " peek exposures / " + abortedPeeks + " aborted peek cycles from the same cover "
                            + cover + " — peek thrash");
                    v.with("exposures", exposures).with("abortedPeeks", abortedPeeks)
                        .with("firstExposureTick", firstExposureTick)
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
                for (Run r : runs(series, s -> s.state.pinned)) {
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
                            "PINNED " + span + "t with no relocation and no suppression search ever queued — dead escape chain");
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
}

package com.stevesarmy.debug.trace;

import com.google.gson.*;
import com.stevesarmy.combat.CombatStateSnapshot;
import com.stevesarmy.sim.*;
import com.stevesarmy.sim.detector.StandardDetectors;
import com.stevesarmy.sim.scenario.SimScenario;
import java.util.Set;

/** Runs only existing pure detectors whose required inputs the passive recorder actually captured. */
public final class TraceAnalysis {
    private TraceAnalysis() {}
    private static final Set<String> APPLICABLE = Set.of("StuckSeek", "SeekFailLoop", "CoverOscillation", "PeekThrash",
        "SuppressionStarve", "FollowEscapeBlocked", "PathFailStorm", "PositionDeadlock", "ReservationDrift",
        "CoverDockDrift", "FireDeadlock", "FacingAwayDuringContact", "RotationOscillation", "InteriorStrand");

    public static JsonArray findings(String id, JsonArray frames) {
        JsonArray findings = new JsonArray();
        SimTimeline timeline = new SimTimeline();
        long firstTick = -1;
        int duration = 0;
        for (JsonElement element : frames) {
            JsonObject f = element.getAsJsonObject();
            if (!id.equals(f.get("soldierId").getAsString()) || !f.has("state") || f.get("state").isJsonNull()) continue;
            if (firstTick < 0) firstTick = f.get("tick").getAsLong();
            int tick = (int) (f.get("tick").getAsLong() - firstTick); duration = Math.max(duration, tick);
            CombatStateSnapshot state = TraceJson.GSON.fromJson(f.get("state"), CombatStateSnapshot.class);
            timeline.add(new SimSample(tick, id, state, (float) number(f, "health", 0), 0,
                f.has("targetId") && !f.get("targetId").isJsonNull(), null, -1,
                (int) number(f, "reservationCount", -1), false, 0, -1, -1,
                number(f, "slotOffset", -1), f.getAsJsonObject("movement").has("anchorPulls")
                    ? f.getAsJsonObject("movement").get("anchorPulls").getAsInt() : -1,
                -1, -1, 0, f.get("reloading").getAsBoolean(), f.get("posture").getAsString(), false,
                state.currentCoverHeight, number(f, "dangerScore", -1), false, state.headYaw,
                -1, -1, false, f.has("clearing") && f.getAsJsonObject("clearing").get("advancing").getAsBoolean(),
                text(f.getAsJsonObject("movement"), "source"), text(f.getAsJsonObject("movement"), "cautionReason"), null, null));
        }
        if (timeline.sampleCount() < 2) return findings;
        SimRunResult result = new SimRunResult(SimScenario.builder("live_trace").duration(duration).build(), duration, false, 0, 0, timeline);
        for (var detector : StandardDetectors.all()) if (APPLICABLE.contains(detector.name())) {
            try {
                for (SimViolation violation : detector.evaluate(result)) {
                    JsonObject j = TraceJson.GSON.toJsonTree(violation).getAsJsonObject();
                    j.addProperty("interpretation", "suspected signature, not a confirmed root cause");
                    j.addProperty("gameTick", firstTick + violation.tick);
                    findings.add(j);
                }
            } catch (RuntimeException e) {
                JsonObject skipped = new JsonObject(); skipped.addProperty("detector", detector.name());
                skipped.addProperty("skipped", "Insufficient compatible live evidence: " + e.getClass().getSimpleName()); findings.add(skipped);
            }
        }
        return findings;
    }
    private static double number(JsonObject j, String key, double fallback) { return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsDouble() : fallback; }
    private static String text(JsonObject j, String key) { return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsString() : "unavailable"; }
}

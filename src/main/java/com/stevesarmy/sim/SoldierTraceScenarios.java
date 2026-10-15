package com.stevesarmy.sim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.debug.trace.TraceBuffer;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Focused server-simulator probes for recorder retention and detached snapshots. */
public final class SoldierTraceScenarios {
    private SoldierTraceScenarios() {}

    public static SimScenario recorderProbes() {
        ProbeState probe = new ProbeState();
        return SimScenario.builder("soldier_trace_recorder_probes")
            .description("Records a stalled rifleman with nearby rifleman, machine-gunner, support, and enemy actors; "
                + "checks cadence-independent events, detached freezes, stopped history, removal records, and buffer limits.")
            .duration(240)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 8);
                var stalled = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "trace_stalled", 18, 0, 18, SquadMode.HOLD);
                stalled.setNoAi(true);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "trace_active", 21, 0, 18, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "trace_mg", 18, 0, 21, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SUPPORT.get(), "trace_support", 21, 0, 21, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("trace_enemy", 24, 0, 18).setNoAi(true);
                ctx.setDangerSweepEnabled(false);
                probe.stalledId = stalled.getUUID();
                probe.activeId = ctx.soldier("trace_active").getUUID();
                probe.machineGunnerId = ctx.soldier("trace_mg").getUUID();
                probe.supportId = ctx.soldier("trace_support").getUUID();
                probe.enemyId = ctx.soldier("trace_enemy").getUUID();
                probe.bufferWindow = bufferWindowProbe();
                probe.bufferBudget = bufferBudgetProbe();
            })
            .action(1, "start recorder before selecting an actor", ctx -> {
                if (SoldierFlightRecorder.isRecording()) SoldierFlightRecorder.stop();
                String result = SoldierFlightRecorder.start(60, 5);
                probe.started = SoldierFlightRecorder.isRecording() && result.contains("Recording ALL");
            })
            .action(42, "record controller failure between regular samples", ctx -> {
                SoldierFlightRecorder.event(ctx.soldier("trace_stalled"), "controller-failure-probe",
                    "stalled-no-ai", java.util.Map.of("at", "between-samples", "movement", "blocked"));
            })
            .action(70, "freeze without stopping background capture", ctx -> {
                probe.firstFreeze = SoldierFlightRecorder.freeze(probe.stalledId);
            })
            .action(82, "record after detached snapshot", ctx -> {
                SoldierFlightRecorder.event(ctx.soldier("trace_stalled"), "future-after-freeze-probe",
                    "continued-recording", java.util.Map.of("sequence", 2));
                probe.repeatedStartMessage = SoldierFlightRecorder.start(60, 5);
                probe.secondFreeze = SoldierFlightRecorder.freeze(probe.stalledId);
            })
            .action(105, "remove selected actor while retaining its UUID history", ctx -> {
                ctx.soldier("trace_stalled").discard();
            })
            .action(125, "stop and freeze retained history", ctx -> {
                probe.stopMessage = SoldierFlightRecorder.stop();
                probe.stoppedFreeze = SoldierFlightRecorder.freeze(probe.stalledId);
            })
            .action(135, "verify repeated freeze does not rewrite stopped history", ctx -> {
                probe.stoppedFreezeAgain = SoldierFlightRecorder.freeze(probe.stalledId);
            })
            .action(230, "leave recorder stopped after the fixture", ctx -> {
                if (SoldierFlightRecorder.isRecording()) SoldierFlightRecorder.stop();
            })
            .assertThat("recording starts with no selected actor and retains sampled frames",
                view -> probe.started && frameCount(probe.firstFreeze, probe.stalledId) > 0)
            .assertThat("freeze includes multiple nearby soldier roles and the enemy actor",
                view -> containsFrameActors(probe.firstFreeze, probe.activeId, probe.machineGunnerId,
                    probe.supportId, probe.enemyId))
            .assertThat("controller failure event is retained independently of the sample cadence",
                view -> hasEvent(probe.firstFreeze, probe.stalledId, "controller-failure-probe"))
            .assertThat("freeze is detached while recording continues and repeated start preserves history",
                view -> probe.firstFreeze != null && probe.secondFreeze != null
                    && !hasEvent(probe.firstFreeze, probe.stalledId, "future-after-freeze-probe")
                    && hasEvent(probe.secondFreeze, probe.stalledId, "future-after-freeze-probe")
                    && probe.repeatedStartMessage != null && probe.repeatedStartMessage.contains("already running")
                    && firstTime(probe.firstFreeze, probe.stalledId) == firstTime(probe.secondFreeze, probe.stalledId))
            .assertThat("removed selected actor keeps history and receives an unavailable event",
                view -> probe.stoppedFreeze != null
                    && hasEvent(probe.stoppedFreeze, probe.stalledId, "actor-unavailable"))
            .assertThat("stopped freeze remains readable without extending frame history",
                view -> probe.stopMessage != null && probe.stopMessage.startsWith("Recording stopped")
                    && probe.stoppedFreeze != null && probe.stoppedFreezeAgain != null
                    && frameCount(probe.stoppedFreeze, probe.stalledId)
                        == frameCount(probe.stoppedFreezeAgain, probe.stalledId)
                    && !probe.stoppedFreezeAgain.getAsJsonObject("metadata").get("recordingContinues").getAsBoolean())
            .assertThat("TraceBuffer prunes by time window and enforces byte cap",
                view -> probe.bufferWindow && probe.bufferBudget)
            .build();
    }

    public static SimScenario exportCommandProbe() {
        ExportProbeState probe = new ExportProbeState();
        return SimScenario.builder("soldier_trace_export_probe")
            .description("Runs the registered recorder start/export/stop commands and verifies asynchronous JSON, "
                + "viewer, summary, terrain and recording-state artifacts.")
            .duration(1200).field(32, 32)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                if (SoldierFlightRecorder.isRecording()) SoldierFlightRecorder.stop();
                var selected = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "export_stalled", 16, 0, 16, SquadMode.HOLD);
                selected.setNoAi(true);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "export_mg", 19, 0, 16, SquadMode.HOLD).setNoAi(true);
                ctx.spawnFriendly(ModEntities.SUPPORT.get(), "export_support", 16, 0, 19, SquadMode.HOLD).setNoAi(true);
                ctx.spawnEnemyLabeled("export_enemy", 21, 0, 16).setNoAi(true);
                probe.selectedId = selected.getUUID();
                probe.exportRoot = ctx.level.getServer().getServerDirectory().toPath()
                    .resolve("debug").resolve("soldier_traces");
                probe.preexisting = directoryNames(probe.exportRoot);
                ctx.setDangerSweepEnabled(false);
            })
            .action(1, "start recorder through registered debug command", ctx -> {
                probe.startCommandResult = command(ctx, "stevesarmy_debug record start 60 5");
            })
            .action(50, "export selected actor through registered debug command", ctx -> {
                probe.firstCommandResult = command(ctx,
                    "stevesarmy_debug record export uuid " + probe.selectedId);
            })
            .action(260, "poll first asynchronous artifact", ctx -> collectExports(probe))
            .action(360, "launch stopped-history export after first writer completes", ctx -> {
                collectExports(probe);
                launchStoppedExport(ctx, probe);
            })
            .action(460, "poll asynchronous exports", ctx -> {
                collectExports(probe);
                launchStoppedExport(ctx, probe);
            })
            .action(560, "poll asynchronous exports", ctx -> {
                collectExports(probe);
                launchStoppedExport(ctx, probe);
            })
            .action(700, "poll final trace files", ctx -> collectExports(probe))
            .action(1000, "poll delayed asynchronous trace writes", ctx -> collectExports(probe))
            .action(1190, "final artifact scan", ctx -> collectExports(probe))
            .assertThat("real registered command starts recording and queues selected-UUID export",
                view -> probe.startCommandResult > 0 && probe.firstCommandResult > 0)
            .assertThat("recording export writes valid JSON, HTML, summary and terrain with a consistent unloaded marker",
                view -> probe.firstExport != null && probe.firstExport.recordingContinues
                    && probe.firstExport.filesComplete && probe.firstExport.selectedId.equals(probe.selectedId.toString())
                    && probe.firstExport.terrainValid)
            .assertThat("second command exports retained history after stop", view ->
                probe.stopCommandResult > 0 && probe.secondCommandResult > 0 && probe.secondExport != null
                    && !probe.secondExport.recordingContinues && probe.secondExport.filesComplete
                    && probe.secondExport.selectedId.equals(probe.selectedId.toString()))
            .assertThat("export worker returned to idle after both writes", view -> probe.finalStatus != null
                && probe.finalStatus.contains("export: idle"))
            .build();
    }

    public static SimScenario populationProbe() {
        PopulationProbeState probe = new PopulationProbeState();
        return SimScenario.builder("soldier_trace_population_probe")
            .description("Logs disabled and recording status plus average server tick time at 24 and 100 no-AI soldiers; "
                + "timings are observational and have no pass/fail threshold.")
            .duration(520).field(96, 96)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                if (SoldierFlightRecorder.isRecording()) SoldierFlightRecorder.stop();
                SoldierFlightRecorder.start(60, 5);
                SoldierFlightRecorder.stop();
                for (int i = 0; i < 24; i++) spawnNoAi(ctx, "pop24_" + i, 8 + (i % 6) * 6, 8 + (i / 6) * 6);
                ctx.setDangerSweepEnabled(false);
            })
            .action(100, "capture disabled 24-actor baseline", ctx -> {
                capturePopulation(probe, "disabled24", ctx);
            })
            .action(101, "record 24-actor population", ctx -> {
                probe.start24 = SoldierFlightRecorder.start(60, 5);
            })
            .action(201, "capture enabled 24-actor sample", ctx -> {
                capturePopulation(probe, "recording24", ctx);
                probe.stop24 = SoldierFlightRecorder.stop();
            })
            .action(301, "capture disabled 24-actor follow-up", ctx -> {
                capturePopulation(probe, "disabled24Followup", ctx);
            })
            .action(302, "grow fixture to 100 no-AI actors", ctx -> {
                for (int i = 24; i < 100; i++) {
                    spawnNoAi(ctx, "pop100_" + i, 8 + (i % 10) * 7, 48 + ((i - 24) / 10) * 6);
                }
                SoldierFlightRecorder.start(60, 5);
                SoldierFlightRecorder.stop();
            })
            .action(402, "capture disabled 100-actor baseline", ctx -> {
                capturePopulation(probe, "disabled100", ctx);
            })
            .action(403, "record 100-actor population", ctx -> {
                probe.start100 = SoldierFlightRecorder.start(60, 5);
            })
            .action(503, "capture enabled 100-actor sample", ctx -> {
                capturePopulation(probe, "recording100", ctx);
                probe.stop100 = SoldierFlightRecorder.stop();
            })
            .assertThat("disabled phases cover 24 and 100 actor populations",
                view -> probe.disabled24 != null && probe.disabled100 != null
                    && probe.disabled24.population == 24 && probe.disabled100.population == 100)
            .assertThat("recorder runs for both populations and reports observed actor/row metrics",
                view -> probe.start24 != null && probe.start24.startsWith("Recording ALL")
                    && probe.start100 != null && probe.start100.startsWith("Recording ALL")
                    && probe.recording24 != null && probe.recording24.status.contains("24 loaded soldiers")
                    && probe.recording100 != null && probe.recording100.status.contains("100 loaded soldiers")
                    && probe.recording24.serverAverageMs >= 0 && probe.recording100.serverAverageMs >= 0)
            .assertThat("fixture exits with recorder stopped", view -> probe.stop24 != null && probe.stop100 != null
                && !SoldierFlightRecorder.isRecording())
            .build();
    }

    private static void spawnNoAi(com.stevesarmy.sim.scenario.SimContext ctx, String label, int x, int z) {
        ctx.spawnFriendly(ModEntities.SOLDIER.get(), label, x, 0, z, SquadMode.HOLD).setNoAi(true);
    }

    private static void capturePopulation(PopulationProbeState probe, String key,
                                          com.stevesarmy.sim.scenario.SimContext ctx) {
        PopulationObservation observation = new PopulationObservation(ctx.friendlies.size(),
            SoldierFlightRecorder.status(), ctx.level.getServer().getAverageTickTime());
        switch (key) {
            case "disabled24" -> probe.disabled24 = observation;
            case "recording24" -> probe.recording24 = observation;
            case "disabled24Followup" -> probe.disabled24Followup = observation;
            case "disabled100" -> probe.disabled100 = observation;
            case "recording100" -> probe.recording100 = observation;
            default -> throw new IllegalArgumentException("Unknown population probe phase " + key);
        }
        StevesArmyMod.LOGGER.info("[Sim][trace-population] phase={} population={} serverAverageMs={} recorderStatus={}",
            key, observation.population, observation.serverAverageMs, observation.status);
    }

    private static int command(com.stevesarmy.sim.scenario.SimContext ctx, String command) {
        try {
            return ctx.level.getServer().getCommands().performPrefixedCommand(
                ctx.level.getServer().createCommandSourceStack().withPermission(2), command);
        } catch (RuntimeException e) {
            StevesArmyMod.LOGGER.error("[Sim][trace-export] command failed: {}", command, e);
            return 0;
        }
    }

    private static void collectExports(ExportProbeState probe) {
        if (probe.exportRoot == null || !Files.isDirectory(probe.exportRoot)) return;
        try (Stream<Path> dirs = Files.list(probe.exportRoot)) {
            for (Path directory : dirs.filter(Files::isDirectory).toList()) {
                String name = directory.getFileName().toString();
                if (probe.preexisting.contains(name) || probe.inspected.contains(name)) continue;
                Path json = directory.resolve("trace.json");
                Path html = directory.resolve("viewer.html");
                Path summary = directory.resolve("summary.txt");
                if (!Files.isRegularFile(json) || !Files.isRegularFile(html) || !Files.isRegularFile(summary)) continue;
                JsonObject bundle = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                String selected = bundle.get("selectedId").getAsString();
                if (!probe.selectedId.toString().equals(selected)) continue;
                probe.inspected.add(name);
                JsonObject metadata = bundle.getAsJsonObject("metadata");
                JsonObject terrain = bundle.getAsJsonObject("terrain");
                boolean unloadedConsistent = terrain != null
                    && terrain.getAsJsonArray("unloadedChunks") != null
                    && terrain.get("unloadedChunksPresent").getAsBoolean()
                        == !terrain.getAsJsonArray("unloadedChunks").isEmpty();
                ExportEvidence evidence = new ExportEvidence(selected,
                    metadata.get("recordingContinues").getAsBoolean(),
                    Files.size(json) > 0 && Files.size(html) > 0 && Files.size(summary) > 0,
                    terrain != null && terrain.getAsJsonArray("palette") != null
                        && terrain.getAsJsonArray("sections") != null
                        && terrain.get("radius").getAsInt() == 64
                        && terrain.getAsJsonArray("boundsXZ").size() == 4
                        && unloadedConsistent,
                    unloadedConsistent);
                if (evidence.recordingContinues) probe.firstExport = evidence;
                else probe.secondExport = evidence;
                StevesArmyMod.LOGGER.info("[Sim][trace-export] verified {} selected={} recordingContinues={} terrainValid={} unloadedMarkerConsistent={} filesComplete={}",
                    directory.toAbsolutePath(), selected, evidence.recordingContinues, evidence.terrainValid,
                    evidence.unloadedMarkerConsistent, evidence.filesComplete);
            }
        } catch (IOException | RuntimeException e) {
            probe.scanError = e.toString();
            StevesArmyMod.LOGGER.error("[Sim][trace-export] artifact scan failed under {}", probe.exportRoot, e);
        }
        probe.finalStatus = SoldierFlightRecorder.status();
    }

    private static void launchStoppedExport(com.stevesarmy.sim.scenario.SimContext ctx, ExportProbeState probe) {
        if (probe.firstExport == null || probe.secondCommandResult > 0
            || !SoldierFlightRecorder.status().contains("export: idle")) return;
        probe.stopCommandResult = command(ctx, "stevesarmy_debug record stop");
        probe.secondCommandResult = command(ctx,
            "stevesarmy_debug record export uuid " + probe.selectedId);
    }

    private static Set<String> directoryNames(Path root) {
        if (!Files.isDirectory(root)) return Set.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static boolean bufferWindowProbe() {
        TraceBuffer buffer = new TraceBuffer(50, 4096);
        UUID actor = UUID.randomUUID();
        JsonObject row = new JsonObject();
        row.addProperty("sample", 1);
        buffer.add(0, "frame", actor, "minecraft:overworld", row);
        row.addProperty("sample", 2);
        buffer.add(80, "frame", actor, "minecraft:overworld", row);
        return buffer.size() == 1 && buffer.firstTime() == 80 && buffer.rows().get(0).json().get("sample").getAsInt() == 2;
    }

    private static boolean bufferBudgetProbe() {
        TraceBuffer buffer = new TraceBuffer(10_000, 560);
        UUID actor = UUID.randomUUID();
        JsonObject row = new JsonObject();
        row.addProperty("payload", "x".repeat(260));
        buffer.add(1, "event", actor, "minecraft:overworld", row);
        buffer.add(2, "event", actor, "minecraft:overworld", row);
        return buffer.size() == 1 && buffer.dropped() == 1 && buffer.truncated(2);
    }

    private static int frameCount(JsonObject bundle, UUID selectedId) {
        if (bundle == null) return 0;
        int count = 0;
        for (var element : bundle.getAsJsonArray("frames")) {
            if (selectedId.toString().equals(element.getAsJsonObject().get("soldierId").getAsString())) count++;
        }
        return count;
    }

    private static long firstTime(JsonObject bundle, UUID selectedId) {
        if (bundle == null) return -1;
        long first = Long.MAX_VALUE;
        for (var element : bundle.getAsJsonArray("frames")) {
            JsonObject frame = element.getAsJsonObject();
            if (selectedId.toString().equals(frame.get("soldierId").getAsString())) {
                first = Math.min(first, frame.get("timeMs").getAsLong());
            }
        }
        return first == Long.MAX_VALUE ? -1 : first;
    }

    private static boolean containsFrameActors(JsonObject bundle, UUID... expected) {
        if (bundle == null) return false;
        Set<String> found = new HashSet<>();
        for (var element : bundle.getAsJsonArray("frames")) {
            JsonObject frame = element.getAsJsonObject();
            if (frame.has("soldierId")) found.add(frame.get("soldierId").getAsString());
        }
        for (UUID id : expected) if (!found.contains(id.toString())) return false;
        return true;
    }

    private static boolean hasEvent(JsonObject bundle, UUID actor, String category) {
        if (bundle == null) return false;
        for (var element : bundle.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            if (actor.toString().equals(event.get("soldierId").getAsString())
                && category.equals(event.get("category").getAsString())) return true;
        }
        return false;
    }

    private static final class ProbeState {
        private UUID stalledId;
        private UUID activeId;
        private UUID machineGunnerId;
        private UUID supportId;
        private UUID enemyId;
        private JsonObject firstFreeze;
        private JsonObject secondFreeze;
        private JsonObject stoppedFreeze;
        private JsonObject stoppedFreezeAgain;
        private String repeatedStartMessage;
        private String stopMessage;
        private boolean started;
        private boolean bufferWindow;
        private boolean bufferBudget;
    }

    private static final class ExportProbeState {
        private UUID selectedId;
        private Path exportRoot;
        private Set<String> preexisting = Set.of();
        private final Set<String> inspected = new HashSet<>();
        private ExportEvidence firstExport;
        private ExportEvidence secondExport;
        private int startCommandResult;
        private int firstCommandResult;
        private int stopCommandResult;
        private int secondCommandResult;
        private String finalStatus;
        private String scanError;
    }

    private record ExportEvidence(String selectedId, boolean recordingContinues, boolean filesComplete,
                                  boolean terrainValid, boolean unloadedMarkerConsistent) {}

    private record PopulationObservation(int population, String status, float serverAverageMs) {}

    private static final class PopulationProbeState {
        private String start24;
        private String start100;
        private String stop24;
        private String stop100;
        private PopulationObservation disabled24;
        private PopulationObservation recording24;
        private PopulationObservation disabled24Followup;
        private PopulationObservation disabled100;
        private PopulationObservation recording100;
    }
}

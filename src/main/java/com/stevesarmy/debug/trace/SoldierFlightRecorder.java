package com.stevesarmy.debug.trace;

import com.google.gson.*;
import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Modifier;
import java.util.*;

/** Explicitly enabled, server-wide retrospective recorder. All live observation stays on the server thread. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierFlightRecorder {
    private static final long BUDGET = 128L * 1024 * 1024;
    private static boolean recording;
    private static int historySeconds = 60;
    private static int sampleTicks = 5;
    private static long startedNanos;
    private static long frozenTime;
    private static long sequence;
    private static long sampledTicks;
    private static long observationNanos;
    private static long observationCount;
    private static int loadedSoldiers;
    private static TraceBuffer buffer = new TraceBuffer(60_000, BUDGET);
    private static final Map<UUID, JsonObject> actors = new HashMap<>();
    private static final Map<UUID, Observation> observations = new HashMap<>();
    private static final Map<String, Gate> gates = new HashMap<>();
    private static TraceExportJob exportJob;

    private SoldierFlightRecorder() {}
    public static boolean isRecording() { return recording; }
    private static long now() { return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000); }

    public static String start(int seconds, int interval) {
        if (recording) return "Recording is already running; existing history preserved. " + status();
        if (seconds < 10 || seconds > 60 || interval < 1 || interval > 20) return "History must be 10–60 seconds and sampling 1–20 ticks.";
        historySeconds = seconds; sampleTicks = interval;
        startedNanos = System.nanoTime(); frozenTime = 0; sequence = 0; sampledTicks = 0;
        observationCount = 0; observationNanos = 0;
        buffer = new TraceBuffer(seconds * 1000L, BUDGET);
        actors.clear(); observations.clear(); gates.clear();
        recording = true;
        return "Recording ALL loaded soldiers across all dimensions: latest " + seconds + " seconds, snapshots every "
            + interval + " ticks plus decision events. Export a soldier when something looks wrong.";
    }

    public static String stop() {
        if (recording) {
            long time = now();
            flushGates(time, true);
            buffer.prune(time); frozenTime = time; recording = false;
        }
        return "Recording stopped. Retained history is frozen and can still be exported. " + status();
    }

    public static String status() {
        long time = recording ? now() : frozenTime;
        return String.format(Locale.ROOT,
            "%s | %d loaded soldiers | %d rows | %.1f/%.0fs retained | every %dt | %.2f MiB estimated / 128 MiB | %d budget drops%s | observation avg %.3f ms/tick | export: %s",
            recording ? "RECORDING" : "STOPPED", loadedSoldiers, buffer.size(),
            buffer.size() == 0 ? 0 : (time - buffer.firstTime()) / 1000.0, (double) historySeconds,
            sampleTicks, buffer.bytes() / 1048576.0, buffer.dropped(), buffer.truncated(time) ? " (TRUNCATED)" : "",
            observationCount == 0 ? 0 : observationNanos / 1_000_000.0 / observationCount,
            exportJob == null ? "idle" : exportJob.progress());
    }

    /** Call only at actual execution points. No formatting/serialization when disabled. */
    public static void event(SoldierEntity s, String category, String reason, Object detail) {
        if (!recording || s.level().isClientSide) return;
        long time = now();
        String dimension = s.level().dimension().location().toString();
        JsonElement data = detail == null ? JsonNull.INSTANCE : TraceJson.GSON.toJsonTree(detail);
        String key = s.getUUID() + ":" + category;
        String signature = dimension + ":" + reason + ":" + data;
        Gate old = gates.get(key);
        if (old != null && old.signature.equals(signature)) {
            old.repeats++;
            old.lastTick = s.level().getGameTime();
            return;
        }
        if (old != null) flush(old, time);
        JsonObject row = base(s.getUUID(), dimension, s.level().getGameTime(), time);
        row.addProperty("category", category); row.addProperty("reason", reason);
        row.add("data", data); row.addProperty("repeat", 1);
        buffer.add(time, "event", s.getUUID(), dimension, row);
        gates.put(key, new Gate(signature, row, time));
    }

    private static JsonObject base(UUID id, String dimension, long tick, long time) {
        JsonObject row = new JsonObject();
        row.addProperty("soldierId", id.toString()); row.addProperty("dimension", dimension);
        row.addProperty("tick", tick); row.addProperty("timeMs", time); row.addProperty("sequence", sequence++);
        return row;
    }

    private static void flush(Gate gate, long time) {
        if (gate.repeats == 0) return;
        JsonObject row = gate.first.deepCopy();
        row.addProperty("timeMs", time); row.addProperty("tick", gate.lastTick);
        row.addProperty("sequence", sequence++); row.addProperty("repeat", gate.repeats);
        row.addProperty("repeatSinceMs", gate.lastFlush);
        buffer.add(time, "event", UUID.fromString(row.get("soldierId").getAsString()), row.get("dimension").getAsString(), row);
        gate.repeats = 0; gate.lastFlush = time;
    }

    private static void flushGates(long time, boolean all) {
        for (Gate gate : gates.values()) if (all || time - gate.lastFlush >= 1000) flush(gate, time);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (recording) {
            long before = System.nanoTime();
            long time = now();
            boolean sample = sampledTicks++ % sampleTicks == 0;
            Set<UUID> seen = new HashSet<>();
            Map<ServerLevel, List<SoldierEntity>> byLevel = new HashMap<>();
            for (ServerLevel level : event.getServer().getAllLevels()) {
                List<SoldierEntity> soldiers = new ArrayList<>();
                // One loaded-entity pass per dimension. Never load distant chunks to record them.
                for (Entity entity : level.getEntities().getAll()) if (entity instanceof SoldierEntity s && !s.isRemoved()) soldiers.add(s);
                byLevel.put(level, soldiers);
                for (SoldierEntity s : soldiers) {
                    seen.add(s.getUUID());
                    try { observe(s, time, sample); }
                    catch (RuntimeException e) {
                        // A diagnostic failure cannot break AI/server ticks; record a bounded failure row.
                        event(s, "recorder-error", e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }
            loadedSoldiers = seen.size();
            if (sample) captureContext(byLevel, time);
            for (var entry : observations.entrySet()) if (entry.getValue().loaded && !seen.contains(entry.getKey())) {
                Observation o = entry.getValue(); o.loaded = false; o.path = null;
                long unavailableTime = now();
                JsonObject row = base(entry.getKey(), o.dimension, o.lastTick, unavailableTime);
                row.addProperty("category", "actor-unavailable"); row.addProperty("reason", "removed or no longer loaded");
                row.addProperty("repeat", 1); row.add("data", JsonNull.INSTANCE);
                buffer.add(unavailableTime, "event", entry.getKey(), o.dimension, row);
            }
            flushGates(now(), false); buffer.prune(now());
            if (sampledTicks % 100 == 0) {
                Set<UUID> retained = buffer.actorIds();
                actors.keySet().retainAll(retained);
                observations.keySet().removeIf(id -> !seen.contains(id) && !retained.contains(id));
                gates.entrySet().removeIf(e -> !retained.contains(UUID.fromString(e.getValue().first.get("soldierId").getAsString())));
            }
            observationNanos += System.nanoTime() - before; observationCount++;
        }
        if (exportJob != null && exportJob.tick(event.getServer())) exportJob = null;
    }

    private static void observe(SoldierEntity s, long time, boolean sample) {
        Observation o = observations.computeIfAbsent(s.getUUID(), ignored -> new Observation());
        String dimension = s.level().dimension().location().toString();
        if (!o.loaded || !dimension.equals(o.dimension)) event(s, "actor-available", "loaded", dimension);
        o.loaded = true; o.dimension = dimension; o.lastTick = s.level().getGameTime();
        var manager = s.getCoverBehaviorManager();
        var movement = s.getMoveControl() instanceof com.stevesarmy.entity.ai.CoverPositionController c ? c : null;
        String signature = s.getTraceRunningGoals() + "|" + s.getSquadMode() + "|" + s.getPingMoveGeneration() + "|"
            + s.getAttackGeneration() + "|" + s.getPingMoveTarget() + "|" + s.getAttackTargetPos() + "|"
            + (s.getTarget() == null ? "none" : s.getTarget().getUUID()) + "|" + s.getPose() + "|" + s.isPreparingOrReloading() + "|" + s.isHealing() + "|"
            + manager.getState() + "|" + s.getPeekController().getState() + "|"
            + (movement == null ? "" : movement.debugMoveSource() + ":" + movement.debugMoveReason()) + "|"
            + s.getEngagementTelemetry().getMovementOwner() + "|" + s.getEngagementTelemetry().getMovementBlockReason();
        if (!signature.equals(o.signature)) {
            event(s, "observed-control-change", "END-tick observation (not an inferred causal reason)", signature);
            o.signature = signature;
        }
        Path path = s.getNavigation().getPath();
        // Compare coordinates too: vanilla can update a Path instance in place.
        int hash = 1;
        if (path != null) for (int i = 0; i < path.getNodeCount(); i++) hash = 31 * hash + path.getNode(i).asBlockPos().hashCode();
        if (path != o.path || hash != o.pathHash || time - o.pathRecordedMs >= 5000) {
            o.pathId++; o.path = path; o.pathHash = hash; o.pathRecordedMs = time;
            event(s, "navigation-path", "current route geometry", SoldierTraceSampler.navigation(s, o.pathId, true));
        }
        if (!sample) return;
        actors.put(s.getUUID(), SoldierTraceSampler.identity(s));
        var snapshot = SoldierTraceSampler.state(s);
        JsonObject frame = SoldierTraceSampler.frame(s, snapshot, o.position, o.pathId);
        // Snapshot completion follows any causal events emitted by observe().
        // Reusing tick-start time here would put rows backwards in the rolling deque.
        long sampleTime = now();
        frame.addProperty("soldierId", s.getUUID().toString()); frame.addProperty("dimension", dimension);
        frame.addProperty("tick", o.lastTick); frame.addProperty("entityTick", s.tickCount);
        frame.addProperty("timeMs", sampleTime); frame.addProperty("sequence", sequence++);
        frame.addProperty("sampleElapsedMs", o.position == null ? 0 : sampleTime - o.lastSampleMs);
        buffer.add(sampleTime, "frame", s.getUUID(), dimension, frame);
        o.position = s.position(); o.lastSampleMs = sampleTime;
    }

    private static void captureContext(Map<ServerLevel, List<SoldierEntity>> byLevel, long time) {
        for (var entry : byLevel.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            // Spatial buckets avoid a soldiers x actors scan in crowded battles.
            Set<Long> occupied = new HashSet<>();
            for (SoldierEntity s : entry.getValue()) occupied.add(cell(s.getX(), s.getZ()));
            for (Entity entity : entry.getKey().getEntities().getAll()) {
                if (!(entity instanceof LivingEntity) || entity instanceof SoldierEntity || entity.isRemoved()) continue;
                int cx = net.minecraft.util.Mth.floor(entity.getX() / 64), cz = net.minecraft.util.Mth.floor(entity.getZ() / 64);
                boolean near = false;
                for (int dx = -1; dx <= 1 && !near; dx++) for (int dz = -1; dz <= 1; dz++) if (occupied.contains(cell(cx + dx, cz + dz))) { near = true; break; }
                if (!near) continue;
                actors.put(entity.getUUID(), SoldierTraceSampler.identity(entity));
                long sampleTime = now();
                JsonObject row = base(entity.getUUID(), entry.getKey().dimension().location().toString(), entry.getKey().getGameTime(), sampleTime);
                row.add("position", TraceJson.vector(entity.position())); row.add("velocity", TraceJson.vector(entity.getDeltaMovement()));
                row.addProperty("contextOnly", true); row.addProperty("alive", entity.isAlive());
                buffer.add(sampleTime, "frame", entity.getUUID(), entry.getKey().dimension().location().toString(), row);
            }
        }
    }
    private static long cell(double x, double z) { return cell(net.minecraft.util.Mth.floor(x / 64), net.minecraft.util.Mth.floor(z / 64)); }
    private static long cell(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /** Detached export at command time. World geometry is captured later and explicitly timestamped. */
    public static JsonObject freeze(UUID selectedId) {
        if (recording) { flushGates(now(), true); buffer.prune(now()); }
        List<TraceBuffer.Row> rows = buffer.rows();
        List<JsonObject> selected = new ArrayList<>();
        for (var row : rows) if (row.kind().equals("frame") && row.actor().equals(selectedId)) {
            JsonObject j = row.json(); if (!j.has("contextOnly")) selected.add(j);
        }
        if (selected.isEmpty()) return null;
        JsonObject last = selected.get(selected.size() - 1);
        String dimension = last.get("dimension").getAsString();
        // An export is one dimension segment. Never draw coordinates from another dimension on the same map.
        selected.removeIf(j -> !dimension.equals(j.get("dimension").getAsString()));
        Map<Long, List<Vec3>> selectedPositions = new HashMap<>();
        Set<UUID> included = new HashSet<>(); included.add(selectedId);
        for (JsonObject frame : selected) selectedPositions.computeIfAbsent(frame.get("tick").getAsLong(), ignored -> new ArrayList<>())
            .add(TraceJson.vector(frame.getAsJsonArray("position")));
        JsonArray frames = new JsonArray();
        // Include complete histories of actors that were near the selected soldier or explicitly referenced.
        for (var row : rows) if (row.kind().equals("frame") && dimension.equals(row.dimension())) {
            JsonObject frame = row.json();
            List<Vec3> positions = selectedPositions.getOrDefault(frame.get("tick").getAsLong(), List.of());
            Vec3 p = TraceJson.vector(frame.getAsJsonArray("position"));
            for (Vec3 center : positions) if (p.distanceToSqr(center) <= 64 * 64) { included.add(row.actor()); break; }
        }
        for (JsonObject frame : selected) {
            if (frame.has("targetId") && !frame.get("targetId").isJsonNull()) included.add(UUID.fromString(frame.get("targetId").getAsString()));
            if (frame.has("threats")) for (JsonElement t : frame.getAsJsonArray("threats")) {
                JsonElement id = t.getAsJsonObject().get("id"); if (id != null && !id.isJsonNull()) included.add(UUID.fromString(id.getAsString()));
            }
        }
        JsonArray events = new JsonArray();
        for (var row : rows) if (included.contains(row.actor()) && dimension.equals(row.dimension())) {
            if (row.kind().equals("frame")) frames.add(row.json()); else events.add(row.json());
        }
        JsonArray identities = new JsonArray();
        for (UUID id : included) if (actors.containsKey(id)) identities.add(actors.get(id).deepCopy());
        JsonObject bundle = new JsonObject();
        bundle.addProperty("schemaVersion", 1); bundle.addProperty("selectedId", selectedId.toString());
        JsonObject meta = new JsonObject();
        meta.addProperty("historySeconds", historySeconds); meta.addProperty("sampleTicks", sampleTicks);
        meta.addProperty("exportTime", java.time.Instant.now().toString()); meta.addProperty("dimension", dimension);
        meta.addProperty("coverageSeconds", (last.get("timeMs").getAsLong() - selected.get(0).get("timeMs").getAsLong()) / 1000.0);
        meta.addProperty("truncated", buffer.truncated(recording ? now() : frozenTime));
        meta.addProperty("budgetDrops", buffer.dropped()); meta.addProperty("estimatedBytes", buffer.bytes());
        meta.addProperty("clock", "timeMs: monotonic milliseconds since recording start; tick: dimension game time; entityTick: entity age");
        meta.addProperty("recordingContinues", recording);
        JsonArray mods = new JsonArray();
        ModList.get().getMods().forEach(mod -> { JsonObject m = new JsonObject(); m.addProperty("id", mod.getModId()); m.addProperty("version", mod.getVersion().toString()); mods.add(m); });
        meta.add("mods", mods);
        meta.addProperty("modVersion", ModList.get().getModContainerById(StevesArmyMod.MODID).map(m -> m.getModInfo().getVersion().toString()).orElse("unknown"));
        JsonObject config = new JsonObject();
        for (var field : StevesArmyConfig.class.getFields()) if (Modifier.isStatic(field.getModifiers()) && net.minecraftforge.common.ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
            try { config.add(field.getName(), TraceJson.GSON.toJsonTree(((net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?>) field.get(null)).get())); }
            catch (ReflectiveOperationException | RuntimeException e) { config.addProperty(field.getName(), "unavailable"); }
        }
        meta.add("effectiveConfig", config);
        JsonArray limits = new JsonArray();
        for (String limit : List.of("Terrain is captured at export, not historical; block entities and moving ship geometry are not reconstructed.",
            "Only loaded actors were recorded; no data exists before recording began. Unavailable or evicted history cannot be recovered.",
            "Full state uses the configured cadence; decision hooks record transitions between snapshots. Some gates are END-tick observations.",
            "Export uses the selected actor's latest dimension; prior dimension segments remain outside this bundle.",
            "At the rolling boundary, a path's geometry event may have expired before its frames; the overlay resumes at the next geometry refresh (within five seconds). Navigation target and index remain in frames.",
            "Exposure rays, fresh LoS probes, RNG state and async worker scheduling are not captured; this is evidence for manual reconstruction, not deterministic replay.")) limits.add(limit);
        meta.add("limitations", limits);
        bundle.add("metadata", meta); bundle.add("actors", identities); bundle.add("frames", frames); bundle.add("events", events);
        bundle.add("findings", TraceAnalysis.findings(selectedId.toString(), frames));
        return bundle;
    }

    public static void export(CommandSourceStack source, UUID selectedId) {
        if (exportJob != null) { source.sendFailure(Component.literal("An export is already in progress: " + exportJob.progress())); return; }
        JsonObject bundle = freeze(selectedId);
        if (bundle == null) { source.sendFailure(Component.literal("No retained soldier history for " + selectedId + ". Start recording before testing.")); return; }
        JsonObject last = null;
        for (JsonElement f : bundle.getAsJsonArray("frames")) if (selectedId.toString().equals(f.getAsJsonObject().get("soldierId").getAsString())) last = f.getAsJsonObject();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(bundle.getAsJsonObject("metadata").get("dimension").getAsString()));
        ServerLevel level = source.getServer().getLevel(dimension);
        if (level != null) {
            JsonObject environment = new JsonObject();
            environment.addProperty("difficulty", level.getDifficulty().getKey());
            environment.addProperty("gameRulesSnbt", level.getGameRules().createTag().toString());
            environment.addProperty("dayTime", level.getDayTime());
            environment.addProperty("raining", level.isRaining());
            environment.addProperty("thundering", level.isThundering());
            bundle.getAsJsonObject("metadata").add("environmentAtExport", environment);
        }
        exportJob = new TraceExportJob(source, bundle, TraceJson.vector(last.getAsJsonArray("position")), dimension);
        source.sendSuccess(() -> Component.literal("History frozen for " + selectedId + "; capturing loaded terrain within 64 blocks. "
            + (recording ? "Background recording continues." : "Recording remains stopped.")), false);
    }

    @SubscribeEvent
    public static void shutdown(ServerStoppingEvent event) {
        recording = false;
        if (exportJob != null) exportJob.cancel();
        exportJob = null; TraceExportJob.shutdown();
        actors.clear(); observations.clear(); gates.clear();
        buffer = new TraceBuffer(60_000, BUDGET); loadedSoldiers = 0; frozenTime = 0;
    }

    private static final class Observation {
        String signature = "", dimension = "";
        Vec3 position;
        Path path;
        int pathId, pathHash;
        long lastSampleMs, pathRecordedMs, lastTick;
        boolean loaded;
    }
    private static final class Gate {
        final String signature;
        final JsonObject first;
        long lastFlush, lastTick;
        int repeats;
        Gate(String signature, JsonObject first, long time) { this.signature = signature; this.first = first; lastFlush = time; lastTick = first.get("tick").getAsLong(); }
    }
}

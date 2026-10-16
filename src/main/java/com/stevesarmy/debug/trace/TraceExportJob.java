package com.stevesarmy.debug.trace;

import com.stevesarmy.StevesArmyMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Incrementally snapshots loaded terrain and writes a flight-recorder bundle off-thread. */
public final class TraceExportJob {
    private static final int RADIUS = 64;
    private static final long MAX_RAW_INDEX_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_SECTIONS_PER_TICK = 2;
    private static final long MAX_CAPTURE_NANOS = 2_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService writer;
    private static long lifecycle;

    private final CommandSourceStack source;
    private final JsonObject bundle;
    private final Vec3 center;
    private final ResourceKey<Level> dimension;
    private final List<ChunkPlan> chunks = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIds = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final List<Integer> paletteColors = new ArrayList<>();
    private final List<JsonObject> sections = new ArrayList<>();
    private final List<int[]> sectionIndices = new ArrayList<>();
    private final List<int[]> unloadedChunks = new ArrayList<>();
    private final long lifecycleToken;
    private final String captureStarted = Instant.now().toString();

    private int chunkCursor;
    private int sectionCursor;
    private long rawIndexBytes;
    private boolean sizeLimited;
    private boolean captureFinished;
    private boolean writerSubmitted;
    private int minY;
    private int maxY;
    private volatile boolean completed;
    private volatile String progress = "Preparing terrain capture";
    private volatile String failure;

    public TraceExportJob(CommandSourceStack source, JsonObject frozenBundle, Vec3 center,
                          ResourceKey<Level> dimension) {
        this.source = source;
        this.bundle = frozenBundle;
        this.center = center;
        this.dimension = dimension;
        synchronized (EXECUTOR_LOCK) {
            if (writer == null || writer.isShutdown()) {
                writer = Executors.newSingleThreadExecutor(new WriterThreadFactory());
            }
            lifecycleToken = lifecycle;
        }

        int minChunkX = Math.floorDiv(BlockPos.containing(center.x - RADIUS, 0, 0).getX(), 16);
        int maxChunkX = Math.floorDiv(BlockPos.containing(center.x + RADIUS, 0, 0).getX(), 16);
        int minChunkZ = Math.floorDiv(BlockPos.containing(0, 0, center.z - RADIUS).getZ(), 16);
        int maxChunkZ = Math.floorDiv(BlockPos.containing(0, 0, center.z + RADIUS).getZ(), 16);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) chunks.add(new ChunkPlan(x, z));
        }
    }

    /** @return true only after the asynchronous writer has finished. */
    public boolean tick(MinecraftServer server) {
        if (completed) return true;
        if (failure != null) return true;
        if (!writerSubmitted && !captureFinished) capture(server);
        if (captureFinished && !writerSubmitted) submitWriter(server);
        return completed || failure != null;
    }

    public void cancel() {
        if (completed) return;
        failure = "Export cancelled";
        progress = failure;
    }

    public String progress() {
        if (failure != null) return failure;
        return progress;
    }

    /** Called from server stopping; invalidates callbacks belonging to the old server. */
    public static void shutdown() {
        ExecutorService current;
        synchronized (EXECUTOR_LOCK) {
            lifecycle++;
            current = writer;
            writer = null;
        }
        if (current != null) {
            current.shutdownNow();
            try {
                current.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void capture(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            fail(server, "The recorded dimension is not available");
            return;
        }
        minY = level.getMinBuildHeight();
        maxY = level.getMaxBuildHeight();
        long tickStart = System.nanoTime();
        int copied = 0;
        while (chunkCursor < chunks.size() && copied < MAX_SECTIONS_PER_TICK
            && (copied == 0 || System.nanoTime() - tickStart < MAX_CAPTURE_NANOS)) {
            ChunkPlan plan = chunks.get(chunkCursor);
            if (plan.chunk == null) {
                plan.chunk = level.getChunkSource().getChunkNow(plan.x, plan.z);
                if (plan.chunk == null) {
                    unloadedChunks.add(new int[]{plan.x, plan.z});
                    chunkCursor++;
                    sectionCursor = 0;
                    continue;
                }
            }

            int sectionCount = plan.chunk.getSections().length;
            if (sectionCursor >= sectionCount) {
                plan.chunk = null;
                chunkCursor++;
                sectionCursor = 0;
                continue;
            }
            LevelChunkSection section = plan.chunk.getSections()[sectionCursor++];
            if (section == null || section.hasOnlyAir()) continue;
            if (rawIndexBytes + 4096L * Integer.BYTES > MAX_RAW_INDEX_BYTES) {
                sizeLimited = true;
                captureFinished = true;
                progress = "Terrain capture reached the 32 MiB raw-index limit";
                break;
            }
            int sectionY = level.getMinSection() + sectionCursor - 1;
            sectionIndices.add(copySection(section, level, new BlockPos(plan.x << 4, sectionY << 4, plan.z << 4)));
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("x", plan.x);
            descriptor.addProperty("y", sectionY);
            descriptor.addProperty("z", plan.z);
            sections.add(descriptor);
            rawIndexBytes += 4096L * Integer.BYTES;
            copied++;
        }

        if (chunkCursor >= chunks.size()) captureFinished = true;
        progress = captureFinished
            ? "Terrain copied; preparing export"
            : "Terrain sections copied: " + sectionIndices.size() + " / " + (chunks.size() * level.getSectionsCount());
    }

    private int[] copySection(LevelChunkSection section, ServerLevel level, BlockPos sectionBase) {
        int[] result = new int[4096];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Integer id = paletteIds.get(state);
                    if (id == null) {
                        id = palette.size();
                        paletteIds.put(state, id);
                        // Canonical registry name + properties; no Block{...} wrapper in the artifact.
                        palette.add(state.toString().replace("Block{", "").replace("}", ""));
                        // Vanilla map color for the viewer's filled-map terrain display.
                        paletteColors.add(com.stevesarmy.util.VanillaMapColors.surfaceRgb(
                            state, level, sectionBase.offset(x, y, z)));
                    }
                    result[i++] = id;
                }
            }
        }
        return result;
    }

    private void submitWriter(MinecraftServer server) {
        writerSubmitted = true;
        JsonObject terrain = new JsonObject();
        JsonArray centerJson = new JsonArray();
        centerJson.add(center.x);
        centerJson.add(center.y);
        centerJson.add(center.z);
        terrain.add("center", centerJson);
        terrain.addProperty("radius", RADIUS);
        terrain.addProperty("dimension", dimension.location().toString());
        JsonArray bounds = new JsonArray();
        bounds.add(center.x - RADIUS);
        bounds.add(center.x + RADIUS);
        bounds.add(center.z - RADIUS);
        bounds.add(center.z + RADIUS);
        terrain.add("boundsXZ", bounds);
        terrain.addProperty("minY", minY);
        terrain.addProperty("maxY", maxY);
        terrain.addProperty("captureStarted", captureStarted);
        terrain.addProperty("captureFinished", Instant.now().toString());
        terrain.add("palette", GSON.toJsonTree(palette));
        terrain.add("paletteColors", GSON.toJsonTree(paletteColors));
        JsonArray unloaded = new JsonArray();
        for (int[] pos : unloadedChunks) {
            JsonArray pair = new JsonArray();
            pair.add(pos[0]);
            pair.add(pos[1]);
            unloaded.add(pair);
        }
        terrain.add("unloadedChunks", unloaded);
        terrain.addProperty("unloadedChunksPresent", !unloadedChunks.isEmpty());
        terrain.addProperty("sizeLimited", sizeLimited);
        terrain.addProperty("rawIndexBytes", rawIndexBytes);
        final ExecutorService targetWriter;
        synchronized (EXECUTOR_LOCK) {
            if (lifecycleToken != lifecycle || writer == null || writer.isShutdown()) {
                fail(server, "Export writer is unavailable");
                return;
            }
            targetWriter = writer;
        }
        progress = "Writing trace files";
        Path serverDirectory = server.getServerDirectory().toPath();
        for (ChunkPlan plan : chunks) plan.chunk = null;
        try {
            targetWriter.execute(() -> writeFiles(server, serverDirectory, terrain));
        } catch (RuntimeException rejected) {
            fail(server, "Export writer rejected the trace");
        }
    }

    private void writeFiles(MinecraftServer server, Path serverDirectory, JsonObject terrain) {
        try {
            JsonArray sectionJson = new JsonArray();
            for (int index = 0; index < sections.size(); index++) {
                JsonObject entry = sections.get(index);
                JsonArray indices = new JsonArray();
                for (int paletteId : sectionIndices.get(index)) indices.add(paletteId);
                entry.add("indices", indices);
                sectionJson.add(entry);
            }
            terrain.add("sections", sectionJson);
            bundle.add("terrain", terrain);
            Path root = serverDirectory.resolve("debug").resolve("soldier_traces");
            Files.createDirectories(root);
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)
                .format(Instant.now());
            Path candidate;
            do {
                candidate = root.resolve("trace_" + timestamp + "_" + UUID.randomUUID().toString().substring(0, 8));
            } while (Files.exists(candidate));
            final Path directory = candidate;
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("trace.json"), GSON.toJson(bundle), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("viewer.html"), TracePresentation.html(bundle), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("summary.txt"), TracePresentation.summary(bundle), StandardCharsets.UTF_8);
            server.execute(() -> {
                if (!isCurrentLifecycle()) return;
                progress = "Export complete: " + directory.toAbsolutePath();
                completed = true;
                StevesArmyMod.LOGGER.info("Soldier trace exported to {}", directory.toAbsolutePath());
                source.sendSuccess(() -> Component.literal("Soldier trace exported to " + directory.toAbsolutePath()), false);
            });
        } catch (Throwable error) {
            server.execute(() -> {
                if (!isCurrentLifecycle()) return;
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                failure = "Export failed: " + detail;
                completed = true;
                StevesArmyMod.LOGGER.error("Soldier trace export failed", error);
                source.sendFailure(Component.literal(failure));
            });
        }
    }

    private boolean isCurrentLifecycle() {
        synchronized (EXECUTOR_LOCK) {
            return lifecycleToken == lifecycle;
        }
    }

    private void fail(MinecraftServer server, String message) {
        failure = message;
        progress = message;
        server.execute(() -> {
            if (isCurrentLifecycle()) source.sendFailure(Component.literal(message));
        });
    }

    private static final class ChunkPlan {
        private final int x;
        private final int z;
        private LevelChunk chunk;

        private ChunkPlan(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class WriterThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "steves-army-trace-writer");
            thread.setDaemon(true);
            return thread;
        }
    }
}

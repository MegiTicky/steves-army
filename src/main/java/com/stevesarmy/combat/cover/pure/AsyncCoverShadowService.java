package com.stevesarmy.combat.cover.pure;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverGoalController;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One-worker, read-only shadow pipeline for routine rifleman cover evaluation.
 * Requests contain only immutable snapshots; all live-world work remains on
 * the server thread before submission or while results are drained.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class AsyncCoverShadowService {
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final int MAX_RESULTS_PER_TICK = 64;
    private static final long MAX_RESULT_AGE_TICKS = 40L;

    private static final Object LIFECYCLE_LOCK = new Object();
    private static final ConcurrentMap<UUID, Request> LATEST_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Request> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Completed> LATEST_RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Boolean> SCHEDULED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ResourceLocation, Long> DIMENSION_GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LATEST_SEQUENCES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Request> PILOT_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Request> PILOT_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Completed> PILOT_RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Boolean> PILOT_SCHEDULED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> PILOT_SEQUENCES = new ConcurrentHashMap<>();

    private static volatile ThreadPoolExecutor executor;
    private static volatile UUID serverEpoch;
    private static long requestSequence;

    private AsyncCoverShadowService() {
    }

    /**
     * Captures no server or level reference. The level is used only to stamp
     * the request with its immutable dimension identity.
     */
    public static boolean submit(ServerLevel level, UUID soldierId, CoverSnapshotCapture.Capture capture,
                            List<BlockPos> legacyPositions) {
        if (!StevesArmyConfig.isPhase5AsyncShadowEnabled() || level == null || soldierId == null
            || capture == null || legacyPositions == null) {
            return false;
        }

        ThreadPoolExecutor currentExecutor = executor;
        UUID currentEpoch = serverEpoch;
        if (currentExecutor == null || currentExecutor.isShutdown() || currentEpoch == null) {
            PerformanceMetrics.recordPhase5QueueSkip();
            return false;
        }

        Request request;
        synchronized (LIFECYCLE_LOCK) {
            if (executor != currentExecutor || serverEpoch != currentEpoch
                || currentExecutor.isShutdown()) {
                PerformanceMetrics.recordPhase5QueueSkip();
                return false;
            }
            request = new Request(
                currentEpoch,
                level.dimension().location(),
                dimensionGeneration(level.dimension().location()),
                soldierId,
                ++requestSequence,
                level.getGameTime(),
                System.nanoTime(),
                capture.input(),
                capture.terrain(),
                legacyPositions.stream().map(BlockPos::immutable).toList());

            Request previous = LATEST_REQUESTS.put(soldierId, request);
            LATEST_SEQUENCES.put(soldierId, request.sequence());
            if (previous != null) {
                PerformanceMetrics.recordPhase5RequestCoalesced();
            } else if (LATEST_REQUESTS.size() > MAX_PENDING_REQUESTS) {
                LATEST_REQUESTS.remove(soldierId, request);
                LATEST_SEQUENCES.remove(soldierId, request.sequence());
                PerformanceMetrics.recordPhase5QueueSkip();
                return false;
            }
            PerformanceMetrics.recordPhase5RequestQueued();
        }

        schedule(request, currentExecutor);
        return true;
    }

    public static boolean submitPilot(ServerLevel level, UUID soldierId, CoverSnapshotCapture.Capture capture) {
        if (!StevesArmyConfig.isPhase6AsyncCoverPilotEnabled() || level == null || soldierId == null || capture == null) {
            return false;
        }
        ThreadPoolExecutor currentExecutor = executor;
        UUID currentEpoch = serverEpoch;
        if (currentExecutor == null || currentExecutor.isShutdown() || currentEpoch == null) {
            PerformanceMetrics.recordPhase6QueueSkip();
            return false;
        }
        Request request;
        synchronized (LIFECYCLE_LOCK) {
            if (executor != currentExecutor || serverEpoch != currentEpoch || currentExecutor.isShutdown()) {
                PerformanceMetrics.recordPhase6QueueSkip();
                return false;
            }
            request = new Request(currentEpoch, level.dimension().location(),
                dimensionGeneration(level.dimension().location()), soldierId, ++requestSequence,
                level.getGameTime(), System.nanoTime(), capture.input(), capture.terrain(), List.of());
            Request previous = PILOT_REQUESTS.put(soldierId, request);
            PILOT_SEQUENCES.put(soldierId, request.sequence());
            if (previous != null) {
                PerformanceMetrics.recordPhase6RequestCoalesced();
            } else if (PILOT_REQUESTS.size() > MAX_PENDING_REQUESTS) {
                PILOT_REQUESTS.remove(soldierId, request);
                PILOT_SEQUENCES.remove(soldierId, request.sequence());
                PerformanceMetrics.recordPhase6QueueSkip();
                return false;
            }
            PerformanceMetrics.recordPhase6RequestQueued();
        }
        schedulePilot(request, currentExecutor);
        return true;
    }

    private static void schedule(Request request, ThreadPoolExecutor currentExecutor) {
        UUID soldierId = request.soldierId();
        synchronized (LIFECYCLE_LOCK) {
            if (executor != currentExecutor || !request.serverEpoch().equals(serverEpoch)
                || currentExecutor.isShutdown() || SCHEDULED.putIfAbsent(soldierId, Boolean.TRUE) != null) {
                return;
            }

            try {
                currentExecutor.execute(() -> runWorker(soldierId, currentExecutor));
            } catch (RejectedExecutionException ignored) {
                SCHEDULED.remove(soldierId);
                Request rejected = LATEST_REQUESTS.remove(soldierId);
                if (rejected != null) {
                    LATEST_SEQUENCES.remove(soldierId, rejected.sequence());
                }
                PerformanceMetrics.recordPhase5QueueSkip();
            }
        }
    }

    private static void schedulePilot(Request request, ThreadPoolExecutor currentExecutor) {
        UUID soldierId = request.soldierId();
        synchronized (LIFECYCLE_LOCK) {
            if (executor != currentExecutor || !request.serverEpoch().equals(serverEpoch)
                || currentExecutor.isShutdown() || PILOT_SCHEDULED.putIfAbsent(soldierId, Boolean.TRUE) != null) return;
            try {
                currentExecutor.execute(() -> runPilotWorker(soldierId, currentExecutor));
            } catch (RejectedExecutionException ignored) {
                PILOT_SCHEDULED.remove(soldierId);
                Request rejected = PILOT_REQUESTS.remove(soldierId);
                if (rejected != null) PILOT_SEQUENCES.remove(soldierId, rejected.sequence());
                PerformanceMetrics.recordPhase6QueueSkip();
            }
        }
    }

    private static void runWorker(UUID soldierId, ThreadPoolExecutor currentExecutor) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Request request = LATEST_REQUESTS.remove(soldierId);
                if (request == null) {
                    return;
                }

                if (!StevesArmyConfig.isPhase5AsyncShadowEnabled()) {
                    LATEST_SEQUENCES.remove(soldierId, request.sequence());
                    PerformanceMetrics.recordPhase5Cancelled();
                    continue;
                }

                IN_FLIGHT.put(soldierId, request);
                long workerStarted = System.nanoTime();
                PerformanceMetrics.recordPhase5QueueWait(workerStarted - request.submittedNanos());
                PerformanceMetrics.recordPhase5WorkerRequest();
                try {
                    CoverSearchResult result = PureCoverEvaluator.evaluate(request.input(), request.terrain());
                    PerformanceMetrics.recordPhase5WorkerCompleted(System.nanoTime() - workerStarted);
                    synchronized (LIFECYCLE_LOCK) {
                        if (currentExecutor != executor || currentExecutor.isShutdown()
                            || !request.serverEpoch().equals(serverEpoch)
                            || dimensionGeneration(request.dimension()) != request.dimensionGeneration()) {
                            PerformanceMetrics.recordPhase5Cancelled();
                            continue;
                        }
                        Completed completed = new Completed(request, result);
                        Completed replaced = LATEST_RESULTS.put(soldierId, completed);
                        if (replaced != null) {
                            PerformanceMetrics.recordPhase5ResultCoalesced();
                        }
                    }
                } catch (RuntimeException exception) {
                    PerformanceMetrics.recordPhase5Failure();
                    StevesArmyMod.LOGGER.warn("Async cover shadow evaluation failed for soldier {}", soldierId,
                        exception);
                } finally {
                    IN_FLIGHT.remove(soldierId, request);
                }
            }
        } finally {
            synchronized (LIFECYCLE_LOCK) {
                if (executor == currentExecutor) {
                    SCHEDULED.remove(soldierId);
                }
                if (executor == currentExecutor && serverEpoch != null && !currentExecutor.isShutdown()) {
                    Request pending = LATEST_REQUESTS.get(soldierId);
                    if (pending != null && SCHEDULED.putIfAbsent(soldierId, Boolean.TRUE) == null) {
                        try {
                            currentExecutor.execute(() -> runWorker(soldierId, currentExecutor));
                        } catch (RejectedExecutionException ignored) {
                            SCHEDULED.remove(soldierId);
                            if (LATEST_REQUESTS.remove(soldierId, pending)) {
                                LATEST_SEQUENCES.remove(soldierId, pending.sequence());
                            }
                            PerformanceMetrics.recordPhase5QueueSkip();
                        }
                    }
                }
            }
        }
    }

    private static void runPilotWorker(UUID soldierId, ThreadPoolExecutor currentExecutor) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Request request = PILOT_REQUESTS.remove(soldierId);
                if (request == null) return;
                if (!StevesArmyConfig.isPhase6AsyncCoverPilotEnabled()) {
                    PILOT_SEQUENCES.remove(soldierId, request.sequence());
                    PerformanceMetrics.recordPhase6Cancelled();
                    continue;
                }
                PILOT_IN_FLIGHT.put(soldierId, request);
                long started = System.nanoTime();
                PerformanceMetrics.recordPhase6QueueWait(started - request.submittedNanos());
                PerformanceMetrics.recordPhase6WorkerRequest();
                try {
                    CoverSearchResult result = PureCoverEvaluator.evaluate(request.input(), request.terrain());
                    PerformanceMetrics.recordPhase6WorkerCompleted(System.nanoTime() - started);
                    synchronized (LIFECYCLE_LOCK) {
                        if (currentExecutor != executor || currentExecutor.isShutdown()
                            || !request.serverEpoch().equals(serverEpoch)
                            || dimensionGeneration(request.dimension()) != request.dimensionGeneration()) {
                            PerformanceMetrics.recordPhase6Cancelled();
                            continue;
                        }
                        Completed replaced = PILOT_RESULTS.put(soldierId, new Completed(request, result));
                        if (replaced != null) PerformanceMetrics.recordPhase6RequestCoalesced();
                    }
                } catch (RuntimeException exception) {
                    PerformanceMetrics.recordPhase6Failure();
                    StevesArmyMod.LOGGER.warn("Async cover pilot evaluation failed for soldier {}", soldierId, exception);
                } finally {
                    PILOT_IN_FLIGHT.remove(soldierId, request);
                }
            }
        } finally {
            synchronized (LIFECYCLE_LOCK) {
                if (executor == currentExecutor) PILOT_SCHEDULED.remove(soldierId);
                if (executor == currentExecutor && serverEpoch != null && !currentExecutor.isShutdown()) {
                    Request pending = PILOT_REQUESTS.get(soldierId);
                    if (pending != null && PILOT_SCHEDULED.putIfAbsent(soldierId, Boolean.TRUE) == null) {
                        try {
                            currentExecutor.execute(() -> runPilotWorker(soldierId, currentExecutor));
                        } catch (RejectedExecutionException ignored) {
                            PILOT_SCHEDULED.remove(soldierId);
                            if (PILOT_REQUESTS.remove(soldierId, pending)) PILOT_SEQUENCES.remove(soldierId, pending.sequence());
                            PerformanceMetrics.recordPhase6QueueSkip();
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        synchronized (LIFECYCLE_LOCK) {
            shutdownLocked();
            LATEST_REQUESTS.clear();
            IN_FLIGHT.clear();
            LATEST_RESULTS.clear();
            SCHEDULED.clear();
            LATEST_SEQUENCES.clear();
            PILOT_REQUESTS.clear();
            PILOT_IN_FLIGHT.clear();
            PILOT_RESULTS.clear();
            PILOT_SCHEDULED.clear();
            PILOT_SEQUENCES.clear();
            DIMENSION_GENERATIONS.clear();
            requestSequence = 0L;
            serverEpoch = UUID.randomUUID();
            executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_REQUESTS),
                new ShadowThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || LATEST_RESULTS.isEmpty()) {
            if (event.phase != TickEvent.Phase.END || PILOT_RESULTS.isEmpty()) return;
        }

        MinecraftServer server = event.getServer();
        int processed = 0;
        for (Map.Entry<UUID, Completed> entry : new ArrayList<>(LATEST_RESULTS.entrySet())) {
            if (processed++ >= MAX_RESULTS_PER_TICK) {
                break;
            }
            Completed completed = entry.getValue();
            if (!LATEST_RESULTS.remove(entry.getKey(), completed)) {
                continue;
            }
            applyShadowResult(server, completed);
        }
        processed = 0;
        for (Map.Entry<UUID, Completed> entry : new ArrayList<>(PILOT_RESULTS.entrySet())) {
            if (processed++ >= MAX_RESULTS_PER_TICK) break;
            Completed completed = entry.getValue();
            if (PILOT_RESULTS.remove(entry.getKey(), completed)) applyPilotResult(server, completed);
        }
    }

    private static void applyShadowResult(MinecraftServer server, Completed completed) {
        long applyStarted = System.nanoTime();
        Request request = completed.request();
        try {
            UUID currentEpoch = serverEpoch;
            if (!StevesArmyConfig.isPhase5AsyncShadowEnabled()
                || !request.serverEpoch().equals(currentEpoch)
                || dimensionGeneration(request.dimension()) != request.dimensionGeneration()
                || !isDimensionLoaded(server, request.dimension())) {
                PerformanceMetrics.recordPhase5StaleResult();
                return;
            }

            Long latestSequence = LATEST_SEQUENCES.get(request.soldierId());
            if (latestSequence == null || latestSequence.longValue() != request.sequence()) {
                PerformanceMetrics.recordPhase5StaleResult();
                return;
            }

            ServerLevel level = findLevel(server, request.dimension());
            Entity soldier = level != null ? level.getEntity(request.soldierId()) : null;
            if (soldier == null || !soldier.isAlive()) {
                PerformanceMetrics.recordPhase5ValidationReject();
                return;
            }

            if (!soldier.blockPosition().equals(request.input().soldierPosition())) {
                PerformanceMetrics.recordPhase5StaleResult();
                return;
            }

            long resultAge = Math.max(0L, level.getGameTime() - request.sourceTick());
            PerformanceMetrics.recordPhase5ResultAge(resultAge);
            if (resultAge > MAX_RESULT_AGE_TICKS) {
                PerformanceMetrics.recordPhase5StaleResult();
                return;
            }

            List<BlockPos> workerPositions = completed.result().positions();
            List<BlockPos> legacyPositions = request.legacyPositions();
            boolean topMatches = !legacyPositions.isEmpty() && !workerPositions.isEmpty()
                && legacyPositions.get(0).equals(workerPositions.get(0));
            PerformanceMetrics.recordPhase5Top1Comparison(topMatches);

            int comparableCount = Math.min(legacyPositions.size(), workerPositions.size());
            boolean orderingMatches = comparableCount > 0
                && legacyPositions.subList(0, comparableCount)
                    .equals(workerPositions.subList(0, comparableCount));
            PerformanceMetrics.recordPhase5OrderingComparison(orderingMatches);
        } catch (RuntimeException exception) {
            PerformanceMetrics.recordPhase5ValidationReject();
            StevesArmyMod.LOGGER.warn("Async cover shadow result validation failed for soldier {}",
                request.soldierId(), exception);
        } finally {
            LATEST_SEQUENCES.remove(request.soldierId(), request.sequence());
            PerformanceMetrics.recordPhase5Apply(System.nanoTime() - applyStarted);
        }
    }

    private static void applyPilotResult(MinecraftServer server, Completed completed) {
        long started = System.nanoTime();
        Request request = completed.request();
        try {
            if (!StevesArmyConfig.isPhase6AsyncCoverPilotEnabled()
                || !request.serverEpoch().equals(serverEpoch)
                || dimensionGeneration(request.dimension()) != request.dimensionGeneration()
                || !isDimensionLoaded(server, request.dimension())) {
                PerformanceMetrics.recordPhase6StaleResult();
                return;
            }
            Long latest = PILOT_SEQUENCES.get(request.soldierId());
            if (latest == null || latest.longValue() != request.sequence()) {
                PerformanceMetrics.recordPhase6StaleResult();
                return;
            }
            ServerLevel level = findLevel(server, request.dimension());
            Entity entity = level == null ? null : level.getEntity(request.soldierId());
            if (!(entity instanceof SoldierEntity soldier) || !entity.isAlive()) {
                PerformanceMetrics.recordPhase6ValidationReject();
                return;
            }
            CoverGoalController goal = soldier.getCoverTacticalGoal();
            if (goal == null) {
                PerformanceMetrics.recordPhase6ValidationReject();
                return;
            }
            if (!entity.blockPosition().equals(request.input().soldierPosition())) {
                PerformanceMetrics.recordPhase6StaleResult();
                goal.rejectAsyncCoverPilot();
                return;
            }
            long age = Math.max(0L, level.getGameTime() - request.sourceTick());
            PerformanceMetrics.recordPhase6ResultAge(age);
            if (age > MAX_RESULT_AGE_TICKS) {
                PerformanceMetrics.recordPhase6StaleResult();
                goal.rejectAsyncCoverPilot();
                return;
            }
            goal.applyAsyncCoverPilotResult(completed.result(), request.input().soldierPosition(), request.sourceTick());
        } catch (RuntimeException exception) {
            PerformanceMetrics.recordPhase6ValidationReject();
            StevesArmyMod.LOGGER.warn("Async cover pilot result validation failed for soldier {}", request.soldierId(), exception);
        } finally {
            PILOT_SEQUENCES.remove(request.soldierId(), request.sequence());
            PerformanceMetrics.recordPhase6Apply(System.nanoTime() - started);
        }
    }

    private static long dimensionGeneration(ResourceLocation dimension) {
        return DIMENSION_GENERATIONS.getOrDefault(dimension, 0L);
    }

    private static boolean isDimensionLoaded(MinecraftServer server, ResourceLocation dimension) {
        return findLevel(server, dimension) != null;
    }

    private static ServerLevel findLevel(MinecraftServer server, ResourceLocation dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            DIMENSION_GENERATIONS.merge(level.dimension().location(), 1L, Long::sum);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceLocation dimension = level.dimension().location();
        DIMENSION_GENERATIONS.merge(dimension, 1L, Long::sum);
        LATEST_REQUESTS.entrySet().removeIf(entry -> {
            if (entry.getValue().dimension().equals(dimension)) {
                PerformanceMetrics.recordPhase5Cancelled();
                LATEST_SEQUENCES.remove(entry.getKey(), entry.getValue().sequence());
                return true;
            }
            return false;
        });
        PILOT_REQUESTS.entrySet().removeIf(entry -> {
            if (entry.getValue().dimension().equals(dimension)) {
                PerformanceMetrics.recordPhase6Cancelled();
                PILOT_SEQUENCES.remove(entry.getKey(), entry.getValue().sequence());
                return true;
            }
            return false;
        });
        PILOT_RESULTS.entrySet().removeIf(entry -> {
            if (entry.getValue().request().dimension().equals(dimension)) {
                PerformanceMetrics.recordPhase6StaleResult();
                return true;
            }
            return false;
        });
        LATEST_RESULTS.entrySet().removeIf(entry -> {
            if (entry.getValue().request().dimension().equals(dimension)) {
                PerformanceMetrics.recordPhase5StaleResult();
                return true;
            }
            return false;
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (LIFECYCLE_LOCK) {
            int cancelled = LATEST_REQUESTS.size() + IN_FLIGHT.size();
            for (int i = 0; i < cancelled; i++) {
                PerformanceMetrics.recordPhase5Cancelled();
            }
            LATEST_REQUESTS.clear();
            IN_FLIGHT.clear();
            LATEST_RESULTS.clear();
            SCHEDULED.clear();
            LATEST_SEQUENCES.clear();
            int pilotCancelled = PILOT_REQUESTS.size() + PILOT_IN_FLIGHT.size();
            for (int i = 0; i < pilotCancelled; i++) PerformanceMetrics.recordPhase6Cancelled();
            PILOT_REQUESTS.clear();
            PILOT_IN_FLIGHT.clear();
            PILOT_RESULTS.clear();
            PILOT_SCHEDULED.clear();
            PILOT_SEQUENCES.clear();
            DIMENSION_GENERATIONS.clear();
            serverEpoch = null;
            shutdownLocked();
            executor = null;
        }
    }

    private static void shutdownLocked() {
        ThreadPoolExecutor currentExecutor = executor;
        if (currentExecutor == null) {
            return;
        }
        currentExecutor.shutdownNow();
        try {
            currentExecutor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Request(UUID serverEpoch, ResourceLocation dimension, long dimensionGeneration,
                           UUID soldierId,
                           long sequence, long sourceTick, long submittedNanos,
                           CoverSearchInput input, CoverTerrainSnapshot terrain,
                           List<BlockPos> legacyPositions) {
    }

    private record Completed(Request request, CoverSearchResult result) {
    }

    private static final class ShadowThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "steves-army-cover-shadow");
            thread.setDaemon(true);
            return thread;
        }
    }
}

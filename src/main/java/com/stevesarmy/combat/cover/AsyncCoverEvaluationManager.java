package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.ai.SupportPositionFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the opt-in worker path for machine-gunner firing-lane evaluation.
 * Workers only see immutable FiringPositionFinder snapshots; all Minecraft
 * state validation and navigation remains on the server thread.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AsyncCoverEvaluationManager {
    private static final Map<MinecraftServer, AsyncCoverEvaluationManager> INSTANCES =
        new ConcurrentHashMap<>();
    private static final int MAX_RESULT_AGE_TICKS = 10;
    private static final int MAX_QUEUE_SIZE = 32;

    private final MinecraftServer server;
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Pending> inFlight = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private AsyncCoverEvaluationManager(MinecraftServer server) {
        this.server = server;
        int workers = Math.max(1, Math.min(4,
            Runtime.getRuntime().availableProcessors() - 1));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "steves-army-cover-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(workers, workers, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUE_SIZE), factory,
            new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    public interface ResultConsumer {
        void accept(FiringPositionFinder.AsyncEvaluationSnapshot snapshot,
                    FiringPositionFinder.EvaluationReport report);
    }

    public static boolean request(MachineGunnerEntity mg, BlockPos suppressionCenter,
                                  BlockPos supportAnchor, long tacticalRevision,
                                  long suppressionSequence, long sectorGeneration,
                                  ResultConsumer consumer) {
        if (mg == null || mg.level().getServer() == null) {
            return false;
        }
        MinecraftServer server = mg.level().getServer();
        return get(server).requestInternal(mg, suppressionCenter, supportAnchor,
            tacticalRevision, suppressionSequence, sectorGeneration, consumer);
    }

    private static AsyncCoverEvaluationManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, AsyncCoverEvaluationManager::new);
    }

    private boolean requestInternal(MachineGunnerEntity mg, BlockPos suppressionCenter,
                                    BlockPos supportAnchor, long tacticalRevision,
                                    long suppressionSequence, long sectorGeneration,
                                    ResultConsumer consumer) {
        if (closed || suppressionCenter == null || supportAnchor == null) return false;
        UUID soldierId = mg.getUUID();
        if (inFlight.containsKey(soldierId)) {
            PerformanceMetrics.recordAsyncCoverCoalescedRequest();
            return true;
        }

        FiringPositionFinder.AsyncEvaluationSnapshot snapshot;
        try {
            snapshot = FiringPositionFinder.captureAsyncSnapshot(mg, suppressionCenter,
                supportAnchor, tacticalRevision, suppressionSequence, sectorGeneration);
        } catch (RuntimeException exception) {
            PerformanceMetrics.recordAsyncCoverWorkerFailure();
            StevesArmyMod.LOGGER.warn("Failed to capture async cover snapshot for soldier {}",
                mg.getId(), exception);
            return false;
        }

        Pending pending = new Pending(snapshot, consumer);
        if (inFlight.putIfAbsent(soldierId, pending) != null) {
            PerformanceMetrics.recordAsyncCoverCoalescedRequest();
            return true;
        }

        if (executor.getQueue().remainingCapacity() <= 0) {
            inFlight.remove(soldierId, pending);
            PerformanceMetrics.recordAsyncCoverQueueSkip();
            return false;
        }

        PerformanceMetrics.recordAsyncCoverWorkerRequest();
        try {
            pending.future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    FiringPositionFinder.AsyncEvaluationResult result =
                        FiringPositionFinder.evaluateAsyncSnapshot(snapshot);
                    PerformanceMetrics.recordAsyncCoverWorkerCompleted();
                    return result;
                } finally {
                    PerformanceMetrics.recordAsyncCoverWorkerTime(
                        System.nanoTime() - started);
                }
            }, executor);
            pending.future.whenComplete((result, error) -> complete(pending, result, error));
        } catch (RejectedExecutionException exception) {
            inFlight.remove(soldierId, pending);
            PerformanceMetrics.recordAsyncCoverQueueSkip();
            return false;
        }
        return true;
    }

    private void complete(Pending pending,
                          @Nullable FiringPositionFinder.AsyncEvaluationResult result,
                          @Nullable Throwable error) {
        inFlight.remove(pending.snapshot().soldierId(), pending);
        if (closed) return;
        if (error != null || result == null) {
            PerformanceMetrics.recordAsyncCoverWorkerFailure();
            return;
        }
        try {
            server.execute(() -> applyOnServerThread(pending, result));
        } catch (RuntimeException exception) {
            PerformanceMetrics.recordAsyncCoverWorkerFailure();
        }
    }

    private void applyOnServerThread(Pending pending,
                                     FiringPositionFinder.AsyncEvaluationResult result) {
        FiringPositionFinder.AsyncEvaluationSnapshot snapshot = pending.snapshot();
        MachineGunnerEntity mg = findMachineGunner(snapshot.soldierId());
        if (mg == null || !isCurrentSnapshot(mg, snapshot)) {
            PerformanceMetrics.recordAsyncCoverStaleResult();
            return;
        }

        long started = System.nanoTime();
        FiringPositionFinder.EvaluationReport report =
            FiringPositionFinder.finalizeAsyncEvaluation(mg, snapshot, result);
        if (!result.candidates().isEmpty() && report.selected() == null) {
            PerformanceMetrics.recordAsyncCoverValidationReject();
        }
        PerformanceMetrics.recordAsyncCoverApply(System.nanoTime() - started);
        pending.consumer().accept(snapshot, report);
    }

    private boolean isCurrentSnapshot(MachineGunnerEntity mg,
                                      FiringPositionFinder.AsyncEvaluationSnapshot snapshot) {
        if (!mg.isAlive() || mg.level().getServer() != server) return false;
        long age = mg.level().getGameTime() - snapshot.sourceTick();
        if (age < 0 || age > MAX_RESULT_AGE_TICKS) return false;
        if (mg.blockPosition().distSqr(snapshot.soldierBlockPosition()) > 4.0) return false;
        if (mg.getCoverBehaviorManager().getTacticalRevision() != snapshot.tacticalRevision()) {
            return false;
        }
        if (mg.getCoverBehaviorManager().getSuppressionTracker().getSuppressionEventSequence()
            != snapshot.suppressionSequence()) {
            return false;
        }
        if (mg.getSuppressionSectorGeneration() != snapshot.sectorGeneration()) return false;
        BlockPos center = mg.getSuppressionCenter();
        if (center == null || !center.equals(snapshot.suppressionCenter())) return false;
        BlockPos anchor = SupportPositionFinder.findSupportPosition(mg);
        return anchor != null && anchor.equals(snapshot.supportAnchor());
    }

    @Nullable
    private MachineGunnerEntity findMachineGunner(UUID soldierId) {
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(soldierId) instanceof MachineGunnerEntity mg) return mg;
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AsyncCoverEvaluationManager manager = INSTANCES.remove(event.getServer());
        if (manager != null) manager.close();
    }

    private void close() {
        closed = true;
        for (Pending pending : inFlight.values()) {
            CompletableFuture<?> future = pending.future;
            if (future != null && future.cancel(false)) {
                PerformanceMetrics.recordAsyncCoverWorkerCancelled();
            }
        }
        inFlight.clear();
        executor.shutdownNow();
    }

    private static final class Pending {
        private final FiringPositionFinder.AsyncEvaluationSnapshot snapshot;
        private final ResultConsumer consumer;
        private volatile CompletableFuture<FiringPositionFinder.AsyncEvaluationResult> future;

        private Pending(FiringPositionFinder.AsyncEvaluationSnapshot snapshot,
                        ResultConsumer consumer) {
            this.snapshot = snapshot;
            this.consumer = consumer;
        }

        private FiringPositionFinder.AsyncEvaluationSnapshot snapshot() {
            return snapshot;
        }

        private ResultConsumer consumer() {
            return consumer;
        }
    }
}

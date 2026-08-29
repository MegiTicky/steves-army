package com.stevesarmy.entity.ai.pathfinding;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns the bounded worker pool used by async soldier A* searches. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class AsyncPathfindingService {
    private static final Object LOCK = new Object();
    private static volatile ThreadPoolExecutor executor;

    private AsyncPathfindingService() {}

    public static boolean submit(Runnable task) {
        ThreadPoolExecutor current = executor;
        if (current == null || current.isShutdown()) return false;
        try {
            current.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        synchronized (LOCK) {
            shutdownLocked();
            if (!StevesArmyConfig.isAsyncPathfindingEnabled()) {
                executor = null;
                return;
            }
            int threads = StevesArmyConfig.getAsyncPathfindingThreads();
            int capacity = StevesArmyConfig.getAsyncPathfindingQueueCapacity();
            executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                new PathThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
            StevesArmyMod.LOGGER.info("Async soldier pathfinding enabled with {} worker(s) and queue capacity {}",
                threads, capacity);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (LOCK) {
            shutdownLocked();
            executor = null;
        }
    }

    private static void shutdownLocked() {
        ThreadPoolExecutor current = executor;
        if (current == null) return;
        current.shutdownNow();
        try {
            current.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PathThreadFactory implements ThreadFactory {
        private int nextId;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "steves-army-pathfinding-" + nextId++);
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        }
    }
}

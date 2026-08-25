package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Bounds expensive cover searches without moving Minecraft world access off
 * the server thread. Requests are coalesced per goal and applied at tick end.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class CoverSearchScheduler {
    public static final int MAX_SEARCHES_PER_TICK = 2;
    public static final int ROUTINE_STAGGER_TICKS = 6;

    private static final Map<CoverTacticalGoal, Request> PENDING = new IdentityHashMap<>();
    private static long sequence;

    private CoverSearchScheduler() {
    }

    public static boolean request(CoverTacticalGoal goal, int priority, int dueTick) {
        Request existing = PENDING.get(goal);
        if (existing != null) {
            if (priority < existing.priority) {
                existing.priority = priority;
                existing.dueTick = Math.min(existing.dueTick, dueTick);
            }
            PerformanceMetrics.recordCoverSearchRequestCoalesced();
            return false;
        }

        PENDING.put(goal, new Request(goal, priority, dueTick, sequence++));
        PerformanceMetrics.recordCoverSearchRequestQueued();
        return true;
    }

    public static void cancel(CoverTacticalGoal goal) {
        if (PENDING.remove(goal) != null) {
            PerformanceMetrics.recordCoverSearchRequestCancelled();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }

        int currentTick = event.getServer().getTickCount();
        List<Request> ready = new ArrayList<>();
        for (Request request : PENDING.values()) {
            if (request.dueTick <= currentTick) {
                ready.add(request);
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        ready.sort(Comparator
            .comparingInt((Request request) -> request.priority)
            .thenComparingLong(request -> request.sequence));

        int executed = 0;
        for (Request request : ready) {
            if (executed >= MAX_SEARCHES_PER_TICK) {
                PerformanceMetrics.recordCoverSearchRequestDeferred();
                continue;
            }

            // Remove before execution so a callback can safely enqueue a follow-up.
            if (PENDING.remove(request.goal) != request) {
                continue;
            }
            executed++;
            PerformanceMetrics.recordCoverSearchRequestExecuted();
            request.goal.executeQueuedCoverSearch();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Iterator<CoverTacticalGoal> goals = PENDING.keySet().iterator();
        while (goals.hasNext()) {
            goals.next();
            goals.remove();
            PerformanceMetrics.recordCoverSearchRequestCancelled();
        }
    }

    private static final class Request {
        private final CoverTacticalGoal goal;
        private int priority;
        private int dueTick;
        private final long sequence;

        private Request(CoverTacticalGoal goal, int priority, int dueTick, long sequence) {
            this.goal = goal;
            this.priority = priority;
            this.dueTick = dueTick;
            this.sequence = sequence;
        }
    }
}

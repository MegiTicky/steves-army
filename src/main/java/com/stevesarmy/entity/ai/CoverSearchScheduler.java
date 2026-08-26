package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerLevel;

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
    public static final int MAX_EMERGENCY_SEARCHES_PER_TICK = 1;
    public static final int ROUTINE_STAGGER_TICKS = 6;
    private static final int PRIORITY_AGING_INTERVAL_TICKS = 40;

    private static final Map<CoverTacticalGoal, Request> PENDING = new IdentityHashMap<>();
    private static long sequence;

    private CoverSearchScheduler() {
    }

    public static boolean request(CoverTacticalGoal goal, int priority, int dueTick) {
        return request(goal, priority, dueTick, false);
    }

    public static boolean request(CoverTacticalGoal goal, int priority, int dueTick, boolean emergency) {
        Request existing = PENDING.get(goal);
        if (existing != null) {
            if (emergency && !existing.emergency) {
                existing.emergency = true;
                existing.priority = 0;
            }
            if (priority < existing.priority) {
                existing.priority = priority;
                existing.dueTick = Math.min(existing.dueTick, dueTick);
            }
            if (emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestCoalesced();
            } else {
                PerformanceMetrics.recordCoverSearchRequestCoalesced();
            }
            return false;
        }

        PENDING.put(goal, new Request(goal, priority, dueTick, sequence++, emergency));
        if (emergency) {
            PerformanceMetrics.recordEmergencyCoverRequestQueued();
        } else {
            PerformanceMetrics.recordCoverSearchRequestQueued();
        }
        return true;
    }

    /** Returns the server clock used by queued cover requests. */
    public static int currentServerTick(CoverTacticalGoal goal) {
        ServerLevel level = goal.getServerLevel();
        return level != null ? level.getServer().getTickCount() : goal.getSoldierTickCount();
    }

    public static void cancel(CoverTacticalGoal goal) {
        Request request = PENDING.remove(goal);
        if (request != null) {
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestCancelled();
            } else {
                PerformanceMetrics.recordCoverSearchRequestCancelled();
            }
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
            .comparingInt((Request request) -> request.effectivePriority(currentTick))
            .thenComparingLong(request -> request.sequence));

        int executed = 0;
        int emergencyExecuted = 0;
        for (Request request : ready) {
            if (request.emergency && emergencyExecuted >= MAX_EMERGENCY_SEARCHES_PER_TICK) {
                PerformanceMetrics.recordEmergencyCoverRequestDeferred();
                continue;
            }
            if (!request.emergency && executed >= MAX_SEARCHES_PER_TICK) {
                PerformanceMetrics.recordCoverSearchRequestDeferred();
                continue;
            }

            // Remove before execution so a callback can safely enqueue a follow-up.
            if (PENDING.remove(request.goal) != request) {
                continue;
            }
            if (request.emergency) {
                emergencyExecuted++;
            } else {
                executed++;
            }
            long queueAge = request.queueAge(currentTick);
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestAge(queueAge);
            } else {
                PerformanceMetrics.recordCoverSearchRequestAge(queueAge);
            }
            if (request.isAged(currentTick) && !request.ageRecorded) {
                request.ageRecorded = true;
                PerformanceMetrics.recordCoverSearchRequestAged();
            }
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestExecuted();
            } else {
                PerformanceMetrics.recordCoverSearchRequestExecuted();
            }
            request.goal.executeQueuedCoverSearch();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Iterator<Map.Entry<CoverTacticalGoal, Request>> requests = PENDING.entrySet().iterator();
        while (requests.hasNext()) {
            Request request = requests.next().getValue();
            requests.remove();
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestCancelled();
            } else {
                PerformanceMetrics.recordCoverSearchRequestCancelled();
            }
        }
    }

    private static final class Request {
        private final CoverTacticalGoal goal;
        private int priority;
        private int dueTick;
        private final long sequence;
        private boolean emergency;
        private boolean ageRecorded;

        private Request(CoverTacticalGoal goal, int priority, int dueTick, long sequence, boolean emergency) {
            this.goal = goal;
            this.priority = priority;
            this.dueTick = dueTick;
            this.sequence = sequence;
            this.emergency = emergency;
        }

        private long queueAge(int currentTick) {
            return Math.max(0L, (long) currentTick - dueTick);
        }

        private boolean isAged(int currentTick) {
            return StevesArmyConfig.isPhase2RetryPolicyEnabled()
                && queueAge(currentTick) >= PRIORITY_AGING_INTERVAL_TICKS && priority > 0;
        }

        private int effectivePriority(int currentTick) {
            if (!StevesArmyConfig.isPhase2RetryPolicyEnabled()) {
                return priority;
            }
            long age = queueAge(currentTick);
            int promotions = (int) (age / PRIORITY_AGING_INTERVAL_TICKS);
            return Math.max(0, priority - promotions);
        }
    }
}

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
    // A cover search is still synchronous server-thread work. Emergency requests
    // use the same global slot and win by priority rather than adding another slot.
    public static final int MAX_SEARCHES_PER_TICK = 1;
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
            if (goal.isGoToRelocation()) {
                existing.goTo = true;
            }
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
            } else if (existing.goTo) {
                PerformanceMetrics.recordGoToCoverSearchRequestCoalesced();
            } else {
                PerformanceMetrics.recordCoverSearchRequestCoalesced();
            }
            return false;
        }

        boolean goTo = goal.isGoToRelocation();
        PENDING.put(goal, new Request(goal, priority, dueTick, sequence++, emergency, goTo));
        if (emergency) {
            PerformanceMetrics.recordEmergencyCoverRequestQueued();
        } else if (goTo) {
            PerformanceMetrics.recordGoToCoverSearchRequestQueued();
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
            } else if (request.goTo) {
                PerformanceMetrics.recordGoToCoverSearchRequestCancelled();
            } else {
                PerformanceMetrics.recordCoverSearchRequestCancelled();
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (PENDING.isEmpty()) {
            PerformanceMetrics.recordCoverSearchSchedulerTick(0, 0, 0, 0, 0);
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
            PerformanceMetrics.recordCoverSearchSchedulerTick(PENDING.size(), 0, 0, 0, 0);
            return;
        }

        ready.sort(Comparator
            .comparingInt((Request request) -> request.effectivePriority(currentTick))
            .thenComparingLong(request -> request.sequence));

        int executed = 0;
        int deferred = 0;
        int emergencyExecuted = 0;
        for (Request request : ready) {
            if (executed >= MAX_SEARCHES_PER_TICK) {
                deferred++;
                if (request.emergency) {
                    PerformanceMetrics.recordEmergencyCoverRequestDeferred();
                } else if (request.goTo) {
                    PerformanceMetrics.recordGoToCoverSearchRequestDeferred();
                } else {
                    PerformanceMetrics.recordCoverSearchRequestDeferred();
                }
                continue;
            }
            if (request.emergency) {
                emergencyExecuted++;
            }
            // Remove before execution so a callback can safely enqueue a follow-up.
            if (PENDING.remove(request.goal) != request) {
                continue;
            }
            executed++;
            long queueAge = request.queueAge(currentTick);
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestAge(queueAge);
            } else if (request.goTo) {
                PerformanceMetrics.recordGoToCoverSearchRequestAge(queueAge);
            } else {
                PerformanceMetrics.recordCoverSearchRequestAge(queueAge);
            }
            if (request.isAged(currentTick) && !request.ageRecorded) {
                request.ageRecorded = true;
                if (request.goTo) {
                    PerformanceMetrics.recordGoToCoverSearchRequestAged();
                } else {
                    PerformanceMetrics.recordCoverSearchRequestAged();
                }
            }
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestExecuted();
            } else if (request.goTo) {
                PerformanceMetrics.recordGoToCoverSearchRequestExecuted();
            } else {
                PerformanceMetrics.recordCoverSearchRequestExecuted();
            }
            long started = request.goTo ? System.nanoTime() : 0L;
            if (request.goTo) {
                PerformanceMetrics.beginGoToCoverSearch();
            }
            try {
                request.goal.executeQueuedCoverSearch();
            } finally {
                if (request.goTo) {
                    PerformanceMetrics.recordGoToCoverSearch(currentTick, System.nanoTime() - started);
                    PerformanceMetrics.endGoToCoverSearch();
                }
            }
        }
        PerformanceMetrics.recordCoverSearchSchedulerTick(PENDING.size(), ready.size(), executed,
            deferred, emergencyExecuted);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Iterator<Map.Entry<CoverTacticalGoal, Request>> requests = PENDING.entrySet().iterator();
        while (requests.hasNext()) {
            Request request = requests.next().getValue();
            requests.remove();
            if (request.emergency) {
                PerformanceMetrics.recordEmergencyCoverRequestCancelled();
            } else if (request.goTo) {
                PerformanceMetrics.recordGoToCoverSearchRequestCancelled();
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
        private boolean goTo;
        private boolean ageRecorded;

        private Request(CoverTacticalGoal goal, int priority, int dueTick, long sequence,
                        boolean emergency, boolean goTo) {
            this.goal = goal;
            this.priority = priority;
            this.dueTick = dueTick;
            this.sequence = sequence;
            this.emergency = emergency;
            this.goTo = goTo;
        }

        private long queueAge(int currentTick) {
            return Math.max(0L, (long) currentTick - dueTick);
        }

        private boolean isAged(int currentTick) {
            return StevesArmyConfig.isRetryPolicyEnabled()
                && queueAge(currentTick) >= PRIORITY_AGING_INTERVAL_TICKS && priority > 0;
        }

        private int effectivePriority(int currentTick) {
            if (!StevesArmyConfig.isRetryPolicyEnabled()) {
                return priority;
            }
            long age = queueAge(currentTick);
            int promotions = (int) (age / PRIORITY_AGING_INTERVAL_TICKS);
            return Math.max(0, priority - promotions);
        }
    }
}

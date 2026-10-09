package com.stevesarmy;

import com.stevesarmy.sim.SimManager;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Millisecond clock for gameplay timing. In production and normal dev runs
 * this is the plain wall clock, exactly as before. When the sim harness runs
 * with fast ticks (tick loop unthrottled, see SimManager), it returns
 * game-time-derived milliseconds (50 ms per tick) so millisecond combat
 * timers — peek cadence, cover entry, suppression memory, reservation
 * timeouts — keep their production tick-for-tick behavior even when the
 * server runs far faster than 20 TPS.
 */
public final class GameClock {
    private GameClock() {}

    public static long nowMs() {
        if (SimManager.fastTicks()) {
            try {
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null && server.overworld() != null) {
                    return server.overworld().getGameTime() * 50L;
                }
            } catch (Exception ignored) {
                // fall through to the wall clock rather than break gameplay
            }
        }
        return System.currentTimeMillis();
    }
}

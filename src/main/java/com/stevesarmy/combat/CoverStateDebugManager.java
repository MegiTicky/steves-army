package com.stevesarmy.combat;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side subscription for the cover state-machine debug render
 * ({@code /stevesarmy_debug coverstate}). Subscribed players receive a
 * per-soldier snapshot of the cover goal's decision state each debug sync so
 * the client renderer can draw the state card, loop counters, and the
 * follow-blocker chain while a strand is happening.
 */
public final class CoverStateDebugManager {
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private CoverStateDebugManager() {}

    public static boolean isEnabled(UUID playerId) {
        return SUBSCRIBERS.contains(playerId);
    }

    public static boolean setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) {
            SUBSCRIBERS.add(player.getUUID());
        } else {
            SUBSCRIBERS.remove(player.getUUID());
        }
        return enabled;
    }
}

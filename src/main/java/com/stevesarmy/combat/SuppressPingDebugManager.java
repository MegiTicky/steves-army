package com.stevesarmy.combat;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side subscription for the suppress-ping debug render
 * ({@code /stevesarmy_debug suppressping}). Subscribed players receive a
 * per-soldier snapshot of the ping-suppress pipeline each debug sync so the
 * client renderer can draw the ping zone, cached aim points with live LOS
 * validity, the current shot lane, and a status label.
 */
public final class SuppressPingDebugManager {
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private SuppressPingDebugManager() {}

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

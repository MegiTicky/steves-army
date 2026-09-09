package com.stevesarmy.combat;

import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.VehicleCrewDebugPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side owner subscriptions for vehicle crew diagnostics. */
public final class VehicleCrewDebugManager {
    public static final int OFF = 0;
    public static final int MINIMAL = 1;
    public static final int VERBOSE = 2;

    private static final ConcurrentHashMap<UUID, Integer> MODES = new ConcurrentHashMap<>();

    private VehicleCrewDebugManager() {}

    public static int getMode(UUID playerId) {
        return MODES.getOrDefault(playerId, OFF);
    }

    public static int cycle(ServerPlayer player) {
        return setMode(player, (getMode(player.getUUID()) + 1) % 3);
    }

    public static int setMode(ServerPlayer player, int mode) {
        int clamped = Math.max(OFF, Math.min(VERBOSE, mode));
        if (clamped == OFF) {
            MODES.remove(player.getUUID());
        } else {
            MODES.put(player.getUUID(), clamped);
        }
        StationGunnerAI.sendDebugSnapshot(player, clamped);
        return clamped;
    }

    public static Set<UUID> subscribedPlayers() {
        return MODES.keySet();
    }

    public static void clear(ServerPlayer player) {
        MODES.remove(player.getUUID());
        NetworkHandler.sendTo(player, new VehicleCrewDebugPacket(OFF, java.util.List.of()));
    }

    public static String modeName(int mode) {
        return switch (mode) {
            case MINIMAL -> "MINIMAL";
            case VERBOSE -> "VERBOSE";
            default -> "OFF";
        };
    }
}

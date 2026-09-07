package com.stevesarmy.combat;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of which crew soldier mans which vehicle station
 * (tallyho hull MG or periscope camera entity). One soldier per station;
 * claims are re-validated lazily by the crew goal, so stale entries from
 * unloaded/despawned stations never block new claims permanently.
 */
public final class VehicleCrewManager {
    private static final Map<UUID, UUID> stations = new ConcurrentHashMap<>();

    private VehicleCrewManager() {}

    /** Claims the station for the soldier. Fails when another soldier holds it. */
    public static boolean claim(UUID stationId, UUID soldierId) {
        UUID existing = stations.get(stationId);
        if (existing != null && !existing.equals(soldierId)) {
            return false;
        }
        stations.put(stationId, soldierId);
        return true;
    }

    @Nullable
    public static UUID claimantOf(UUID stationId) {
        return stations.get(stationId);
    }

    public static void release(UUID stationId, UUID soldierId) {
        stations.remove(stationId, soldierId);
    }

    /** Releases every station held by the soldier (dismount, removal, role change). */
    public static void releaseAllFor(UUID soldierId) {
        stations.values().removeIf(soldierId::equals);
    }
}

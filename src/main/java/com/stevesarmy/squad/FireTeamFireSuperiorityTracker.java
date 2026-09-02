package com.stevesarmy.squad;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks rolling counts of friendly outgoing fire vs enemy incoming fire
 * per fireteam to compute a fire superiority ratio.
 *
 * superiority = friendly / (friendly + enemy + 0.01)
 *   0.0 = enemy dominates (we're being shot at far more than we're shooting)
 *   0.5 = neutral
 *   1.0 = we dominate
 */
public final class FireTeamFireSuperiorityTracker {

    private static final int WINDOW_TICKS = 100; // 5 seconds at 20 TPS
    private static int tickCounter = 0;

    private static class FireTeamEntry {
        int friendlyShots = 0;
        int enemyShots = 0;
        float superiority = 0.5f;
    }

    private static final Map<UUID, EnumMap<FireTeam, FireTeamEntry>> DATA = new HashMap<>();

    private FireTeamFireSuperiorityTracker() {}

    public static void tick() {
        tickCounter++;
        if (tickCounter < WINDOW_TICKS) return;
        tickCounter = 0;

        for (EnumMap<FireTeam, FireTeamEntry> ownerMap : DATA.values()) {
            for (Map.Entry<FireTeam, FireTeamEntry> e : ownerMap.entrySet()) {
                FireTeamEntry fte = e.getValue();
                // Halve counters (rolling window decay)
                fte.friendlyShots /= 2;
                fte.enemyShots /= 2;
                fte.superiority = (float) fte.friendlyShots
                    / (fte.friendlyShots + fte.enemyShots + 0.01f);
            }
        }
    }

    public static void onFriendlyShot(SoldierEntity shooter) {
        if (shooter == null || !shooter.isAlive()) return;
        UUID ownerId = shooter.getOwnerUUID().orElse(null);
        if (ownerId == null) return;
        FireTeam ft = shooter.getFireTeam();
        if (ft == FireTeam.GARRISON || ft == FireTeam.ALL) return;

        FireTeamEntry fte = getOrCreate(ownerId, ft);
        fte.friendlyShots++;
    }

    public static void onEnemyShotDetected(SoldierEntity victim) {
        if (victim == null || !victim.isAlive()) return;
        UUID ownerId = victim.getOwnerUUID().orElse(null);
        if (ownerId == null) return;
        FireTeam ft = victim.getFireTeam();
        if (ft == FireTeam.GARRISON || ft == FireTeam.ALL) return;

        FireTeamEntry fte = getOrCreate(ownerId, ft);
        fte.enemyShots++;
    }

    public static float getSuperiority(SoldierEntity soldier) {
        if (soldier == null) return 0.5f;
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return 0.5f;
        FireTeam ft = soldier.getFireTeam();
        EnumMap<FireTeam, FireTeamEntry> ownerMap = DATA.get(ownerId);
        if (ownerMap == null) return 0.5f;
        FireTeamEntry fte = ownerMap.get(ft);
        return fte == null ? 0.5f : fte.superiority;
    }

    private static FireTeamEntry getOrCreate(UUID ownerId, FireTeam ft) {
        EnumMap<FireTeam, FireTeamEntry> ownerMap =
            DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class));
        return ownerMap.computeIfAbsent(ft, k -> new FireTeamEntry());
    }
}

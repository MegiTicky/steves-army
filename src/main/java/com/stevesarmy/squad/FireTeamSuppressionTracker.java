package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.combat.cover.SuppressionTracker;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.FireTeamSuppressionSyncPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class FireTeamSuppressionTracker {
    public enum FireTeamSuppressionState {
        CLEAR,
        SUPPRESSED,
        HEAVY
    }

    private static class Entry {
        float level = 0.0f;
        FireTeamSuppressionState state = FireTeamSuppressionState.CLEAR;
        long heavyStartTick = -1;
        long lastNotifyTick = Long.MIN_VALUE;
        float pendingImpulse = 0.0f;
        Vec3 centroid = null;
        float incomingPressure = 0.0f;
        float memberPressure = 0.0f;
    }

    private static final Map<UUID, EnumMap<FireTeam, Entry>> DATA = new HashMap<>();
    private static long serverTick = 0;

    private FireTeamSuppressionTracker() {}

    public static void tick(MinecraftServer server) {
        if (!StevesArmyConfig.isFireteamSuppressionEnabled()) return;
        serverTick++;
        FireTeamFireSuperiorityTracker.tick();
        float riseRate = StevesArmyConfig.getFireteamRiseRate();
        float fallRate = StevesArmyConfig.getFireteamFallRate();
        float suppressionThreshold = StevesArmyConfig.getFireteamSuppressionThreshold();
        float heavyThreshold = StevesArmyConfig.getFireteamHeavyThreshold();
        float incomingDecay = StevesArmyConfig.getFireteamIncomingPressureDecay();
        float supDampening = StevesArmyConfig.getFireteamSuperiorityDampening();

        Set<UUID> seenOwners = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID ownerId = player.getUUID();
            seenOwners.add(ownerId);
            ServerLevel playerLevel = (ServerLevel) player.level();
            FireTeamAssignment fta;
            try {
                fta = FireTeamAssignment.get(playerLevel, ownerId);
            } catch (Exception e) {
                continue;
            }
            for (FireTeam ft : fta.getActiveTeams()) {
                if (ft == FireTeam.GARRISON || ft == FireTeam.ALL) continue;
                List<UUID> ids = fta.getSoldiersInTeam(ft);
                List<SoldierEntity> living = new ArrayList<>();
                Vec3 sum = Vec3.ZERO;
                int count = 0;
                float sumSuppression = 0.0f;
                int pressured = 0;
                int pinned = 0;
                for (UUID sid : ids) {
                    SoldierEntity soldier = findSoldier(server, sid);
                    if (soldier == null || !soldier.isAlive() || soldier.isRemoved()) continue;
                    if (soldier.getFireTeam() != ft) continue;
                    living.add(soldier);
                    sum = sum.add(soldier.position());
                    count++;
                    float lvl = 0.0f;
                    SuppressionTracker.SuppressionState cur = SuppressionTracker.SuppressionState.CLEAR;
                    try {
                        lvl = soldier.getCoverBehaviorManager().getSuppressionTracker().getSuppressionLevel();
                        cur = soldier.getCoverBehaviorManager().getSuppressionTracker().getState();
                    } catch (Exception ignored) {}
                    sumSuppression += Mth.clamp(lvl, 0.0f, 1.0f);
                    if (cur == SuppressionTracker.SuppressionState.PRESSURED) pressured++;
                    if (cur == SuppressionTracker.SuppressionState.PINNED) pinned++;
                }
                if (count == 0) {
                    EnumMap<FireTeam, Entry> map = DATA.get(ownerId);
                    if (map != null) map.remove(ft);
                    continue;
                }

                Vec3 centroid = sum.scale(1.0 / count);

                EnumMap<FireTeam, Entry> map = DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class));
                Entry entry = map.computeIfAbsent(ft, k -> new Entry());
                entry.centroid = centroid;

                // --- memberPressure: slow composite of individual states ---
                float avgSuppression = sumSuppression / count;
                float pressuredFraction = (float) pressured / count;
                float pinnedFraction = (float) pinned / count;
                entry.memberPressure = 0.65f * avgSuppression
                    + 0.25f * pressuredFraction
                    + 0.10f * pinnedFraction;

                // --- incomingPressure: deduplicated hostile fire events ---
                // Decay each tick
                entry.incomingPressure *= incomingDecay;
                // Add casualty bump as pending impulse
                if (entry.pendingImpulse > 0.0f) {
                    entry.incomingPressure = Math.min(
                        entry.incomingPressure + entry.pendingImpulse, 1.0f);
                    entry.pendingImpulse = 0.0f;
                }

                // --- fire superiority ---
                float superiority = 0.5f;
                if (!living.isEmpty()) {
                    superiority = FireTeamFireSuperiorityTracker.getSuperiority(living.get(0));
                }

                // --- final target ---
                float target = 0.75f * entry.memberPressure
                    + 0.25f * entry.incomingPressure;
                // superiority dampening: 0.0 → target * 1.2 (enemy dominates),
                // 1.0 → target * 0.8 (we dominate)
                target *= Mth.lerp(superiority, 1.2f, 0.8f);
                target = Mth.clamp(target, 0.0f, 1.0f);

                // --- asymmetric EMA ---
                float rate = target > entry.level ? riseRate : fallRate;
                // Smoke cuts the enemy's observation of this fireteam, so the
                // pressure source is gone: recover in seconds, not half a minute.
                if (target <= entry.level
                    && SmokeDeploymentCoordinator.isScreenActive(ownerId, ft,
                        server.overworld().getGameTime())) {
                    rate *= StevesArmyConfig.getSmokeFallRateMultiplier();
                }
                entry.level = Mth.clamp(
                    entry.level + (target - entry.level) * rate, 0.0f, 1.0f);

                FireTeamSuppressionState newState;
                if (entry.level >= heavyThreshold) newState = FireTeamSuppressionState.HEAVY;
                else if (entry.level >= suppressionThreshold) newState = FireTeamSuppressionState.SUPPRESSED;
                else newState = FireTeamSuppressionState.CLEAR;

                if (newState == FireTeamSuppressionState.HEAVY) {
                    if (entry.state != FireTeamSuppressionState.HEAVY) entry.heavyStartTick = serverTick;
                } else {
                    entry.heavyStartTick = -1;
                }
                entry.state = newState;

                if (entry.state == FireTeamSuppressionState.HEAVY && entry.heavyStartTick != -1) {
                    long held = serverTick - entry.heavyStartTick;
                    long need = StevesArmyConfig.getFireteamHoldNotifyTicks();
                    long cooldown = StevesArmyConfig.getFireteamNotifyCooldownTicks();
                    if (held >= need && (entry.lastNotifyTick == Long.MIN_VALUE || serverTick - entry.lastNotifyTick >= cooldown)) {
                        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                        if (owner != null) {
                            BlockPos c = BlockPos.containing(centroid);
                            owner.sendSystemMessage(Component.literal("[Squad] Fireteam " + ft.name() + " is pinned down at (" + c.getX() + ", " + c.getY() + ", " + c.getZ() + ")."));
                            entry.lastNotifyTick = serverTick;
                        }
                    }
                }
            }
        }
        Set<UUID> toRemove = new HashSet<>();
        for (UUID oid : DATA.keySet()) if (!seenOwners.contains(oid)) toRemove.add(oid);
        for (UUID oid : toRemove) DATA.remove(oid);
    }

    private static SoldierEntity findSoldier(MinecraftServer server, UUID id) {
        for (ServerLevel lvl : server.getAllLevels()) {
            Entity e = lvl.getEntity(id);
            if (e instanceof SoldierEntity s) return s;
        }
        return null;
    }

    public static void recordCasualty(SoldierEntity soldier) {
        if (!StevesArmyConfig.isFireteamSuppressionEnabled()) return;
        Optional<UUID> ownerOpt = soldier.getOwnerUUID();
        if (ownerOpt.isEmpty()) return;
        UUID ownerId = ownerOpt.get();
        FireTeam ft = soldier.getFireTeam();
        if (ft == FireTeam.GARRISON || ft == FireTeam.ALL) return;
        EnumMap<FireTeam, Entry> map = DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class));
        Entry entry = map.computeIfAbsent(ft, k -> new Entry());
        entry.pendingImpulse = Math.min(entry.pendingImpulse + StevesArmyConfig.getFireteamCasualtyBump(), 1.0f);
    }

    /**
     * Called once per enemy bullet burst (deduplicated by IncomingFireHandler).
     * affectedCount = number of team members whose near-miss search box was hit.
     * teamSize = total living members in the fireteam.
     * eventPressure = weaponThreat * proximity * sqrt(affectedCount / teamSize).
     */
    public static void onHostileFireEvent(SoldierEntity victim, float weaponThreat,
                                          int affectedCount, int teamSize) {
        if (!StevesArmyConfig.isFireteamSuppressionEnabled()) return;
        if (victim == null) return;
        Optional<UUID> ownerOpt = victim.getOwnerUUID();
        if (ownerOpt.isEmpty()) return;
        UUID ownerId = ownerOpt.get();
        FireTeam ft = victim.getFireTeam();
        if (ft == FireTeam.GARRISON || ft == FireTeam.ALL) return;

        float fraction = (float) affectedCount / Math.max(teamSize, 1);
        float eventPressure = weaponThreat * (float) Math.sqrt(fraction);
        eventPressure = Mth.clamp(eventPressure, 0.0f, 1.0f);

        EnumMap<FireTeam, Entry> map = DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class));
        Entry entry = map.computeIfAbsent(ft, k -> new Entry());
        // Blend: 70% existing + 30% new event, capped at 1.0
        entry.incomingPressure = Mth.clamp(
            0.7f * entry.incomingPressure + 0.3f * eventPressure, 0.0f, 1.0f);
    }

    public static float getLevel(SoldierEntity soldier) {
        Optional<UUID> ownerOpt = soldier.getOwnerUUID();
        if (ownerOpt.isEmpty()) return 0.0f;
        EnumMap<FireTeam, Entry> map = DATA.get(ownerOpt.get());
        if (map == null) return 0.0f;
        Entry e = map.get(soldier.getFireTeam());
        return e == null ? 0.0f : e.level;
    }

    public static FireTeamSuppressionState getState(SoldierEntity soldier) {
        Optional<UUID> ownerOpt = soldier.getOwnerUUID();
        if (ownerOpt.isEmpty()) return FireTeamSuppressionState.CLEAR;
        EnumMap<FireTeam, Entry> map = DATA.get(ownerOpt.get());
        if (map == null) return FireTeamSuppressionState.CLEAR;
        Entry e = map.get(soldier.getFireTeam());
        return e == null ? FireTeamSuppressionState.CLEAR : e.state;
    }

    public static boolean shouldBlockPeek(SoldierEntity soldier) {
        return getLevel(soldier) >= StevesArmyConfig.getFireteamPeekBlockThreshold();
    }

    public static boolean shouldPauseAttack(SoldierEntity soldier) {
        return getLevel(soldier) >= StevesArmyConfig.getFireteamHeavyThreshold();
    }

    public static boolean isHeavilySuppressed(SoldierEntity soldier) {
        return getState(soldier) == FireTeamSuppressionState.HEAVY;
    }

    /** Ticks the fireteam has continuously held the HEAVY state; 0 when not HEAVY. */
    public static long getHeavyHoldTicks(SoldierEntity soldier) {
        Entry e = getEntry(soldier);
        if (e == null || e.heavyStartTick == -1) return 0;
        return Math.max(0, serverTick - e.heavyStartTick);
    }

    public static Vec3 getCentroid(SoldierEntity soldier) {
        Entry e = getEntry(soldier);
        return e == null ? null : e.centroid;
    }

    public static float getMemberPressure(SoldierEntity soldier) {
        Entry e = getEntry(soldier);
        return e == null ? 0.0f : e.memberPressure;
    }

    public static float getIncomingPressure(SoldierEntity soldier) {
        Entry e = getEntry(soldier);
        return e == null ? 0.0f : e.incomingPressure;
    }

    public static float getFireSuperiority(SoldierEntity soldier) {
        return FireTeamFireSuperiorityTracker.getSuperiority(soldier);
    }

    private static Entry getEntry(SoldierEntity soldier) {
        Optional<UUID> ownerOpt = soldier.getOwnerUUID();
        if (ownerOpt.isEmpty()) return null;
        EnumMap<FireTeam, Entry> map = DATA.get(ownerOpt.get());
        if (map == null) return null;
        return map.get(soldier.getFireTeam());
    }

    public static void syncToPlayer(ServerPlayer player) {
        if (!StevesArmyConfig.isFireteamSuppressionEnabled()) return;
        UUID ownerId = player.getUUID();
        EnumMap<FireTeam, Entry> map = DATA.get(ownerId);
        List<FireTeamSuppressionSyncPacket.Entry> out = new ArrayList<>();
        if (map != null) {
            for (Map.Entry<FireTeam, Entry> e : map.entrySet()) {
                BlockPos centroid = e.getValue().centroid == null ? BlockPos.ZERO : BlockPos.containing(e.getValue().centroid);
                out.add(new FireTeamSuppressionSyncPacket.Entry(e.getKey(), e.getValue().level, e.getValue().state.ordinal(), centroid));
            }
        }
        NetworkHandler.sendTo(player, new FireTeamSuppressionSyncPacket(out));
    }
}

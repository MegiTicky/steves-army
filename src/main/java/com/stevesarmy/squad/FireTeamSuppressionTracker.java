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
    }

    private static final Map<UUID, EnumMap<FireTeam, Entry>> DATA = new HashMap<>();
    private static final Map<UUID, SuppressionTracker.SuppressionState> PREV_MEMBER_STATE = new HashMap<>();
    private static long serverTick = 0;

    private FireTeamSuppressionTracker() {}

    public static void tick(MinecraftServer server) {
        if (!StevesArmyConfig.isFireteamSuppressionEnabled()) return;
        serverTick++;
        float riseRate = StevesArmyConfig.getFireteamRiseRate();
        float fallRate = StevesArmyConfig.getFireteamFallRate();
        float impulsePerSuppressed = StevesArmyConfig.getFireteamSuppressionImpulse();
        float suppressionThreshold = StevesArmyConfig.getFireteamSuppressionThreshold();
        float heavyThreshold = StevesArmyConfig.getFireteamHeavyThreshold();

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
                int newlySuppressed = 0;
                for (UUID sid : ids) {
                    SoldierEntity soldier = findSoldier(server, sid);
                    if (soldier == null || !soldier.isAlive() || soldier.isRemoved()) continue;
                    if (soldier.getFireTeam() != ft) continue;
                    living.add(soldier);
                    sum = sum.add(soldier.position());
                    count++;
                    float lvl = 0.0f;
                    try {
                        lvl = soldier.getCoverBehaviorManager().getSuppressionTracker().getSuppressionLevel();
                    } catch (Exception ignored) {}
                    sumSuppression += Mth.clamp(lvl, 0.0f, 1.0f);
                    SuppressionTracker.SuppressionState cur = SuppressionTracker.SuppressionState.CLEAR;
                    try {
                        cur = soldier.getCoverBehaviorManager().getSuppressionTracker().getState();
                    } catch (Exception ignored) {}
                    SuppressionTracker.SuppressionState prev = PREV_MEMBER_STATE.get(sid);
                    if ((prev == null || prev == SuppressionTracker.SuppressionState.CLEAR) && cur != SuppressionTracker.SuppressionState.CLEAR) {
                        newlySuppressed++;
                    }
                    PREV_MEMBER_STATE.put(sid, cur);
                }
                if (count == 0) {
                    EnumMap<FireTeam, Entry> map = DATA.get(ownerId);
                    if (map != null) map.remove(ft);
                    continue;
                }
                float target = sumSuppression / count;
                Vec3 centroid = sum.scale(1.0 / count);

                EnumMap<FireTeam, Entry> map = DATA.computeIfAbsent(ownerId, k -> new EnumMap<>(FireTeam.class));
                Entry entry = map.computeIfAbsent(ft, k -> new Entry());
                entry.centroid = centroid;

                float impulse = newlySuppressed * impulsePerSuppressed + entry.pendingImpulse;
                entry.pendingImpulse = 0.0f;

                float delta = target - entry.level;
                float rate = delta > 0 ? riseRate : fallRate;
                entry.level = Mth.clamp(entry.level + delta * rate + impulse, 0.0f, 1.0f);

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
        PREV_MEMBER_STATE.keySet().removeIf(id -> findSoldier(server, id) == null);
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

package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SquadActivitySyncPacket;
import com.stevesarmy.ping.PingType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SquadActivityManager {
    private static final Map<UUID, EnumMap<FireTeam, Activity>> ACTIVITIES = new HashMap<>();
    private static final Map<UUID, EnumMap<FireTeam, Long>> GENERATIONS = new HashMap<>();
    /** Server handle cached from tick() for packet-free completion paths. */
    private static volatile MinecraftServer serverInstance;

    private SquadActivityManager() {
    }

    public static void applyCommand(ServerPlayer owner, FireTeam scope, PingType pingType,
                                    BlockPos requestedObjective, int dimension,
                                    List<SoldierEntity> recipients) {
        applyCommandCore(owner.getUUID(), scope, pingType, requestedObjective, dimension, recipients);
        sync(owner);
    }

    /**
     * Owner-agnostic order application. The player path calls the overload
     * above (which syncs afterwards); simulation harnesses issue orders for
     * non-player owners (anchor entities) through this core directly.
     */
    public static void applyCommandCore(UUID ownerId, FireTeam scope, PingType pingType,
                                        BlockPos requestedObjective, int dimension,
                                        List<SoldierEntity> recipients) {
        if (pingType != PingType.FOLLOW && SquadActivityType.fromPingType(pingType) == null) return;
        // A scoped command may replace only part of an earlier ALL activity.
        // Remove those recipients from its roster so the old regroup cannot reclaim them.
        Set<UUID> replaced = new HashSet<>();
        recipients.forEach(soldier -> replaced.add(soldier.getUUID()));
        ClearingCoordinator.detachRecipients(ownerId, replaced);
        EnumMap<FireTeam, Activity> previous = ACTIVITIES.get(ownerId);
        if (previous != null) {
            previous.values().forEach(activity -> activity.recipientIds.removeAll(replaced));
            previous.values().removeIf(activity -> activity.recipientIds.isEmpty());
        }
        if (pingType == PingType.FOLLOW) {
            clearScope(ownerId, scope);
            return;
        }

        SquadActivityType activityType = SquadActivityType.fromPingType(pingType);
        if (activityType == null) {
            return;
        }

        clearScope(ownerId, scope);
        if (recipients.isEmpty()) {
            return;
        }

        if (scope == FireTeam.ALL) {
            BlockPos objective = activityType == SquadActivityType.HOLD
                ? centerOf(recipients)
                : requestedObjective;
            long generation = nextGeneration(ownerId, FireTeam.ALL);
            Set<UUID> recipientIds = new HashSet<>();
            for (SoldierEntity soldier : recipients) {
                recipientIds.add(soldier.getUUID());
            }
            getOwnerActivities(ownerId).put(FireTeam.ALL,
                new Activity(FireTeam.ALL, activityType, objective.immutable(), dimension, recipientIds, generation));
            return;
        }

        Map<FireTeam, List<SoldierEntity>> byTeam = new EnumMap<>(FireTeam.class);
        for (SoldierEntity soldier : recipients) {
            FireTeam team = soldier.getFireTeam();
            if (team == FireTeam.ALL && scope != FireTeam.ALL) {
                team = scope;
            }
            byTeam.computeIfAbsent(team, ignored -> new ArrayList<>()).add(soldier);
        }

        for (Map.Entry<FireTeam, List<SoldierEntity>> entry : byTeam.entrySet()) {
            FireTeam team = entry.getKey();
            List<SoldierEntity> teamRecipients = entry.getValue();
            BlockPos objective = activityType == SquadActivityType.HOLD
                ? centerOf(teamRecipients)
                : requestedObjective;
            long generation = nextGeneration(ownerId, team);
            Set<UUID> recipientIds = new HashSet<>();
            for (SoldierEntity soldier : teamRecipients) {
                recipientIds.add(soldier.getUUID());
            }
            getOwnerActivities(ownerId).put(team,
                new Activity(team, activityType, objective.immutable(), dimension, recipientIds, generation));
        }
    }

    public static void sync(ServerPlayer player) {
        List<SquadActivitySyncPacket.ActivityEntry> entries = new ArrayList<>();
        EnumMap<FireTeam, Activity> ownerActivities = ACTIVITIES.get(player.getUUID());
        if (ownerActivities != null) {
            for (Activity activity : ownerActivities.values()) {
                entries.add(new SquadActivitySyncPacket.ActivityEntry(
                    activity.fireTeam,
                    activity.type,
                    activity.objective,
                    activity.dimension,
                    activity.generation,
                    activity.type == SquadActivityType.ATTACK
                        ? ClearingCoordinator.stageOrdinal(player.getUUID(), activity.fireTeam) : -1,
                    ClearingCoordinator.regroupingCount(player.getUUID(), activity.fireTeam, activity.generation)
                ));
            }
        }
        entries.sort(java.util.Comparator.comparingInt(entry -> entry.fireTeam().ordinal()));
        NetworkHandler.sendTo(player, new SquadActivitySyncPacket(entries));
    }

    /** Immutable read view of one ATTACK activity for the clearing coordinator. */
    public record AttackActivityView(UUID ownerId, FireTeam fireTeam, BlockPos objective,
                                     int dimension, Set<UUID> recipientIds, long generation) {
    }

    /** Include cleared HOLD activities while their original roster is regrouping. */
    public static List<AttackActivityView> snapshotAttackActivities() {
        List<AttackActivityView> views = new ArrayList<>();
        for (Map.Entry<UUID, EnumMap<FireTeam, Activity>> ownerEntry : ACTIVITIES.entrySet()) {
            for (Activity activity : ownerEntry.getValue().values()) {
                if (activity.type == SquadActivityType.ATTACK
                    || (activity.type == SquadActivityType.HOLD && ClearingCoordinator.isRegrouping(
                        ownerEntry.getKey(), activity.fireTeam, activity.generation))) {
                    views.add(new AttackActivityView(ownerEntry.getKey(), activity.fireTeam,
                        activity.objective, activity.dimension,
                        new HashSet<>(activity.recipientIds), activity.generation));
                }
            }
        }
        return views;
    }

    public static void tick(MinecraftServer server) {
        serverInstance = server;
        Set<UUID> changedOwners = new HashSet<>();

        for (Map.Entry<UUID, EnumMap<FireTeam, Activity>> ownerEntry : ACTIVITIES.entrySet()) {
            UUID ownerId = ownerEntry.getKey();
            EnumMap<FireTeam, Activity> ownerActivities = ownerEntry.getValue();
            List<FireTeam> completed = new ArrayList<>();
            Map<FireTeam, Activity> transitioned = new EnumMap<>(FireTeam.class);

            for (Map.Entry<FireTeam, Activity> activityEntry : ownerActivities.entrySet()) {
                Activity activity = activityEntry.getValue();
                if (activity.type == SquadActivityType.GO_TO && isGoToComplete(server, activity)) {
                    List<SoldierEntity> livingRecipients = livingRecipients(server, activity);
                    if (!livingRecipients.isEmpty()) {
                        transitioned.put(activityEntry.getKey(), activity.asHold(centerOf(livingRecipients)));
                    }
                } else if (activity.type != SquadActivityType.GO_TO && isComplete(server, activity)) {
                    completed.add(activityEntry.getKey());
                }
            }

            if (!transitioned.isEmpty()) {
                ownerActivities.putAll(transitioned);
                changedOwners.add(ownerId);
            }

            if (!completed.isEmpty()) {
                for (FireTeam team : completed) {
                    ownerActivities.remove(team);
                }
                changedOwners.add(ownerId);
            }
        }

        ACTIVITIES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (UUID ownerId : changedOwners) {
            ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
            if (player != null) {
                sync(player);
            }
        }
    }

    public static void removeSoldier(UUID soldierId, MinecraftServer server) {
        Set<UUID> changedOwners = new HashSet<>();
        for (Map.Entry<UUID, EnumMap<FireTeam, Activity>> ownerEntry : ACTIVITIES.entrySet()) {
            EnumMap<FireTeam, Activity> ownerActivities = ownerEntry.getValue();
            List<FireTeam> emptyTeams = new ArrayList<>();
            for (Map.Entry<FireTeam, Activity> activityEntry : ownerActivities.entrySet()) {
                Activity activity = activityEntry.getValue();
                activity.recipientIds.remove(soldierId);
                if (activity.recipientIds.isEmpty()) {
                    emptyTeams.add(activityEntry.getKey());
                }
            }
            if (!emptyTeams.isEmpty()) {
                emptyTeams.forEach(ownerActivities::remove);
                changedOwners.add(ownerEntry.getKey());
            }
        }
        ACTIVITIES.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (UUID ownerId : changedOwners) {
            ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
            if (player != null) {
                sync(player);
            }
        }
    }

    private static boolean isComplete(MinecraftServer server, Activity activity) {
        if (activity.type == SquadActivityType.HOLD) {
            return false;
        }
        if (activity.type == SquadActivityType.ATTACK) {
            return isAttackComplete(server, activity);
        }

        boolean foundRecipient = false;
        for (UUID recipientId : activity.recipientIds) {
            SoldierEntity soldier = findSoldier(server, recipientId);
            if (soldier == null || !soldier.isAlive()) {
                return false;
            }
            foundRecipient = true;

            boolean complete = switch (activity.type) {
                case GO_TO -> false;
                case ATTACK -> false;
                case SEND -> !soldier.hasValidPingMoveTarget()
                    || distanceToObjectiveSqr(soldier, activity.objective) <= 4.0;
                case SUPPRESS_AREA -> !soldier.hasValidPingSuppressPos();
                case THREAT_DIRECTION -> !soldier.hasValidPingThreatPos();
                case HOLD -> false;
            };
            if (!complete) {
                return false;
            }
        }
        return foundRecipient;
    }

    private static boolean isAttackComplete(MinecraftServer server, Activity activity) {
        // The clearing coordinator owns ATTACK completion (area reached, blind
        // spots observed, contact resolved, squad settled). Proximity alone is
        // only the fallback when clearing doctrine is disabled or the
        // coordinator has no state for this order (e.g. direct
        // setAttackTarget callers that never created an activity).
        if (ClearingCoordinator.isComplete(activityOwner(activity), activity.fireTeam,
            activity.generation)) {
            return true;
        }
        if (!com.stevesarmy.StevesArmyConfig.isAttackClearEnabled()) {
            for (UUID recipientId : activity.recipientIds) {
                SoldierEntity soldier = findSoldier(server, recipientId);
                if (soldier == null || !soldier.isAlive()) {
                    continue;
                }
                if (distanceToObjectiveSqr(soldier, activity.objective) > 16.0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Clearing-doctrine completion: the activity becomes a HOLD anchored on
     * the secured objective after a majority arrives. The coordinator retains
     * individual catch-up orders for remaining recipients.
     */
    public static void completeAttackToHold(UUID ownerId, FireTeam team, BlockPos objective) {
        EnumMap<FireTeam, Activity> ownerActivities = ACTIVITIES.get(ownerId);
        if (ownerActivities == null) {
            return;
        }
        Activity activity = ownerActivities.get(team);
        if (activity == null || activity.type != SquadActivityType.ATTACK) {
            return;
        }
        // The coordinator owns individual arrival. Travellers keep their GO_TO
        // endpoints after the majority handoff; do not clear their movement here.
        ownerActivities.put(team, activity.asHold(objective.immutable()));
        MinecraftServer server = serverInstance;
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
            if (player != null) {
                sync(player);
            }
        }
    }

    private static UUID activityOwner(Activity activity) {
        for (Map.Entry<UUID, EnumMap<FireTeam, Activity>> entry : ACTIVITIES.entrySet()) {
            if (entry.getValue().containsValue(activity)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Iterable<ServerLevel> serverLevels() {
        MinecraftServer server = serverInstance;
        return server != null ? server.getAllLevels() : List.of();
    }

    private static boolean isGoToComplete(MinecraftServer server, Activity activity) {
        boolean foundRecipient = false;
        for (UUID recipientId : activity.recipientIds) {
            SoldierEntity soldier = findSoldier(server, recipientId);
            if (soldier == null) {
                return false;
            }
            if (!soldier.isAlive()) {
                continue;
            }
            foundRecipient = true;
            if (!soldier.isGoToHolding()) {
                return false;
            }
        }
        return foundRecipient;
    }

    private static List<SoldierEntity> livingRecipients(MinecraftServer server, Activity activity) {
        List<SoldierEntity> recipients = new ArrayList<>();
        for (UUID recipientId : activity.recipientIds) {
            SoldierEntity soldier = findSoldier(server, recipientId);
            if (soldier != null && soldier.isAlive()) {
                recipients.add(soldier);
            }
        }
        return recipients;
    }

    private static double distanceToObjectiveSqr(SoldierEntity soldier, BlockPos objective) {
        return soldier.distanceToSqr(objective.getX() + 0.5, objective.getY() + 0.5, objective.getZ() + 0.5);
    }

    @javax.annotation.Nullable
    private static SoldierEntity findSoldier(MinecraftServer server, UUID soldierId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(soldierId) instanceof SoldierEntity soldier) {
                return soldier;
            }
        }
        return null;
    }

    private static BlockPos centerOf(Collection<SoldierEntity> soldiers) {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (SoldierEntity soldier : soldiers) {
            x += soldier.getX();
            y += soldier.getY();
            z += soldier.getZ();
        }
        int count = Math.max(1, soldiers.size());
        return BlockPos.containing(x / count, y / count, z / count);
    }

    private static EnumMap<FireTeam, Activity> getOwnerActivities(UUID ownerId) {
        return ACTIVITIES.computeIfAbsent(ownerId, ignored -> new EnumMap<>(FireTeam.class));
    }

    private static long nextGeneration(UUID ownerId, FireTeam team) {
        EnumMap<FireTeam, Long> ownerGenerations = GENERATIONS.computeIfAbsent(
            ownerId, ignored -> new EnumMap<>(FireTeam.class));
        long generation = ownerGenerations.getOrDefault(team, 0L) + 1L;
        ownerGenerations.put(team, generation);
        return generation;
    }

    private static void clearScope(UUID ownerId, FireTeam scope) {
        ClearingCoordinator.cancelOrder(ownerId, scope);
        EnumMap<FireTeam, Activity> ownerActivities = ACTIVITIES.get(ownerId);
        if (ownerActivities == null) {
            return;
        }
        if (scope == FireTeam.ALL) {
            ownerActivities.clear();
        } else {
            ownerActivities.remove(scope);
        }
        if (ownerActivities.isEmpty()) {
            ACTIVITIES.remove(ownerId);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVITIES.clear();
        GENERATIONS.clear();
    }

    private static final class Activity {
        private final FireTeam fireTeam;
        private final SquadActivityType type;
        private final BlockPos objective;
        private final int dimension;
        private final Set<UUID> recipientIds;
        private final long generation;

        private Activity(FireTeam fireTeam, SquadActivityType type, BlockPos objective, int dimension,
                         Set<UUID> recipientIds, long generation) {
            this.fireTeam = fireTeam;
            this.type = type;
            this.objective = objective;
            this.dimension = dimension;
            this.recipientIds = recipientIds;
            this.generation = generation;
        }

        private Activity asHold(BlockPos holdObjective) {
            return new Activity(fireTeam, SquadActivityType.HOLD, holdObjective.immutable(), dimension,
                new HashSet<>(recipientIds), generation);
        }
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.AttackDebugPacket;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.entity.ai.CoverGoalController;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadActivityManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class SquadSyncHandler {
    private static int tickCounter = 0;
    private static final Set<UUID> ATTACK_DEBUG_PLAYERS = new HashSet<>();

    public static boolean toggleAttackDebug(ServerPlayer player) {
        UUID playerId = player.getUUID();
        boolean enabled;
        if (ATTACK_DEBUG_PLAYERS.remove(playerId)) {
            enabled = false;
        } else {
            ATTACK_DEBUG_PLAYERS.add(playerId);
            enabled = true;
        }
        sendAttackDebugPacket(player, enabled);
        return enabled;
    }

    private static void sendAttackDebugPacket(ServerPlayer player, boolean enabled) {
        List<AttackDebugPacket.Entry> entries = enabled
            ? createAttackDebugEntries(player)
            : List.of();
        NetworkHandler.sendTo(player, new AttackDebugPacket(enabled, entries));
    }

    private static List<AttackDebugPacket.Entry> createAttackDebugEntries(ServerPlayer player) {
        List<AttackDebugPacket.Entry> entries = new ArrayList<>();
        for (SoldierEntity soldier : player.serverLevel().getEntitiesOfClass(
                SoldierEntity.class, player.getBoundingBox().inflate(100),
                soldier -> soldier.isOwnedBy(player) && soldier.isAlive() && !soldier.isRemoved())) {
            var cover = soldier.getCoverBehaviorManager();
            var tracker = cover.getSuppressionTracker();
            CoverGoalController controller = soldier.getCoverTacticalGoal();
            CoverTacticalGoal goal = controller instanceof CoverTacticalGoal tactical ? tactical : null;
            FireTeam fireTeam = soldier.getFireTeam();
            float fireteamLevel = com.stevesarmy.squad.FireTeamSuppressionTracker.getLevel(soldier);
            int fireteamState = com.stevesarmy.squad.FireTeamSuppressionTracker.getState(soldier).ordinal();
            entries.add(new AttackDebugPacket.Entry(
                soldier.getUUID(),
                fireTeam.getShortName(),
                fireteamLevel,
                fireteamState,
                goal == null ? 0 : goal.getAttackPhaseOrdinal(),
                goal == null ? 0.0f : goal.getAttackDwellFraction(),
                tracker.getSuppressionLevel(),
                tracker.isSuppressed(),
                tracker.isRecovered(),
                com.stevesarmy.squad.FireTeamSuppressionTracker.shouldPauseAttack(soldier),
                com.stevesarmy.squad.FireTeamSuppressionTracker.isHeavilySuppressed(soldier),
                goal != null && goal.isRecoverySafetyPeekDone(),
                goal != null && goal.isPeeking(),
                soldier.hasValidAttackTarget(),
                soldier.position(),
                goal != null && goal.isAttackHasPeekedThisCover(),
                goal != null && goal.isAttackDwellMet(),
                goal != null && goal.isSoftCoverAllowed()));
        }
        return entries;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PerformanceMetrics.beginTick();
            PerformanceMetrics.beginCoverPopulationTick();
            return;
        }
        if (event.phase != TickEvent.Phase.END) return;

        SquadActivityManager.tick(event.getServer());
        com.stevesarmy.squad.FireTeamSuppressionTracker.tick(event.getServer());

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;

            for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (entity instanceof SoldierEntity soldier && !soldier.level().isClientSide) {
                        OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(event.getServer());
                        if (soldier.isAlive() && !soldier.isRemoved()) {
                            registry.refresh(soldier, level);
                        } else {
                            registry.remove(soldier.getUUID());
                        }
                    }
                }
            }

            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                SquadStatusSyncPacket packet = SquadStatusSyncPacket.createForPlayer(player);
                NetworkHandler.sendTo(player, packet);
                SquadActivityManager.sync(player);
                com.stevesarmy.squad.FireTeamSuppressionTracker.syncToPlayer(player);
                if (ATTACK_DEBUG_PLAYERS.contains(player.getUUID())) {
                    sendAttackDebugPacket(player, true);
                }
            }
        }

        PerformanceMetrics.endCoverPopulationTick();
        PerformanceMetrics.endTick();
    }
}

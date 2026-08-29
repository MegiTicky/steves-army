package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadActivityManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class SquadSyncHandler {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PerformanceMetrics.beginTick();
            PerformanceMetrics.beginCoverPopulationTick();
            return;
        }
        if (event.phase != TickEvent.Phase.END) return;

        SquadActivityManager.tick(event.getServer());

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;

            for (net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (entity instanceof SoldierEntity soldier && !soldier.level().isClientSide) {
                        OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(event.getServer());
                        if (soldier.isAlive()) {
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
            }
        }

        PerformanceMetrics.endCoverPopulationTick();
        PerformanceMetrics.endTick();
    }
}

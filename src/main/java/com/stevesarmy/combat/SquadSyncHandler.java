package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SquadStatusSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class SquadSyncHandler {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter < 20) return;
        tickCounter = 0;

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            SquadStatusSyncPacket packet = SquadStatusSyncPacket.createForPlayer(player);
            if (!packet.getEntries().isEmpty()) {
                NetworkHandler.sendTo(player, packet);
            }
        }
    }
}
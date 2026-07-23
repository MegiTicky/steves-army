package com.stevesarmy.inventory;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class SoldierInventorySyncHandler {
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof SoldierEntity soldier)) return;

        NetworkHandler.sendTo(player, new SyncSoldierInventoryPacket(
            soldier.getId(), soldier.getSoldierInventory().save()));
        StevesArmyMod.LOGGER.debug("[SoldierInventorySync] Sent {} inventory to {}",
            soldier.getStringUUID(), player.getScoreboardName());
    }
}

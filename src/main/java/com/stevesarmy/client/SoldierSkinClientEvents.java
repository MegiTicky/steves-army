package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drops server-pushed skins when leaving a world so they don't leak across servers. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, value = Dist.CLIENT)
public final class SoldierSkinClientEvents {

    private SoldierSkinClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SoldierSkinLoader.clearRemote();
    }
}

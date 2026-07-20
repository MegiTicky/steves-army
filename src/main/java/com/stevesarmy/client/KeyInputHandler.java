package com.stevesarmy.client;

import com.stevesarmy.client.screen.SquadCommandScreen;
import com.stevesarmy.network.DebugMessage;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army", value = Dist.CLIENT)
public class KeyInputHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (KeyBindings.DEBUG.consumeClick()) {
            NetworkHandler.INSTANCE.sendToServer(new DebugMessage());
        }

        if (KeyBindings.SQUAD_COMMAND.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof SquadCommandScreen) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new SquadCommandScreen());
            }
        }
    }
}
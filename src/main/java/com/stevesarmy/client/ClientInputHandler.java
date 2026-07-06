package com.stevesarmy.client;

import com.stevesarmy.network.DebugMessage;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army", value = Dist.CLIENT)
public class ClientInputHandler {
    
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (KeyBindings.DEBUG.consumeClick()) {
            NetworkHandler.INSTANCE.sendToServer(new DebugMessage());
        }
    }
}
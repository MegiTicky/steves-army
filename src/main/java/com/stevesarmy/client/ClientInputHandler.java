package com.stevesarmy.client;

import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.ToggleSquadModeMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army", value = Dist.CLIENT)
public class ClientInputHandler {
    
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (KeyBindings.TOGGLE_SQUAD_MODE.consumeClick()) {
            NetworkHandler.INSTANCE.sendToServer(new ToggleSquadModeMessage());
        }
    }
}
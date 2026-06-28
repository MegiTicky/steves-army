package com.stevesarmy.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.stevesarmy.network.DebugMessage;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.ToggleSquadModeMessage;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "steves_army", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyBindings {
    public static final KeyMapping TOGGLE_SQUAD_MODE = new KeyMapping(
        "key.steves_army.toggle_squad",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        "key.categories.steves_army"
    );

    public static final KeyMapping DEBUG = new KeyMapping(
        "key.steves_army.debug",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_H,
        "key.categories.steves_army"
    );
    
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_SQUAD_MODE);
        event.register(DEBUG);
    }
}
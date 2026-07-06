package com.stevesarmy.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "steves_army", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyBindings {
    public static final KeyMapping FORMATION_WHEEL = new KeyMapping(
        "key.steves_army.formation_wheel",
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
    
    public static final KeyMapping PING_WHEEL = new KeyMapping(
        "key.steves_army.ping_wheel",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.MOUSE,
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
        "key.categories.steves_army"
    );
    
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(FORMATION_WHEEL);
        event.register(DEBUG);
        event.register(PING_WHEEL);
    }
    
    public static boolean isPingWheelKeyDown() {
        return PING_WHEEL.isDown();
    }
}
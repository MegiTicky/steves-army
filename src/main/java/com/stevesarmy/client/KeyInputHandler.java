package com.stevesarmy.client;

import com.stevesarmy.client.screen.SquadCommandScreen;
import com.stevesarmy.network.DebugMessage;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = "steves_army", value = Dist.CLIENT)
public class KeyInputHandler {

    private static boolean ctrlLastTick = false;

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

        if (KeyBindings.CYCLE_FIRE_TEAM.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            long window = mc.getWindow().getWindow();
            boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

            if (ctrlHeld) {
                FireTeamScopeState.INSTANCE.setCurrentScope(FireTeam.ALL);
                mc.player.displayClientMessage(Component.literal("[ALL]"), true);
            } else {
                FireTeam current = FireTeamScopeState.INSTANCE.getCurrentScope();
                int teamCount = FireTeamScopeState.INSTANCE.getTeamCount();
                FireTeam next;
                if (current == FireTeam.ALL || current.ordinal() >= teamCount) {
                    next = FireTeam.values()[1];
                } else {
                    next = FireTeam.values()[current.ordinal() + 1];
                }
                FireTeamScopeState.INSTANCE.setCurrentScope(next);
                mc.player.displayClientMessage(Component.literal("[" + next.getShortName() + "]"), true);
            }
        }
    }
}
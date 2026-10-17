package com.stevesarmy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Shared lifecycle for command, formation and fire-team wheel sessions. */
public final class WheelInteractionController {
    private static boolean pingPress;
    private static boolean formationPress;
    private static boolean teamPress;

    private WheelInteractionController() {}

    public static void queueKeyboardPress(int key, int scanCode) {
        if (!isClickMode() || !canOpen()) return;
        pingPress |= KeyBindings.PING_WHEEL.matches(key, scanCode);
        formationPress |= KeyBindings.FORMATION_WHEEL.matches(key, scanCode);
        teamPress |= KeyBindings.FIRE_TEAM_WHEEL.matches(key, scanCode);
    }

    public static boolean takePingPress() { boolean value = pingPress; pingPress = false; return value; }
    public static boolean takeFormationPress() { boolean value = formationPress; formationPress = false; return value; }
    public static boolean takeTeamPress() { boolean value = teamPress; teamPress = false; return value; }

    public static void queueMousePress(int button) {
        if (!isClickMode() || !canOpen()) return;
        pingPress |= KeyBindings.PING_WHEEL.matchesMouse(button);
        formationPress |= KeyBindings.FORMATION_WHEEL.matchesMouse(button);
        teamPress |= KeyBindings.FIRE_TEAM_WHEEL.matchesMouse(button);
    }

    public static boolean isClickMode() {
        return StevesArmyClientConfig.WHEEL_INTERACTION_MODE.get()
            == StevesArmyClientConfig.WheelInteractionMode.CLICK;
    }

    public static void beforeOpen() {
        cancelAll();
    }

    public static void cancelAll() {
        PingWheelHandler.cancel();
        FormationWheelHandler.cancel();
        FireTeamWheelHandler.cancel();
    }

    public static boolean canOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.screen == null && mc.isWindowActive();
    }

    public static void tick() {
        if (!canOpen()) {
            pingPress = formationPress = teamPress = false;
            cancelAll();
        }
    }

    public static boolean onEscape() {
        if (!isClickMode() || !WheelCycleController.isAnyHoldWheelActive()) return false;
        cancelAll();
        return true;
    }

    /** Called before vanilla can re-grab the cursor or start an attack. */
    public static void confirmClick() {
        if (!canOpen()) { cancelAll(); return; }
        if (PingWheelHandler.isWheelActive()) PingWheelHandler.confirm();
        else if (FormationWheelHandler.isWheelActive()) FormationWheelHandler.confirm();
        else if (FireTeamWheelHandler.isWheelActive()) FireTeamWheelHandler.confirm();
    }

    public static boolean isOpenerMouse(int button) {
        return KeyBindings.PING_WHEEL.matchesMouse(button)
            || KeyBindings.FORMATION_WHEEL.matchesMouse(button)
            || (StevesArmyClientConfig.ENABLE_FIRE_TEAM_WHEEL.get()
                && KeyBindings.FIRE_TEAM_WHEEL.matchesMouse(button));
    }

    public static void setOpenerMouseDown(int button, boolean down) {
        if (KeyBindings.PING_WHEEL.matchesMouse(button)) KeyBindings.PING_WHEEL.setDown(down);
        if (KeyBindings.FORMATION_WHEEL.matchesMouse(button)) KeyBindings.FORMATION_WHEEL.setDown(down);
        if (KeyBindings.FIRE_TEAM_WHEEL.matchesMouse(button)) KeyBindings.FIRE_TEAM_WHEEL.setDown(down);
    }

    public static void drawHint(GuiGraphics graphics) {
        if (!isClickMode() || !WheelCycleController.isAnyHoldWheelActive()) return;
        Minecraft mc = Minecraft.getInstance();
        graphics.drawCenteredString(mc.font, Component.translatable("wheel.steves_army.click_hint"),
            mc.getWindow().getGuiScaledWidth() / 2,
            mc.getWindow().getGuiScaledHeight() / 2 + 100, 0xFFCCCCCC);
    }
}

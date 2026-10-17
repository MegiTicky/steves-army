package com.stevesarmy.client;

import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class FireTeamWheelHandler {
    private static final int HOLD_TIME_MS = 150;

    private static boolean isWheelActive = false;
    private static boolean wasKeyDown = false;
    private static long pressStartTime = 0;
    private static double pressMouseX = 0;
    private static double pressMouseY = 0;

    private static FireTeam currentHoveredTeam = FireTeam.ALL;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (!StevesArmyClientConfig.ENABLE_FIRE_TEAM_WHEEL.get()) {
            if (isWheelActive) {
                releaseMouse(mc);
                isWheelActive = false;
                wasKeyDown = false;
            }
            return;
        }
        if (mc.player == null || mc.level == null) {
            if (isWheelActive) releaseMouse(mc);
            isWheelActive = false;
            wasKeyDown = false;
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean pingDown = KeyBindings.isPingWheelKeyDown();
        boolean fireTeamKeyDown = KeyBindings.FIRE_TEAM_WHEEL.isDown() || (ctrlHeld && pingDown);
        boolean openingPress = WheelInteractionController.takeTeamPress() | (fireTeamKeyDown && !wasKeyDown);
        boolean shortcutPress = PingWheelHandler.takeFireTeamShortcutPress();
        openingPress |= ctrlHeld && shortcutPress;

        if (!WheelInteractionController.canOpen()) {
            cancel();
            wasKeyDown = fireTeamKeyDown;
            return;
        }

        if (openingPress && isWheelActive && WheelInteractionController.isClickMode()) {
            cancel();
            wasKeyDown = fireTeamKeyDown;
            return;
        }

        if (openingPress) {
            WheelInteractionController.beforeOpen();
            isWheelActive = true;
            pressStartTime = System.currentTimeMillis();
            grabMouseForWheel(mc);
        }

        if (!fireTeamKeyDown && wasKeyDown && isWheelActive && !WheelInteractionController.isClickMode()) {
            finish(false);
        }

        wasKeyDown = fireTeamKeyDown;
    }

    public static void confirm() {
        if (isWheelActive) finish(true);
    }

    public static void cancel() {
        if (!isWheelActive) return;
        isWheelActive = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null && mc.isWindowActive()) releaseMouse(mc);
    }

    private static void finish(boolean force) {
        Minecraft mc = Minecraft.getInstance();

        long holdTime = System.currentTimeMillis() - pressStartTime;
        if (force) currentHoveredTeam = getHoveredTeam();
        releaseMouse(mc);

        if (force || holdTime >= HOLD_TIME_MS) {
            FireTeam selected = currentHoveredTeam;
            if (selected != FireTeam.ALL) {
                if (selected.ordinal() <= FireTeamScopeState.INSTANCE.getTeamCount()) {
                    FireTeamScopeState.INSTANCE.setCurrentScope(selected, "fire-team wheel");
                }
            } else {
                FireTeamScopeState.INSTANCE.setCurrentScope(FireTeam.ALL, "fire-team wheel");
            }
        }

        isWheelActive = false;
    }

    private static void grabMouseForWheel(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        // GLFW cursor positions use logical window units, not Retina framebuffer pixels.
        mc.mouseHandler.releaseMouse();
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(windowHandle, width, height);
        GLFW.glfwSetCursorPos(windowHandle, width[0] / 2.0, height[0] / 2.0);
        double[] cursorX = new double[1];
        double[] cursorY = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, cursorX, cursorY);
        pressMouseX = cursorX[0];
        pressMouseY = cursorY[0];
    }

    private static void releaseMouse(Minecraft mc) {
        mc.mouseHandler.grabMouse();
    }

    private static FireTeam determineTeamFromMouse(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        double[] xpos = new double[1], ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        double deltaX = xpos[0] - pressMouseX;
        double deltaY = ypos[0] - pressMouseY;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        if (distance < 30) {
            return FireTeamScopeState.INSTANCE.getCurrentScope();
        }

        double angle = Math.toDegrees(Math.atan2(deltaY, deltaX));
        double adjustedDegrees = angle + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;

        int teamCount = FireTeamScopeState.INSTANCE.getTeamCount();
        int numSectors = teamCount + 1;
        int sectorSize = 360 / numSectors;
        int sector = ((int) (adjustedDegrees / sectorSize)) % numSectors;

        if (sector == 0) return FireTeam.ALL;
        return FireTeam.values()[sector];
    }

    public static boolean isWheelActive() {
        return isWheelActive;
    }

    public static FireTeam getHoveredTeam() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredTeam = determineTeamFromMouse(mc);
        return currentHoveredTeam;
    }
}

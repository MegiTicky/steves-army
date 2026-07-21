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

        if (fireTeamKeyDown && !wasKeyDown) {
            isWheelActive = true;
            pressStartTime = System.currentTimeMillis();
            grabMouseForWheel(mc);
        }

        if (!fireTeamKeyDown && wasKeyDown && isWheelActive) {
            long holdTime = System.currentTimeMillis() - pressStartTime;
            releaseMouse(mc);

            if (holdTime >= HOLD_TIME_MS) {
                FireTeam selected = currentHoveredTeam;
                if (selected != FireTeam.ALL) {
                    if (selected.ordinal() <= FireTeamScopeState.INSTANCE.getTeamCount()) {
                        FireTeamScopeState.INSTANCE.setCurrentScope(selected);
                    }
                } else {
                    FireTeamScopeState.INSTANCE.setCurrentScope(FireTeam.ALL);
                }
            }

            isWheelActive = false;
        }

        wasKeyDown = fireTeamKeyDown;
    }

    private static void grabMouseForWheel(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        GLFW.glfwSetCursorPos(windowHandle, width / 2.0, height / 2.0);
        pressMouseX = width / 2.0;
        pressMouseY = height / 2.0;
        mc.mouseHandler.releaseMouse();
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
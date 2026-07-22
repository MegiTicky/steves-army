package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.FormationMessage;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.SquadFormation;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class FormationWheelHandler {
    private static final int HOLD_TIME_MS = 150;

    private static boolean isWheelActive = false;
    private static boolean wasKeyDown = false;
    private static long pressStartTime = 0;
    private static double pressMouseX = 0;
    private static double pressMouseY = 0;

    private static SquadFormation currentHoveredFormation = SquadFormation.NONE;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (isWheelActive) {
                releaseMouse(mc);
            }
            isWheelActive = false;
            wasKeyDown = false;
            return;
        }

        boolean isKeyDown = KeyBindings.FORMATION_WHEEL.isDown();

        if (isKeyDown && !wasKeyDown) {
            isWheelActive = true;
            pressStartTime = System.currentTimeMillis();

            grabMouseForWheel(mc);

            StevesArmyMod.LOGGER.info("Formation wheel activated");
        }

        if (!isKeyDown && wasKeyDown && isWheelActive) {
            long holdTime = System.currentTimeMillis() - pressStartTime;

            releaseMouse(mc);

            if (holdTime >= HOLD_TIME_MS) {
                SquadFormation selectedFormation = currentHoveredFormation;
                StevesArmyMod.LOGGER.info("Formation wheel released after {}ms, selected: {}", holdTime, selectedFormation.getDisplayName());

                if (selectedFormation == SquadFormation.CQB) {
                    com.stevesarmy.network.CQBToggleMessage message = new com.stevesarmy.network.CQBToggleMessage();
                    NetworkHandler.INSTANCE.sendToServer(message);
                } else {
                    FormationMessage message = new FormationMessage(selectedFormation);
                    NetworkHandler.INSTANCE.sendToServer(message);
                }
            }

            isWheelActive = false;
            FormationWheelRenderer.resetLogFlag();
        }

        wasKeyDown = isKeyDown;
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

    private static SquadFormation determineFormationFromMouse(Minecraft mc) {
        long windowHandle = mc.getWindow().getWindow();
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        double currentX = xpos[0];
        double currentY = ypos[0];

        double deltaX = currentX - pressMouseX;
        double deltaY = currentY - pressMouseY;

        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        if (distance < 30) {
            return SquadFormation.NONE;
        }

        double angle = Math.toDegrees(Math.atan2(deltaY, deltaX));

        double adjustedDegrees = angle + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;

        int sector = ((int) (adjustedDegrees / 72)) % 5;
        return SquadFormation.values()[sector];
    }

    public static boolean isWheelActive() {
        return isWheelActive;
    }

    public static SquadFormation getHoveredFormation() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredFormation = determineFormationFromMouse(mc);
        return currentHoveredFormation;
    }
}
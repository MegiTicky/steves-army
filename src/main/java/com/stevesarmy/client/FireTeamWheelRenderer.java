package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.squad.FireTeam;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class FireTeamWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 70;
    private static final int SEPARATOR_COLOR = 0xCCAAAAAA;
    private static final int SEPARATOR_HALF_WIDTH = 0;

    public static void render(GuiGraphics guiGraphics) {
        if (!FireTeamWheelHandler.isWheelActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        FireTeam hovered = FireTeamWheelHandler.getHoveredTeam();
        int teamCount = FireTeamScopeState.INSTANCE.getTeamCount();
        int numSectors = teamCount + 1; // ALL + active teams
        int sectorSize = 360 / numSectors;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        drawSectorSeparators(guiGraphics, centerX, centerY, numSectors, sectorSize);

        for (int i = 0; i < numSectors; i++) {
            FireTeam team;
            if (i == 0) {
                team = FireTeam.ALL;
            } else {
                team = FireTeam.values()[i];
            }
            boolean isHovered = team == hovered;
            int startAngle = i * sectorSize;

            String label = team.getShortName();
            double labelRad = Math.toRadians(startAngle + sectorSize / 2 - 90);
            int labelX = centerX + (int) (Math.cos(labelRad) * LABEL_RADIUS);
            int labelY = centerY + (int) (Math.sin(labelRad) * LABEL_RADIUS);

            int textColor = isHovered ? 0xFFFFFFFF : 0xFFAAAAAA;
            guiGraphics.drawCenteredString(mc.font, label, labelX, labelY - mc.font.lineHeight / 2, textColor);
        }

        FireTeam current = FireTeamScopeState.INSTANCE.getCurrentScope();
        String curLabel = current.getShortName();
        int curColor = getTeamColor(current);
        guiGraphics.drawCenteredString(mc.font, "[" + curLabel + "]", centerX, centerY - mc.font.lineHeight / 2, curColor);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    public static int getTeamColor(FireTeam team) {
        return switch (team) {
            case ALL -> 0xFFFFFF;
            case ALPHA -> 0xFF5555;
            case BRAVO -> 0x5555FF;
            case CHARLIE -> 0x55FF55;
            case DELTA -> 0xFFFF55;
        };
    }

    private static void drawSectorSeparators(GuiGraphics guiGraphics, int centerX, int centerY,
                                             int sectorCount, int sectorSize) {
        for (int i = 0; i < sectorCount; i++) {
            float angle = i * sectorSize - 90.0f;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, centerY, 0);
            guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(angle));
            guiGraphics.fill(INNER_RADIUS, -SEPARATOR_HALF_WIDTH,
                OUTER_RADIUS, SEPARATOR_HALF_WIDTH + 1, SEPARATOR_COLOR);
            guiGraphics.pose().popPose();
        }
    }
}

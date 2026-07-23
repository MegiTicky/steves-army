package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.squad.SquadFormation;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class FormationWheelRenderer {
    private static final int SECTOR_SIZE = 72;
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 70;
    private static final int SEPARATOR_COLOR = 0xCCAAAAAA;
    private static final int SEPARATOR_HALF_WIDTH = 0;
    private static boolean loggedRender = false;

    public static void render(GuiGraphics guiGraphics) {
        if (!FormationWheelHandler.isWheelActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!loggedRender) {
            loggedRender = true;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        SquadFormation hoveredFormation = FormationWheelHandler.getHoveredFormation();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        SquadFormation[] formations = SquadFormation.values();
        int sectorCount = formations.length; // 5 sectors: NONE, LINE, WEDGE, COLUMN, CQB

        drawSectorSeparators(guiGraphics, centerX, centerY, sectorCount, SECTOR_SIZE);

        for (int i = 0; i < sectorCount; i++) {
            SquadFormation formation = formations[i];
            boolean isHovered = formation == hoveredFormation;
            int startAngle = i * SECTOR_SIZE;

            String label = formation.getDisplayName();
            double labelRad = Math.toRadians(startAngle + SECTOR_SIZE / 2 - 90);
            int labelX = centerX + (int) (Math.cos(labelRad) * LABEL_RADIUS);
            int labelY = centerY + (int) (Math.sin(labelRad) * LABEL_RADIUS);

            int textColor = isHovered ? 0xFFFFFFFF : 0xFFAAAAAA;
            guiGraphics.drawCenteredString(mc.font, label, labelX, labelY - mc.font.lineHeight / 2, textColor);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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

    public static void resetLogFlag() {
        loggedRender = false;
    }
}

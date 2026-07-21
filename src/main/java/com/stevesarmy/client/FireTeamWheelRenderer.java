package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class FireTeamWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 70;

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

        // Sectors: ALL at top (sector 0), then ALPHA, BRAVO, CHARLIE, DELTA clockwise
        for (int i = 0; i < numSectors; i++) {
            FireTeam team;
            if (i == 0) {
                team = FireTeam.ALL;
            } else {
                team = FireTeam.values()[i]; // ALPHA=1, BRAVO=2, CHARLIE=3, DELTA=4
            }
            boolean isHovered = team == hovered;
            int startAngle = i * sectorSize;

            int color = getTeamColor(team);
            int alpha = isHovered ? 180 : 80;
            int renderColor = (alpha << 24) | (color & 0x00FFFFFF);
            drawSector(guiGraphics.pose(), centerX, centerY, INNER_RADIUS, OUTER_RADIUS, startAngle, sectorSize, renderColor);
        }

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

    private static void drawSector(PoseStack poseStack, int centerX, int centerY, int innerRadius, int outerRadius, int startAngleDeg, int arcDeg, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        Matrix4f matrix = poseStack.last().pose();

        double startRad = Math.toRadians(startAngleDeg - 90);
        double endRad = Math.toRadians(startAngleDeg + arcDeg - 90);
        int segments = Math.max(4, arcDeg / 5);
        double step = (endRad - startRad) / segments;

        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segments; i++) {
            double angle1 = startRad + i * step;
            double angle2 = startRad + (i + 1) * step;
            float cos1 = (float) Math.cos(angle1);
            float sin1 = (float) Math.sin(angle1);
            float cos2 = (float) Math.cos(angle2);
            float sin2 = (float) Math.sin(angle2);
            float innerX1 = centerX + cos1 * innerRadius;
            float innerY1 = centerY + sin1 * innerRadius;
            float outerX1 = centerX + cos1 * outerRadius;
            float outerY1 = centerY + sin1 * outerRadius;
            float innerX2 = centerX + cos2 * innerRadius;
            float innerY2 = centerY + sin2 * innerRadius;
            float outerX2 = centerX + cos2 * outerRadius;
            float outerY2 = centerY + sin2 * outerRadius;
            buffer.vertex(matrix, innerX1, innerY1, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, outerX1, outerY1, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, innerX2, innerY2, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, innerX2, innerY2, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, outerX1, outerY1, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, outerX2, outerY2, 0).color(r, g, b, a).endVertex();
        }
        tesselator.end();
    }
}
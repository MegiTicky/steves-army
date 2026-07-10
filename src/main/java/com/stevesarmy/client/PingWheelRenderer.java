package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.ping.PingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class PingWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 70;
    private static boolean loggedRender = false;
    
    public static void render(GuiGraphics guiGraphics) {
        if (!PingWheelHandler.isWheelActive()) return;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        if (!loggedRender) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("Ping wheel rendering");
            loggedRender = true;
        }
        
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        
        PingType hoveredType = PingWheelHandler.getHoveredType();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        
        int numTypes = PingType.values().length;
        int sectorSize = 360 / numTypes;
        
        for (int i = 0; i < numTypes; i++) {
            PingType type = PingType.values()[i];
            boolean isHovered = type == hoveredType;
            int startAngle = i * sectorSize;
            
            int color = type.getColor();
            int alpha = isHovered ? 180 : 80;
            int renderColor = (alpha << 24) | (color & 0x00FFFFFF);
            
            drawSector(guiGraphics.pose(), centerX, centerY, INNER_RADIUS, OUTER_RADIUS, startAngle, sectorSize, renderColor);
        }
        
        for (int i = 0; i < numTypes; i++) {
            PingType type = PingType.values()[i];
            boolean isHovered = type == hoveredType;
            int startAngle = i * sectorSize;
            
            String label = Component.translatable(type.getTranslationKey()).getString();
            double labelRad = Math.toRadians(startAngle + sectorSize / 2 - 90);
            int labelX = centerX + (int) (Math.cos(labelRad) * LABEL_RADIUS);
            int labelY = centerY + (int) (Math.sin(labelRad) * LABEL_RADIUS);
            
            int textColor = isHovered ? 0xFFFFFFFF : 0xFFAAAAAA;
            guiGraphics.drawCenteredString(mc.font, label, labelX, labelY - mc.font.lineHeight / 2, textColor);
        }
        
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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
    
    public static void resetLogFlag() {
        loggedRender = false;
    }
}
package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.FireTeam;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class PingWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 70;
    private static final int SEPARATOR_COLOR = 0xCCAAAAAA;
    private static final int SEPARATOR_HALF_WIDTH = 0;
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

        drawSectorSeparators(guiGraphics, centerX, centerY, numTypes, sectorSize);
        
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

        // Scope badge
        FireTeam scope = FireTeamScopeState.INSTANCE.getCurrentScope();
        String scopeLabel = "[" + scope.getShortName() + "]";
        int scopeColor = switch (scope) {
            case ALL -> 0xFFFFFFFF;
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            case GARRISON -> 0xFF55FFFF;
        };
        guiGraphics.drawCenteredString(mc.font, scopeLabel, centerX, centerY - mc.font.lineHeight / 2, scopeColor);

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

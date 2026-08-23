package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.ping.Ping;
import com.stevesarmy.ping.PingManager;
import com.stevesarmy.util.ScreenPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

public class PingOverlayRenderer {
    private static final int PING_SIZE = 12;

    public static void render(GuiGraphics guiGraphics, WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        
        List<Ping> pings = PingManager.getActivePings();
        if (pings.isEmpty()) return;
        
        for (Ping ping : pings) {
            ScreenPos screenPos = ping.getScreenPos();
            if (screenPos == null) continue;
            if (screenPos.isBehindCamera()) continue;
            
            double distance = ping.getDistance();
            if (distance < 1.0 || distance > 256.0) continue;
            
            float screenX = screenPos.x;
            float screenY = screenPos.y;
            float scale = ping.getScale();
            PingCrosshairFade.Opacity opacity = PingCrosshairFade.calculate(mc, screenX, screenY);
            
            renderPingIcon(guiGraphics, ping, screenX, screenY, scale, distance, opacity);
        }
    }
    
    private static void renderPingIcon(GuiGraphics guiGraphics, Ping ping, float x, float y, float scale,
                                       double distance, PingCrosshairFade.Opacity opacity) {
        Minecraft mc = Minecraft.getInstance();
        
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        
        int color = PingCrosshairFade.withAlpha(ping.getTeamColor(), opacity.diamondAlpha());
        int iconSize = PING_SIZE;
        int halfSize = iconSize / 2;
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        
        guiGraphics.pose().pushPose();
        guiGraphics.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) Math.PI / 4));
        guiGraphics.fill(-halfSize, -halfSize, halfSize, halfSize, color);
        guiGraphics.pose().popPose();
        
        if (opacity.textAlpha() > 0.0f) {
            int textColor = PingCrosshairFade.withAlpha(0xFFFFFFFF, opacity.textAlpha());
            String distanceText = String.format("%.1fm", distance);
            int textWidth = mc.font.width(distanceText);
            guiGraphics.drawString(mc.font, distanceText, -textWidth / 2, halfSize + 4, textColor, true);

            String authorText = ping.getAuthorName();
            int authorWidth = mc.font.width(authorText);
            guiGraphics.drawString(mc.font, authorText, -authorWidth / 2, -halfSize - 14, textColor, true);

            int typeColor = PingCrosshairFade.withAlpha(ping.getTeamColor(), opacity.textAlpha());
            String typeText = ping.getType().name();
            int typeWidth = mc.font.width(typeText);
            guiGraphics.drawString(mc.font, typeText, -typeWidth / 2, -halfSize - 4, typeColor, true);

            String scopeText = ping.getScopeLabel();
            if (scopeText != null && !scopeText.isEmpty()) {
                int scopeWidth = mc.font.width(scopeText);
                int scopeColor = PingCrosshairFade.withAlpha(0xFFAAAAAA, opacity.textAlpha());
                guiGraphics.drawString(mc.font, scopeText, -scopeWidth / 2, -halfSize + 8, scopeColor, true);
            }
        }
        
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        
        guiGraphics.pose().popPose();
    }
}

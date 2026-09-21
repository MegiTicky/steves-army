package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Dynamic-sector wheel: one sector per squad role present, plus swap-back when available. */
public class SwapRoleWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 68;
    private static final int SEPARATOR_COLOR = 0xCCAAAAAA;

    public static void render(GuiGraphics guiGraphics) {
        if (!SwapRoleWheelHandler.isWheelActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        java.util.List<SwapRoleWheelHandler.WheelOption> options = SwapRoleWheelHandler.getOptions();
        if (options.isEmpty()) return;

        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int centerY = mc.getWindow().getGuiScaledHeight() / 2;
        double sectorSize = 360.0 / options.size();

        SwapRoleWheelHandler.WheelOption hovered = SwapRoleWheelHandler.getHoveredOption();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        for (int i = 0; i < options.size(); i++) {
            float angle = (float) (i * sectorSize) - 90.0f;
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, centerY, 0);
            guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(angle));
            guiGraphics.fill(INNER_RADIUS, 0, OUTER_RADIUS, 1, SEPARATOR_COLOR);
            guiGraphics.pose().popPose();
        }

        for (int i = 0; i < options.size(); i++) {
            SwapRoleWheelHandler.WheelOption option = options.get(i);
            String label = option.label;
            if (!option.previousBody && option.count > 1) {
                label = label + " x" + option.count;
            }
            boolean isHovered = option == hovered;
            int color = option.previousBody && !isHovered ? 0xFF55FFAA : (isHovered ? 0xFFFFFFFF : 0xFFAAAAAA);

            double labelRad = Math.toRadians(i * sectorSize + sectorSize / 2 - 90);
            int labelX = centerX + (int) (Math.cos(labelRad) * LABEL_RADIUS);
            int labelY = centerY + (int) (Math.sin(labelRad) * LABEL_RADIUS);

            if (option.subLabel != null) {
                // Two lines keeps long soldier names from spilling into the
                // neighboring sectors.
                guiGraphics.drawCenteredString(mc.font, label, labelX, labelY - mc.font.lineHeight - 1, color);
                guiGraphics.drawCenteredString(mc.font, option.subLabel, labelX, labelY + 1, 0xFFCCCCCC);
            } else {
                guiGraphics.drawCenteredString(mc.font, label, labelX, labelY - mc.font.lineHeight / 2, color);
            }
        }

        String title = net.minecraft.client.resources.language.I18n.get("gui.steves_army.swap_wheel.title");
        guiGraphics.drawCenteredString(mc.font, title, centerX, centerY - mc.font.lineHeight / 2, 0xFF888888);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}

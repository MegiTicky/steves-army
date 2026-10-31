package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.stevesarmy.transport.TransportOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Radial wheel for vehicle transport and driving orders. */
public class VehicleWheelRenderer {
    private static final int INNER_RADIUS = 30;
    private static final int OUTER_RADIUS = 80;
    private static final int LABEL_RADIUS = 74;
    private static final int SEPARATOR_COLOR = 0xCCAAAAAA;
    private static final int SEPARATOR_HALF_WIDTH = 0;

    public static void render(GuiGraphics guiGraphics) {
        if (!PingWheelHandler.isWheelActive() || !WheelCycleController.isVehiclePage()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        TransportOrder hoveredAction = VehicleWheelHandler.getHoveredAction();
        List<TransportOrder> actions = VehicleWheelHandler.actionsForCurrentPage();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        int count = actions.size();
        int sectorSize = 360 / count;

        drawSectorSeparators(guiGraphics, centerX, centerY, count, sectorSize);

        for (int i = 0; i < count; i++) {
            TransportOrder order = actions.get(i);
            boolean isHovered = order == hoveredAction;
            int startAngle = i * sectorSize;

            String label = Component.translatable(order.getTranslationKey()).getString();
            double labelRad = Math.toRadians(startAngle + sectorSize / 2 - 90);
            int labelX = centerX + (int) (Math.cos(labelRad) * LABEL_RADIUS);
            int labelY = centerY + (int) (Math.sin(labelRad) * LABEL_RADIUS);
            int textColor = isHovered ? 0xFFFFFFFF : 0xFFAAAAAA;
            String[] labelLines = label.split("\\s+");
            int widestLine = 0;
            for (String line : labelLines) widestLine = Math.max(widestLine, mc.font.width(line));
            int sectorLabelWidth = Math.max(1,
                (int) (2 * LABEL_RADIUS * Math.sin(Math.PI / count) * 0.9));
            float textScale = widestLine == 0 ? 1.0F
                : Math.min(1.0F, (float) sectorLabelWidth / widestLine);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(labelX, labelY, 0);
            guiGraphics.pose().scale(textScale, textScale, 1.0F);
            int firstLineY = -(labelLines.length * mc.font.lineHeight) / 2;
            for (int line = 0; line < labelLines.length; line++) {
                guiGraphics.drawCenteredString(mc.font, labelLines[line], 0,
                    firstLineY + line * mc.font.lineHeight, textColor);
            }
            guiGraphics.pose().popPose();
        }

        // Vehicle orders respect the fire-team scope, so show the same badge as the ping wheel.
        WheelHud.drawScopeBadge(guiGraphics, centerX, centerY);

        WheelCycleController.drawPageHeader(guiGraphics,
            Component.translatable("wheel.steves_army.vehicle"));

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
}

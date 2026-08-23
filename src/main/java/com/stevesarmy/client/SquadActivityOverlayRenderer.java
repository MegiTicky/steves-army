package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stevesarmy.network.SquadActivitySyncPacket;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.util.MathUtils;
import com.stevesarmy.util.ScreenPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

public final class SquadActivityOverlayRenderer {
    private static final int MARKER_SIZE = 14;
    private static final double MAX_DISTANCE = 256.0;

    private SquadActivityOverlayRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, WorldRenderContext context) {
        if (context == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int currentDimension = mc.level.dimension().location().hashCode();
        for (SquadActivitySyncPacket.ActivityEntry activity : ClientSquadActivityData.INSTANCE.getActivities()) {
            if (activity.dimension() != currentDimension) continue;

            Vec3 objective = Vec3.atCenterOf(activity.objective()).add(0.0, 1.25, 0.0);
            double distance = context.camera.getPosition().distanceTo(objective);
            if (distance < 1.0 || distance > MAX_DISTANCE) continue;

            ScreenPos screenPos = MathUtils.worldToScreen(objective, context);
            if (screenPos == null || screenPos.isBehindCamera()) continue;

            float scale = calculateScale(distance);
            renderMarker(guiGraphics, activity, screenPos.x, screenPos.y, scale, distance, mc);
        }
    }

    private static void renderMarker(GuiGraphics guiGraphics,
                                     SquadActivitySyncPacket.ActivityEntry activity,
                                     float x, float y, float scale, double distance, Minecraft mc) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0);
        guiGraphics.pose().scale(scale, scale, 1.0f);

        int color = teamColor(activity.fireTeam());
        int halfSize = MARKER_SIZE / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) Math.PI / 4.0f));
        guiGraphics.fill(-halfSize, -halfSize, halfSize, halfSize, color);
        guiGraphics.pose().popPose();

        String label = activity.fireTeam().name() + "  " + activity.type().getDisplayName();
        int labelWidth = mc.font.width(label);
        guiGraphics.drawString(mc.font, label, -labelWidth / 2, -halfSize - 13, color, true);

        String distanceText = String.format("%.1fm", distance);
        int distanceWidth = mc.font.width(distanceText);
        guiGraphics.drawString(mc.font, distanceText, -distanceWidth / 2, halfSize + 4, 0xFFFFFFFF, true);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        guiGraphics.pose().popPose();
    }

    private static float calculateScale(double distance) {
        double scale = 2.0 / Math.pow(distance, 0.3);
        return (float) Math.max(0.5, Math.min(2.0, scale))
            * StevesArmyClientConfig.PING_SCALE.get().floatValue();
    }

    private static int teamColor(FireTeam team) {
        return switch (team) {
            case ALL -> 0xFFFFFFFF;
            case ALPHA -> 0xFF55FF55;
            case BRAVO -> 0xFFFFAA00;
            case CHARLIE -> 0xFF5555FF;
            case DELTA -> 0xFFFF5555;
        };
    }
}

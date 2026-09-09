package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.VehicleCrewDebugManager;
import com.stevesarmy.network.VehicleCrewDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class VehicleCrewDebugRenderer {
    private VehicleCrewDebugRenderer() {}

    public static void render(PoseStack poseStack, Camera camera) {
        int mode = ClientVehicleCrewDebugData.INSTANCE.mode();
        if (mode == VehicleCrewDebugManager.OFF || ClientVehicleCrewDebugData.INSTANCE.entries().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) return;

        Vec3 cameraPos = camera.getPosition();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (VehicleCrewDebugPacket.Entry entry : ClientVehicleCrewDebugData.INSTANCE.entries().values()) {
            renderLines(buffer, poseStack.last().pose(), cameraPos, entry, mode);
        }
        tesselator.end();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (VehicleCrewDebugPacket.Entry entry : ClientVehicleCrewDebugData.INSTANCE.entries().values()) {
            renderLabel(mc.font, poseStack, cameraPos, entry, buffers);
            if (mode == VehicleCrewDebugManager.VERBOSE) renderObservations(mc.font, poseStack, cameraPos, entry, buffers);
        }
        buffers.endBatch();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderLines(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                    VehicleCrewDebugPacket.Entry entry, int mode) {
        Vec3 origin = entry.cameraWorld();
        Vec3 look = entry.look().normalize();
        line(buffer, matrix, cameraPos, origin, origin.add(look.scale(8)), 64, 224, 255);
        arc(buffer, matrix, cameraPos, origin, look, entry.focusedRange(), 90.0, 48, 210, 255);
        arc(buffer, matrix, cameraPos, origin, look, DetectionSystem.PERIPHERAL_RANGE, 180.0, 64, 116, 255);

        if (entry.suppressionAimPos() != null) {
            line(buffer, matrix, cameraPos, origin, entry.suppressionAimPos(), 255, 64, 224);
        } else if (entry.targetPos() != null) {
            int[] color = targetColor(entry);
            line(buffer, matrix, cameraPos, origin, entry.targetPos(), color[0], color[1], color[2]);
        }
        if (mode == VehicleCrewDebugManager.VERBOSE) {
            for (VehicleCrewDebugPacket.Observation observation : entry.observations()) {
                int[] color = observation.detected() ? new int[] {80, 255, 80}
                    : observation.visible() ? new int[] {255, 220, 64} : new int[] {255, 80, 80};
                line(buffer, matrix, cameraPos, origin, observation.targetPos(), color[0], color[1], color[2]);
            }
        }
    }

    private static int[] targetColor(VehicleCrewDebugPacket.Entry entry) {
        if (entry.state() == 4) return new int[] {255, 64, 224};
        if (entry.aimError() > 2.0f || !entry.ready() || !entry.hasAmmo()) return new int[] {255, 220, 64};
        return new int[] {80, 255, 80};
    }

    private static void arc(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, Vec3 origin,
                            Vec3 look, double radius, double degrees, int r, int g, int b) {
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() < 1.0e-4) horizontal = new Vec3(0, 0, 1);
        horizontal = horizontal.normalize();
        double base = Math.atan2(horizontal.x, horizontal.z);
        int steps = Math.max(8, (int) (degrees / 7.5));
        Vec3 previous = null;
        for (int i = 0; i <= steps; i++) {
            double angle = base + Math.toRadians(-degrees / 2.0 + degrees * i / steps);
            Vec3 point = origin.add(Math.sin(angle) * radius, 0.05, Math.cos(angle) * radius);
            if (previous != null) line(buffer, matrix, cameraPos, previous, point, r, g, b);
            previous = point;
        }
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, Vec3 from, Vec3 to,
                             int r, int g, int b) {
        Vec3 a = from.subtract(cameraPos), z = to.subtract(cameraPos);
        buffer.vertex(matrix, (float) a.x, (float) a.y, (float) a.z).color(r, g, b, 255).endVertex();
        buffer.vertex(matrix, (float) z.x, (float) z.y, (float) z.z).color(r, g, b, 255).endVertex();
    }

    private static void renderLabel(Font font, PoseStack poseStack, Vec3 cameraPos,
                                    VehicleCrewDebugPacket.Entry entry, MultiBufferSource.BufferSource buffers) {
        String kind = entry.gunner() ? "MG" : "OPTIC";
        String state = switch (entry.state()) {
            case 1 -> "NO TARGET";
            case 2 -> "TRACKING";
            case 3 -> "SWEEP";
            case 4 -> "SUPPRESS";
            default -> "ACTIVE";
        };
        String line1 = kind + " " + state + " crew=" + entry.soldierId().toString().substring(0, 8);
        String line2 = entry.gunner()
            ? String.format("aim=%.2f err=%s bloom=%.2f burst=%d/%d ready=%s ammo=%s", entry.aimQuality(),
                Float.isNaN(entry.aimError()) ? "-" : String.format("%.1f", entry.aimError()), entry.bloom(),
                entry.burstShots(), entry.burstPauseTicks(), entry.ready(), entry.hasAmmo())
            : String.format("sweep=%.0f%% yawLimit=%.0f range=%.0f", entry.sweepProgress() * 100.0f,
                entry.yawLimit(), entry.focusedRange());
        billboard(font, poseStack, cameraPos, entry.cameraWorld().add(0, 0.8, 0), line1, 0, 0xFF40E0FF, buffers);
        billboard(font, poseStack, cameraPos, entry.cameraWorld().add(0, 0.8, 0), line2, 10, 0xFFFFFFFF, buffers);
    }

    private static void renderObservations(Font font, PoseStack poseStack, Vec3 cameraPos,
                                           VehicleCrewDebugPacket.Entry entry, MultiBufferSource.BufferSource buffers) {
        for (VehicleCrewDebugPacket.Observation observation : entry.observations()) {
            int color = observation.detected() ? 0xFF50FF50 : observation.visible() ? 0xFFFFFF40 : 0xFFFF5050;
            String text = String.format("%s %.0f/80 %s LOS=%s", bandName(observation.band()), observation.points(),
                observation.detected() ? "DETECTED" : "", observation.visible());
            billboard(font, poseStack, cameraPos, observation.targetPos().add(0, 0.5, 0), text, 0, color, buffers);
        }
    }

    private static String bandName(int band) {
        return switch (band) {
            case 0 -> "FOCUSED";
            case 1 -> "PERIPHERAL";
            case 2 -> "OUTSIDE";
            default -> "RANGE";
        };
    }

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 position, String text,
                                  int y, int color, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 relative = position.subtract(cameraPos);
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        font.drawInBatch(text, -font.width(text) / 2.0f, y, color, false, poseStack.last().pose(), buffers,
            Font.DisplayMode.NORMAL, 0, 15728880);
        poseStack.popPose();
    }
}

package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.SpacingDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpacingDebugRenderer {

    private static boolean enabled = false;
    private static final Map<UUID, SpacingDebugPacket.SpacingDebugEntry> entries = new HashMap<>();

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void receivePacket(SpacingDebugPacket packet) {
        enabled = packet.isEnabled();
        entries.clear();
        for (SpacingDebugPacket.SpacingDebugEntry entry : packet.getEntries()) {
            entries.put(entry.soldierUUID, entry);
        }
    }

    public static void render(PoseStack poseStack, Camera camera) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 cameraPos = camera.getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        Matrix4f matrix = poseStack.last().pose();

        for (Map.Entry<UUID, SpacingDebugPacket.SpacingDebugEntry> mapEntry : entries.entrySet()) {
            SpacingDebugPacket.SpacingDebugEntry entry = mapEntry.getValue();

            // Find the soldier entity on the client
            Entity entity = null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof SoldierEntity && e.getUUID().equals(entry.soldierUUID)) {
                    entity = e;
                    break;
                }
            }
            if (entity == null) continue;

            BlockPos raw = entry.rawTarget;
            BlockPos nav = entry.navigationTarget;

            // Raw target: green box
            double rx = raw.getX() + 0.5 - cameraPos.x;
            double ry = raw.getY() + 0.5 - cameraPos.y;
            double rz = raw.getZ() + 0.5 - cameraPos.z;

            // Soldier position relative to camera
            Vec3 soldierPos = entity.position();
            double sx = soldierPos.x - cameraPos.x;
            double sy = soldierPos.y - cameraPos.y + 1.0;
            double sz = soldierPos.z - cameraPos.z;

            // Navigation target relative to camera
            double nx = nav.getX() + 0.5 - cameraPos.x;
            double ny = nav.getY() + 0.5 - cameraPos.y;
            double nz = nav.getZ() + 0.5 - cameraPos.z;

            BufferBuilder buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

            // Soldier to navigation target (cyan)
            buffer.vertex(matrix, (float) sx, (float) sy, (float) sz).color(0, 255, 255, 200).endVertex();
            buffer.vertex(matrix, (float) nx, (float) ny, (float) nz).color(0, 255, 255, 200).endVertex();

            // Raw target to navigation target (white dashed feel)
            buffer.vertex(matrix, (float) rx, (float) ry, (float) rz).color(255, 255, 255, 150).endVertex();
            buffer.vertex(matrix, (float) nx, (float) ny, (float) nz).color(255, 255, 255, 150).endVertex();

            tesselator.end();

            // Box at raw target (green)
            buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderBox(buffer, matrix, rx - 0.3, ry - 0.3, rz - 0.3, rx + 0.3, ry + 0.3, rz + 0.3, 0, 255, 0);
            tesselator.end();

            // Box at navigation target (cyan)
            buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderBox(buffer, matrix, nx - 0.3, ny - 0.3, nz - 0.3, nx + 0.3, ny + 0.3, nz + 0.3, 0, 255, 255);
            tesselator.end();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderBox(BufferBuilder buffer, Matrix4f matrix,
                                   double x1, double y1, double z1,
                                   double x2, double y2, double z2,
                                   int r, int g, int b) {
        int a = 180;
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y1, (float)z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1, (float)y2, (float)z2).color(r, g, b, a).endVertex();
    }
}
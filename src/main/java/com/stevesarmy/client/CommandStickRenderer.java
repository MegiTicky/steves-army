package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class CommandStickRenderer {

    private static final int SELECTED_R = 255, SELECTED_G = 255, SELECTED_B = 255, SELECTED_A = 255;

    public static void render(PoseStack poseStack, Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        CommandStickState state = CommandStickState.get();
        if (!state.isActive()) return;

        Vec3 cameraPos = camera.getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof SoldierEntity soldier)) continue;

            if (state.isSelected(entity.getId())) {
                renderBox(buffer, matrix, cameraPos, soldier, SELECTED_R, SELECTED_G, SELECTED_B, SELECTED_A);
            }
        }

        tesselator.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderBox(BufferBuilder buffer, Matrix4f matrix, Vec3 camera,
                                  SoldierEntity soldier, int r, int g, int b, int a) {
        AABB box = soldier.getBoundingBox().inflate(0.05).move(-camera.x, -camera.y, -camera.z);

        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;

        // Bottom face
        line(buffer, matrix, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buffer, matrix, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buffer, matrix, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buffer, matrix, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face
        line(buffer, matrix, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buffer, matrix, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buffer, matrix, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buffer, matrix, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // Vertical edges
        line(buffer, matrix, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buffer, matrix, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buffer, matrix, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buffer, matrix, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             int r, int g, int b, int a) {
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
    }
}

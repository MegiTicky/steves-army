package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.client.FireTeamScopeState;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

public class FireTeamOutlineRenderer {

    public static void render(PoseStack poseStack, Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        FireTeam selectedScope = FireTeamScopeState.INSTANCE.getCurrentScope();
        if (selectedScope == FireTeam.ALL) return;

        Vec3 cameraPos = camera.getPosition();
        AABB searchArea = new AABB(cameraPos, cameraPos).inflate(64);

        List<SoldierEntity> soldiers = mc.level.getEntitiesOfClass(
            SoldierEntity.class,
            searchArea,
            s -> s.isAlive() && s.isOwnedBy(mc.player)
        );

        if (soldiers.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        for (SoldierEntity soldier : soldiers) {
            if (soldier.getFireTeam() != selectedScope) continue;

            int r = 255; int g = 255; int b = 255; int a = 200;

            AABB bb = soldier.getBoundingBox();
            double x1 = bb.minX - cameraPos.x;
            double y1 = bb.minY - cameraPos.y;
            double z1 = bb.minZ - cameraPos.z;
            double x2 = bb.maxX - cameraPos.x;
            double y2 = bb.maxY - cameraPos.y;
            double z2 = bb.maxZ - cameraPos.z;

            // bottom
            buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y1, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y1, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y1, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();

            // top
            buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y2, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y2, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();

            // verticals
            buffer.vertex(matrix, (float)x1, (float)y1, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y2, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y1, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y2, (float)z1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y1, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x2, (float)y2, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y1, (float)z2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)x1, (float)y2, (float)z2).color(r, g, b, a).endVertex();
        }

        tesselator.end();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
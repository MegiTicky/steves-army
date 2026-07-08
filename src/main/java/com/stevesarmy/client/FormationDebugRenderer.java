package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.cover.FormationDebugManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;

public class FormationDebugRenderer {
    
    public static void render(PoseStack poseStack, Camera camera) {
        if (!FormationDebugManager.isVisualizationEnabled()) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        
        Vec3 cameraPos = camera.getPosition();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        
        renderFormationTargets(buffer, poseStack, cameraPos, mc.level, tesselator, mc);
        
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
    
    private static void renderFormationTargets(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, 
                                                Level level, Tesselator tesselator, Minecraft mc) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                mc.player.getBoundingBox().inflate(50))) {
            
            FormationDebugManager.FormationSoldierData data = FormationDebugManager.getSoldierData(soldier.getId());
            if (data == null) continue;
            
            Vec3 soldierPos = soldier.position();
            double sx = soldierPos.x - cameraPos.x;
            double sy = soldierPos.y - cameraPos.y + 1.0;
            double sz = soldierPos.z - cameraPos.z;
            
            if (data.targetPos != null) {
                double tx = data.targetPos.getX() + 0.5 - cameraPos.x;
                double ty = data.targetPos.getY() + 0.5 - cameraPos.y;
                double tz = data.targetPos.getZ() + 0.5 - cameraPos.z;
                
                buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                buffer.vertex(matrix, (float)sx, (float)sy, (float)sz).color(255, 255, 0, 200).endVertex();
                buffer.vertex(matrix, (float)tx, (float)ty, (float)tz).color(255, 255, 0, 200).endVertex();
                tesselator.end();
                
                renderTargetBox(buffer, poseStack, cameraPos, data.targetPos, tesselator, 255, 255, 0);
            }
            
            if (data.anchorPos != null && data.hasLeader) {
                double ax = data.anchorPos.getX() + 0.5 - cameraPos.x;
                double ay = data.anchorPos.getY() + 0.5 - cameraPos.y;
                double az = data.anchorPos.getZ() + 0.5 - cameraPos.z;
                
                buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                buffer.vertex(matrix, (float)sx, (float)sy, (float)sz).color(0, 255, 255, 150).endVertex();
                buffer.vertex(matrix, (float)ax, (float)ay, (float)az).color(0, 255, 255, 150).endVertex();
                tesselator.end();
                
                renderTargetBox(buffer, poseStack, cameraPos, data.anchorPos, tesselator, 0, 255, 255);
            }
            
            if (data.forwardDirection != null) {
                double fdx = data.forwardDirection.x * 3.0;
                double fdz = data.forwardDirection.z * 3.0;
                
                buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                buffer.vertex(matrix, (float)sx, (float)sy, (float)sz).color(255, 128, 0, 200).endVertex();
                buffer.vertex(matrix, (float)(sx + fdx), (float)sy, (float)(sz + fdz)).color(255, 128, 0, 200).endVertex();
                tesselator.end();
            }
        }
    }
    
    private static void renderTargetBox(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos,
                                         BlockPos pos, Tesselator tesselator, int r, int g, int b) {
        Matrix4f matrix = poseStack.last().pose();
        
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        int a = 180;
        
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        
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
        
        tesselator.end();
    }
}
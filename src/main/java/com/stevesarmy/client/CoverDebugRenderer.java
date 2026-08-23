package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.PeekController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Set;

public class CoverDebugRenderer {
    
    public static void render(PoseStack poseStack, Camera camera) {
        if (!CoverDebugManager.isVisualizationEnabled()) {
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
        
        if (CoverDebugManager.isShowSolidBlocks()) {
            renderSolidBlocks(buffer, poseStack, cameraPos, mc.player, mc.level, tesselator);
        }
        
        List<CoverPoint> coverPoints = CoverDebugManager.getCoverPoints();
        
        if (!coverPoints.isEmpty()) {
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (CoverPoint cp : coverPoints) {
                renderCoverPointBox(buffer, poseStack, cameraPos, cp);
            }
            tesselator.end();
            
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (CoverPoint cp : coverPoints) {
                renderProtectedDirections(buffer, poseStack, cameraPos, cp);
            }
            tesselator.end();
            
            if (CoverDebugManager.isShowRays() && CoverDebugManager.getThreatEntity() != null) {
                buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
                for (CoverPoint cp : coverPoints) {
                    renderRaycastLines(buffer, poseStack, cameraPos, cp, CoverDebugManager.getThreatEntity());
                }
                tesselator.end();
            }
        }
        
        CoverPoint best = CoverDebugManager.getBestCoverPoint();
        if (best != null) {
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderBestCoverOutline(buffer, poseStack, cameraPos, best);
            tesselator.end();
        }
        
        LivingEntity threat = CoverDebugManager.getThreatEntity();
        if (threat != null) {
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderThreatIndicator(buffer, poseStack, cameraPos, threat);
            tesselator.end();
        }
        
        if (CoverDebugManager.isShowSoldierCover()) {
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderSoldierCoverVisualization(buffer, poseStack, cameraPos, mc.level, tesselator);
            tesselator.end();
            
            MultiBufferSource.BufferSource soldierBufferSource = mc.renderBuffers().bufferSource();
            renderSoldierCoverLabels(poseStack, cameraPos, mc.level, mc, soldierBufferSource);
            soldierBufferSource.endBatch();

            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderCqbDebugPaths(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();
            
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderTopCoversVisualization(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();
            
            MultiBufferSource.BufferSource topCoversBufferSource = mc.renderBuffers().bufferSource();
            renderTopCoversLabels(poseStack, cameraPos, mc.level, mc, topCoversBufferSource);
            topCoversBufferSource.endBatch();
        }

        if (CoverDebugManager.isShowMachineGunners()) {
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderMachineGunnerVisualization(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();

            MultiBufferSource.BufferSource mgBufferSource = mc.renderBuffers().bufferSource();
            renderMachineGunnerLabels(poseStack, cameraPos, mc.level, mc, mgBufferSource);
            mgBufferSource.endBatch();
        }

        if (CoverDebugManager.isShowMachineGunners()
            && CoverDebugManager.getMachineGunnerEvaluation() != null) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderMachineGunnerEvaluation(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();

            MultiBufferSource.BufferSource evaluationBufferSource = mc.renderBuffers().bufferSource();
            renderMachineGunnerEvaluationLabels(poseStack, cameraPos, mc.level, mc, evaluationBufferSource);
            evaluationBufferSource.endBatch();
        }

        if (CoverDebugManager.isShowMachineGunnerProtection()
            && CoverDebugManager.getMachineGunnerEvaluation() != null) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderMachineGunnerProtection(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();

            MultiBufferSource.BufferSource protectionBufferSource = mc.renderBuffers().bufferSource();
            renderMachineGunnerProtectionLabels(poseStack, cameraPos, mc.level, mc, protectionBufferSource);
            protectionBufferSource.endBatch();
        }
        
        if (CoverDebugManager.isShowPeekCandidates()) {
            // Draw candidate boxes (first batch)
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderPeekCandidateVisualization(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();
            
            // Draw LOS rays (second batch with explicit shader)
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderPeekCandidateRays(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();
            
            // Draw cover block outline AFTER candidates so it's on top
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderPeekCoverBlockOverlay(buffer, poseStack, cameraPos, mc.level);
            tesselator.end();
            
            MultiBufferSource.BufferSource peekBufferSource = mc.renderBuffers().bufferSource();
            renderPeekCandidateLabels(poseStack, cameraPos, mc.level, mc, peekBufferSource);
            peekBufferSource.endBatch();
        }
        
MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        for (CoverPoint cp : coverPoints) {
            renderQualityLabel(poseStack, cameraPos, cp, mc, bufferSource);
        }
        bufferSource.endBatch();

        if (CoverDebugManager.isShowSearchCenter() && CoverDebugManager.getSearchCenterPos() != null) {
            BlockPos searchCenter = CoverDebugManager.getSearchCenterPos();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            renderSearchCenterBox(buffer, poseStack, cameraPos, searchCenter);
            tesselator.end();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
    
    private static void renderSolidBlocks(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, 
                                          Player player, Level level, Tesselator tesselator) {
        BlockPos playerPos = player.blockPosition();
        int radius = 10;
        
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();
        
        int solidCount = 0;
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius + 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    
                    if (state.isCollisionShapeFullBlock(level, checkPos) && isBlockValidCover(state)) {
                        renderBlockOutline(buffer, matrix, cameraPos, checkPos, 0, 150, 0, 100);
                        solidCount++;
                        
                        if (solidCount > 200) break;
                    }
                }
                if (solidCount > 200) break;
            }
            if (solidCount > 200) break;
        }
        
        tesselator.end();
    }
    
    private static boolean isBlockValidCover(BlockState state) {
        if (!state.isSolid()) {
            return false;
        }
        
        net.minecraft.world.level.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.IronBarsBlock ||
            block instanceof net.minecraft.world.level.block.GlassBlock ||
            block instanceof net.minecraft.world.level.block.StainedGlassBlock ||
            block instanceof net.minecraft.world.level.block.TintedGlassBlock) {
            return false;
        }
        
        return true;
    }
    
    private static void renderRaycastLines(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos,
                                           CoverPoint cp, LivingEntity threat) {
        if (cp.getQuality() <= 0.0f) return;
        
        Matrix4f matrix = poseStack.last().pose();
        
        Vec3 threatEye = threat.getEyePosition();
        double threatRelX = threatEye.x - cameraPos.x;
        double threatRelY = threatEye.y - cameraPos.y;
        double threatRelZ = threatEye.z - cameraPos.z;
        
        BlockPos pos = cp.getPosition();
        
        double[] testHeights = {0.25, 0.75, 1.1, 1.62};
        int[] colors = {
            0xFF6060,  // red - feet
            0xFFAA60,  // orange - waist  
            0xAAFF60,  // yellow-green - chest
            0x60FFAA   // cyan - eyes
        };
        
        for (int i = 0; i < testHeights.length; i++) {
            double targetRelX = pos.getX() - cameraPos.x + 0.5;
            double targetRelY = pos.getY() - cameraPos.y + testHeights[i];
            double targetRelZ = pos.getZ() - cameraPos.z + 0.5;
            
            int color = colors[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            int a = 150;
            
            buffer.vertex(matrix, (float)threatRelX, (float)threatRelY, (float)threatRelZ).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)targetRelX, (float)targetRelY, (float)targetRelZ).color(r, g, b, a).endVertex();
        }
    }
    
    private static void renderBlockOutline(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, 
                                           BlockPos pos, int r, int g, int b, int a) {
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        
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
    
    private static void renderCoverPointBox(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, CoverPoint cp) {
        BlockPos pos = cp.getPosition();
        CoverType type = cp.getType();
        
        int color = getTypeColor(type);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = 200;
        
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        
        Matrix4f matrix = poseStack.last().pose();
        
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

    private static void renderSearchCenterBox(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, BlockPos searchCenter) {
        double x1 = searchCenter.getX() - cameraPos.x;
        double y1 = searchCenter.getY() - cameraPos.y;
        double z1 = searchCenter.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;

        Matrix4f matrix = poseStack.last().pose();

        // Cyan with slight outward offset for visibility
        int r = 0, g = 255, b = 255, a = 200;
        float ox = 0.05f, oy = 0.05f, oz = 0.05f;

        // Bottom face
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        // Top face
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        // Vertical edges
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z1 - oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x2 + ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y1 - oy, (float)z2 + oz).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)x1 - ox, (float)y2 + oy, (float)z2 + oz).color(r, g, b, a).endVertex();
    }

    private static void renderProtectedDirections(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, CoverPoint cp) {
        Set<Direction> directions = cp.getProtectedDirections();
        if (directions.isEmpty()) {
            return;
        }
        
        BlockPos pos = cp.getPosition();
        Matrix4f matrix = poseStack.last().pose();
        
        double centerX = pos.getX() - cameraPos.x + 0.5;
        double centerY = pos.getY() - cameraPos.y + 0.5;
        double centerZ = pos.getZ() - cameraPos.z + 0.5;
        double arrowLength = 0.6;
        
        int r = 0;
        int g = 255;
        int b = 100;
        int a = 255;
        
        for (Direction dir : directions) {
            double endX = centerX + dir.getStepX() * arrowLength;
            double endZ = centerZ + dir.getStepZ() * arrowLength;
            
            buffer.vertex(matrix, (float)centerX, (float)centerY, (float)centerZ).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)endX, (float)centerY, (float)endZ).color(r, g, b, a).endVertex();
        }
    }
    
    private static void renderBestCoverOutline(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, CoverPoint cp) {
        BlockPos pos = cp.getPosition();
        
        int r = 255;
        int g = 128;
        int b = 0;
        int a = 255;
        
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        
        Matrix4f matrix = poseStack.last().pose();
        
        for (int i = 0; i < 2; i++) {
            double offset = i * 0.1;
            double ox1 = x1 - offset;
            double oy1 = y1 - offset;
            double oz1 = z1 - offset;
            double ox2 = x2 + offset;
            double oy2 = y2 + offset;
            double oz2 = z2 + offset;
            
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
        }
    }
    
    private static void renderThreatIndicator(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, LivingEntity threat) {
        Vec3 threatPos = threat.position();
        
        double x = threatPos.x - cameraPos.x;
        double y = threatPos.y - cameraPos.y;
        double z = threatPos.z - cameraPos.z;
        
        Matrix4f matrix = poseStack.last().pose();
        
        int r = 255;
        int g = 0;
        int b = 0;
        int a = 255;
        
        double size = 0.5;
        
        buffer.vertex(matrix, (float)(x - size), (float)y, (float)(z - size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x + size), (float)y, (float)(z - size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x + size), (float)y, (float)(z - size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x + size), (float)y, (float)(z + size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x + size), (float)y, (float)(z + size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x - size), (float)y, (float)(z + size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x - size), (float)y, (float)(z + size)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(x - size), (float)y, (float)(z - size)).color(r, g, b, a).endVertex();
    }
    
    private static void renderQualityLabel(PoseStack poseStack, Vec3 cameraPos, CoverPoint cp, Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null) {
            return;
        }
        
        BlockPos pos = cp.getPosition();
        float quality = cp.getQuality();
        CoverType type = cp.getType();
        
        String label = String.format("%.1f", quality) + " " + type.name().charAt(0);
        
        double x = pos.getX() - cameraPos.x + 0.5;
        double y = pos.getY() - cameraPos.y + 1.3;
        double z = pos.getZ() - cameraPos.z + 0.5;
        
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        
        net.minecraft.client.gui.Font font = mc.font;
        int color = 0xFFFFFFFF;
        font.drawInBatch(label, -font.width(label) / 2.0f, 0, color, false, 
                         poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
        
        poseStack.popPose();
    }
    
    private static int getTypeColor(CoverType type) {
        switch (type) {
            case FULL: return 0x00FF00;
            case HALF: return 0xFFFF00;
            case CONCEALMENT: return 0x808080;
            default: return 0x404040;
        }
    }
    
    private static void renderSoldierCoverVisualization(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, 
                                                         Level level, Tesselator tesselator) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, 
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            int stateOrdinal = soldier.getSyncedCoverState();
            CoverBehaviorManager.CoverState state = CoverBehaviorManager.CoverState.values()[stateOrdinal];
            
            BlockPos currentPos = soldier.getSyncedCoverCurrentPos();
            BlockPos targetPos = soldier.getSyncedCoverTargetPos();
            BlockPos lastPos = soldier.getSyncedCoverLastPos();
            
            int currentTypeOrdinal = soldier.getSyncedCoverCurrentType();
            float currentQuality = soldier.getSyncedCoverCurrentQuality();
            int targetTypeOrdinal = soldier.getSyncedCoverTargetType();
            float targetQuality = soldier.getSyncedCoverTargetQuality();
            
            Vec3 soldierPos = soldier.position();
            double soldierRelX = soldierPos.x - cameraPos.x;
            double soldierRelY = soldierPos.y - cameraPos.y + 1.0;
            double soldierRelZ = soldierPos.z - cameraPos.z;
            
            int stateColor = getStateColor(state);
            int r = (stateColor >> 16) & 0xFF;
            int g = (stateColor >> 8) & 0xFF;
            int b = stateColor & 0xFF;
            int a = 255;
            
            double indicatorSize = 0.3;
            buffer.vertex(matrix, (float)(soldierRelX - indicatorSize), (float)soldierRelY, (float)(soldierRelZ - indicatorSize)).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)(soldierRelX + indicatorSize), (float)soldierRelY, (float)(soldierRelZ + indicatorSize)).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)(soldierRelX + indicatorSize), (float)soldierRelY, (float)(soldierRelZ - indicatorSize)).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, (float)(soldierRelX - indicatorSize), (float)soldierRelY, (float)(soldierRelZ + indicatorSize)).color(r, g, b, a).endVertex();
            
            if (!targetPos.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, targetPos, cameraPos, 255, 255, 0, 150);
                
                double coverRelX = targetPos.getX() - cameraPos.x + 0.5;
                double coverRelY = targetPos.getY() - cameraPos.y + 0.5;
                double coverRelZ = targetPos.getZ() - cameraPos.z + 0.5;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.5), (float)soldierRelZ).color(255, 255, 0, a).endVertex();
                buffer.vertex(matrix, (float)coverRelX, (float)coverRelY, (float)coverRelZ).color(255, 255, 0, a).endVertex();
            }
            
            if (!currentPos.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, currentPos, cameraPos, 0, 255, 0, 150);
                
                double coverRelX = currentPos.getX() - cameraPos.x + 0.5;
                double coverRelY = currentPos.getY() - cameraPos.y + 0.5;
                double coverRelZ = currentPos.getZ() - cameraPos.z + 0.5;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.6), (float)soldierRelZ).color(0, 255, 0, a).endVertex();
                buffer.vertex(matrix, (float)coverRelX, (float)coverRelY, (float)coverRelZ).color(0, 255, 0, a).endVertex();
            }
            
            if (!lastPos.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, lastPos, cameraPos, 128, 128, 128, 100);
                
                double coverRelX = lastPos.getX() - cameraPos.x + 0.5;
                double coverRelY = lastPos.getY() - cameraPos.y + 0.5;
                double coverRelZ = lastPos.getZ() - cameraPos.z + 0.5;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.7), (float)soldierRelZ).color(128, 128, 128, a).endVertex();
                buffer.vertex(matrix, (float)coverRelX, (float)coverRelY, (float)coverRelZ).color(128, 128, 128, a).endVertex();
            }
            
            BlockPos peekPos = soldier.getSyncedPeekPosition();
            int peekStateOrdinal = soldier.getSyncedPeekState();
            PeekController.State peekState = PeekController.State.values()[peekStateOrdinal];
            
            if (!peekPos.equals(BlockPos.ZERO)) {
                int peekColor = getPeekStateColor(peekState);
                int pr = (peekColor >> 16) & 0xFF;
                int pg = (peekColor >> 8) & 0xFF;
                int pb = peekColor & 0xFF;
                
                renderCoverBlockBox(buffer, matrix, peekPos, cameraPos, pr, pg, pb, 200);
                
                double peekRelX = peekPos.getX() - cameraPos.x + 0.5;
                double peekRelY = peekPos.getY() - cameraPos.y + 0.5;
                double peekRelZ = peekPos.getZ() - cameraPos.z + 0.5;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.8), (float)soldierRelZ).color(pr, pg, pb, a).endVertex();
                buffer.vertex(matrix, (float)peekRelX, (float)peekRelY, (float)peekRelZ).color(pr, pg, pb, a).endVertex();
                
                // Render LOS raycast from peek position eye to nearby target entity
                // Client-side target is often null, so find nearby TargetEntity
                LivingEntity losTarget = soldier.getTarget();
                if (losTarget == null) {
                    double searchRange = 20.0;
                    for (net.minecraft.world.entity.LivingEntity entity : level.getEntitiesOfClass(
                            net.minecraft.world.entity.LivingEntity.class, 
                            soldier.getBoundingBox().inflate(searchRange))) {
                        if (entity != soldier && entity.isAlive() && 
                            entity.getType() == com.stevesarmy.registry.ModEntities.TARGET.get()) {
                            losTarget = entity;
                            break;
                        }
                    }
                }
                
                if (losTarget != null) {
                    Vec3 peekEye = new Vec3(peekPos.getX() + 0.5, soldier.getY() + 1.62, peekPos.getZ() + 0.5);
                    Vec3 targetEye = new Vec3(losTarget.getX(), losTarget.getEyeY(), losTarget.getZ());
                    
                    double peekEyeRelX = peekEye.x - cameraPos.x;
                    double peekEyeRelY = peekEye.y - cameraPos.y;
                    double peekEyeRelZ = peekEye.z - cameraPos.z;
                    double targetEyeRelX = targetEye.x - cameraPos.x;
                    double targetEyeRelY = targetEye.y - cameraPos.y;
                    double targetEyeRelZ = targetEye.z - cameraPos.z;
                    
                    net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
                        peekEye, targetEye, 
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, 
                        net.minecraft.world.level.ClipContext.Fluid.NONE, 
                        soldier);
                    net.minecraft.world.phys.HitResult result = level.clip(context);
                    boolean hasLOS = result.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
                    
                    int lr = hasLOS ? 0 : 255;
                    int lg = hasLOS ? 255 : 0;
                    int lb = 0;
                    
                    buffer.vertex(matrix, (float)peekEyeRelX, (float)peekEyeRelY, (float)peekEyeRelZ).color(lr, lg, lb, a).endVertex();
                    buffer.vertex(matrix, (float)targetEyeRelX, (float)targetEyeRelY, (float)targetEyeRelZ).color(lr, lg, lb, a).endVertex();
                    
                    if (!hasLOS && result.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        Vec3 hitPoint = result.getLocation();
                        double hitRelX = hitPoint.x - cameraPos.x;
                        double hitRelY = hitPoint.y - cameraPos.y;
                        double hitRelZ = hitPoint.z - cameraPos.z;
                        
                        float hitSize = 0.2f;
                        buffer.vertex(matrix, (float)(hitRelX - hitSize), (float)hitRelY, (float)hitRelZ).color(255, 255, 0, a).endVertex();
                        buffer.vertex(matrix, (float)(hitRelX + hitSize), (float)hitRelY, (float)hitRelZ).color(255, 255, 0, a).endVertex();
                        buffer.vertex(matrix, (float)hitRelX, (float)(hitRelY - hitSize), (float)hitRelZ).color(255, 255, 0, a).endVertex();
                        buffer.vertex(matrix, (float)hitRelX, (float)(hitRelY + hitSize), (float)hitRelZ).color(255, 255, 0, a).endVertex();
                        buffer.vertex(matrix, (float)hitRelX, (float)hitRelY, (float)(hitRelZ - hitSize)).color(255, 255, 0, a).endVertex();
                        buffer.vertex(matrix, (float)hitRelX, (float)hitRelY, (float)(hitRelZ + hitSize)).color(255, 255, 0, a).endVertex();
                    }
                }
            }
            
            // Render all candidate peek positions for full cover soldiers (using synced data)
            int coverTypeOrdinal = soldier.getSyncedCoverCurrentType();
            com.stevesarmy.combat.cover.CoverType coverType = coverTypeOrdinal >= 0 && coverTypeOrdinal < com.stevesarmy.combat.cover.CoverType.values().length 
                ? com.stevesarmy.combat.cover.CoverType.values()[coverTypeOrdinal] 
                : com.stevesarmy.combat.cover.CoverType.NONE;
            
            if (state == CoverBehaviorManager.CoverState.IN_COVER && !currentPos.equals(BlockPos.ZERO) && coverType == com.stevesarmy.combat.cover.CoverType.FULL) {
                BlockPos coverPos = currentPos;
                
                // Get protected directions from synced data
                java.util.Set<net.minecraft.core.Direction> protectedDirs = null;
                CoverPoint currentCover = soldier.getCoverBehaviorManager().getCurrentCover();
                if (currentCover != null) {
                    protectedDirs = currentCover.getProtectedDirections();
                }
                
                if (protectedDirs == null || protectedDirs.isEmpty()) {
                    protectedDirs = java.util.Set.of();
                }
                
                Vec3 threatDir = soldier.getSyncedThreatDirection();
                
                // Iterate 4 cardinal directions (same logic as computePeekPositionStatic)
                for (net.minecraft.core.Direction peekDir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    if (protectedDirs.contains(peekDir)) {
                        continue;
                    }
                    
                    BlockPos candidate = coverPos.relative(peekDir);
                    
                    if (!com.stevesarmy.combat.cover.CoverFinder.isValidPeekPosition(candidate, level)) {
                        continue;
                    }
                    
                    // Cone raycast parameters
                    final int RAY_COUNT = 7;
                    final double CONE_HALF_ANGLE_DEG = 30.0;
                    final double MAX_RAY_DISTANCE = 20.0;
                    final double MIN_OPENING_DISTANCE = 5.0;
                    
                    int validRays = 0;
                    double totalCoverage = 0.0;
                    
                    Vec3 candidateEye = new Vec3(candidate.getX() + 0.5, soldier.getY() + 1.62, candidate.getZ() + 0.5);
                    
                    double candRelX = candidate.getX() - cameraPos.x + 0.5;
                    double candRelY = candidate.getY() - cameraPos.y + 1.62;
                    double candRelZ = candidate.getZ() - cameraPos.z + 0.5;
                    
                    // Render cone rays from candidate position
                    for (int ri = 0; ri < RAY_COUNT; ri++) {
                        double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * ri / (RAY_COUNT - 1));
                        
                        Vec3 rayDir = com.stevesarmy.combat.cover.CoverFinder.rotateVectorY(threatDir != null ? threatDir.normalize() : new Vec3(1, 0, 0), angleOffset);
                        
                        Vec3 rayEnd = candidateEye.add(rayDir.scale(MAX_RAY_DISTANCE));
                        net.minecraft.world.level.ClipContext rayContext = new net.minecraft.world.level.ClipContext(
                            candidateEye, rayEnd,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, null);
                        net.minecraft.world.phys.HitResult rayResult = level.clip(rayContext);
                        
                        double distance;
                        Vec3 rayHitPoint;
                        if (rayResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                            distance = MAX_RAY_DISTANCE;
                            rayHitPoint = rayEnd;
                        } else {
                            distance = candidateEye.distanceTo(rayResult.getLocation());
                            rayHitPoint = rayResult.getLocation();
                        }
                        
                        boolean isValid = distance >= MIN_OPENING_DISTANCE;
                        if (isValid) {
                            validRays++;
                            totalCoverage += distance / MAX_RAY_DISTANCE;
                        }
                        
                        int rr = isValid ? 0 : 255;
                        int rg = isValid ? 255 : 0;
                        int rb = 0;
                        
                        double rayHitRelX = rayHitPoint.x - cameraPos.x;
                        double rayHitRelY = rayHitPoint.y - cameraPos.y;
                        double rayHitRelZ = rayHitPoint.z - cameraPos.z;
                        
                        buffer.vertex(matrix, (float)candRelX, (float)candRelY, (float)candRelZ).color(rr, rg, rb, a).endVertex();
                        buffer.vertex(matrix, (float)rayHitRelX, (float)rayHitRelY, (float)rayHitRelZ).color(rr, rg, rb, a).endVertex();
                    }
                    
                    // Calculate cone coverage score
                    float coneScore = 0.0f;
                    if (validRays > 0) {
                        float avgCoverage = (float)(totalCoverage / validRays);
                        float validRatio = (float)validRays / RAY_COUNT;
                        coneScore = avgCoverage * validRatio;
                    }
                    
                    // Check angle between peek direction and threat
                    Vec3 peekCenter = candidate.getCenter();
                    Vec3 toThreat = threatDir != null ? threatDir.normalize() : new Vec3(1, 0, 0);
                    Vec3 fromPeekToCover = new Vec3(
                        coverPos.getX() + 0.5 - peekCenter.x,
                        0,
                        coverPos.getZ() + 0.5 - peekCenter.z
                    ).normalize();
                    
                    double dot = toThreat.dot(fromPeekToCover);
                    dot = Math.max(-1.0, Math.min(1.0, dot));
                    double angleBetween = Math.toDegrees(Math.acos(dot));
                    
                    boolean validAngle = angleBetween >= 45 && angleBetween <= 135;
                    float angleScore = validAngle ? 1.0f - (float)Math.abs(angleBetween - 90) / 90 : 0.0f;
                    float finalScore = angleScore * coneScore;
                    
                    // Render score indicator at candidate position
                    int sr, sg, sb;
                    if (finalScore > 0.3f) {
                        sr = 0; sg = 255; sb = 0;
                    } else if (finalScore > 0.1f) {
                        sr = 255; sg = 255; sb = 0;
                    } else {
                        sr = 255; sg = 0; sb = 0;
                    }
                    
                    float boxSize = 0.15f;
                    buffer.vertex(matrix, (float)(candRelX - boxSize), (float)candRelY, (float)(candRelZ - boxSize)).color(sr, sg, sb, a).endVertex();
                    buffer.vertex(matrix, (float)(candRelX + boxSize), (float)candRelY, (float)(candRelZ + boxSize)).color(sr, sg, sb, a).endVertex();
                    buffer.vertex(matrix, (float)(candRelX - boxSize), (float)candRelY, (float)(candRelZ + boxSize)).color(sr, sg, sb, a).endVertex();
                    buffer.vertex(matrix, (float)(candRelX + boxSize), (float)candRelY, (float)(candRelZ - boxSize)).color(sr, sg, sb, a).endVertex();
                }
            }
            
            LivingEntity target = soldier.getTarget();
            if (target != null) {
                Vec3 targetPosEntity = target.position();
                double targetRelX = targetPosEntity.x - cameraPos.x;
                double targetRelY = targetPosEntity.y - cameraPos.y + 1.0;
                double targetRelZ = targetPosEntity.z - cameraPos.z;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.3), (float)soldierRelZ).color(255, 100, 0, a).endVertex();
                buffer.vertex(matrix, (float)targetRelX, (float)(targetRelY - 0.3), (float)targetRelZ).color(255, 100, 0, a).endVertex();
            }
            
            // Render threat direction as a bold arrow (multiple parallel lines)
            // Render threat direction using synced data from ThreatAwareness
            Vec3 threatDir = soldier.getSyncedThreatDirection();
            
            if (threatDir != null) {
                double arrowLength = 2.5;
                double arrowHeadSize = 0.3;
                
                // Arrow end point
                double arrowEndX = soldierRelX + threatDir.x * arrowLength;
                double arrowEndY = soldierRelY + threatDir.y * arrowLength;
                double arrowEndZ = soldierRelZ + threatDir.z * arrowLength;
                
                // Bold arrow - draw multiple parallel lines (3 offsets)
                int tr = 255, tg = 50, tb = 255; // Magenta/pink color for threat direction
                int ta = 255;
                
                // Draw 3 parallel lines for bold effect
                for (double offset : new double[]{0.0, 0.02, -0.02}) {
                    buffer.vertex(matrix, (float)(soldierRelX + offset), (float)(soldierRelY + offset), (float)(soldierRelZ + offset)).color(tr, tg, tb, ta).endVertex();
                    buffer.vertex(matrix, (float)(arrowEndX + offset), (float)(arrowEndY + offset), (float)(arrowEndZ + offset)).color(tr, tg, tb, ta).endVertex();
                }
                
                // Arrow head (perpendicular lines at the tip)
                // Compute perpendicular direction (in horizontal plane)
                double perpX = -threatDir.z;
                double perpZ = threatDir.x;
                
                // Arrow head lines (forming a V)
                double headBaseX = arrowEndX - threatDir.x * arrowHeadSize;
                double headBaseZ = arrowEndZ - threatDir.z * arrowHeadSize;
                
                buffer.vertex(matrix, (float)headBaseX, (float)arrowEndY, (float)headBaseZ).color(tr, tg, tb, ta).endVertex();
                buffer.vertex(matrix, (float)(headBaseX + perpX * arrowHeadSize), (float)arrowEndY, (float)(headBaseZ + perpZ * arrowHeadSize)).color(tr, tg, tb, ta).endVertex();
                
                buffer.vertex(matrix, (float)headBaseX, (float)arrowEndY, (float)headBaseZ).color(tr, tg, tb, ta).endVertex();
                buffer.vertex(matrix, (float)(headBaseX - perpX * arrowHeadSize), (float)arrowEndY, (float)(headBaseZ - perpZ * arrowHeadSize)).color(tr, tg, tb, ta).endVertex();
            }
        }
    }
    
    private static void renderCoverBlockBox(BufferBuilder buffer, Matrix4f matrix, BlockPos pos, Vec3 cameraPos, 
                                             int r, int g, int b, int a) {
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        
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
    
    private static void renderThreatPositionBox(BufferBuilder buffer, Matrix4f matrix, BlockPos pos, Vec3 cameraPos) {
        double x1 = pos.getX() - cameraPos.x;
        double y1 = pos.getY() - cameraPos.y;
        double z1 = pos.getZ() - cameraPos.z;
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;
        
        int r = 255, g = 128, b = 0, a = 200;
        
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
    
private static void renderSoldierCoverLabels(PoseStack poseStack, Vec3 cameraPos, Level level, 
                                                   Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null) return;
        
        net.minecraft.client.gui.Font font = mc.font;
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, 
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            int stateOrdinal = soldier.getSyncedCoverState();
            CoverBehaviorManager.CoverState state = CoverBehaviorManager.CoverState.values()[stateOrdinal];
            
            int peekStateOrdinal = soldier.getSyncedPeekState();
            PeekController.State peekState = PeekController.State.values()[peekStateOrdinal];
            
            Vec3 soldierPos = soldier.position();
            double x = soldierPos.x - cameraPos.x + 0.5;
            double y = soldierPos.y - cameraPos.y + 2.2;
            double z = soldierPos.z - cameraPos.z + 0.5;
            
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            
            int lineOffset = 0;
            
            String stateLabel = state.name();
            int stateColor = getStateColor(state);
            font.drawInBatch(stateLabel, -font.width(stateLabel) / 2.0f, lineOffset, stateColor | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;
            
            int peekStateColor = getPeekStateColor(peekState);
            PeekController peekCtrl = soldier.getPeekController();
            long peekTimeMs = peekCtrl.getTimeInCurrentState();
            long sinceLastPeekMs = peekCtrl.getTimeSinceLastPeek();
            String peekLabel = "Peek: " + peekState.name() + "(" + peekTimeMs + "ms,last=" + sinceLastPeekMs + "ms)";
            font.drawInBatch(peekLabel, -font.width(peekLabel) / 2.0f, lineOffset, peekStateColor | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;

            float suppression = soldier.getSyncedSuppressionLevel();
            String suppressionState = getSuppressionStateLabel(suppression);
            int nearbyPeekers = countNearbyFriendlyPeekers(soldier, level);
            float recovery = Math.max(0.0f, Math.min(1.0f, (0.90f - suppression) / 0.40f));
            float peekChance = suppression >= 0.90f ? 0.0f
                : suppression >= 0.50f ? CoverTacticalGoal.calculatePressuredPeekChance(suppression, nearbyPeekers)
                : 1.0f;
            String suppressionLabel = String.format("Supp: %s %.0f%% rec=%.0f%% peek=%.0f%% peekers=%d event=%d",
                suppressionState, suppression * 100.0f, recovery * 100.0f, peekChance * 100.0f,
                nearbyPeekers, soldier.getSyncedSuppressionEventSequence());
            font.drawInBatch(suppressionLabel, -font.width(suppressionLabel) / 2.0f, lineOffset,
                getSuppressionStateColor(suppression) | 0xFF000000, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;
            
            CoverPositionController ctrl = (CoverPositionController) soldier.getMoveControl();
            CoverPositionController.MovementResult moveResult = ctrl.getLastResult();
            Vec3 ctrlTarget = ctrl.getDebugTargetPos();
            double ctrlDist = ctrlTarget != null ?
                Math.sqrt(Math.pow(ctrlTarget.x - soldier.getX(), 2) + Math.pow(ctrlTarget.z - soldier.getZ(), 2)) : -1;
            Vec3 vel = soldier.getDeltaMovement();
            double velH = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            String intentLabel = "Result: " + moveResult.name() + " dist=" + String.format("%.2f", ctrlDist);
            String velLabel = "Vel: " + String.format("%.3f", velH) + " (" + String.format("%.2f", vel.x) + "," + String.format("%.2f", vel.z) + ")";
            String srcLabel = "Src: " + ctrl.getDebugMoveSource() + " / " + ctrl.getDebugMoveReason();
            int intentColor = moveResult == CoverPositionController.MovementResult.IN_PROGRESS ? 0xFFFF00 :
                              moveResult == CoverPositionController.MovementResult.REACHED_TARGET ? 0x00FF00 : 0xAAAAAA;
            font.drawInBatch(intentLabel, -font.width(intentLabel) / 2.0f, lineOffset, intentColor | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;
            font.drawInBatch(velLabel, -font.width(velLabel) / 2.0f, lineOffset, 0x00FFFF | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;
            font.drawInBatch(srcLabel, -font.width(srcLabel) / 2.0f, lineOffset, 0xFFAA00 | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;
            
            String modeLabel = soldier.getSquadMode().name();
            font.drawInBatch(modeLabel, -font.width(modeLabel) / 2.0f, lineOffset, 0xFFAAAAAA, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            lineOffset += 10;

            if (soldier.isTacticalReloading()) {
                String reloadLabel = "TACTICAL RELOAD";
                font.drawInBatch(reloadLabel, -font.width(reloadLabel) / 2.0f, lineOffset, 0xFFFF00, false,
                                 poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            } else if (soldier.isPreparingOrReloading()) {
                String reloadLabel = "RELOAD";
                font.drawInBatch(reloadLabel, -font.width(reloadLabel) / 2.0f, lineOffset, 0xFF8800, false,
                                 poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            }
            
            poseStack.popPose();
        }
    }

    private static void renderCqbDebugPaths(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, Level level) {
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            String encoded = soldier.getSyncedCqbPath();
            if (encoded == null || encoded.isEmpty()) continue;
            String[] nodes = encoded.split("\\|");
            Vec3 previous = soldier.position().subtract(cameraPos);
            for (String node : nodes) {
                String[] parts = node.split(":");
                if (parts.length != 3) continue;
                try {
                    Vec3 next = new Vec3(Integer.parseInt(parts[0]) + 0.5 - cameraPos.x,
                        Integer.parseInt(parts[1]) + 0.15 - cameraPos.y,
                        Integer.parseInt(parts[2]) + 0.5 - cameraPos.z);
                    buffer.vertex(poseStack.last().pose(), (float) previous.x, (float) previous.y, (float) previous.z)
                        .color(0, 200, 255, 220).endVertex();
                    buffer.vertex(poseStack.last().pose(), (float) next.x, (float) next.y, (float) next.z)
                        .color(0, 200, 255, 220).endVertex();
                    previous = next;
                } catch (NumberFormatException ignored) {
                    // A malformed debug sample must not break world rendering.
                }
            }
        }
    }

    private static void renderMachineGunnerVisualization(BufferBuilder buffer, PoseStack poseStack,
                                                         Vec3 cameraPos, Level level) {
        Matrix4f matrix = poseStack.last().pose();
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                player.getBoundingBox().inflate(64))) {
            BlockPos destination = mg.getMachineGunnerDebugPosition();
            BlockPos movementDestination = mg.getMachineGunnerDebugMovementPosition();
            BlockPos center = mg.getMachineGunnerDebugCenter();
            boolean active = mg.isMachineGunnerDebugActive();
            boolean fallback = mg.isMachineGunnerDebugFallback();
            float access = mg.getMachineGunnerDebugAccess();
            int color = access >= 0.25f ? 0 : 255;
            int green = access >= 0.25f ? 220 : 70;
            int blue = mg.getMachineGunnerDebugPosture() == 2 ? 255 : 40;

            if (destination != null && !destination.equals(BlockPos.ZERO)) {
                // Yellow is the authoritative physical cover target. Cyan/green
                // below is the route or controller destination currently being
                // consumed by movement.
                renderCoverBlockBox(buffer, matrix, destination, cameraPos, 255, 255, 0, 230);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(),
                    destination.getCenter().add(0, mg.getMachineGunnerDebugPosture() == 2 ? 0.45 : 1.6, 0),
                    255, 255, 0, 220);
            }
            if (movementDestination != null && !movementDestination.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, movementDestination, cameraPos, color, green, blue, 230);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(),
                    movementDestination.getCenter().add(0, mg.getMachineGunnerDebugPosture() == 2 ? 0.45 : 1.6, 0),
                    color, green, blue, 220);
            }
            if (center != null && !center.equals(BlockPos.ZERO)) {
                renderThreatPositionBox(buffer, matrix, center, cameraPos);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(), center.getCenter().add(0, 1.0, 0),
                    255, 170, 0, 150);
            }
        }
    }

    private static void renderDebugLine(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                        Vec3 from, Vec3 to, int r, int g, int b, int a) {
        buffer.vertex(matrix, (float)(from.x - cameraPos.x), (float)(from.y - cameraPos.y),
            (float)(from.z - cameraPos.z)).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float)(to.x - cameraPos.x), (float)(to.y - cameraPos.y),
            (float)(to.z - cameraPos.z)).color(r, g, b, a).endVertex();
    }

    private static void renderMachineGunnerLabels(PoseStack poseStack, Vec3 cameraPos, Level level,
                                                   Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null || mc.player == null) return;
        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                mc.player.getBoundingBox().inflate(64))) {
            BlockPos destination = mg.getMachineGunnerDebugPosition();
            BlockPos movementDestination = mg.getMachineGunnerDebugMovementPosition();
            BlockPos center = mg.getMachineGunnerDebugCenter();
            boolean active = mg.isMachineGunnerDebugActive();
            float access = mg.getMachineGunnerDebugAccess();
            String posture = switch (mg.getMachineGunnerDebugPosture()) {
                case 1 -> "COVER";
                case 2 -> "PRONE";
                default -> "NONE";
            };
            String label = "MG " + (active ? posture : mg.isMachineGunnerDebugFallback() ? "FALLBACK" : "IDLE")
                + " access=" + String.format("%.2f", access)
                + (mg.isMachineGunnerDebugSuppressed() ? " SUPPRESSED" : "");
            String destinationLabel = destination != null && !destination.equals(BlockPos.ZERO)
                ? "pos=" + destination.getX() + "," + destination.getY() + "," + destination.getZ() : "pos=none";
            String movementLabel = movementDestination != null && !movementDestination.equals(BlockPos.ZERO)
                ? "moving=" + movementDestination.getX() + "," + movementDestination.getY() + "," + movementDestination.getZ()
                : "moving=none";
            String centerLabel = center != null && !center.equals(BlockPos.ZERO)
                ? "obj=" + center.getX() + "," + center.getY() + "," + center.getZ() : "obj=none";

            double x = mg.getX() - cameraPos.x + 0.5;
            double y = mg.getY() - cameraPos.y + 2.35;
            double z = mg.getZ() - cameraPos.z + 0.5;
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            int color = access >= 0.25f ? 0x55FF55 : 0xFF5555;
            int line = 0;
            mc.font.drawInBatch(label, -mc.font.width(label) / 2.0f, line, color | 0xFF000000, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            line += 10;
            mc.font.drawInBatch(destinationLabel, -mc.font.width(destinationLabel) / 2.0f, line, 0xFFFFFF | 0xFF000000, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            line += 10;
            mc.font.drawInBatch(movementLabel, -mc.font.width(movementLabel) / 2.0f, line, 0x55FFFF | 0xFF000000, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            line += 10;
            mc.font.drawInBatch(centerLabel, -mc.font.width(centerLabel) / 2.0f, line, 0xFFAA00 | 0xFF000000, false,
                poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            poseStack.popPose();
        }
    }

    private static void renderMachineGunnerEvaluation(BufferBuilder buffer, PoseStack poseStack,
                                                       Vec3 cameraPos, Level level) {
        CoverDebugManager.MachineGunnerEvaluationDebugData data =
            CoverDebugManager.getMachineGunnerEvaluation();
        if (data == null) return;
        Matrix4f matrix = poseStack.last().pose();
        EntityLookup:
        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(96))) {
            if (mg.getId() != data.entityId) continue;

            if (data.center != null) {
                renderThreatPositionBox(buffer, matrix, data.center, cameraPos);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(),
                    data.center.getCenter().add(0, 1.0, 0), 255, 170, 0, 180);
            }
            if (data.anchor != null) {
                renderCoverBlockBox(buffer, matrix, data.anchor, cameraPos, 0, 220, 255, 230);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(),
                    data.anchor.getCenter(), 0, 220, 255, 180);
            }
            for (int targetIndex = 0; targetIndex < data.targets.size(); targetIndex++) {
                CoverDebugManager.MachineGunnerEvaluationDebugData.TargetDebugEntry targetEntry =
                    data.targets.get(targetIndex);
                Vec3 target = targetEntry.position();
                FiringPositionFinder.TargetCategory category = targetEntry.category() >= 0
                    && targetEntry.category() < FiringPositionFinder.TargetCategory.values().length
                    ? FiringPositionFinder.TargetCategory.values()[targetEntry.category()]
                    : FiringPositionFinder.TargetCategory.GRID_FALLBACK;
                int targetColor = category == FiringPositionFinder.TargetCategory.LAST_SEEN ? 0 : 255;
                int targetGreen = category == FiringPositionFinder.TargetCategory.ACTIVE_TARGET ? 40
                    : category == FiringPositionFinder.TargetCategory.LAST_SEEN ? 180 : 140;
                int targetBlue = category == FiringPositionFinder.TargetCategory.ACTIVE_TARGET ? 40
                    : category == FiringPositionFinder.TargetCategory.LAST_SEEN ? 255 : 0;
                renderDebugCross(buffer, matrix, cameraPos, target,
                    targetColor, targetGreen, targetBlue, 220, 0.16f);
            }
            for (CoverDebugManager.FiringPositionDebugEntry candidate : data.candidates) {
                int r;
                int g;
                int b;
                if (candidate.pathChecked && candidate.canReach) {
                    r = 0; g = 255; b = 0;
                } else if (candidate.pathChecked && !candidate.pathExists) {
                    r = 255; g = 40; b = 40;
                } else if (candidate.pathChecked) {
                    r = 255; g = 150; b = 30;
                } else {
                    r = 170; g = 80; b = 255;
                }
                renderCoverBlockBox(buffer, matrix, candidate.position, cameraPos, r, g, b, 210);
            }

            // Evaluator candidates are diagnostics only. Draw the shared
            // cover goal's movement markers after them so the physical target
            // remains unambiguous even when coordinates overlap.
            BlockPos movement = mg.getMachineGunnerDebugMovementPosition();
            if (movement != null && !movement.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, movement, cameraPos, 0, 220, 255, 230);
            }
            BlockPos authoritative = mg.getMachineGunnerDebugPosition();
            if (authoritative != null && !authoritative.equals(BlockPos.ZERO)) {
                renderCoverBlockBox(buffer, matrix, authoritative, cameraPos, 255, 255, 0, 245);
                renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(),
                    authoritative.getCenter().add(0, mg.getMachineGunnerDebugPosture() == 2 ? 0.45 : 1.6, 0),
                    255, 255, 0, 235);
            }
            break EntityLookup;
        }
    }

    private static void renderMachineGunnerProtection(BufferBuilder buffer, PoseStack poseStack,
                                                       Vec3 cameraPos, Level level) {
        CoverDebugManager.MachineGunnerEvaluationDebugData data =
            CoverDebugManager.getMachineGunnerEvaluation();
        if (data == null || data.center == null) return;
        Matrix4f matrix = poseStack.last().pose();
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                player.getBoundingBox().inflate(96))) {
            if (mg.getId() != data.entityId) continue;
            Vec3 center = data.center.getCenter().add(0, 1.0, 0);
            renderThreatPositionBox(buffer, matrix, data.center, cameraPos);
            renderDebugLine(buffer, matrix, cameraPos, mg.getEyePosition(), center, 255, 170, 0, 220);
            for (CoverDebugManager.MachineGunnerEvaluationDebugData.ProtectionDebugEntry entry
                : data.protectionCandidates) {
                int r;
                int g;
                int b;
                if (entry.posture() == 2) {
                    r = 255; g = 220; b = 40;
                } else if (entry.directionallyProtected()) {
                    r = 40; g = 255; b = 80;
                } else if (entry.protection() > 0.0f) {
                    r = 255; g = 150; b = 40;
                } else {
                    r = 255; g = 45; b = 45;
                }
                renderCoverBlockBox(buffer, matrix, entry.position(), cameraPos, r, g, b, 220);
                renderDebugLine(buffer, matrix, cameraPos, center,
                    entry.position().getCenter().add(0, entry.posture() == 2 ? 0.45 : 1.0, 0),
                    r, g, b, 140);
            }
            break;
        }
    }

    private static void renderMachineGunnerProtectionLabels(PoseStack poseStack, Vec3 cameraPos,
                                                            Level level, Minecraft mc,
                                                            MultiBufferSource bufferSource) {
        if (mc.font == null || mc.player == null) return;
        CoverDebugManager.MachineGunnerEvaluationDebugData data =
            CoverDebugManager.getMachineGunnerEvaluation();
        if (data == null) return;
        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                mc.player.getBoundingBox().inflate(96))) {
            if (mg.getId() != data.entityId) continue;
            String title = "MG PROTECTION rejected=" + data.rejectedProtection;
            String legend = "green=protected yellow=prone orange=partial red=exposed";
            poseStack.pushPose();
            poseStack.translate(mg.getX() - cameraPos.x + 0.5, mg.getY() - cameraPos.y + 3.4,
                mg.getZ() - cameraPos.z + 0.5);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.022f, -0.022f, 0.022f);
            mc.font.drawInBatch(title, -mc.font.width(title) / 2.0f, 0, 0xFFFF55 | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            mc.font.drawInBatch(legend, -mc.font.width(legend) / 2.0f, 10, 0xFFFFFF | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            poseStack.popPose();
            break;
        }
    }

    private static void renderDebugCross(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                         Vec3 point, int r, int g, int b, int a, float size) {
        float x = (float) (point.x - cameraPos.x);
        float y = (float) (point.y - cameraPos.y);
        float z = (float) (point.z - cameraPos.z);
        buffer.vertex(matrix, x - size, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x + size, y, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y - size, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y + size, z).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y, z - size).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x, y, z + size).color(r, g, b, a).endVertex();
    }

    private static void renderMachineGunnerEvaluationLabels(PoseStack poseStack, Vec3 cameraPos,
                                                              Level level, Minecraft mc,
                                                              MultiBufferSource bufferSource) {
        if (mc.font == null || mc.player == null) return;
        CoverDebugManager.MachineGunnerEvaluationDebugData data =
            CoverDebugManager.getMachineGunnerEvaluation();
        if (data == null) return;
        for (MachineGunnerEntity mg : level.getEntitiesOfClass(MachineGunnerEntity.class,
                mc.player.getBoundingBox().inflate(96))) {
            if (mg.getId() != data.entityId) continue;
            String title = "MG EVAL #" + data.entityId + " " + data.failure;
            String summary = "targets=" + data.targetCount + " active=" + data.activeTargetCount
                + " last=" + data.lastSeenCount + " peeks=" + data.peekTargetCount
                + " candidates=" + data.candidates.size() + " accessReject=" + data.rejectedAccess;
            BlockPos authoritative = mg.getMachineGunnerDebugPosition();
            BlockPos movement = mg.getMachineGunnerDebugMovementPosition();
            String targetLabel = "target=" + formatDebugPosition(authoritative)
                + " movement=" + formatDebugPosition(movement);
            String legend = "red=no path orange=access rejected green=reachable purple=unchecked yellow=cover target cyan=movement";
            poseStack.pushPose();
            poseStack.translate(mg.getX() - cameraPos.x + 0.5, mg.getY() - cameraPos.y + 3.0,
                mg.getZ() - cameraPos.z + 0.5);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.022f, -0.022f, 0.022f);
            mc.font.drawInBatch(title, -mc.font.width(title) / 2.0f, 0, 0xFFFF55 | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            mc.font.drawInBatch(summary, -mc.font.width(summary) / 2.0f, 10, 0xFFFFFF | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            mc.font.drawInBatch(targetLabel, -mc.font.width(targetLabel) / 2.0f, 20, 0xFFFF55 | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            mc.font.drawInBatch(legend, -mc.font.width(legend) / 2.0f, 30, 0xAAAAAA | 0xFF000000,
                false, poseStack.last().pose(), bufferSource,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            poseStack.popPose();
            break;
        }
    }

    private static String formatDebugPosition(BlockPos position) {
        return position == null || position.equals(BlockPos.ZERO)
            ? "none" : position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static String getSuppressionStateLabel(float suppression) {
        if (suppression >= 0.90f) return "PINNED";
        if (suppression >= 0.50f) return "PRESSURED";
        return "CLEAR";
    }

    private static int getSuppressionStateColor(float suppression) {
        if (suppression >= 0.90f) return 0xFFFF3333;
        if (suppression >= 0.50f) return 0xFFFFAA00;
        return 0xFF66FF66;
    }

    private static int countNearbyFriendlyPeekers(SoldierEntity soldier, Level level) {
        double radius = 8.0D;
        return (int) level.getEntitiesOfClass(SoldierEntity.class,
                soldier.getBoundingBox().inflate(radius),
                member -> member != soldier && member.isAlive()
                    && soldier.isFriendlyTo(member)
                    && (member.getSyncedPeekState() == PeekController.State.MOVING_TO_PEEK.ordinal()
                        || member.getSyncedPeekState() == PeekController.State.EXPOSED.ordinal()))
            .size();
    }
    
    private static int getStateColor(CoverBehaviorManager.CoverState state) {
        switch (state) {
            case IN_COVER: return 0x00FF00;
            case SUPPRESSED_IN_COVER: return 0xFF0000;
            case SEEKING_COVER: return 0xFFFF00;
            case NO_COVER: return 0x404040;
            default: return 0x808080;
        }
    }
    
    private static int getPeekStateColor(PeekController.State peekState) {
        switch (peekState) {
            case HIDING: return 0x808080;
            case STANDING_IN_HALF_COVER: return 0x6699FF;
            case EXPOSED: return 0x00FF00;
            case RETURNING_TO_COVER: return 0xFF8800;
            case MOVING_TO_PEEK: return 0x00FFFF;
            default: return 0x808080;
        }
    }
    
    private static void renderPeekCandidateVisualization(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, Level level) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.PeekCandidateDebugData data = CoverDebugManager.getSoldierPeekCandidates(soldier.getId());
            if (data == null) continue;
            
            List<BlockPos> candidates = data.candidatePositions;
            List<Integer> reasons = data.rejectionReasons;
            
            for (int i = 0; i < candidates.size(); i++) {
                BlockPos pos = candidates.get(i);
                int reason = reasons.get(i);
                
                int r, g, b, a;
                boolean isChosen = data.chosenPosition != null && pos.equals(data.chosenPosition);
                
                switch (reason) {
                    case CoverDebugManager.PeekCandidateDebugData.REASON_PROTECTED_DIR:
                        r = 128; g = 0; b = 0; a = 180;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_INVALID_POS:
                        r = 100; g = 100; b = 100; a = 180;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_NO_LOS:
                        r = 255; g = 0; b = 0; a = 200;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_BAD_ANGLE:
                        r = 255; g = 255; b = 0; a = 200;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_ACCEPTED:
                        r = 0; g = 255; b = 0; a = 200;
                        break;
                    default:
                        r = 150; g = 150; b = 150; a = 100;
                }
                
                renderCoverBlockBox(buffer, matrix, pos, cameraPos, r, g, b, a);
                
                if (isChosen) {
                    renderCoverBlockBox(buffer, matrix, pos, cameraPos, 0, 255, 255, 255);
                    renderCoverBlockBox(buffer, matrix, pos.offset(0, 1, 0), cameraPos, 0, 255, 255, 100);
                }
            }
        }
    }
    
    private static void renderPeekCandidateRays(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, Level level) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.PeekCandidateDebugData data = CoverDebugManager.getSoldierPeekCandidates(soldier.getId());
            if (data == null) continue;
            
            List<BlockPos> candidates = data.candidatePositions;
            List<Boolean> losResults = data.losResults;
            List<Vec3> peekEyePositions = data.peekEyePositions;
            Vec3 targetEye = data.targetEyePosition;
            if (targetEye == null) continue;
            
            for (int i = 0; i < candidates.size(); i++) {
                if (i >= losResults.size()) continue;
                
                Vec3 peekEye = peekEyePositions.get(i);
                if (peekEye == null) continue;
                
                double fromX = peekEye.x - cameraPos.x;
                double fromY = peekEye.y - cameraPos.y;
                double fromZ = peekEye.z - cameraPos.z;
                double toX = targetEye.x - cameraPos.x;
                double toY = targetEye.y - cameraPos.y;
                double toZ = targetEye.z - cameraPos.z;
                
                boolean hasLos = losResults.get(i);
                int lr = hasLos ? 0 : 255;
                int lg = hasLos ? 255 : 0;
                int lb = hasLos ? 0 : 255;
                int la = 180;
                
                buffer.vertex(matrix, (float)fromX, (float)fromY, (float)fromZ).color(lr, lg, lb, la).endVertex();
                buffer.vertex(matrix, (float)toX, (float)toY, (float)toZ).color(lr, lg, lb, la).endVertex();
            }
        }
    }
    
    private static void renderPeekCoverBlockOverlay(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, Level level) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.PeekCandidateDebugData data = CoverDebugManager.getSoldierPeekCandidates(soldier.getId());
            if (data == null) continue;
            
            // Green outline at the actual cover position, drawn on top of everything
            renderCoverBlockBox(buffer, matrix, data.coverPos, cameraPos, 0, 255, 0, 255);
        }
    }
    
    private static void renderPeekCandidateLabels(PoseStack poseStack, Vec3 cameraPos, Level level,
                                                    Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null) return;
        
        net.minecraft.client.gui.Font font = mc.font;
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.PeekCandidateDebugData data = CoverDebugManager.getSoldierPeekCandidates(soldier.getId());
            if (data == null) continue;
            
            List<BlockPos> candidates = data.candidatePositions;
            List<Integer> reasons = data.rejectionReasons;
            List<Double> scores = data.angleScores;
            List<Boolean> losResults = data.losResults;
            List<Vec3> peekEyePositions = data.peekEyePositions;
            List<Float> coneScores = data.coneCoverageScores;
            
            for (int i = 0; i < candidates.size(); i++) {
                BlockPos pos = candidates.get(i);
                int reason = reasons.get(i);
                
                Vec3 soldierPos = soldier.position();
                double distToSoldier = Math.sqrt(pos.distToCenterSqr(soldierPos.x, soldierPos.y, soldierPos.z));
                if (distToSoldier > 8) continue;
                
                double x = pos.getX() - cameraPos.x + 0.5;
                double y = pos.getY() - cameraPos.y + 1.3;
                double z = pos.getZ() - cameraPos.z + 0.5;
                
                poseStack.pushPose();
                poseStack.translate(x, y, z);
                poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
                poseStack.scale(-0.025f, -0.025f, 0.025f);
                
                String label;
                String sublabel = null;
                int color;
                
                switch (reason) {
                    case CoverDebugManager.PeekCandidateDebugData.REASON_PROTECTED_DIR:
                        label = "PROTECTED";
                        color = 0x80_00_00;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_INVALID_POS:
                        label = "BLOCKED";
                        color = 0x88_88_88;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_NO_LOS:
                        label = "NO_LOS";
                        Vec3 peekEye = peekEyePositions.get(i);
                        if (peekEye != null) {
                            sublabel = String.format("eyeY=%.1f", peekEye.y);
                        }
                        color = 0xFF_00_00;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_BAD_ANGLE:
                        label = "BAD_ANGLE";
                        color = 0xFF_FF_00;
                        break;
                    case CoverDebugManager.PeekCandidateDebugData.REASON_ACCEPTED:
                    case CoverDebugManager.PeekCandidateDebugData.REASON_CHOSEN:
                        double score = i < scores.size() ? scores.get(i) : 0;
                        boolean isChosen = data.chosenPosition != null && pos.equals(data.chosenPosition);
                        boolean hasLos = i < losResults.size() && losResults.get(i);
                        float coneScore = i < coneScores.size() ? coneScores.get(i) : 0;
                        label = isChosen ? "CHOSEN" : String.format("S:%.2f", score);
                        if (isChosen) {
                            Vec3 eye = peekEyePositions.get(i);
                            if (eye != null) {
                                sublabel = String.format("eyeY=%.1f LOS=%s", eye.y, hasLos ? "Y" : "N");
                            }
                        }
                        color = isChosen ? 0x00_FF_FF : 0x00_FF_00;
                        break;
                    default:
                        label = "?";
                        color = 0x88_88_88;
                }
                
                font.drawInBatch(label, -font.width(label) / 2.0f, 0, color | 0xFF000000, false,
                        poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                
                if (sublabel != null) {
                    font.drawInBatch(sublabel, -font.width(sublabel) / 2.0f, 10, (color & 0xFFFFFF) | 0xFF000000, false,
                            poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                }
                
                poseStack.popPose();
            }
        }
    }
    
    private static void renderTopCoversVisualization(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, Level level) {
        Matrix4f matrix = poseStack.last().pose();
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.TopCoversDebugData topData = CoverDebugManager.getSoldierTopCovers(soldier.getId());
            if (topData == null || topData.topCovers == null) continue;
            
            int[][] rankColors = {
                {255, 215, 0},   // Gold #1
                {192, 192, 192}, // Silver #2
                {205, 127, 50},  // Bronze #3
                {100, 200, 255}, // Blue #4
                {200, 100, 255}  // Purple #5
            };
            
            for (int i = 0; i < topData.topCovers.length && i < 5; i++) {
                CoverFinder.ScoredCover sc = topData.topCovers[i];
                BlockPos pos = sc.cover.getPosition();
                
                int[] col = rankColors[i];
                int r = col[0], g = col[1], b = col[2], a = 200;
                
                double x1 = pos.getX() - cameraPos.x;
                double y1 = pos.getY() - cameraPos.y;
                double z1 = pos.getZ() - cameraPos.z;
                double x2 = x1 + 1.0;
                double y2 = y1 + 1.0;
                double z2 = z1 + 1.0;
                
                for (int layer = 0; layer < 2; layer++) {
                    double off = layer * 0.05;
                    double ox1 = x1 - off, oy1 = y1 - off, oz1 = z1 - off;
                    double ox2 = x2 + off, oy2 = y2 + off, oz2 = z2 + off;
                    
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz1).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox2, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy1, (float)oz2).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float)ox1, (float)oy2, (float)oz2).color(r, g, b, a).endVertex();
                }
            }
        }
    }
    
    private static void renderTopCoversLabels(PoseStack poseStack, Vec3 cameraPos, Level level,
                                                Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null) return;
        
        net.minecraft.client.gui.Font font = mc.font;
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverDebugManager.TopCoversDebugData topData = CoverDebugManager.getSoldierTopCovers(soldier.getId());
            if (topData == null || topData.topCovers == null) continue;
            
            int[] rankColors = {
                0xFFFFD700,   // Gold #1
                0xFFC0C0C0,   // Silver #2
                0xFFCD7F32,   // Bronze #3
                0xFF64C8FF,   // Blue #4
                0xFFC864FF    // Purple #5
            };
            
            for (int i = 0; i < topData.topCovers.length && i < 5; i++) {
                CoverFinder.ScoredCover sc = topData.topCovers[i];
                BlockPos pos = sc.cover.getPosition();
                
                String rejection = topData.getRejectionReason(i);
                String label = String.format("#%d %.2f", i + 1, sc.score);
                if (!rejection.isEmpty()) {
                    label += " " + rejection;
                }
                int color = rankColors[i] | 0xFF000000;
                
                double x = pos.getX() - cameraPos.x + 0.5;
                double y = pos.getY() - cameraPos.y + 1.6;
                double z = pos.getZ() - cameraPos.z + 0.5;
                
                poseStack.pushPose();
                poseStack.translate(x, y, z);
                poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
                poseStack.scale(-0.025f, -0.025f, 0.025f);
                
                font.drawInBatch(label, -font.width(label) / 2.0f, 0, color, false,
                        poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                
                String blacklistDetail = topData.getBlacklistDetail(i);
                if (!blacklistDetail.isEmpty()) {
                    font.drawInBatch(blacklistDetail, -font.width(blacklistDetail) / 2.0f, 10, 0xFFFF8888, false,
                            poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                }
                
                poseStack.popPose();
            }
        }
    }
    
    private static boolean isPathClearClient(Level level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        
        if (steps == 0) return true;
        
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + (dx * i) / steps;
            int z = from.getZ() + (dz * i) / steps;
            BlockPos checkPos = new BlockPos(x, from.getY(), z);
            
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(checkPos);
            if (!state.isAir() && !state.getCollisionShape(level, checkPos).isEmpty()) {
                return false;
            }
        }
        
        return true;
    }
}

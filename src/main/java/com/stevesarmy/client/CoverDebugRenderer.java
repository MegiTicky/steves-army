package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
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
        }
        
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        for (CoverPoint cp : coverPoints) {
            renderQualityLabel(poseStack, cameraPos, cp, mc, bufferSource);
        }
        bufferSource.endBatch();
        
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
            
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            CoverBehaviorManager.CoverState state = coverManager.getState();
            CoverPoint currentCover = coverManager.getCurrentCover();
            
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
            
            if (currentCover != null && (state == CoverBehaviorManager.CoverState.IN_COVER || 
                                         state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER ||
                                         state == CoverBehaviorManager.CoverState.SEEKING_COVER)) {
                BlockPos coverPos = currentCover.getPosition();
                double coverRelX = coverPos.getX() - cameraPos.x + 0.5;
                double coverRelY = coverPos.getY() - cameraPos.y + 0.5;
                double coverRelZ = coverPos.getZ() - cameraPos.z + 0.5;
                
                int coverColor = (state == CoverBehaviorManager.CoverState.SEEKING_COVER) ? 0xFFFF00 : 0x00FF00;
                int cr = (coverColor >> 16) & 0xFF;
                int cg = (coverColor >> 8) & 0xFF;
                int cb = coverColor & 0xFF;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.5), (float)soldierRelZ).color(cr, cg, cb, a).endVertex();
                buffer.vertex(matrix, (float)coverRelX, (float)coverRelY, (float)coverRelZ).color(cr, cg, cb, a).endVertex();
            }
            
            Vec3 threatDirection = coverManager.getLastPrimaryThreatDirection();
            if (threatDirection != null) {
                double arrowLength = 2.0;
                double endX = soldierRelX + threatDirection.x * arrowLength;
                double endZ = soldierRelZ + threatDirection.z * arrowLength;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.5), (float)soldierRelZ).color(255, 0, 0, a).endVertex();
                buffer.vertex(matrix, (float)endX, (float)(soldierRelY - 0.5), (float)endZ).color(255, 0, 0, a).endVertex();
            }
            
            LivingEntity target = soldier.getTarget();
            if (target != null) {
                Vec3 targetPos = target.position();
                double targetRelX = targetPos.x - cameraPos.x;
                double targetRelY = targetPos.y - cameraPos.y + 1.0;
                double targetRelZ = targetPos.z - cameraPos.z;
                
                buffer.vertex(matrix, (float)soldierRelX, (float)(soldierRelY - 0.3), (float)soldierRelZ).color(255, 100, 0, a).endVertex();
                buffer.vertex(matrix, (float)targetRelX, (float)(targetRelY - 0.3), (float)targetRelZ).color(255, 100, 0, a).endVertex();
            }
        }
    }
    
    private static void renderSoldierCoverLabels(PoseStack poseStack, Vec3 cameraPos, Level level, 
                                                  Minecraft mc, MultiBufferSource bufferSource) {
        if (mc.font == null) return;
        
        net.minecraft.client.gui.Font font = mc.font;
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, 
                Minecraft.getInstance().player.getBoundingBox().inflate(50))) {
            
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            CoverBehaviorManager.CoverState state = coverManager.getState();
            CoverPoint currentCover = coverManager.getCurrentCover();
            
            Vec3 soldierPos = soldier.position();
            double x = soldierPos.x - cameraPos.x + 0.5;
            double y = soldierPos.y - cameraPos.y + 2.2;
            double z = soldierPos.z - cameraPos.z + 0.5;
            
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            
            String stateLabel = state.name();
            int stateColor = getStateColor(state);
            font.drawInBatch(stateLabel, -font.width(stateLabel) / 2.0f, 0, stateColor | 0xFF000000, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            
            if (currentCover != null) {
                String coverLabel = String.format("Cover: %.0f%%", currentCover.getQuality() * 100);
                font.drawInBatch(coverLabel, -font.width(coverLabel) / 2.0f, 10, 0xFFFFFFFF, false,
                                 poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            }
            
            String modeLabel = soldier.getSquadMode().name();
            font.drawInBatch(modeLabel, -font.width(modeLabel) / 2.0f, 20, 0xFFAAAAAA, false,
                             poseStack.last().pose(), bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            
            poseStack.popPose();
        }
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
}
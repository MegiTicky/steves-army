package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.CombatDebugData;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class CombatDebugRenderer {
    public static final int DEBUG_MODE_OFF = 0;
    public static final int DEBUG_MODE_MINIMAL = 1;
    public static final int DEBUG_MODE_VERBOSE = 2;
    
    private static int currentDebugMode = DEBUG_MODE_OFF;
    private static int maxUntargetedToRender = 3;
    private static final List<CombatDebugData> debugDataList = new ArrayList<>();
    
    public static void setDebugMode(int mode) {
        currentDebugMode = Math.min(mode, DEBUG_MODE_VERBOSE);
    }
    
    public static int getDebugMode() {
        return currentDebugMode;
    }
    
    public static void cycleDebugMode() {
        currentDebugMode = (currentDebugMode + 1) % 3;
    }
    
    public static String getDebugModeName() {
        switch (currentDebugMode) {
            case DEBUG_MODE_OFF: return "OFF";
            case DEBUG_MODE_MINIMAL: return "MINIMAL";
            case DEBUG_MODE_VERBOSE: return "VERBOSE+ARCS";
            default: return "UNKNOWN";
        }
    }
    
    public static void setMaxUntargeted(int count) {
        maxUntargetedToRender = Math.max(0, Math.min(10, count));
    }
    
    public static int getMaxUntargeted() {
        return maxUntargetedToRender;
    }
    
    public static void addDebugData(CombatDebugData data) {
        debugDataList.add(data);
    }
    
    public static void clearDebugData() {
        debugDataList.clear();
    }
    
    public static void render(PoseStack poseStack, Camera camera, float partialTick) {
        StevesArmyMod.LOGGER.debug("CombatDebugRenderer.render() called - mode: {}, dataCount: {}", currentDebugMode, debugDataList.size());
        
        if (currentDebugMode == DEBUG_MODE_OFF || debugDataList.isEmpty()) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        Vec3 cameraPos = camera.getPosition();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        
        for (CombatDebugData data : debugDataList) {
            renderLines(buffer, poseStack, cameraPos, data, mc, partialTick);
        }
        
        tesselator.end();
        
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        
        for (CombatDebugData data : debugDataList) {
            renderText(poseStack, cameraPos, data, mc, bufferSource);
        }
        
        bufferSource.endBatch();
        
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
    
    private static void renderLines(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, CombatDebugData data, Minecraft mc, float partialTick) {
        Matrix4f matrix = poseStack.last().pose();
        
        Vec3 soldierRel = data.soldierPos.subtract(cameraPos);
        Vec3 targetRel = data.targetPos.subtract(cameraPos);
        
        int lineColor = getLineColor(data);
        int a = 255;
        int r = (lineColor >> 16) & 0xFF;
        int g = (lineColor >> 8) & 0xFF;
        int b = lineColor & 0xFF;
        
        if (data.isLockedTarget) {
            if (data.hasLOS) {
                buffer.vertex(matrix, (float) soldierRel.x, (float) soldierRel.y + 1.5f, (float) soldierRel.z).color(r, g, b, a).endVertex();
                buffer.vertex(matrix, (float) targetRel.x, (float) targetRel.y + 1.0f, (float) targetRel.z).color(r, g, b, a).endVertex();
            } else {
                float step = 0.5f;
                Vec3 delta = targetRel.subtract(soldierRel);
                int segments = (int) (soldierRel.distanceTo(targetRel) / step);
                Vec3 normalized = delta.normalize();
                
                for (int i = 0; i < segments; i += 2) {
                    if (i + 1 >= segments) break;
                    Vec3 p1 = soldierRel.add(normalized.scale(i * step));
                    Vec3 p2 = soldierRel.add(normalized.scale((i + 1) * step));
                    buffer.vertex(matrix, (float) p1.x, (float) p1.y + 1.5f, (float) p1.z).color(r, g, b, a).endVertex();
                    buffer.vertex(matrix, (float) p2.x, (float) p2.y + 1.5f, (float) p2.z).color(r, g, b, a).endVertex();
                }
            }
        } else {
            float dashLength = 0.3f;
            float gapLength = 0.2f;
            Vec3 delta = targetRel.subtract(soldierRel);
            double totalDistance = soldierRel.distanceTo(targetRel);
            Vec3 normalized = delta.normalize();
            
            float currentDist = 0;
            while (currentDist < totalDistance) {
                Vec3 p1 = soldierRel.add(normalized.scale(currentDist));
                float endDist = (float) Math.min(currentDist + dashLength, totalDistance);
                Vec3 p2 = soldierRel.add(normalized.scale(endDist));
                
                buffer.vertex(matrix, (float) p1.x, (float) p1.y + 1.5f, (float) p1.z).color(r, g, b, a).endVertex();
                buffer.vertex(matrix, (float) p2.x, (float) p2.y + 1.5f, (float) p2.z).color(r, g, b, a).endVertex();
                
                currentDist = endDist + gapLength;
            }
        }
        
        if (currentDebugMode == DEBUG_MODE_VERBOSE) {
            renderArcLines(buffer, poseStack, cameraPos, data, mc, partialTick);
        }
    }
    
    private static void renderArcLines(BufferBuilder buffer, PoseStack poseStack, Vec3 cameraPos, CombatDebugData data, Minecraft mc, float partialTick) {
        net.minecraft.world.entity.Entity soldierEntity = null;
        net.minecraft.world.phys.Vec3 liveSoldierPos = data.soldierPos.subtract(cameraPos);
        
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity.getUUID().equals(data.soldierId)) {
                soldierEntity = entity;
                liveSoldierPos = entity.position().subtract(cameraPos);
                break;
            }
        }
        
        float yawDegrees = 0.0f;
        if (soldierEntity != null) {
            if (soldierEntity instanceof net.minecraft.world.entity.LivingEntity living) {
                yawDegrees = living.getYHeadRot();
            } else {
                yawDegrees = soldierEntity.getViewYRot(1.0f);
            }
        }
        
        float yawRad = (float) Math.toRadians(yawDegrees);
        float centerX = (float) -Math.sin(yawRad);
        float centerZ = (float) Math.cos(yawRad);
        
        float focusedArcHalf = 45.0f;
        float peripheralArcHalf = 90.0f;
        float focusedArcLength = 8.0f;
        float peripheralArcLength = 16.0f;
        
        int cyanR = 0, cyanG = 255, cyanB = 255, cyanA = 200;
        int darkCyanR = 0, darkCyanG = 136, darkCyanB = 136, darkCyanA = 150;
        
        Matrix4f matrix = poseStack.last().pose();
        
        float focusedTanHalf = (float) Math.tan(Math.toRadians(focusedArcHalf));
        float focusedPerpScale = focusedArcLength * focusedTanHalf;
        
        float leftFX = centerX * focusedArcLength - (-centerZ) * focusedPerpScale;
        float leftFZ = centerZ * focusedArcLength - centerX * focusedPerpScale;
        float rightFX = centerX * focusedArcLength + (-centerZ) * focusedPerpScale;
        float rightFZ = centerZ * focusedArcLength + centerX * focusedPerpScale;
        
        buffer.vertex(matrix, (float) liveSoldierPos.x, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z).color(cyanR, cyanG, cyanB, cyanA).endVertex();
        buffer.vertex(matrix, (float) liveSoldierPos.x + leftFX, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z + leftFZ).color(cyanR, cyanG, cyanB, cyanA).endVertex();
        
        buffer.vertex(matrix, (float) liveSoldierPos.x, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z).color(cyanR, cyanG, cyanB, cyanA).endVertex();
        buffer.vertex(matrix, (float) liveSoldierPos.x + rightFX, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z + rightFZ).color(cyanR, cyanG, cyanB, cyanA).endVertex();
        
        int segments = 10;
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float angleRad = (float) Math.toRadians(-focusedArcHalf + focusedArcHalf * 2 * t);
            
            float cosA = (float) Math.cos(angleRad);
            float sinA = (float) Math.sin(angleRad);
            
            float dirX = centerX * cosA - (-centerZ) * sinA;
            float dirZ = centerZ * cosA - centerX * sinA;
            
            float arcX = (float) liveSoldierPos.x + dirX * focusedArcLength;
            float arcZ = (float) liveSoldierPos.z + dirZ * focusedArcLength;
            
            if (i > 0) {
                float prevT = (float) (i - 1) / segments;
                float prevAngleRad = (float) Math.toRadians(-focusedArcHalf + focusedArcHalf * 2 * prevT);
                float prevCosA = (float) Math.cos(prevAngleRad);
                float prevSinA = (float) Math.sin(prevAngleRad);
                float prevDirX = centerX * prevCosA - (-centerZ) * prevSinA;
                float prevDirZ = centerZ * prevCosA - centerX * prevSinA;
                float prevArcX = (float) liveSoldierPos.x + prevDirX * focusedArcLength;
                float prevArcZ = (float) liveSoldierPos.z + prevDirZ * focusedArcLength;
                
                buffer.vertex(matrix, prevArcX, (float) liveSoldierPos.y + 1.5f, prevArcZ).color(cyanR, cyanG, cyanB, cyanA).endVertex();
                buffer.vertex(matrix, arcX, (float) liveSoldierPos.y + 1.5f, arcZ).color(cyanR, cyanG, cyanB, cyanA).endVertex();
            }
        }
        
        float peripheralTanHalf = (float) Math.tan(Math.toRadians(peripheralArcHalf));
        float peripheralPerpScale = peripheralArcLength * peripheralTanHalf;
        
        float leftPX = centerX * peripheralArcLength - (-centerZ) * peripheralPerpScale;
        float leftPZ = centerZ * peripheralArcLength - centerX * peripheralPerpScale;
        float rightPX = centerX * peripheralArcLength + (-centerZ) * peripheralPerpScale;
        float rightPZ = centerZ * peripheralArcLength + centerX * peripheralPerpScale;
        
        buffer.vertex(matrix, (float) liveSoldierPos.x, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
        buffer.vertex(matrix, (float) liveSoldierPos.x + leftPX, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z + leftPZ).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
        
        buffer.vertex(matrix, (float) liveSoldierPos.x, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
        buffer.vertex(matrix, (float) liveSoldierPos.x + rightPX, (float) liveSoldierPos.y + 1.5f, (float) liveSoldierPos.z + rightPZ).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
        
        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float angleRad = (float) Math.toRadians(-peripheralArcHalf + peripheralArcHalf * 2 * t);
            
            float cosA = (float) Math.cos(angleRad);
            float sinA = (float) Math.sin(angleRad);
            
            float dirX = centerX * cosA - (-centerZ) * sinA;
            float dirZ = centerZ * cosA - centerX * sinA;
            
            float arcX = (float) liveSoldierPos.x + dirX * peripheralArcLength;
            float arcZ = (float) liveSoldierPos.z + dirZ * peripheralArcLength;
            
            if (i > 0) {
                float prevT = (float) (i - 1) / segments;
                float prevAngleRad = (float) Math.toRadians(-peripheralArcHalf + peripheralArcHalf * 2 * prevT);
                float prevCosA = (float) Math.cos(prevAngleRad);
                float prevSinA = (float) Math.sin(prevAngleRad);
                float prevDirX = centerX * prevCosA - (-centerZ) * prevSinA;
                float prevDirZ = centerZ * prevCosA - centerX * prevSinA;
                float prevArcX = (float) liveSoldierPos.x + prevDirX * peripheralArcLength;
                float prevArcZ = (float) liveSoldierPos.z + prevDirZ * peripheralArcLength;
                
                buffer.vertex(matrix, prevArcX, (float) liveSoldierPos.y + 1.5f, prevArcZ).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
                buffer.vertex(matrix, arcX, (float) liveSoldierPos.y + 1.5f, arcZ).color(darkCyanR, darkCyanG, darkCyanB, darkCyanA).endVertex();
            }
        }
    }
    
    private static void renderText(PoseStack poseStack, Vec3 cameraPos, CombatDebugData data, Minecraft mc, MultiBufferSource.BufferSource bufferSource) {
        Vec3 targetRel = data.targetPos.subtract(cameraPos);
        
        poseStack.pushPose();
        poseStack.translate(targetRel.x, targetRel.y + 2.5, targetRel.z);
        
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-mc.player.getYRot()));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(mc.player.getXRot()));
        
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        
        Matrix4f matrix4f = poseStack.last().pose();
        
        String targetLabel = data.isLockedTarget ? "" : "[potential] ";
        String scoreText = targetLabel + String.format("%.1f/%.0f", data.detectionPoints, data.detectionThreshold);
        int color = data.isDetected ? 0x00FF00 : (data.detectionPoints >= 40 ? 0xFFFF00 : 0xFF0000);
        
        int width = mc.font.width(scoreText);
        mc.font.drawInBatch(scoreText, -width / 2f, 0, color, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
        
        if (currentDebugMode == DEBUG_MODE_VERBOSE) {
            String arcType = data.inFocusedArc ? "F" : (data.inPeripheralArc ? "P" : "?");
            String rateText = String.format("%s:%.1f", arcType, data.baseRate);
            String distText = String.format("D:%.2f", data.distanceFactor);
            String expText = String.format("E:%.2f", data.exposureFactor);
            String movText = String.format("M:%.2f", data.movementFactor);
            String briText = String.format("B:%.2f", data.brightnessFactor);
            
            float y = -12;
            mc.font.drawInBatch(rateText, -mc.font.width(rateText) / 2f, y, 0xFFFFFF, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            y -= 10;
            mc.font.drawInBatch(distText, -mc.font.width(distText) / 2f, y, 0x8888FF, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            y -= 10;
            mc.font.drawInBatch(expText, -mc.font.width(expText) / 2f, y, 0x88FF88, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            y -= 10;
            mc.font.drawInBatch(movText, -mc.font.width(movText) / 2f, y, 0xFF8888, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            y -= 10;
            mc.font.drawInBatch(briText, -mc.font.width(briText) / 2f, y, 0xFFFF88, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            
            if (data.isLockedTarget) {
                y -= 10;
                String adsText = String.format("ADS:%d%%", (int)(data.adsProgress * 100));
                mc.font.drawInBatch(adsText, -mc.font.width(adsText) / 2f, y, 0xAAAAFF, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                y -= 10;
                String trackText = String.format("TRK:%d%%", (int)(data.trackingProgress * 100));
                mc.font.drawInBatch(trackText, -mc.font.width(trackText) / 2f, y, 0xFFAAFF, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                y -= 10;
                String accText = String.format("ACC:%d%%", (int)(data.currentAccuracy * 100));
                mc.font.drawInBatch(accText, -mc.font.width(accText) / 2f, y, 0xFFAAAA, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                y -= 10;
                String shotText = String.format("SHOT:%d%%", (int)(data.shotThreshold * 100));
                mc.font.drawInBatch(shotText, -mc.font.width(shotText) / 2f, y, 0xAAFFAA, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                y -= 10;
                String aimText = String.format("AIM:%s", data.aimPointType);
                int aimColor = data.bulletPathClear ? 0x00FF00 : 0xFF0000;
                mc.font.drawInBatch(aimText, -mc.font.width(aimText) / 2f, y, aimColor, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
                y -= 10;
                String pathText = data.bulletPathClear ? "PATH:OK" : "PATH:BLOCKED";
                int pathColor = data.bulletPathClear ? 0x00FF00 : 0xFF0000;
                mc.font.drawInBatch(pathText, -mc.font.width(pathText) / 2f, y, pathColor, false, matrix4f, bufferSource, net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880);
            }
        }
        
        poseStack.popPose();
    }
    
    private static int getLineColor(CombatDebugData data) {
        if (data.isDetected) {
            return 0x00FF00;
        } else if (data.detectionPoints >= data.detectionThreshold * 0.5) {
            return 0xFFFF00;
        } else if (data.detectionPoints >= data.detectionThreshold * 0.25) {
            return 0xFF8800;
        } else {
            return 0xFF0000;
        }
    }
}
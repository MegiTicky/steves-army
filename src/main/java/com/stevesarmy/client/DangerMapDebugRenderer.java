package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.network.DangerMapDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Danger-map overlay (/stevesarmy_debug dangermap): one ground quad per baked
 * cell — red for eye-exposed (alpha scales with score), orange for leg-only,
 * faint green for baked-safe shadow — plus a labeled marker at each active
 * threat origin.
 */
public final class DangerMapDebugRenderer {
    private DangerMapDebugRenderer() {}

    public static void render(PoseStack poseStack, Camera camera) {
        if (!ClientDangerMapDebugData.INSTANCE.renderEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 cam = camera.getPosition();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        // the pose matrix carries the camera rotation — raw vertices would draw
        // the overlay glued to one view direction instead of the world
        org.joml.Matrix4f matrix = poseStack.last().pose();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (DangerMapDebugPacket.Cell cell : ClientDangerMapDebugData.INSTANCE.cells()) {
            BlockPos pos = BlockPos.of(cell.packedPos());
            float alpha;
            if (cell.eyeExposed()) {
                alpha = (float) (0.10 + 0.30 * cell.score() / 100.0);
                color(buf, matrix, cam, pos, 255, 60, 40, alpha);
            } else if (cell.legExposed()) {
                color(buf, matrix, cam, pos, 255, 170, 60, 0.22f);
            } else {
                color(buf, matrix, cam, pos, 80, 255, 140, 0.07f);
            }
        }
        tesselator.end();

        // pathfinding corridors: blue tint over the map, alpha by danger x weight
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (DangerMapDebugPacket.Corridor corridor : ClientDangerMapDebugData.INSTANCE.corridors()) {
            for (DangerMapDebugPacket.Cell cell : corridor.cells()) {
                BlockPos pos = BlockPos.of(cell.packedPos());
                float intensity = cell.score() / 100.0f;
                float alpha = 0.10f + 0.30f * intensity;
                color(buf, matrix, cam, pos, 60, 140, 255, alpha);
            }
        }
        tesselator.end();

        for (DangerMapDebugPacket.Threat threat : ClientDangerMapDebugData.INSTANCE.threats()) {
            renderThreatMarker(poseStack, cam, threat);
        }

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (DangerMapDebugPacket.Corridor corridor : ClientDangerMapDebugData.INSTANCE.corridors()) {
            String label = "corridor w=" + String.format("%.1f", corridor.weight())
                + " (" + corridor.cells().size() + " cells)";
            billboard(mc.font, poseStack, cam, Vec3.atLowerCornerOf(corridor.target()).add(0, 1.2, 0),
                label, 0xFF70B0FF, buffers);
        }
        DangerMapDebugPacket.Stats stats = ClientDangerMapDebugData.INSTANCE.stats();
        Vec3 playerTop = mc.player.position().add(0, 3.1, 0);
        billboard(mc.font, poseStack, cam, playerTop,
            "dangermap: sent " + stats.cells() + " | baked " + stats.bakedCells()
                + " | threats " + stats.threats() + " | rays " + stats.raysLastTick()
                + "/" + stats.rayBudget() + "t | surfMiss " + stats.surfaceMiss(),
            0xFFE0E0E0, buffers);
        buffers.endBatch();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** One axis-aligned ground quad covering the 2x2 cell whose min corner is {@code pos}. */
    private static void color(BufferBuilder buf, org.joml.Matrix4f matrix, Vec3 cam, BlockPos pos,
                              int r, int g, int b, float alpha) {
        float x1 = (float) (pos.getX() - cam.x);
        float y = (float) (pos.getY() + 0.05 - cam.y);
        float z1 = (float) (pos.getZ() - cam.z);
        float x2 = x1 + 2;
        float z2 = z1 + 2;
        int a = (int) (alpha * 255);
        buf.vertex(matrix, x1, y, z1).color(r, g, b, a).endVertex();
        buf.vertex(matrix, x1, y, z2).color(r, g, b, a).endVertex();
        buf.vertex(matrix, x2, y, z2).color(r, g, b, a).endVertex();
        buf.vertex(matrix, x2, y, z1).color(r, g, b, a).endVertex();
    }

    private static void renderThreatMarker(PoseStack poseStack, Vec3 cam, DangerMapDebugPacket.Threat threat) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;

        // vertical tick at the origin
        org.joml.Matrix4f matrix = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        float x = (float) (threat.pos().x - cam.x);
        float y1 = (float) (threat.pos().y - cam.y);
        float y2 = y1 + 1.2f;
        int lineColor = threat.hardTarget() ? 0xFFFF3030 : 0xFFFF8030;
        buf.vertex(matrix, x, y1, (float) (threat.pos().z - cam.z)).color(lineColor).endVertex();
        buf.vertex(matrix, x, y2, (float) (threat.pos().z - cam.z)).color(lineColor).endVertex();
        tesselator.end();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        String label = (threat.hardTarget() ? "HARD " : "") + "threat w=" + String.format("%.1f", threat.weight());
        billboard(mc.font, poseStack, cam, threat.pos().add(0, 1.4, 0), label,
            threat.hardTarget() ? 0xFFFF5050 : 0xFFFFA050, buffers);
        buffers.endBatch();
    }

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 position, String text,
                                  int color, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 relative = position.subtract(cameraPos);
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        font.drawInBatch(text, -font.width(text) / 2.0f, 0, color, false, poseStack.last().pose(), buffers,
            Font.DisplayMode.NORMAL, 0, 15728880);
        poseStack.popPose();
    }
}

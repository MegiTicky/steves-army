package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.SuppressPingDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World-space visualization of the suppress-ping pipeline: the pinged zone,
 * every cached aim point colored by live LOS validity, the current shot lane,
 * and a per-soldier status label. Toggle with {@code /stevesarmy_debug
 * suppressping on}.
 */
public final class SuppressPingDebugRenderer {
    private SuppressPingDebugRenderer() {}

    private static final int ZONE_SEGMENTS = 24;
    private static final int[] ZONE_COLOR = {255, 160, 32};
    private static final int[] VALID_COLOR = {64, 255, 64};
    private static final int[] BLOCKED_COLOR = {255, 64, 64};
    private static final int[] LANE_COLOR = {64, 224, 255};
    private static final int[] FALLBACK_COLOR = {255, 255, 64};
    private static final int[] SEARCH_COLOR = {255, 140, 0};
    private static final int[] PATH_COLOR = {170, 200, 255};

    public static void render(PoseStack poseStack, Camera camera) {
        if (!ClientSuppressPingDebugData.INSTANCE.renderEnabled()) return;
        List<SuppressPingDebugPacket> snapshots = ClientSuppressPingDebugData.INSTANCE.freshSnapshots();
        if (snapshots.isEmpty()) return;

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
        for (SuppressPingDebugPacket snapshot : snapshots) {
            renderSoldier(buffer, poseStack.last().pose(), cameraPos, snapshot);
        }
        tesselator.end();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (SuppressPingDebugPacket snapshot : snapshots) {
            renderLabel(mc.font, poseStack, cameraPos, snapshot, buffers);
        }
        buffers.endBatch();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderSoldier(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                      SuppressPingDebugPacket snapshot) {
        if (snapshot.pingPos() != null) {
            Vec3 pingCentre = snapshot.pingPos();
            // Zone circle + centre marker.
            renderCircle(buffer, matrix, cameraPos, pingCentre,
                SoldierEntity.SUPPRESSION_ZONE_RADIUS, ZONE_COLOR[0], ZONE_COLOR[1], ZONE_COLOR[2]);
            cross(buffer, matrix, cameraPos, pingCentre, 1.0, ZONE_COLOR[0], ZONE_COLOR[1], ZONE_COLOR[2]);
            line(buffer, matrix, cameraPos, pingCentre, pingCentre.add(0, 4, 0),
                ZONE_COLOR[0], ZONE_COLOR[1], ZONE_COLOR[2]);
            // Soldier aiming at the zone.
            line(buffer, matrix, cameraPos, snapshot.soldierPos(), pingCentre, 128, 128, 128);
        }

        // Cached aim points, colored by whether the firing path would shoot.
        List<Vec3> aimPoints = snapshot.aimPoints();
        List<Boolean> valid = snapshot.aimPointValid();
        for (int i = 0; i < aimPoints.size(); i++) {
            Vec3 point = aimPoints.get(i);
            int[] color = i < valid.size() && valid.get(i) ? VALID_COLOR : BLOCKED_COLOR;
            cross(buffer, matrix, cameraPos, point, 0.35, color[0], color[1], color[2]);
            square(buffer, matrix, cameraPos, point, 0.18, color[0], color[1], color[2]);
        }

        // The lane the soldier is actually shooting down this tick.
        Vec3 target = snapshot.currentTarget();
        if (target != null) {
            boolean fallback = aimPoints.isEmpty();
            int[] color = fallback ? FALLBACK_COLOR : LANE_COLOR;
            line(buffer, matrix, cameraPos, snapshot.soldierPos(), target, color[0], color[1], color[2]);
            cross(buffer, matrix, cameraPos, target, 0.5, color[0], color[1], color[2]);
        }

        // Reposition destination while seeking/moving to new cover.
        Vec3 relocTarget = snapshot.relocTargetPos();
        if (relocTarget != null) {
            line(buffer, matrix, cameraPos, snapshot.soldierPos(), relocTarget, 255, 64, 255);
            cross(buffer, matrix, cameraPos, relocTarget, 0.6, 255, 64, 255);
            line(buffer, matrix, cameraPos, relocTarget, relocTarget.add(0, 2.2, 0), 255, 64, 255);
            square(buffer, matrix, cameraPos, relocTarget.add(0, 1.1, 0), 0.45, 255, 64, 255);
        }

        // Relocation search fan: anchor + radius the cover search uses, with
        // the direction it is biased toward (the ping centre).
        Vec3 searchOrigin = snapshot.searchOrigin();
        if (searchOrigin != null) {
            renderCircle(buffer, matrix, cameraPos, searchOrigin, snapshot.searchRadius(),
                SEARCH_COLOR[0], SEARCH_COLOR[1], SEARCH_COLOR[2]);
            cross(buffer, matrix, cameraPos, searchOrigin, 0.5,
                SEARCH_COLOR[0], SEARCH_COLOR[1], SEARCH_COLOR[2]);
            line(buffer, matrix, cameraPos, searchOrigin, searchOrigin.add(0, 2.2, 0),
                SEARCH_COLOR[0], SEARCH_COLOR[1], SEARCH_COLOR[2]);
            if (snapshot.pingPos() != null) {
                line(buffer, matrix, cameraPos, searchOrigin, snapshot.pingPos(),
                    SEARCH_COLOR[0], SEARCH_COLOR[1], SEARCH_COLOR[2]);
            }
        }

        // The live navigation path while repositioning.
        List<Vec3> navPath = snapshot.navPath();
        Vec3 prevNode = null;
        for (Vec3 node : navPath) {
            square(buffer, matrix, cameraPos, node, 0.12, PATH_COLOR[0], PATH_COLOR[1], PATH_COLOR[2]);
            if (prevNode != null) {
                line(buffer, matrix, cameraPos, prevNode, node,
                    PATH_COLOR[0], PATH_COLOR[1], PATH_COLOR[2]);
            }
            prevNode = node;
        }
        if (prevNode != null) {
            cross(buffer, matrix, cameraPos, prevNode, 0.4, PATH_COLOR[0], PATH_COLOR[1], PATH_COLOR[2]);
        }
    }

    private static void renderLabel(Font font, PoseStack poseStack, Vec3 cameraPos,
                                    SuppressPingDebugPacket snapshot, MultiBufferSource.BufferSource buffers) {
        StringBuilder text = new StringBuilder("PING");
        if (snapshot.heavy()) text.append(" rpg");
        text.append(String.format(" t=%.1fs", snapshot.remainingTicks() / 20.0));
        if (!snapshot.statusLine().isEmpty()) {
            text.append(' ').append(snapshot.statusLine());
        }
        int color = snapshot.heavy() ? 0xFFFFD040 : 0xFF40FF40;
        billboard(font, poseStack, cameraPos, snapshot.soldierPos().add(0, 2.6, 0),
            text.toString(), color, buffers);

        String relocLine = buildRelocLine(snapshot);
        if (!relocLine.isEmpty()) {
            billboard(font, poseStack, cameraPos, snapshot.soldierPos().add(0, 2.35, 0),
                relocLine, relocLineColor(snapshot), buffers);
        }
    }

    /** Second label line: relocation progress, or the countdown to requesting one. */
    private static String buildRelocLine(SuppressPingDebugPacket snapshot) {
        String status = snapshot.relocStatus();
        if (!status.isEmpty()) {
            StringBuilder line = new StringBuilder("RELOC ").append(status);
            if (snapshot.relocFailures() > 0) {
                line.append(" x").append(snapshot.relocFailures());
                if (!snapshot.relocLastFailure().isEmpty()) {
                    line.append(" (").append(snapshot.relocLastFailure()).append(')');
                }
            }
            return line.toString();
        }
        if (snapshot.blindTicks() > 0) {
            return String.format("blind %d/%d", snapshot.blindTicks(), snapshot.blindThreshold());
        }
        return "";
    }

    private static int relocLineColor(SuppressPingDebugPacket snapshot) {
        switch (snapshot.relocStatus()) {
            case "FAILED": return 0xFFFF4040;
            case "BLOCKED": return 0xFFFFA000;
            case "": return 0xFFFFFF40;
            default: return 0xFFFF60FF;
        }
    }

    private static void renderCircle(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                     Vec3 center, double radius, int r, int g, int b) {
        Vec3 prev = center.add(radius, 0, 0);
        for (int i = 1; i <= ZONE_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / ZONE_SEGMENTS;
            Vec3 next = center.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            line(buffer, matrix, cameraPos, prev, next, r, g, b);
            prev = next;
        }
    }

    private static void square(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                               Vec3 center, double half, int r, int g, int b) {
        Vec3 tl = center.add(-half, half, 0);
        Vec3 tr = center.add(half, half, 0);
        Vec3 br = center.add(half, -half, 0);
        Vec3 bl = center.add(-half, -half, 0);
        line(buffer, matrix, cameraPos, tl, tr, r, g, b);
        line(buffer, matrix, cameraPos, tr, br, r, g, b);
        line(buffer, matrix, cameraPos, br, bl, r, g, b);
        line(buffer, matrix, cameraPos, bl, tl, r, g, b);
    }

    private static void cross(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                              Vec3 center, double size, int r, int g, int b) {
        line(buffer, matrix, cameraPos, center.add(-size, 0, 0), center.add(size, 0, 0), r, g, b);
        line(buffer, matrix, cameraPos, center.add(0, -size, 0), center.add(0, size, 0), r, g, b);
        line(buffer, matrix, cameraPos, center.add(0, 0, -size), center.add(0, 0, size), r, g, b);
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos, Vec3 from, Vec3 to,
                             int r, int g, int b) {
        Vec3 a = from.subtract(cameraPos), z = to.subtract(cameraPos);
        buffer.vertex(matrix, (float) a.x, (float) a.y, (float) a.z).color(r, g, b, 255).endVertex();
        buffer.vertex(matrix, (float) z.x, (float) z.y, (float) z.z).color(r, g, b, 255).endVertex();
    }

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 position,
                                  String text, int color, MultiBufferSource.BufferSource buffers) {
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

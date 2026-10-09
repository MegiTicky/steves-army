package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.network.CoverStateDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World-space visualization of the cover state machine: a per-soldier card
 * with the cover/peek/mode state, the seek-loop counters, the search pipeline,
 * the relocation state, and the follow-blocker chain, plus MG anchor and
 * suppression-center markers. Toggle with {@code /stevesarmy_debug coverstate}.
 *
 * This view exists to make strands diagnosable: a soldier parked in
 * SEEKING_COVER shows red once its stuck/exhaustion/failure counters climb,
 * and the FOLLOW line names the exact gate holding the follow goal off.
 */
public final class CoverStateDebugRenderer {
    private CoverStateDebugRenderer() {}

    private static final int[] ANCHOR_COLOR = {64, 224, 255};
    private static final int[] SUPCENTER_COLOR = {255, 140, 0};
    private static final int[] RELOC_COLOR = {255, 64, 255};

    private static final int GREEN = 0xFF50FF50;
    private static final int YELLOW = 0xFFFFD040;
    private static final int RED = 0xFFFF5050;
    private static final int GRAY = 0xFFB0B0B0;

    public static void render(PoseStack poseStack, Camera camera) {
        if (!ClientCoverStateDebugData.INSTANCE.renderEnabled()) return;
        List<CoverStateDebugPacket> snapshots = ClientCoverStateDebugData.INSTANCE.freshSnapshots();
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
        for (CoverStateDebugPacket snapshot : snapshots) {
            renderMarkers(buffer, poseStack.last().pose(), cameraPos, snapshot);
        }
        tesselator.end();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (CoverStateDebugPacket snapshot : snapshots) {
            renderCard(mc.font, poseStack, cameraPos, snapshot, buffers);
        }
        buffers.endBatch();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderMarkers(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                      CoverStateDebugPacket snapshot) {
        Vec3 pos = snapshot.soldierPos();
        if (snapshot.mgPipeline()) {
            Vec3 anchor = snapshot.mgAnchor();
            if (anchor != null) {
                line(buffer, matrix, cameraPos, pos, anchor,
                    ANCHOR_COLOR[0], ANCHOR_COLOR[1], ANCHOR_COLOR[2]);
                cross(buffer, matrix, cameraPos, anchor, 0.5,
                    ANCHOR_COLOR[0], ANCHOR_COLOR[1], ANCHOR_COLOR[2]);
                line(buffer, matrix, cameraPos, anchor, anchor.add(0, 1.4, 0),
                    ANCHOR_COLOR[0], ANCHOR_COLOR[1], ANCHOR_COLOR[2]);
            }
            Vec3 suppressionCenter = snapshot.suppressionCenter();
            if (suppressionCenter != null) {
                cross(buffer, matrix, cameraPos, suppressionCenter, 0.6,
                    SUPCENTER_COLOR[0], SUPCENTER_COLOR[1], SUPCENTER_COLOR[2]);
            }
        }
        Vec3 relocCenter = snapshot.relocCenter();
        if (relocCenter != null && !"NONE".equals(snapshot.relocType())) {
            line(buffer, matrix, cameraPos, pos, relocCenter,
                RELOC_COLOR[0], RELOC_COLOR[1], RELOC_COLOR[2]);
            cross(buffer, matrix, cameraPos, relocCenter, 0.6,
                RELOC_COLOR[0], RELOC_COLOR[1], RELOC_COLOR[2]);
            line(buffer, matrix, cameraPos, relocCenter, relocCenter.add(0, 2.2, 0),
                RELOC_COLOR[0], RELOC_COLOR[1], RELOC_COLOR[2]);
        }
    }

    private static void renderCard(Font font, PoseStack poseStack, Vec3 cameraPos,
                                   CoverStateDebugPacket snapshot, MultiBufferSource.BufferSource buffers) {
        Vec3 base = snapshot.soldierPos().add(0, 3.1, 0);

        billboard(font, poseStack, cameraPos, base,
            stateLine(snapshot), stateColor(snapshot), buffers, 0.0);
        billboard(font, poseStack, cameraPos, base,
            countersLine(snapshot), snapshot.wantCover() ? 0xFFE0E0E0 : GRAY, buffers, -0.25);
        billboard(font, poseStack, cameraPos, base,
            pipelineLine(snapshot), GRAY, buffers, -0.5);
        if (!snapshot.followBlockedBy().isEmpty()) {
            boolean stranded = snapshot.ownerDist() > 20.0f;
            billboard(font, poseStack, cameraPos, base,
                String.format("FOLLOW blocked-by=%s (%.0fm)",
                    snapshot.followBlockedBy(), snapshot.ownerDist()),
                stranded ? RED : YELLOW, buffers, -0.75);
        }
        if (snapshot.traveling() || snapshot.travelCrawl()
                || snapshot.crawlHidesHere() || snapshot.travelReroutesServed() > 0) {
            billboard(font, poseStack, cameraPos, base,
                travelLine(snapshot),
                snapshot.travelCrawl() ? YELLOW : GRAY, buffers, -1.25);
        }
        if (snapshot.mgPipeline()) {
            billboard(font, poseStack, cameraPos, base,
                mgLine(snapshot), GRAY, buffers, -1.0);
        }
        if (!snapshot.fireGate().isEmpty()) {
            billboard(font, poseStack, cameraPos, base,
                "FIRE " + snapshot.fireGate(), YELLOW, buffers, -1.5);
        }
    }

    private static String stateLine(CoverStateDebugPacket snapshot) {
        return shortRole(snapshot.role()) + ' ' + snapshot.coverState()
            + '/' + snapshot.peekState()
            + ' ' + snapshot.squadMode()
            + postureSegment(snapshot)
            + (snapshot.emergencyPosture() ? " EMR" : "")
            + (snapshot.pinned() ? " PINNED" : "");
    }

    /**
     * Posture diagnostics: server pose, the LOW_CROUCH flag the pose gate obeys,
     * and the claimed cover's type + synced height. A standing-looking soldier
     * whose segment says crouch/prone is a RENDER mismatch, not a state problem;
     * STANDING with cover type HALF and height >= 1.3 is the pose gate's choice.
     */
    private static String postureSegment(CoverStateDebugPacket snapshot) {
        StringBuilder seg = new StringBuilder(" ");
        if (snapshot.currentCoverType() != null && !snapshot.currentCoverType().isEmpty()) {
            seg.append(snapshot.currentCoverType());
            if (snapshot.currentCoverHeight() >= 0) {
                seg.append(String.format(" h=%.1f", snapshot.currentCoverHeight()));
            }
            seg.append(' ');
        }
        seg.append(snapshot.posture());
        if (snapshot.lowCrouching()) {
            seg.append("(low)");
        }
        return seg.toString();
    }

    /**
     * Suppressed-traveler doctrine segment: moving/crawling state and the
     * served-reroute counter. CRAWL+ = engaged and the map underfoot still
     * says crawling hides; a bare rr count means the doctrine armed but the
     * soldier never crossed crawl-hidden ground.
     */
    private static String travelLine(CoverStateDebugPacket snapshot) {
        StringBuilder line = new StringBuilder(snapshot.traveling() ? "TRAVEL" : "travel");
        if (snapshot.travelCrawl()) {
            line.append(" CRAWL").append(snapshot.crawlHidesHere() ? '+' : '?');
        } else if (snapshot.crawlHidesHere()) {
            line.append(" crawlHides");
        }
        line.append(" rr=").append(snapshot.travelReroutesServed());
        return line.toString();
    }

    private static String countersLine(CoverStateDebugPacket snapshot) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("sup=%.2f", snapshot.suppressionLevel()));
        line.append(snapshot.wantCover()
            ? " want=Y(" + snapshot.wantCoverReason() + ')'
            : " want=N");
        if (snapshot.goalCooldown() > 0) {
            line.append(" cd=").append(snapshot.goalCooldown());
        }
        line.append(String.format(" seek=%d/%d stuck=%d/%d",
            snapshot.seekingTicks(), snapshot.seekingTimeout(),
            snapshot.stuckTicks(), snapshot.maxStuckTicks()));
        if (snapshot.noProgressTicks() > 0) {
            line.append(" np=").append(snapshot.noProgressTicks());
        }
        if (snapshot.searchExhaustionTicks() > 0) {
            line.append(" exh=").append(snapshot.searchExhaustionTicks());
        }
        line.append(String.format(" f=%d bl=%d/%ds",
            snapshot.failedSearches(), snapshot.blacklistSize(), snapshot.blacklistClearInSec()));
        return line.toString();
    }

    private static String pipelineLine(CoverStateDebugPacket snapshot) {
        StringBuilder line = new StringBuilder();
        line.append(snapshot.searchPending()
            ? "q=" + snapshot.queuedMode() + " pend"
            : "q-idle");
        if (snapshot.schedulerPending() > 0) {
            line.append(" sched=").append(snapshot.schedulerPending());
        }
        line.append(" reloc=").append(snapshot.relocType());
        if (snapshot.relocCenter() != null && !"NONE".equals(snapshot.relocType())) {
            line.append('@').append(shortPos(snapshot.relocCenter()));
        }
        if (snapshot.relocBackoffTicks() > 0) {
            line.append(" b=").append(snapshot.relocBackoffTicks());
        }
        return line.toString();
    }

    private static String mgLine(CoverStateDebugPacket snapshot) {
        StringBuilder line = new StringBuilder();
        Vec3 anchor = snapshot.mgAnchor();
        if (anchor != null) {
            line.append("anchor@").append(shortPos(anchor))
                .append(' ').append(snapshot.mgAnchorAgeTicks()).append('t');
        } else {
            line.append("anchor-");
        }
        Vec3 suppressionCenter = snapshot.suppressionCenter();
        if (suppressionCenter != null) {
            line.append(" supC@").append(shortPos(suppressionCenter));
            int fresh = snapshot.supCenterFreshTicks();
            line.append(fresh >= 0 ? " f=" + fresh : " stale");
        } else {
            line.append(" supC-");
        }
        Vec3 lane = snapshot.laneTarget();
        if (lane != null) {
            line.append(" lane@").append(shortPos(lane));
        }
        return line.toString();
    }

    private static int stateColor(CoverStateDebugPacket snapshot) {
        boolean looping = "SEEKING_COVER".equals(snapshot.coverState())
            && (snapshot.stuckTicks() > snapshot.maxStuckTicks() / 2
                || snapshot.searchExhaustionTicks() > 0
                || snapshot.failedSearches() >= 2);
        boolean followStranded = !snapshot.followBlockedBy().isEmpty()
            && snapshot.ownerDist() > 20.0f;
        if (looping || followStranded) return RED;
        boolean elevated = snapshot.seekingTicks() > snapshot.seekingTimeout() / 2
            || snapshot.goalCooldown() > 0 || snapshot.pinned();
        if (elevated) return YELLOW;
        return GREEN;
    }

    private static String shortRole(String role) {
        return switch (role) {
            case "MACHINE_GUNNER" -> "MG";
            case "SUPPORT" -> "SUP";
            case "ANTI_TANK" -> "AT";
            case "VEHICLE_CREW" -> "CREW";
            default -> role;
        };
    }

    private static String shortPos(Vec3 pos) {
        return (int) Math.floor(pos.x) + "," + (int) Math.floor(pos.z);
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

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 basePosition,
                                  String text, int color, MultiBufferSource.BufferSource buffers,
                                  double yOffset) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 relative = basePosition.add(0, yOffset, 0).subtract(cameraPos);
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.02f, -0.02f, 0.02f);
        font.drawInBatch(text, -font.width(text) / 2.0f, 0, color, false, poseStack.last().pose(), buffers,
            Font.DisplayMode.NORMAL, 0, 15728880);
        poseStack.popPose();
    }
}

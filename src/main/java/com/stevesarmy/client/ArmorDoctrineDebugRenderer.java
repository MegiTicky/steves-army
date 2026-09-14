package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.ArmorDoctrineDebugManager;
import com.stevesarmy.network.ArmorDoctrineDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World-space visualization of the armor doctrine: hard-target contacts and
 * their projected path corridors, plus each nearby soldier's role and gate
 * state (hunter, exposed, displace, ducked, suppression claim).
 */
public final class ArmorDoctrineDebugRenderer {
    private ArmorDoctrineDebugRenderer() {}

    private static final int PATH_LOOKAHEAD_TICKS = 100;

    public static void render(PoseStack poseStack, Camera camera) {
        int mode = ClientArmorDoctrineDebugData.INSTANCE.mode();
        List<ArmorDoctrineDebugPacket.Contact> contacts = ClientArmorDoctrineDebugData.INSTANCE.contacts();
        if (mode == ArmorDoctrineDebugManager.OFF
            || (contacts.isEmpty() && ClientArmorDoctrineDebugData.INSTANCE.soldiersById().isEmpty())) {
            return;
        }
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
        for (ArmorDoctrineDebugPacket.Contact contact : contacts) {
            renderContactLines(buffer, poseStack.last().pose(), cameraPos, contact, mode);
        }
        for (ArmorDoctrineDebugPacket.Soldier soldier : ClientArmorDoctrineDebugData.INSTANCE.soldiersById().values()) {
            renderSoldierLines(buffer, poseStack.last().pose(), cameraPos, contacts, soldier);
        }
        tesselator.end();

        if (mode == ArmorDoctrineDebugManager.VERBOSE) {
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            for (ArmorDoctrineDebugPacket.Contact contact : contacts) {
                renderContactLabel(mc.font, poseStack, cameraPos, contact, buffers);
            }
            for (ArmorDoctrineDebugPacket.Soldier soldier : ClientArmorDoctrineDebugData.INSTANCE.soldiersById().values()) {
                renderSoldierLabel(mc.font, poseStack, cameraPos, soldier, buffers);
            }
            buffers.endBatch();
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderContactLines(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                           ArmorDoctrineDebugPacket.Contact contact, int mode) {
        int[] classColor = classColor(contact.vehicleClass());

        // War Thunder-style marker: bracket frame above the vehicle, colored by
        // class, with a drop line to the aim point.
        Vec3 markerCenter = contact.aimPoint().add(0, 2.6, 0);
        double half = 1.1;
        square(buffer, matrix, cameraPos, markerCenter, half, classColor[0], classColor[1], classColor[2]);
        line(buffer, matrix, cameraPos, markerCenter, contact.aimPoint(), classColor[0], classColor[1], classColor[2]);

        // Gun marker: aim point with a red drop line to the ground.
        line(buffer, matrix, cameraPos, contact.aimPoint(), contact.aimPoint().add(0, -3, 0), 255, 64, 64);
        cross(buffer, matrix, cameraPos, contact.aimPoint(), 0.6, 255, 96, 96);

        // Hull footprint: white ground cross.
        cross(buffer, matrix, cameraPos, contact.hullCenter(), 1.2, 255, 255, 255);

        // Hull silhouette box (VERBOSE): the surface any-block hull LOS tests against.
        if (mode == ArmorDoctrineDebugManager.VERBOSE) {
            renderHullBox(buffer, matrix, cameraPos, contact);
        }

        if (contact.velocity() != null && contact.velocity().horizontalDistance() >= 0.01) {
            Vec3 end = contact.hullCenter().add(contact.velocity().scale(PATH_LOOKAHEAD_TICKS));
            int[] pathColor = contact.suppressed() ? new int[] {255, 64, 255} : new int[] {255, 160, 32};
            line(buffer, matrix, cameraPos, contact.hullCenter(), end, pathColor[0], pathColor[1], pathColor[2]);
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

    /** Marker colors by vehicle class: tank red, hull MG orange, vehicle white. */
    private static int[] classColor(int vehicleClass) {
        return switch (vehicleClass) {
            case com.stevesarmy.squad.SquadThreatIntel.VC_TANK -> new int[] {255, 64, 48};
            case com.stevesarmy.squad.SquadThreatIntel.VC_HULL_MG -> new int[] {255, 160, 32};
            default -> new int[] {255, 255, 255};
        };
    }

    /**
     * Draws the hull corner box. Corners arrive ordered cx*4 + cy*2 + cz
     * (z fastest), so flipping one index bit walks to the edge neighbor.
     */
    private static void renderHullBox(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                      ArmorDoctrineDebugPacket.Contact contact) {
        Vec3[] corners = contact.hullCorners();
        if (corners == null || corners.length != 8) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            for (int bit = 0; bit < 3; bit++) {
                int j = i ^ (1 << bit);
                if (j > i) {
                    line(buffer, matrix, cameraPos, corners[i], corners[j], 255, 255, 255);
                }
            }
        }
    }

    private static void renderSoldierLines(BufferBuilder buffer, Matrix4f matrix, Vec3 cameraPos,
                                           List<ArmorDoctrineDebugPacket.Contact> contacts,
                                           ArmorDoctrineDebugPacket.Soldier soldier) {
        ArmorDoctrineDebugPacket.Contact claimed = findContact(contacts, soldier.suppressionTargetId());
        if (claimed != null) {
            // Suppression claim on the vehicle: magenta.
            line(buffer, matrix, cameraPos, soldier.pos(), claimed.aimPoint(), 255, 64, 255);
            return;
        }
        if (soldier.hunter()) {
            ArmorDoctrineDebugPacket.Contact nearest = nearestContact(contacts, soldier.pos());
            if (nearest != null) {
                // Hunter: green while it can shoot, dim green when gated.
                int[] color = soldier.ducked() ? new int[] {64, 160, 64} : new int[] {64, 255, 64};
                line(buffer, matrix, cameraPos, soldier.pos(), nearest.aimPoint(), color[0], color[1], color[2]);
            }
            return;
        }
        if (soldier.squadHasAt()) {
            ArmorDoctrineDebugPacket.Contact nearest = nearestContact(contacts, soldier.pos());
            if (nearest != null && (soldier.exposed() || soldier.displace() || soldier.ducked())) {
                int[] color = soldier.displace() ? new int[] {255, 160, 32}
                    : soldier.ducked() ? new int[] {96, 96, 255} : new int[] {255, 220, 64};
                line(buffer, matrix, cameraPos, soldier.pos(), nearest.aimPoint(), color[0], color[1], color[2]);
            }
        }
        if (soldier.coverPos() != null) {
            line(buffer, matrix, cameraPos, soldier.pos(), soldier.coverPos(), 160, 160, 160);
        }
        // Resolved firing solution: where the AI believes the shootable hull is.
        if (soldier.firingSolution() != null) {
            line(buffer, matrix, cameraPos, soldier.pos(), soldier.firingSolution(), 64, 224, 255);
            cross(buffer, matrix, cameraPos, soldier.firingSolution(), 0.35, 64, 224, 255);
        }
    }

    private static ArmorDoctrineDebugPacket.Contact findContact(
            List<ArmorDoctrineDebugPacket.Contact> contacts, java.util.UUID threatId) {
        if (threatId == null) return null;
        for (ArmorDoctrineDebugPacket.Contact contact : contacts) {
            if (contact.threatId().equals(threatId)) return contact;
        }
        return null;
    }

    private static ArmorDoctrineDebugPacket.Contact nearestContact(
            List<ArmorDoctrineDebugPacket.Contact> contacts, Vec3 pos) {
        ArmorDoctrineDebugPacket.Contact nearest = null;
        double best = Double.MAX_VALUE;
        for (ArmorDoctrineDebugPacket.Contact contact : contacts) {
            double dist = contact.aimPoint().distanceToSqr(pos);
            if (dist < best) {
                best = dist;
                nearest = contact;
            }
        }
        return nearest;
    }

    private static void renderContactLabel(Font font, PoseStack poseStack, Vec3 cameraPos,
                                           ArmorDoctrineDebugPacket.Contact contact,
                                           MultiBufferSource.BufferSource buffers) {
        StringBuilder text = new StringBuilder(switch (contact.vehicleClass()) {
            case com.stevesarmy.squad.SquadThreatIntel.VC_TANK -> "TANK";
            case com.stevesarmy.squad.SquadThreatIntel.VC_HULL_MG -> "HULL MG";
            default -> "VEHICLE";
        });
        if (contact.suppressed()) text.append(" [SUPPRESSED]");
        if (contact.velocity() == null || contact.velocity().horizontalDistance() < 0.01) {
            text.append(" stationary");
        } else {
            text.append(String.format(" %.1f b/t", contact.velocity().horizontalDistance()));
        }
        int[] classColor = classColor(contact.vehicleClass());
        int argb = 0xFF000000 | (classColor[0] << 16) | (classColor[1] << 8) | classColor[2];
        billboard(font, poseStack, cameraPos, contact.aimPoint().add(0, 4.0, 0), text.toString(),
            0, contact.suppressed() ? 0xFFFF40FF : argb, buffers);
    }

    private static void renderSoldierLabel(Font font, PoseStack poseStack, Vec3 cameraPos,
                                           ArmorDoctrineDebugPacket.Soldier soldier,
                                           MultiBufferSource.BufferSource buffers) {
        StringBuilder text = new StringBuilder(soldier.hunter() ? "HUNTER" : "RIFLEMAN");
        if (!soldier.squadHasAt() && !soldier.hunter()) text.append(" no-AT");
        if (soldier.hunter() && soldier.blockReason() != null) {
            text.append(" ").append(soldier.blockReason());
        }
        if (soldier.exposed()) text.append(" exposed");
        if (soldier.ducked()) text.append(" ducked");
        if (soldier.displace()) text.append(" DISPLACE");
        if (soldier.suppressionTargetId() != null) text.append(" buttoning");
        int color = soldier.hunter() ? 0xFF40FF40 : soldier.displace() ? 0xFFFFA020 : 0xFFFFFFFF;
        billboard(font, poseStack, cameraPos, soldier.pos().add(0, 0.5, 0), text.toString(), 0, color, buffers);
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

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 position, String text,
                                  int y, int color, MultiBufferSource.BufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 relative = position.subtract(cameraPos);
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);
        font.drawInBatch(text, -font.width(text) / 2.0f, y, color, false, poseStack.last().pose(), buffers,
            Font.DisplayMode.NORMAL, 0, 15728880);
        poseStack.popPose();
    }
}

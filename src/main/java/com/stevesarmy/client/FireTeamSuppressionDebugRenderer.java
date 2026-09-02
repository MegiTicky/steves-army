package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.AttackDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

public final class FireTeamSuppressionDebugRenderer {
    private FireTeamSuppressionDebugRenderer() {}

    private static boolean enabled = false;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }
    public static void toggle() { enabled = !enabled; }

    public static void render(PoseStack poseStack, Camera camera) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.font == null) return;

        Font font = mc.font;
        Vec3 cameraPos = camera.getPosition();
        Level level = mc.level;

        Map<UUID, AttackDebugPacket.Entry> entries =
            ClientAttackDebugData.INSTANCE.getEntries();
        if (entries.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                mc.player.getBoundingBox().inflate(50))) {

            AttackDebugPacket.Entry entry = entries.get(soldier.getUUID());
            if (entry == null) continue;

            Vec3 soldierPos = soldier.position();
            double x = soldierPos.x - cameraPos.x + 0.5;
            double y = soldierPos.y - cameraPos.y + 2.4;
            double z = soldierPos.z - cameraPos.z + 0.5;

            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);

            int lineOffset = 0;

            // Line 1: Fireteam name + level + state
            String ftStateName = entry.fireteamState() == 2 ? " HEAVY"
                : entry.fireteamState() == 1 ? " SUPP" : "";
            String line1 = "--- " + entry.fireTeamName()
                + " " + String.format("%.2f", entry.fireteamLevel())
                + ftStateName + " ---";
            int ftColor = entry.fireteamState() == 2 ? 0xFFFF5555
                : entry.fireteamState() == 1 ? 0xFFFFAA00 : 0xFF55FF55;
            draw(font, line1, lineOffset, ftColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 2: Attack phase
            String phaseName = switch (entry.attackPhase()) {
                case 1 -> "SELECTING";
                case 2 -> "MOVING";
                case 3 -> "OCCUPYING";
                case 4 -> "COMPLETE";
                default -> "NONE";
            };
            int dwellColor = entry.dwellFraction() >= 1.0f ? 0xFF55FF55 : 0xFFFFFF55;
            String line2 = "Phase: " + phaseName;
            draw(font, line2, lineOffset, dwellColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 3: Dwell breakdown
            int dwellMs = (int) entry.dwellElapsedMs();
            int reqMs = (int) entry.requiredDwellMs();
            String line3 = "Dwell: " + dwellMs + "/" + reqMs + "ms"
                + " (base 4000 x" + String.format("%.1f", entry.suppressionDwellMult())
                + " supp x" + String.format("%.1f", entry.groupCohesionMult()) + " coh)";
            draw(font, line3, lineOffset, dwellColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 4: Individual suppression
            String suppState = entry.suppressionLevel() >= 0.9f ? "PINNED"
                : entry.suppressionLevel() >= 0.5f ? "SUPP" : "CLEAR";
            int suppColor = entry.suppressionLevel() >= 0.9f ? 0xFFFF5555
                : entry.suppressionLevel() >= 0.5f ? 0xFFFFAA00 : 0xFF55FF55;
            String line4 = "Supp: " + String.format("%.2f", entry.suppressionLevel())
                + " " + suppState + (entry.individualSuppressed() ? " [pressured]" : "")
                + (entry.recovered() ? " rec=V" : " rec=X");
            draw(font, line4, lineOffset, suppColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 5: All four canAdvance predicates as individual V/X flags
            String line5 = "AdvGate: dw=" + (entry.dwellMet() ? "V" : "X")
                + " rc=" + (entry.recovered() ? "V" : "X")
                + " fp=" + (entry.fireteamPinned() ? "X" : "V")
                + " hh=" + (entry.heavyHold() ? "X" : "V");
            int gateColor = entry.canAdvance() ? 0xFF55FF55 : 0xFFFFAA00;
            draw(font, line5, lineOffset, gateColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 5: Advance reason + peek latch + dwell progress
            String advanceReason;
            if (!entry.dwellMet()) {
                advanceReason = "WAIT_DWELL";
            } else if (!entry.recovered()) {
                advanceReason = "WAIT_RECOVERY";
            } else if (entry.fireteamPinned()) {
                advanceReason = "FIRETEAM_PINNED";
            } else if (entry.heavyHold()) {
                advanceReason = "HEAVY_HOLD";
            } else if (!entry.peekCompleted()) {
                advanceReason = "NO_PEEK_LATCH";
            } else {
                advanceReason = "CAN_ADVANCE";
            }
            int advColor = entry.canAdvance() ? 0xFF55FF55 : 0xFFFFAA00;
            String line6 = "Adv: " + advanceReason
                + "  pkDone=" + (entry.peekCompleted() ? "V" : "X");
            draw(font, line6, lineOffset, advColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 7: Target + position + centroid offset
            Vec3 pos = entry.position();
            Vec3 cent = entry.centroidPos();
            String line7 = "Tgt: " + (entry.hasTarget() ? "yes" : "no")
                + "  pos: " + formatPos(pos);
            draw(font, line7, lineOffset, 0xAAAAAA, poseStack, bufferSource);
            lineOffset += 10;

            if (cent != null && (entry.attackPhase() == 3 || entry.attackPhase() == 1)) {
                // Show centroid offset only during attack (OCCUPYING or SELECTING)
                double dx = cent.x - pos.x;
                double dz = cent.z - pos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                String side;
                // Project onto objective direction is complex; just show raw distance
                side = String.format("%.0f blocks from centroid", dist);
                int cohColor = entry.groupCohesionMult() > 1.0f ? 0xFFFFAA00
                    : entry.groupCohesionMult() < 1.0f ? 0xFF55FF55 : 0xAAAAAA;
                String line8 = "Cohesion: " + side;
                draw(font, line8, lineOffset, cohColor, poseStack, bufferSource);
            }
            lineOffset += 10;

            // Diagnostic line: cover state + search + fallback
            String coverStateName = switch (entry.coverState()) {
                case 1 -> "SEEKING";
                case 2 -> "IN_COVER";
                case 3 -> "SUPP_COVER";
                case 4 -> "REPOS";
                default -> "NO_COVER";
            };
            String line9 = "Cover: " + coverStateName
                + " | srch=" + (entry.coverSearchPending() ? "V" : "X")
                + " | fb=" + (entry.fallbackAdvanceActive() ? "V" : "X");
            draw(font, line9, lineOffset, 0xAAAAAA, poseStack, bufferSource);
            lineOffset += 10;

            // Diagnostic line: phase age + last advance trigger age
            long phaseAge = entry.phaseAgeMs();
            long advAge = entry.lastAdvanceTriggerAgeMs();
            String advAgeStr = advAge == Long.MAX_VALUE ? "never" : advAge + "ms";
            int phaseAgeColor = phaseAge > 10000 ? 0xFFFF5555 : phaseAge > 5000 ? 0xFFFFAA00 : 0xFF55FF55;
            int advAgeColor = advAge > 5000 ? 0xFFFF5555 : advAge > 2000 ? 0xFFFFAA00 : 0xFF55FF55;
            String line10 = "PhaseAge: " + phaseAge + "ms"
                + "  LastAdv: " + advAgeStr;
            draw(font, line10, lineOffset, phaseAgeColor, poseStack, bufferSource);
            lineOffset += 10;

            poseStack.popPose();
        }

        bufferSource.endBatch();
        RenderSystem.disableBlend();
    }

    private static void draw(Font font, String text, int y, int color,
                              PoseStack poseStack, MultiBufferSource bufferSource) {
        font.drawInBatch(text, -font.width(text) / 2.0f, y,
            color | 0xFF000000, false,
            poseStack.last().pose(), bufferSource,
            Font.DisplayMode.NORMAL, 0, 15728880);
    }

    private static String formatPos(Vec3 pos) {
        return String.format("%.0f,%.0f,%.0f", pos.x, pos.y, pos.z);
    }
}

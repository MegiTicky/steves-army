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

            // Line 2: Attack phase + dwell
            String phaseName = switch (entry.attackPhase()) {
                case 1 -> "SELECTING";
                case 2 -> "MOVING";
                case 3 -> "OCCUPYING";
                case 4 -> "COMPLETE";
                default -> "NONE";
            };
            int dwellPct = (int) Math.min(200, entry.dwellFraction() * 100);
            int dwellColor = entry.dwellFraction() >= 1.0f ? 0xFF55FF55 : 0xFFFFFF55;
            String line2 = "Phase: " + phaseName + "  dwell: " + dwellPct + "%";
            draw(font, line2, lineOffset, dwellColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 3: Individual suppression
            String suppState = entry.suppressionLevel() >= 0.9f ? "PINNED"
                : entry.suppressionLevel() >= 0.5f ? "SUPP" : "CLEAR";
            int suppColor = entry.suppressionLevel() >= 0.9f ? 0xFFFF5555
                : entry.suppressionLevel() >= 0.5f ? 0xFFFFAA00 : 0xFF55FF55;
            String line3 = "Supp: " + String.format("%.2f", entry.suppressionLevel())
                + " " + suppState + (entry.individualSuppressed() ? " [pressured]" : "")
                + (entry.recovered() ? " rec=V" : " rec=X");
            draw(font, line3, lineOffset, suppColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 4: Gate states + advance blockers
            String line4 = "Gates: heavy="
                + (entry.heavyHold() ? "BLOCKED" : "ok")
                + "  peek=" + (entry.peeking() ? "active" : "ok")
                + "  safe=" + (entry.safetyPeekDone() ? "ok" : "pending")
                + "  ftPinned=" + (entry.fireteamPinned() ? "YES" : "no");
            int gateColor = (entry.heavyHold() || entry.fireteamPinned())
                ? 0xFFFF5555 : 0xFF55FF55;
            draw(font, line4, lineOffset, gateColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 5: Advance reason — shows why soldier can/cannot move
            boolean canNormalAdvance = entry.dwellMet() && entry.recovered() && !entry.peeking()
                && !entry.fireteamPinned() && !entry.heavyHold() && entry.softCoverAllowed();
            String advanceReason;
            if (!entry.dwellMet()) {
                advanceReason = "WAIT_DWELL";
            } else if (!entry.recovered()) {
                advanceReason = "WAIT_RECOVERY";
            } else if (entry.peeking()) {
                advanceReason = "CURRENTLY_PEEKING";
            } else if (entry.fireteamPinned()) {
                advanceReason = "FIRETEAM_PINNED";
            } else if (entry.heavyHold()) {
                advanceReason = "HEAVY_HOLD";
            } else if (!entry.softCoverAllowed()) {
                advanceReason = "WAITING_COVER";
            } else if (!entry.attackHasPeeked()) {
                advanceReason = "NO_PEEK_LATCH";
            } else {
                advanceReason = "CAN_ADVANCE";
            }
            int advColor = canNormalAdvance || advanceReason.equals("CAN_ADVANCE")
                ? 0xFF55FF55 : 0xFFFFAA00;
            String line5 = "Adv: " + advanceReason
                + "  peekLatch=" + (entry.attackHasPeeked() ? "V" : "X")
                + "  soft=" + (entry.softCoverAllowed() ? "V" : "X");
            draw(font, line5, lineOffset, advColor, poseStack, bufferSource);
            lineOffset += 10;

            // Line 6: Target + position
            String line6 = "Tgt: " + (entry.hasTarget() ? "yes" : "no")
                + "  pos: " + formatPos(entry.position());
            draw(font, line6, lineOffset, 0xAAAAAA, poseStack, bufferSource);

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

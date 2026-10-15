package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.MovementSpeedDebugPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** World labels and a focused HUD for the server's movement-speed diagnostics. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, value = Dist.CLIENT)
public final class MovementSpeedDebugRenderer {
    private static final double WORLD_RANGE_SQR = 32.0 * 32.0;
    private static final int MAX_WORLD_LABELS = 16;
    private static final double AIM_DOT_THRESHOLD = 0.965925826; // 15 degrees

    private MovementSpeedDebugRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderWorld(event.getPoseStack(), event.getCamera());
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.player == null || mc.font == null) return;
        renderHud(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMovementSpeedDebugData.INSTANCE.clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) ClientMovementSpeedDebugData.INSTANCE.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().level == null) {
            ClientMovementSpeedDebugData.INSTANCE.clear();
        }
    }

    private static void renderWorld(PoseStack poseStack, Camera camera) {
        ClientMovementSpeedDebugData data = ClientMovementSpeedDebugData.INSTANCE;
        if (!data.renderEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.font == null) return;

        Vec3 cameraPos = camera.getPosition();
        List<MovementSpeedDebugPacket.Entry> nearby = new ArrayList<>();
        for (MovementSpeedDebugPacket.Entry entry : data.freshSnapshots()) {
            if (entry.position().distanceToSqr(cameraPos) <= WORLD_RANGE_SQR) nearby.add(entry);
        }
        nearby.sort(Comparator.comparingDouble(entry -> entry.position().distanceToSqr(cameraPos)));

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int count = 0;
        for (MovementSpeedDebugPacket.Entry entry : nearby) {
            if (count++ >= MAX_WORLD_LABELS) break;
            String name = trim(entry.name(), 18);
            String speed = String.format("%s %.2f b/t (%.1f b/s) cap %.2f b/t peak %.2f%s",
                name, entry.measuredSpeed(), entry.measuredSpeed() * 20.0,
                entry.appliedSpeed(), entry.peakSpeed(), entry.impulseBypass() ? " IMPULSE" : "");
            int color = entry.impulseBypass() ? 0xFFFF55FF
                : entry.measuredSpeed() > entry.appliedSpeed() + 0.035 ? 0xFFFF6655 : 0xFFB8F5C2;
            String owner = "owner " + trim(entry.movementOwner(), 20);
            Vec3 labelPos = entry.position().add(0, 2.6, 0);
            billboard(mc.font, poseStack, cameraPos, labelPos, speed, 0, color, buffers);
            billboard(mc.font, poseStack, cameraPos, labelPos, owner, 10, 0xFFD0D8E0, buffers);
        }
        buffers.endBatch();
    }

    private static void renderHud(GuiGraphics graphics) {
        ClientMovementSpeedDebugData data = ClientMovementSpeedDebugData.INSTANCE;
        if (!data.renderEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        List<MovementSpeedDebugPacket.Entry> snapshots = data.freshSnapshots();
        MovementSpeedDebugPacket.Entry focused = findAimedSoldier(mc, snapshots);
        Font font = mc.font;

        List<String> lines = new ArrayList<>();
        lines.add("MOVEMENT SPEED DEBUG  (blocks/tick; blocks/s = x20)");
        if (snapshots.isEmpty()) {
            lines.add("Waiting for nearby soldier telemetry...");
        } else if (focused == null) {
            lines.add("No soldier within 15 degrees of aim (max 32m)");
            lines.add(snapshots.size() + " soldier snapshot(s) received");
        } else {
            lines.add(trim(focused.name(), 24) + "  #" + focused.entityId());
            lines.add(String.format("actual %.3f b/t (%.2f b/s)  avg %.3f  peak5s %.3f",
                focused.measuredSpeed(), focused.measuredSpeed() * 20.0, focused.averageSpeed(), focused.peakSpeed()));
            lines.add(String.format("request %.3f / attr %.3f / target %.3f / cap %.3f b/t",
                focused.requestedModifier(), focused.attributeSpeed(), focused.targetSpeed(), focused.appliedSpeed()));
            lines.add("limit: " + trim(focused.limitingReason(), 40));
            lines.add("owner: " + trim(focused.movementOwner(), 44));
            lines.add(String.format("incoming %.3f b/t  budget %.3f spent  impulse bypass: %s",
                focused.incomingSpeed(), focused.spentBudget(), focused.impulseBypass() ? "YES" : "no"));
            lines.add(String.format("cover %s  posture %s  grounded %s  collision %s",
                trim(focused.coverState(), 12), trim(focused.posture(), 12), focused.onGround(), focused.collision()));
        }

        int x = 8;
        int y = 8;
        int maxWidth = Math.max(1, mc.getWindow().getGuiScaledWidth() - 16);
        int width = Math.min(maxWidth, Math.max(160,
            lines.stream().mapToInt(font::width).max().orElse(160) + 16));
        int lineHeight = font.lineHeight + 2;
        int chartHeight = focused == null ? 0 : 30;
        int height = lines.size() * lineHeight + 8 + chartHeight;
        graphics.fill(x, y, x + width, y + height, 0xB010141A);
        graphics.fill(x, y, x + 2, y + height, 0xFF56D6A0);
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFF7FE8BC : 0xFFE8EDF2;
            String visible = font.plainSubstrByWidth(lines.get(i), Math.max(0, width - 16));
            graphics.drawString(font, visible, x + 8, y + 5 + i * lineHeight, color, false);
        }
        if (focused != null) renderHistoryChart(graphics, x + width - 102,
            y + 5 + lines.size() * lineHeight, 92, 24,
            data.recentHistory(focused.entityId()));
    }

    private static MovementSpeedDebugPacket.Entry findAimedSoldier(Minecraft mc,
            List<MovementSpeedDebugPacket.Entry> snapshots) {
        if (mc.player == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle().normalize();
        MovementSpeedDebugPacket.Entry best = null;
        double bestDot = AIM_DOT_THRESHOLD;
        Entity hitEntity = mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
        for (MovementSpeedDebugPacket.Entry entry : snapshots) {
            if (hitEntity != null && hitEntity.getUUID().equals(entry.soldierId())
                && entry.position().distanceToSqr(eye) <= 32.0 * 32.0) return entry;
            Vec3 toward = entry.position().add(0, 1.0, 0).subtract(eye);
            double distance = toward.length();
            if (distance > 32.0 || distance < 0.001) continue;
            double dot = toward.scale(1.0 / distance).dot(look);
            if (dot >= bestDot) {
                best = entry;
                bestDot = dot;
            }
        }
        return best;
    }

    private static void renderHistoryChart(GuiGraphics graphics, int x, int y, int width, int height,
                                           List<ClientMovementSpeedDebugData.SpeedSample> samples) {
        graphics.fill(x, y, x + width, y + height, 0xA820272E);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF87919B);
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, "9s actual / cap", x + 2, y, 0xFFE8EDF2, false);
        if (samples.isEmpty()) return;
        double maxActual = samples.stream().mapToDouble(ClientMovementSpeedDebugData.SpeedSample::measuredSpeed).max().orElse(0);
        double maxCap = samples.stream().mapToDouble(ClientMovementSpeedDebugData.SpeedSample::appliedSpeed).max().orElse(0);
        double scale = Math.max(0.08, Math.max(maxActual, maxCap) * 1.15);
        int n = samples.size();
        for (int i = 0; i < n; i++) {
            int px = x + (n <= 1 ? 0 : i * (width - 1) / (n - 1));
            int plotHeight = height - 13;
            int actualY = y + height - 2 - (int) Math.round(Math.min(1,
                samples.get(i).measuredSpeed() / scale) * plotHeight);
            int capY = y + height - 2 - (int) Math.round(Math.min(1,
                samples.get(i).appliedSpeed() / scale) * plotHeight);
            graphics.fill(px, actualY, px + 2, actualY + 2, 0xFF68E6A0);
            graphics.fill(px, capY, px + 2, capY + 1, 0xFFFFC857);
        }
    }

    private static void billboard(Font font, PoseStack poseStack, Vec3 cameraPos, Vec3 position,
                                  String text, int y, int color, MultiBufferSource.BufferSource buffers) {
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

    private static String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}

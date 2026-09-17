package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.network.SidearmDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * World-space label for the sidearm fallback: the fallback state machine,
 * both guns with their ammo, cover/suppression/engagement state, and the
 * server-computed restore-blocker string.
 */
public final class SidearmDebugRenderer {
    private SidearmDebugRenderer() {}

    public static void render(PoseStack poseStack, Camera camera) {
        if (!ClientSidearmDebugData.INSTANCE.renderEnabled()
            || ClientSidearmDebugData.INSTANCE.soldiersById().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.font == null) return;

        Vec3 cameraPos = camera.getPosition();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (SidearmDebugPacket.Soldier soldier : ClientSidearmDebugData.INSTANCE.soldiersById().values()) {
            renderLabel(font(mc), poseStack, cameraPos, soldier, buffers);
        }
        buffers.endBatch();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static Font font(Minecraft mc) {
        return mc.font;
    }

    private static void renderLabel(Font font, PoseStack poseStack, Vec3 cameraPos,
                                    SidearmDebugPacket.Soldier soldier, MultiBufferSource.BufferSource buffers) {
        int statusColor = soldier.fallbackActive() ? 0xFFFFA030
            : soldier.restoreCooldown() > 0 ? 0xFFFFF040 : 0xFFE0E0E0;
        billboard(font, poseStack, cameraPos, soldier.pos().add(0, 0.4, 0),
            "SIDEARM " + soldier.status(), statusColor, buffers);

        StringBuilder held = new StringBuilder("held ").append(soldier.heldGunId());
        held.append(" ").append(soldier.heldAmmo()).append("/").append(soldier.heldMag());
        if (soldier.reloading()) held.append(" RELOAD");
        billboard(font, poseStack, cameraPos, soldier.pos().add(0, 0.15, 0), held.toString(), 0xFFC8C8C8, buffers);

        StringBuilder slots = new StringBuilder("slot4 ").append(soldier.sidearmGunId());
        slots.append(" [").append(soldier.sidearmAmmo()).append("]");
        slots.append(" | orig ").append(soldier.origMainGunId().isEmpty() ? "-" : soldier.origMainGunId());
        billboard(font, poseStack, cameraPos, soldier.pos().add(0, -0.1, 0), slots.toString(), 0xFFC8C8C8, buffers);

        StringBuilder state = new StringBuilder("cover ").append(soldier.coverState());
        state.append(" | supp ").append(soldier.suppState());
        state.append(" | eng ").append(soldier.engaged() ? "YES" : "no");
        billboard(font, poseStack, cameraPos, soldier.pos().add(0, -0.35, 0), state.toString(), 0xFF90C8FF, buffers);
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

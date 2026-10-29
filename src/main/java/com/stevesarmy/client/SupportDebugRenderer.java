package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.SupportDebugPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, value = Dist.CLIENT)
public final class SupportDebugRenderer {
    private static boolean enabled;
    private static List<SupportDebugPacket.Support> supports = List.of();
    private SupportDebugRenderer() {}

    public static void receive(boolean active, List<SupportDebugPacket.Support> entries) {
        enabled = active;
        supports = List.copyOf(entries);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { receive(false, List.of()); }

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || supports.isEmpty()) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (SupportDebugPacket.Support support : supports) {
            Vec3 relative = support.position().add(0, 1.6, 0).subtract(camera.getPosition());
            poseStack.pushPose();
            poseStack.translate(relative.x, relative.y, relative.z);
            poseStack.mulPose(camera.rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            for (int i = 0; i < support.lines().size(); i++) {
                String line = support.lines().get(i);
                mc.font.drawInBatch(line, -mc.font.width(line) / 2.0f, i * 10, 0xFFFFFFFF,
                    false, poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0x80000000, 15728880);
            }
            poseStack.popPose();
        }
        buffers.endBatch();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}

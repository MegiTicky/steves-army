package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.SquadStatusSyncPacket;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

public class FireTeamBadgeLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    public FireTeamBadgeLayer(LivingEntityRenderer<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float headYaw, float headPitch) {

        if (!(entity instanceof SoldierEntity) || entity instanceof EnemySoldierEntity) return;
        if (entity.isInvisible()) return;

        SquadStatusSyncPacket.SoldierStatusEntry entry = ClientSquadData.INSTANCE.getEntry(entity.getUUID());
        if (entry == null) return;

        FireTeam team = entry.getFireTeam();
        if (team == FireTeam.ALL) return;

        String badge = "[" + team.getShortName() + "]";
        int color = switch (team) {
            case ALPHA -> 0xFFFF5555;
            case BRAVO -> 0xFF5555FF;
            case CHARLIE -> 0xFF55FF55;
            case DELTA -> 0xFFFFFF55;
            default -> 0xFFFFFFFF;
        };

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int halfWidth = font.width(badge) / 2;

        poseStack.pushPose();
        poseStack.translate(0.0, entity.getBbHeight() + 0.65, 0.0);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.05F, -0.05F, 0.05F);

        font.drawInBatch(Component.literal(badge), -halfWidth, 0, color, true,
            poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0x66000000, packedLight);

        poseStack.popPose();
    }
}
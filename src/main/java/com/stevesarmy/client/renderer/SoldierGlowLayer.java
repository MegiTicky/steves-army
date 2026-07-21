package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.TeamManager;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

public class SoldierGlowLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private static final ResourceLocation GLOW_TEXTURE = new ResourceLocation("minecraft", "textures/entity/creeper/creeper_armor.png");

    public SoldierGlowLayer(LivingEntityRenderer<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float headYaw, float headPitch) {

        if (!(entity instanceof SoldierEntity)) return;
        if (entity.isInvisible()) return;

        float r, g, b, a;

        if (entity instanceof EnemySoldierEntity) {
            r = 1.0f; g = 0.2f; b = 0.2f; a = 0.35f;
        } else if (TeamManager.isOnFriendlyTeam(entity)) {
            r = 0.2f; g = 0.4f; b = 1.0f; a = 0.35f;
        } else {
            return;
        }

        RenderType renderType = RenderType.entityCutoutNoCull(GLOW_TEXTURE);
        this.getParentModel().renderToBuffer(poseStack, bufferSource.getBuffer(renderType),
            packedLight, OverlayTexture.NO_OVERLAY, r, g, b, a);
    }
}
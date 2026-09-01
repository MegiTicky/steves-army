package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.stevesarmy.entity.ResupplyPouchEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class ResupplyPouchRenderer extends EntityRenderer<ResupplyPouchEntity> {
    private static final ResourceLocation TEXTURE =
        new ResourceLocation("steves_army", "textures/item/resupply_pouch.png");

    public ResupplyPouchRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.15F;
    }

    @Override
    public void render(ResupplyPouchEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.scale(0.375F, 0.375F, 0.375F);

        float spin = (entity.tickCount + partialTick) * 40.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();

        VertexConsumer vertex = buffer.getBuffer(RenderType.entityCutout(TEXTURE));

        vertex.vertex(pose, -1.0F, -1.0F, 0.0F)
            .color(255, 255, 255, 255)
            .uv(0.0F, 1.0F)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0F, 1.0F, 0.0F)
            .endVertex();
        vertex.vertex(pose, -1.0F, 1.0F, 0.0F)
            .color(255, 255, 255, 255)
            .uv(0.0F, 0.0F)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0F, 1.0F, 0.0F)
            .endVertex();
        vertex.vertex(pose, 1.0F, 1.0F, 0.0F)
            .color(255, 255, 255, 255)
            .uv(1.0F, 0.0F)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0F, 1.0F, 0.0F)
            .endVertex();
        vertex.vertex(pose, 1.0F, -1.0F, 0.0F)
            .color(255, 255, 255, 255)
            .uv(1.0F, 1.0F)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0F, 1.0F, 0.0F)
            .endVertex();

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(ResupplyPouchEntity entity) {
        return TEXTURE;
    }
}

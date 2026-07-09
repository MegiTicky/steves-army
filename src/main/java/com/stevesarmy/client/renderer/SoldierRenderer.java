package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, SoldierModel> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public SoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new SoldierModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this, 
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(SoldierEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(SoldierEntity soldier, float entityYaw, float partialTick, 
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        this.model.crouching = soldier.isCrouching();
        super.render(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Vec3 getRenderOffset(SoldierEntity soldier, float partialTick) {
        return soldier.isCrouching() ? new Vec3(0.0D, -0.125D, 0.0D) : super.getRenderOffset(soldier, partialTick);
    }

    @Override
    protected void setupRotations(SoldierEntity soldier, PoseStack poseStack, float ageInTicks, float bodyYaw, float partialTick) {
        float swim = soldier.getSwimAmount(partialTick);
        if (swim > 0.001F && soldier.isAlive()) {
            float rotX = Mth.lerp(swim, 0.0F, -90.0F);
            double transY = Mth.lerp(swim, 0.0D, -1.0D);
            double transZ = Mth.lerp(swim, 0.0D, 0.25D);

            poseStack.mulPose(new Quaternionf().rotationY(180.0F - bodyYaw * Mth.DEG_TO_RAD));
            poseStack.mulPose(new Quaternionf().rotationX(rotX * Mth.DEG_TO_RAD));
            poseStack.translate(0.0D, transY, transZ);
            return;
        }
        super.setupRotations(soldier, poseStack, ageInTicks, bodyYaw, partialTick);
    }
}
package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.entity.EnemySoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;

public class EnemySoldierRenderer extends HumanoidMobRenderer<EnemySoldierEntity, SoldierModel<EnemySoldierEntity>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public EnemySoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new SoldierModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this, 
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));
        // Vanilla glow outline via MobEffects.GLOWING + team color handles enemy glow
    }

    @Override
    public ResourceLocation getTextureLocation(EnemySoldierEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(EnemySoldierEntity soldier, float entityYaw, float partialTick, 
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        this.model.crouching = soldier.isCrouching();
        super.render(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Vec3 getRenderOffset(EnemySoldierEntity soldier, float partialTick) {
        return soldier.isCrouching() ? new Vec3(0.0D, -0.125D, 0.0D) : super.getRenderOffset(soldier, partialTick);
    }

    @Override
    protected void scale(EnemySoldierEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void setupRotations(EnemySoldierEntity soldier, PoseStack poseStack, float ageInTicks, float bodyYaw, float partialTick) {
        float swim = soldier.getSwimAmount(partialTick);
        if (swim > 0.001F && soldier.isAlive()) {
            super.setupRotations(soldier, poseStack, ageInTicks, bodyYaw, partialTick);
            float rotX = Mth.lerp(swim, 0.0F, -90.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(rotX));
            poseStack.translate(
                0.0D,
                Mth.lerp(swim, 0.0D, -1.0D),
                Mth.lerp(swim, 0.0D, 0.3D));
            return;
        }
        super.setupRotations(soldier, poseStack, ageInTicks, bodyYaw, partialTick);
    }
}

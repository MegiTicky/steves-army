package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.client.SoldierSkinLoader;
import com.stevesarmy.compat.ysm.ISoldierGeoRenderer;
import com.stevesarmy.compat.ysm.YsmCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, SoldierModel<SoldierEntity>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    private final ISoldierGeoRenderer geoRenderer;

    public SoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new SoldierModel<SoldierEntity>(SoldierModel.createBodyLayer().bakeRoot()), 0.5F);
        this.layers.removeIf(net.minecraft.client.renderer.entity.layers.ItemInHandLayer.class::isInstance);
        this.addLayer(new SoldierHeldItemLayer<>(this, context.getItemInHandRenderer()));
        this.geoRenderer = YsmCompat.createGeoRenderer(context);
        this.addLayer(new SoldierArmorLayer<>(this, context));
        this.addLayer(new SoldierBackWeaponLayer<>(this));
    }

    @Override
    public ResourceLocation getTextureLocation(SoldierEntity entity) {
        ResourceLocation skin = SoldierSkinLoader.resolve(entity.getSkin());
        return skin != null ? skin : TEXTURE;
    }

    @Override
    public void render(SoldierEntity soldier, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (geoRenderer != null && soldier.hasYsmModel()
            && geoRenderer.renderSoldier(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight)) {
            return;
        }
        this.model.crouching = soldier.isCrouching() || soldier.isHalfCoverRising();
        super.render(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Vec3 getRenderOffset(SoldierEntity soldier, float partialTick) {
        return Vec3.ZERO; // The animated rig owns continuous pelvis lowering.
    }

    @Override
    protected void scale(SoldierEntity entity, PoseStack poseStack, float partialTick) {
        // Rig is calibrated at sixteen pixels per block.
    }

    @Override protected float getFlipDegrees(SoldierEntity soldier) { return 0; }
}

package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.client.SoldierSkinLoader;
import com.stevesarmy.compat.ysm.ISoldierGeoRenderer;
import com.stevesarmy.compat.ysm.YsmCompat;
import com.stevesarmy.entity.EnemySoldierEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class EnemySoldierRenderer extends HumanoidMobRenderer<EnemySoldierEntity, SoldierModel<EnemySoldierEntity>> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    private final ISoldierGeoRenderer geoRenderer;

    public EnemySoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new SoldierModel<>(SoldierModel.createBodyLayer().bakeRoot()), 0.5F);
        this.layers.removeIf(net.minecraft.client.renderer.entity.layers.ItemInHandLayer.class::isInstance);
        this.addLayer(new SoldierHeldItemLayer<>(this, context.getItemInHandRenderer()));
        this.geoRenderer = YsmCompat.createGeoRenderer(context);
        this.addLayer(new SoldierArmorLayer<>(this, context));
        this.addLayer(new SoldierBackWeaponLayer<>(this));
        // Vanilla glow outline via MobEffects.GLOWING + team color handles enemy glow
    }

    @Override
    public ResourceLocation getTextureLocation(EnemySoldierEntity entity) {
        ResourceLocation skin = SoldierSkinLoader.resolve(entity.getSkin());
        return skin != null ? skin : TEXTURE;
    }

    @Override
    public void render(EnemySoldierEntity soldier, float entityYaw, float partialTick, 
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (geoRenderer != null && soldier.hasYsmModel()
            && geoRenderer.renderSoldier(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight)) {
            return;
        }
        this.model.crouching = soldier.isCrouching();
        super.render(soldier, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Vec3 getRenderOffset(EnemySoldierEntity soldier, float partialTick) {
        return Vec3.ZERO;
    }

    @Override
    protected void scale(EnemySoldierEntity entity, PoseStack poseStack, float partialTick) {
        // Rig is calibrated at sixteen pixels per block.
    }

    @Override protected float getFlipDegrees(EnemySoldierEntity soldier) { return 0; }
}

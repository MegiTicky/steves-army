package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.ClientModelManager;
import com.elfmcys.yesstevemodel.geckolib3.geo.GeoReplacedEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Geo renderer that renders a soldier with its YSM model. Created lazily by the vanilla
 * soldier renderers when YSM is present and used as a delegate.
 */
@OnlyIn(Dist.CLIENT)
public class SoldierModelRenderer extends GeoReplacedEntityRenderer<SoldierEntity, SoldierModelCapability> implements ISoldierGeoRenderer {

    public SoldierModelRenderer(EntityRendererProvider.Context context) {
        super(context);
        addLayerRenderer(new SoldierItemInHandLayer(context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(SoldierEntity entity) {
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(entity);
        if (capability != null) {
            return capability.getTextureLocation();
        }
        return ClientModelManager.getDefaultTexture();
    }

    @Override
    protected void scale(SoldierEntity entity, PoseStack poseStack, float partialTick) {
    }

    @Override
    public boolean renderSoldier(SoldierEntity soldier, float entityYaw, float partialTick,
                                 PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(soldier);
        if (capability == null) {
            return false;
        }
        capability.syncModelFromEntityData();
        capability.tickModel();
        if (!capability.isModelReady()) {
            return false;
        }
        this.rtb = bufferSource;
        try {
            renderEntityWithTexture(capability, null, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            this.rtb = null;
        }
        return true;
    }
}

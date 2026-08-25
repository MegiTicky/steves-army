package com.stevesarmy.compat.ysm;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side delegate used by the vanilla soldier renderers. Implemented by the YSM geo
 * renderer; the interface only references mod and Minecraft types so it can be loaded even
 * when YSM is absent. Returns true when the entity was rendered with its YSM model.
 */
@OnlyIn(Dist.CLIENT)
public interface ISoldierGeoRenderer {
    boolean renderSoldier(SoldierEntity soldier, float entityYaw, float partialTick,
                          PoseStack poseStack, MultiBufferSource bufferSource, int packedLight);
}

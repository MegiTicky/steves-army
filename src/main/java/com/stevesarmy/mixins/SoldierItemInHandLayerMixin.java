package com.stevesarmy.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.client.StevesArmyClientConfig;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandLayer.class)
public class SoldierItemInHandLayerMixin {

    @Inject(method = "m_6494_",
        at = @At("HEAD"), cancellable = true)
    private void skipDistantSoldierHeldItems(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                              LivingEntity entity, float limbSwing, float limbSwingAmount,
                                              float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
                                              CallbackInfo ci) {
        if (!(entity instanceof SoldierEntity)) {
            return;
        }

        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        if (cameraEntity != null && !StevesArmyClientConfig.shouldRenderHeldItem(entity.distanceToSqr(cameraEntity))) {
            ci.cancel();
        }
    }
}

package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.compat.gun.swarfare.SWarfareCompat;
import com.elfmcys.yesstevemodel.client.compat.gun.tacz.TacCompat;
import com.elfmcys.yesstevemodel.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.yesstevemodel.geckolib3.geo.animated.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.util.RenderUtils;
import com.elfmcys.yesstevemodel.util.accessors.BufferSourceAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Renders held items from YSM hand locators and delegates TaCZ gun transforms. */
@OnlyIn(Dist.CLIENT)
public class SoldierItemInHandLayer extends GeoLayerRenderer<SoldierModelCapability> {
    private final ItemInHandRenderer itemRenderer;

    public SoldierItemInHandLayer(ItemInHandRenderer itemRenderer) {
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       SoldierModelCapability capability, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        SoldierEntity entity = capability.getEntity();
        AnimatedGeoModel model = capability.getCurrentModel();
        if (model == null) {
            return;
        }

        ItemStack offhandItem = entity.getOffhandItem();
        ItemStack mainHandItem = entity.getMainHandItem();
        if (offhandItem.isEmpty() && mainHandItem.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        if (!model.rightHandBones().isEmpty()) {
            TacCompat.handleGunSound(entity, mainHandItem);
            renderItem(model, entity, mainHandItem, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                HumanoidArm.RIGHT, poseStack, bufferSource, packedLight);
            resetBufferSource(bufferSource, mainHandItem);
            TacCompat.handleItemSound(mainHandItem);
        }
        if (!model.leftHandBones().isEmpty()) {
            if (!SWarfareCompat.isGunItem(offhandItem)) {
                renderItem(model, entity, offhandItem, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    HumanoidArm.LEFT, poseStack, bufferSource, packedLight);
            }
            resetBufferSource(bufferSource, offhandItem);
        }
        poseStack.popPose();

        // TaCZ renders its model-specific gun locators after the normal hand items.
        TacCompat.applyItemTransform(offhandItem, model, entity, poseStack, packedLight, partialTick);
        SWarfareCompat.applyGunTransform(offhandItem, model, entity, poseStack, packedLight, partialTick);
    }

    private void resetBufferSource(MultiBufferSource bufferSource, ItemStack item) {
        if (!item.isEmpty() && bufferSource instanceof BufferSourceAccessor accessor) {
            accessor.initialize();
        }
    }

    private void renderItem(AnimatedGeoModel model, SoldierEntity entity, ItemStack item,
                            ItemDisplayContext displayContext, HumanoidArm arm, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight) {
        if (item.isEmpty()) {
            return;
        }
        boolean left = arm == HumanoidArm.LEFT;
        poseStack.pushPose();
        if (!applyItemBoneTransform(arm, poseStack, model)) {
            renderVanillaItem(entity, item, displayContext, left, poseStack, bufferSource, packedLight);
        }
        poseStack.popPose();

        (left ? model.rightHandChain() : model.leftHandChains()).forEach(locator -> {
            poseStack.pushPose();
            if (!RenderUtils.prepMatrixForLocator(poseStack, locator)) {
                renderVanillaItem(entity, item, displayContext, left, poseStack, bufferSource, packedLight);
            }
            poseStack.popPose();
        });
    }

    private void renderVanillaItem(SoldierEntity entity, ItemStack item, ItemDisplayContext displayContext,
                                   boolean left, PoseStack poseStack, MultiBufferSource bufferSource,
                                   int packedLight) {
        poseStack.translate(0.0D, -0.0625D, -0.1D);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
        if (SWarfareCompat.isGunItem(item)) {
            poseStack.translate(0.1D, 0.0D, 0.0D);
            poseStack.scale(1.25F, 1.25F, 1.25F);
        }
        itemRenderer.renderItem(entity, item, displayContext, left, poseStack, bufferSource,
            packedLight);
    }

    private boolean applyItemBoneTransform(HumanoidArm arm, PoseStack poseStack, AnimatedGeoModel model) {
        return arm == HumanoidArm.LEFT
            ? RenderUtils.prepMatrixForLocator(poseStack, model.leftHandBones())
            : RenderUtils.prepMatrixForLocator(poseStack, model.rightHandBones());
    }
}

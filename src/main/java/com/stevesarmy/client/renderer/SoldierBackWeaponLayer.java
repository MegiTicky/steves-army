package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * TaCZ-style stowed-weapon display: whatever sits in the soldier's sidearm
 * slot (after a weapon swap that is always the gun not currently held) is
 * drawn lying diagonally across the back. Parented to the body part, so
 * crouch lean, half-cover rise and prone pitch all carry the weapon along.
 * Rendered in the THIRD_PERSON (held-gun) framing — gun packs author that
 * context to the in-world size, while FIXED is their oversized item-frame
 * display. Never runs under the YSM geo-model path, which bypasses the
 * layer pipeline.
 */
public class SoldierBackWeaponLayer<T extends SoldierEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    // Body-pivot frame in model space: +Y runs down the torso from the neck
    // pivot, +Z points out the back surface. Tunables for the TaCZ look.
    private static final double HEIGHT_FROM_PIVOT = 0.34;
    private static final double BEHIND_SURFACE = 0.16;
    private static final float PITCH_DEGREES = 35.0F;
    private static final float ROLL_DEGREES = 18.0F;
    private static final float SCALE = 1.0F;

    public SoldierBackWeaponLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T soldier,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (soldier.isInvisible() || soldier.hasYsmModel()) {
            return;
        }
        ItemStack stowed = soldier.getStowedWeapon();
        if (stowed.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        ModelPart body = this.getParentModel().body;
        body.translateAndRotate(poseStack);
        poseStack.translate(0.0D, HEIGHT_FROM_PIVOT, BEHIND_SURFACE);
        // Out of the back, then muzzle up and rolled into the diagonal carry.
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(PITCH_DEGREES));
        poseStack.mulPose(Axis.ZP.rotationDegrees(ROLL_DEGREES));
        poseStack.scale(SCALE, SCALE, SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(stowed, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, soldier.level(), soldier.getId());
        poseStack.popPose();
    }
}

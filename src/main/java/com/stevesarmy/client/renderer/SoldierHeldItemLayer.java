package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Simple Enemy Mod's GPL-3.0 hand anchor/display transform, with ready blending. */
public class SoldierHeldItemLayer<T extends SoldierEntity> extends ItemInHandLayer<T, SoldierModel<T>> {
    private final ItemInHandRenderer items;

    public SoldierHeldItemLayer(RenderLayerParent<T, SoldierModel<T>> parent, ItemInHandRenderer items) {
        super(parent, items);
        this.items = items;
    }

    @Override protected void renderArmWithItem(net.minecraft.world.entity.LivingEntity entity, ItemStack item, ItemDisplayContext context,
                                               HumanoidArm arm, PoseStack stack, MultiBufferSource buffers, int light) {
        if (entity.isDeadOrDying()) return;
        if (!GunIntegration.isGun(item) || GunIntegration.isVpbGun(item)) {
            super.renderArmWithItem(entity, item, context, arm, stack, buffers, light);
            return;
        }
        if (arm != HumanoidArm.RIGHT) return;
        stack.pushPose();
        getParentModel().translateWeaponToHand(stack);
        boolean heavy = item.getTag() != null && item.getTag().getString("GunId").contains("minigun");
        float ready = getParentModel().weaponReady();
        stack.translate(heavy ? .01 : 0, heavy ? .30 : Mth.lerp(ready, .35F, .10F),
            heavy ? -.25 : Mth.lerp(ready, .13F, .28F));
        stack.mulPose(Axis.YP.rotationDegrees(-180));
        stack.mulPose(Axis.XP.rotationDegrees(heavy ? -70 : Mth.lerp(ready, -90, -95)));
        if (heavy) stack.mulPose(Axis.ZP.rotationDegrees(6));
        stack.scale(1, -1, -1);
        items.renderItem(entity, item, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, stack, buffers, light);
        stack.popPose();
    }
}

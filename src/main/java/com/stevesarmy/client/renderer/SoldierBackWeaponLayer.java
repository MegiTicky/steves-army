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
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * TaCZ-style stowed-weapon display: whatever sits in the soldier's sidearm
 * slot (after a weapon swap that is always the gun not currently held) is
 * drawn slung across the back. The mount reproduces TaCZ's own back-gun
 * math (HumanoidOffhandRender.renderGunItem, injected by TaCZ at
 * ItemInHandLayer TAIL — the same pose frame this layer renders in):
 * translate(-x/16, 1.5 - y/16, z/16), un-mirroring scale(-sx, -sy, sz),
 * intrinsic ZYX rotation, FIXED display context, with per-gun pos/rotate/
 * scale from the pack's authored hotbar_show. Fallback is the TaCZ default
 * pack's AK47 mount. Body parenting adds what TaCZ's entity-anchored
 * version lacks: the mount follows lean/crouch poses. Never runs under the
 * YSM geo-model path, which bypasses the layer pipeline.
 */
public class SoldierBackWeaponLayer<T extends SoldierEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    /** TaCZ default pack, ak47_display.json hotbar_show slot 0. */
    private static final GunShow FALLBACK_SHOW = new GunShow(
        new Vector3f(-1.0F, 20.0F, 3.0F), new Vector3f(-180.0F, 0.0F, 120.0F), new Vector3f(0.5F, 0.5F, 0.5F));

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
        GunShow show = lookupTaczShow(stowed);
        if (show == null) {
            show = FALLBACK_SHOW;
        }

        poseStack.pushPose();
        this.getParentModel().body.translateAndRotate(poseStack);
        // Verbatim HumanoidOffhandRender.renderGunItem transform chain.
        poseStack.translate(-show.pos().x() / 16.0, 1.5 - show.pos().y() / 16.0, show.pos().z() / 16.0);
        poseStack.scale(-show.scale().x(), -show.scale().y(), show.scale().z());
        poseStack.mulPose(Axis.ZP.rotationDegrees(show.rot().z()));
        poseStack.mulPose(Axis.YP.rotationDegrees(show.rot().y()));
        poseStack.mulPose(Axis.XP.rotationDegrees(show.rot().x()));
        Minecraft.getInstance().getItemRenderer().renderStatic(stowed, ItemDisplayContext.FIXED,
            packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, soldier.level(), soldier.getId());
        poseStack.popPose();
    }

    /**
     * The gun's authored TaCZ back mount (hotbar_show slot 0, else any
     * slot), or null when TaCZ is absent or the gun has no display data.
     */
    @Nullable
    private static GunShow lookupTaczShow(ItemStack stack) {
        try {
            Class<?> api = Class.forName("com.tacz.guns.api.TimelessAPI");
            Object result = api.getMethod("getGunDisplay", ItemStack.class).invoke(null, stack);
            if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
                return null;
            }
            Object index = optional.get();
            Object mapObj = index.getClass().getMethod("getHotbarShow").invoke(index);
            if (!(mapObj instanceof java.util.Map<?, ?> map) || map.isEmpty()) {
                return null;
            }
            Object show = map.containsKey(0) ? map.get(0) : map.values().iterator().next();
            Class<?> showClass = show.getClass();
            return new GunShow(
                (Vector3f) showClass.getMethod("getPos").invoke(show),
                (Vector3f) showClass.getMethod("getRotate").invoke(show),
                (Vector3f) showClass.getMethod("getScale").invoke(show));
        } catch (ReflectiveOperationException | NoClassDefFoundError | ClassCastException ignored) {
            return null;
        }
    }

    private record GunShow(Vector3f pos, Vector3f rot, Vector3f scale) {}
}

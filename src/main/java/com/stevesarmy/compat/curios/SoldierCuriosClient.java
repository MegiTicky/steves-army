package com.stevesarmy.compat.curios;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;

/** Curios-free wrapper keeps renderer failures separate from equipment failures. */
public final class SoldierCuriosClient {
    private static boolean broken;
    private static String reason;
    private static boolean notified;
    private static void fail(Throwable error) {
        broken = true;
        reason = error.getClass().getSimpleName();
        com.stevesarmy.StevesArmyMod.LOGGER.warn("Soldier Curios rendering disabled; equipped items retained", error);
    }
    public static <T extends LivingEntity, M extends EntityModel<T>> RenderLayer<T, M> layer(RenderLayerParent<T, M> parent) {
        return new RenderLayer<T, M>(parent) {
            private RenderLayer<T, M> delegate;
            @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity,
                    float walk, float walkAmount, float partial, float age, float yaw, float pitch) {
                if (broken) {
                    var player = net.minecraft.client.Minecraft.getInstance().player;
                    if (!notified && player != null) {
                        notified = true;
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("steves_army.curios.render_disabled", reason), false);
                    }
                    return;
                }
                if (!SoldierCurios.available()) return;
                pose.pushPose();
                try {
                    if (delegate == null) delegate = new SoldierCuriosLayer<>(parent);
                    delegate.render(pose, buffers, light, entity, walk, walkAmount, partial, age, yaw, pitch);
                } catch (RuntimeException | LinkageError error) { fail(error); }
                finally { pose.popPose(); }
            }
        };
    }
    private SoldierCuriosClient() {}
}

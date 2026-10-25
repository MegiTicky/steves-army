package com.stevesarmy.compat.curios;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;

/** Loaded only after the optional Curios presence check on the client. */
public final class SoldierCuriosClient {
    public static <T extends LivingEntity, M extends EntityModel<T>> RenderLayer<T, M> layer(RenderLayerParent<T, M> parent) {
        try {
            @SuppressWarnings("unchecked")
            RenderLayer<T, M> layer = (RenderLayer<T, M>) Class.forName("top.theillusivec4.curios.client.render.CuriosLayer")
                .getConstructor(RenderLayerParent.class).newInstance(parent);
            return layer;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Curios is present but its native rendering layer is unavailable", exception);
        }
    }
    private SoldierCuriosClient() {}
}

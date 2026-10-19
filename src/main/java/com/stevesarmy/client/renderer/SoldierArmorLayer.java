package com.stevesarmy.client.renderer;

import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Keep Forge equipment-model replacements attached to the articulated rig. */
public final class SoldierArmorLayer<T extends SoldierEntity>
        extends HumanoidArmorLayer<T, SoldierModel<T>, SoldierModel<T>> {
    public SoldierArmorLayer(RenderLayerParent<T, SoldierModel<T>> parent, EntityRendererProvider.Context context) {
        super(parent,
            new SoldierModel<>(SoldierModel.createBodyLayer(new CubeDeformation(.5F)).bakeRoot()),
            new SoldierModel<>(SoldierModel.createBodyLayer(new CubeDeformation(1)).bakeRoot()),
            context.getModelManager());
    }

    @Override protected Model getArmorModelHook(T entity, ItemStack item, EquipmentSlot slot, SoldierModel<T> fallback) {
        Model equipment = super.getArmorModelHook(entity, item, slot, fallback);
        return equipment instanceof SoldierModel<?> ? equipment : new ArticulatedArmorModel(equipment, getParentModel());
    }
}

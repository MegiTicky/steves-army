package com.stevesarmy.combat.throwable;

import com.stevesarmy.combat.GrenadeIntegration;
import com.stevesarmy.combat.GrenadeIntegration.BallisticResult;
import com.stevesarmy.combat.GrenadeIntegration.Category;
import com.stevesarmy.combat.GrenadeIntegration.SupportInfo;
import com.stevesarmy.combat.GrenadeIntegration.ThrowResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Adapter for LesRaisins Tactical Equipments throwables.
 */
public final class LesRaisinsThrowableProvider implements ThrowableProvider {

    private static final String MOD_ID = "lrtactical";

    @Override
    public String id() {
        return MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        return GrenadeIntegration.lrAvailable();
    }

    @Override
    public boolean claims(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && MOD_ID.equals(key.getNamespace());
    }

    @Override
    public SupportInfo inspect(@Nullable ItemStack stack, Category category) {
        return GrenadeIntegration.lrInspect(stack, category);
    }

    @Override
    public BallisticResult inspectBallistics(@Nullable ItemStack stack, Category category) {
        return GrenadeIntegration.lrInspectBallistics(stack, category);
    }

    @Override
    public ThrowResult throwGrenade(LivingEntity owner, ItemStack stack,
                                    @Nullable Vec3 launchOrigin,
                                    @Nullable Vec3 appliedVelocity) {
        return GrenadeIntegration.lrThrow(owner, stack, launchOrigin, appliedVelocity);
    }
}
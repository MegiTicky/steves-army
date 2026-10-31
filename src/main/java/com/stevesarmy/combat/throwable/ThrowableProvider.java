package com.stevesarmy.combat.throwable;

import com.stevesarmy.combat.GrenadeIntegration.BallisticResult;
import com.stevesarmy.combat.GrenadeIntegration.Category;
import com.stevesarmy.combat.GrenadeIntegration.SupportInfo;
import com.stevesarmy.combat.GrenadeIntegration.ThrowResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Common provider interface for throwable integrations (LesRaisins, Superb Warfare, etc.).
 */
public interface ThrowableProvider {

    String id();

    /** True when this provider's backing mod is present and enabled. */
    boolean isAvailable();

    /** Whether this provider recognises the stack at all (claim, not support). */
    boolean claims(@Nullable ItemStack stack);

    SupportInfo inspect(@Nullable ItemStack stack, Category category);

    BallisticResult inspectBallistics(@Nullable ItemStack stack, Category category);

    /**
     * Performs a native throw, optionally overriding spawn origin and velocity
     * so the AI's validated arc replaces the mod's random launch spread.
     */
    ThrowResult throwGrenade(LivingEntity owner, ItemStack stack,
                             @Nullable Vec3 launchOrigin,
                             @Nullable Vec3 appliedVelocity);
}
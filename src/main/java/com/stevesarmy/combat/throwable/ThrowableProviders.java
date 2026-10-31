package com.stevesarmy.combat.throwable;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registry and lookup for all supported throwable integrations. */
public final class ThrowableProviders {

    private static final List<ThrowableProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ThrowableProviders() {
    }

    public static void register(ThrowableProvider provider) {
        if (provider == null) return;
        if (!PROVIDERS.contains(provider)) {
            PROVIDERS.add(provider);
        }
    }

    @Nullable
    public static ThrowableProvider resolve(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (ThrowableProvider provider : PROVIDERS) {
            if (provider.isAvailable() && provider.claims(stack)) {
                return provider;
            }
        }
        return null;
    }

    public static boolean anyAvailable() {
        for (ThrowableProvider provider : PROVIDERS) {
            if (provider.isAvailable()) return true;
        }
        return false;
    }

    public static String describeSupported() {
        List<String> ids = new ArrayList<>();
        for (ThrowableProvider provider : PROVIDERS) {
            if (provider.isAvailable()) ids.add(provider.id());
        }
        return ids.isEmpty() ? "none" : String.join(", ", ids);
    }
}
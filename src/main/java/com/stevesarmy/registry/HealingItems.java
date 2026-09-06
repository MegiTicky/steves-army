package com.stevesarmy.registry;

import com.stevesarmy.compat.LrTacticalCompat;
import net.minecraft.world.item.ItemStack;

/** Single predicate for "does this stack count as a medical item" used by healing and resupply logic. */
public final class HealingItems {

    private HealingItems() {}

    public static boolean isHealingItem(ItemStack stack) {
        return !stack.isEmpty()
            && stack.is(ModItemTags.SOLDIER_HEALING_ITEMS)
            && !LrTacticalCompat.isFoodOnlyVariant(stack);
    }
}

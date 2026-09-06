package com.stevesarmy.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * LesRaisins Tactical Equipements (lrtactical) compatibility.
 *
 * lrtactical registers all consumables as a single item, {@code lrtactical:consumable};
 * the concrete variant (ai2, blood_pack, condensed_milk, ...) is stored in the stack
 * NBT under "ConsumableId". Item tags therefore cannot separate medical consumables
 * from food-only ones, so the exclusion is done here.
 */
public class LrTacticalCompat {

    private static final String MOD_ID = "lrtactical";
    /** lrtactical's IConsumable.ID_TAG; read as plain NBT to avoid a compile-time dependency. */
    private static final String CONSUMABLE_ID_TAG = "ConsumableId";
    /** Consumable variants that restore no health, so soldiers should not treat them as medical items. */
    private static final String FOOD_ONLY_CONSUMABLE = MOD_ID + ":condensed_milk";

    private static Boolean loaded = null;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** True when the stack is an lrtactical consumable variant that heals nothing. */
    public static boolean isFoodOnlyVariant(ItemStack stack) {
        if (!isLoaded() || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CONSUMABLE_ID_TAG, CompoundTag.TAG_STRING)) return false;
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(CONSUMABLE_ID_TAG));
        return id != null && FOOD_ONLY_CONSUMABLE.equals(id.toString());
    }
}

package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public class ModItemTags {
    public static final TagKey<Item> SOLDIER_HEALING_ITEMS = TagKey.create(
        Registries.ITEM,
        new ResourceLocation(StevesArmyMod.MODID, "soldier_healing_items")
    );
}
package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public class ModBlockTags {
    public static final TagKey<Block> HAZARD_BLOCKS = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation(StevesArmyMod.MODID, "hazard_blocks")
    );
}
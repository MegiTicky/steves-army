package com.stevesarmy.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ModBlockTags {
    public static final TagKey<Block> VISION_CONCEALMENT = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation("steves_army", "vision_concealment")
    );

    public static final TagKey<Block> VISION_CONCEALMENT_LIGHT = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation("steves_army", "vision_concealment_light")
    );

    public static final TagKey<Block> VISION_CONCEALMENT_MEDIUM = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation("steves_army", "vision_concealment_medium")
    );

    public static final TagKey<Block> TRANSPARENT_PENETRABLE = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation("steves_army", "transparent_penetrable")
    );

    private ModBlockTags() {}
}

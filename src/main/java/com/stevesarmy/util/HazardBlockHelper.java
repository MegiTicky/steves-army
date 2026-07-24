package com.stevesarmy.util;

import com.stevesarmy.StevesArmyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

public class HazardBlockHelper {
    private static Set<Block> cachedHazardBlocks = null;
    private static java.util.List<? extends String> cachedConfigList = null;

    public static boolean isHazardBlock(Level level, BlockPos pos) {
        return isHazardBlock(level.getBlockState(pos));
    }

    public static boolean isHazardBlock(BlockState state) {
        if (cachedConfigList != StevesArmyConfig.getHazardBlocks()) {
            cachedConfigList = StevesArmyConfig.getHazardBlocks();
            rebuildCache();
        }
        return cachedHazardBlocks != null && cachedHazardBlocks.contains(state.getBlock());
    }

    private static void rebuildCache() {
        cachedHazardBlocks = new HashSet<>();
        if (cachedConfigList == null) return;
        for (String id : cachedConfigList) {
            ResourceLocation key = ResourceLocation.tryParse(id.trim());
            if (key != null) {
                Block block = ForgeRegistries.BLOCKS.getValue(key);
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    cachedHazardBlocks.add(block);
                }
            }
        }
    }

    public static void invalidateCache() {
        cachedConfigList = null;
        cachedHazardBlocks = null;
    }
}
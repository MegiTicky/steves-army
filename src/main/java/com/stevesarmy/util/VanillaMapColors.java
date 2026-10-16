package com.stevesarmy.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Vanilla filled-map terrain colors, computable entirely server-side.
 *
 * <p>Base palette comes from {@code state.getMapColor(level, pos)} (1.20.1
 * common code; vanilla maps are not biome-tinted). The four brightness steps
 * are LOW 180, NORMAL 220, HIGH 255, LOWEST 135, applied per channel as
 * {@code c * modifier / 255}; the HTML viewers replicate that in JS. Relief
 * shading follows {@code MapItem#update}: a column's brightness is HIGH when it
 * rises against the previous row (+z scanline) past +0.6 after dithering, LOW
 * when it falls past -0.6, else NORMAL; at scale 0 the height delta weighs
 * 4/(1+4)=0.8 plus an {@code (x+z)&1} checkerboard dither of ±0.2. The vanilla
 * water-depth brightness is not replicated (fluids render at NORMAL).
 */
public final class VanillaMapColors {
    private VanillaMapColors() {}

    /**
     * Base map color (0xRRGGBB, 0 when map-transparent) for a surface block,
     * applying vanilla's fluid rule: a block carrying a fluid whose top face is
     * not blocked renders as the fluid's color, so lake and river surfaces read
     * as water on the map.
     */
    public static int surfaceRgb(BlockState state, BlockGetter level, BlockPos pos) {
        if (!state.getFluidState().isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP)) {
            state = state.getFluidState().createLegacyBlock();
        }
        return blockRgb(state, level, pos);
    }

    /** Base map color (0xRRGGBB, 0 when map-transparent) without fluid handling. */
    public static int blockRgb(BlockState state, BlockGetter level, BlockPos pos) {
        MapColor color = state.getMapColor(level, pos);
        return color == null || color == MapColor.NONE ? 0 : color.col;
    }
}

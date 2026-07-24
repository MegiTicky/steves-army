package com.stevesarmy.util;

import com.stevesarmy.registry.ModBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class HazardBlockHelper {

    public static boolean isHazardBlock(Level level, BlockPos pos) {
        return isHazardBlock(level.getBlockState(pos));
    }

    public static boolean isHazardBlock(BlockState state) {
        return state.is(ModBlockTags.HAZARD_BLOCKS);
    }

    public static boolean isHazardBlockAt(BlockGetter level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return state.is(ModBlockTags.HAZARD_BLOCKS);
    }

    /**
     * True if sweeping the mob's bounding box from currentPos toward nextPos
     * would intersect a hazard block (step-sampled, NOT continuous).
     * Also rejects a start or end position that is inside a hazard.
     */
    public static boolean sweptPathCrossesHazard(Mob mob, Vec3 currentPos, Vec3 nextPos) {
        AABB bb = mob.getBoundingBox();
        double dx = nextPos.x - currentPos.x;
        double dz = nextPos.z - currentPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            return boundingBoxOverlapsHazard(mob.level(), bb);
        }

        int steps = Math.max(3, (int) Math.ceil(dist * 4.0));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            AABB sample = bb.move(dx * t, 0, dz * t);
            if (boundingBoxOverlapsHazard(mob.level(), sample)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if any block position fully inside the AABB matches a configured hazard block.
     * Checks at feet-level (floor of AABB) and head-level (floor + 1) to catch 2-block-tall entities.
     */
    public static boolean boundingBoxOverlapsHazard(Level level, AABB bb) {
        int minX = (int) Math.floor(bb.minX);
        int maxX = (int) Math.ceil(bb.maxX) - 1;
        int minZ = (int) Math.floor(bb.minZ);
        int maxZ = (int) Math.ceil(bb.maxZ) - 1;
        int yFeet = (int) Math.floor(bb.minY);
        int yHead = (int) Math.floor(bb.maxY - 0.01);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos feetPos = new BlockPos(x, yFeet, z);
                if (level.getBlockState(feetPos).is(ModBlockTags.HAZARD_BLOCKS)) {
                    return true;
                }
                if (yHead != yFeet) {
                    BlockPos headPos = new BlockPos(x, yHead, z);
                    if (level.getBlockState(headPos).is(ModBlockTags.HAZARD_BLOCKS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
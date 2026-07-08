package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class CoverQualityEvaluator {
    private static final int RAYS_PER_HEIGHT = 4;
    private static final int CONE_HEIGHTS = 2;
    private static final double CONE_HALF_ANGLE_DEG = 10.0;
    private static final double CONE_MAX_DISTANCE = 3.0;

    private static final double WAIST_HEIGHT = 0.8;
    private static final double EYE_HEIGHT = 1.6;
    private static final double[] TEST_HEIGHTS = {WAIST_HEIGHT, EYE_HEIGHT};

    private static final double HITBOX_OFFSET = 0.25;
    private static final double[][] CORNER_OFFSETS = {
        {-HITBOX_OFFSET, -HITBOX_OFFSET},
        {-HITBOX_OFFSET, +HITBOX_OFFSET},
        {+HITBOX_OFFSET, -HITBOX_OFFSET},
        {+HITBOX_OFFSET, +HITBOX_OFFSET}
    };

    private final Level level;

    public CoverQualityEvaluator(Level level) {
        this.level = level;
    }

    public CoverPoint evaluateWithCone(CoverPoint coverPoint, Vec3 threatDirection) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return coverPoint;
        }

        BlockPos pos = coverPoint.getPosition();
        Vec3 threatDir = threatDirection.normalize();

        boolean waistCovered = true;
        boolean eyeCovered = true;
        boolean anyBlocked = false;
        boolean allEyeBlocked = true;

        StringBuilder debug = new StringBuilder();
        debug.append("Threat dir: ").append(String.format("%.2f,%.2f,%.2f", threatDir.x, threatDir.y, threatDir.z)).append("\n");

        for (int hi = 0; hi < CONE_HEIGHTS; hi++) {
            double height = TEST_HEIGHTS[hi];
            debug.append("  H=").append(String.format("%.1f", height)).append(": ");

            int heightBlocked = 0;

            for (int ri = 0; ri < RAYS_PER_HEIGHT; ri++) {
                Vec3 origin = new Vec3(
                    pos.getX() + 0.5 + CORNER_OFFSETS[ri][0],
                    pos.getY() + height,
                    pos.getZ() + 0.5 + CORNER_OFFSETS[ri][1]
                );

                double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * ri / (RAYS_PER_HEIGHT - 1));
                Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
                double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

                boolean blocked = distance < CONE_MAX_DISTANCE - 0.1;
                if (blocked) heightBlocked++;
                if (blocked) anyBlocked = true;

                debug.append(blocked ? "B" : "C");
            }

            boolean heightCovered = heightBlocked == RAYS_PER_HEIGHT;
            if (hi == 0) {
                waistCovered = heightCovered;
            } else {
                eyeCovered = heightCovered;
                allEyeBlocked = heightBlocked == RAYS_PER_HEIGHT;
            }

            debug.append(" ").append(heightBlocked).append("/").append(RAYS_PER_HEIGHT).append(heightCovered ? " COVERED" : "").append("\n");
        }

        CoverType type = determineType(waistCovered, eyeCovered, anyBlocked);
        coverPoint.setType(type);

        coverPoint.setCanShootFrom(determineCanShoot(type, allEyeBlocked));

        coverPoint.setQuality(type.getBaseQuality());

        float coverHeight = estimateCoverHeight(type);
        coverPoint.setCoverHeight(coverHeight);

        coverPoint.setDebugInfo(String.format("Waist: %s | Eye: %s | Type: %s | Shoot: %s\n%s",
            waistCovered ? "COVERED" : "EXPOSED",
            eyeCovered ? "COVERED" : "EXPOSED",
            type, coverPoint.canShootFrom() ? "YES" : "NO",
            debug.toString()));

        return coverPoint;
    }

    private double raycastDistance(Vec3 start, Vec3 direction, double maxDistance) {
        Vec3 end = start.add(direction.scale(maxDistance));

        ClipContext context = new ClipContext(
            start, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );

        HitResult result = level.clip(context);

        if (result.getType() == HitResult.Type.MISS) {
            return maxDistance;
        }

        if (result.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockResult = (BlockHitResult) result;
            BlockPos hitPos = blockResult.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);

            if (!isBlockValidCover(hitState, hitPos)) {
                return maxDistance;
            }
        }

        return start.distanceTo(result.getLocation());
    }

    private boolean isBlockValidCover(BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }

        if (!state.isSolid()) {
            return false;
        }

        net.minecraft.world.level.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.IronBarsBlock ||
            block instanceof net.minecraft.world.level.block.GlassBlock ||
            block instanceof net.minecraft.world.level.block.StainedGlassBlock ||
            block instanceof net.minecraft.world.level.block.TintedGlassBlock ||
            block instanceof net.minecraft.world.level.block.FenceBlock ||
            block instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return false;
        }

        return true;
    }

    private CoverType determineType(boolean waistCovered, boolean eyeCovered, boolean anyBlocked) {
        if (waistCovered && eyeCovered) return CoverType.FULL;
        if (waistCovered) return CoverType.HALF;
        if (anyBlocked) return CoverType.CONCEALMENT;
        return CoverType.NONE;
    }

    private boolean determineCanShoot(CoverType type, boolean allEyeBlocked) {
        if (type == CoverType.HALF) return true;
        if (type == CoverType.FULL) return !allEyeBlocked;
        return false;
    }

    private float estimateCoverHeight(CoverType type) {
        switch (type) {
            case FULL: return 2.0f;
            case HALF: return 1.0f;
            case CONCEALMENT: return 0.5f;
            default: return 0.0f;
        }
    }

    public boolean isDirectionProtected(CoverPoint coverPoint, Vec3 threatDirection) {
        if (threatDirection == null || coverPoint == null) {
            return false;
        }

        Vec3 threatDir = threatDirection.normalize();
        BlockPos pos = coverPoint.getPosition();
        double height = WAIST_HEIGHT;

        int blockedRays = 0;

        for (int ri = 0; ri < RAYS_PER_HEIGHT; ri++) {
            Vec3 origin = new Vec3(
                pos.getX() + 0.5 + CORNER_OFFSETS[ri][0],
                pos.getY() + height,
                pos.getZ() + 0.5 + CORNER_OFFSETS[ri][1]
            );

            double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * ri / (RAYS_PER_HEIGHT - 1));
            Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
            double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

            if (distance < CONE_MAX_DISTANCE - 0.1) blockedRays++;
        }

        return blockedRays >= 3;
    }

    public float evaluateProtectionFromDirection(BlockPos pos, Vec3 threatDirection) {
        Vec3 threatDir = threatDirection.normalize();
        double height = WAIST_HEIGHT;

        int blockedRays = 0;

        for (int ri = 0; ri < RAYS_PER_HEIGHT; ri++) {
            Vec3 origin = new Vec3(
                pos.getX() + 0.5 + CORNER_OFFSETS[ri][0],
                pos.getY() + height,
                pos.getZ() + 0.5 + CORNER_OFFSETS[ri][1]
            );

            double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * ri / (RAYS_PER_HEIGHT - 1));
            Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
            double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

            if (distance < CONE_MAX_DISTANCE - 0.1) blockedRays++;
        }

        return (float) blockedRays / RAYS_PER_HEIGHT;
    }
}
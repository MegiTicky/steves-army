package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CoverQualityEvaluator {
    private static final int CONE_RAYS_PER_HEIGHT = 3;
    private static final int CONE_HEIGHTS = 2;
    private static final double CONE_HALF_ANGLE_DEG = 15.0;
    private static final double CONE_MAX_DISTANCE = 3.0;

    private static final double[] TEST_HEIGHTS = {0.4, 1.6};

    private static final float FULL_THRESHOLD = 0.8f;
    private static final float HALF_THRESHOLD = 0.4f;

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

        int totalRays = 0;
        int blockedRays = 0;

        StringBuilder debug = new StringBuilder();
        debug.append("Threat dir: ").append(String.format("%.2f,%.2f,%.2f", threatDir.x, threatDir.y, threatDir.z)).append("\n");
        debug.append("Cone (±").append((int)CONE_HALF_ANGLE_DEG).append("°, ").append((int)CONE_MAX_DISTANCE).append("m):\n");

        for (int hi = 0; hi < CONE_HEIGHTS; hi++) {
            double height = TEST_HEIGHTS[hi];
            Vec3 origin = new Vec3(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5);

            debug.append("  H=").append(String.format("%.1f", height)).append(": ");

            for (int ri = 0; ri < CONE_RAYS_PER_HEIGHT; ri++) {
                double angleOffset = -CONE_HALF_ANGLE_DEG + (CONE_HALF_ANGLE_DEG * ri / (CONE_RAYS_PER_HEIGHT - 1));
                Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
                double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

                boolean blocked = distance < CONE_MAX_DISTANCE - 0.1;
                if (blocked) blockedRays++;
                totalRays++;

                debug.append(blocked ? "B" : "C");
            }
            debug.append("\n");
        }

        float coverage = totalRays > 0 ? (float) blockedRays / totalRays : 0.0f;

        CoverType type = determineTypeFromCoverage(coverage);
        coverPoint.setType(type);

        coverPoint.setCanShootFrom(type == CoverType.HALF || type == CoverType.CONCEALMENT);

        float quality = calculateQualityFromCoverage(coverage, type);
        coverPoint.setQuality(quality);

        float coverHeight = estimateCoverHeightFromCoverage(coverage, type);
        coverPoint.setCoverHeight(coverHeight);

        coverPoint.setDebugInfo(String.format("Cone: %d/%d blocked (%.0f%%) | Type: %s | Shoot: %s\n%s",
            blockedRays, totalRays, coverage * 100,
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

    private CoverType determineTypeFromCoverage(float coverage) {
        if (coverage >= FULL_THRESHOLD) {
            return CoverType.FULL;
        } else if (coverage >= HALF_THRESHOLD) {
            return CoverType.HALF;
        } else if (coverage > 0.0f) {
            return CoverType.CONCEALMENT;
        }
        return CoverType.NONE;
    }

    private float calculateQualityFromCoverage(float coverage, CoverType type) {
        return type.getBaseQuality();
    }

    private float estimateCoverHeightFromCoverage(float coverage, CoverType type) {
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

        BlockPos coverPos = coverPoint.getPosition();
        Vec3 threatDir = threatDirection.normalize();

        int totalRays = 0;
        int blockedRays = 0;

        double height = TEST_HEIGHTS[0];
        Vec3 origin = new Vec3(coverPos.getX() + 0.5, coverPos.getY() + height, coverPos.getZ() + 0.5);

        for (int ri = 0; ri < CONE_RAYS_PER_HEIGHT; ri++) {
            double angleOffset = -CONE_HALF_ANGLE_DEG + (CONE_HALF_ANGLE_DEG * ri / (CONE_RAYS_PER_HEIGHT - 1));
            Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
            double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

            if (distance < CONE_MAX_DISTANCE - 0.1) blockedRays++;
            totalRays++;
        }

        float coverage = totalRays > 0 ? (float) blockedRays / totalRays : 0.0f;
        return coverage >= HALF_THRESHOLD;
    }

    public float evaluateProtectionFromDirection(BlockPos coverPos, Vec3 threatDirection) {
        Vec3 threatDir = threatDirection.normalize();

        int totalRays = 0;
        int blockedRays = 0;

        double height = TEST_HEIGHTS[0];
        Vec3 origin = new Vec3(coverPos.getX() + 0.5, coverPos.getY() + height, coverPos.getZ() + 0.5);

        for (int ri = 0; ri < CONE_RAYS_PER_HEIGHT; ri++) {
            double angleOffset = -CONE_HALF_ANGLE_DEG + (CONE_HALF_ANGLE_DEG * ri / (CONE_RAYS_PER_HEIGHT - 1));
            Vec3 rayDir = CoverFinder.rotateVectorY(threatDir, angleOffset);
            double distance = raycastDistance(origin, rayDir, CONE_MAX_DISTANCE);

            if (distance < CONE_MAX_DISTANCE - 0.1) blockedRays++;
            totalRays++;
        }

        return totalRays > 0 ? (float) blockedRays / totalRays : 0.0f;
    }
}
package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CoverQualityEvaluator {
    private static final double SOLDIER_STANDING_HEIGHT = 1.8;
    private static final double SOLDIER_CRAWLING_HEIGHT = 0.8;
    private static final double SOLDIER_WIDTH = 0.6;
    private static final double SOLDIER_CRAWL_WIDTH = 0.6;
    
    private final Level level;
    
    public CoverQualityEvaluator(Level level) {
        this.level = level;
    }
    
    public CoverPoint evaluateWithRaycast(CoverPoint coverPoint, LivingEntity threat) {
        if (threat == null) {
            return coverPoint;
        }
        
        BlockPos pos = coverPoint.getPosition();
        Vec3 threatEye = threat.getEyePosition();
        
        List<Vec3> standingTestPoints = generateTestPoints(pos, SOLDIER_STANDING_HEIGHT);
        List<Vec3> crawlTestPoints = generateCrawlTestPoints(pos);
        
        int standingBlocked = 0;
        int crawlBlocked = 0;
        
        StringBuilder rayDebug = new StringBuilder();
        rayDebug.append("Threat eye: ").append(String.format("%.1f,%.1f,%.1f", threatEye.x, threatEye.y, threatEye.z)).append("\n");
        rayDebug.append("Standing rays (height 1.8m, 8 points):\n");
        
        for (int i = 0; i < standingTestPoints.size(); i++) {
            Vec3 testPoint = standingTestPoints.get(i);
            boolean blocked = isRayBlockedBySolidBlock(threatEye, testPoint);
            if (blocked) standingBlocked++;
            rayDebug.append("  [").append(i).append("] y=").append(String.format("%.2f", testPoint.y))
                    .append(" -> ").append(blocked ? "BLOCKED" : "CLEAR").append("\n");
        }
        
        rayDebug.append("Crawl rays (height 0.4m, 4 points at corners):\n");
        for (int i = 0; i < crawlTestPoints.size(); i++) {
            Vec3 testPoint = crawlTestPoints.get(i);
            boolean blocked = isRayBlockedBySolidBlock(threatEye, testPoint);
            if (blocked) crawlBlocked++;
            rayDebug.append("  [").append(i).append("] y=").append(String.format("%.2f", testPoint.y))
                    .append(" -> ").append(blocked ? "BLOCKED" : "CLEAR").append("\n");
        }
        
        boolean canShoot = canShootAtTarget(coverPoint, threat);
        coverPoint.setCanShootFrom(canShoot);
        
        CoverType type = determineTypeFromRaycast(standingBlocked, crawlBlocked, standingTestPoints.size(), crawlTestPoints.size());
        coverPoint.setType(type);
        
        float standingProtection = (float) standingBlocked / standingTestPoints.size();
        float crawlProtection = (float) crawlBlocked / crawlTestPoints.size();
        
        float quality = calculateQualityFromProtection(standingProtection, crawlProtection, canShoot);
        coverPoint.setQuality(quality);
        
        float coverHeight = estimateCoverHeightFromProtection(standingBlocked, crawlBlocked, standingTestPoints.size(), crawlTestPoints.size());
        coverPoint.setCoverHeight(coverHeight);
        
        coverPoint.setDebugInfo(String.format("Stand: %d/8 (%.0f%%) | Crawl: %d/4 (%.0f%%) | Type: %s | Shoot: %s\n%s",
            standingBlocked, standingProtection * 100,
            crawlBlocked, crawlProtection * 100,
            type, canShoot ? "YES" : "NO",
            rayDebug.toString()));
        
        return coverPoint;
    }
    
    private List<Vec3> generateTestPoints(BlockPos pos, double totalHeight) {
        List<Vec3> points = new ArrayList<>();
        
        double baseX = pos.getX() + 0.5;
        double baseY = pos.getY();
        double baseZ = pos.getZ() + 0.5;
        
        double feetY = baseY + totalHeight * 0.10;
        double lowerY = baseY + totalHeight * 0.35;
        double upperY = baseY + totalHeight * 0.65;
        double headY = baseY + totalHeight * 0.85;
        
        double halfWidth = SOLDIER_WIDTH * 0.45;
        
        points.add(new Vec3(baseX - halfWidth, feetY, baseZ));
        points.add(new Vec3(baseX + halfWidth, feetY, baseZ));
        points.add(new Vec3(baseX - halfWidth, lowerY, baseZ));
        points.add(new Vec3(baseX + halfWidth, lowerY, baseZ));
        points.add(new Vec3(baseX - halfWidth, upperY, baseZ));
        points.add(new Vec3(baseX + halfWidth, upperY, baseZ));
        points.add(new Vec3(baseX - halfWidth, headY, baseZ));
        points.add(new Vec3(baseX + halfWidth, headY, baseZ));
        
        return points;
    }
    
    private List<Vec3> generateCrawlTestPoints(BlockPos pos) {
        List<Vec3> points = new ArrayList<>();
        
        double baseX = pos.getX() + 0.5;
        double baseY = pos.getY();
        double baseZ = pos.getZ() + 0.5;
        
        double crawlBodyCenterY = baseY + SOLDIER_CRAWLING_HEIGHT / 2.0;
        
        double halfWidth = SOLDIER_CRAWL_WIDTH * 0.45;
        double halfLength = SOLDIER_CRAWL_WIDTH * 0.45;
        
        points.add(new Vec3(baseX - halfWidth, crawlBodyCenterY, baseZ - halfLength));
        points.add(new Vec3(baseX + halfWidth, crawlBodyCenterY, baseZ - halfLength));
        points.add(new Vec3(baseX - halfWidth, crawlBodyCenterY, baseZ + halfLength));
        points.add(new Vec3(baseX + halfWidth, crawlBodyCenterY, baseZ + halfLength));
        
        return points;
    }
    
    private boolean isRayBlockedBySolidBlock(Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );
        
        HitResult result = level.clip(context);
        
        if (result.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockResult = (BlockHitResult) result;
            BlockPos hitPos = blockResult.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);
            
            if (!isBlockValidCover(hitState, hitPos)) {
                return false;
            }
            
            if (hitState.isCollisionShapeFullBlock(level, hitPos)) {
                return true;
            }
            
            VoxelShape shape = hitState.getCollisionShape(level, hitPos);
            if (!shape.isEmpty()) {
                double maxY = shape.max(Direction.Axis.Y);
                if (maxY >= 1.0 && hitState.isSolid()) {
                    return true;
                }
            }
        }
        
        return false;
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
            block instanceof net.minecraft.world.level.block.TintedGlassBlock) {
            return false;
        }
        
        return true;
    }
    
    private boolean canShootAtTarget(CoverPoint coverPoint, LivingEntity threat) {
        BlockPos coverPos = coverPoint.getPosition();
        Vec3 threatCenter = threat.position().add(0, threat.getBbHeight() / 2.0, 0);
        
        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        
        // Check from the position the soldier will actually be placed at (offset from center)
        if (protectedDirs != null && !protectedDirs.isEmpty()) {
            Direction protectDir = protectedDirs.iterator().next();
            Vec3 offsetCenter = new Vec3(
                coverPos.getX() + 0.5 - protectDir.getStepX() * 0.5,
                coverPos.getY(),
                coverPos.getZ() + 0.5 - protectDir.getStepZ() * 0.5
            );
            if (hasClearShotFromEyePosition(offsetCenter.add(0, 1.62, 0), threatCenter)) {
                return true;
            }
        } else {
            if (hasClearShotFromPosition(coverPos, threatCenter, true)) {
                return true;
            }
        }
        
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return false;
        }
        
        // For full cover, check adjacent peek positions
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (protectedDirs.contains(dir)) continue;
            
            BlockPos peekPos = coverPos.relative(dir);
            if (isStandablePosition(peekPos)) {
                if (hasClearShotFromPosition(peekPos, threatCenter, false)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean hasClearShotFromEyePosition(Vec3 eyePos, Vec3 targetCenter) {
        ClipContext context = new ClipContext(
            eyePos, targetCenter,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );
        
        HitResult result = level.clip(context);
        return result.getType() != HitResult.Type.BLOCK;
    }
    
    private boolean hasClearShotFromPosition(BlockPos fromPos, Vec3 targetCenter, boolean skipOwnBlock) {
        Vec3 eyePos = new Vec3(fromPos.getX() + 0.5, fromPos.getY() + 1.62, fromPos.getZ() + 0.5);
        ClipContext context = new ClipContext(
            eyePos, targetCenter,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );
        
        HitResult result = level.clip(context);
        
        if (result.getType() != HitResult.Type.BLOCK) {
            return true;
        }
        
        if (skipOwnBlock) {
            BlockHitResult blockResult = (BlockHitResult) result;
            BlockPos hitPos = blockResult.getBlockPos();
            if (hitPos.equals(fromPos) || hitPos.equals(fromPos.above())) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean isStandablePosition(BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.isSolid()) return false;
        BlockState standing = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (!standing.getFluidState().isEmpty()) return false;
        if (!head.getFluidState().isEmpty()) return false;
        return (standing.isAir() || standing.getCollisionShape(level, pos).isEmpty()) &&
               (head.isAir() || head.getCollisionShape(level, pos.above()).isEmpty());
    }
    
    private CoverType determineTypeFromRaycast(int standingBlocked, int crawlBlocked, int standingTotal, int crawlTotal) {
        if (standingBlocked == standingTotal) {
            return CoverType.FULL;
        }
        
        if (crawlBlocked == crawlTotal) {
            return CoverType.HALF;
        }
        
        if (standingBlocked > standingTotal / 4 || crawlBlocked > crawlTotal / 2) {
            return CoverType.CONCEALMENT;
        }
        
        return CoverType.NONE;
    }
    
    private float calculateQualityFromProtection(float standingProtection, float crawlProtection, boolean canShoot) {
        float baseQuality = Math.max(standingProtection, crawlProtection);
        
        float shootBonus = canShoot ? 0.15f : 0.0f;
        
        float stanceBonus = 0.0f;
        if (crawlProtection > standingProtection + 0.2f) {
            stanceBonus = 0.1f;
        }
        
        return Math.min(1.0f, baseQuality + shootBonus + stanceBonus);
    }
    
    private float estimateCoverHeightFromProtection(int standingBlocked, int crawlBlocked, int standingTotal, int crawlTotal) {
        if (standingBlocked == standingTotal) {
            return 2.0f;
        }
        if (crawlBlocked == crawlTotal) {
            return 1.0f;
        }
        if (standingBlocked > standingTotal / 4 || crawlBlocked > crawlTotal / 2) {
            return 0.5f;
        }
        return 0.0f;
    }
    
    public boolean isDirectionProtected(CoverPoint coverPoint, Vec3 threatDirection, LivingEntity threat) {
        if (threatDirection == null || coverPoint == null) {
            return false;
        }
        
        BlockPos coverPos = coverPoint.getPosition();
        Vec3 threatEye = threat != null ? threat.getEyePosition() : 
            coverPos.getCenter().add(threatDirection.scale(10));
        
        List<Vec3> crawlTestPoints = generateCrawlTestPoints(coverPos);
        
        int blocked = 0;
        for (Vec3 testPoint : crawlTestPoints) {
            if (isRayBlockedBySolidBlock(threatEye, testPoint)) {
                blocked++;
            }
        }
        
        return blocked == crawlTestPoints.size();
    }
    
    public float evaluateProtectionFromDirection(BlockPos coverPos, Vec3 threatEye) {
        List<Vec3> crawlTestPoints = generateCrawlTestPoints(coverPos);
        
        int blocked = 0;
        for (Vec3 testPoint : crawlTestPoints) {
            if (isRayBlockedBySolidBlock(threatEye, testPoint)) {
                blocked++;
            }
        }
        
        return (float) blocked / crawlTestPoints.size();
    }
}
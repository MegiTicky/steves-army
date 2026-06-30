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

public class CoverQualityEvaluator {
    private static final double SOLDIER_STANDING_HEIGHT = 1.8;
    private static final double SOLDIER_CROUCHING_HEIGHT = 1.5;
    private static final double SOLDIER_WIDTH = 0.6;
    
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
        List<Vec3> crouchingTestPoints = generateTestPoints(pos, SOLDIER_CROUCHING_HEIGHT);
        
        int standingBlocked = 0;
        int crouchingBlocked = 0;
        
        StringBuilder rayDebug = new StringBuilder();
        rayDebug.append("Threat eye: ").append(String.format("%.1f,%.1f,%.1f", threatEye.x, threatEye.y, threatEye.z)).append("\n");
        rayDebug.append("Standing rays (height 1.8m):\n");
        
        for (int i = 0; i < standingTestPoints.size(); i++) {
            Vec3 testPoint = standingTestPoints.get(i);
            boolean blocked = isRayBlockedBySolidBlock(threatEye, testPoint);
            if (blocked) standingBlocked++;
            rayDebug.append("  [").append(i).append("] y=").append(String.format("%.2f", testPoint.y))
                    .append(" -> ").append(blocked ? "BLOCKED" : "CLEAR").append("\n");
        }
        
        rayDebug.append("Crouching rays (height 1.5m):\n");
        for (int i = 0; i < crouchingTestPoints.size(); i++) {
            Vec3 testPoint = crouchingTestPoints.get(i);
            boolean blocked = isRayBlockedBySolidBlock(threatEye, testPoint);
            if (blocked) crouchingBlocked++;
            rayDebug.append("  [").append(i).append("] y=").append(String.format("%.2f", testPoint.y))
                    .append(" -> ").append(blocked ? "BLOCKED" : "CLEAR").append("\n");
        }
        
        float standingProtection = (float) standingBlocked / standingTestPoints.size();
        float crouchingProtection = (float) crouchingBlocked / crouchingTestPoints.size();
        
        boolean canShoot = canShootAtTarget(pos, threat);
        coverPoint.setCanShootFrom(canShoot);
        
        CoverType type = determineTypeFromRaycast(standingProtection, crouchingProtection);
        coverPoint.setType(type);
        
        float quality = calculateQualityFromProtection(standingProtection, crouchingProtection, canShoot);
        coverPoint.setQuality(quality);
        
        float coverHeight = estimateCoverHeightFromProtection(standingProtection, crouchingProtection);
        coverPoint.setCoverHeight(coverHeight);
        
        coverPoint.setDebugInfo(String.format("Stand: %d/8 (%.0f%%) | Crouch: %d/8 (%.0f%%) | Type: %s | Shoot: %s\n%s",
            standingBlocked, standingProtection * 100,
            crouchingBlocked, crouchingProtection * 100,
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
    
    private boolean canShootAtTarget(BlockPos coverPos, LivingEntity threat) {
        Vec3 threatCenter = threat.position().add(0, threat.getBbHeight() / 2.0, 0);
        
        Vec3[] shootOrigins = {
            new Vec3(coverPos.getX() + 0.5, coverPos.getY() + 1.7, coverPos.getZ() + 0.5),
            new Vec3(coverPos.getX() + 0.5, coverPos.getY() + 1.45, coverPos.getZ() + 0.5),
            new Vec3(coverPos.getX() + 0.6, coverPos.getY() + 1.55, coverPos.getZ() + 0.5),
            new Vec3(coverPos.getX() + 0.4, coverPos.getY() + 1.55, coverPos.getZ() + 0.5),
        };
        
        for (Vec3 origin : shootOrigins) {
            ClipContext context = new ClipContext(
                origin, threatCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null
            );
            
            HitResult result = level.clip(context);
            
            if (result.getType() != HitResult.Type.BLOCK) {
                return true;
            }
            
            BlockHitResult blockResult = (BlockHitResult) result;
            BlockPos hitPos = blockResult.getBlockPos();
            if (hitPos.equals(coverPos) || hitPos.equals(coverPos.above())) {
                continue;
            }
            
            BlockState hitState = level.getBlockState(hitPos);
            if (!hitState.isCollisionShapeFullBlock(level, hitPos)) {
                return true;
            }
        }
        
        return false;
    }
    
    private CoverType determineTypeFromRaycast(float standingProtection, float crouchingProtection) {
        if (standingProtection >= 0.85f || crouchingProtection >= 0.85f) {
            return CoverType.FULL;
        }
        
        if (crouchingProtection >= 0.4f) {
            return CoverType.HALF;
        }
        
        if (standingProtection > 0.25f || crouchingProtection > 0.25f) {
            return CoverType.CONCEALMENT;
        }
        
        return CoverType.NONE;
    }
    
    private float calculateQualityFromProtection(float standingProtection, float crouchingProtection, boolean canShoot) {
        float baseQuality = Math.max(standingProtection, crouchingProtection);
        
        float shootBonus = canShoot ? 0.15f : 0.0f;
        
        float stanceBonus = 0.0f;
        if (crouchingProtection > standingProtection + 0.2f) {
            stanceBonus = 0.1f;
        }
        
        return Math.min(1.0f, baseQuality + shootBonus + stanceBonus);
    }
    
    private float estimateCoverHeightFromProtection(float standingProtection, float crouchingProtection) {
        if (standingProtection >= 0.85f || crouchingProtection >= 0.85f) {
            return 2.0f;
        }
        if (crouchingProtection >= 0.4f) {
            return 1.0f;
        }
        if (crouchingProtection > 0.25f || standingProtection > 0.25f) {
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
        
        Vec3 coverCenter = coverPos.getCenter();
        List<Vec3> crouchingTestPoints = generateTestPoints(coverPos, SOLDIER_CROUCHING_HEIGHT);
        
        int blocked = 0;
        for (Vec3 testPoint : crouchingTestPoints) {
            if (isRayBlockedBySolidBlock(threatEye, testPoint)) {
                blocked++;
            }
        }
        
        float protection = (float) blocked / crouchingTestPoints.size();
        return protection >= 0.4f;
    }
    
    public float evaluateProtectionFromDirection(BlockPos coverPos, Vec3 threatEye) {
        List<Vec3> crouchingTestPoints = generateTestPoints(coverPos, SOLDIER_CROUCHING_HEIGHT);
        
        int blocked = 0;
        for (Vec3 testPoint : crouchingTestPoints) {
            if (isRayBlockedBySolidBlock(threatEye, testPoint)) {
                blocked++;
            }
        }
        
        return (float) blocked / crouchingTestPoints.size();
    }
}
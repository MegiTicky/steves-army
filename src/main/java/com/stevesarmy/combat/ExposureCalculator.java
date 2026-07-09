package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExposureCalculator {
    
    public enum AimPointType {
        HEAD(4, "HEAD"),
        NECK(3, "NECK"),
        CENTER_MASS(8, "CENTER"),
        UPPER_TORSO(7, "UPPER"),
        LOWER_TORSO(6, "LOWER"),
        HIP(5, "HIP"),
        FEET(3, "FEET"),
        FALLBACK(0, "FALLBACK");
        
        public final int priority;
        public final String displayName;
        
        AimPointType(int priority, String displayName) {
            this.priority = priority;
            this.displayName = displayName;
        }
    }
    
    public static class AimPointResult {
        public final Vec3 position;
        public final AimPointType type;
        public final boolean bulletPathClear;
        public final boolean pointVisible;
        
        public AimPointResult(Vec3 position, AimPointType type, boolean bulletPathClear, boolean pointVisible) {
            this.position = position;
            this.type = type;
            this.bulletPathClear = bulletPathClear;
            this.pointVisible = pointVisible;
        }
        
        public boolean canShoot() {
            return pointVisible && bulletPathClear;
        }
    }
    
    public static int calculateExposure(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return 0;
        
        Level level = observer.level();
        Vec3 observerEye = observer.getEyePosition();
        
        Vec3[] targetPoints = getTargetPoints(target);
        
        int visiblePoints = 0;
        for (Vec3 point : targetPoints) {
            if (canSeePoint(level, observerEye, point, observer)) {
                visiblePoints++;
            }
        }
        
        return visiblePoints;
    }
    
    public static double getExposureFactor(LivingEntity observer, LivingEntity target) {
        int visiblePoints = calculateExposure(observer, target);
        return Math.sqrt(visiblePoints / 8.0);
    }
    
    public static AimPointResult getBestAimPoint(LivingEntity observer, LivingEntity target) {
        return getBestAimPoint(observer, target, null);
    }

    public static AimPointResult getBestAimPoint(LivingEntity observer, LivingEntity target, BlockPos skipBlock) {
        if (observer.level() != target.level()) {
            return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false);
        }
        
        Level level = observer.level();
        Vec3 observerEye = observer.getEyePosition();
        
        TargetPoint[] targetPoints = getTargetPointsWithPriority(target);
        
        TargetPoint bestVisible = null;
        
        for (TargetPoint point : targetPoints) {
            boolean canReach = canSeePoint(level, observerEye, point.position, observer, skipBlock);
            if (canReach) {
                if (bestVisible == null || point.type.priority > bestVisible.type.priority) {
                    bestVisible = point;
                }
            }
        }
        
        if (bestVisible != null) {
            return new AimPointResult(bestVisible.position, bestVisible.type, true, true);
        }
        
        return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false);
    }
    
    private static TargetPoint[] getTargetPointsWithPriority(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();
        
        double headY = basePos.y + height * 0.92;
        double neckY = basePos.y + height * 0.82;
        double midTorsoY = basePos.y + height * 0.55;
        double upperTorsoY = basePos.y + height * 0.70;
        double lowerTorsoY = basePos.y + height * 0.40;
        double hipY = basePos.y + height * 0.25;
        double feetY = basePos.y + height * 0.10;
        
        double halfWidth = width * 0.35;
        double quarterWidth = width * 0.15;
        
        return new TargetPoint[] {
            new TargetPoint(new Vec3(basePos.x, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x, neckY, basePos.z), AimPointType.NECK),
            new TargetPoint(new Vec3(basePos.x, midTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, upperTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, lowerTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, hipY, basePos.z), AimPointType.HIP),
            new TargetPoint(new Vec3(basePos.x - quarterWidth, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x + quarterWidth, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x - halfWidth, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
            new TargetPoint(new Vec3(basePos.x + halfWidth, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
            new TargetPoint(new Vec3(basePos.x - halfWidth, lowerTorsoY, basePos.z), AimPointType.LOWER_TORSO),
            new TargetPoint(new Vec3(basePos.x + halfWidth, lowerTorsoY, basePos.z), AimPointType.LOWER_TORSO),
            new TargetPoint(new Vec3(basePos.x - halfWidth, feetY, basePos.z), AimPointType.FEET),
            new TargetPoint(new Vec3(basePos.x + halfWidth, feetY, basePos.z), AimPointType.FEET),
        };
    }
    
    private static Vec3[] getTargetPoints(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();
        
        double headY = basePos.y + height * 0.85;
        double upperTorsoY = basePos.y + height * 0.65;
        double lowerTorsoY = basePos.y + height * 0.35;
        double feetY = basePos.y + height * 0.1;
        
        double halfWidth = width * 0.45;
        
        return new Vec3[] {
            new Vec3(basePos.x - halfWidth, headY, basePos.z),
            new Vec3(basePos.x + halfWidth, headY, basePos.z),
            new Vec3(basePos.x - halfWidth, upperTorsoY, basePos.z),
            new Vec3(basePos.x + halfWidth, upperTorsoY, basePos.z),
            new Vec3(basePos.x - halfWidth, lowerTorsoY, basePos.z),
            new Vec3(basePos.x + halfWidth, lowerTorsoY, basePos.z),
            new Vec3(basePos.x - halfWidth, feetY, basePos.z),
            new Vec3(basePos.x + halfWidth, feetY, basePos.z)
        };
    }
    
    private static boolean canSeePoint(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return canSeePoint(level, from, to, observer, null);
    }

    private static boolean canSeePoint(Level level, Vec3 from, Vec3 to, LivingEntity observer, BlockPos skipBlock) {
        ClipContext context = new ClipContext(
            from,
            to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            observer
        );
        
        HitResult result = level.clip(context);
        if (result.getType() == HitResult.Type.MISS) return true;
        if (skipBlock != null && result.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockResult = (BlockHitResult) result;
            if (blockResult.getBlockPos().equals(skipBlock) || blockResult.getBlockPos().equals(skipBlock.above())) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean canBulletReach(Level level, Vec3 from, Vec3 to, LivingEntity shooter, BlockPos skipBlock) {
        return canSeePoint(level, from, to, shooter, skipBlock);
    }
    
    private static class TargetPoint {
        final Vec3 position;
        final AimPointType type;
        
        TargetPoint(Vec3 position, AimPointType type) {
            this.position = position;
            this.type = type;
        }
    }
}
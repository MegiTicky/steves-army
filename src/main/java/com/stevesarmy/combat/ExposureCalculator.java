package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExposureCalculator {
    
    public enum AimPointType {
        HEAD(4, "HEAD"),
        NECK(3, "NECK"),
        CENTER_MASS(8, "CENTER"),
        UPPER_TORSO(9, "UPPER"),
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
        public final double concealment;
        
        public AimPointResult(Vec3 position, AimPointType type, boolean bulletPathClear,
                              boolean pointVisible, double concealment) {
            this.position = position;
            this.type = type;
            this.bulletPathClear = bulletPathClear;
            this.pointVisible = pointVisible;
            this.concealment = concealment;
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
            if (getVisibility(level, observerEye, point, observer).hasContact()) {
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
            if (DiagnosticLogManager.isDamageLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] getBestAimPoint: different levels, returning FALLBACK");
            }
            return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false, 1.0);
        }
        
        Level level = observer.level();
        Vec3 observerEye = observer.getEyePosition();
        
        TargetPoint[] targetPoints = getTargetPointsWithPriority(target);
        
        TargetPoint bestVisible = null;
        
        for (TargetPoint point : targetPoints) {
            VisibilityRay.Result visibility = getVisibility(level, observerEye, point.position, observer, skipBlock);
            if (visibility.hasContact()) {
                if (bestVisible == null || point.type.priority > bestVisible.type.priority) {
                    bestVisible = point;
                }
            }
        }
        
        if (bestVisible != null) {
            if (DiagnosticLogManager.isDamageLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] getBestAimPoint: FOUND aimpoint type={} pos=({},{},{})",
                    bestVisible.type.displayName,
                    String.format("%.2f", bestVisible.position.x),
                    String.format("%.2f", bestVisible.position.y),
                    String.format("%.2f", bestVisible.position.z));
            }
            VisibilityRay.Result visibility = getVisibility(
                level, observerEye, bestVisible.position, observer, skipBlock);
            return new AimPointResult(bestVisible.position, bestVisible.type, true, true, visibility.concealment());
        }
        
        if (DiagnosticLogManager.isDamageLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] getBestAimPoint: NO visible aimpoint, returning FALLBACK. observer=({},{},{}) target=({},{},{})",
                String.format("%.2f", observer.getX()), String.format("%.2f", observer.getEyeY()), String.format("%.2f", observer.getZ()),
                String.format("%.2f", target.getX()), String.format("%.2f", target.getY()), String.format("%.2f", target.getZ()));
        }
        return new AimPointResult(target.getEyePosition(), AimPointType.FALLBACK, false, false, 1.0);
    }
    
    private static TargetPoint[] getTargetPointsWithPriority(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();
        
        double headY = basePos.y + height * 0.92;
        double neckY = basePos.y + height * 0.82;
        double midTorsoY = basePos.y + height * 0.55;
        double upperTorsoY = basePos.y + height * 0.68;
        double lowerTorsoY = basePos.y + height * 0.40;
        double hipY = basePos.y + height * 0.25;
        double feetY = basePos.y + height * 0.10;
        
        double halfWidth = width * 0.35;
        double quarterWidth = width * 0.15;
        
        return new TargetPoint[] {
            new TargetPoint(new Vec3(basePos.x, headY, basePos.z), AimPointType.HEAD),
            new TargetPoint(new Vec3(basePos.x, neckY, basePos.z), AimPointType.NECK),
            new TargetPoint(new Vec3(basePos.x, midTorsoY, basePos.z), AimPointType.CENTER_MASS),
            new TargetPoint(new Vec3(basePos.x, upperTorsoY, basePos.z), AimPointType.UPPER_TORSO),
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
    
    private static VisibilityRay.Result getVisibility(Level level, Vec3 from, Vec3 to,
                                                       LivingEntity observer, BlockPos skipBlock) {
        if (skipBlock == null) {
            return VisibilityRay.trace(level, from, to, observer);
        }

        // Cover peeks intentionally ignore the selected cover block and the block above it.
        BlockPos first = skipBlock;
        BlockPos second = skipBlock.above();
        return VisibilityRay.trace(level, from, to, observer, first, second);
    }

    private static VisibilityRay.Result getVisibility(Level level, Vec3 from, Vec3 to,
                                                       LivingEntity observer) {
        return VisibilityRay.trace(level, from, to, observer);
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

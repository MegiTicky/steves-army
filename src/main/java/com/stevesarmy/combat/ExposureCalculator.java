package com.stevesarmy.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExposureCalculator {
    
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
    
    public static Vec3 getBestAimPoint(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return target.getEyePosition();
        
        Level level = observer.level();
        Vec3 observerEye = observer.getEyePosition();
        
        TargetPoint[] targetPoints = getTargetPointsWithPriority(target);
        
        List<TargetPoint> visiblePoints = new ArrayList<>();
        for (TargetPoint point : targetPoints) {
            if (canSeePoint(level, observerEye, point.position, observer)) {
                visiblePoints.add(point);
            }
        }
        
        if (visiblePoints.isEmpty()) {
            return target.getEyePosition();
        }
        
        visiblePoints.sort(Comparator.comparingInt(p -> -p.priority));
        
        return visiblePoints.get(0).position;
    }
    
    private static TargetPoint[] getTargetPointsWithPriority(LivingEntity target) {
        Vec3 basePos = target.position();
        float height = target.getBbHeight();
        float width = target.getBbWidth();
        
        double headY = basePos.y + height * 0.9;
        double neckY = basePos.y + height * 0.8;
        double upperTorsoY = basePos.y + height * 0.65;
        double midTorsoY = basePos.y + height * 0.5;
        double lowerTorsoY = basePos.y + height * 0.35;
        double hipY = basePos.y + height * 0.2;
        double feetY = basePos.y + height * 0.1;
        
        double halfWidth = width * 0.35;
        double centerWidth = width * 0.0;
        
        return new TargetPoint[] {
            new TargetPoint(new Vec3(basePos.x, headY, basePos.z), 10),
            new TargetPoint(new Vec3(basePos.x, neckY, basePos.z), 9),
            new TargetPoint(new Vec3(basePos.x - halfWidth, upperTorsoY, basePos.z), 7),
            new TargetPoint(new Vec3(basePos.x + halfWidth, upperTorsoY, basePos.z), 7),
            new TargetPoint(new Vec3(basePos.x, midTorsoY, basePos.z), 8),
            new TargetPoint(new Vec3(basePos.x - halfWidth, lowerTorsoY, basePos.z), 6),
            new TargetPoint(new Vec3(basePos.x + halfWidth, lowerTorsoY, basePos.z), 6),
            new TargetPoint(new Vec3(basePos.x, hipY, basePos.z), 5),
            new TargetPoint(new Vec3(basePos.x - halfWidth, feetY, basePos.z), 3),
            new TargetPoint(new Vec3(basePos.x + halfWidth, feetY, basePos.z), 3),
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
        ClipContext context = new ClipContext(
            from,
            to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            observer
        );
        
        HitResult result = level.clip(context);
        return result.getType() == HitResult.Type.MISS;
    }
    
    private static class TargetPoint {
        final Vec3 position;
        final int priority;
        
        TargetPoint(Vec3 position, int priority) {
            this.position = position;
            this.priority = priority;
        }
    }
}

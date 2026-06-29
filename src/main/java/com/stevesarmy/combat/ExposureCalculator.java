package com.stevesarmy.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
}

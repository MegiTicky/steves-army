package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class TargetAcquisition {
    
    public static boolean canSeeTarget(LivingEntity observer, LivingEntity target) {
        if (observer.level() != target.level()) return false;
        if (!target.isAlive()) return false;
        
        if (!hasLineOfSight(observer, target)) return false;
        
        return true;
    }
    
    public static boolean isInFocusedArc(LivingEntity observer, LivingEntity target) {
        return isInArc(observer, target, DetectionSystem.FOCUSED_ARC_DEGREES);
    }
    
    public static boolean isInPeripheralArc(LivingEntity observer, LivingEntity target) {
        return isInArc(observer, target, DetectionSystem.PERIPHERAL_ARC_DEGREES);
    }
    
    private static boolean isInArc(LivingEntity observer, LivingEntity target, double arcDegrees) {
        Vec3 observerLook = observer.getLookAngle();
        Vec3 toTarget = target.position().subtract(observer.position()).normalize();
        
        double dot = observerLook.dot(toTarget);
        double angleRadians = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double angleDegrees = Math.toDegrees(angleRadians);
        
        return angleDegrees <= arcDegrees / 2.0;
    }
    
    public static boolean hasLineOfSight(LivingEntity observer, LivingEntity target) {
        Vec3 observerEye = observer.getEyePosition();
        Vec3 targetEye = target.getEyePosition();
        
        ClipContext context = new ClipContext(
            observerEye,
            targetEye,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            observer
        );
        
        HitResult result = observer.level().clip(context);
        return result.getType() == HitResult.Type.MISS;
    }
    
    public static boolean isValidTarget(LivingEntity observer, LivingEntity target) {
        if (target == observer) return false;
        if (!target.isAlive()) return false;
        if (target.isSpectator()) return false;
        return true;
    }
    
    public static BlockPos getEstimatedPosition(LivingEntity target, double accuracy) {
        if (accuracy >= 1.0) {
            return target.blockPosition();
        }
        
        double maxOffset = 10.0 * (1.0 - accuracy);
        double offsetX = (target.level().random.nextDouble() - 0.5) * maxOffset * 2;
        double offsetZ = (target.level().random.nextDouble() - 0.5) * maxOffset * 2;
        
        return target.blockPosition().offset(
            (int) Math.round(offsetX),
            0,
            (int) Math.round(offsetZ)
        );
    }
}
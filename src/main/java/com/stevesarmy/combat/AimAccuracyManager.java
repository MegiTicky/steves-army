package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

public class AimAccuracyManager {
    
    private static final float BASE_TRACKING_TIME_MS = 500.0f;
    
    public static float getBaseAccuracy() {
        return StevesArmyConfig.getBaseAccuracy();
    }
    
    public static float getTrackingSpeed(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        
        float distanceFactor = calculateDistanceTrackingFactor(distance, effectiveRange);
        float movementFactor = calculateMovementTrackingFactor(target);
        float exposureFactor = calculateExposureTrackingFactor(soldier, target);
        
        return distanceFactor * movementFactor * exposureFactor;
    }
    
    public static float getTrackingTimeMs(LivingEntity soldier, LivingEntity target) {
        float speed = getTrackingSpeed(soldier, target);
        return BASE_TRACKING_TIME_MS / Math.max(speed, 0.1f);
    }
    
    public static float calculateAccuracy(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        
        float distanceAccuracy = calculateDistanceAccuracy(distance, effectiveRange);
        float movementAccuracy = calculateMovementAccuracy(target);
        float exposureAccuracy = calculateExposureAccuracy(soldier, target);
        
        return Mth.clamp(getBaseAccuracy() * distanceAccuracy * movementAccuracy * exposureAccuracy, 0.1f, 1.0f);
    }
    
    public static float calculateHitProbability(LivingEntity soldier, LivingEntity target) {
        return calculateAccuracy(soldier, target);
    }
    
    public static float calculateShotThreshold(float trackingProgress, float accuracy) {
        return trackingProgress * accuracy;
    }
    
    public static float calculateMaxDeviation(float accuracy, double distance) {
        float baseDeviation = (1.0f - accuracy) * 5.0f;
        double distanceFactor = Math.max(1.0, distance / 10.0);
        return (float) (baseDeviation * distanceFactor);
    }
    
    private static float calculateDistanceTrackingFactor(double distance, double effectiveRange) {
        if (distance <= 0.5) {
            return 3.0f;
        }
        if (distance <= effectiveRange) {
            float ratio = (float) (distance / effectiveRange);
            return Mth.lerp(ratio, 2.0f, 1.0f);
        }
        return (float) Math.max(0.3, effectiveRange / distance);
    }
    
    private static float calculateMovementTrackingFactor(LivingEntity target) {
        double horizontalSpeed = target.getDeltaMovement().horizontalDistanceSqr();
        
        if (horizontalSpeed < 0.01) {
            return 1.0f;
        }
        if (horizontalSpeed < 0.05) {
            return 0.9f;
        }
        if (horizontalSpeed < 0.1) {
            return 0.8f;
        }
        if (horizontalSpeed < 0.2) {
            return 0.6f;
        }
        return (float) Math.max(0.4, 1.0 - horizontalSpeed * 2.0);
    }
    
    private static float calculateExposureTrackingFactor(LivingEntity soldier, LivingEntity target) {
        float exposure = (float) ExposureCalculator.getExposureFactor(soldier, target);
        return 0.6f + 0.4f * exposure;
    }
    
    private static float calculateDistanceAccuracy(double distance, double effectiveRange) {
        if (distance <= effectiveRange) {
            return 1.0f;
        }
        return (float) Math.max(0.3, effectiveRange / distance);
    }
    
    private static float calculateMovementAccuracy(LivingEntity target) {
        double horizontalSpeed = target.getDeltaMovement().horizontalDistanceSqr();
        
        if (horizontalSpeed < 0.01) {
            return 1.0f;
        }
        if (horizontalSpeed < 0.05) {
            return 0.95f;
        }
        if (horizontalSpeed < 0.1) {
            return 0.85f;
        }
        if (horizontalSpeed < 0.2) {
            return 0.7f;
        }
        return (float) Math.max(0.5, 1.0 - horizontalSpeed * 1.5);
    }
    
    private static float calculateExposureAccuracy(LivingEntity soldier, LivingEntity target) {
        float exposure = (float) ExposureCalculator.getExposureFactor(soldier, target);
        return 0.7f + 0.3f * exposure;
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Optional;

public class AimAccuracyManager {
    
    private static final float MAX_SPREAD_DEG = 5.0f;

    public static float getTargetAimQuality(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        float base = StevesArmyConfig.getAimQualityBaseAccuracy();
        float distanceFactor = calculateDistanceAccuracy(distance, effectiveRange);
        float movementFactor = calculateMovementAccuracy(target);
        float exposureFactor = calculateExposureAccuracy(soldier, target);
        return Mth.clamp(base * distanceFactor * movementFactor * exposureFactor, 0.0f, 1.0f);
    }

    public static float getBuildRate(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        float baseRate = StevesArmyConfig.getAimQualityBuildRate();
        float exposureFactor = calculateExposureTrackingFactor(soldier, target);
        float distanceFactor = calculateDistanceTrackingFactor(distance, effectiveRange);
        return baseRate * exposureFactor * distanceFactor;
    }

    public static float calculateHitProbability(LivingEntity soldier, LivingEntity target) {
        return getTargetAimQuality(soldier, target);
    }

    public static Vec3 calculateMissPosition(LivingEntity target, Level level) {
        float missOffsetDistance = 2.0f + level.getRandom().nextFloat() * 3.0f;
        float missAngleRadians = level.getRandom().nextFloat() * (float) (2 * Math.PI);
        float verticalOffset = level.getRandom().nextFloat() * 2.0f;
        
        return target.position().add(
            Math.cos(missAngleRadians) * missOffsetDistance,
            verticalOffset,
            Math.sin(missAngleRadians) * missOffsetDistance
        );
    }

    public static float[] getGunRecoil(LivingEntity entity) {
        if (!GunIntegration.isTaczLoaded()) {
            return new float[]{0.5f, 0.25f};
        }
        try {
            ItemStack gunStack = entity.getMainHandItem();
            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return new float[]{0.5f, 0.25f};

            Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
            Object gunId = getGunId.invoke(iGun, gunStack);

            Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
            Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
            Object indexOpt = getCommonGunIndex.invoke(null, gunId);

            if (indexOpt instanceof Optional<?> opt && opt.isPresent()) {
                Object gunIndex = opt.get();
                Method getGunData = gunIndex.getClass().getMethod("getGunData");
                Object gunData = getGunData.invoke(gunIndex);

                Method getRecoilMethod = gunData.getClass().getMethod("getRecoil");
                Object recoil = getRecoilMethod.invoke(gunData);

                Method getPitch = recoil.getClass().getMethod("getPitch");
                Object[] pitchFrames = (Object[]) getPitch.invoke(recoil);

                Method getYaw = recoil.getClass().getMethod("getYaw");
                Object[] yawFrames = (Object[]) getYaw.invoke(recoil);

                Method getValue = pitchFrames[0].getClass().getMethod("getValue");
                float[] pitchValues = (float[]) getValue.invoke(pitchFrames[0]);
                float[] yawValues = (float[]) getValue.invoke(yawFrames[0]);

                float vertical = Math.abs(pitchValues.length > 0 ? pitchValues[0] : 0);
                float horizontal = Math.abs(yawValues.length > 0 ? yawValues[0] : 0);

                return new float[]{vertical, horizontal};
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.debug("[AimQuality] Failed to get gun recoil: {}", e.getMessage());
        }
        return new float[]{0.5f, 0.25f};
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

    public static float calculateExposureTrackingFactor(LivingEntity soldier, LivingEntity target) {
        float exposure = (float) ExposureCalculator.getExposureFactor(soldier, target);
        return 0.6f + 0.4f * exposure;
    }

    public static float calculateDistanceAccuracy(double distance, double effectiveRange) {
        double optimalDistance = effectiveRange * 0.5;
        
        if (distance <= optimalDistance) {
            float ratio = (float) (distance / optimalDistance);
            return Mth.lerp(ratio, 1.2f, 1.0f);
        }
        if (distance <= effectiveRange) {
            float ratio = (float) ((distance - optimalDistance) / (effectiveRange - optimalDistance));
            return Mth.lerp(ratio, 1.0f, 0.7f);
        }
        return (float) Math.max(0.3, effectiveRange / distance);
    }

    public static float calculateMovementAccuracy(LivingEntity target) {
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

    public static float calculateExposureAccuracy(LivingEntity soldier, LivingEntity target) {
        float exposure = (float) ExposureCalculator.getExposureFactor(soldier, target);
        return 0.2f + 0.8f * exposure;
    }
}

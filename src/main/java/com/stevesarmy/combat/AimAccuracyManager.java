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

/**
 * Converts aim stability (0..1) into angular dispersion (standard deviation in degrees).
 * 
 * Higher aimQuality = tighter cone = more shots near the center of the aim point.
 * The model is emergent: hit rate depends on target size, distance, and exposure.
 * Range and exposure are NOT used to weaken precision — they affect target angular
 * size instead, so geometry naturally determines difficulty.
 * 
 * Raw aimQuality normally tops out at the configured baseAccuracy (0.75 by
 * default), so dispersion uses normalized stability rather than treating 0.75
 * as only half of the attainable aim. The resulting curve is:
 *   stability 0.0 -> 2.50° horizontal, 0.75° vertical
 *   stability 0.35 -> ~1.60° horizontal, ~0.51° vertical
 *   stability 0.50 -> ~1.23° horizontal, ~0.44° vertical
 *   stability 0.75 -> ~0.68° horizontal, ~0.30° vertical
 *   stability 1.0 -> 0.25° horizontal, 0.15° vertical
 */
public class AimAccuracyManager {

    // Maximum angular standard deviations (degrees) at the firing threshold.
    private static final float MAX_HORIZONTAL_SIGMA = 2.50f;
    private static final float MAX_VERTICAL_SIGMA = 0.75f;

    // Controls how quickly the cone tightens as the soldier settles the aim.
    private static final float CURVE_SHARPNESS = 1.20f;

    // Minimum sigma (degrees) applied even at perfect aimQuality 1.0
    private static final float MIN_HORIZONTAL_SIGMA = 0.25f;
    private static final float MIN_VERTICAL_SIGMA = 0.15f;
    private static final double MAX_STANDARD_DEVIATIONS = 2.5;

    public static float getTargetAimQuality(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        float base = StevesArmyConfig.getAimQualityBaseAccuracy();
        float movementFactor = calculateMovementAccuracy(target);
        return Mth.clamp(base * movementFactor, 0.0f, 1.0f);
    }

    /**
     * Returns angular standard deviation in degrees for yaw (horizontal) error
     * at the given aim quality.
     */
    public static float getYawSigma(float aimQuality) {
        float sigma = powerLerp(normalizeAimStability(aimQuality), MAX_HORIZONTAL_SIGMA,
            pickWeaponSigmaHorizontal(), CURVE_SHARPNESS);
        return Math.max(sigma, MIN_HORIZONTAL_SIGMA);
    }

    /**
     * Returns angular standard deviation in degrees for pitch (vertical) error
     * at the given aim quality.
     */
    public static float getPitchSigma(float aimQuality) {
        float sigma = powerLerp(normalizeAimStability(aimQuality), MAX_VERTICAL_SIGMA,
            pickWeaponSigmaVertical(), CURVE_SHARPNESS);
        return Math.max(sigma, MIN_VERTICAL_SIGMA);
    }

    /**
     * Sample a random angular deviation from a 2D Gaussian centered on the aim point.
     * Uses the Box-Muller transform for Gaussian random numbers.
     */
    public static float[] sampleGaussianDeviation(float aimQuality, float yawSigma, float pitchSigma, Level level) {
        double u1 = level.getRandom().nextDouble();
        double u2 = level.getRandom().nextDouble();
        if (u1 < 1e-15) u1 = 1e-15; // avoid log(0)

        double radius = Math.sqrt(-2.0 * Math.log(u1));
        double angle = 2.0 * Math.PI * u2;

        // Limit extreme Gaussian outliers without biasing the normal miss pattern.
        double yawNormal = Mth.clamp(radius * Math.cos(angle), -MAX_STANDARD_DEVIATIONS, MAX_STANDARD_DEVIATIONS);
        double pitchNormal = Mth.clamp(radius * Math.sin(angle), -MAX_STANDARD_DEVIATIONS, MAX_STANDARD_DEVIATIONS);
        float yawDev = (float) (yawNormal * yawSigma);
        float pitchDev = (float) (pitchNormal * pitchSigma);

        return new float[]{pitchDev, yawDev};
    }

    public static float getBuildRate(LivingEntity soldier, LivingEntity target) {
        double distance = soldier.distanceTo(target);
        double effectiveRange = GunIntegration.getEffectiveRange(soldier);
        float baseRate = StevesArmyConfig.getAimQualityBuildRate();
        float exposureFactor = calculateExposureTrackingFactor(soldier, target);
        float distanceFactor = calculateDistanceTrackingFactor(distance, effectiveRange);
        double concealment = TargetAcquisition.getVisibility(soldier, target).concealment();
        float concealmentFactor = (float) Math.max(0.25, 1.0 - concealment * 0.70);
        return baseRate * exposureFactor * distanceFactor * concealmentFactor;
    }

    public static float calculateHitProbability(LivingEntity soldier, LivingEntity target) {
        // Approximate estimate for target selection purposes.
        // Uses a circular target of radius = width/2 at the distance.
        double distance = soldier.distanceTo(target);
        if (distance < 0.5) return 1.0f;

        float width = target.getBbWidth();
        double angularRadiusDeg = Math.toDegrees(Math.atan2(width * 0.5, distance));

        float hitQuality = getTargetAimQuality(soldier, target);
        float yawSigma = getYawSigma(hitQuality);

        // Rough isotropic hit probability
        if (yawSigma < 0.01f) return hitQuality > 0.5f ? 1.0f : 0.0f;
        double p = 1.0 - Math.exp(-(angularRadiusDeg * angularRadiusDeg) / (2.0 * yawSigma * yawSigma));
        return (float) Mth.clamp(p, 0.0f, 1.0f);
    }

    public static float[] getGunRecoil(LivingEntity entity) {
        return GunIntegration.getGunRecoil(entity);
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

    /** Converts raw aimQuality into progress toward its configured ideal ceiling. */
    private static float normalizeAimStability(float aimQuality) {
        float idealAimQuality = Math.max(0.01f, StevesArmyConfig.getAimQualityBaseAccuracy());
        return Mth.clamp(aimQuality / idealAimQuality, 0.0f, 1.0f);
    }

    /**
     * Nonlinear interpolation between max (at stability=0) and min (at stability=1).
     * Uses the remaining instability so the cone tightens progressively:
     * result = min + (max - min) * pow(1 - stability, sharpness)
     */
    private static float powerLerp(float stability, float maxVal, float minVal, float sharpness) {
        float remainingInstability = 1.0f - Mth.clamp(stability, 0.0f, 1.0f);
        return minVal + (maxVal - minVal) * (float) Math.pow(remainingInstability, sharpness);
    }

    /**
     * Returns the horizontal sigma floor from the weapon's TaCZ inaccuracy value.
     */
    private static float pickWeaponSigmaHorizontal() {
        // In degrees, minimum weapon spread.
        // TaCZ getAimInaccuracy is in degrees, typically 2-5 for rifles.
        // We halve it as the sigma floor so most shots land within the inaccuracy cone.
        return 0.5f;
    }

    private static float pickWeaponSigmaVertical() {
        return 0.3f;
    }
}

package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-only integration with the tallyho vehicle mod (no compile or runtime
 * hard dependency). Exposes the pieces the vehicle crew role needs:
 *
 * - HullMachineGunEntity: a CameraEntity2 turret with clamped ship-space aiming
 *   ({@code getLimitedRotationFromTarget}), delta rotation ({@code turnView}),
 *   and CBC autocannon fire ({@code ITurret.handleShoot}); belt/reload handled
 *   internally by tallyho.
 * - PeriscopeEntity: a CameraEntity2 observation optic with yaw limits.
 * - Player possession ({@code isPossessed}) so crew always yields to a player.
 *
 * All reflected members are tallyho-declared, so their names survive Forge's
 * reobfuscation (vanilla overrides are not referenced by name).
 */
public final class TallyhoCompat {
    private static final String MOD_ID = "tallyho";
    private static final String CAMERA_ENTITY_CLASS =
        "edn.stratodonut.tallyho.camera.entity.CameraEntity2";
    private static final String HULL_MG_CLASS =
        "edn.stratodonut.tallyho.camera.entity.HullMachineGunEntity";
    private static final String PERISCOPE_CLASS =
        "edn.stratodonut.tallyho.camera.entity.PeriscopeEntity";
    private static final String ITURRET_CLASS =
        "edn.stratodonut.tallyho.camera.entity.ITurret";

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static boolean failureLogged;

    private static Class<?> cameraEntityClass;
    private static Class<?> hullMgClass;
    private static Class<?> periscopeClass;
    private static Class<?> iTurretClass;
    private static Method turnView;                  // (double yawDelta, double pitchDelta)
    private static Method limitedRotationFromTarget; // (Vec3 worldTarget, float partialTick) -> Vec2
    private static Method rotationFromDirection;     // (Vec3 worldDir) -> Vec2, UNLIMITED
    private static Method limitedRotationFromDirection; // (Vec3 worldDir) -> Vec2, clamped
    private static Method isPossessed;               // () -> boolean
    private static Method handleShoot;               // (LivingEntity)
    private static Method getBaseYaw;                // () -> float
    private static Method getYawLimit;               // AngleLimits.Y() -> float
    private static Field firingCooldown;             // int, HullMachineGunEntity
    private static Field belt;                       // int, HullMachineGunEntity

    private TallyhoCompat() {}

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            cameraEntityClass = Class.forName(CAMERA_ENTITY_CLASS);
            hullMgClass = Class.forName(HULL_MG_CLASS);
            periscopeClass = Class.forName(PERISCOPE_CLASS);
            iTurretClass = Class.forName(ITURRET_CLASS);

            turnView = cameraEntityClass.getMethod("turnView", double.class, double.class);
            limitedRotationFromTarget = cameraEntityClass
                .getDeclaredMethod("getLimitedRotationFromTarget", Vec3.class, float.class);
            limitedRotationFromTarget.setAccessible(true);
            rotationFromDirection = cameraEntityClass
                .getDeclaredMethod("getRotationFromDirection", Vec3.class);
            rotationFromDirection.setAccessible(true);
            limitedRotationFromDirection = cameraEntityClass
                .getDeclaredMethod("getLimitedRotationFromDirection", Vec3.class);
            limitedRotationFromDirection.setAccessible(true);
            isPossessed = cameraEntityClass.getMethod("isPossessed");
            getBaseYaw = cameraEntityClass.getMethod("getBaseYaw");

            Class<?> angleLimitsClass = Class.forName(
                "edn.stratodonut.tallyho.camera.AngleLimits");
            getYawLimit = angleLimitsClass.getMethod("Y");

            handleShoot = iTurretClass.getMethod("handleShoot", LivingEntity.class);

            firingCooldown = hullMgClass.getDeclaredField("firingCooldown");
            firingCooldown.setAccessible(true);
            belt = hullMgClass.getDeclaredField("belt");
            belt.setAccessible(true);

            available = true;
            StevesArmyMod.LOGGER.info("[Tallyho] Vehicle crew station integration enabled");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logFailure(exception);
        }
    }

    private static void logFailure(Throwable exception) {
        available = false;
        if (!failureLogged) {
            failureLogged = true;
            StevesArmyMod.LOGGER.warn("[Tallyho] Vehicle crew station integration unavailable: {}",
                exception.toString());
        }
    }

    public static boolean isAvailable() {
        initialize();
        return available;
    }

    public static boolean isHullMG(Entity entity) {
        return available && hullMgClass == entity.getClass();
    }

    public static boolean isPeriscope(Entity entity) {
        return available && periscopeClass == entity.getClass();
    }

    /** True for any crewable vehicle station entity (hull MG or periscope camera). */
    public static boolean isCameraEntity(Entity entity) {
        return available && (hullMgClass == entity.getClass() || periscopeClass == entity.getClass());
    }

    /** True when a player is currently possessing the camera (players outrank crew). */
    public static boolean isPlayerPossessed(Entity camera) {
        if (!available || !cameraEntityClass.isInstance(camera)) {
            return false;
        }
        try {
            return (boolean) isPossessed.invoke(camera);
        } catch (Exception exception) {
            logFailure(exception);
            return false;
        }
    }

    public static float getBaseYaw(Entity camera) {
        try {
            return (float) getBaseYaw.invoke(camera);
        } catch (Exception exception) {
            return camera.getYRot();
        }
    }

    /** Total yaw traverse in degrees (>= 360 means unlimited). */
    public static float getYawLimit(Entity camera) {
        try {
            Object limits = cameraEntityClass.getMethod("getRotationLimit").invoke(camera);
            return limits != null ? (float) getYawLimit.invoke(limits) : 0.0f;
        } catch (Exception exception) {
            return 0.0f;
        }
    }

    /** Tallyho's internal firing cooldown (0 = ready). */
    public static boolean isReadyToFire(Entity camera) {
        if (!isHullMG(camera)) {
            return false;
        }
        try {
            return firingCooldown.getInt(camera) <= 0;
        } catch (Exception exception) {
            return false;
        }
    }

    public static boolean hasAmmo(Entity camera) {
        if (!isHullMG(camera)) {
            return false;
        }
        try {
            return belt.getInt(camera) > 0;
        } catch (Exception exception) {
            return true;
        }
    }

    /**
     * Steps the camera's rotation toward {@code worldTarget} at a capped traverse
     * speed, with optional rotation-space deviation (degrees) applied to the target
     * direction. Ship-space transform and turret angle limits are handled by
     * tallyho's own {@code getLimitedRotationFromTarget}/{@code turnView}.
     *
     * @return the remaining angular error in degrees after stepping.
     */
    public static float aimTowards(Entity camera, Vec3 worldTarget, float maxDegPerTick,
                                   float yawOffsetDeg, float pitchOffsetDeg) {
        if (!available || !cameraEntityClass.isInstance(camera)) {
            return Float.MAX_VALUE;
        }
        try {
            Vec2 desired = (Vec2) limitedRotationFromTarget.invoke(camera, worldTarget, 1.0F);
            float desiredPitch = Mth.clamp(desired.x + pitchOffsetDeg, -89.0F, 89.0F);
            float desiredYaw = Mth.wrapDegrees(desired.y + yawOffsetDeg);

            float yawError = Mth.wrapDegrees(desiredYaw - camera.getYRot());
            float pitchError = desiredPitch - camera.getXRot();
            float error = (float) Math.sqrt(yawError * yawError + pitchError * pitchError);

            float step = Mth.clamp(error, 0.0F, maxDegPerTick);
            if (step > 1.0e-4F) {
                float scale = error > 1.0e-6F ? step / error : 0.0F;
                turnView.invoke(camera, (double) (yawError * scale), (double) (pitchError * scale));
            }
            return error;
        } catch (Exception exception) {
            logFailure(exception);
            return Float.MAX_VALUE;
        }
    }

    /** Fires the hull MG (no-op for non-turret cameras; belt/cooldown handled by tallyho). */
    public static void fire(Entity camera, LivingEntity shooter) {
        if (!available || !iTurretClass.isInstance(camera)) {
            return;
        }
        try {
            handleShoot.invoke(camera, shooter);
        } catch (Exception exception) {
            logFailure(exception);
        }
    }

    /**
     * True when {@code aimTarget} lies outside the camera's turret arc. tallyho's
     * limited rotation CLAMPS out-of-arc targets to the arc edge, so an aim error of
     * zero at the edge does not mean the target is reachable — compare the unlimited
     * rotation against the limited one instead. {@code aimTarget} must be the same
     * point handed to {@link #aimTowards} (the station pseudo-target).
     */
    public static boolean isTargetOutsideLimits(Entity camera, Vec3 aimTarget) {
        if (!available || !cameraEntityClass.isInstance(camera)) {
            return false;
        }
        Vec3 direction = aimTarget.subtract(camera.position());
        if (direction.lengthSqr() < 1.0e-8) {
            return false;
        }
        try {
            Vec2 raw = (Vec2) rotationFromDirection.invoke(camera, direction);
            Vec2 limited = (Vec2) limitedRotationFromDirection.invoke(camera, direction);
            final float epsilonDegrees = 0.5F;
            float yawDelta = Math.abs(Mth.wrapDegrees(raw.y - limited.y));
            float pitchDelta = Math.abs(raw.x - limited.x);
            return yawDelta > epsilonDegrees || pitchDelta > epsilonDegrees;
        } catch (Exception exception) {
            logFailure(exception);
            return false;
        }
    }
}

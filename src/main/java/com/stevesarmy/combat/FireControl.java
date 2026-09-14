package com.stevesarmy.combat;

import com.stevesarmy.squad.FireDiscipline;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Fire-control doctrine shared by the infantry combat goal and the vehicle-crew
 * station gunner: burst weapon profiles, the dynamic shot-threshold rule, and
 * the last-known-position suppression spread model. The station gunner runs the
 * same pacing and beaten-zone math as an infantry machine gunner — only the
 * muzzle differs (TaCZ gun vs tallyho turret), so everything the mod itself
 * decides lives here once.
 */
public final class FireControl {
    private FireControl() {}

    // --- Burst weapon profiles -----------------------------------------------

    /** Direct-fire burst pacing by weapon class. */
    public enum DirectFireWeaponProfile {
        SINGLE_SHOT(1, 0),
        AUTO_RIFLE(4, 8),
        SMG(4, 7),
        MACHINE_GUN(5, 10);

        public final int burstShots;
        public final int recoveryTicks;

        DirectFireWeaponProfile(int burstShots, int recoveryTicks) {
            this.burstShots = burstShots;
            this.recoveryTicks = recoveryTicks;
        }
    }

    /** Last-known-position suppression burst pacing by weapon class. */
    public enum SuppressionWeaponProfile {
        BOLT(2, 12),
        RIFLE(4, 10),
        AUTO_RIFLE(6, 7),
        SMG(5, 7),
        MACHINE_GUN(12, 4);

        public final int burstShots;
        public final int pauseTicks;

        SuppressionWeaponProfile(int burstShots, int pauseTicks) {
            this.burstShots = burstShots;
            this.pauseTicks = pauseTicks;
        }
    }

    // --- Dynamic burst pacing ----------------------------------------------------

    /**
     * Roll this burst's shot count from the weapon's base, scaled by bias.
     * The bias carries the per-soldier personality; the ±0.75 rounding window
     * adds burst-to-burst variety on top, so no two bursts are identical.
     */
    public static int rollBurstShots(int baseShots, float bias, RandomSource random) {
        double scaled = baseShots * Mth.clamp(bias, 0.5f, 2.0f);
        int min = (int) Math.max(1, Math.floor(scaled - 0.75));
        int max = (int) Math.max(min, Math.ceil(scaled + 0.75));
        return min + random.nextInt(max - min + 1);
    }

    /** Roll a burst recovery/gap from its base, same variance model as {@link #rollBurstShots}. */
    public static int rollRecoveryTicks(int baseTicks, float bias, RandomSource random) {
        double scaled = Math.max(0, baseTicks) * Mth.clamp(bias, 0.5f, 2.0f);
        int min = (int) Math.max(0, Math.floor(scaled - 1.5));
        int max = (int) Math.max(min, Math.ceil(scaled + 1.5));
        return min + random.nextInt(max - min + 1);
    }

    // --- Suppression pacing ----------------------------------------------------

    public static final int SUPPRESSION_PLAN_MAX_TICKS = 200;
    public static final int SUPPRESSION_COOLDOWN_TICKS = 100;
    public static final int SUPPRESSION_CONTACT_FOCUSED_TICKS = 50;
    public static final int SUPPRESSION_CONTACT_MAX_TICKS = 120;

    private static final double SUPPRESSION_SPREAD_MIN_RADIUS = 0.12;
    private static final double SUPPRESSION_SPREAD_PER_BLOCK = 0.0075;
    private static final double SUPPRESSION_SPREAD_MAX_RADIUS = 0.85;
    private static final double SUPPRESSION_VERTICAL_SPREAD_RATIO = 0.45;

    // --- Shot threshold ----------------------------------------------------------

    /**
     * Continuing a direct-fire burst is easier than starting one: the lower
     * continuation floor lets recoil degrade later shots naturally instead of
     * cutting the burst the moment quality dips.
     */
    public static float directBurstContinuationThreshold(FireDiscipline discipline, float startThreshold) {
        float scale = switch (discipline) {
            case CONSERVE -> 0.70f;
            case SUPPRESSIVE -> 0.40f;
            default -> 0.55f;
        };
        return Math.max(0.08f, startThreshold * scale);
    }

    // --- Suppression spread ------------------------------------------------------

    /**
     * A sample inside the beaten zone around {@code targetPos}. Spread grows with
     * distance plus the weapon's own inaccuracy term ({@code gunSpreadMeters}, the
     * caller converts its inaccuracy angle to meters), and scatters perpendicular
     * to the firing direction from {@code originEye}.
     */
    public static Vec3 calculateSuppressionSpread(Vec3 originPos, Vec3 originEye, RandomSource random,
                                                  Vec3 targetPos, double gunSpreadMeters) {
        double distance = originPos.distanceTo(targetPos);
        double spreadRadius = Mth.clamp(
            SUPPRESSION_SPREAD_MIN_RADIUS + distance * SUPPRESSION_SPREAD_PER_BLOCK + gunSpreadMeters,
            SUPPRESSION_SPREAD_MIN_RADIUS,
            SUPPRESSION_SPREAD_MAX_RADIUS
        );

        // Spread perpendicular to the firing direction so shots form a small, believable
        // beaten zone around the selected last-known position or cover opening.
        Vec3 toTarget = targetPos.subtract(originEye);
        Vec3 horizontalDirection = new Vec3(toTarget.x, 0.0, toTarget.z).normalize();
        Vec3 lateralDirection = new Vec3(-horizontalDirection.z, 0.0, horizontalDirection.x);
        double lateralOffset = (random.nextDouble() - 0.5) * 2.0 * spreadRadius;
        double depthOffset = (random.nextDouble() - 0.5) * spreadRadius * 0.35;
        double verticalOffset = (random.nextDouble() - 0.5)
            * 2.0 * spreadRadius * SUPPRESSION_VERTICAL_SPREAD_RATIO;

        // Callers provide a complete world-space target. Do not add a generic
        // vertical offset here: half-cover opening targets already include the
        // cover top, while other suppression targets set their own height.
        return targetPos.add(lateralDirection.scale(lateralOffset))
            .add(horizontalDirection.scale(depthOffset))
            .add(0.0, verticalOffset, 0.0);
    }

    /**
     * Suppression spread for a stale contact: the same beaten zone, widened into a
     * lateral lane as the contact ages past {@link #SUPPRESSION_CONTACT_FOCUSED_TICKS}
     * so the gun sweeps a likely movement corridor instead of drilling one spot.
     */
    public static Vec3 calculateLastSeenSuppressionSpread(Vec3 originPos, Vec3 originEye, RandomSource random,
                                                          Vec3 targetPos, double gunSpreadMeters, long contactAge) {
        Vec3 spreadTarget = calculateSuppressionSpread(originPos, originEye, random, targetPos, gunSpreadMeters);
        if (contactAge <= SUPPRESSION_CONTACT_FOCUSED_TICKS) {
            return spreadTarget;
        }

        double ageFraction = Mth.clamp(
            (contactAge - SUPPRESSION_CONTACT_FOCUSED_TICKS)
                / (double) (SUPPRESSION_CONTACT_MAX_TICKS - SUPPRESSION_CONTACT_FOCUSED_TICKS),
            0.0, 1.0);
        Vec3 toTarget = targetPos.subtract(originEye);
        Vec3 lateral = new Vec3(-toTarget.z, 0.0, toTarget.x).normalize();
        double laneHalfWidth = 0.35 + ageFraction * 1.15;
        double lateralOffset = (random.nextDouble() - 0.5) * 2.0 * laneHalfWidth;
        return spreadTarget.add(lateral.scale(lateralOffset));
    }
}

package com.stevesarmy.combat;

/**
 * Why the direct-fire pipeline did not produce a shot this tick. The fire
 * chain in {@code SoldierCombatGoal.tickGunCombat} has roughly a dozen silent
 * exits (reload, draw, aim-point miss, visibility, ADS ramp, aim quality,
 * burst cooldown, friendly-fire cone, ...); before this enum a stuck soldier
 * was indistinguishable from a soldier legitimately waiting on one of them.
 * Every exit now tags itself so sim detectors can separate "firing normally"
 * from "visible target + functional weapon + still no shot for over N ticks".
 */
public enum EngagementGateReason {
    /** Shot went out (or nothing blocked it this tick). */
    NONE("none"),
    /** Magazine reload in progress. */
    RELOADING("reloading"),
    /** Weapon still being drawn. */
    DRAWING("drawing"),
    /** Bolt cycle between shots. */
    BOLTING("bolting"),
    /** No aim point could be computed for the target. */
    NO_AIM_POINT("no_aim"),
    /** Aim point exists but is not visible from the muzzle. */
    NOT_VISIBLE("not_visible"),
    /** Visible but the bullet lane is blocked (fresh or cached probe). */
    BULLET_PATH_BLOCKED("path_blocked"),
    /** Pose/ADS preparation refused (half-cover rise, vehicle solution lost). */
    PREPARE_FAILED("prepare"),
    /** Still ramping aim-down-sights toward the required progress. */
    ADS_LOW("ads_low"),
    /** Aim quality below the shot/continuation threshold. */
    AIM_QUALITY("aim_quality"),
    /** Burst recovery cooldown between bursts. */
    BURST_COOLDOWN("burst_cd"),
    /** Friendly-fire cone refused the lane. */
    FF_BLOCK("ff_block"),
    /** Weapon deliberately held (AT gun facing infantry, no vehicle solution). */
    WEAPON_HOLD("weapon_hold");

    private final String wire;

    EngagementGateReason(String wire) {
        this.wire = wire;
    }

    /** Compact name used in sim JSON and detector output. */
    public String wire() {
        return wire;
    }
}

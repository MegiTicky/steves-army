package com.stevesarmy.sim;

import com.stevesarmy.combat.CombatStateSnapshot;

/**
 * One telemetry frame for one soldier at one scenario tick: the shared
 * {@link CombatStateSnapshot} plus entity-level extras the detectors need
 * (health, LoS, attack-target presence).
 */
public final class SimSample {
    public final int tick;
    public final String label;
    public final CombatStateSnapshot state;
    public final float health;
    public final float maxHealth;
    public final boolean hasAttackTarget;
    /** Clear line of sight to the nearest visible enemy eye; null when no enemy or untested. */
    public final Boolean losToEnemy;
    /** Distance to nearest enemy; -1 when none. */
    public final float enemyDistance;
    /** Reservation count backing the claimed cover; -1 when no cover claimed. */
    public final int reservationCountForClaim;
    /** Soldier holds a usable gun — gates firing-chain detectors on armed runs. */
    public final boolean hasGun;
    /** Rounds this soldier fired since scenario start (from the GunIntegration shot tap). */
    public final int shotsFired;
    /** Rounds left in the magazine; -1 when no gun reports one. */
    public final int ammo;
    /** Magazine capacity; -1 when no gun reports one. */
    public final int magazineSize;
    /** Horizontal distance from the soldier to its claimed cover slot center; -1 when no cover claimed. */
    public final double coverSlotOffset;
    /** Cover-anchor velocity pulls since spawn (CoverPositionController telemetry); -1 when unavailable. */
    public final int anchorPulls;
    /** Body sample points visible to the nearest enemy's eye (CoverFidelityProbe); -1 when not probed. */
    public final int exposedBodyRays;
    /** Head-band body points visible to the threat; a hiding soldier with head rays has a pose failure. */
    public final int exposedHeadRays;
    /** Total body points probed; 0 when not probed. */
    public final int probedBodyRays;
    /** Soldier is mid reload (facade) or has a pending reload flag. */
    public final boolean reloading;
    /** Rendered pose name: STANDING, CROUCHING, SWIMMING (prone/low crouch), ... */
    public final String posture;
    /** Half-cover rise animation in progress (crouch-to-stand blend). */
    public final boolean rising;
    /** Synced cover height the pose gate reads (CoverPoint cover height); -1 when unavailable. */
    public final float syncedCoverHeight;
    /** Danger-map score (0..100) at the soldier's position; -1 when no danger source is wired. */
    public final double dangerAtSoldier;
    /** Leg-height exposure bit behind {@link #dangerAtSoldier} (false when no data or a stub sampler). */
    public final boolean dangerLegExposed;
    /** Head yaw in degrees at sampling time (aim orientation). */
    public final float headYaw;
    /** ClearingCoordinator stage ordinal for this soldier's order; -1 when no clearing order. */
    public final int clearingStage;
    /** Squad-level count of checked check-targets at sample time; -1 when no clearing order. */
    public final int clearingChecked;
    /** Completion armed (every obligation resolved, order not yet complete); false when no clearing order. */
    public final boolean clearingAssembleArmed;
    /** True while executing a coordinator-driven advance (investigate walk or SECURE assemble). */
    public final boolean clearingAdvancing;

    public SimSample(int tick, String label, CombatStateSnapshot state,
                     float health, float maxHealth, boolean hasAttackTarget,
                     Boolean losToEnemy, float enemyDistance, int reservationCountForClaim,
                     boolean hasGun, int shotsFired, int ammo, int magazineSize,
                     double coverSlotOffset, int anchorPulls,
                     int exposedBodyRays, int exposedHeadRays, int probedBodyRays, boolean reloading,
                     String posture, boolean rising, float syncedCoverHeight,
                     double dangerAtSoldier, boolean dangerLegExposed, float headYaw,
                     int clearingStage, int clearingChecked, boolean clearingAssembleArmed,
                     boolean clearingAdvancing) {
        this.tick = tick;
        this.label = label;
        this.state = state;
        this.health = health;
        this.maxHealth = maxHealth;
        this.hasAttackTarget = hasAttackTarget;
        this.losToEnemy = losToEnemy;
        this.enemyDistance = enemyDistance;
        this.reservationCountForClaim = reservationCountForClaim;
        this.hasGun = hasGun;
        this.shotsFired = shotsFired;
        this.ammo = ammo;
        this.magazineSize = magazineSize;
        this.coverSlotOffset = coverSlotOffset;
        this.anchorPulls = anchorPulls;
        this.exposedBodyRays = exposedBodyRays;
        this.exposedHeadRays = exposedHeadRays;
        this.probedBodyRays = probedBodyRays;
        this.reloading = reloading;
        this.posture = posture;
        this.rising = rising;
        this.syncedCoverHeight = syncedCoverHeight;
        this.dangerAtSoldier = dangerAtSoldier;
        this.dangerLegExposed = dangerLegExposed;
        this.headYaw = headYaw;
        this.clearingStage = clearingStage;
        this.clearingChecked = clearingChecked;
        this.clearingAssembleArmed = clearingAssembleArmed;
        this.clearingAdvancing = clearingAdvancing;
    }
}

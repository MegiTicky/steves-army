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

    public SimSample(int tick, String label, CombatStateSnapshot state,
                     float health, float maxHealth, boolean hasAttackTarget,
                     Boolean losToEnemy, float enemyDistance, int reservationCountForClaim,
                     boolean hasGun, int shotsFired, int ammo, int magazineSize) {
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
    }
}

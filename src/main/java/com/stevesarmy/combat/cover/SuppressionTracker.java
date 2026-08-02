package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.debug.DiagnosticLogManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SuppressionTracker {
    public enum SuppressionState {
        CLEAR,
        PRESSURED,
        PINNED
    }

    private float suppressionLevel = 0.0f;
    private float peakSuppression = 0.0f;
    private long lastSuppressionTime = 0;
    private long suppressionEventSequence = 0;
    private int nearMissCount = 0;
    private long lastBurstTime = 0;
    private int burstCount = 0;

    // Explosion burst damping
    private long lastExplosionTime = 0;
    private int explosionBurstCount = 0;

    private int tickCounter = 0;
    private boolean wasSuppressed = false;
    private boolean wasPinned = false;

    private static final float DECAY_RATE = 0.15f;
    private static final float NEAR_MISS_THRESHOLD = 3.0f;
    private static final float NEAR_MISS_SUPPRESSION = 0.25f;
    private static final float DIRECT_FIRE_SUPPRESSION = 0.3f;
    private static final float PRESSURED_THRESHOLD = 0.5f;
    private static final float PINNED_THRESHOLD = 0.9f;
    private static final long MIN_PEEK_TIME_MS = 1000;
    private static final long MIN_RECOVERY_TIME_MS = 2500;
    private static final float MAX_SUPPRESSION = 1.0f;
    private static final float BASE_SPEED = 1.0f;
    private static final float MAX_SPEED_MULTIPLIER = 3.0f;
    private static final float MIN_SPEED_MULTIPLIER = 0.5f;
    private static final long BURST_WINDOW_MS = 150;
    private static final float MACHINE_GUN_SUPPRESSION_MULTIPLIER = 1.6f;

    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier) {
        onNearMiss(bulletPath, soldier, 1.0f);
    }

    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier, float bulletSpeed) {
        onNearMiss(bulletPath, soldier, bulletSpeed, null);
    }

    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier, float bulletSpeed, LivingEntity shooter) {
        double distance = soldier.position().distanceTo(bulletPath);
        if (distance >= NEAR_MISS_THRESHOLD) return;

        float distanceFactor = (float)(NEAR_MISS_THRESHOLD - distance) / (float)NEAR_MISS_THRESHOLD;
        float speedMultiplier = Mth.clamp(bulletSpeed / BASE_SPEED, MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER);

        long now = System.currentTimeMillis();
        if (now - lastBurstTime < BURST_WINDOW_MS) {
            burstCount++;
        } else {
            burstCount = 1;
        }
        lastBurstTime = now;
        float burstMultiplier = 1.0f - (Math.min(burstCount - 1, 3)) * 0.15f;

        float weaponMultiplier = shooter != null && GunIntegration.isMachineGun(shooter)
            ? MACHINE_GUN_SUPPRESSION_MULTIPLIER : 1.0f;
        float add = distanceFactor * NEAR_MISS_SUPPRESSION * speedMultiplier * burstMultiplier * weaponMultiplier;
        recordSuppressionEvent(add, now);
        nearMissCount++;

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] Soldier {} near miss: dist=" + String.format("%.1f", distance) + ", speedMult=" + String.format("%.2f", speedMultiplier) + ", burstMult=" + String.format("%.2f", burstMultiplier) + ", weaponMult=" + String.format("%.2f", weaponMultiplier) + ", +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel),
                soldier.getId());
        }
    }

    /**
     * CBC near-miss. A large-caliber round passing within 3 blocks is
     * an extreme event — always drives suppression to MAX_SUPPRESSION (1.0).
     * No distance scaling, no burst damping, no speed multiplier.
     */
    public void onCbcNearMiss(LivingEntity soldier) {
        recordSuppressionEvent(MAX_SUPPRESSION, System.currentTimeMillis());
        nearMissCount++;

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] Soldier {} CBC near miss: suppression set to 1.0",
                soldier.getId());
        }
    }

    public void onIncomingFire(LivingEntity shooter) {
        onIncomingFire(shooter, 1.0f);
    }

    public void onIncomingFire(LivingEntity shooter, float bulletSpeed) {
        float speedMultiplier = Mth.clamp(bulletSpeed / BASE_SPEED, MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER);
        float weaponMultiplier = GunIntegration.isMachineGun(shooter) ? MACHINE_GUN_SUPPRESSION_MULTIPLIER : 1.0f;
        float add = DIRECT_FIRE_SUPPRESSION * speedMultiplier * weaponMultiplier;
        recordSuppressionEvent(add, System.currentTimeMillis());

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] incoming fire from {}: speedMult=" + String.format("%.2f", speedMultiplier) + ", weaponMult=" + String.format("%.2f", weaponMultiplier) + ", +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel),
                shooter.getName().getString());
        }
    }

    public void onTakeDamage() {
        float add = 0.5f;
        recordSuppressionEvent(add, System.currentTimeMillis());

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] took damage: +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel));
        }
    }

    public void onExplosion(Vec3 explosionPosition, Vec3 soldierPosition, float exposure) {
        float radius = com.stevesarmy.StevesArmyConfig.getExplosionSuppressionRadius();
        float strength = com.stevesarmy.StevesArmyConfig.getExplosionSuppressionStrength();
        float shelterFloor = com.stevesarmy.StevesArmyConfig.getExplosionShelterFloor();
        int burstWindowMs = com.stevesarmy.StevesArmyConfig.getExplosionBurstWindowMs();
        float burstMultiplier = com.stevesarmy.StevesArmyConfig.getExplosionBurstMultiplier();

        double distance = soldierPosition.distanceTo(explosionPosition);
        if (distance > radius) {
            if (debugLog()) {
                StevesArmyMod.LOGGER.info("[ExplosionSuppression] ignored: distance={} exceeds radius={}",
                    String.format("%.2f", distance), String.format("%.2f", radius));
            }
            return;
        }

        float distanceFactor = (float)(1.0 - distance / radius);

        float effectiveExposure = shelterFloor + exposure * (1.0f - shelterFloor);

        float add = strength * distanceFactor * effectiveExposure;

        // Explosion burst damping
        long now = System.currentTimeMillis();
        float burstFactor = 1.0f;
        if (now - lastExplosionTime < burstWindowMs) {
            explosionBurstCount++;
            if (explosionBurstCount > 1) {
                burstFactor = burstMultiplier;
            }
        } else {
            explosionBurstCount = 1;
        }
        lastExplosionTime = now;
        add *= burstFactor;

        float previousLevel = suppressionLevel;
        recordSuppressionEvent(add, now);

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] explosion: dist=" + String.format("%.1f", distance)
                + ", exposure=" + String.format("%.2f", exposure)
                + ", effExposure=" + String.format("%.2f", effectiveExposure)
                + ", distFactor=" + String.format("%.2f", distanceFactor)
                + ", burstFactor=" + String.format("%.2f", burstFactor)
                + ", +" + String.format("%.2f", add) + " sup " + String.format("%.2f", previousLevel)
                + " -> " + String.format("%.2f", suppressionLevel)
                + ", state=" + getState());
        }
    }

    public void tick(boolean inCover) {
        float oldLevel = suppressionLevel;
        float decayMultiplier = inCover ? 2.0f : 1.0f;
        float decayAmount = DECAY_RATE * decayMultiplier * 0.05f;

        // Higher peak suppression means slower decay — peak of 1.0 reduces decay to ~50%
        float peakSlowdown = 0.5f + (1.0f - peakSuppression) * 0.5f;

        suppressionLevel = Math.max(0.0f, suppressionLevel - decayAmount * peakSlowdown);

        if (suppressionLevel < 0.1f) {
            nearMissCount = 0;
        }

        if (debugLog()) {
            tickCounter++;
            boolean nowSuppressed = isSuppressed();
            boolean nowPinned = isPinned();
            // Log on state transitions or every 20 ticks
            if (nowSuppressed != wasSuppressed || nowPinned != wasPinned || tickCounter >= 20) {
                tickCounter = 0;
                wasSuppressed = nowSuppressed;
                wasPinned = nowPinned;
                StevesArmyMod.LOGGER.info("[Suppression] Soldier tick: inCover={}, decay=" + String.format("%.4f", decayAmount) + ", peakSlow=" + String.format("%.2f", peakSlowdown) + ", sup " + String.format("%.2f", oldLevel) + " -> " + String.format("%.2f", suppressionLevel) + ", state={}",
                    inCover, getState());
            }
        }
    }

    public void reset() {
        if (suppressionLevel > 0.01f && debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] Soldier reset: " + String.format("%.2f", suppressionLevel) + " -> 0.0");
        }
        suppressionLevel = 0.0f;
        peakSuppression = 0.0f;
        lastSuppressionTime = 0;
        nearMissCount = 0;
        burstCount = 0;
        explosionBurstCount = 0;
    }

    public SuppressionState getState() {
        if (suppressionLevel >= PINNED_THRESHOLD) return SuppressionState.PINNED;
        if (suppressionLevel >= PRESSURED_THRESHOLD) return SuppressionState.PRESSURED;
        return SuppressionState.CLEAR;
    }

    public boolean isSuppressed() {
        return getState() != SuppressionState.CLEAR;
    }

    public boolean isPinned() {
        return getState() == SuppressionState.PINNED;
    }

    public float getSuppressionLevel() {
        return suppressionLevel;
    }

    public long getSuppressionEventSequence() {
        return suppressionEventSequence;
    }

    public float getAccuracyModifier() {
        return 1.0f - (suppressionLevel * 0.9f);
    }

    public boolean canPeek() {
        if (isPinned()) {
            return false;
        }
        long timeSinceLastSuppression = System.currentTimeMillis() - lastSuppressionTime;
        return timeSinceLastSuppression > MIN_PEEK_TIME_MS;
    }

    public boolean isRecovered() {
        if (isSuppressed()) return false;
        long timeSinceLastSuppression = System.currentTimeMillis() - lastSuppressionTime;
        return timeSinceLastSuppression > MIN_RECOVERY_TIME_MS;
    }

    public long getTimeSinceLastSuppression() {
        return System.currentTimeMillis() - lastSuppressionTime;
    }

    public int getNearMissCount() {
        return nearMissCount;
    }

    public boolean wasRecentlySuppressed() {
        return getTimeSinceLastSuppression() < 5000;
    }

    private boolean debugLog() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }

    private void recordSuppressionEvent(float amount, long now) {
        if (amount <= 0.0f) return;
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + amount);
        if (suppressionLevel > peakSuppression) peakSuppression = suppressionLevel;
        lastSuppressionTime = now;
        suppressionEventSequence++;
    }
}

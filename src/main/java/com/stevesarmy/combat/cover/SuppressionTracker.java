package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SuppressionTracker {
    private float suppressionLevel = 0.0f;
    private float peakSuppression = 0.0f;
    private long lastSuppressionTime = 0;
    private int nearMissCount = 0;
    private long lastBurstTime = 0;
    private int burstCount = 0;

    private static final float DECAY_RATE = 0.15f;
    private static final float NEAR_MISS_THRESHOLD = 3.0f;
    private static final float NEAR_MISS_SUPPRESSION = 0.25f;
    private static final float DIRECT_FIRE_SUPPRESSION = 0.3f;
    private static final float SUPPRESSED_THRESHOLD = 0.5f;
    private static final long MIN_PEEK_TIME_MS = 2500;
    private static final float MAX_SUPPRESSION = 1.0f;
    private static final float BASE_SPEED = 1.0f;
    private static final float MAX_SPEED_MULTIPLIER = 3.0f;
    private static final float MIN_SPEED_MULTIPLIER = 0.5f;
    private static final long BURST_WINDOW_MS = 150;

    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier) {
        onNearMiss(bulletPath, soldier, 1.0f);
    }

    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier, float bulletSpeed) {
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

        float add = distanceFactor * NEAR_MISS_SUPPRESSION * speedMultiplier * burstMultiplier;
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + add);
        if (suppressionLevel > peakSuppression) peakSuppression = suppressionLevel;
        lastSuppressionTime = now;
        nearMissCount++;

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] Soldier {} near miss: dist=" + String.format("%.1f", distance) + ", speedMult=" + String.format("%.2f", speedMultiplier) + ", burstMult=" + String.format("%.2f", burstMultiplier) + ", +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel),
                soldier.getId());
        }
    }

    public void onIncomingFire(LivingEntity shooter) {
        onIncomingFire(shooter, 1.0f);
    }

    public void onIncomingFire(LivingEntity shooter, float bulletSpeed) {
        float speedMultiplier = Mth.clamp(bulletSpeed / BASE_SPEED, MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER);
        float add = DIRECT_FIRE_SUPPRESSION * speedMultiplier;
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + add);
        if (suppressionLevel > peakSuppression) peakSuppression = suppressionLevel;
        lastSuppressionTime = System.currentTimeMillis();

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] incoming fire from {}: speedMult=" + String.format("%.2f", speedMultiplier) + ", +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel),
                shooter.getName().getString());
        }
    }

    public void onTakeDamage() {
        float add = 0.5f;
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + add);
        if (suppressionLevel > peakSuppression) peakSuppression = suppressionLevel;
        lastSuppressionTime = System.currentTimeMillis();

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[Suppression] took damage: +" + String.format("%.2f", add) + " sup -> " + String.format("%.2f", suppressionLevel));
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
            StevesArmyMod.LOGGER.info("[Suppression] Soldier tick: inCover={}, decay=" + String.format("%.4f", decayAmount) + ", peakSlow=" + String.format("%.2f", peakSlowdown) + ", sup " + String.format("%.2f", oldLevel) + " -> " + String.format("%.2f", suppressionLevel) + ", suppressed={}",
                inCover, isSuppressed());
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
    }

    public boolean isSuppressed() {
        return suppressionLevel > SUPPRESSED_THRESHOLD;
    }

    public boolean isPinned() {
        return isSuppressed();
    }

    public float getSuppressionLevel() {
        return suppressionLevel;
    }

    public float getAccuracyModifier() {
        return 1.0f - (suppressionLevel * 0.9f);
    }

    public boolean canPeek() {
        if (isSuppressed()) {
            return false;
        }
        long timeSinceLastSuppression = System.currentTimeMillis() - lastSuppressionTime;
        return timeSinceLastSuppression > MIN_PEEK_TIME_MS;
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
        return com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled();
    }
}
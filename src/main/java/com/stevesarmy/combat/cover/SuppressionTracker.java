package com.stevesarmy.combat.cover;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SuppressionTracker {
    private float suppressionLevel = 0.0f;
    private long lastSuppressionTime = 0;
    private int nearMissCount = 0;
    
    private static final float DECAY_RATE = 0.15f;
    private static final float NEAR_MISS_THRESHOLD = 3.0f;
    private static final float NEAR_MISS_SUPPRESSION = 0.25f;
    private static final float DIRECT_FIRE_SUPPRESSION = 0.3f;
    private static final float SUPPRESSED_THRESHOLD = 0.5f;
    private static final float PINNED_THRESHOLD = 0.7f;
    private static final long MIN_PEEK_TIME_MS = 2500;
    private static final float MAX_SUPPRESSION = 1.0f;
    
    public void onNearMiss(Vec3 bulletPath, LivingEntity soldier) {
        double distance = soldier.position().distanceTo(bulletPath);
        if (distance < NEAR_MISS_THRESHOLD) {
            float add = (float)(NEAR_MISS_THRESHOLD - distance) * NEAR_MISS_SUPPRESSION;
            suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + add);
            lastSuppressionTime = System.currentTimeMillis();
            nearMissCount++;
        }
    }
    
    public void onIncomingFire(LivingEntity shooter) {
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + DIRECT_FIRE_SUPPRESSION);
        lastSuppressionTime = System.currentTimeMillis();
    }
    
    public void onTakeDamage() {
        suppressionLevel = Math.min(MAX_SUPPRESSION, suppressionLevel + 0.4f);
        lastSuppressionTime = System.currentTimeMillis();
    }
    
    public void tick(boolean inCover) {
        float decayMultiplier = inCover ? 2.0f : 1.0f;
        float decayAmount = DECAY_RATE * decayMultiplier * 0.05f;
        suppressionLevel = Math.max(0.0f, suppressionLevel - decayAmount);
        
        if (suppressionLevel < 0.1f) {
            nearMissCount = 0;
        }
    }
    
    public void reset() {
        suppressionLevel = 0.0f;
        lastSuppressionTime = 0;
        nearMissCount = 0;
    }
    
    public boolean isSuppressed() {
        return suppressionLevel > SUPPRESSED_THRESHOLD;
    }
    
    public boolean isPinned() {
        return suppressionLevel > PINNED_THRESHOLD;
    }
    
    public float getSuppressionLevel() {
        return suppressionLevel;
    }
    
    public float getAccuracyModifier() {
        return 1.0f - (suppressionLevel * 0.9f);
    }
    
    public boolean canPeek() {
        if (suppressionLevel > PINNED_THRESHOLD) {
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
}

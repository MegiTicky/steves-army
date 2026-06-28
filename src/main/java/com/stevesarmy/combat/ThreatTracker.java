package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

public class ThreatTracker {
    private final Map<UUID, ThreatInfo> knownThreats = new HashMap<>();
    private static final long THREAT_MEMORY_TICKS = 600;
    private static final long UPDATE_INTERVAL = 20;

    public void update(LivingEntity soldier) {
        long currentTime = soldier.level().getGameTime();
        knownThreats.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().lastSeenTime > THREAT_MEMORY_TICKS
        );
    }

    public void reportThreat(LivingEntity threat, BlockPos lastKnownPos, double accuracy) {
        UUID threatId = threat.getUUID();
        long currentTime = threat.level().getGameTime();
        
        ThreatInfo info = knownThreats.get(threatId);
        if (info == null) {
            info = new ThreatInfo(threatId, lastKnownPos, accuracy, currentTime);
            knownThreats.put(threatId, info);
        } else {
            info.update(lastKnownPos, accuracy, currentTime);
        }
    }

    public void reportThreatDirect(LivingEntity threat, LivingEntity reporter) {
        BlockPos estimatedPos = TargetAcquisition.getEstimatedPosition(threat, 1.0);
        reportThreat(threat, estimatedPos, 1.0);
    }

    public Optional<ThreatInfo> getHighestPriorityThreat(LivingEntity soldier, List<LivingEntity> visibleEnemies) {
        ThreatInfo best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        
        for (LivingEntity enemy : visibleEnemies) {
            ThreatInfo info = knownThreats.get(enemy.getUUID());
            if (info != null) {
                double score = calculateThreatScore(soldier, enemy, info);
                if (score > bestScore) {
                    bestScore = score;
                    best = info;
                }
            }
        }
        
        if (best == null && !visibleEnemies.isEmpty()) {
            LivingEntity closest = visibleEnemies.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)))
                .orElse(null);
            if (closest != null) {
                return Optional.of(new ThreatInfo(closest.getUUID(), closest.blockPosition(), 1.0, soldier.level().getGameTime()));
            }
        }
        
        return Optional.ofNullable(best);
    }

    private double calculateThreatScore(LivingEntity soldier, LivingEntity enemy, ThreatInfo info) {
        double distance = soldier.distanceToSqr(enemy);
        double distanceScore = -distance / 100.0;
        double accuracyScore = info.accuracy * 10.0;
        double recencyScore = 0;
        
        long timeSinceSeen = soldier.level().getGameTime() - info.lastSeenTime;
        if (timeSinceSeen < 100) {
            recencyScore = 10.0 - (timeSinceSeen / 10.0);
        }
        
        return distanceScore + accuracyScore + recencyScore;
    }

    public void clear() {
        knownThreats.clear();
    }

    public void removeThreat(UUID threatId) {
        knownThreats.remove(threatId);
    }

    public static class ThreatInfo {
        public final UUID threatId;
        public BlockPos lastKnownPosition;
        public double accuracy;
        public long lastSeenTime;

        public ThreatInfo(UUID threatId, BlockPos lastKnownPosition, double accuracy, long lastSeenTime) {
            this.threatId = threatId;
            this.lastKnownPosition = lastKnownPosition;
            this.accuracy = accuracy;
            this.lastSeenTime = lastSeenTime;
        }

        public void update(BlockPos newPos, double newAccuracy, long time) {
            this.lastKnownPosition = newPos;
            this.accuracy = Math.max(this.accuracy, newAccuracy);
            this.lastSeenTime = time;
        }
    }
}
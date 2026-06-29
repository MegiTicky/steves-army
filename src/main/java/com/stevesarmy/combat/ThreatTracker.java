package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;

public class ThreatTracker {
    
    private final Map<UUID, ThreatInfo> knownThreats = new HashMap<>();
    private static final long THREAT_MEMORY_TICKS = 600;
    
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
    
    public void reportThreatDirect(LivingEntity threat) {
        BlockPos pos = threat.blockPosition();
        reportThreat(threat, pos, 1.0);
    }
    
    public void reportThreatAtPosition(BlockPos position) {
        UUID dummyId = UUID.randomUUID();
        long currentTime = System.currentTimeMillis();
        ThreatInfo info = new ThreatInfo(dummyId, position, 0.5, currentTime);
        knownThreats.put(dummyId, info);
    }
    
    public Optional<ThreatInfo> getHighestPriorityThreat(LivingEntity soldier, List<LivingEntity> detectedEnemies) {
        if (detectedEnemies.isEmpty()) {
            return Optional.empty();
        }
        
        LivingEntity closest = detectedEnemies.stream()
            .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)))
            .orElse(null);
        
        if (closest != null) {
            reportThreatDirect(closest);
            return Optional.of(knownThreats.get(closest.getUUID()));
        }
        
        return Optional.empty();
    }
    
    public void clear() {
        knownThreats.clear();
    }
    
    public void removeThreat(UUID threatId) {
        knownThreats.remove(threatId);
    }
    
    public boolean hasThreat(UUID threatId) {
        return knownThreats.containsKey(threatId);
    }
    
    public Optional<ThreatInfo> getThreat(UUID threatId) {
        return Optional.ofNullable(knownThreats.get(threatId));
    }
    
    public Optional<BlockPos> getLastKnownPosition() {
        return knownThreats.values().stream()
            .max(Comparator.comparingLong(info -> info.lastSeenTime))
            .map(info -> info.lastKnownPosition);
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
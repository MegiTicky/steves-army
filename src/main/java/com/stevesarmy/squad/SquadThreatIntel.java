package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class SquadThreatIntel {

    private final Map<UUID, ThreatKnowledge> knownThreats = new HashMap<>();
    private static final long THREAT_MEMORY_TICKS = 600;
    private static final long STALE_TIMEOUT_TICKS = 60;

    public static class ThreatKnowledge {
        public final UUID threatEntityId;
        public BlockPos lastKnownPosition;
        public long lastSeenTime;
        public UUID lastSeenBySoldier;
        public float accuracy;
        public boolean isAlive;
        public boolean isSuppressed;
        public UUID suppressedBy;

        public ThreatKnowledge(UUID threatEntityId) {
            this.threatEntityId = threatEntityId;
            this.isAlive = true;
            this.isSuppressed = false;
            this.accuracy = 0.0f;
        }

        public CompoundTag toNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("ThreatEntityId", threatEntityId);
            if (lastKnownPosition != null) {
                tag.putInt("PosX", lastKnownPosition.getX());
                tag.putInt("PosY", lastKnownPosition.getY());
                tag.putInt("PosZ", lastKnownPosition.getZ());
            }
            tag.putLong("LastSeenTime", lastSeenTime);
            if (lastSeenBySoldier != null) {
                tag.putUUID("LastSeenBy", lastSeenBySoldier);
            }
            tag.putFloat("Accuracy", accuracy);
            tag.putBoolean("IsAlive", isAlive);
            tag.putBoolean("IsSuppressed", isSuppressed);
            if (suppressedBy != null) {
                tag.putUUID("SuppressedBy", suppressedBy);
            }
            return tag;
        }

        public static ThreatKnowledge fromNBT(CompoundTag tag) {
            ThreatKnowledge knowledge = new ThreatKnowledge(tag.getUUID("ThreatEntityId"));
            if (tag.contains("PosX")) {
                knowledge.lastKnownPosition = new BlockPos(
                    tag.getInt("PosX"),
                    tag.getInt("PosY"),
                    tag.getInt("PosZ")
                );
            }
            knowledge.lastSeenTime = tag.getLong("LastSeenTime");
            if (tag.contains("LastSeenBy")) {
                knowledge.lastSeenBySoldier = tag.getUUID("LastSeenBy");
            }
            knowledge.accuracy = tag.getFloat("Accuracy");
            knowledge.isAlive = tag.getBoolean("IsAlive");
            knowledge.isSuppressed = tag.getBoolean("IsSuppressed");
            if (tag.contains("SuppressedBy")) {
                knowledge.suppressedBy = tag.getUUID("SuppressedBy");
            }
            return knowledge;
        }
    }

    public void reportThreat(UUID reporterId, LivingEntity threat, BlockPos pos, float accuracy) {
        ThreatKnowledge knowledge = knownThreats.getOrDefault(threat.getUUID(), 
            new ThreatKnowledge(threat.getUUID()));
        
        knowledge.lastKnownPosition = pos;
        knowledge.lastSeenTime = threat.level().getGameTime();
        knowledge.lastSeenBySoldier = reporterId;
        knowledge.accuracy = Math.max(knowledge.accuracy, accuracy);
        knowledge.isAlive = true;
        
        knownThreats.put(threat.getUUID(), knowledge);
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat reported: {} at {} by {}, accuracy={}",
                threat.getName().getString(), pos, reporterId, String.format("%.2f", accuracy));
        }
    }

    public void reportThreatPosition(UUID reporterId, UUID threatId, BlockPos pos, float accuracy, Level level) {
        ThreatKnowledge knowledge = knownThreats.getOrDefault(threatId, new ThreatKnowledge(threatId));
        
        knowledge.lastKnownPosition = pos;
        knowledge.lastSeenTime = level.getGameTime();
        knowledge.lastSeenBySoldier = reporterId;
        knowledge.accuracy = Math.max(knowledge.accuracy, accuracy);
        knowledge.isAlive = true;
        
        knownThreats.put(threatId, knowledge);
    }

    public void markThreatDead(UUID threatId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null) {
            knowledge.isAlive = false;
            knowledge.isSuppressed = false;
            knowledge.suppressedBy = null;
            
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat marked dead: {}", threatId);
            }
        }
    }

    public void markThreatSuppressed(UUID threatId, UUID soldierId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null && knowledge.isAlive) {
            knowledge.isSuppressed = true;
            knowledge.suppressedBy = soldierId;
            
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat {} now suppressed by {}", threatId, soldierId);
            }
        }
    }

    public void clearThreatSuppression(UUID threatId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null) {
            knowledge.isSuppressed = false;
            knowledge.suppressedBy = null;
        }
    }

    public void clearSuppressionBySoldier(UUID soldierId) {
        for (ThreatKnowledge knowledge : knownThreats.values()) {
            if (soldierId.equals(knowledge.suppressedBy)) {
                knowledge.isSuppressed = false;
                knowledge.suppressedBy = null;
            }
        }
    }

    public Optional<ThreatKnowledge> getThreat(UUID threatId) {
        return Optional.ofNullable(knownThreats.get(threatId));
    }

    public boolean hasThreat(UUID threatId) {
        return knownThreats.containsKey(threatId);
    }

    public List<ThreatKnowledge> getAllThreats() {
        return new ArrayList<>(knownThreats.values());
    }

    public List<ThreatKnowledge> getUnsuppressedThreats() {
        return knownThreats.values().stream()
            .filter(t -> t.isAlive && !t.isSuppressed)
            .sorted(Comparator.comparingDouble(t -> -t.accuracy))
            .collect(Collectors.toList());
    }

    public Optional<ThreatKnowledge> getHighestAccuracyUnsuppressedThreat() {
        return knownThreats.values().stream()
            .filter(t -> t.isAlive && !t.isSuppressed)
            .max(Comparator.comparingDouble(t -> t.accuracy));
    }

    public Optional<ThreatKnowledge> getAssignedThreatForSoldier(UUID soldierId) {
        return knownThreats.values().stream()
            .filter(t -> t.isSuppressed && soldierId.equals(t.suppressedBy))
            .findFirst();
    }

    public void tickCleanup(long currentGameTime) {
        knownThreats.entrySet().removeIf(entry -> {
            ThreatKnowledge knowledge = entry.getValue();
            
            long timeSinceSeen = currentGameTime - knowledge.lastSeenTime;
            if (timeSinceSeen > THREAT_MEMORY_TICKS) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat {} removed (stale, {} ticks old)",
                        knowledge.threatEntityId, timeSinceSeen);
                }
                return true;
            }
            
            if (!knowledge.isAlive && timeSinceSeen > 100) {
                return true;
            }
            
            return false;
        });
    }

    public boolean isThreatStale(UUID threatId, long currentGameTime) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge == null) return true;
        
        long timeSinceSeen = currentGameTime - knowledge.lastSeenTime;
        return timeSinceSeen > STALE_TIMEOUT_TICKS;
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag threatsList = new ListTag();
        
        for (ThreatKnowledge knowledge : knownThreats.values()) {
            threatsList.add(knowledge.toNBT());
        }
        
        tag.put("Threats", threatsList);
        return tag;
    }

    public static SquadThreatIntel fromNBT(CompoundTag tag) {
        SquadThreatIntel intel = new SquadThreatIntel();
        
        if (tag.contains("Threats")) {
            ListTag threatsList = tag.getList("Threats", Tag.TAG_COMPOUND);
            for (int i = 0; i < threatsList.size(); i++) {
                CompoundTag threatTag = threatsList.getCompound(i);
                ThreatKnowledge knowledge = ThreatKnowledge.fromNBT(threatTag);
                intel.knownThreats.put(knowledge.threatEntityId, knowledge);
            }
        }
        
        return intel;
    }

    public void clear() {
        knownThreats.clear();
    }

    public int getThreatCount() {
        return knownThreats.size();
    }

    public int getAliveThreatCount() {
        return (int) knownThreats.values().stream()
            .filter(t -> t.isAlive)
            .count();
    }

    public int getSuppressedThreatCount() {
        return (int) knownThreats.values().stream()
            .filter(t -> t.isAlive && t.isSuppressed)
            .count();
    }
}

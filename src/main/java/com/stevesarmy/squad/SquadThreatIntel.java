package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class SquadThreatIntel {

    private final Map<UUID, ThreatKnowledge> knownThreats = new HashMap<>();
    private static final long THREAT_MEMORY_TICKS = 600;
    private static final long STALE_TIMEOUT_TICKS = 120;

    public static class ThreatKnowledge {
        public final UUID threatEntityId;
        public BlockPos lastKnownPosition;
        public long lastSeenTime;
        public UUID lastSeenBySoldier;
        public float accuracy;
        public boolean isAlive;
        public boolean isSuppressed;
        public UUID suppressedBy;
        public long lastSuppressionHeartbeat;
        public final Set<UUID> suppressors = new HashSet<>();
        public final Map<UUID, Long> suppressionHeartbeats = new HashMap<>();
        @Nullable public Vec3 lastVisibleAimPoint;
        @Nullable public Vec3 lastVisibleHeadPoint;

        public ThreatKnowledge(UUID threatEntityId) {
            this.threatEntityId = threatEntityId;
            this.isAlive = true;
            this.isSuppressed = false;
            this.accuracy = 0.0f;
            this.lastSuppressionHeartbeat = 0;
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
            tag.putLong("LastSuppressionHeartbeat", lastSuppressionHeartbeat);
            if (lastVisibleAimPoint != null) {
                tag.putDouble("AimX", lastVisibleAimPoint.x);
                tag.putDouble("AimY", lastVisibleAimPoint.y);
                tag.putDouble("AimZ", lastVisibleAimPoint.z);
            }
            if (lastVisibleHeadPoint != null) {
                tag.putDouble("HeadX", lastVisibleHeadPoint.x);
                tag.putDouble("HeadY", lastVisibleHeadPoint.y);
                tag.putDouble("HeadZ", lastVisibleHeadPoint.z);
            }
            ListTag suppressorList = new ListTag();
            for (UUID suppressor : suppressors) {
                CompoundTag suppressorTag = new CompoundTag();
                suppressorTag.putUUID("Id", suppressor);
                suppressorTag.putLong("Heartbeat", suppressionHeartbeats.getOrDefault(suppressor, 0L));
                suppressorList.add(suppressorTag);
            }
            tag.put("Suppressors", suppressorList);
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
            knowledge.lastSuppressionHeartbeat = tag.getLong("LastSuppressionHeartbeat");
            if (tag.contains("AimX")) {
                knowledge.lastVisibleAimPoint = new Vec3(
                    tag.getDouble("AimX"), tag.getDouble("AimY"), tag.getDouble("AimZ"));
            }
            if (tag.contains("HeadX")) {
                knowledge.lastVisibleHeadPoint = new Vec3(
                    tag.getDouble("HeadX"), tag.getDouble("HeadY"), tag.getDouble("HeadZ"));
            }
            ListTag suppressorList = tag.getList("Suppressors", Tag.TAG_COMPOUND);
            for (int i = 0; i < suppressorList.size(); i++) {
                CompoundTag suppressorTag = suppressorList.getCompound(i);
                UUID suppressor = suppressorTag.getUUID("Id");
                knowledge.suppressors.add(suppressor);
                knowledge.suppressionHeartbeats.put(suppressor, suppressorTag.getLong("Heartbeat"));
            }
            // Keep legacy single-owner saves as a single active assignment.
            if (knowledge.suppressors.isEmpty() && knowledge.suppressedBy != null) {
                knowledge.suppressors.add(knowledge.suppressedBy);
                knowledge.suppressionHeartbeats.put(knowledge.suppressedBy, knowledge.lastSuppressionHeartbeat);
            }
            return knowledge;
        }
    }

    public void reportThreat(UUID reporterId, LivingEntity threat, BlockPos pos, float accuracy) {
        reportThreat(reporterId, threat, pos, null, null, accuracy);
    }

    public void reportThreat(UUID reporterId, LivingEntity threat, BlockPos pos,
                             @Nullable Vec3 aimPoint, float accuracy) {
        reportThreat(reporterId, threat, pos, aimPoint, null, accuracy);
    }

    public void reportThreat(UUID reporterId, LivingEntity threat, BlockPos pos,
                             @Nullable Vec3 aimPoint, @Nullable Vec3 headPoint, float accuracy) {
        UUID threatId = threat.getUUID();
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null && !knowledge.isAlive) {
            return;
        }
        if (knowledge == null) {
            knowledge = new ThreatKnowledge(threatId);
        }
        
        knowledge.lastKnownPosition = pos;
        knowledge.lastSeenTime = threat.level().getGameTime();
        knowledge.lastSeenBySoldier = reporterId;
        if (aimPoint != null) {
            knowledge.lastVisibleAimPoint = aimPoint;
        }
        knowledge.lastVisibleHeadPoint = headPoint;
        knowledge.accuracy = Math.max(knowledge.accuracy, accuracy);
        knowledge.isAlive = true;
        
        knownThreats.put(threatId, knowledge);
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat reported: {} at {} by {}, accuracy={}",
                threat.getName().getString(), pos, reporterId, String.format("%.2f", accuracy));
        }
    }

    public void reportThreatPosition(UUID reporterId, UUID threatId, BlockPos pos, float accuracy, Level level) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null && !knowledge.isAlive) {
            return;
        }
        if (knowledge == null) {
            knowledge = new ThreatKnowledge(threatId);
        }
        
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
            knowledge.suppressors.clear();
            knowledge.suppressionHeartbeats.clear();
            
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
            knowledge.suppressors.add(soldierId);
            
            if (DiagnosticLogManager.isSuppressionLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[SquadThreatIntel] Threat {} now suppressed by {}", threatId, soldierId);
            }
        }
    }
    
    public synchronized boolean tryMarkThreatSuppressed(UUID threatId, UUID soldierId) {
        return tryClaimThreatSuppression(threatId, soldierId, 0L, 1);
    }

    public synchronized boolean tryClaimThreatSuppression(UUID threatId, UUID soldierId,
                                                           long currentGameTime, int maxSuppressors) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null && knowledge.isAlive
            && (knowledge.suppressors.contains(soldierId) || knowledge.suppressors.size() < maxSuppressors)) {
            knowledge.isSuppressed = true;
            knowledge.suppressors.add(soldierId);
            knowledge.suppressedBy = knowledge.suppressors.iterator().next();
            knowledge.suppressionHeartbeats.put(soldierId, currentGameTime);
            knowledge.lastSuppressionHeartbeat = currentGameTime;
            return true;
        }
        return false;
    }
    
    public void updateSuppressionHeartbeat(UUID threatId, long currentGameTime) {
        updateSuppressionHeartbeat(threatId, null, currentGameTime);
    }

    public void updateSuppressionHeartbeat(UUID threatId, @Nullable UUID soldierId, long currentGameTime) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null && knowledge.isSuppressed) {
            knowledge.lastSuppressionHeartbeat = currentGameTime;
            if (soldierId != null && knowledge.suppressors.contains(soldierId)) {
                knowledge.suppressionHeartbeats.put(soldierId, currentGameTime);
            }
        }
    }
    
    public boolean isSuppressionStale(UUID threatId, long currentGameTime) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge == null || !knowledge.isSuppressed) return false;
        
        long heartbeatTimeout = 10;
        return currentGameTime - knowledge.lastSuppressionHeartbeat > heartbeatTimeout;
    }

    public void clearThreatSuppression(UUID threatId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge != null) {
            knowledge.isSuppressed = false;
            knowledge.suppressedBy = null;
            knowledge.suppressors.clear();
            knowledge.suppressionHeartbeats.clear();
        }
    }

    public void releaseThreatSuppression(UUID threatId, UUID soldierId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        if (knowledge == null) return;

        knowledge.suppressors.remove(soldierId);
        knowledge.suppressionHeartbeats.remove(soldierId);
        knowledge.isSuppressed = !knowledge.suppressors.isEmpty();
        knowledge.suppressedBy = knowledge.isSuppressed ? knowledge.suppressors.iterator().next() : null;
    }

    public boolean hasSuppressionAssignment(UUID threatId, UUID soldierId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        return knowledge != null && knowledge.suppressors.contains(soldierId);
    }

    public int getSuppressionCount(UUID threatId) {
        ThreatKnowledge knowledge = knownThreats.get(threatId);
        return knowledge != null ? knowledge.suppressors.size() : 0;
    }

    public void clearSuppressionBySoldier(UUID soldierId) {
        for (ThreatKnowledge knowledge : knownThreats.values()) {
            releaseThreatSuppression(knowledge.threatEntityId, soldierId);
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
            .filter(t -> t.suppressors.contains(soldierId))
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

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ThreatAwareness {

    public enum ThreatSource {
        PING_DIRECTION,
        ENEMY_PING,
        ENTITY_DETECTED,
        PERIPHERAL,
        DEAD_ENTITY
    }

    private static class ThreatEntry {
        ThreatSource source;
        final UUID entityId;
        @Nullable LivingEntity entity;
        BlockPos position;
        float weight;
        long lastSeenTime;

        ThreatEntry(ThreatSource source, @Nullable LivingEntity entity, BlockPos position, float weight) {
            this.source = source;
            this.entityId = entity != null ? entity.getUUID() : UUID.randomUUID();
            this.entity = entity;
            this.position = position;
            this.weight = weight;
            this.lastSeenTime = System.currentTimeMillis();
        }
    }

    private static final float PING_DIRECTION_INITIAL_WEIGHT = 15.0f;
    private static final float ENEMY_PING_INITIAL_WEIGHT = 10.0f;
    private static final float ENTITY_DETECTED_INITIAL_WEIGHT = 3.0f;
    private static final float PERIPHERAL_INITIAL_WEIGHT = 1.0f;
    
    private static final float DEAD_ENTITY_WEIGHT_MULTIPLIER = 0.3f;
    private static final float DISTANCE_WEIGHT_FACTOR = 0.1f;
    private static final float WEIGHT_DECAY_PER_TICK = 0.995f;
    private static final float MIN_WEIGHT = 0.1f;
    private static final double FLANKING_ANGLE_THRESHOLD_DEG = 90.0;
    private static final float PERSISTENT_THREAT_BLEND_FACTOR = 0.3f;

    private final List<ThreatEntry> threats = new ArrayList<>();
    private Vec3 persistentThreatDirection = null;
    private Vec3 coverFacingDirection = null;
    private Vec3 soldierPosReference = null;

    public void setSoldierPosReference(Vec3 pos) {
        this.soldierPosReference = pos;
    }

    public void onPingDirection(BlockPos pos) {
        removeBySource(ThreatSource.PING_DIRECTION);
        threats.add(new ThreatEntry(ThreatSource.PING_DIRECTION, null, pos, PING_DIRECTION_INITIAL_WEIGHT));
        
        Vec3 threatPos = Vec3.atCenterOf(pos);
        updatePersistentThreatDirection(threatPos);
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Ping direction added: pos={}, weight={}", pos, PING_DIRECTION_INITIAL_WEIGHT);
        }
    }

    public void onEnemyPing(BlockPos pos) {
        removeBySource(ThreatSource.ENEMY_PING);
        threats.add(new ThreatEntry(ThreatSource.ENEMY_PING, null, pos, ENEMY_PING_INITIAL_WEIGHT));
        
        Vec3 threatPos = Vec3.atCenterOf(pos);
        updatePersistentThreatDirection(threatPos);
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Enemy ping added: pos={}, weight={}", pos, ENEMY_PING_INITIAL_WEIGHT);
        }
    }

    public void onEntityDetected(LivingEntity entity, Vec3 soldierPos) {
        removeByEntity(entity);
        
        float weight = calculateDistanceWeight(entity, soldierPos);
        threats.add(new ThreatEntry(ThreatSource.ENTITY_DETECTED, entity, entity.blockPosition(), weight));
        
        updatePersistentThreatDirection(entity.position());
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            double distance = entity.position().distanceTo(soldierPos);
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Entity detected: {}, distance={}, weight={}", 
                entity.getName().getString(), String.format("%.1f", distance), String.format("%.2f", weight));
        }
    }

    public void onEntityRemoved(LivingEntity entity) {
        ThreatEntry entry = findByEntity(entity);
        if (entry != null) {
            entry.source = ThreatSource.DEAD_ENTITY;
            entry.weight *= DEAD_ENTITY_WEIGHT_MULTIPLIER;
            entry.entity = null;
            
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[ThreatAwareness] Entity removed (dead): {}, weight reduced to {}", 
                    entity.getName().getString(), String.format("%.2f", entry.weight));
            }
        }
    }

    public void onEntityLost(LivingEntity entity) {
        ThreatEntry entry = findByEntity(entity);
        if (entry != null) {
            entry.entity = null;
        }
    }

    public void updatePersistentThreatDirection(Vec3 threatPos) {
        if (soldierPosReference == null) return;
        
        Vec3 toThreat = threatPos.subtract(soldierPosReference).normalize();
        
        if (persistentThreatDirection == null) {
            persistentThreatDirection = toThreat;
        } else {
            persistentThreatDirection = persistentThreatDirection.scale(1.0f - PERSISTENT_THREAT_BLEND_FACTOR)
                .add(toThreat.scale(PERSISTENT_THREAT_BLEND_FACTOR))
                .normalize();
        }
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Persistent threat direction updated: ({}, {}, {})",
                String.format("%.2f", persistentThreatDirection.x),
                String.format("%.2f", persistentThreatDirection.y),
                String.format("%.2f", persistentThreatDirection.z));
        }
    }

    public void setPersistentThreatDirection(Vec3 direction) {
        this.persistentThreatDirection = direction != null ? direction.normalize() : null;
    }

    public void clearPersistentThreatDirection() {
        this.persistentThreatDirection = null;
    }

    public Vec3 getPersistentThreatDirection() {
        return persistentThreatDirection;
    }

    public void setCoverFacingDirection(Vec3 direction) {
        this.coverFacingDirection = direction;
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Cover facing direction set: ({}, {}, {})",
                direction != null ? String.format("%.2f", direction.x) : "null",
                direction != null ? String.format("%.2f", direction.y) : "null",
                direction != null ? String.format("%.2f", direction.z) : "null");
        }
    }

    public void setCoverFacingDirectionFromCover(Set<Direction> protectedDirs) {
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            this.coverFacingDirection = null;
            return;
        }
        
        Direction wallDir = protectedDirs.iterator().next();
        Vec3 threatDir = vec3FromDirection(wallDir.getOpposite());
        this.coverFacingDirection = threatDir;
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareness] Cover facing direction from wall {}: ({}, {}, {})",
                wallDir, 
                String.format("%.2f", threatDir.x),
                String.format("%.2f", threatDir.y),
                String.format("%.2f", threatDir.z));
        }
    }

    public void clearCoverFacingDirection() {
        this.coverFacingDirection = null;
    }

    public Vec3 getCoverFacingDirection() {
        return coverFacingDirection;
    }

    public Vec3 getThreatDirectionForProactivePeek(Vec3 soldierPos) {
        if (!threats.isEmpty()) {
            Vec3 weightedCenter = getWeightedAveragePosition();
            if (weightedCenter != Vec3.ZERO) {
                Vec3 dir = weightedCenter.subtract(soldierPos).normalize();
                if (dir.lengthSqr() > 0.001) {
                    return dir;
                }
            }
        }
        
        if (persistentThreatDirection != null) {
            return persistentThreatDirection;
        }
        
        if (coverFacingDirection != null) {
            return coverFacingDirection;
        }
        
        return null;
    }

    public void tick() {
        Iterator<ThreatEntry> it = threats.iterator();
        while (it.hasNext()) {
            ThreatEntry entry = it.next();
            
            if (entry.entity != null && entry.entity.isAlive()) {
                entry.position = entry.entity.blockPosition();
                entry.lastSeenTime = System.currentTimeMillis();
            } else {
                entry.weight *= WEIGHT_DECAY_PER_TICK;
            }
            
            if (entry.weight < MIN_WEIGHT) {
                it.remove();
                continue;
            }
        }
    }

    public void clear() {
        threats.clear();
    }

    public Vec3 getPrimaryDirection(Vec3 soldierPos) {
        if (threats.isEmpty()) return Vec3.ZERO;

        Vec3 total = Vec3.ZERO;
        for (ThreatEntry entry : threats) {
            Vec3 dir = Vec3.atCenterOf(entry.position).subtract(soldierPos).normalize();
            total = total.add(dir.scale(entry.weight));
        }

        if (total.lengthSqr() < 0.001) return Vec3.ZERO;
        return total.normalize();
    }

    public float getThreatLevel() {
        float totalWeight = 0;
        for (ThreatEntry entry : threats) {
            totalWeight += entry.weight;
        }
        return Math.min(1.0f, totalWeight / PING_DIRECTION_INITIAL_WEIGHT);
    }

    public boolean hasActiveThreat() {
        return !threats.isEmpty();
    }

    public int getActiveThreatCount() {
        return threats.size();
    }

    @Nullable
    public BlockPos getPrimaryThreatPosition() {
        ThreatEntry best = null;
        for (ThreatEntry entry : threats) {
            if (best == null || entry.weight > best.weight) {
                best = entry;
            }
        }
        return best != null ? best.position : null;
    }

    public boolean isBeingFlanked(Vec3 soldierPos) {
        if (threats.size() < 2) return false;

        double minAngle = 360.0;
        double maxAngle = 0.0;
        int valid = 0;

        for (ThreatEntry entry : threats) {
            Vec3 toThreat = Vec3.atCenterOf(entry.position).subtract(soldierPos);
            double angle = Math.toDegrees(Math.atan2(toThreat.x, toThreat.z));
            angle = normalizeAngle(angle);
            minAngle = Math.min(minAngle, angle);
            maxAngle = Math.max(maxAngle, angle);
            valid++;
        }

        if (valid < 2) return false;
        double span = calculateAngularSpan(minAngle, maxAngle);
        return span > FLANKING_ANGLE_THRESHOLD_DEG;
    }

    public float getThreatCoverage(BlockPos coverPos) {
        if (threats.isEmpty()) return 1.0f;

        int protectedCount = 0;
        Vec3 coverCenter = coverPos.getCenter();

        for (ThreatEntry entry : threats) {
            Vec3 threatDir = coverCenter.subtract(Vec3.atCenterOf(entry.position)).normalize();
            if (isDirectionProtected(coverPos, threatDir)) {
                protectedCount++;
            }
        }

        return (float) protectedCount / threats.size();
    }

    public Vec3 getWeightedAveragePosition() {
        if (threats.isEmpty()) return Vec3.ZERO;

        double totalX = 0, totalY = 0, totalZ = 0;
        float totalWeight = 0;

        for (ThreatEntry entry : threats) {
            totalX += entry.position.getX() * entry.weight;
            totalY += entry.position.getY() * entry.weight;
            totalZ += entry.position.getZ() * entry.weight;
            totalWeight += entry.weight;
        }

        if (totalWeight <= 0) return Vec3.ZERO;
        return new Vec3(totalX / totalWeight + 0.5, totalY / totalWeight + 0.5, totalZ / totalWeight + 0.5);
    }

    public List<BlockPos> getThreatPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (ThreatEntry entry : threats) {
            positions.add(entry.position);
        }
        return positions;
    }

    public void debugLogThreatState(Vec3 soldierPos) {
        if (!CoverTacticalGoal.isDebugLoggingEnabled()) return;
        
        StevesArmyMod.LOGGER.info("[ThreatAwareness] ===== THREAT STATE DEBUG =====");
        StevesArmyMod.LOGGER.info("  Active threats: {}", threats.size());
        
        for (ThreatEntry entry : threats) {
            StevesArmyMod.LOGGER.info("    - Source: {}, Entity: {}, Pos: {}, Weight: {:.2f}", 
                entry.source, 
                entry.entity != null ? entry.entity.getName().getString() : "null",
                entry.position,
                String.format("%.2f", entry.weight));
        }
        
        Vec3 weightedCenter = getWeightedAveragePosition();
        StevesArmyMod.LOGGER.info("  Weighted center: ({}, {}, {})", 
            weightedCenter != Vec3.ZERO ? String.format("%.1f", weightedCenter.x) : "none",
            weightedCenter != Vec3.ZERO ? String.format("%.1f", weightedCenter.y) : "none",
            weightedCenter != Vec3.ZERO ? String.format("%.1f", weightedCenter.z) : "none");
        
        StevesArmyMod.LOGGER.info("  Persistent threat dir: ({}, {}, {})", 
            persistentThreatDirection != null ? String.format("%.2f", persistentThreatDirection.x) : "none",
            persistentThreatDirection != null ? String.format("%.2f", persistentThreatDirection.y) : "none",
            persistentThreatDirection != null ? String.format("%.2f", persistentThreatDirection.z) : "none");
        
        StevesArmyMod.LOGGER.info("  Cover facing dir: ({}, {}, {})", 
            coverFacingDirection != null ? String.format("%.2f", coverFacingDirection.x) : "none",
            coverFacingDirection != null ? String.format("%.2f", coverFacingDirection.y) : "none",
            coverFacingDirection != null ? String.format("%.2f", coverFacingDirection.z) : "none");
        
        Vec3 finalDir = getThreatDirectionForProactivePeek(soldierPos);
        StevesArmyMod.LOGGER.info("  Final proactive peek dir: ({}, {}, {})", 
            finalDir != null ? String.format("%.2f", finalDir.x) : "none",
            finalDir != null ? String.format("%.2f", finalDir.y) : "none",
            finalDir != null ? String.format("%.2f", finalDir.z) : "none");
    }

    private float calculateDistanceWeight(LivingEntity entity, Vec3 soldierPos) {
        double distance = entity.position().distanceTo(soldierPos);
        float distanceFactor = 1.0f / (1.0f + (float)distance * DISTANCE_WEIGHT_FACTOR);
        return ENTITY_DETECTED_INITIAL_WEIGHT * distanceFactor;
    }

    private Vec3 vec3FromDirection(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()).normalize();
    }

    private void removeBySource(ThreatSource source) {
        threats.removeIf(e -> e.source == source);
    }

    private void removeByEntity(LivingEntity entity) {
        threats.removeIf(e -> e.entity != null && e.entity.getUUID().equals(entity.getUUID()));
    }

    @Nullable
    private ThreatEntry findByEntity(LivingEntity entity) {
        for (ThreatEntry e : threats) {
            if (e.entity != null && e.entity.getUUID().equals(entity.getUUID())) {
                return e;
            }
        }
        return null;
    }

    private boolean isDirectionProtected(BlockPos coverPos, Vec3 threatDirection) {
        double dx = threatDirection.x;
        double dz = threatDirection.z;
        return Math.abs(dx) > Math.abs(dz) ? dx > 0 : dz > 0;
    }

    private double normalizeAngle(double angle) {
        while (angle < 0) angle += 360.0;
        while (angle >= 360.0) angle -= 360.0;
        return angle;
    }

    private double calculateAngularSpan(double minAngle, double maxAngle) {
        double directSpan = maxAngle - minAngle;
        if (directSpan <= 180.0) return directSpan;
        return 360.0 - directSpan;
    }
}
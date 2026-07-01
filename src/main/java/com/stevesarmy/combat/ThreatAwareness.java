package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ThreatAwareness {

    public enum ThreatSource {
        PING_DIRECTION,
        ENEMY_PING,
        ENTITY_DETECTED,
        PERIPHERAL
    }

    private static class ThreatEntry {
        final ThreatSource source;
        @Nullable LivingEntity entity;
        BlockPos position;
        float weight;
        long lastSeenTime;

        ThreatEntry(ThreatSource source, @Nullable LivingEntity entity, BlockPos position, float weight) {
            this.source = source;
            this.entity = entity;
            this.position = position;
            this.weight = weight;
            this.lastSeenTime = System.currentTimeMillis();
        }
    }

    private static final float PING_DIRECTION_INITIAL_WEIGHT = 10.0f;
    private static final float PING_DIRECTION_DECAY = 0.5f;
    private static final long PING_DIRECTION_MEMORY_MS = 20000;

    private static final float ENEMY_PING_INITIAL_WEIGHT = 5.0f;
    private static final float ENEMY_PING_DECAY = 1.0f;
    private static final long ENEMY_PING_MEMORY_MS = 10000;

    private static final float ENTITY_DETECTED_INITIAL_WEIGHT = 3.0f;
    private static final float ENTITY_DETECTED_DECAY = 0.3f;
    private static final long ENTITY_LOST_GRACE_MS = 5000;

    private static final float PERIPHERAL_INITIAL_WEIGHT = 1.0f;
    private static final float PERIPHERAL_DECAY = 0.5f;
    private static final long PERIPHERAL_MEMORY_MS = 5000;

    private static final float MIN_WEIGHT = 0.1f;
    private static final double FLANKING_ANGLE_THRESHOLD_DEG = 90.0;

    private final List<ThreatEntry> threats = new ArrayList<>();

    public void onPingDirection(BlockPos pos) {
        removeBySource(ThreatSource.PING_DIRECTION);
        threats.add(new ThreatEntry(ThreatSource.PING_DIRECTION, null, pos, PING_DIRECTION_INITIAL_WEIGHT));
    }

    public void onEnemyPing(BlockPos pos) {
        removeBySource(ThreatSource.ENEMY_PING);
        threats.add(new ThreatEntry(ThreatSource.ENEMY_PING, null, pos, ENEMY_PING_INITIAL_WEIGHT));
    }

    public void onEntityDetected(LivingEntity entity) {
        removeByEntity(entity);
        threats.add(new ThreatEntry(ThreatSource.ENTITY_DETECTED, entity, entity.blockPosition(), ENTITY_DETECTED_INITIAL_WEIGHT));
    }

    public void onEntityLost(LivingEntity entity) {
        ThreatEntry entry = findByEntity(entity);
        if (entry != null) {
            entry.entity = null;
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<ThreatEntry> it = threats.iterator();
        while (it.hasNext()) {
            ThreatEntry entry = it.next();
            if (shouldRemove(entry, now)) {
                it.remove();
                continue;
            }
            entry.weight = decayWeight(entry, now);
            if (entry.weight < MIN_WEIGHT) {
                it.remove();
                continue;
            }
            if (entry.entity != null && entry.entity.isAlive()) {
                entry.position = entry.entity.blockPosition();
                entry.lastSeenTime = now;
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
            Vec3 dir = soldierPos.subtract(Vec3.atCenterOf(entry.position)).normalize();
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
        return new Vec3(totalX / totalWeight, totalY / totalWeight, totalZ / totalWeight);
    }

    public List<BlockPos> getThreatPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (ThreatEntry entry : threats) {
            positions.add(entry.position);
        }
        return positions;
    }

    private boolean shouldRemove(ThreatEntry entry, long now) {
        long elapsed = now - entry.lastSeenTime;
        switch (entry.source) {
            case PING_DIRECTION: return elapsed > PING_DIRECTION_MEMORY_MS;
            case ENEMY_PING:     return elapsed > ENEMY_PING_MEMORY_MS;
            case ENTITY_DETECTED: {
                if (entry.entity != null && entry.entity.isAlive()) return false;
                return elapsed > ENTITY_LOST_GRACE_MS;
            }
            case PERIPHERAL:     return elapsed > PERIPHERAL_MEMORY_MS;
            default: return true;
        }
    }

    private float decayWeight(ThreatEntry entry, long now) {
        float elapsedSec = (now - entry.lastSeenTime) / 1000.0f;
        switch (entry.source) {
            case PING_DIRECTION: return Math.max(MIN_WEIGHT, entry.weight - PING_DIRECTION_DECAY * elapsedSec);
            case ENEMY_PING:     return Math.max(MIN_WEIGHT, entry.weight - ENEMY_PING_DECAY * elapsedSec);
            case ENTITY_DETECTED: {
                if (entry.entity != null && entry.entity.isAlive()) return entry.weight;
                return Math.max(MIN_WEIGHT, entry.weight - ENTITY_DETECTED_DECAY * elapsedSec);
            }
            case PERIPHERAL:     return Math.max(MIN_WEIGHT, entry.weight - PERIPHERAL_DECAY * elapsedSec);
            default: return entry.weight;
        }
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
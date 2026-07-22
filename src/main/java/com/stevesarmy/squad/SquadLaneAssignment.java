package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class SquadLaneAssignment {

    private final UUID squadId;
    private final BlockPos rawTarget;
    private final long timestamp;
    private final Vec3 forward;
    private final Vec3 perpendicular;
    private final Map<UUID, LaneSlot> slots = new HashMap<>();
    private final List<UUID> orderedUuids;

    public static class LaneSlot {
        public final int laneIndex;
        public final int totalLanes;
        public final double laneOffset;

        LaneSlot(int laneIndex, int totalLanes, double laneOffset) {
            this.laneIndex = laneIndex;
            this.totalLanes = totalLanes;
            this.laneOffset = laneOffset;
        }
    }

    public SquadLaneAssignment(UUID squadId, BlockPos rawTarget, List<SoldierEntity> team) {
        this.squadId = squadId;
        this.rawTarget = rawTarget;
        this.timestamp = System.currentTimeMillis();

        // Sort team by UUID for deterministic ordering
        List<SoldierEntity> sorted = new ArrayList<>(team);
        sorted.sort(Comparator.comparing(e -> e.getUUID()));
        this.orderedUuids = new ArrayList<>();
        for (SoldierEntity s : sorted) {
            orderedUuids.add(s.getUUID());
        }

        // Compute shared centroid and forward/perpendicular frame
        Vec3 centroid = Vec3.ZERO;
        for (SoldierEntity s : sorted) {
            centroid = centroid.add(s.position());
        }
        centroid = centroid.scale(1.0 / sorted.size());

        Vec3 toTarget = Vec3.atCenterOf(rawTarget).subtract(centroid);
        if (toTarget.horizontalDistanceSqr() < 0.01) {
            // Fallback to world Z as forward
            this.forward = new Vec3(0, 0, -1);
            this.perpendicular = new Vec3(1, 0, 0);
        } else {
            this.forward = new Vec3(toTarget.x, 0, toTarget.z).normalize();
            this.perpendicular = new Vec3(-toTarget.z, 0, toTarget.x).normalize();
        }

        // Assign centered lane offsets
        int n = sorted.size();
        double spacing = StevesArmyConfig.getSpacingDistance();
        for (int i = 0; i < n; i++) {
            double offset;
            if (n == 1) {
                offset = 0;
            } else {
                // Center around 0: -2d, -1d, 0, +1d, +2d for 5 soldiers
                // For 4: -1.5d, -0.5d, +0.5d, +1.5d
                double halfSpan = (n - 1) / 2.0;
                offset = (i - halfSpan) * spacing;
            }
            slots.put(sorted.get(i).getUUID(), new LaneSlot(i, n, offset));
        }
    }

    public LaneSlot getSlot(UUID soldierUuid) {
        return slots.get(soldierUuid);
    }

    public Vec3 getForward() { return forward; }
    public Vec3 getPerpendicular() { return perpendicular; }
    public BlockPos getRawTarget() { return rawTarget; }
    public UUID getSquadId() { return squadId; }
    public long getTimestamp() { return timestamp; }
    public List<UUID> getOrderedUuids() { return orderedUuids; }

    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > 30000; // 30 second validity
    }

    /**
     * Compute the lane-aligned navigation target — a fixed offset from rawTarget.
     * No look-ahead; the offset applies at the destination only.
     */
    public BlockPos getLaneTarget(UUID soldierUuid, Vec3 soldierPos) {
        LaneSlot slot = slots.get(soldierUuid);
        if (slot == null) return rawTarget;
        return rawTarget.offset(
            (int) Math.round(perpendicular.x * slot.laneOffset),
            0,
            (int) Math.round(perpendicular.z * slot.laneOffset));
    }

    /**
     * Compute the final lane-aligned position at the destination.
     * Identical to getLaneTarget — both return the fixed lane position.
     */
    public BlockPos getFinalLanePosition(UUID soldierUuid) {
        return getLaneTarget(soldierUuid, null);
    }
}
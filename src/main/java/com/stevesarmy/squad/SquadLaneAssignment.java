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
    private final SquadFormation formation;

    public static class LaneSlot {
        public final int laneIndex;
        public final int totalLanes;
        public final double laneOffset;
        public final double forwardOffset;

        LaneSlot(int laneIndex, int totalLanes, double laneOffset, double forwardOffset) {
            this.laneIndex = laneIndex;
            this.totalLanes = totalLanes;
            this.laneOffset = laneOffset;
            this.forwardOffset = forwardOffset;
        }
    }

    public SquadLaneAssignment(UUID squadId, BlockPos rawTarget, List<SoldierEntity> team, SquadFormation formation) {
        this.squadId = squadId;
        this.rawTarget = rawTarget;
        this.timestamp = System.currentTimeMillis();
        this.formation = formation;

        List<SoldierEntity> sorted = new ArrayList<>(team);
        sorted.sort(Comparator.comparing(e -> e.getUUID()));
        this.orderedUuids = new ArrayList<>();
        for (SoldierEntity s : sorted) {
            orderedUuids.add(s.getUUID());
        }

        Vec3 centroid = Vec3.ZERO;
        for (SoldierEntity s : sorted) {
            centroid = centroid.add(s.position());
        }
        centroid = centroid.scale(1.0 / sorted.size());

        Vec3 toTarget = Vec3.atCenterOf(rawTarget).subtract(centroid);
        if (toTarget.horizontalDistanceSqr() < 0.01) {
            this.forward = new Vec3(0, 0, -1);
            this.perpendicular = new Vec3(1, 0, 0);
        } else {
            this.forward = new Vec3(toTarget.x, 0, toTarget.z).normalize();
            this.perpendicular = new Vec3(-toTarget.z, 0, toTarget.x).normalize();
        }

        int n = sorted.size();
        double spacing = StevesArmyConfig.getSpacingDistance();
        boolean noSpacing = formation == SquadFormation.NONE;
        for (int i = 0; i < n; i++) {
            double laneOffset;
            double forwardOffset;
            if (n == 1 || noSpacing) {
                laneOffset = 0;
                forwardOffset = 0;
            } else {
                double halfSpan = (n - 1) / 2.0;
                double centered = (i - halfSpan);

                switch (formation) {
                    case LINE -> {
                        laneOffset = centered * spacing;
                        forwardOffset = 0;
                    }
                    case WEDGE -> {
                        laneOffset = centered * spacing;
                        forwardOffset = -Math.abs(centered) * spacing * 0.5;
                    }
                    case COLUMN -> {
                        laneOffset = 0;
                        forwardOffset = centered * spacing;
                    }
                    default -> {
                        laneOffset = centered * spacing;
                        forwardOffset = 0;
                    }
                }
            }
            slots.put(sorted.get(i).getUUID(), new LaneSlot(i, n, laneOffset, forwardOffset));
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
    public SquadFormation getFormation() { return formation; }

    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > 30000;
    }

    /**
     * Compute the formation-aligned navigation target — a fixed offset from rawTarget.
     */
    public BlockPos getLaneTarget(UUID soldierUuid, Vec3 soldierPos) {
        LaneSlot slot = slots.get(soldierUuid);
        if (slot == null) return rawTarget;
        return rawTarget.offset(
            (int) Math.round(perpendicular.x * slot.laneOffset + forward.x * slot.forwardOffset),
            0,
            (int) Math.round(perpendicular.z * slot.laneOffset + forward.z * slot.forwardOffset));
    }

    public BlockPos getFinalLanePosition(UUID soldierUuid) {
        return getLaneTarget(soldierUuid, null);
    }
}
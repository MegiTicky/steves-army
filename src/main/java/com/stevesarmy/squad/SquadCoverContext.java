package com.stevesarmy.squad;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record SquadCoverContext(
    boolean inSquad,
    int squadSize,
    int memberIndex,
    List<BlockPos> occupiedCovers,
    List<BlockPos> defensivePositions,
    List<Vec3> squadThreatDirections,
    List<FiringContact> firingContacts,
    SquadCoverPeekabilityCache peekabilityCache,
    Vec3 ownerPosition
) {
    public record FiringContact(UUID threatEntityId, Vec3 exposedPoint, long lastSeenTick) {
        public static final long MAX_AGE_TICKS = 200L;
        public static final int MAX_CONTACTS = 12;

        public float freshnessAt(long currentTick) {
            long age = Math.max(0L, currentTick - lastSeenTick);
            return Math.max(0.0f, 1.0f - (float) age / MAX_AGE_TICKS);
        }
    }

    public boolean isTooClose(BlockPos pos, double minDist) {
        if (occupiedCovers.isEmpty()) return false;
        double minDistSq = minDist * minDist;
        for (BlockPos cover : occupiedCovers) {
            if (cover.distSqr(pos) < minDistSq) {
                return true;
            }
        }
        return false;
    }

    public boolean isSameCover(BlockPos pos) {
        return occupiedCovers.contains(pos);
    }

    public List<BlockPos> getOccupiedCovers() {
        return occupiedCovers != null ? occupiedCovers : Collections.emptyList();
    }

    /** Physical cover and non-reservable open-ground defensive lanes selected by peers. */
    public List<BlockPos> getDefensivePositions() {
        return defensivePositions != null ? defensivePositions : Collections.emptyList();
    }

    public List<Vec3> getSquadThreatDirections() {
        return squadThreatDirections != null ? squadThreatDirections : Collections.emptyList();
    }

    /** Last genuinely observed hostile body points eligible for cover fire-lane scoring. */
    public List<FiringContact> getFiringContacts() {
        return firingContacts != null ? firingContacts : Collections.emptyList();
    }

    public SquadCoverPeekabilityCache getPeekabilityCache() {
        return peekabilityCache;
    }

    public Vec3 getOwnerPosition() {
        return ownerPosition;
    }
}

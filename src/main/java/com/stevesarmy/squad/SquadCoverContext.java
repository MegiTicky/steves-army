package com.stevesarmy.squad;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

public record SquadCoverContext(
    boolean inSquad,
    SquadFormation formation,
    int squadSize,
    int memberIndex,
    List<BlockPos> occupiedCovers,
    List<Vec3> squadThreatDirections,
    Vec3 ownerPosition
) {
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

    public SquadFormation getFormation() {
        return formation;
    }

    public List<BlockPos> getOccupiedCovers() {
        return occupiedCovers != null ? occupiedCovers : Collections.emptyList();
    }

    public List<Vec3> getSquadThreatDirections() {
        return squadThreatDirections != null ? squadThreatDirections : Collections.emptyList();
    }

    public Vec3 getOwnerPosition() {
        return ownerPosition;
    }
}
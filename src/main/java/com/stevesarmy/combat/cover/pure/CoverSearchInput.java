package com.stevesarmy.combat.cover.pure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/** Immutable tactical input copied from the server thread for cover scoring. */
public record CoverSearchInput(
    BlockPos soldierPosition,
    Vec3 threatDirection,
    Vec3 protectionThreatPosition,
    Vec3 protectionAwarenessDirection,
    List<ThreatSnapshot> threats,
    List<FiringContactSnapshot> firingContacts,
    List<CoverCandidateSnapshot> candidates,
    List<BlockPos> occupiedCovers,
    Vec3 ownerPosition,
    BlockPos attackTargetPosition,
    BlockPos searchCenter,
    int searchRadius,
    long sourceTick
) {
    public CoverSearchInput {
        soldierPosition = soldierPosition == null ? BlockPos.ZERO : soldierPosition.immutable();
        threatDirection = copy(threatDirection);
        protectionThreatPosition = copy(protectionThreatPosition);
        protectionAwarenessDirection = copy(protectionAwarenessDirection);
        threats = List.copyOf(threats == null ? List.of() : threats);
        firingContacts = List.copyOf(firingContacts == null ? List.of() : firingContacts);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        occupiedCovers = immutablePositions(occupiedCovers);
        ownerPosition = copy(ownerPosition);
        attackTargetPosition = attackTargetPosition == null ? null : attackTargetPosition.immutable();
        searchCenter = searchCenter == null ? soldierPosition : searchCenter.immutable();
        searchRadius = Math.max(1, searchRadius);
    }

    public record ThreatSnapshot(Vec3 position, float weight) {
        public ThreatSnapshot {
            position = copy(position);
            weight = Math.max(0.0f, weight);
        }
    }

    public record FiringContactSnapshot(Vec3 exposedPoint, float freshness) {
        public FiringContactSnapshot {
            exposedPoint = copy(exposedPoint);
            freshness = Math.max(0.0f, freshness);
        }
    }

    public record CoverCandidateSnapshot(
        BlockPos position,
        com.stevesarmy.combat.cover.CoverType type,
        float quality,
        boolean canShootFrom,
        float coverHeight,
        Set<Direction> protectedDirections,
        List<Vec3> firingOrigins,
        float legacyScore
    ) {
        public CoverCandidateSnapshot {
            position = position == null ? BlockPos.ZERO : position.immutable();
            type = type == null ? com.stevesarmy.combat.cover.CoverType.NONE : type;
            quality = clamp(quality);
            protectedDirections = protectedDirections == null ? Set.of() : Set.copyOf(protectedDirections);
            firingOrigins = firingOrigins == null ? List.of() : firingOrigins.stream()
                .map(CoverSearchInput::copy).toList();
        }
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return List.of();
        return positions.stream().map(BlockPos::immutable).toList();
    }

    private static Vec3 copy(Vec3 value) {
        return value == null ? null : new Vec3(value.x, value.y, value.z);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

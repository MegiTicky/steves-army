package com.stevesarmy.combat.cover.pure;

import com.stevesarmy.combat.ModBlockTags;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverProtectionContext;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.squad.SquadCoverContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread capture of the immutable inputs required by PureCoverEvaluator. */
public final class CoverSnapshotCapture {
    private static final double HALF_COVER_CROUCH_EYE_HEIGHT = 1.27;
    private static final double HALF_COVER_STANDING_EYE_HEIGHT = 1.62;
    private static final double HALF_COVER_STANDING_HEIGHT_THRESHOLD = 1.3;
    private static final double HALF_COVER_WAIST_HEIGHT = 0.8;
    private static final double COVER_RAY_DISTANCE = 3.0;
    private static final double CONE_RAY_DISTANCE = 20.0;

    private CoverSnapshotCapture() {}

    public static Capture capture(Level level, LivingEntity soldier, Vec3 threatDirection,
                                  CoverProtectionContext protection, List<LivingEntity> threats,
                                  SquadCoverContext squadContext,
                                   List<CoverFinder.ScoredCover> scored, BlockPos searchCenter,
                                   int searchRadius) {
        List<CoverSearchInput.CoverCandidateSnapshot> candidates = new ArrayList<>();
        if (scored != null) {
            for (CoverFinder.ScoredCover scoredCover : scored) {
                candidates.add(snapshotCandidate(level, scoredCover.cover, scoredCover.score));
            }
        }
        return captureCandidates(level, soldier, threatDirection, protection, threats, squadContext,
            candidates, searchCenter, searchRadius);
    }

    /** Captures discovered candidates before tactical scoring for the Phase 6 pilot. */
    public static Capture captureRaw(Level level, LivingEntity soldier, Vec3 threatDirection,
                                     CoverProtectionContext protection, List<LivingEntity> threats,
                                     SquadCoverContext squadContext, List<CoverPoint> candidates,
                                     BlockPos searchCenter, int searchRadius) {
        List<CoverSearchInput.CoverCandidateSnapshot> snapshots = new ArrayList<>();
        if (candidates != null) {
            for (CoverPoint candidate : candidates) {
                snapshots.add(snapshotCandidate(level, candidate, 0.0f));
            }
        }
        return captureCandidates(level, soldier, threatDirection, protection, threats, squadContext,
            snapshots, searchCenter, searchRadius);
    }

    private static Capture captureCandidates(Level level, LivingEntity soldier, Vec3 threatDirection,
                                   CoverProtectionContext protection, List<LivingEntity> threats,
                                   SquadCoverContext squadContext,
                                   List<CoverSearchInput.CoverCandidateSnapshot> candidates,
                                   BlockPos searchCenter, int searchRadius) {
        List<CoverSearchInput.ThreatSnapshot> threatSnapshots = new ArrayList<>();
        if (threats != null) {
            for (LivingEntity threat : threats) {
                if (threat != null && threat.isAlive()) {
                    threatSnapshots.add(new CoverSearchInput.ThreatSnapshot(
                        threat.position(), 1.0f));
                }
            }
        }

        List<CoverSearchInput.FiringContactSnapshot> contacts = new ArrayList<>();
        long currentTick = level.getGameTime();
        if (squadContext != null) {
            for (SquadCoverContext.FiringContact contact : squadContext.getFiringContacts()) {
                float freshness = contact.freshnessAt(currentTick);
                if (freshness > 0.0f) {
                    contacts.add(new CoverSearchInput.FiringContactSnapshot(contact.exposedPoint(), freshness));
                }
            }
        }

        CoverSearchInput input = new CoverSearchInput(
            soldier.blockPosition(), threatDirection,
            protection == null ? null : protection.threatPosition(),
            protection == null ? null : protection.awarenessDirection(),
            threatSnapshots, contacts, candidates,
            squadContext == null ? List.of() : squadContext.getOccupiedCovers(),
            squadContext == null ? null : squadContext.getOwnerPosition(),
            null, searchCenter, searchRadius, currentTick);

        Set<BlockPos> rayCells = new HashSet<>();
        for (CoverSearchInput.CoverCandidateSnapshot candidate : candidates) {
            addCandidateRays(rayCells, candidate, input);
        }
        return new Capture(input, new CoverTerrainSnapshot(captureCells(level, rayCells)));
    }

    private static CoverSearchInput.CoverCandidateSnapshot snapshotCandidate(Level level, CoverPoint cover,
                                                                               float score) {
        return new CoverSearchInput.CoverCandidateSnapshot(
            cover.getPosition(), cover.getType(), cover.getQuality(), cover.canShootFrom(),
            cover.getCoverHeight(), cover.getProtectedDirections(), firingOrigins(level, cover), score);
    }

    private static void addCandidateRays(Set<BlockPos> cells,
                                         CoverSearchInput.CoverCandidateSnapshot candidate,
                                         CoverSearchInput input) {
        BlockPos position = candidate.position();
        Vec3 protectionDirection = input.protectionThreatPosition() != null
            ? input.protectionThreatPosition().subtract(position.getCenter())
            : input.protectionAwarenessDirection();
        if (protectionDirection != null && horizontalLengthSqr(protectionDirection) > 0.001) {
            Vec3 normalized = horizontal(protectionDirection).normalize();
            for (int i = 0; i < 4; i++) {
                Vec3 origin = corner(position, HALF_COVER_WAIST_HEIGHT, i);
                addRayCells(cells, origin, origin.add(normalized.scale(COVER_RAY_DISTANCE)));
            }
        }

        List<Vec3> origins = candidate.firingOrigins();
        for (Vec3 origin : origins) {
            Vec3 activeThreatPosition = input.threats().isEmpty()
                ? null : input.threats().get(0).position();
            if (activeThreatPosition != null) {
                addRayCells(cells, origin, activeThreatPosition);
            }
            for (CoverSearchInput.FiringContactSnapshot contact : input.firingContacts()) {
                addRayCells(cells, origin, contact.exposedPoint());
            }
            if (input.threatDirection() != null && horizontalLengthSqr(input.threatDirection()) > 0.001) {
                Vec3 direction = horizontal(input.threatDirection()).normalize();
                for (int i = 0; i < 7; i++) {
                    double angle = -30.0 + 60.0 * i / 6.0;
                    Vec3 rayDirection = CoverFinder.rotateVectorY(direction, angle);
                    addRayCells(cells, origin, origin.add(rayDirection.scale(CONE_RAY_DISTANCE)));
                }
            }
        }
    }

    private static List<Vec3> firingOrigins(Level level, CoverPoint cover) {
        List<Vec3> origins = new ArrayList<>();
        BlockPos position = cover.getPosition();
        if (cover.getType() == CoverType.HALF) {
            double height = cover.getCoverHeight() >= HALF_COVER_STANDING_HEIGHT_THRESHOLD
                ? HALF_COVER_STANDING_EYE_HEIGHT : HALF_COVER_CROUCH_EYE_HEIGHT;
            origins.add(center(position, height));
            return origins;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!cover.getProtectedDirections().contains(direction)) {
                BlockPos peekPosition = position.relative(direction);
                if (CoverFinder.isValidPeekPosition(peekPosition, level)) {
                    origins.add(center(peekPosition, HALF_COVER_STANDING_EYE_HEIGHT));
                }
            }
        }
        return origins;
    }

    private static Map<BlockPos, FiringPositionFinder.SnapshotCell> captureCells(Level level,
                                                                                    Set<BlockPos> positions) {
        Map<BlockPos, FiringPositionFinder.SnapshotCell> cells = new HashMap<>();
        for (BlockPos position : positions) {
            BlockPos key = position.immutable();
            if (!level.isLoaded(key)) {
                cells.put(key, new FiringPositionFinder.SnapshotCell(false, List.of(), List.of(), 0.0f));
                continue;
            }
            BlockState state = level.getBlockState(key);
            boolean leaf = state.is(BlockTags.LEAVES);
            boolean transparent = state.is(ModBlockTags.TRANSPARENT_PENETRABLE);
            boolean concealment = state.is(ModBlockTags.VISION_CONCEALMENT);
            List<FiringPositionFinder.SnapshotBox> collision = !leaf && !transparent && !concealment
                ? snapshotShape(state.getCollisionShape(level, key), key) : List.of();
            List<FiringPositionFinder.SnapshotBox> outline = leaf || concealment
                ? snapshotShape(state.getShape(level, key), key) : List.of();
            float concealmentWeight = leaf ? 0.75f
                : state.is(ModBlockTags.VISION_CONCEALMENT_MEDIUM) ? 0.30f
                : concealment ? 0.20f : 0.0f;
            cells.put(key, new FiringPositionFinder.SnapshotCell(true, collision, outline, concealmentWeight));
        }
        return cells;
    }

    private static List<FiringPositionFinder.SnapshotBox> snapshotShape(VoxelShape shape, BlockPos position) {
        if (shape.isEmpty()) return List.of();
        List<FiringPositionFinder.SnapshotBox> boxes = new ArrayList<>();
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> boxes.add(
            new FiringPositionFinder.SnapshotBox(position.getX() + minX, position.getY() + minY,
                position.getZ() + minZ, position.getX() + maxX, position.getY() + maxY,
                position.getZ() + maxZ)));
        return List.copyOf(boxes);
    }

    private static void addRayCells(Set<BlockPos> cells, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0e-7) {
            cells.add(BlockPos.containing(from).immutable());
            return;
        }
        Vec3 unit = delta.scale(1.0 / length);
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int endX = floor(to.x), endY = floor(to.y), endZ = floor(to.z);
        double t = 0.0;
        while (t <= length + 1.0e-7) {
            cells.add(new BlockPos(x, y, z).immutable());
            if (x == endX && y == endY && z == endZ) break;
            double nextX = boundary(from.x, unit.x, x);
            double nextY = boundary(from.y, unit.y, y);
            double nextZ = boundary(from.z, unit.z, z);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next == Double.POSITIVE_INFINITY) break;
            if (nextX <= next + 1.0e-7) x += step(unit.x);
            if (nextY <= next + 1.0e-7) y += step(unit.y);
            if (nextZ <= next + 1.0e-7) z += step(unit.z);
            t = next;
        }
    }

    private static Vec3 corner(BlockPos position, double height, int index) {
        double[] offsets = {-0.25, 0.25};
        return new Vec3(position.getX() + 0.5 + offsets[index % 2], position.getY() + height,
            position.getZ() + 0.5 + offsets[index / 2]);
    }

    private static Vec3 center(BlockPos position, double height) {
        return new Vec3(position.getX() + 0.5, position.getY() + height, position.getZ() + 0.5);
    }

    private static Vec3 horizontal(Vec3 value) { return new Vec3(value.x, 0.0, value.z); }
    private static double horizontalLengthSqr(Vec3 value) { return value.x * value.x + value.z * value.z; }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static int step(double value) { return value > 0.0 ? 1 : -1; }
    private static double boundary(double coordinate, double direction, int block) {
        if (Math.abs(direction) < 1.0e-7) return Double.POSITIVE_INFINITY;
        return ((direction > 0.0 ? block + 1.0 : block) - coordinate) / direction;
    }

    public record Capture(CoverSearchInput input, CoverTerrainSnapshot terrain) {}
}

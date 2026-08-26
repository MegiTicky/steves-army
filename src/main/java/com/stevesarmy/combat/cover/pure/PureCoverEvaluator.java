package com.stevesarmy.combat.cover.pure;

import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure NORMAL-mode cover scorer. It has no Level, Entity, navigation, or cache
 * access; all geometry comes from CoverTerrainSnapshot.
 */
public final class PureCoverEvaluator {
    private static final double PRIMARY_PROTECTION_WEIGHT = 0.25;
    private static final double FLANKING_PROTECTION_WEIGHT = 0.15;
    private static final double DISTANCE_WEIGHT = 0.10;
    private static final double FIRING_QUALITY_WEIGHT = 0.20;
    private static final double PEEK_ANGLE_WEIGHT = 0.15;
    private static final double SQUAD_DISPERSION_WEIGHT = 0.20;
    private static final float HALF_COVER_FIGHTABILITY_BONUS = 0.25f;
    private static final float FULL_COVER_FIGHTABILITY_BONUS = 0.15f;
    private static final float FIRING_LANE_EPSILON = 0.01f;
    private static final double LAST_SEEN_CONTACT_TOLERANCE = 2.0;
    private static final int MAX_FULL_SCORE_CANDIDATES = 16;

    private PureCoverEvaluator() {}

    public static CoverSearchResult evaluate(CoverSearchInput input, CoverTerrainSnapshot terrain) {
        if (input == null || terrain == null) return new CoverSearchResult(List.of());
        List<CoverSearchInput.CoverCandidateSnapshot> coarse = new ArrayList<>(input.candidates());
        coarse.sort((first, second) -> Float.compare(coarseScore(input, second), coarseScore(input, first)));
        List<CoverSearchResult.RankedCandidate> ranked = new ArrayList<>();
        int fullScoreCount = Math.min(MAX_FULL_SCORE_CANDIDATES, coarse.size());
        for (int i = 0; i < fullScoreCount; i++) {
            CoverSearchInput.CoverCandidateSnapshot candidate = coarse.get(i);
            if (candidate.type() == CoverType.NONE) continue;
            ranked.add(new CoverSearchResult.RankedCandidate(candidate,
                score(input, terrain, candidate)));
        }
        return new CoverSearchResult(ranked);
    }

    /** Cheap stage-one ordering used to bound the ray-heavy stage-two work. */
    private static float coarseScore(CoverSearchInput input,
                                     CoverSearchInput.CoverCandidateSnapshot candidate) {
        if (candidate.type() == CoverType.NONE) return -Float.MAX_VALUE;
        return candidate.quality() * 0.55f
            + distanceScore(candidate.position(), input.soldierPosition()) * 0.25f
            + firingQuality(candidate, input.threatDirection()) * 0.20f;
    }

    private static float score(CoverSearchInput input, CoverTerrainSnapshot terrain,
                               CoverSearchInput.CoverCandidateSnapshot candidate) {
        float primaryProtection = primaryProtection(input, terrain, candidate);
        float flankingProtection = flankingProtection(candidate, input.threats());
        float firingQuality = firingQuality(candidate, input.threatDirection());
        float firingLane = firingLane(input, terrain, candidate);
        float distance = distanceScore(candidate.position(), input.soldierPosition());
        float dispersion = dispersionScore(candidate.position(), input.occupiedCovers());

        float fightability = candidate.canShootFrom() && firingLane > FIRING_LANE_EPSILON
            ? candidate.type() == CoverType.HALF ? HALF_COVER_FIGHTABILITY_BONUS : FULL_COVER_FIGHTABILITY_BONUS
            : 0.0f;
        float blindPenalty = candidate.type() == CoverType.FULL && firingLane <= FIRING_LANE_EPSILON
            && !input.threats().isEmpty() ? 0.50f : 0.0f;
        if (candidate.type() == CoverType.CONCEALMENT) {
            blindPenalty = 0.50f;
            fightability = 0.0f;
            firingQuality = 0.0f;
            firingLane = 0.0f;
        }

        return (float) (primaryProtection * PRIMARY_PROTECTION_WEIGHT
            + flankingProtection * FLANKING_PROTECTION_WEIGHT
            + distance * DISTANCE_WEIGHT
            + firingQuality * FIRING_QUALITY_WEIGHT
            + firingLane * PEEK_ANGLE_WEIGHT
            + dispersion * SQUAD_DISPERSION_WEIGHT)
            + fightability - blindPenalty;
    }

    private static float primaryProtection(CoverSearchInput input, CoverTerrainSnapshot terrain,
                                           CoverSearchInput.CoverCandidateSnapshot candidate) {
        Vec3 direction = input.protectionThreatPosition() != null
            ? input.protectionThreatPosition().subtract(candidate.position().getCenter())
            : input.protectionAwarenessDirection();
        if (direction == null || horizontalLengthSqr(direction) < 0.001) return candidate.quality();
        Vec3 normalized = horizontal(direction).normalize();
        int blocked = 0;
        for (int i = 0; i < 4; i++) {
            Vec3 origin = corner(candidate.position(), 0.8, i);
            Vec3 end = origin.add(normalized.scale(3.0));
            if (trace(terrain, origin, end).blocked()) blocked++;
        }
        return blocked >= 3 ? candidate.quality() : 0.0f;
    }

    private static float flankingProtection(CoverSearchInput.CoverCandidateSnapshot candidate,
                                             List<CoverSearchInput.ThreatSnapshot> threats) {
        if (threats.isEmpty()) return 1.0f;
        int protectedCount = 0;
        for (CoverSearchInput.ThreatSnapshot threat : threats) {
            Direction direction = directionFrom(threat.position().subtract(candidate.position().getCenter()));
            if (candidate.protectedDirections().contains(direction)) protectedCount++;
        }
        return (float) protectedCount / threats.size();
    }

    private static float firingQuality(CoverSearchInput.CoverCandidateSnapshot candidate, Vec3 threatDirection) {
        if (threatDirection == null || horizontalLengthSqr(threatDirection) < 0.001) return 0.5f;
        Set<Direction> protectedDirections = candidate.protectedDirections();
        if (protectedDirections.isEmpty()) return 0.0f;
        Direction opposite = directionFrom(horizontal(threatDirection)).getOpposite();
        if (protectedDirections.contains(opposite)) return 1.0f;
        int adjacent = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (protectedDirections.contains(direction) && adjacent(direction, opposite)) adjacent++;
        }
        return 0.5f + adjacent * 0.25f;
    }

    private static float firingLane(CoverSearchInput input, CoverTerrainSnapshot terrain,
                                    CoverSearchInput.CoverCandidateSnapshot candidate) {
        if (input.threatDirection() == null || horizontalLengthSqr(input.threatDirection()) < 0.001) return 0.0f;
        List<Vec3> origins = new ArrayList<>();
        origins.addAll(candidate.firingOrigins());
        float best = 0.0f;
        for (Vec3 origin : origins) {
            Vec3 activeThreatPosition = input.threats().isEmpty()
                ? null : input.threats().get(0).position();
            if (activeThreatPosition != null) {
                RayResult active = trace(terrain, origin, activeThreatPosition);
                if (active.clear && active.concealment < 1.0) return 1.0f;
            }
            float contact = contactCoverage(input, terrain, origin);
            best = Math.max(best, contact);
            best = Math.max(best, coneCoverage(terrain, origin, input.threatDirection()));
        }
        return best;
    }

    private static float contactCoverage(CoverSearchInput input, CoverTerrainSnapshot terrain, Vec3 origin) {
        float total = 0.0f;
        float reachable = 0.0f;
        boolean any = false;
        for (CoverSearchInput.FiringContactSnapshot contact : input.firingContacts()) {
            if (contact.freshness() <= 0.0f) continue;
            any = true;
            total += contact.freshness();
            RayResult result = trace(terrain, origin, contact.exposedPoint());
            if (result.clear && result.concealment < 1.0
                || !result.clear && remainingDistance(origin, contact.exposedPoint(), result) <= LAST_SEEN_CONTACT_TOLERANCE) {
                reachable += contact.freshness();
            }
        }
        return any && total > 0.0f ? reachable / total : 0.0f;
    }

    private static float coneCoverage(CoverTerrainSnapshot terrain, Vec3 origin, Vec3 direction) {
        Vec3 normalized = horizontal(direction).normalize();
        double total = 0.0;
        int valid = 0;
        for (int i = 0; i < 7; i++) {
            double angle = -30.0 + 60.0 * i / 6.0;
            Vec3 rayDirection = rotateY(normalized, angle);
            RayResult result = trace(terrain, origin, origin.add(rayDirection.scale(20.0)));
            double distance = result.clear ? 20.0 : result.blockedDistance;
            if (distance >= 5.0) {
                total += distance / 20.0;
                valid++;
            }
        }
        return valid == 0 ? 0.0f : (float) (total / 7.0);
    }

    private static float distanceScore(BlockPos position, BlockPos soldier) {
        // CoverFinder's NORMAL scorer intentionally uses its block-distance value directly.
        return (float) (1.0 - Math.min(position.distSqr(soldier) / 24.0, 1.0));
    }

    private static float dispersionScore(BlockPos position, List<BlockPos> occupied) {
        if (occupied.isEmpty()) return 0.5f;
        if (occupied.contains(position)) return 0.0f;
        double min = Double.MAX_VALUE;
        for (BlockPos other : occupied) min = Math.min(min, other.distSqr(position));
        if (min < 16.0) return 0.2f;
        if (min >= 36.0) return 0.7f;
        if (min >= 16.0) return 0.5f;
        return 0.3f;
    }

    private static Vec3 corner(BlockPos position, double height, int index) {
        double[] offsets = {-0.25, 0.25};
        return new Vec3(position.getX() + 0.5 + offsets[index % 2], position.getY() + height,
            position.getZ() + 0.5 + offsets[index / 2]);
    }

    private static Vec3 center(BlockPos position, double height) {
        return new Vec3(position.getX() + 0.5, position.getY() + height, position.getZ() + 0.5);
    }

    private static Direction directionFrom(Vec3 vector) {
        return com.stevesarmy.combat.cover.CoverFinder.getDirectionFromVector(vector);
    }

    private static boolean adjacent(Direction first, Direction second) {
        return first.getAxis() != second.getAxis();
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    private static double horizontalLengthSqr(Vec3 vector) {
        return vector.x * vector.x + vector.z * vector.z;
    }

    private static Vec3 rotateY(Vec3 vector, double angle) {
        return com.stevesarmy.combat.cover.CoverFinder.rotateVectorY(vector, angle);
    }

    private static double remainingDistance(Vec3 from, Vec3 to, RayResult result) {
        return from.distanceTo(to) - result.blockedDistance;
    }

    private static RayResult trace(CoverTerrainSnapshot terrain, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0e-7) return new RayResult(true, 0.0, Double.POSITIVE_INFINITY);
        Vec3 unit = delta.scale(1.0 / length);
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int endX = floor(to.x), endY = floor(to.y), endZ = floor(to.z);
        double concealment = 0.0;
        double t = 0.0;
        while (t <= length + 1.0e-7) {
            FiringPositionFinder.SnapshotCell cell = terrain.get(new BlockPos(x, y, z));
            if (cell == null || !cell.loaded()) return new RayResult(false, concealment, t);
            for (FiringPositionFinder.SnapshotBox box : cell.collision()) {
                if (intersects(box, from, to)) return new RayResult(false, concealment, t);
            }
            for (FiringPositionFinder.SnapshotBox box : cell.outline()) {
                if (intersects(box, from, to)) {
                    concealment = Math.min(1.0, concealment + cell.concealment());
                    break;
                }
            }
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
        return new RayResult(true, concealment, Double.POSITIVE_INFINITY);
    }

    private static boolean intersects(FiringPositionFinder.SnapshotBox box, Vec3 from, Vec3 to) {
        double tMin = 0.0, tMax = 1.0;
        double[] origin = {from.x, from.y, from.z};
        double[] delta = {to.x - from.x, to.y - from.y, to.z - from.z};
        double[] min = {box.minX(), box.minY(), box.minZ()};
        double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1.0e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
                continue;
            }
            double inverse = 1.0 / delta[axis];
            double near = (min[axis] - origin[axis]) * inverse;
            double far = (max[axis] - origin[axis]) * inverse;
            if (near > far) { double swap = near; near = far; far = swap; }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return false;
        }
        return true;
    }

    private static int floor(double value) { return (int) Math.floor(value); }
    private static int step(double value) { return value > 0.0 ? 1 : -1; }
    private static double boundary(double coordinate, double direction, int block) {
        if (Math.abs(direction) < 1.0e-7) return Double.POSITIVE_INFINITY;
        return ((direction > 0.0 ? block + 1.0 : block) - coordinate) / direction;
    }

    private record RayResult(boolean clear, double concealment, double blockedDistance) {
        private boolean blocked() { return !clear; }
    }
}

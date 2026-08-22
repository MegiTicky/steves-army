package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated firing-position evaluator for the machine gunner. Unlike the
 * rifleman cover scorer this measures how well a candidate position can
 * actually fire at the suppression area (raycast-verified LOS to exposure
 * points) and treats firing access as the dominant objective. Distance from the
 * support anchor is only a tie-breaker, and a position with no firing access is
 * never accepted: a blind cover is deliberately worse than an open prone lane.
 */
public final class FiringPositionFinder {
    public static final int SEARCH_RADIUS = 16;
    public static final float MIN_FIRING_ACCESS = 0.25f;

    private static final float PRONE_EYE_HEIGHT = 0.45f;
    private static final float STANDING_EYE_HEIGHT = 1.6f;
    private static final float TARGET_EXPOSURE_HEIGHT = 1.0f;
    private static final float GRID_EXPOSURE_HEIGHT = 1.35f;
    private static final int MAX_TARGETS = 12;
    // A strong firing score is not enough when the position is on an isolated
    // roof. Keep the score ordering, but search far enough down it to find a
    // reachable ground-level alternative.
    public static final int MAX_PATH_CHECK_CANDIDATES = 20;
    private static final int TARGET_GRID_STEP = 3;

    private static final double FIRING_WEIGHT = 0.60;
    private static final double PROTECTION_WEIGHT = 0.20;
    private static final double POSTURE_WEIGHT = 0.10;
    private static final double PROXIMITY_WEIGHT = 0.10;
    private static final float COVER_POSTURE_BONUS = 1.0f;
    private static final float OPEN_PRONE_POSTURE_BONUS = 0.8f;

    private static final double[] HALF_COVER_LATERAL_OFFSETS = {-0.28, 0.0, 0.28};
    private static final double[] HALF_COVER_OPENING_HEIGHTS = {0.12, 0.42};

    private FiringPositionFinder() {}

    public record CandidateDiagnostic(FiringPosition position, int rank,
                                      boolean pathExists, boolean canReach) {}

    public record EvaluationReport(int suppressionTargetCount, int coverTargetCount,
                                   boolean usedGridFallback, int coverPositionsChecked,
                                   int pronePositionsChecked, int rejectedForAccess,
                                   List<Vec3> suppressionTargets,
                                   List<FiringPosition> candidates,
                                   List<CandidateDiagnostic> pathChecks,
                                   FiringPosition selected) {}

    private record TargetGeneration(List<Vec3> targets, int coverTargetCount,
                                    boolean usedGridFallback) {}

    private record CandidateCollection(List<FiringPosition> positions, int positionsChecked,
                                       int rejectedForAccess) {}

    /**
     * Evaluates cover and open-prone candidates around the support anchor and
     * returns the best firing position, or null when nothing can actually fire
     * at the suppression area.
     */
    @Nullable
    public static FiringPosition findBest(MachineGunnerEntity mg, BlockPos suppressionCenter, BlockPos supportAnchor) {
        return evaluate(mg, suppressionCenter, supportAnchor).selected();
    }

    /** Runs firing-position selection and retains stage data for read-only diagnostics. */
    public static EvaluationReport evaluate(MachineGunnerEntity mg, BlockPos suppressionCenter,
                                             BlockPos supportAnchor) {
        if (suppressionCenter == null || supportAnchor == null) {
            return emptyReport();
        }
        Level level = mg.level();
        CoverFinder finder = new CoverFinder(level);
        TargetGeneration targetGeneration = generateSuppressionTargets(
            level, finder, suppressionCenter, SoldierEntity.SUPPRESSION_ZONE_RADIUS);
        List<Vec3> targets = targetGeneration.targets();
        if (targets.isEmpty()) {
            return new EvaluationReport(0, targetGeneration.coverTargetCount(),
                targetGeneration.usedGridFallback(), 0, 0, 0, targets,
                List.of(), List.of(), null);
        }

        CandidateCollection coverCandidates = collectCoverCandidates(
            level, finder, mg, supportAnchor, targets);
        CandidateCollection proneCandidates = collectOpenProneCandidates(
            level, mg, supportAnchor, targets);
        List<FiringPosition> candidates = new ArrayList<>(coverCandidates.positions());
        candidates.addAll(proneCandidates.positions());

        if (candidates.isEmpty()) {
            return new EvaluationReport(targets.size(), targetGeneration.coverTargetCount(),
                targetGeneration.usedGridFallback(), coverCandidates.positionsChecked(),
                proneCandidates.positionsChecked(), coverCandidates.rejectedForAccess()
                    + proneCandidates.rejectedForAccess(), targets, List.of(), List.of(), null);
        }

        candidates.sort((a, b) -> Float.compare(b.score(), a.score()));
        FiringPosition best = null;
        List<CandidateDiagnostic> pathChecks = new ArrayList<>();
        for (int candidateIndex = 0;
             candidateIndex < candidates.size() && candidateIndex < MAX_PATH_CHECK_CANDIDATES;
             candidateIndex++) {
            FiringPosition candidate = candidates.get(candidateIndex);
            if (candidate == null) {
                break;
            }
            boolean samePosition = candidate.destination().equals(mg.blockPosition());
            Path path = samePosition ? null : candidate.posture() == FiringPosition.FiringPosture.OPEN_PRONE
                ? mg.getNavigation().createPath(candidate.destination(), 0)
                : mg.getNavigation().createPath(candidate.destination().getX() + 0.5,
                    candidate.destination().getY(), candidate.destination().getZ() + 0.5, 0);
            boolean pathExists = samePosition || path != null;
            boolean canReach = samePosition || isPathReachableForCandidate(
                level, finder, candidate, path);
            pathChecks.add(new CandidateDiagnostic(candidate, candidateIndex + 1, pathExists, canReach));
            if (canReach) {
                best = candidate;
                break;
            }
        }

        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[FiringPosition] MG {} targets={} candidates={} best={}",
                mg.getId(), targets.size(), candidates.size(),
                best != null ? best.destination() : "none");
        }
        return new EvaluationReport(targets.size(), targetGeneration.coverTargetCount(),
            targetGeneration.usedGridFallback(), coverCandidates.positionsChecked(),
            proneCandidates.positionsChecked(), coverCandidates.rejectedForAccess()
            + proneCandidates.rejectedForAccess(), targets, candidates, pathChecks, best);
    }

    private static boolean isPathReachableForCandidate(Level level, CoverFinder finder,
                                                        FiringPosition candidate, Path path) {
        if (path == null || !path.canReach() || path.getNodeCount() == 0) {
            return false;
        }
        if (candidate.posture() == FiringPosition.FiringPosture.OPEN_PRONE) {
            return true;
        }
        CoverPoint cover = finder.evaluatePosition(candidate.destination(), null);
        return cover != null
            && path.getNode(path.getNodeCount() - 1).asBlockPos().equals(cover.getPosition());
    }

    private static EvaluationReport emptyReport() {
        return new EvaluationReport(0, 0, false, 0, 0, 0,
            List.of(), List.of(), List.of(), null);
    }

    /** Evaluates the firing access from an already selected destination. */
    public static float evaluateFiringAccess(SoldierEntity soldier, BlockPos suppressionCenter,
                                             BlockPos destination, FiringPosition.FiringPosture posture) {
        if (suppressionCenter == null || destination == null || posture == null) {
            return 0.0f;
        }
        List<Vec3> targets = generateSuppressionTargets(
            soldier.level(), new CoverFinder(soldier.level()), suppressionCenter,
            SoldierEntity.SUPPRESSION_ZONE_RADIUS).targets();
        if (targets.isEmpty()) {
            return 0.0f;
        }
        double eyeHeight = posture == FiringPosition.FiringPosture.OPEN_PRONE
            ? PRONE_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        Vec3 eye = new Vec3(destination.getX() + 0.5, destination.getY() + eyeHeight,
            destination.getZ() + 0.5);
        return computeFiringAccess(soldier.level(), eye, targets, soldier);
    }

    /**
     * Returns the same exposure samples used by firing-position evaluation that
     * are visible from the soldier's current eye. Ping suppression consumes this
     * list, preventing it from firing at a stale pre-movement sample set.
     */
    public static List<Vec3> findVisibleSuppressionTargets(SoldierEntity soldier, BlockPos suppressionCenter) {
        if (suppressionCenter == null) {
            return List.of();
        }
        Level level = soldier.level();
        List<Vec3> targets = generateSuppressionTargets(
            level, new CoverFinder(level), suppressionCenter, SoldierEntity.SUPPRESSION_ZONE_RADIUS).targets();
        Vec3 eye = soldier.getEyePosition();
        List<Vec3> visible = new ArrayList<>();
        for (Vec3 target : targets) {
            if (VisibilityRay.traceIgnoringSmoke(level, eye, target, soldier).hasContact()) {
                visible.add(target);
            }
        }
        return visible;
    }

    /** Generates candidate-independent exposure points in the suppression zone. */
    private static TargetGeneration generateSuppressionTargets(Level level, CoverFinder finder,
                                                               BlockPos center, double radius) {
        List<Vec3> targets = new ArrayList<>();
        for (CoverPoint cover : finder.findCoverPoints(center, (int) Math.ceil(radius))) {
            if (targets.size() >= MAX_TARGETS) {
                break;
            }
            if (cover.getType() == CoverType.HALF) {
                addHalfCoverTargets(level, cover, targets);
            } else {
                for (Direction peekDir : Direction.Plane.HORIZONTAL) {
                    if (cover.getProtectedDirections().contains(peekDir)) {
                        continue;
                    }
                    BlockPos peekPos = cover.getPosition().relative(peekDir);
                    if (!isValidTargetCell(level, peekPos)) {
                        continue;
                    }
                    targets.add(new Vec3(peekPos.getX() + 0.5,
                        peekPos.getY() + TARGET_EXPOSURE_HEIGHT, peekPos.getZ() + 0.5));
                    if (targets.size() >= MAX_TARGETS) {
                        break;
                    }
                }
            }
        }
        int coverTargetCount = targets.size();
        boolean usedGridFallback = targets.isEmpty();
        if (usedGridFallback) {
            addGridFallbackTargets(center, radius, targets);
        }
        return new TargetGeneration(targets, coverTargetCount, usedGridFallback);
    }

    private static void addHalfCoverTargets(Level level, CoverPoint cover, List<Vec3> targets) {
        for (Direction wallDirection : cover.getProtectedDirections()) {
            BlockPos coverBlock = cover.getPosition().relative(wallDirection);
            VoxelShape shape = level.getBlockState(coverBlock).getCollisionShape(level, coverBlock);
            if (shape.isEmpty()) {
                continue;
            }
            double coverTop = coverBlock.getY() + shape.max(Direction.Axis.Y);
            Direction.Axis lateralAxis = wallDirection.getAxis() == Direction.Axis.Z
                ? Direction.Axis.X : Direction.Axis.Z;
            for (double lateralOffset : HALF_COVER_LATERAL_OFFSETS) {
                double x = coverBlock.getX() + 0.5;
                double z = coverBlock.getZ() + 0.5;
                if (lateralAxis == Direction.Axis.X) {
                    x += lateralOffset;
                } else {
                    z += lateralOffset;
                }
                for (double openingHeight : HALF_COVER_OPENING_HEIGHTS) {
                    targets.add(new Vec3(x, coverTop + openingHeight, z));
                    if (targets.size() >= MAX_TARGETS) {
                        return;
                    }
                }
            }
        }
    }

    private static void addGridFallbackTargets(BlockPos center, double radius, List<Vec3> targets) {
        for (int dx = -(int) radius; dx <= (int) radius; dx += TARGET_GRID_STEP) {
            for (int dz = -(int) radius; dz <= (int) radius; dz += TARGET_GRID_STEP) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos p = center.offset(dx, 0, dz);
                targets.add(new Vec3(p.getX() + 0.5, p.getY() + GRID_EXPOSURE_HEIGHT, p.getZ() + 0.5));
                if (targets.size() >= MAX_TARGETS) {
                    return;
                }
            }
        }
    }

    private static CandidateCollection collectCoverCandidates(Level level, CoverFinder finder,
                                                              SoldierEntity mg, BlockPos anchor,
                                                              List<Vec3> targets) {
        List<FiringPosition> out = new ArrayList<>();
        int positionsChecked = 0;
        int rejectedForAccess = 0;
        for (CoverPoint cover : finder.findCoverPoints(anchor, SEARCH_RADIUS)) {
            positionsChecked++;
            BlockPos pos = cover.getPosition();
            Vec3 eye = new Vec3(pos.getX() + 0.5, pos.getY() + STANDING_EYE_HEIGHT, pos.getZ() + 0.5);
            float access = computeFiringAccess(level, eye, targets, mg);
            if (access < MIN_FIRING_ACCESS) {
                rejectedForAccess++;
                continue;
            }
            float protection = protectionScore(level, cover);
            out.add(new FiringPosition(pos, FiringPosition.FiringPosture.COVER_PEEK, access, protection,
                score(access, protection, COVER_POSTURE_BONUS, pos, anchor)));
        }
        return new CandidateCollection(out, positionsChecked, rejectedForAccess);
    }

    private static CandidateCollection collectOpenProneCandidates(Level level, SoldierEntity mg,
                                                                  BlockPos anchor, List<Vec3> targets) {
        List<FiringPosition> out = new ArrayList<>();
        int positionsChecked = 0;
        int rejectedForAccess = 0;
        int radius = SEARCH_RADIUS;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                BlockPos ground = level.getHeightmapPos(
                    Heightmap.Types.WORLD_SURFACE, anchor.offset(x, 0, z));
                BlockPos pos = ground.above();
                if (!isProneTerrainValid(level, pos)) {
                    continue;
                }
                positionsChecked++;
                Vec3 eye = new Vec3(pos.getX() + 0.5, pos.getY() + PRONE_EYE_HEIGHT, pos.getZ() + 0.5);
                float access = computeFiringAccess(level, eye, targets, mg);
                if (access < MIN_FIRING_ACCESS) {
                    rejectedForAccess++;
                    continue;
                }
                float protection = adjacentCover(level, pos) ? 0.15f : 0.0f;
                out.add(new FiringPosition(pos, FiringPosition.FiringPosture.OPEN_PRONE, access, protection,
                    score(access, protection, OPEN_PRONE_POSTURE_BONUS, pos, anchor)));
            }
        }
        return new CandidateCollection(out, positionsChecked, rejectedForAccess);
    }

    private static float score(float access, float protection, float postureBonus,
                               BlockPos pos, BlockPos anchor) {
        return (float) (FIRING_WEIGHT * access + PROTECTION_WEIGHT * protection
            + POSTURE_WEIGHT * postureBonus + PROXIMITY_WEIGHT * proximityScore(pos, anchor));
    }

    private static float computeFiringAccess(Level level, Vec3 eye, List<Vec3> targets, SoldierEntity observer) {
        if (targets.isEmpty()) {
            return 0.0f;
        }
        int clear = 0;
        for (Vec3 target : targets) {
            if (VisibilityRay.traceIgnoringSmoke(level, eye, target, observer).hasContact()) {
                clear++;
            }
        }
        return (float) clear / targets.size();
    }

    private static float protectionScore(Level level, CoverPoint cover) {
        if (cover.getType() == CoverType.FULL) {
            return 1.0f;
        }
        if (cover.getType() == CoverType.HALF) {
            return 0.5f;
        }
        if (cover.getType() == CoverType.CONCEALMENT) {
            return 0.25f;
        }
        return adjacentCover(level, cover.getPosition()) ? 0.15f : 0.0f;
    }

    private static float proximityScore(BlockPos pos, BlockPos anchor) {
        double dist = Math.sqrt(pos.distSqr(anchor));
        return (float) (1.0 - Math.min(1.0, dist / SEARCH_RADIUS));
    }

    private static boolean isReachable(SoldierEntity soldier, BlockPos pos) {
        Path path = soldier.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private static boolean isValidTargetCell(Level level, BlockPos pos) {
        return level.isLoaded(pos)
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.below()).isSolid();
    }

    private static boolean isProneTerrainValid(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos.below()).isSolid()
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
            && level.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean adjacentCover(Level level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(pos.relative(d)).getCollisionShape(level, pos.relative(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}

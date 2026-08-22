package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_ACTIVE_TARGET_SAMPLES = 4;
    private static final int MAX_LAST_SEEN_SAMPLES = 6;
    private static final int MAX_PEEK_TARGET_SAMPLES = 12;
    private static final int MAX_TOTAL_TARGET_SAMPLES = 24;
    private static final int MAX_PEEK_CONTEXT_CENTERS = 3;
    private static final int MAX_FULL_ACCESS_CANDIDATES = 120;
    private static final int MAX_COARSE_TARGET_SAMPLES = 6;
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
    private static final float ACTIVE_TARGET_WEIGHT = 1.0f;
    private static final float LAST_SEEN_WEIGHT = 0.70f;
    private static final float PEEK_TARGET_WEIGHT = 0.25f;
    private static final float GRID_FALLBACK_WEIGHT = 0.10f;
    private static final float MIN_MEANINGFUL_ACCESS = 0.20f;
    private static final float MIN_PEEK_COVERAGE = 0.35f;

    private static final double[] HALF_COVER_LATERAL_OFFSETS = {-0.28, 0.0, 0.28};
    private static final double[] HALF_COVER_OPENING_HEIGHTS = {0.12, 0.42};

    private FiringPositionFinder() {}

    public enum TargetCategory {
        ACTIVE_TARGET,
        LAST_SEEN,
        POTENTIAL_PEEK,
        GRID_FALLBACK
    }

    public record TargetSample(Vec3 position, TargetCategory category, float weight, float freshness) {}

    public record AccessDiagnostic(float access, float activeCoverage, float lastSeenCoverage,
                                   float peekCoverage, int activeVisible, int lastSeenVisible,
                                   int peekVisible, boolean meaningful) {}

    public record CandidateDiagnostic(FiringPosition position, int rank,
                                      boolean pathExists, boolean canReach,
                                      AccessDiagnostic access) {}

    public record EvaluationReport(int suppressionTargetCount, int coverTargetCount,
                                   boolean usedGridFallback, int coverPositionsChecked,
                                   int pronePositionsChecked, int rejectedForAccess,
                                   List<TargetSample> suppressionTargets,
                                   int activeTargetCount, int lastSeenCount, int peekTargetCount,
                                   List<FiringPosition> candidates,
                                   List<CandidateDiagnostic> pathChecks,
                                   FiringPosition selected) {}

    private record TargetGeneration(List<TargetSample> targets, int coverTargetCount,
                                    boolean usedGridFallback, int activeTargetCount,
                                    int lastSeenCount, int peekTargetCount) {}

    private record CandidateCollection(List<FiringPosition> positions, int positionsChecked,
                                       int rejectedForAccess) {}

    private record CandidateGeometry(BlockPos position, FiringPosition.FiringPosture posture,
                                     float protection) {}

    private record CoarseCandidate(CandidateGeometry geometry, float access) {}

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
            mg, level, finder, suppressionCenter, SoldierEntity.SUPPRESSION_ZONE_RADIUS);
        List<TargetSample> targets = targetGeneration.targets();
        if (targets.isEmpty()) {
            return new EvaluationReport(0, targetGeneration.coverTargetCount(),
                targetGeneration.usedGridFallback(), 0, 0, 0, targets,
                targetGeneration.activeTargetCount(), targetGeneration.lastSeenCount(),
                targetGeneration.peekTargetCount(),
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
                    + proneCandidates.rejectedForAccess(), targets,
                targetGeneration.activeTargetCount(), targetGeneration.lastSeenCount(),
                targetGeneration.peekTargetCount(), List.of(), List.of(), null);
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
            AccessDiagnostic access = evaluateAccess(level, candidateEye(candidate), targets, mg);
            boolean canReach = access.meaningful() && (samePosition || isPathReachableForCandidate(
                level, finder, candidate, path));
            pathChecks.add(new CandidateDiagnostic(candidate, candidateIndex + 1, pathExists, canReach, access));
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
            + proneCandidates.rejectedForAccess(), targets,
            targetGeneration.activeTargetCount(), targetGeneration.lastSeenCount(),
            targetGeneration.peekTargetCount(), candidates, pathChecks, best);
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
            List.of(), 0, 0, 0, List.of(), List.of(), null);
    }

    /** Evaluates the firing access from an already selected destination. */
    public static float evaluateFiringAccess(SoldierEntity soldier, BlockPos suppressionCenter,
                                              BlockPos destination, FiringPosition.FiringPosture posture) {
        if (suppressionCenter == null || destination == null || posture == null) {
            return 0.0f;
        }
        List<TargetSample> targets = generateSuppressionTargets(
            soldier instanceof MachineGunnerEntity mg ? mg : null, soldier.level(),
            new CoverFinder(soldier.level()), suppressionCenter,
            SoldierEntity.SUPPRESSION_ZONE_RADIUS).targets();
        if (targets.isEmpty()) {
            return 0.0f;
        }
        double eyeHeight = posture == FiringPosition.FiringPosture.OPEN_PRONE
            ? PRONE_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        Vec3 eye = new Vec3(destination.getX() + 0.5, destination.getY() + eyeHeight,
            destination.getZ() + 0.5);
        AccessDiagnostic access = evaluateAccess(soldier.level(), eye, targets, soldier);
        return access.meaningful() ? access.access() : 0.0f;
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
        List<TargetSample> targets = generateSuppressionTargets(
            soldier instanceof MachineGunnerEntity mg ? mg : null, soldier.level(),
            new CoverFinder(soldier.level()), suppressionCenter,
            SoldierEntity.SUPPRESSION_ZONE_RADIUS).targets();
        Vec3 eye = soldier.getEyePosition();
        List<Vec3> visible = new ArrayList<>();
        for (TargetSample target : targets) {
            if (VisibilityRay.traceIgnoringSmoke(level, eye, target.position(), soldier).hasContact()) {
                visible.add(target.position());
            }
        }
        return visible;
    }

    /** Generates candidate-independent exposure points in the suppression zone. */
    private static TargetGeneration generateSuppressionTargets(@Nullable MachineGunnerEntity mg,
                                                               Level level, CoverFinder finder,
                                                               BlockPos center, double radius) {
        List<TargetSample> targets = new ArrayList<>();
        addActiveTargetSamples(mg, targets);
        addLastSeenSamples(mg, level, targets);
        List<BlockPos> threatCenters = new ArrayList<>();
        threatCenters.add(center);
        if (mg != null && mg.getTarget() != null && mg.getTarget().isAlive()) {
            threatCenters.add(mg.getTarget().blockPosition());
        }
        for (TargetSample sample : targets) {
            if (sample.category() == TargetCategory.LAST_SEEN) {
                threatCenters.add(BlockPos.containing(sample.position()));
            }
        }
        int peekBefore = targets.size();
        for (int i = 0; i < threatCenters.size() && i < MAX_PEEK_CONTEXT_CENTERS; i++) {
            BlockPos threatCenter = threatCenters.get(i);
            addPotentialPeekSamples(level, finder, threatCenter, radius, targets);
            if (targets.size() - peekBefore >= MAX_PEEK_TARGET_SAMPLES) break;
        }
        int coverTargetCount = targets.size() - peekBefore;
        boolean usedGridFallback = targets.isEmpty();
        if (usedGridFallback) {
            addGridFallbackTargets(center, radius, targets);
        }
        deduplicateTargets(targets);
        int activeCount = countTargets(targets, TargetCategory.ACTIVE_TARGET);
        int lastSeenCount = countTargets(targets, TargetCategory.LAST_SEEN);
        int peekCount = countTargets(targets, TargetCategory.POTENTIAL_PEEK);
        if (targets.size() > MAX_TOTAL_TARGET_SAMPLES) {
            targets = new ArrayList<>(targets.subList(0, MAX_TOTAL_TARGET_SAMPLES));
        }
        activeCount = countTargets(targets, TargetCategory.ACTIVE_TARGET);
        lastSeenCount = countTargets(targets, TargetCategory.LAST_SEEN);
        peekCount = countTargets(targets, TargetCategory.POTENTIAL_PEEK);
        return new TargetGeneration(targets, coverTargetCount, usedGridFallback,
            activeCount, lastSeenCount, peekCount);
    }

    private static void addActiveTargetSamples(@Nullable MachineGunnerEntity mg,
                                               List<TargetSample> targets) {
        if (mg == null) return;
        LivingEntity target = mg.getTarget();
        if (target == null || !target.isAlive() || target.level() != mg.level()) return;
        float height = target.getBbHeight();
        float width = target.getBbWidth();
        Vec3 base = target.position();
        addTarget(targets, new Vec3(base.x, base.y + height * 0.92, base.z),
            TargetCategory.ACTIVE_TARGET, ACTIVE_TARGET_WEIGHT, 1.0f);
        addTarget(targets, new Vec3(base.x, base.y + height * 0.68, base.z),
            TargetCategory.ACTIVE_TARGET, ACTIVE_TARGET_WEIGHT, 1.0f);
        if (width > 0.4f) {
            addTarget(targets, new Vec3(base.x - width * 0.30, base.y + height * 0.55, base.z),
                TargetCategory.ACTIVE_TARGET, ACTIVE_TARGET_WEIGHT, 1.0f);
            addTarget(targets, new Vec3(base.x + width * 0.30, base.y + height * 0.55, base.z),
                TargetCategory.ACTIVE_TARGET, ACTIVE_TARGET_WEIGHT, 1.0f);
        }
    }

    private static void addLastSeenSamples(@Nullable MachineGunnerEntity mg, Level level,
                                           List<TargetSample> targets) {
        if (mg == null || !(level instanceof ServerLevel serverLevel) || mg.getSquadId() == null) return;
        SquadData squad = SquadManager.get(serverLevel).getSquadById(mg.getSquadId()).orElse(null);
        if (squad == null) return;
        long now = level.getGameTime();
        List<SquadThreatIntel.ThreatKnowledge> threats = squad.getThreatIntel().getAllThreats();
        threats.sort(Comparator.comparingDouble((SquadThreatIntel.ThreatKnowledge t) -> -t.accuracy));
        for (SquadThreatIntel.ThreatKnowledge threat : threats) {
            if (!threat.isAlive || threat.lastKnownPosition == null
                || countTargets(targets, TargetCategory.LAST_SEEN) >= MAX_LAST_SEEN_SAMPLES) {
                continue;
            }
            long age = Math.max(0L, now - threat.lastSeenTime);
            float freshness = Math.max(0.1f, 1.0f - age / 200.0f);
            Vec3 position = threat.lastVisibleAimPoint != null
                ? threat.lastVisibleAimPoint
                : threat.lastKnownPosition.getCenter().add(0, 1.0, 0);
            addTarget(targets, position, TargetCategory.LAST_SEEN,
                LAST_SEEN_WEIGHT * freshness, freshness);
        }
    }

    private static void addPotentialPeekSamples(Level level, CoverFinder finder, BlockPos center,
                                                 double radius, List<TargetSample> targets) {
        if (countTargets(targets, TargetCategory.POTENTIAL_PEEK) >= MAX_PEEK_TARGET_SAMPLES) return;
        for (CoverPoint cover : finder.findCoverPoints(center, (int) Math.ceil(radius))) {
            if (countTargets(targets, TargetCategory.POTENTIAL_PEEK) >= MAX_PEEK_TARGET_SAMPLES) {
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
                    addTarget(targets, new Vec3(peekPos.getX() + 0.5,
                        peekPos.getY() + TARGET_EXPOSURE_HEIGHT, peekPos.getZ() + 0.5),
                        TargetCategory.POTENTIAL_PEEK, PEEK_TARGET_WEIGHT, 1.0f);
                    if (countTargets(targets, TargetCategory.POTENTIAL_PEEK) >= MAX_PEEK_TARGET_SAMPLES) {
                        break;
                    }
                }
            }
        }
    }

    private static void addHalfCoverTargets(Level level, CoverPoint cover, List<TargetSample> targets) {
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
                    addTarget(targets, new Vec3(x, coverTop + openingHeight, z),
                        TargetCategory.POTENTIAL_PEEK, PEEK_TARGET_WEIGHT, 1.0f);
                    if (countTargets(targets, TargetCategory.POTENTIAL_PEEK) >= MAX_PEEK_TARGET_SAMPLES) {
                        return;
                    }
                }
            }
        }
    }

    private static void addGridFallbackTargets(BlockPos center, double radius, List<TargetSample> targets) {
        for (int dx = -(int) radius; dx <= (int) radius; dx += TARGET_GRID_STEP) {
            for (int dz = -(int) radius; dz <= (int) radius; dz += TARGET_GRID_STEP) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos p = center.offset(dx, 0, dz);
                addTarget(targets, new Vec3(p.getX() + 0.5, p.getY() + GRID_EXPOSURE_HEIGHT, p.getZ() + 0.5),
                    TargetCategory.GRID_FALLBACK, GRID_FALLBACK_WEIGHT, 1.0f);
                if (countTargets(targets, TargetCategory.GRID_FALLBACK) >= MAX_TARGETS) {
                    return;
                }
            }
        }
    }

    private static CandidateCollection collectCoverCandidates(Level level, CoverFinder finder,
                                                               SoldierEntity mg, BlockPos anchor,
                                                               List<TargetSample> targets) {
        List<CandidateGeometry> geometries = new ArrayList<>();
        int positionsChecked = 0;
        for (CoverPoint cover : finder.findCoverPoints(anchor, SEARCH_RADIUS)) {
            positionsChecked++;
            BlockPos pos = cover.getPosition();
            geometries.add(new CandidateGeometry(pos, FiringPosition.FiringPosture.COVER_PEEK,
                protectionScore(level, cover)));
        }
        return evaluateCandidateGeometries(level, mg, anchor, targets, geometries,
            positionsChecked);
    }

    private static CandidateCollection collectOpenProneCandidates(Level level, SoldierEntity mg,
                                                                   BlockPos anchor, List<TargetSample> targets) {
        List<CandidateGeometry> geometries = new ArrayList<>();
        int positionsChecked = 0;
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
                float protection = adjacentCover(level, pos) ? 0.15f : 0.0f;
                geometries.add(new CandidateGeometry(pos, FiringPosition.FiringPosture.OPEN_PRONE,
                    protection));
            }
        }
        return evaluateCandidateGeometries(level, mg, anchor, targets, geometries,
            positionsChecked);
    }

    private static CandidateCollection evaluateCandidateGeometries(Level level, SoldierEntity mg,
                                                                    BlockPos anchor,
                                                                    List<TargetSample> targets,
                                                                    List<CandidateGeometry> geometries,
                                                                    int positionsChecked) {
        if (geometries.isEmpty()) {
            return new CandidateCollection(List.of(), positionsChecked, 0);
        }
        List<TargetSample> coarseTargets = selectCoarseTargets(targets);
        List<CoarseCandidate> coarseCandidates = new ArrayList<>(geometries.size());
        for (CandidateGeometry geometry : geometries) {
            coarseCandidates.add(new CoarseCandidate(geometry,
                evaluateAccess(level, candidateEye(geometry.position(), geometry.posture()),
                    coarseTargets, mg).access()));
        }
        coarseCandidates.sort(Comparator.comparingDouble(CoarseCandidate::access).reversed());

        List<FiringPosition> out = new ArrayList<>();
        int fullChecks = Math.min(MAX_FULL_ACCESS_CANDIDATES, coarseCandidates.size());
        for (int i = 0; i < fullChecks; i++) {
            CandidateGeometry geometry = coarseCandidates.get(i).geometry();
            AccessDiagnostic access = evaluateAccess(level,
                candidateEye(geometry.position(), geometry.posture()), targets, mg);
            if (access.access() < MIN_FIRING_ACCESS || !access.meaningful()) {
                continue;
            }
            out.add(new FiringPosition(geometry.position(), geometry.posture(), access.access(),
                geometry.protection(), score(access.access(), geometry.protection(),
                    geometry.posture() == FiringPosition.FiringPosture.OPEN_PRONE
                        ? OPEN_PRONE_POSTURE_BONUS : COVER_POSTURE_BONUS,
                    geometry.position(), anchor)));
        }
        return new CandidateCollection(out, positionsChecked,
            fullChecks - out.size());
    }

    private static List<TargetSample> selectCoarseTargets(List<TargetSample> targets) {
        if (targets.size() <= MAX_COARSE_TARGET_SAMPLES) return targets;
        List<TargetSample> coarse = new ArrayList<>();
        for (TargetCategory category : TargetCategory.values()) {
            for (TargetSample target : targets) {
                if (target.category() == category) {
                    coarse.add(target);
                    break;
                }
            }
        }
        for (TargetSample target : targets) {
            if (coarse.size() >= MAX_COARSE_TARGET_SAMPLES) break;
            if (!coarse.contains(target)) coarse.add(target);
        }
        return coarse;
    }

    private static float score(float access, float protection, float postureBonus,
                               BlockPos pos, BlockPos anchor) {
        return (float) (FIRING_WEIGHT * access + PROTECTION_WEIGHT * protection
            + POSTURE_WEIGHT * postureBonus + PROXIMITY_WEIGHT * proximityScore(pos, anchor));
    }

    private static AccessDiagnostic evaluateAccess(Level level, Vec3 eye, List<TargetSample> targets,
                                                   SoldierEntity observer) {
        if (targets.isEmpty()) return new AccessDiagnostic(0, 0, 0, 0, 0, 0, 0, false);
        int active = 0, lastSeen = 0, peek = 0;
        float activeVisibleWeight = 0.0f;
        float activeWeight = 0.0f;
        float lastSeenVisibleWeight = 0.0f;
        float lastSeenWeight = 0.0f;
        float peekVisibleWeight = 0.0f;
        float peekWeight = 0.0f;
        for (TargetSample target : targets) {
            boolean visible = VisibilityRay.traceIgnoringSmoke(level, eye, target.position(), observer).hasContact();
            switch (target.category()) {
                case ACTIVE_TARGET -> {
                    activeWeight += target.weight();
                    if (visible) activeVisibleWeight += target.weight();
                    if (visible) active++;
                }
                case LAST_SEEN -> {
                    lastSeenWeight += target.weight();
                    if (visible) lastSeenVisibleWeight += target.weight();
                    if (visible) lastSeen++;
                }
                case POTENTIAL_PEEK, GRID_FALLBACK -> {
                    peekWeight += target.weight();
                    if (visible) peekVisibleWeight += target.weight();
                    if (visible) peek++;
                }
            }
        }
        float activeCoverage = weightedCategoryCoverage(activeVisibleWeight, activeWeight);
        float lastSeenCoverage = weightedCategoryCoverage(lastSeenVisibleWeight, lastSeenWeight);
        float peekCoverage = weightedCategoryCoverage(peekVisibleWeight, peekWeight);
        float categoryWeight = 0.0f;
        float weightedCoverage = 0.0f;
        if (countTargets(targets, TargetCategory.ACTIVE_TARGET) > 0) {
            categoryWeight += ACTIVE_TARGET_WEIGHT;
            weightedCoverage += activeCoverage * ACTIVE_TARGET_WEIGHT;
        }
        if (countTargets(targets, TargetCategory.LAST_SEEN) > 0) {
            categoryWeight += LAST_SEEN_WEIGHT;
            weightedCoverage += lastSeenCoverage * LAST_SEEN_WEIGHT;
        }
        if (countPeekTargets(targets) > 0) {
            categoryWeight += PEEK_TARGET_WEIGHT;
            weightedCoverage += peekCoverage * PEEK_TARGET_WEIGHT;
        }
        boolean meaningful = activeCoverage >= 0.25f || lastSeen > 0 || peekCoverage >= MIN_PEEK_COVERAGE;
        float access = categoryWeight > 0 ? weightedCoverage / categoryWeight : 0;
        return new AccessDiagnostic(access, activeCoverage, lastSeenCoverage, peekCoverage,
            active, lastSeen, peek, meaningful && access >= MIN_MEANINGFUL_ACCESS);
    }

    private static float weightedCategoryCoverage(float visibleWeight, float totalWeight) {
        return totalWeight > 0.0f ? visibleWeight / totalWeight : 0.0f;
    }

    private static Vec3 candidateEye(FiringPosition candidate) {
        return candidateEye(candidate.destination(), candidate.posture());
    }

    private static Vec3 candidateEye(BlockPos position, FiringPosition.FiringPosture posture) {
        double eyeHeight = posture == FiringPosition.FiringPosture.OPEN_PRONE
            ? PRONE_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        return new Vec3(position.getX() + 0.5, position.getY() + eyeHeight,
            position.getZ() + 0.5);
    }

    private static void addTarget(List<TargetSample> targets, Vec3 position, TargetCategory category,
                                  float weight, float freshness) {
        if (position == null || targets.size() >= MAX_TOTAL_TARGET_SAMPLES) return;
        targets.add(new TargetSample(position, category, weight, freshness));
    }

    private static int countTargets(List<TargetSample> targets, TargetCategory category) {
        int count = 0;
        for (TargetSample target : targets) {
            if (target.category() == category) count++;
        }
        return count;
    }

    private static int countPeekTargets(List<TargetSample> targets) {
        return countTargets(targets, TargetCategory.POTENTIAL_PEEK)
            + countTargets(targets, TargetCategory.GRID_FALLBACK);
    }

    private static void deduplicateTargets(List<TargetSample> targets) {
        Map<String, TargetSample> unique = new LinkedHashMap<>();
        for (TargetSample target : targets) {
            String key = target.category() + ":" + Math.round(target.position().x * 10)
                + ":" + Math.round(target.position().y * 10)
                + ":" + Math.round(target.position().z * 10);
            unique.putIfAbsent(key, target);
        }
        targets.clear();
        targets.addAll(unique.values());
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

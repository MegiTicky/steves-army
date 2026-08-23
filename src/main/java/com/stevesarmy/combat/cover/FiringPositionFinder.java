package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.debug.PerformanceMetrics;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static final double PROTECTION_WEIGHT = 0.30;
    private static final double POSTURE_WEIGHT = 0.10;
    private static final double PROXIMITY_WEIGHT = 0.05;
    private static final float COVER_POSTURE_BONUS = 1.0f;
    private static final float OPEN_PRONE_POSTURE_BONUS = 0.8f;
    private static final float ACTIVE_TARGET_WEIGHT = 1.0f;
    private static final float LAST_SEEN_WEIGHT = 0.70f;
    private static final float PEEK_TARGET_WEIGHT = 0.25f;
    private static final float GRID_FALLBACK_WEIGHT = 0.10f;
    private static final float MIN_MEANINGFUL_ACCESS = 0.20f;
    private static final float MIN_PEEK_COVERAGE = 0.35f;
    public static final float MIN_COVER_PROTECTION = 0.45f;
    private static final float OPEN_PRONE_PROTECTION = 0.45f;

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

    /** Access to confirmed enemy information only, excluding speculative peek samples. */
    public record ConfirmedFiringAccess(float access, boolean hasConfirmedTargets) {}

    public record CandidateDiagnostic(FiringPosition position, int rank,
                                      boolean pathExists, boolean canReach,
                                      AccessDiagnostic access) {}

    /** Protection data for accepted and rejected candidate geometry. */
    public record ProtectionDiagnostic(BlockPos position, FiringPosition.FiringPosture posture,
                                       float protection, boolean directionallyProtected) {}

    public record EvaluationReport(int suppressionTargetCount, int coverTargetCount,
                                   boolean usedGridFallback, int coverPositionsChecked,
                                   int pronePositionsChecked, int rejectedForAccess,
                                    List<TargetSample> suppressionTargets,
                                    int activeTargetCount, int lastSeenCount, int peekTargetCount,
                                    int rejectedForProtection,
                                    List<FiringPosition> candidates,
                                    List<CandidateDiagnostic> pathChecks,
                                    List<ProtectionDiagnostic> protectionDiagnostics,
                                    FiringPosition selected) {}

    private record TargetGeneration(List<TargetSample> targets, int coverTargetCount,
                                    boolean usedGridFallback, int activeTargetCount,
                                    int lastSeenCount, int peekTargetCount) {}

    private record CandidateCollection(List<FiringPosition> positions, int positionsChecked,
                                       int rejectedForAccess, int rejectedForProtection,
                                       List<ProtectionDiagnostic> protectionDiagnostics) {}

    private record CandidateGeometry(BlockPos position, FiringPosition.FiringPosture posture,
                                     float protection, boolean directionallyProtected) {}

    private record CoarseCandidate(CandidateGeometry geometry, float access) {}

    /** Immutable static geometry copied on the server thread for worker evaluation. */
    public record SnapshotBox(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {}

    public record SnapshotCell(boolean loaded, List<SnapshotBox> collision,
                                List<SnapshotBox> outline, float concealment) {}

    public record TerrainSnapshot(Map<BlockPos, SnapshotCell> cells) {}

    public record AsyncCandidateGeometry(BlockPos position, FiringPosition.FiringPosture posture,
                                         float protection, boolean directionallyProtected) {}

    public record AsyncEvaluationSnapshot(
        UUID soldierId, long sourceTick, long tacticalRevision,
        long suppressionSequence, long sectorGeneration,
        BlockPos soldierBlockPosition, Vec3 observerEye,
        BlockPos suppressionCenter, BlockPos supportAnchor,
        List<TargetSample> targets, List<AsyncCandidateGeometry> geometries,
        TerrainSnapshot terrain, int coverTargetCount, boolean usedGridFallback,
        int activeTargetCount, int lastSeenCount, int peekTargetCount,
        int coverPositionsChecked, int pronePositionsChecked) {}

    public record AsyncEvaluationResult(
        List<FiringPosition> candidates, List<ProtectionDiagnostic> protectionDiagnostics,
        int rejectedForAccess, int rejectedForProtection) {}

    private record AsyncGeometryCollection(List<AsyncCandidateGeometry> geometries,
                                           int positionsChecked) {}

    private record AsyncScoredCandidate(AsyncCandidateGeometry geometry, float access) {}

    private record SnapshotVisibility(boolean clear, double concealment) {}

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
                targetGeneration.peekTargetCount(), 0,
                List.of(), List.of(), List.of(), null);
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
                targetGeneration.peekTargetCount(), coverCandidates.rejectedForProtection()
                    + proneCandidates.rejectedForProtection(), List.of(), List.of(),
                mergeProtectionDiagnostics(coverCandidates, proneCandidates), null);
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
            targetGeneration.peekTargetCount(), coverCandidates.rejectedForProtection()
            + proneCandidates.rejectedForProtection(), candidates, pathChecks,
            mergeProtectionDiagnostics(coverCandidates, proneCandidates), best);
    }

    /** Captures all data needed by the pure worker evaluator on the server thread. */
    public static AsyncEvaluationSnapshot captureAsyncSnapshot(
        MachineGunnerEntity mg, BlockPos suppressionCenter, BlockPos supportAnchor,
        long tacticalRevision, long suppressionSequence, long sectorGeneration) {
        long started = System.nanoTime();
        try {
            Level level = mg.level();
            CoverFinder finder = new CoverFinder(level);
            TargetGeneration targetGeneration = generateSuppressionTargets(
                mg, level, finder, suppressionCenter, SoldierEntity.SUPPRESSION_ZONE_RADIUS);
            List<TargetSample> targets = List.copyOf(targetGeneration.targets());

            AsyncGeometryCollection cover = collectAsyncCoverGeometries(
                level, finder, mg, supportAnchor, targets);
            AsyncGeometryCollection prone = collectAsyncProneGeometries(
                level, mg, supportAnchor, targets);
            List<AsyncCandidateGeometry> geometries = new ArrayList<>(
                cover.geometries().size() + prone.geometries().size());
            geometries.addAll(cover.geometries());
            geometries.addAll(prone.geometries());

            TerrainSnapshot terrain = captureTerrainSnapshot(level, targets, geometries);
            return new AsyncEvaluationSnapshot(
                mg.getUUID(), level.getGameTime(), tacticalRevision, suppressionSequence,
                sectorGeneration, mg.blockPosition().immutable(), mg.getEyePosition(),
                suppressionCenter.immutable(), supportAnchor.immutable(), targets,
                List.copyOf(geometries), terrain, targetGeneration.coverTargetCount(),
                targetGeneration.usedGridFallback(), targetGeneration.activeTargetCount(),
                targetGeneration.lastSeenCount(), targetGeneration.peekTargetCount(),
                cover.positionsChecked(), prone.positionsChecked());
        } finally {
            com.stevesarmy.debug.PerformanceMetrics.recordAsyncCoverSnapshot(
                System.nanoTime() - started);
        }
    }

    /** Pure worker-side ranking over immutable candidate and terrain snapshots. */
    public static AsyncEvaluationResult evaluateAsyncSnapshot(AsyncEvaluationSnapshot snapshot) {
        List<TargetSample> targets = snapshot.targets();
        if (targets.isEmpty() || snapshot.geometries().isEmpty()) {
            return new AsyncEvaluationResult(List.of(), List.of(), 0, 0);
        }

        List<TargetSample> coarseTargets = selectCoarseTargets(targets);
        List<AsyncScoredCandidate> coarseCandidates = new ArrayList<>(snapshot.geometries().size());
        for (AsyncCandidateGeometry geometry : snapshot.geometries()) {
            coarseCandidates.add(new AsyncScoredCandidate(geometry,
                evaluateSnapshotAccess(snapshot.terrain(),
                    candidateEye(geometry.position(), geometry.posture()), coarseTargets).access()));
        }
        coarseCandidates.sort(Comparator.comparingDouble(AsyncScoredCandidate::access).reversed());

        List<FiringPosition> candidates = new ArrayList<>();
        List<ProtectionDiagnostic> protectionDiagnostics = new ArrayList<>();
        int rejectedForProtection = 0;
        int fullChecks = Math.min(MAX_FULL_ACCESS_CANDIDATES, coarseCandidates.size());
        for (int i = 0; i < fullChecks; i++) {
            AsyncCandidateGeometry geometry = coarseCandidates.get(i).geometry();
            if (protectionDiagnostics.size() < MAX_PATH_CHECK_CANDIDATES * 2) {
                protectionDiagnostics.add(new ProtectionDiagnostic(geometry.position(), geometry.posture(),
                    geometry.protection(), geometry.directionallyProtected()));
            }
            if (geometry.posture() == FiringPosition.FiringPosture.COVER_PEEK
                && !geometry.directionallyProtected()) {
                rejectedForProtection++;
                continue;
            }
            AccessDiagnostic access = evaluateSnapshotAccess(snapshot.terrain(),
                candidateEye(geometry.position(), geometry.posture()), targets);
            if (access.access() < MIN_FIRING_ACCESS || !access.meaningful()) {
                continue;
            }
            candidates.add(new FiringPosition(geometry.position(), geometry.posture(), access.access(),
                geometry.protection(), score(access.access(), geometry.protection(),
                    geometry.posture() == FiringPosition.FiringPosture.OPEN_PRONE
                        ? OPEN_PRONE_POSTURE_BONUS : COVER_POSTURE_BONUS,
                    geometry.position(), snapshot.supportAnchor())));
        }
        candidates.sort(Comparator.comparingDouble(FiringPosition::score).reversed());
        return new AsyncEvaluationResult(List.copyOf(candidates),
            List.copyOf(protectionDiagnostics), fullChecks - candidates.size() - rejectedForProtection,
            rejectedForProtection);
    }

    /** Main-thread finalization: exact LOS, reservations, and navigation stay here. */
    public static EvaluationReport finalizeAsyncEvaluation(
        MachineGunnerEntity mg, AsyncEvaluationSnapshot snapshot, AsyncEvaluationResult result) {
        Level level = mg.level();
        CoverFinder finder = new CoverFinder(level);
        List<CandidateDiagnostic> pathChecks = new ArrayList<>();
        FiringPosition best = null;
        List<FiringPosition> candidates = result.candidates();
        for (int candidateIndex = 0;
             candidateIndex < candidates.size() && candidateIndex < MAX_PATH_CHECK_CANDIDATES;
             candidateIndex++) {
            FiringPosition candidate = candidates.get(candidateIndex);
            boolean samePosition = candidate.destination().equals(mg.blockPosition());
            Path path = samePosition ? null : candidate.posture() == FiringPosition.FiringPosture.OPEN_PRONE
                ? mg.getNavigation().createPath(candidate.destination(), 0)
                : mg.getNavigation().createPath(candidate.destination().getX() + 0.5,
                    candidate.destination().getY(), candidate.destination().getZ() + 0.5, 0);
            boolean pathExists = samePosition || path != null;
            AccessDiagnostic access = evaluateAccess(level, candidateEye(candidate), snapshot.targets(), mg);
            boolean canReach = access.meaningful() && (samePosition || isPathReachableForCandidate(
                level, finder, candidate, path));
            pathChecks.add(new CandidateDiagnostic(candidate, candidateIndex + 1, pathExists, canReach, access));
            if (canReach && CoverReservationManager.isAvailableFor(candidate.destination(), mg)) {
                best = candidate;
                break;
            }
        }

        return new EvaluationReport(snapshot.targets().size(), snapshot.coverTargetCount(),
            snapshot.usedGridFallback(), snapshot.coverPositionsChecked(), snapshot.pronePositionsChecked(),
            result.rejectedForAccess(), snapshot.targets(), snapshot.activeTargetCount(),
            snapshot.lastSeenCount(), snapshot.peekTargetCount(), result.rejectedForProtection(),
            candidates, pathChecks, result.protectionDiagnostics(), best);
    }

    public static EvaluationReport emptyEvaluationReport() {
        return emptyReport();
    }

    private static AsyncGeometryCollection collectAsyncCoverGeometries(
        Level level, CoverFinder finder, MachineGunnerEntity mg, BlockPos anchor,
        List<TargetSample> targets) {
        List<AsyncCandidateGeometry> geometries = new ArrayList<>();
        int positionsChecked = 0;
        for (CoverPoint cover : finder.findCoverPoints(anchor, SEARCH_RADIUS)) {
            positionsChecked++;
            BlockPos pos = cover.getPosition();
            if (!CoverReservationManager.isAvailableFor(pos, mg)) continue;
            float protection = directionalCoverProtection(level, cover, targets);
            geometries.add(new AsyncCandidateGeometry(pos.immutable(),
                FiringPosition.FiringPosture.COVER_PEEK, protection,
                protection >= MIN_COVER_PROTECTION));
        }
        return new AsyncGeometryCollection(geometries, positionsChecked);
    }

    private static AsyncGeometryCollection collectAsyncProneGeometries(
        Level level, MachineGunnerEntity mg, BlockPos anchor, List<TargetSample> targets) {
        List<AsyncCandidateGeometry> geometries = new ArrayList<>();
        int positionsChecked = 0;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                if (x * x + z * z > SEARCH_RADIUS * SEARCH_RADIUS) continue;
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                    anchor.offset(x, 0, z));
                BlockPos pos = ground.above();
                if (!isProneTerrainValid(level, pos)) continue;
                positionsChecked++;
                if (!CoverReservationManager.isProneAvailableFor(pos, mg)) continue;
                float protection = OPEN_PRONE_PROTECTION
                    + (adjacentCover(level, pos) ? 0.10f : 0.0f);
                geometries.add(new AsyncCandidateGeometry(pos.immutable(),
                    FiringPosition.FiringPosture.OPEN_PRONE, protection, false));
            }
        }
        return new AsyncGeometryCollection(geometries, positionsChecked);
    }

    private static TerrainSnapshot captureTerrainSnapshot(Level level,
                                                           List<TargetSample> targets,
                                                           List<AsyncCandidateGeometry> geometries) {
        Set<BlockPos> rayCells = new HashSet<>();
        for (AsyncCandidateGeometry geometry : geometries) {
            Vec3 eye = candidateEye(geometry.position(), geometry.posture());
            for (TargetSample target : targets) {
                addRayCells(rayCells, eye, target.position());
            }
        }

        Map<BlockPos, SnapshotCell> cells = new HashMap<>();
        for (BlockPos pos : rayCells) {
            BlockPos key = pos.immutable();
            if (!level.isLoaded(key)) {
                cells.put(key, new SnapshotCell(false, List.of(), List.of(), 0.0f));
                continue;
            }
            BlockState state = level.getBlockState(key);
            boolean leaf = state.is(net.minecraft.tags.BlockTags.LEAVES);
            boolean transparent = state.is(com.stevesarmy.combat.ModBlockTags.TRANSPARENT_PENETRABLE);
            boolean concealment = state.is(com.stevesarmy.combat.ModBlockTags.VISION_CONCEALMENT);
            List<SnapshotBox> collision = !leaf && !transparent && !concealment
                ? snapshotShape(state.getCollisionShape(level, key), key) : List.of();
            List<SnapshotBox> outline = leaf || concealment
                ? snapshotShape(state.getShape(level, key), key) : List.of();
            float concealmentWeight = leaf ? 0.75f
                : state.is(com.stevesarmy.combat.ModBlockTags.VISION_CONCEALMENT_MEDIUM)
                    ? 0.30f : concealment ? 0.20f : 0.0f;
            cells.put(key, new SnapshotCell(true, collision, outline, concealmentWeight));
        }
        return new TerrainSnapshot(Map.copyOf(cells));
    }

    private static List<SnapshotBox> snapshotShape(VoxelShape shape, BlockPos pos) {
        if (shape.isEmpty()) return List.of();
        List<SnapshotBox> boxes = new ArrayList<>();
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> boxes.add(
            new SnapshotBox(pos.getX() + minX, pos.getY() + minY, pos.getZ() + minZ,
                pos.getX() + maxX, pos.getY() + maxY, pos.getZ() + maxZ)));
        return List.copyOf(boxes);
    }

    private static void addRayCells(Set<BlockPos> cells, Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0e-7) {
            cells.add(BlockPos.containing(from).immutable());
            return;
        }
        Vec3 unit = direction.scale(1.0 / length);
        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);
        int endX = (int) Math.floor(to.x);
        int endY = (int) Math.floor(to.y);
        int endZ = (int) Math.floor(to.z);
        double t = 0.0;
        while (t <= length + 1.0e-7) {
            cells.add(new BlockPos(x, y, z).immutable());
            if (x == endX && y == endY && z == endZ) break;
            double nextX = nextBoundary(from.x, unit.x, x);
            double nextY = nextBoundary(from.y, unit.y, y);
            double nextZ = nextBoundary(from.z, unit.z, z);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next == Double.POSITIVE_INFINITY) break;
            if (nextX <= next + 1.0e-7) x += step(unit.x);
            if (nextY <= next + 1.0e-7) y += step(unit.y);
            if (nextZ <= next + 1.0e-7) z += step(unit.z);
            t = next;
        }
    }

    private static AccessDiagnostic evaluateSnapshotAccess(TerrainSnapshot terrain,
                                                            Vec3 eye,
                                                            List<TargetSample> targets) {
        int active = 0, lastSeen = 0, peek = 0;
        float activeVisibleWeight = 0.0f, activeWeight = 0.0f;
        float lastSeenVisibleWeight = 0.0f, lastSeenWeight = 0.0f;
        float peekVisibleWeight = 0.0f, peekWeight = 0.0f;
        for (TargetSample target : targets) {
            SnapshotVisibility visibility = traceSnapshot(terrain, eye, target.position());
            float laneQuality = visibility.clear
                ? (float) Math.max(0.0, 1.0 - visibility.concealment) : 0.0f;
            switch (target.category()) {
                case ACTIVE_TARGET -> {
                    activeWeight += target.weight();
                    activeVisibleWeight += target.weight() * laneQuality;
                    if (visibility.clear && visibility.concealment < 1.0) active++;
                }
                case LAST_SEEN -> {
                    lastSeenWeight += target.weight();
                    lastSeenVisibleWeight += target.weight() * laneQuality;
                    if (visibility.clear && visibility.concealment < 1.0) lastSeen++;
                }
                case POTENTIAL_PEEK, GRID_FALLBACK -> {
                    peekWeight += target.weight();
                    peekVisibleWeight += target.weight() * laneQuality;
                    if (visibility.clear && visibility.concealment < 1.0) peek++;
                }
            }
        }
        float activeCoverage = weightedCategoryCoverage(activeVisibleWeight, activeWeight);
        float lastSeenCoverage = weightedCategoryCoverage(lastSeenVisibleWeight, lastSeenWeight);
        float peekCoverage = weightedCategoryCoverage(peekVisibleWeight, peekWeight);
        float categoryWeight = 0.0f, weightedCoverage = 0.0f;
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
        boolean meaningful = activeCoverage >= 0.25f || lastSeen > 0
            || peekCoverage >= MIN_PEEK_COVERAGE;
        float access = categoryWeight > 0.0f ? weightedCoverage / categoryWeight : 0.0f;
        return new AccessDiagnostic(access, activeCoverage, lastSeenCoverage, peekCoverage,
            active, lastSeen, peek, meaningful && access >= MIN_MEANINGFUL_ACCESS);
    }

    private static SnapshotVisibility traceSnapshot(TerrainSnapshot terrain, Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0e-7) return new SnapshotVisibility(true, 0.0);
        Vec3 unit = direction.scale(1.0 / length);
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int endX = (int) Math.floor(to.x), endY = (int) Math.floor(to.y), endZ = (int) Math.floor(to.z);
        double concealment = 0.0, t = 0.0;
        while (t <= length + 1.0e-7) {
            SnapshotCell cell = terrain.cells().get(new BlockPos(x, y, z));
            if (cell == null || !cell.loaded()) return new SnapshotVisibility(false, 1.0);
            for (SnapshotBox box : cell.collision()) {
                if (intersectsSegment(box, from, to)) return new SnapshotVisibility(false, concealment);
            }
            boolean outlineHit = false;
            for (SnapshotBox box : cell.outline()) {
                if (intersectsSegment(box, from, to)) {
                    outlineHit = true;
                    break;
                }
            }
            if (outlineHit) concealment = Math.min(1.0, concealment + cell.concealment());
            if (x == endX && y == endY && z == endZ) break;
            double nextX = nextBoundary(from.x, unit.x, x);
            double nextY = nextBoundary(from.y, unit.y, y);
            double nextZ = nextBoundary(from.z, unit.z, z);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next == Double.POSITIVE_INFINITY) break;
            if (nextX <= next + 1.0e-7) x += step(unit.x);
            if (nextY <= next + 1.0e-7) y += step(unit.y);
            if (nextZ <= next + 1.0e-7) z += step(unit.z);
            t = next;
        }
        return new SnapshotVisibility(true, concealment);
    }

    private static boolean intersectsSegment(SnapshotBox box, Vec3 from, Vec3 to) {
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
            if (near > far) {
                double swap = near; near = far; far = swap;
            }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return false;
        }
        return true;
    }

    private static int step(double component) {
        return component > 0.0 ? 1 : -1;
    }

    private static double nextBoundary(double coordinate, double direction, int block) {
        if (Math.abs(direction) < 1.0e-7) return Double.POSITIVE_INFINITY;
        double boundary = direction > 0.0 ? block + 1.0 : block;
        return (boundary - coordinate) / direction;
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
            List.of(), 0, 0, 0, 0, List.of(), List.of(), List.of(), null);
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
     * Revalidates an occupied lane using only active and last-seen enemy points.
     * Predicted peek points are useful to select a lane but must not make an
     * already-occupied lane churn when those speculative samples change.
     */
    public static ConfirmedFiringAccess evaluateConfirmedFiringAccess(SoldierEntity soldier,
                                                                        BlockPos suppressionCenter,
                                                                        BlockPos destination,
                                                                        FiringPosition.FiringPosture posture) {
        if (suppressionCenter == null || destination == null || posture == null) {
            return new ConfirmedFiringAccess(0.0f, false);
        }
        List<TargetSample> generated = generateSuppressionTargets(
            soldier instanceof MachineGunnerEntity mg ? mg : null, soldier.level(),
            new CoverFinder(soldier.level()), suppressionCenter,
            SoldierEntity.SUPPRESSION_ZONE_RADIUS).targets();
        List<TargetSample> confirmed = new ArrayList<>();
        for (TargetSample target : generated) {
            if (target.category() == TargetCategory.ACTIVE_TARGET
                || target.category() == TargetCategory.LAST_SEEN) {
                confirmed.add(target);
            }
        }
        if (confirmed.isEmpty()) {
            return new ConfirmedFiringAccess(0.0f, false);
        }
        double eyeHeight = posture == FiringPosition.FiringPosture.OPEN_PRONE
            ? PRONE_EYE_HEIGHT : STANDING_EYE_HEIGHT;
        Vec3 eye = new Vec3(destination.getX() + 0.5, destination.getY() + eyeHeight,
            destination.getZ() + 0.5);
        AccessDiagnostic access = evaluateAccess(soldier.level(), eye, confirmed, soldier);
        return new ConfirmedFiringAccess(access.meaningful() ? access.access() : 0.0f, true);
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
        int contextCount = Math.min(MAX_PEEK_CONTEXT_CENTERS, threatCenters.size());
        for (int i = 0; i < contextCount; i++) {
            BlockPos threatCenter = threatCenters.get(i);
            int remainingContexts = contextCount - i;
            int remainingSamples = MAX_PEEK_TARGET_SAMPLES
                - (targets.size() - peekBefore);
            int contextQuota = (remainingSamples + remainingContexts - 1) / remainingContexts;
            addPotentialPeekSamples(level, finder, mg, threatCenter, radius, targets, contextQuota);
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
        SquadThreatIntel.ThreatKnowledge[] selected = mg.getThreatSelectionScratch();
        int selectedCount = 0;
        for (int i = 0; i < selected.length; i++) selected[i] = null;
        for (SquadThreatIntel.ThreatKnowledge threat : squad.getThreatIntel().getThreatsView()) {
            if (!threat.isAlive || threat.lastKnownPosition == null
                || squad.getThreatIntel().isThreatStale(threat.threatEntityId, now)) {
                continue;
            }
            int insertion = 0;
            while (insertion < selectedCount
                && selected[insertion].accuracy >= threat.accuracy) {
                insertion++;
            }
            if (selectedCount < MAX_LAST_SEEN_SAMPLES) {
                for (int i = selectedCount; i > insertion; i--) {
                    selected[i] = selected[i - 1];
                }
                selected[insertion] = threat;
                selectedCount++;
            } else if (insertion < MAX_LAST_SEEN_SAMPLES) {
                for (int i = MAX_LAST_SEEN_SAMPLES - 1; i > insertion; i--) {
                    selected[i] = selected[i - 1];
                }
                selected[insertion] = threat;
            }
        }
        for (int i = 0; i < selectedCount; i++) {
            SquadThreatIntel.ThreatKnowledge threat = selected[i];
            long age = Math.max(0L, now - threat.lastSeenTime);
            float freshness = Math.max(0.1f, 1.0f - age / 200.0f);
            Vec3 position = threat.lastVisibleHeadPoint != null
                ? threat.lastVisibleHeadPoint
                : threat.lastVisibleAimPoint != null
                ? threat.lastVisibleAimPoint
                : threat.lastKnownPosition.getCenter().add(0, 1.0, 0);
            addTarget(targets, position, TargetCategory.LAST_SEEN,
                LAST_SEEN_WEIGHT * freshness, freshness);
        }
        PerformanceMetrics.recordThreatSortSelectionPass();
        PerformanceMetrics.recordTemporaryCollectionAvoided();
    }

    private static void addPotentialPeekSamples(Level level, CoverFinder finder,
                                                 @Nullable MachineGunnerEntity mg, BlockPos center,
                                                 double radius, List<TargetSample> targets, int quota) {
        if (quota <= 0) return;
        if (mg != null) {
            int added = 0;
            for (Vec3 target : finder.findSuppressionAimPoints(mg, center, radius)) {
                if (added >= quota || countTargets(targets, TargetCategory.POTENTIAL_PEEK)
                    >= MAX_PEEK_TARGET_SAMPLES) {
                    break;
                }
                if (hasPeekSampleAtBlock(targets, BlockPos.containing(target))) {
                    continue;
                }
                addTarget(targets, target, TargetCategory.POTENTIAL_PEEK,
                    PEEK_TARGET_WEIGHT, 1.0f);
                added++;
            }
            return;
        }

        int added = 0;
        for (CoverPoint cover : finder.findCoverPoints(center, (int) Math.ceil(radius))) {
            if (added >= quota || countTargets(targets, TargetCategory.POTENTIAL_PEEK)
                >= MAX_PEEK_TARGET_SAMPLES) {
                break;
            }
            for (Direction peekDir : Direction.Plane.HORIZONTAL) {
                if (cover.getProtectedDirections().contains(peekDir)) {
                    continue;
                }
                BlockPos peekPos = cover.getPosition().relative(peekDir);
                if (!isValidTargetCell(level, peekPos)) {
                    continue;
                }
                Vec3 target = new Vec3(peekPos.getX() + 0.5,
                    peekPos.getY() + TARGET_EXPOSURE_HEIGHT, peekPos.getZ() + 0.5);
                if (hasPeekSampleAtBlock(targets, BlockPos.containing(target))) {
                    continue;
                }
                addTarget(targets, target, TargetCategory.POTENTIAL_PEEK,
                    PEEK_TARGET_WEIGHT, 1.0f);
                added++;
            }
        }
    }

    private static boolean hasPeekSampleAtBlock(List<TargetSample> targets, BlockPos block) {
        for (TargetSample target : targets) {
            if (target.category() == TargetCategory.POTENTIAL_PEEK
                && BlockPos.containing(target.position()).equals(block)) {
                return true;
            }
        }
        return false;
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
            if (!CoverReservationManager.isAvailableFor(pos, mg)) {
                continue;
            }
            float protection = directionalCoverProtection(level, cover, targets);
            geometries.add(new CandidateGeometry(pos, FiringPosition.FiringPosture.COVER_PEEK,
                protection, protection >= MIN_COVER_PROTECTION));
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
                if (!CoverReservationManager.isProneAvailableFor(pos, mg)) {
                    continue;
                }
                float protection = OPEN_PRONE_PROTECTION + (adjacentCover(level, pos) ? 0.10f : 0.0f);
                geometries.add(new CandidateGeometry(pos, FiringPosition.FiringPosture.OPEN_PRONE,
                    protection, false));
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
            return new CandidateCollection(List.of(), positionsChecked, 0, 0, List.of());
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
        List<ProtectionDiagnostic> protectionDiagnostics = new ArrayList<>();
        int rejectedForProtection = 0;
        int fullChecks = Math.min(MAX_FULL_ACCESS_CANDIDATES, coarseCandidates.size());
        for (int i = 0; i < fullChecks; i++) {
            CandidateGeometry geometry = coarseCandidates.get(i).geometry();
            if (protectionDiagnostics.size() < MAX_PATH_CHECK_CANDIDATES * 2) {
                protectionDiagnostics.add(new ProtectionDiagnostic(geometry.position(), geometry.posture(),
                    geometry.protection(), geometry.directionallyProtected()));
            }
            if (geometry.posture() == FiringPosition.FiringPosture.COVER_PEEK
                && !geometry.directionallyProtected()) {
                rejectedForProtection++;
                continue;
            }
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
            fullChecks - out.size() - rejectedForProtection, rejectedForProtection,
            protectionDiagnostics);
    }

    private static List<ProtectionDiagnostic> mergeProtectionDiagnostics(CandidateCollection... collections) {
        List<ProtectionDiagnostic> merged = new ArrayList<>();
        for (CandidateCollection collection : collections) {
            merged.addAll(collection.protectionDiagnostics());
        }
        return merged;
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
            VisibilityRay.Result visibility = VisibilityRay.traceIgnoringSmoke(
                level, eye, target.position(), observer);
            float laneQuality = (float) visibility.firingLaneQuality();
            switch (target.category()) {
                case ACTIVE_TARGET -> {
                    activeWeight += target.weight();
                    activeVisibleWeight += target.weight() * laneQuality;
                    if (visibility.hasContact()) active++;
                }
                case LAST_SEEN -> {
                    lastSeenWeight += target.weight();
                    lastSeenVisibleWeight += target.weight() * laneQuality;
                    if (visibility.hasContact()) lastSeen++;
                }
                case POTENTIAL_PEEK, GRID_FALLBACK -> {
                    peekWeight += target.weight();
                    peekVisibleWeight += target.weight() * laneQuality;
                    if (visibility.hasContact()) peek++;
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
            String key;
            if (target.category() == TargetCategory.POTENTIAL_PEEK) {
                BlockPos block = BlockPos.containing(target.position());
                key = target.category() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
            } else {
                key = target.category() + ":" + Math.round(target.position().x * 10)
                    + ":" + Math.round(target.position().y * 10)
                    + ":" + Math.round(target.position().z * 10);
            }
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

    /**
     * Measures physical protection against the actual suppression samples. A
     * cover block only protects when its enemy-facing side blocks the incoming
     * direction; cover on the opposite side is not useful to the MG.
     */
    private static float directionalCoverProtection(Level level, CoverPoint cover,
                                                     List<TargetSample> targets) {
        if (targets.isEmpty()) {
            return 0.0f;
        }
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
        float totalWeight = 0.0f;
        float protectedWeight = 0.0f;
        for (TargetSample target : targets) {
            if (target.category() != TargetCategory.ACTIVE_TARGET
                && target.category() != TargetCategory.LAST_SEEN) {
                continue;
            }
            Vec3 direction = target.position().subtract(cover.getPosition().getCenter());
            if (direction.horizontalDistanceSqr() < 0.001) {
                continue;
            }
            float weight = Math.max(0.01f, target.weight());
            totalWeight += weight;
            if (evaluator.isDirectionProtected(cover, direction)) {
                protectedWeight += weight;
            }
        }
        return totalWeight > 0.0f ? protectedWeight / totalWeight : 0.0f;
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

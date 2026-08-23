package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.ModBlockTags;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadCoverContext;
import com.stevesarmy.squad.SquadCoverPeekabilityCache;

public class CoverFinder {
    private static final float MIN_EFFECTIVE_COVER_HEIGHT = 0.8f;
    private static final float STANDING_EYE_HEIGHT = 1.6f;
    // Match SoldierEntity's exposed posture while occupying half cover.
    private static final float HALF_COVER_STANDING_HEIGHT_THRESHOLD = 1.3f;
    private static final double HALF_COVER_CROUCH_EYE_HEIGHT = 1.27;
    private static final double HALF_COVER_STANDING_EYE_HEIGHT = 1.62;
    private static final int DEFAULT_SEARCH_RADIUS = 12;
    private static final int MAX_SEARCH_RADIUS = 24;
    private static final int MAX_COVER_POINTS = 50;
    private static final int MAX_HEIGHT_CHECK = 3;
    
    private static final double PRIMARY_PROTECTION_WEIGHT = 0.25;
    private static final double FLANKING_PROTECTION_WEIGHT = 0.15;
    private static final double DISTANCE_WEIGHT = 0.10;
    private static final double FIRING_QUALITY_WEIGHT = 0.20;
    private static final double PEEK_ANGLE_WEIGHT = 0.15;
    private static final double SQUAD_DISPERSION_WEIGHT = 0.20;

    // Attack-mode weights (replace DISTANCE_WEIGHT; progress should dominate dispersion so
    // trailing soldiers prefer advancing toward the objective over spreading out)
    private static final double ATTACK_OBJECTIVE_PROGRESS_WEIGHT = 0.18;
    private static final double ATTACK_SQUAD_DISPERSION_WEIGHT = 0.08;
    
    private static final float HALF_COVER_FIGHTABILITY_BONUS = 0.25f;
    private static final float FULL_COVER_FIGHTABILITY_BONUS = 0.15f;
    private static final double LAST_SEEN_CONTACT_TOLERANCE = 2.0;
    private static final float FIRING_LANE_EPSILON = 0.01f;
    public static final float MIN_RELIABLE_FIRING_LANE = 0.35f;

    // Suppression targets for half cover must be above the cover's collision shape.
    // A block-center target is commonly inside the block and causes shots to hit cover.
    private static final double[] HALF_COVER_OPENING_HEIGHTS = {0.12, 0.42};
    private static final double[] HALF_COVER_LATERAL_OFFSETS = {-0.28, 0.0, 0.28};
    
    private static final double FOLLOW_MODE_MAX_OWNER_DISTANCE = 15.0;

    // Pre-calculate inside-out search pattern to eliminate directional bias
    private static final List<BlockPos> SEARCH_OFFSETS = new ArrayList<>();
    static {
        int r = MAX_SEARCH_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -r / 2; y <= r / 2; y++) {
                    SEARCH_OFFSETS.add(new BlockPos(x, y, z));
                }
            }
        }
        // Sort by distance squared from center
        SEARCH_OFFSETS.sort(Comparator.comparingDouble(p -> p.getX() * p.getX() + p.getY() * p.getY() + p.getZ() * p.getZ()));
    }

    private final Level level;
    private long candidateDiscoveryNanos;
    private long tacticalScoringNanos;
    private int candidatesDiscovered;
    private int candidatesEvaluated;
    private int candidatesScored;
    
    public CoverFinder(Level level) {
        this.level = level;
    }

    public void resetPerformanceStats() {
        candidateDiscoveryNanos = 0L;
        tacticalScoringNanos = 0L;
        candidatesDiscovered = 0;
        candidatesEvaluated = 0;
        candidatesScored = 0;
    }

    public long getCandidateDiscoveryNanos() { return candidateDiscoveryNanos; }
    public long getTacticalScoringNanos() { return tacticalScoringNanos; }
    public int getCandidatesDiscovered() { return candidatesDiscovered; }
    public int getCandidatesEvaluated() { return candidatesEvaluated; }
    public int getCandidatesScored() { return candidatesScored; }
    
    public List<CoverPoint> findCoverPoints(BlockPos center, int radius) {
        return findCoverPoints(center, radius, null);
    }
    
    public List<CoverPoint> findCoverPoints(BlockPos center, int radius, LivingEntity threat) {
        long started = System.nanoTime();
        List<CoverPoint> coverPoints = new ArrayList<>();
        int searchRadius = Math.min(radius, MAX_SEARCH_RADIUS);
        int maxDistSq = searchRadius * searchRadius;

        // Search closest blocks first to guarantee we find the best nearby cover before hitting the 50 limit
        for (BlockPos offset : SEARCH_OFFSETS) {
            // Skip offsets outside our current dynamic radius
            if (offset.getX() * offset.getX() + offset.getZ() * offset.getZ() > maxDistSq) continue;
            if (Math.abs(offset.getY()) > searchRadius / 2) continue;

            BlockPos checkPos = center.offset(offset);

            if (isValidCoverPosition(checkPos)) {
                CoverPoint coverPoint = evaluatePosition(checkPos, threat);
                if (coverPoint != null && coverPoint.getType() != CoverType.NONE) {
                    coverPoints.add(coverPoint);

                    if (coverPoints.size() >= MAX_COVER_POINTS) {
                        return finishCoverPointSearch(coverPoints, started);
                    }
                }
            }
        }

        return finishCoverPointSearch(coverPoints, started);
    }

    private List<CoverPoint> finishCoverPointSearch(List<CoverPoint> coverPoints, long started) {
        long elapsed = System.nanoTime() - started;
        candidateDiscoveryNanos += elapsed;
        candidatesDiscovered += coverPoints.size();
        PerformanceMetrics.recordCoverSearch(elapsed, coverPoints.size());
        return coverPoints;
    }
    
    public Optional<CoverPoint> findBestCover(BlockPos center, int radius, LivingEntity threat) {
        List<CoverPoint> coverPoints = findCoverPoints(center, radius, threat);
        
        if (coverPoints.isEmpty()) {
            return Optional.empty();
        }
        
        return coverPoints.stream()
            .max(Comparator.comparingDouble(cp -> calculateScore(cp, center, threat)));
    }
    
    public Optional<CoverPoint> findBestCover(BlockPos center, int radius, LivingEntity threat, Vec3 threatDirection) {
        List<CoverPoint> coverPoints = findCoverPoints(center, radius, threat);
        
        if (coverPoints.isEmpty()) {
            return Optional.empty();
        }
        
        return coverPoints.stream()
            .max(Comparator.comparingDouble(cp -> calculateThreatAwareScore(cp, center, threat, threatDirection)));
    }
    
    public Optional<CoverPoint> findBestCover(LivingEntity soldier, Vec3 threatDirection, List<LivingEntity> allThreats) {
        return findBestCover(soldier, threatDirection, allThreats, DEFAULT_SEARCH_RADIUS);
    }
    
    public Optional<CoverPoint> findBestCover(LivingEntity soldier, Vec3 threatDirection, 
                                               List<LivingEntity> allThreats, int radius) {
        List<ScoredCover> all = evaluateAndScoreAll(soldier, threatDirection, allThreats, radius, false);
        
        if (all.isEmpty()) {
            return Optional.empty();
        }
        
        // Hard filter: prefer covers that physically protect from the resolved primary threat.
        if (resolveProtectionContext(soldier, threatDirection).hasThreat()) {
            List<ScoredCover> protectedCovers = all.stream()
                .filter(s -> isPrimaryThreatProtected(s.cover, soldier, threatDirection))
                .collect(java.util.stream.Collectors.toList());
            
            if (!protectedCovers.isEmpty()) {
                if (com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[CoverFinder] Selected protected cover: {} (threatDir={}, {} protected covers available)",
                        protectedCovers.get(0).cover.getPosition(), resolveProtectionContext(soldier, threatDirection).source(), protectedCovers.size());
                }
                return Optional.of(protectedCovers.get(0).cover);
            }

            if (com.stevesarmy.entity.ai.CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[CoverFinder] No physically protected covers available for source={}, using best scored cover",
                    resolveProtectionContext(soldier, threatDirection).source());
            }
        }
        
        return Optional.of(all.get(0).cover);
    }

    public Optional<CoverPoint> findBestCover(LivingEntity soldier, Vec3 threatDirection,
                                               List<LivingEntity> allThreats, int radius,
                                               SquadCoverContext squadCtx) {
        List<ScoredCover> all = evaluateAndScoreAll(soldier, threatDirection, allThreats, radius, false, squadCtx);

        if (all.isEmpty()) {
            return Optional.empty();
        }

        // Hard filter: prefer covers that physically protect from the resolved primary threat.
        if (resolveProtectionContext(soldier, threatDirection).hasThreat()) {
            List<ScoredCover> protectedCovers = all.stream()
                .filter(s -> isPrimaryThreatProtected(s.cover, soldier, threatDirection))
                .collect(java.util.stream.Collectors.toList());

            if (!protectedCovers.isEmpty()) {
                return Optional.of(protectedCovers.get(0).cover);
            }
        }

        return Optional.of(all.get(0).cover);
    }

    public List<ScoredCover> findTopCovers(LivingEntity soldier, Vec3 threatDirection,
                                            List<LivingEntity> allThreats, int radius, int count, boolean includeReserved) {
        List<ScoredCover> all = evaluateAndScoreAll(soldier, threatDirection, allThreats, radius, includeReserved);
        
        // Apply hard filter: prefer physically protected covers when a threat is known.
        if (resolveProtectionContext(soldier, threatDirection).hasThreat()) {
            List<ScoredCover> protectedCovers = all.stream()
                .filter(s -> isPrimaryThreatProtected(s.cover, soldier, threatDirection))
                .collect(java.util.stream.Collectors.toList());
            
            if (!protectedCovers.isEmpty()) {
                return protectedCovers.subList(0, Math.min(count, protectedCovers.size()));
            }
        }
        
        return all.subList(0, Math.min(count, all.size()));
    }

    public List<ScoredCover> evaluateAndScoreAll(LivingEntity soldier, Vec3 threatDirection,
                                                    List<LivingEntity> allThreats, int radius, boolean includeReserved) {
        List<CoverPoint> coverPoints = findCoverPoints(soldier.blockPosition(), radius);
        if (coverPoints.isEmpty()) {
            return Collections.emptyList();
        }
        long scoringStarted = System.nanoTime();

        LivingEntity primaryThreat = allThreats != null && !allThreats.isEmpty() ? allThreats.get(0) : null;
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
        CoverProtectionContext protection = resolveProtectionContext(soldier, threatDirection);
        int evaluatedBefore = candidatesEvaluated;

        for (CoverPoint coverPoint : coverPoints) {
            if (!includeReserved && !CoverReservationManager.isAvailable(coverPoint.getPosition())) {
                continue;
            }
            Vec3 evaluationDirection = protection.directionFrom(coverPoint.getPosition());
            if (evaluationDirection != null) {
                evaluator.evaluateWithCone(coverPoint, evaluationDirection);
            }
            candidatesEvaluated++;
            float score = calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats, primaryThreat, protection);
            coverPoint.setQuality(score);
            coverPoint.setCombatScore(score);
        }

        PerformanceMetrics.recordCoverCandidatesEvaluated(candidatesEvaluated - evaluatedBefore);

        List<ScoredCover> scored = coverPoints.stream()
            .filter(cp -> includeReserved || CoverReservationManager.isAvailable(cp.getPosition()))
            .filter(cp -> cp.getType() != CoverType.NONE)
            .map(cp -> new ScoredCover(cp, cp.getCombatScore()))
            .sorted(Comparator.comparingDouble((ScoredCover s) -> s.score).reversed())
            .collect(java.util.stream.Collectors.toList());
        tacticalScoringNanos += System.nanoTime() - scoringStarted;
        candidatesScored += scored.size();
        PerformanceMetrics.recordCoverCandidatesScored(scored.size());
        return scored;
    }

    public List<ScoredCover> evaluateAndScoreAll(LivingEntity soldier, Vec3 threatDirection,
                                                    List<LivingEntity> allThreats, int radius, boolean includeReserved,
                                                    SquadCoverContext squadCtx) {
        List<CoverPoint> coverPoints = findCoverPoints(soldier.blockPosition(), radius);
        if (coverPoints.isEmpty()) {
            return Collections.emptyList();
        }
        long scoringStarted = System.nanoTime();

        LivingEntity primaryThreat = allThreats != null && !allThreats.isEmpty() ? allThreats.get(0) : null;
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
        CoverProtectionContext protection = resolveProtectionContext(soldier, threatDirection);
        int evaluatedBefore = candidatesEvaluated;

        for (CoverPoint coverPoint : coverPoints) {
            if (!includeReserved && !CoverReservationManager.isAvailable(coverPoint.getPosition())) {
                continue;
            }
            Vec3 evaluationDirection = protection.directionFrom(coverPoint.getPosition());
            if (evaluationDirection != null) {
                evaluator.evaluateWithCone(coverPoint, evaluationDirection);
            }
            candidatesEvaluated++;
            float score = calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats, primaryThreat,
                squadCtx, null, 0, protection);
            coverPoint.setQuality(score);
            coverPoint.setCombatScore(score);
        }

        PerformanceMetrics.recordCoverCandidatesEvaluated(candidatesEvaluated - evaluatedBefore);

        Vec3 ownerPos = squadCtx != null ? squadCtx.ownerPosition() : null;
        double maxOwnerDistSq = FOLLOW_MODE_MAX_OWNER_DISTANCE * FOLLOW_MODE_MAX_OWNER_DISTANCE;
        
        List<ScoredCover> scored = coverPoints.stream()
            .filter(cp -> includeReserved || CoverReservationManager.isAvailable(cp.getPosition()))
            .filter(cp -> cp.getType() != CoverType.NONE)
            .filter(cp -> ownerPos == null || cp.getPosition().getCenter().distanceToSqr(ownerPos) <= maxOwnerDistSq)
            .map(cp -> new ScoredCover(cp, cp.getCombatScore()))
            .sorted(Comparator.comparingDouble((ScoredCover s) -> s.score).reversed())
            .collect(java.util.stream.Collectors.toList());
        tacticalScoringNanos += System.nanoTime() - scoringStarted;
        candidatesScored += scored.size();
        PerformanceMetrics.recordCoverCandidatesScored(scored.size());
        return scored;
    }

    /**
     * Score all cover points using soldier for scoring but searching from a custom center.
     * Used by attack-mode to search forward of the soldier.
     */
    public List<ScoredCover> evaluateAndScoreAllFromCenter(BlockPos searchCenter, LivingEntity soldier,
                                                             Vec3 threatDirection, List<LivingEntity> allThreats,
                                                             int radius, SquadCoverContext squadCtx) {
        List<CoverPoint> coverPoints = findCoverPoints(searchCenter, radius);
        if (coverPoints.isEmpty()) {
            return Collections.emptyList();
        }
        long scoringStarted = System.nanoTime();

        LivingEntity primaryThreat = allThreats != null && !allThreats.isEmpty() ? allThreats.get(0) : null;
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
        CoverProtectionContext protection = resolveProtectionContext(soldier, threatDirection);
        int evaluatedBefore = candidatesEvaluated;

        for (CoverPoint coverPoint : coverPoints) {
            if (!CoverReservationManager.isAvailableFor(coverPoint.getPosition(), soldier)) {
                continue;
            }
            Vec3 evaluationDirection = protection.directionFrom(coverPoint.getPosition());
            if (evaluationDirection != null) {
                evaluator.evaluateWithCone(coverPoint, evaluationDirection);
            }
            candidatesEvaluated++;
            float score = calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats, primaryThreat, squadCtx,
                searchCenter, radius, protection);
            coverPoint.setQuality(score);
            coverPoint.setCombatScore(score);
        }

        PerformanceMetrics.recordCoverCandidatesEvaluated(candidatesEvaluated - evaluatedBefore);

        List<ScoredCover> scored = coverPoints.stream()
            .filter(cp -> cp.getType() != CoverType.NONE)
            .filter(cp -> CoverReservationManager.isAvailableFor(cp.getPosition(), soldier))
            .map(cp -> new ScoredCover(cp, cp.getCombatScore()))
            .sorted(Comparator.comparingDouble((ScoredCover s) -> s.score).reversed())
            .collect(java.util.stream.Collectors.toList());
        tacticalScoringNanos += System.nanoTime() - scoringStarted;
        candidatesScored += scored.size();
        PerformanceMetrics.recordCoverCandidatesScored(scored.size());
        return scored;
    }

    public static class ScoredCover {
        public final CoverPoint cover;
        public final float score;
        public ScoredCover(CoverPoint cover, float score) {
            this.cover = cover;
            this.score = score;
        }
    }
    
    private float calculateThreatAwareScore(CoverPoint coverPoint, LivingEntity soldier,
                                            Vec3 threatDirection, List<LivingEntity> allThreats, LivingEntity primaryThreat,
                                            CoverProtectionContext protection) {
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[ThreatAwareScore] coverPos={}, threatDirection=({}, {}, {}), primaryThreat={}",
                coverPoint.getPosition(),
                threatDirection != null ? String.format("%.2f", threatDirection.x) : "null",
                threatDirection != null ? String.format("%.2f", threatDirection.y) : "null",
                threatDirection != null ? String.format("%.2f", threatDirection.z) : "null",
                primaryThreat != null ? primaryThreat.blockPosition() : "null");
        }
        
        float primaryProtection = calculatePrimaryProtection(coverPoint, protection);
        float flankingProtection = calculateFlankingProtection(coverPoint, allThreats);
        float firingQuality = calculateFiringQuality(coverPoint, threatDirection);
        FiringLaneResult firingLane = calculateFiringLane(coverPoint, threatDirection, primaryThreat, null);
        float firingAccessScore = firingLane.score();
        coverPoint.setFiringAccessScore(firingAccessScore);
        
        boolean isAttackMode = (soldier instanceof SoldierEntity se && se.hasValidAttackTarget());
        float distanceScore;
        if (isAttackMode) {
            SoldierEntity se = (SoldierEntity) soldier;
            distanceScore = calculateObjectiveProgressScore(coverPoint, soldier, se.getAttackTargetPos());
        } else {
            distanceScore = calculateDistanceScore(coverPoint, soldier);
        }
        
        float fightability = 0.0f;
        if (coverPoint.canShootFrom() && firingAccessScore > FIRING_LANE_EPSILON) {
            fightability = coverPoint.getType() == CoverType.HALF ? 
                HALF_COVER_FIGHTABILITY_BONUS : FULL_COVER_FIGHTABILITY_BONUS;
        }
        
        float blindPenalty = 0.0f;
        if (coverPoint.getType() == CoverType.FULL && firingAccessScore <= FIRING_LANE_EPSILON && primaryThreat != null) {
            blindPenalty = 0.50f;
        }
        
        if (coverPoint.getType() == CoverType.NONE) {
            return 0.0f;
        }
        
        if (coverPoint.getType() == CoverType.CONCEALMENT) {
            blindPenalty = 0.50f;
            fightability = 0.0f;
            firingQuality = 0.0f;
            firingAccessScore = 0.0f;
        }
        
        float weightedScore;
        if (isAttackMode) {
            weightedScore = (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                           flankingProtection * FLANKING_PROTECTION_WEIGHT +
                           distanceScore * ATTACK_OBJECTIVE_PROGRESS_WEIGHT +
                           firingQuality * FIRING_QUALITY_WEIGHT +
                           firingAccessScore * PEEK_ANGLE_WEIGHT) + fightability - blindPenalty;
        } else {
            weightedScore = (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                           flankingProtection * FLANKING_PROTECTION_WEIGHT +
                           distanceScore * DISTANCE_WEIGHT +
                           firingQuality * FIRING_QUALITY_WEIGHT +
                           firingAccessScore * PEEK_ANGLE_WEIGHT) + fightability - blindPenalty;
        }
        
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverScore] {} type={} q={} prim={} flank={} dist={} firing={} lane={} laneSource={} contacts={}/{} fight={} blindPen={} TOTAL={}",
                coverPoint.getPosition(), coverPoint.getType(),
                String.format("%.2f", coverPoint.getQuality()),
                String.format("%.2f", primaryProtection * PRIMARY_PROTECTION_WEIGHT),
                String.format("%.2f", flankingProtection * FLANKING_PROTECTION_WEIGHT),
                String.format("%.2f", distanceScore * (isAttackMode ? ATTACK_OBJECTIVE_PROGRESS_WEIGHT : DISTANCE_WEIGHT)),
                String.format("%.2f", firingQuality * FIRING_QUALITY_WEIGHT),
                String.format("%.2f", firingAccessScore * PEEK_ANGLE_WEIGHT),
                firingLane.source(), firingLane.reachableContacts(), firingLane.eligibleContacts(),
                String.format("%.2f", fightability),
                String.format("%.2f", blindPenalty),
                String.format("%.2f", weightedScore));
        }
        
        return weightedScore;
    }

    private float calculateThreatAwareScore(CoverPoint coverPoint, LivingEntity soldier,
                                            Vec3 threatDirection, List<LivingEntity> allThreats,
                                            LivingEntity primaryThreat, SquadCoverContext squadCtx,
                                            CoverProtectionContext protection) {
        return calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats, primaryThreat, squadCtx,
            null, 0, protection);
    }

    private float calculateThreatAwareScore(CoverPoint coverPoint, LivingEntity soldier,
                                            Vec3 threatDirection, List<LivingEntity> allThreats,
                                            LivingEntity primaryThreat, SquadCoverContext squadCtx,
                                            BlockPos searchCenter, int searchRadius,
                                            CoverProtectionContext protection) {
        float primaryProtection = calculatePrimaryProtection(coverPoint, protection);
        float flankingProtection = calculateFlankingProtection(coverPoint, allThreats);
        float firingQuality = calculateFiringQuality(coverPoint, threatDirection);
        FiringLaneResult firingLane = calculateFiringLane(coverPoint, threatDirection, primaryThreat, squadCtx);
        float firingAccessScore = firingLane.score();
        coverPoint.setFiringAccessScore(firingAccessScore);

        boolean isAttackMode = (soldier instanceof SoldierEntity se && se.hasValidAttackTarget());
        float distanceScore;
        float dispersionScore;
        if (isAttackMode) {
            SoldierEntity se = (SoldierEntity) soldier;
            distanceScore = calculateObjectiveProgressScore(coverPoint, soldier, se.getAttackTargetPos());
            dispersionScore = calculateSquadDispersionScore(coverPoint, squadCtx);
        } else if (searchCenter != null) {
            distanceScore = calculateDistanceScore(coverPoint, searchCenter, searchRadius);
            dispersionScore = calculateSquadDispersionScore(coverPoint, squadCtx);
        } else {
            distanceScore = calculateDistanceScore(coverPoint, soldier);
            dispersionScore = calculateSquadDispersionScore(coverPoint, squadCtx);
        }

        float fightability = 0.0f;
        if (coverPoint.canShootFrom() && firingAccessScore > FIRING_LANE_EPSILON) {
            fightability = coverPoint.getType() == CoverType.HALF ? 
                HALF_COVER_FIGHTABILITY_BONUS : FULL_COVER_FIGHTABILITY_BONUS;
        }

        float blindPenalty = 0.0f;
        if (coverPoint.getType() == CoverType.FULL && firingAccessScore <= FIRING_LANE_EPSILON && primaryThreat != null) {
            blindPenalty = 0.50f;
        }

        if (coverPoint.getType() == CoverType.NONE) {
            return 0.0f;
        }

        if (coverPoint.getType() == CoverType.CONCEALMENT) {
            blindPenalty = 0.50f;
            fightability = 0.0f;
            firingQuality = 0.0f;
            firingAccessScore = 0.0f;
        }

        float weightedScore;
        if (isAttackMode) {
            weightedScore = (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                           flankingProtection * FLANKING_PROTECTION_WEIGHT +
                           distanceScore * ATTACK_OBJECTIVE_PROGRESS_WEIGHT +
                           firingQuality * FIRING_QUALITY_WEIGHT +
                           firingAccessScore * PEEK_ANGLE_WEIGHT +
                           dispersionScore * ATTACK_SQUAD_DISPERSION_WEIGHT) + fightability - blindPenalty;
        } else {
            weightedScore = (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                           flankingProtection * FLANKING_PROTECTION_WEIGHT +
                           distanceScore * DISTANCE_WEIGHT +
                           firingQuality * FIRING_QUALITY_WEIGHT +
                           firingAccessScore * PEEK_ANGLE_WEIGHT +
                           dispersionScore * SQUAD_DISPERSION_WEIGHT) + fightability - blindPenalty;
        }

        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[CoverScore] {} type={} lane={} laneSource={} contacts={}/{} total={}",
                coverPoint.getPosition(), coverPoint.getType(),
                String.format("%.2f", firingAccessScore * PEEK_ANGLE_WEIGHT),
                firingLane.source(), firingLane.reachableContacts(), firingLane.eligibleContacts(),
                String.format("%.2f", weightedScore));
        }

        return weightedScore;
    }

    private float calculateSquadDispersionScore(CoverPoint coverPoint, SquadCoverContext ctx) {
        if (ctx == null || !ctx.inSquad() || ctx.getOccupiedCovers().isEmpty()) {
            return 0.5f;
        }

        if (ctx.isSameCover(coverPoint.getPosition())) {
            return 0.0f;
        }

        double minDist = 4.0;
        if (ctx.isTooClose(coverPoint.getPosition(), minDist)) {
            return 0.2f;
        }

        double closestDistSq = ctx.getOccupiedCovers().stream()
            .mapToDouble(c -> c.distSqr(coverPoint.getPosition()))
            .min().orElse(Double.MAX_VALUE);

        if (closestDistSq >= 36.0) return 0.7f;
        if (closestDistSq >= 16.0) return 0.5f;
        return 0.3f;
    }

    private float calculatePrimaryProtection(CoverPoint coverPoint, Vec3 threatDirection) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return coverPoint.getQuality();
        }
        
        BlockPos coverPos = coverPoint.getPosition();
        Vec3 coverCenter = coverPos.getCenter();
        
        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return 0.0f;
        }
        
        Direction threatDir = getDirectionFromVector(threatDirection);
        boolean isProtected = protectedDirs.contains(threatDir);
        
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PrimaryProtection] coverPos={}, threatDirection=({}, {}, {}), threatDir={}, protectedDirs={}, isProtected={}",
                coverPos,
                String.format("%.2f", threatDirection.x),
                String.format("%.2f", threatDirection.y),
                String.format("%.2f", threatDirection.z),
                threatDir,
                protectedDirs,
                isProtected);
        }
        
        if (isProtected) {
            return coverPoint.getQuality();
        }
        
        return 0.0f;
    }

    private float calculatePrimaryProtection(CoverPoint coverPoint, CoverProtectionContext protection) {
        Vec3 direction = protection.directionFrom(coverPoint.getPosition());
        if (direction == null) {
            return coverPoint.getQuality();
        }

        boolean protectedFromThreat = new CoverQualityEvaluator(level).isDirectionProtected(coverPoint, direction);
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PrimaryProtection] coverPos={} source={} direction=({}, {}, {}) physicalProtected={}",
                coverPoint.getPosition(), protection.source(), String.format("%.2f", direction.x),
                String.format("%.2f", direction.y), String.format("%.2f", direction.z), protectedFromThreat);
        }
        return protectedFromThreat ? coverPoint.getQuality() : 0.0f;
    }

    private CoverProtectionContext resolveProtectionContext(LivingEntity soldier, Vec3 fallbackDirection) {
        if (soldier instanceof SoldierEntity soldierEntity && soldierEntity.getCombatGoal() != null) {
            return soldierEntity.getCombatGoal().resolveCoverProtectionContext();
        }
        if (fallbackDirection != null && fallbackDirection.lengthSqr() > 0.001D) {
            return new CoverProtectionContext(CoverProtectionContext.Source.THREAT_AWARENESS,
                null, fallbackDirection);
        }
        return CoverProtectionContext.NONE;
    }

    public boolean isPrimaryThreatProtected(CoverPoint coverPoint, LivingEntity soldier, Vec3 fallbackDirection) {
        CoverProtectionContext protection = resolveProtectionContext(soldier, fallbackDirection);
        Vec3 direction = protection.directionFrom(coverPoint.getPosition());
        return direction == null || new CoverQualityEvaluator(level).isDirectionProtected(coverPoint, direction);
    }

    public boolean hasPrimaryThreat(LivingEntity soldier, Vec3 fallbackDirection) {
        return resolveProtectionContext(soldier, fallbackDirection).hasThreat();
    }
    
    private float calculateFlankingProtection(CoverPoint coverPoint, List<LivingEntity> allThreats) {
        if (allThreats == null || allThreats.isEmpty()) {
            return 1.0f;
        }
        
        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        if (protectedDirs == null) {
            return 0.0f;
        }
        
        int protectedCount = 0;
        for (LivingEntity threat : allThreats) {
            if (!threat.isAlive()) continue;
            
            Vec3 toThreat = threat.position().subtract(coverPoint.getPosition().getCenter());
            Direction threatDir = getDirectionFromVector(toThreat);
            
            if (protectedDirs.contains(threatDir)) {
                protectedCount++;
            }
        }
        
        return (float) protectedCount / allThreats.size();
    }
    
    private float calculateDistanceScore(CoverPoint coverPoint, LivingEntity soldier) {
        double distance = coverPoint.distanceTo(soldier);
        double maxDistance = DEFAULT_SEARCH_RADIUS * 2.0;
        return (float) (1.0 - Math.min(distance / maxDistance, 1.0));
    }

    private float calculateDistanceScore(CoverPoint coverPoint, BlockPos center, int searchRadius) {
        double distance = Math.sqrt(coverPoint.getPosition().distSqr(center));
        return (float) (1.0 - Math.min(distance / Math.max(1, searchRadius), 1.0));
    }

    private float calculateObjectiveProgressScore(CoverPoint coverPoint, LivingEntity soldier, BlockPos attackTarget) {
        Vec3 soldierPos = soldier.position();
        Vec3 toObjective = new Vec3(
            attackTarget.getX() - soldierPos.x,
            0,
            attackTarget.getZ() - soldierPos.z);
        double distToObjective = toObjective.length();
        if (distToObjective < 0.01) return 0.5f;

        Vec3 objectiveDir = toObjective.normalize();
        Vec3 candidateDisplacement = coverPoint.getPosition().getCenter().subtract(soldierPos);
        double forwardProgress = candidateDisplacement.x * objectiveDir.x + candidateDisplacement.z * objectiveDir.z;
        double maxProgress = DEFAULT_SEARCH_RADIUS * 2.0;
        return (float) Math.max(0.0, Math.min(1.0, 0.5 + forwardProgress / maxProgress));
    }
    
    private float calculateFiringQuality(CoverPoint coverPoint, Vec3 threatDirection) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return 0.5f;
        }
        
        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return 0.0f;
        }
        
        Direction threatDir = getDirectionFromVector(threatDirection);
        Direction threatOpposite = threatDir.getOpposite();
        
        if (protectedDirs.contains(threatOpposite)) {
            return 1.0f;
        }
        
        int adjacentProtected = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (protectedDirs.contains(dir) && isAdjacentDirection(dir, threatOpposite)) {
                adjacentProtected++;
            }
        }
        
        return 0.5f + (adjacentProtected * 0.25f);
    }
    
    private boolean isAdjacentDirection(Direction dir1, Direction dir2) {
        return (dir1 == Direction.NORTH && (dir2 == Direction.EAST || dir2 == Direction.WEST)) ||
               (dir1 == Direction.SOUTH && (dir2 == Direction.EAST || dir2 == Direction.WEST)) ||
               (dir1 == Direction.EAST && (dir2 == Direction.NORTH || dir2 == Direction.SOUTH)) ||
               (dir1 == Direction.WEST && (dir2 == Direction.NORTH || dir2 == Direction.SOUTH));
    }
    
    private record FiringLaneResult(float score, String source, int reachableContacts, int eligibleContacts) {
        private static final FiringLaneResult NONE = new FiringLaneResult(0.0f, "none", 0, 0);
    }

    private FiringLaneResult calculateFiringLane(CoverPoint coverPoint, Vec3 threatDirection,
                                                   LivingEntity primaryThreat, SquadCoverContext squadCtx) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return FiringLaneResult.NONE;
        }

        if (coverPoint.getType() == CoverType.HALF) {
            return evaluateFiringOrigin(getHalfCoverExposedEye(coverPoint), threatDirection, primaryThreat, squadCtx);
        }

        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return FiringLaneResult.NONE;
        }

        FiringLaneResult best = FiringLaneResult.NONE;
        BlockPos coverPos = coverPoint.getPosition();
        for (Direction peekDir : Direction.Plane.HORIZONTAL) {
            if (protectedDirs.contains(peekDir)) {
                continue;
            }

            BlockPos peekPos = coverPos.relative(peekDir);
            if (!isValidPeekPosition(peekPos)) {
                continue;
            }

            Vec3 peekEye = new Vec3(peekPos.getX() + 0.5, peekPos.getY() + HALF_COVER_STANDING_EYE_HEIGHT,
                peekPos.getZ() + 0.5);
            FiringLaneResult candidate = evaluateFiringOrigin(peekEye, threatDirection, primaryThreat, squadCtx);
            if (isBetterFiringLane(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private Vec3 getHalfCoverExposedEye(CoverPoint coverPoint) {
        BlockPos coverPos = coverPoint.getPosition();
        double eyeHeight = coverPoint.getCoverHeight() >= HALF_COVER_STANDING_HEIGHT_THRESHOLD
            ? HALF_COVER_STANDING_EYE_HEIGHT
            : HALF_COVER_CROUCH_EYE_HEIGHT;
        return new Vec3(coverPos.getX() + 0.5, coverPos.getY() + eyeHeight, coverPos.getZ() + 0.5);
    }

    private FiringLaneResult evaluateFiringOrigin(Vec3 origin, Vec3 threatDirection,
                                                   LivingEntity primaryThreat, SquadCoverContext squadCtx) {
        if (primaryThreat != null && primaryThreat.isAlive()) {
            float activeLane = (float) ExposureCalculator.getBestFiringLaneQualityFrom(origin, primaryThreat);
            if (activeLane >= MIN_RELIABLE_FIRING_LANE) {
                return new FiringLaneResult(activeLane, "active", 0, 0);
            }
        }

        FiringLaneResult squadLane = calculateSquadContactCoverage(origin, squadCtx);
        if (squadLane.score() > FIRING_LANE_EPSILON) {
            return squadLane;
        }

        SquadCoverPeekabilityCache cache = squadCtx != null ? squadCtx.getPeekabilityCache() : null;
        Direction coneDirection = getDirectionFromVector(threatDirection);
        Float cachedCoverage = cache != null ? cache.getConeCoverage(origin, coneDirection, level.getGameTime()) : null;
        float coneCoverage = cachedCoverage != null
            ? cachedCoverage
            : calculateConeCoverage(origin, threatDirection, level);
        if (cache != null && cachedCoverage == null) {
            cache.putConeCoverage(origin, coneDirection, coneCoverage, level.getGameTime());
        }
        return new FiringLaneResult(coneCoverage, "cone", 0, 0);
    }

    private FiringLaneResult calculateSquadContactCoverage(Vec3 origin, SquadCoverContext squadCtx) {
        if (squadCtx == null || squadCtx.getFiringContacts().isEmpty()) {
            return FiringLaneResult.NONE;
        }

        long currentTick = level.getGameTime();
        float totalWeight = 0.0f;
        float reachableWeight = 0.0f;
        int eligible = 0;
        int reachable = 0;

        for (SquadCoverContext.FiringContact contact : squadCtx.getFiringContacts()) {
            float freshness = contact.freshnessAt(currentTick);
            if (freshness <= 0.0f) {
                continue;
            }

            eligible++;
            totalWeight += freshness;
            SquadCoverPeekabilityCache cache = squadCtx.getPeekabilityCache();
            VisibilityRay.Result visibility = cache != null
                ? cache.getContactVisibility(level, origin, contact.threatEntityId(), contact.exposedPoint(), currentTick)
                : VisibilityRay.trace(level, origin, contact.exposedPoint(), null);
            double laneQuality = visibility.firingLaneQuality();
            if (laneQuality >= MIN_RELIABLE_FIRING_LANE) {
                reachable++;
                reachableWeight += freshness * (float) laneQuality;
            } else if (isNearLastSeenContact(origin, contact.exposedPoint(), visibility)) {
                // Preserve the existing last-seen fallback for a physically
                // blocked point; it is distinct from a foliage-obscured lane.
                reachable++;
                reachableWeight += freshness;
            }
        }

        if (totalWeight <= 0.0f || reachable == 0) {
            return new FiringLaneResult(0.0f, "squad-memory", reachable, eligible);
        }
        return new FiringLaneResult(reachableWeight / totalWeight, "squad-memory", reachable, eligible);
    }

    private boolean isNearLastSeenContact(Vec3 origin, Vec3 exposedPoint, VisibilityRay.Result visibility) {
        if (visibility.clear() || !Double.isFinite(visibility.blockedDistance())) {
            return false;
        }
        double remainingDistance = origin.distanceTo(exposedPoint) - visibility.blockedDistance();
        return remainingDistance <= LAST_SEEN_CONTACT_TOLERANCE;
    }

    private boolean isBetterFiringLane(FiringLaneResult candidate, FiringLaneResult current) {
        if (candidate.score() > current.score() + FIRING_LANE_EPSILON) {
            return true;
        }
        if (Math.abs(candidate.score() - current.score()) > FIRING_LANE_EPSILON) {
            return false;
        }
        return firingLaneSourcePriority(candidate.source()) > firingLaneSourcePriority(current.source());
    }

    private int firingLaneSourcePriority(String source) {
        return switch (source) {
            case "active" -> 3;
            case "squad-memory" -> 2;
            case "cone" -> 1;
            default -> 0;
        };
    }
    
    public static boolean isValidPeekPosition(BlockPos pos, net.minecraft.world.level.Level level) {
        if (!level.isLoaded(pos)) return false;
        
        BlockState groundState = level.getBlockState(pos.below());
        if (!groundState.isSolid()) return false;
        
        BlockState standingState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        
        if (!standingState.getFluidState().isEmpty()) return false;
        if (!headState.getFluidState().isEmpty()) return false;
        
        return (standingState.isAir() || standingState.getCollisionShape(level, pos).isEmpty()) &&
               (headState.isAir() || headState.getCollisionShape(level, pos.above()).isEmpty());
    }
    
    private boolean isValidPeekPosition(BlockPos pos) {
        return isValidPeekPosition(pos, level);
    }
    
    public static Direction getDirectionFromVector(Vec3 vec) {
        if (vec == null) return Direction.NORTH;
        
        double absX = Math.abs(vec.x);
        double absZ = Math.abs(vec.z);
        
        if (absX > absZ) {
            return vec.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return vec.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    
    private double calculateScore(CoverPoint coverPoint, BlockPos soldierPos, LivingEntity threat) {
        double distancePenalty = Math.sqrt(coverPoint.distanceTo(soldierPos)) * 0.05;
        double qualityScore = coverPoint.getQuality() * 10;
        double shootBonus = coverPoint.canShootFrom() ? 2.0 : 0.0;
        
return qualityScore + shootBonus - distancePenalty;
    }

    public static boolean hasLineOfSightStatic(Vec3 from, Vec3 to, net.minecraft.world.level.Level level) {
        return VisibilityRay.trace(level, from, to, null).hasContact();
    }
    
    /**
     * Calculates how much of a cone has clear line-of-sight from peek position toward threat direction.
     * Returns a score from 0.0 (no opening) to 1.0 (full cone coverage).
     * Uses area covered and visibility quality: solid blocks stop a ray, while
     * concealment reduces its contribution without pretending to be physical cover.
    */
    public static float calculateConeCoverage(BlockPos peekPos, Vec3 threatDirection, net.minecraft.world.level.Level level) {
        Vec3 peekEye = new Vec3(peekPos.getX() + 0.5, peekPos.getY() + 1.62, peekPos.getZ() + 0.5);
        return calculateConeCoverage(peekEye, threatDirection, level);
    }

    /** Calculates open firing space from an already-resolved exposed eye position. */
    public static float calculateConeCoverage(Vec3 eyePosition, Vec3 threatDirection, net.minecraft.world.level.Level level) {
        final int RAY_COUNT = 7;
        final double CONE_HALF_ANGLE_DEG = 30.0;
        final double MAX_RAY_DISTANCE = 20.0;
        final double MIN_OPENING_DISTANCE = 5.0;

        Vec3 peekEye = eyePosition;
        Vec3 threatDir = threatDirection.normalize();
        
        double totalCoverage = 0.0;
        int validRays = 0;
        
        for (int i = 0; i < RAY_COUNT; i++) {
            double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * i / (RAY_COUNT - 1));
            
            Vec3 rayDir = rotateVectorY(threatDir, angleOffset);
            VisibilityRay.Result visibility = VisibilityRay.trace(level, peekEye,
                peekEye.add(rayDir.scale(MAX_RAY_DISTANCE)), null);
            double distance = Math.min(MAX_RAY_DISTANCE, visibility.blockedDistance());
            double laneQuality = visibility.firingLaneQuality();
            
            double normalizedDistance = distance / MAX_RAY_DISTANCE;
            
            if (distance >= MIN_OPENING_DISTANCE && laneQuality > 0.0) {
                totalCoverage += normalizedDistance * laneQuality;
                validRays++;
            }
        }
        
        if (validRays == 0) {
            return 0.0f;
        }
        
        float avgCoverage = (float) (totalCoverage / validRays);
        float validRatio = (float) validRays / RAY_COUNT;
        
        return avgCoverage * validRatio;
    }
    
    /**
     * Rotates a vector around the Y axis by the given angle (in degrees).
     */
    public static Vec3 rotateVectorY(Vec3 vec, double angleDeg) {
        double angleRad = Math.toRadians(angleDeg);
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        
        double x = vec.x * cos - vec.z * sin;
        double z = vec.x * sin + vec.z * cos;
        
        return new Vec3(x, vec.y, z).normalize();
    }
    
    /**
     * Casts a ray and returns how far it travels before hitting a solid block.
     */
    public static double raycastDistanceStatic(Vec3 start, Vec3 direction, double maxDistance, net.minecraft.world.level.Level level) {
        Vec3 end = start.add(direction.scale(maxDistance));
        VisibilityRay.Result result = VisibilityRay.trace(level, start, end, null);
        return Math.min(maxDistance, result.blockedDistance());
    }
    
    private double raycastDistance(Vec3 start, Vec3 direction, double maxDistance) {
        return raycastDistanceStatic(start, direction, maxDistance, level);
    }

    private double calculateThreatAwareScore(CoverPoint coverPoint, BlockPos soldierPos, LivingEntity threat, Vec3 threatDirection) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return calculateScore(coverPoint, soldierPos, threat);
        }
        
        double baseScore = calculateScore(coverPoint, soldierPos, threat);
        
        double protectionScore = calculatePrimaryProtection(coverPoint, threatDirection);
        
        return baseScore + protectionScore * 10.0;
    }
    
    public CoverPoint evaluatePosition(BlockPos pos, LivingEntity threat) {
        if (!isValidCoverPosition(pos)) {
            return null;
        }
        
        CoverPoint coverPoint = new CoverPoint(pos);
        
        Set<Direction> protectedDirs = new HashSet<>();
        Map<Direction, Float> coverHeights = new EnumMap<>(Direction.class);
        float maxMeasuredHeight = 0.0f;
        
        for (Direction horizontal : Direction.Plane.HORIZONTAL) {
            float coverHeight = calculateCoverHeight(pos, horizontal);
            coverPoint.setCoverHeight(horizontal, coverHeight);
            maxMeasuredHeight = Math.max(maxMeasuredHeight, coverHeight);
            if (coverHeight >= MIN_EFFECTIVE_COVER_HEIGHT) {
                protectedDirs.add(horizontal);
                coverHeights.put(horizontal, coverHeight);
            }
        }
        
        coverPoint.setProtectedDirections(protectedDirs);
        
        if (protectedDirs.isEmpty()) {
            coverPoint.setCoverHeight(maxMeasuredHeight);
            if (maxMeasuredHeight > 0.0f) {
                coverPoint.setType(CoverType.CONCEALMENT);
                coverPoint.setQuality(CoverType.CONCEALMENT.getBaseQuality());
                coverPoint.setDebugInfo(String.format("Height: %.2f | Concealment only", maxMeasuredHeight));
            } else {
                coverPoint.setType(CoverType.NONE);
                coverPoint.setQuality(0.0f);
            }
            return coverPoint;
        }
        
        if (threat != null) {
            CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
            Vec3 threatDir = threat.position().subtract(pos.getCenter());
            coverPoint = evaluator.evaluateWithCone(coverPoint, threatDir);
        } else {
            float maxHeight = coverHeights.values().stream()
                .max(Float::compare)
                .orElse(0.0f);
            coverPoint.setCoverHeight(maxHeight);
            coverPoint.setType(determineCoverType(maxHeight));
            coverPoint.setQuality(calculateGenericQuality(maxHeight, protectedDirs.size()));
            coverPoint.setCanShootFrom(maxHeight >= MIN_EFFECTIVE_COVER_HEIGHT && maxHeight < STANDING_EYE_HEIGHT);
            coverPoint.setFiringAccessScore(coverPoint.canShootFrom() ? 0.75f : 0.0f);
            coverPoint.setDebugInfo(String.format("Height: %.2f | Dirs: %d | No threat", maxHeight, protectedDirs.size()));
        }
        
        return coverPoint;
    }
    
    private boolean isValidCoverPosition(BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        
        BlockPos groundPos = pos.below();
        BlockState groundState = level.getBlockState(groundPos);
        
        if (!groundState.isSolid()) {
            return false;
        }
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos stepDown = pos.relative(dir).below();
            BlockPos twoDown = stepDown.below();
            if (!level.getBlockState(stepDown).isSolid()
                && !level.getBlockState(twoDown).isSolid()) {
                return false;
            }
        }
        
        BlockState standingState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        
        if (!standingState.getFluidState().isEmpty()) {
            return false;
        }
        
        if (!standingState.isAir() && !standingState.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        
        if (!headState.getFluidState().isEmpty()) {
            return false;
        }
        
        if (!headState.isAir() && !headState.getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        
        // Reject if a soldier's bounding box at this position would overlap a hazard block
        net.minecraft.world.phys.AABB soldierBb = new net.minecraft.world.phys.AABB(
            pos.getX() + 0.2, pos.getY(), pos.getZ() + 0.2,
            pos.getX() + 0.8, pos.getY() + 1.8, pos.getZ() + 0.8);
        if (com.stevesarmy.util.HazardBlockHelper.boundingBoxOverlapsHazard(level, soldierBb)) {
            return false;
        }
        
        boolean hasOpenEyeLevel = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(dir).above()).isAir()) {
                hasOpenEyeLevel = true;
                break;
            }
        }
        if (!hasOpenEyeLevel) {
            return false;
        }
        
        return true;
    }
    
    private float calculateCoverHeight(BlockPos soldierPos, Direction direction) {
        BlockPos adjacentPos = soldierPos.relative(direction);
        float totalHeight = 0.0f;
        
        for (int yOffset = 0; yOffset < MAX_HEIGHT_CHECK; yOffset++) {
            BlockPos checkPos = adjacentPos.above(yOffset);
            BlockState state = level.getBlockState(checkPos);
            
            if (state.isAir()) {
                break;
            }
            
            VoxelShape collisionShape = state.getCollisionShape(level, checkPos);
            if (collisionShape.isEmpty()) {
                break;
            }
            
            if (!isBlockValidCover(state, checkPos)) {
                break;
            }
            
            if (state.isCollisionShapeFullBlock(level, checkPos)) {
                totalHeight += 1.0f;
            } else {
                float partialHeight = (float) collisionShape.max(Direction.Axis.Y);
                
                if (!state.isSolid() || partialHeight < 0.5f) {
                    break;
                }
                
                totalHeight += partialHeight;
                
                if (yOffset == 0 && partialHeight < 1.0f) {
                    BlockPos abovePos = checkPos.above();
                    BlockState aboveState = level.getBlockState(abovePos);
                    if (!aboveState.isAir() && aboveState.isCollisionShapeFullBlock(level, abovePos) && isBlockValidCover(aboveState, abovePos)) {
                        totalHeight += 1.0f;
                    }
                }
            }
        }
        
        return totalHeight;
    }
    
    private boolean isBlockValidCover(BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        
        if (!state.isSolid()) {
            return false;
        }
        
        net.minecraft.world.level.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.IronBarsBlock ||
            block instanceof net.minecraft.world.level.block.GlassBlock ||
            block instanceof net.minecraft.world.level.block.StainedGlassBlock ||
            block instanceof net.minecraft.world.level.block.TintedGlassBlock ||
            block instanceof net.minecraft.world.level.block.FenceBlock ||
            block instanceof net.minecraft.world.level.block.FenceGateBlock ||
            state.is(ModBlockTags.TRANSPARENT_PENETRABLE)) {
            return false;
        }
        
        return true;
    }
    
    private CoverType determineCoverType(float coverHeight) {
        if (coverHeight >= STANDING_EYE_HEIGHT) {
            return CoverType.FULL;
        } else if (coverHeight >= MIN_EFFECTIVE_COVER_HEIGHT) {
            return CoverType.HALF;
        } else if (coverHeight > 0.0f) {
            return CoverType.CONCEALMENT;
        } else {
            return CoverType.NONE;
        }
    }
    
    private float calculateGenericQuality(float coverHeight, int protectedDirections) {
        float heightQuality;
        if (coverHeight >= 1.5f) {
            heightQuality = 1.0f;
        } else if (coverHeight >= 0.4f) {
            heightQuality = 0.5f;
        } else {
            heightQuality = 0.2f;
        }
        
        float directionBonus = protectedDirections / 4.0f * 0.15f;
        
        return Math.min(1.0f, heightQuality + directionBonus);
    }
    
    public boolean isValidCoverPositionPublic(BlockPos pos) {
        return isValidCoverPosition(pos);
    }
    
    public float calculateCoverHeightPublic(BlockPos pos, Direction dir) {
        return calculateCoverHeight(pos, dir);
    }
    
    public void debugWhyInvalid(BlockPos pos, Player player) {
        if (!level.isLoaded(pos)) {
            player.createCommandSourceStack().sendSuccess(() -> 
                net.minecraft.network.chat.Component.literal("   Reason: Position not loaded"), false);
            return;
        }
        
        BlockPos groundPos = pos.below();
        BlockState groundState = level.getBlockState(groundPos);
        if (!groundState.isSolid()) {
            player.createCommandSourceStack().sendSuccess(() -> 
                net.minecraft.network.chat.Component.literal("   Reason: Ground block at " + groundPos + " is not solid (" + groundState.getBlock() + ")"), false);
            return;
        }
        
        BlockState standingState = level.getBlockState(pos);
        if (!standingState.isAir() && !standingState.getCollisionShape(level, pos).isEmpty()) {
            player.createCommandSourceStack().sendSuccess(() -> 
                net.minecraft.network.chat.Component.literal("   Reason: Standing position blocked by " + standingState.getBlock()), false);
            return;
        }
        
        BlockState headState = level.getBlockState(pos.above());
        if (!headState.isAir() && !headState.getCollisionShape(level, pos.above()).isEmpty()) {
            player.createCommandSourceStack().sendSuccess(() -> 
                net.minecraft.network.chat.Component.literal("   Reason: Head position blocked by " + headState.getBlock()), false);
            return;
        }
        
        player.createCommandSourceStack().sendSuccess(() -> 
            net.minecraft.network.chat.Component.literal("   Unknown reason - appears valid"), false);
    }
    
    public java.util.List<Vec3> findSuppressionAimPoints(
            com.stevesarmy.entity.SoldierEntity soldier,
            BlockPos pingCenter,
            double radius) {
        
        java.util.List<Vec3> aimPoints = new java.util.ArrayList<>();
        java.util.Set<BlockPos> sampledPeekBlocks = new java.util.HashSet<>();
        
        java.util.List<CoverPoint> covers = findCoverPoints(pingCenter, (int) radius);
        
        if (covers.isEmpty()) {
            return aimPoints;
        }
        
        for (CoverPoint cover : covers) {
            java.util.Set<Direction> protectedDirs = cover.getProtectedDirections();

            if (cover.getType() == CoverType.HALF) {
                addHalfCoverOpeningAimPoints(soldier, cover, aimPoints, sampledPeekBlocks);
                continue;
            }
            
            for (Direction peekDir : Direction.Plane.HORIZONTAL) {
                if (protectedDirs.contains(peekDir)) continue;
                
                BlockPos peekPos = cover.getPosition().relative(peekDir);
                if (!isValidPeekPosition(peekPos, level)) continue;
                if (!sampledPeekBlocks.add(peekPos)) continue;
                
                // Keep the existing non-half-cover target height. Half cover uses
                // collision-shape-based opening points above instead.
                Vec3 peekTarget = peekPos.getCenter().add(0, 1.0, 0);
                
                if (VisibilityRay.traceIgnoringSmoke(level, soldier.getEyePosition(), peekTarget, soldier).hasContact()) {
                    aimPoints.add(peekTarget);
                }
            }
        }
        
        return aimPoints;
    }

    /** Adds one raycast-validated exposure point for each half-cover peek block. */
    private void addHalfCoverOpeningAimPoints(SoldierEntity soldier, CoverPoint cover,
                                               java.util.List<Vec3> aimPoints,
                                               java.util.Set<BlockPos> sampledPeekBlocks) {
        for (Direction wallDirection : cover.getProtectedDirections()) {
            BlockPos coverBlock = cover.getPosition().relative(wallDirection);
            VoxelShape shape = level.getBlockState(coverBlock).getCollisionShape(level, coverBlock);
            if (shape.isEmpty()) {
                continue;
            }
            if (!sampledPeekBlocks.add(coverBlock)) {
                continue;
            }

            double coverTop = coverBlock.getY() + shape.max(Direction.Axis.Y);
            Vec3 opening = new Vec3(coverBlock.getX() + 0.5, coverTop + 0.25,
                coverBlock.getZ() + 0.5);
            if (VisibilityRay.traceIgnoringSmoke(level, soldier.getEyePosition(), opening, soldier).hasContact()) {
                aimPoints.add(opening);
            }
        }
    }
}

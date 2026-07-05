package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.stevesarmy.StevesArmyMod;

public class CoverFinder {
    private static final int DEFAULT_SEARCH_RADIUS = 12;
    private static final int MAX_COVER_POINTS = 50;
    private static final int MAX_HEIGHT_CHECK = 3;
    
    private static final double PRIMARY_PROTECTION_WEIGHT = 0.30;
    private static final double FLANKING_PROTECTION_WEIGHT = 0.20;
    private static final double DISTANCE_WEIGHT = 0.10;
    private static final double FIRING_QUALITY_WEIGHT = 0.25;
    private static final double PEEK_ANGLE_WEIGHT = 0.15;
    
    private static final float HALF_COVER_FIGHTABILITY_BONUS = 0.25f;
    private static final float FULL_COVER_FIGHTABILITY_BONUS = 0.15f;

    // Pre-calculate inside-out search pattern to eliminate directional bias
    private static final List<BlockPos> SEARCH_OFFSETS = new ArrayList<>();
    static {
        int r = DEFAULT_SEARCH_RADIUS;
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
    
    public CoverFinder(Level level) {
        this.level = level;
    }
    
    public List<CoverPoint> findCoverPoints(BlockPos center, int radius) {
        return findCoverPoints(center, radius, null);
    }
    
    public List<CoverPoint> findCoverPoints(BlockPos center, int radius, LivingEntity threat) {
        List<CoverPoint> coverPoints = new ArrayList<>();
        int searchRadius = Math.min(radius, DEFAULT_SEARCH_RADIUS);
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
                        return coverPoints;
                    }
                }
            }
        }

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
        
        // Hard filter: prefer covers that protect from primary threat direction
        if (threatDirection != null && threatDirection.lengthSqr() > 0.001) {
            Direction threatDir = getDirectionFromVector(threatDirection);
            List<ScoredCover> protectedCovers = all.stream()
                .filter(s -> s.cover.getProtectedDirections().contains(threatDir))
                .collect(java.util.stream.Collectors.toList());
            
            if (!protectedCovers.isEmpty()) {
                StevesArmyMod.LOGGER.info("[CoverFinder] Selected protected cover: {} (threatDir={}, {} protected covers available)",
                    protectedCovers.get(0).cover.getPosition(), threatDir, protectedCovers.size());
                return Optional.of(protectedCovers.get(0).cover);
            }
            
            StevesArmyMod.LOGGER.info("[CoverFinder] No protected covers available for threatDir={}, using best unprotected cover",
                threatDir);
        }
        
        return Optional.of(all.get(0).cover);
    }

    public List<ScoredCover> findTopCovers(LivingEntity soldier, Vec3 threatDirection,
                                            List<LivingEntity> allThreats, int radius, int count, boolean includeReserved) {
        List<ScoredCover> all = evaluateAndScoreAll(soldier, threatDirection, allThreats, radius, includeReserved);
        
        // Apply hard filter: prefer protected covers when threat direction exists
        if (threatDirection != null && threatDirection.lengthSqr() > 0.001) {
            Direction threatDir = getDirectionFromVector(threatDirection);
            List<ScoredCover> protectedCovers = all.stream()
                .filter(s -> s.cover.getProtectedDirections().contains(threatDir))
                .collect(java.util.stream.Collectors.toList());
            
            if (!protectedCovers.isEmpty()) {
                return protectedCovers.subList(0, Math.min(count, protectedCovers.size()));
            }
        }
        
        return all.subList(0, Math.min(count, all.size()));
    }

    private List<ScoredCover> evaluateAndScoreAll(LivingEntity soldier, Vec3 threatDirection,
                                                   List<LivingEntity> allThreats, int radius, boolean includeReserved) {
        List<CoverPoint> coverPoints = findCoverPoints(soldier.blockPosition(), radius);
        if (coverPoints.isEmpty()) return Collections.emptyList();

        LivingEntity primaryThreat = allThreats != null && !allThreats.isEmpty() ? allThreats.get(0) : null;
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);

        for (CoverPoint coverPoint : coverPoints) {
            if (!includeReserved && !CoverReservationManager.isAvailable(coverPoint.getPosition())) {
                continue;
            }
            if (primaryThreat != null) {
                evaluator.evaluateWithRaycast(coverPoint, primaryThreat);
            }
            float score = calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats, primaryThreat);
            coverPoint.setQuality(score);
            coverPoint.setCombatScore(score);
        }

        Direction threatDir = threatDirection != null && threatDirection.lengthSqr() > 0.001 
            ? getDirectionFromVector(threatDirection) : null;
        
        List<ScoredCover> scored = coverPoints.stream()
            .filter(cp -> includeReserved || CoverReservationManager.isAvailable(cp.getPosition()))
            .filter(cp -> cp.getType() != CoverType.NONE || 
                         (threatDir != null && cp.getProtectedDirections().contains(threatDir)))
            .map(cp -> new ScoredCover(cp, cp.getCombatScore()))
            .sorted(Comparator.comparingDouble((ScoredCover s) -> s.score).reversed())
            .collect(java.util.stream.Collectors.toList());
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
                                            Vec3 threatDirection, List<LivingEntity> allThreats, LivingEntity primaryThreat) {
        StevesArmyMod.LOGGER.info("[ThreatAwareScore] coverPos={}, threatDirection=({}, {}, {}), primaryThreat={}",
            coverPoint.getPosition(),
            threatDirection != null ? String.format("%.2f", threatDirection.x) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.y) : "null",
            threatDirection != null ? String.format("%.2f", threatDirection.z) : "null",
            primaryThreat != null ? primaryThreat.blockPosition() : "null");
        
        float primaryProtection = calculatePrimaryProtection(coverPoint, threatDirection);
        
        float flankingProtection = calculateFlankingProtection(coverPoint, allThreats);
        
        float distanceScore = calculateDistanceScore(coverPoint, soldier);
        
        float firingQuality = calculateFiringQuality(coverPoint, threatDirection);
        
        float peekAngleScore = calculatePeekAngleScore(coverPoint, threatDirection, primaryThreat);
        
        float fightability = 0.0f;
        if (coverPoint.canShootFrom()) {
            fightability = coverPoint.getType() == CoverType.HALF ? 
                HALF_COVER_FIGHTABILITY_BONUS : FULL_COVER_FIGHTABILITY_BONUS;
        }
        
        // Severe penalty for full cover with no valid peek spots
        float blindPenalty = 0.0f;
        if (coverPoint.getType() == CoverType.FULL && peekAngleScore <= 0.01f && primaryThreat != null) {
            blindPenalty = 0.50f;
        }
        
        float weightedScore = (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                       flankingProtection * FLANKING_PROTECTION_WEIGHT +
                       distanceScore * DISTANCE_WEIGHT +
                       firingQuality * FIRING_QUALITY_WEIGHT +
                       peekAngleScore * PEEK_ANGLE_WEIGHT) + fightability - blindPenalty;
        
        StevesArmyMod.LOGGER.info("[CoverScore] {} type={} q={} prim={} flank={} dist={} firing={} peek={} fight={} blindPen={} TOTAL={}",
            coverPoint.getPosition(), coverPoint.getType(),
            String.format("%.2f", coverPoint.getQuality()),
            String.format("%.2f", primaryProtection * PRIMARY_PROTECTION_WEIGHT),
            String.format("%.2f", flankingProtection * FLANKING_PROTECTION_WEIGHT),
            String.format("%.2f", distanceScore * DISTANCE_WEIGHT),
            String.format("%.2f", firingQuality * FIRING_QUALITY_WEIGHT),
            String.format("%.2f", peekAngleScore * PEEK_ANGLE_WEIGHT),
            String.format("%.2f", fightability),
            String.format("%.2f", blindPenalty),
            String.format("%.2f", weightedScore));
        
        return weightedScore;
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
        
        StevesArmyMod.LOGGER.info("[PrimaryProtection] coverPos={}, threatDirection=({}, {}, {}), threatDir={}, protectedDirs={}, isProtected={}",
            coverPos,
            String.format("%.2f", threatDirection.x),
            String.format("%.2f", threatDirection.y),
            String.format("%.2f", threatDirection.z),
            threatDir,
            protectedDirs,
            isProtected);
        
        if (isProtected) {
            return coverPoint.getQuality();
        }
        
        return 0.0f;
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
    
    private float calculatePeekAngleScore(CoverPoint coverPoint, Vec3 threatDirection, LivingEntity primaryThreat) {
        if (threatDirection == null || threatDirection.lengthSqr() < 0.001) {
            return 0.0f;
        }
        
        Set<Direction> protectedDirs = coverPoint.getProtectedDirections();
        if (protectedDirs == null || protectedDirs.isEmpty()) {
            return 0.0f;
        }
        
        BlockPos coverPos = coverPoint.getPosition();
        float bestPeekScore = 0.0f;
        StringBuilder debug = new StringBuilder();
        debug.append("calculatePeekAngleScore for ").append(coverPos).append(" protectedDirs=").append(protectedDirs).append(":\n");
        
        for (Direction peekDir : Direction.Plane.HORIZONTAL) {
            if (!protectedDirs.contains(peekDir)) {
                debug.append("  ").append(peekDir).append(": not protected (no wall to peek around)\n");
                continue;
            }
            
            debug.append("  ").append(peekDir).append(": IS PROTECTED, checking...\n");
            
            BlockPos peekPos = coverPos.relative(peekDir);
            if (!isValidPeekPosition(peekPos)) {
                debug.append("    -> ").append(peekPos).append(": INVALID position (blocked or no ground)\n");
                continue;
            }
            
            debug.append("    -> ").append(peekPos).append(": valid position\n");
            
            boolean losOk = true;
            float coneCoverageScore = 1.0f;
            
            if (primaryThreat != null && primaryThreat.isAlive()) {
                // Try direct LOS to entity first
                Vec3 peekEye = new Vec3(peekPos.getX() + 0.5, peekPos.getY() + 1.62, peekPos.getZ() + 0.5);
                Vec3 targetEye = new Vec3(primaryThreat.getX(), primaryThreat.getEyeY(), primaryThreat.getZ());
                losOk = hasLineOfSight(peekEye, targetEye);
                debug.append("    Direct LOS to entity: ").append(losOk ? "SUCCESS" : "FAILED").append("\n");
                
                if (!losOk) {
                    // Direct LOS failed - fallback to cone raycast to find openings
                    debug.append("    Falling back to cone raycast...\n");
                    coneCoverageScore = calculateConeCoverage(peekPos, threatDirection, debug);
                    if (coneCoverageScore <= 0.01f) {
                        debug.append("    Cone raycast: NO OPENING FOUND, skipping this peek direction\n");
                        continue;
                    }
                    debug.append("    Cone raycast found opening, coverage=").append(String.format("%.2f", coneCoverageScore)).append("\n");
                }
            } else {
                // No primaryThreat entity - always use cone raycast
                debug.append("    No entity target, using cone raycast...\n");
                coneCoverageScore = calculateConeCoverage(peekPos, threatDirection, debug);
                if (coneCoverageScore <= 0.01f) {
                    debug.append("    Cone raycast: NO OPENING FOUND, skipping this peek direction\n");
                    continue;
                }
                debug.append("    Cone raycast: coverage=").append(String.format("%.2f", coneCoverageScore)).append("\n");
            }
            
            Vec3 peekCenter = peekPos.getCenter();
            Vec3 toThreat = threatDirection.normalize();
            Vec3 fromPeekToCover = new Vec3(
                coverPos.getX() + 0.5 - peekCenter.x,
                0,
                coverPos.getZ() + 0.5 - peekCenter.z
            ).normalize();
            
            double dot = toThreat.dot(fromPeekToCover);
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double angleBetween = Math.toDegrees(Math.acos(dot));
            
            debug.append("    dot=").append(String.format("%.3f", dot))
                .append(" angle=").append(String.format("%.1f", angleBetween))
                .append("\n");
            
            if (angleBetween >= 45 && angleBetween <= 135) {
                float angleScore = 1.0f - (float)Math.abs(angleBetween - 90) / 90;
                float finalScore = angleScore * coneCoverageScore;
                bestPeekScore = Math.max(bestPeekScore, finalScore);
                debug.append("    -> SCORED ").append(String.format("%.3f", finalScore))
                    .append(" (angle=").append(String.format("%.3f", angleScore))
                    .append(" * cone=").append(String.format("%.3f", coneCoverageScore)).append(")\n");
            }
        }
        
        debug.append("  FINAL score=").append(String.format("%.3f", bestPeekScore)).append("\n");
        StevesArmyMod.LOGGER.info(debug.toString());
        return bestPeekScore;
    }
    
    private boolean isValidPeekPosition(BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        
        BlockState groundState = level.getBlockState(pos.below());
        if (!groundState.isSolid()) return false;
        
        BlockState standingState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        
        return (standingState.isAir() || standingState.getCollisionShape(level, pos).isEmpty()) &&
               (headState.isAir() || headState.getCollisionShape(level, pos.above()).isEmpty());
    }
    
    private Direction getDirectionFromVector(Vec3 vec) {
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

    private boolean hasLineOfSight(Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );
        return level.clip(context).getType() == HitResult.Type.MISS;
    }
    
    /**
     * Calculates how much of a cone has clear line-of-sight from peek position toward threat direction.
     * Returns a score from 0.0 (no opening) to 1.0 (full cone coverage).
     * Uses "area covered" approach - measures how far each ray travels before hitting a block,
     * then normalizes by expected distance.
     */
    private float calculateConeCoverage(BlockPos peekPos, Vec3 threatDirection, StringBuilder debug) {
        final int RAY_COUNT = 7;  // Odd number for symmetric distribution
        final double CONE_HALF_ANGLE_DEG = 30.0;
        final double MAX_RAY_DISTANCE = 20.0;
        final double MIN_OPENING_DISTANCE = 5.0;
        
        Vec3 peekEye = new Vec3(peekPos.getX() + 0.5, peekPos.getY() + 1.62, peekPos.getZ() + 0.5);
        Vec3 threatDir = threatDirection.normalize();
        
        double totalCoverage = 0.0;
        int validRays = 0;
        
        debug.append("    Cone raycast from ").append(String.format("%.1f,%.1f,%.1f", peekEye.x, peekEye.y, peekEye.z))
            .append(" toward threat\n");
        
        for (int i = 0; i < RAY_COUNT; i++) {
            // Calculate angle for this ray (symmetric around center)
            double angleOffset = -CONE_HALF_ANGLE_DEG + (2.0 * CONE_HALF_ANGLE_DEG * i / (RAY_COUNT - 1));
            
            Vec3 rayDir = rotateVectorY(threatDir, angleOffset);
            double distance = raycastDistance(peekEye, rayDir, MAX_RAY_DISTANCE);
            
            // Normalize distance: 0 = blocked immediately, 1 = traveled full distance
            double normalizedDistance = distance / MAX_RAY_DISTANCE;
            
            // Only count rays that travel at least MIN_OPENING_DISTANCE
            if (distance >= MIN_OPENING_DISTANCE) {
                totalCoverage += normalizedDistance;
                validRays++;
            }
            
            debug.append("      ray ").append(i)
                .append(" angle=").append(String.format("%.1f°", angleOffset))
                .append(" dist=").append(String.format("%.1f", distance))
                .append(" norm=").append(String.format("%.2f", normalizedDistance))
                .append(" valid=").append(distance >= MIN_OPENING_DISTANCE)
                .append("\n");
        }
        
        // Score = average coverage of valid rays, weighted by how many rays found openings
        if (validRays == 0) {
            return 0.0f;
        }
        
        float avgCoverage = (float) (totalCoverage / validRays);
        float validRatio = (float) validRays / RAY_COUNT;
        
        // Final score: balance between coverage quality and how many rays found openings
        float score = avgCoverage * validRatio;
        
        debug.append("      RESULT: avgCoverage=").append(String.format("%.2f", avgCoverage))
            .append(" validRatio=").append(String.format("%.2f", validRatio))
            .append(" score=").append(String.format("%.2f", score)).append("\n");
        
        return score;
    }
    
    /**
     * Rotates a vector around the Y axis by the given angle (in degrees).
     */
    private Vec3 rotateVectorY(Vec3 vec, double angleDeg) {
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
    private double raycastDistance(Vec3 start, Vec3 direction, double maxDistance) {
        Vec3 end = start.add(direction.scale(maxDistance));
        
        ClipContext context = new ClipContext(
            start, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null
        );
        
        net.minecraft.world.phys.BlockHitResult result = level.clip(context);
        
        if (result.getType() == HitResult.Type.MISS) {
            return maxDistance;
        }
        
        return start.distanceTo(result.getLocation());
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
        
        for (Direction horizontal : Direction.Plane.HORIZONTAL) {
            float coverHeight = calculateCoverHeight(pos, horizontal);
            if (coverHeight >= 0.4f) {
                protectedDirs.add(horizontal);
                coverHeights.put(horizontal, coverHeight);
            }
        }
        
        coverPoint.setProtectedDirections(protectedDirs);
        
        if (protectedDirs.isEmpty()) {
            coverPoint.setType(CoverType.NONE);
            coverPoint.setQuality(0.0f);
            return coverPoint;
        }
        
        if (threat != null) {
            CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
            coverPoint = evaluator.evaluateWithRaycast(coverPoint, threat);
        } else {
            float maxHeight = coverHeights.values().stream()
                .max(Float::compare)
                .orElse(0.0f);
            coverPoint.setCoverHeight(maxHeight);
            coverPoint.setType(determineCoverType(maxHeight));
            coverPoint.setQuality(calculateGenericQuality(maxHeight, protectedDirs.size()));
            coverPoint.setCanShootFrom(maxHeight >= 0.4f && maxHeight < 1.5f);
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
        
        BlockState standingState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());
        
        if (!standingState.isAir() && !standingState.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        
        if (!headState.isAir() && !headState.getCollisionShape(level, pos.above()).isEmpty()) {
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
            block instanceof net.minecraft.world.level.block.TintedGlassBlock) {
            return false;
        }
        
        return true;
    }
    
    private CoverType determineCoverType(float coverHeight) {
        if (coverHeight >= 1.5f) {
            return CoverType.FULL;
        } else if (coverHeight >= 0.4f) {
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
}
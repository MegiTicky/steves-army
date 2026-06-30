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

public class CoverFinder {
    private static final int DEFAULT_SEARCH_RADIUS = 12;
    private static final int MAX_COVER_POINTS = 50;
    private static final int MAX_HEIGHT_CHECK = 3;
    
    private static final double PRIMARY_PROTECTION_WEIGHT = 0.40;
    private static final double FLANKING_PROTECTION_WEIGHT = 0.30;
    private static final double DISTANCE_WEIGHT = 0.15;
    private static final double FIRING_QUALITY_WEIGHT = 0.15;
    
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
        
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                for (int y = -searchRadius / 2; y <= searchRadius / 2; y++) {
                    BlockPos checkPos = center.offset(x, y, z);
                    
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
    
    public Optional<CoverPoint> findBestCover(LivingEntity soldier, Vec3 threatDirection, List<LivingEntity> allThreats) {
        return findBestCover(soldier, threatDirection, allThreats, DEFAULT_SEARCH_RADIUS);
    }
    
    public Optional<CoverPoint> findBestCover(LivingEntity soldier, Vec3 threatDirection, 
                                               List<LivingEntity> allThreats, int radius) {
        List<CoverPoint> coverPoints = findCoverPoints(soldier.blockPosition(), radius);
        
        if (coverPoints.isEmpty()) {
            return Optional.empty();
        }
        
        LivingEntity primaryThreat = allThreats != null && !allThreats.isEmpty() ? allThreats.get(0) : null;
        CoverQualityEvaluator evaluator = new CoverQualityEvaluator(level);
        
        for (CoverPoint coverPoint : coverPoints) {
            if (!CoverReservationManager.isAvailable(coverPoint.getPosition())) {
                continue;
            }
            
            if (primaryThreat != null) {
                evaluator.evaluateWithRaycast(coverPoint, primaryThreat);
            }
            
            float score = calculateThreatAwareScore(coverPoint, soldier, threatDirection, allThreats);
            coverPoint.setQuality(score);
        }
        
        return coverPoints.stream()
            .filter(cp -> CoverReservationManager.isAvailable(cp.getPosition()))
            .filter(cp -> cp.getType() != CoverType.NONE)
            .max(Comparator.comparingDouble(CoverPoint::getQuality));
    }
    
    private float calculateThreatAwareScore(CoverPoint coverPoint, LivingEntity soldier,
                                            Vec3 threatDirection, List<LivingEntity> allThreats) {
        float primaryProtection = calculatePrimaryProtection(coverPoint, threatDirection);
        
        float flankingProtection = calculateFlankingProtection(coverPoint, allThreats);
        
        float distanceScore = calculateDistanceScore(coverPoint, soldier);
        
        float firingScore = coverPoint.canShootFrom() ? 1.0f : 0.5f;
        
        return (float)(primaryProtection * PRIMARY_PROTECTION_WEIGHT +
                       flankingProtection * FLANKING_PROTECTION_WEIGHT +
                       distanceScore * DISTANCE_WEIGHT +
                       firingScore * FIRING_QUALITY_WEIGHT);
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
        boolean isProtected = protectedDirs.contains(threatDir.getOpposite());
        
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
            
            if (protectedDirs.contains(threatDir.getOpposite())) {
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
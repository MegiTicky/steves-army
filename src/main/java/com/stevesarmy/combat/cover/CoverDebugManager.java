package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public class CoverDebugManager {
    private static List<CoverPoint> coverPoints = Collections.emptyList();
    private static CoverPoint bestCoverPoint = null;
    private static LivingEntity threatEntity = null;
    private static boolean visualizationEnabled = false;
    private static boolean showRays = false;
    private static boolean showSolidBlocks = false;
    private static boolean showSoldierCover = false;
    private static boolean showPeekCandidates = false;
    private static final Map<Integer, PeekCandidateDebugData> soldierPeekCandidates = new HashMap<>();
    
    private static final java.util.Map<Integer, TopCoversDebugData> soldierTopCovers = new HashMap<>();
    
    public static void setSoldierTopCovers(int soldierId, TopCoversDebugData data) {
        if (data != null) {
            soldierTopCovers.put(soldierId, data);
        }
    }
    
    public static TopCoversDebugData getSoldierTopCovers(int soldierId) {
        return soldierTopCovers.get(soldierId);
    }
    
    public static class TopCoversDebugData {
        public final CoverFinder.ScoredCover[] topCovers;
        public final float currentCoverScore;
        public final float penalty;
        public final int peekCount;
        
        public TopCoversDebugData(CoverFinder.ScoredCover[] topCovers, float currentCoverScore, float penalty, int peekCount) {
            this.topCovers = topCovers;
            this.currentCoverScore = currentCoverScore;
            this.penalty = penalty;
            this.peekCount = peekCount;
        }
    }
    
    public static void setCoverPoints(List<CoverPoint> points) {
        coverPoints = points != null ? points : Collections.emptyList();
    }
    
    public static List<CoverPoint> getCoverPoints() {
        return coverPoints;
    }
    
    public static void setBestCoverPoint(CoverPoint point) {
        bestCoverPoint = point;
    }
    
    public static CoverPoint getBestCoverPoint() {
        return bestCoverPoint;
    }
    
    public static void setThreatEntity(LivingEntity entity) {
        threatEntity = entity;
    }
    
    public static LivingEntity getThreatEntity() {
        return threatEntity;
    }
    
    public static void setVisualizationEnabled(boolean enabled) {
        visualizationEnabled = enabled;
    }
    
    public static boolean isVisualizationEnabled() {
        return visualizationEnabled;
    }
    
    public static void setShowRays(boolean enabled) {
        showRays = enabled;
    }
    
    public static boolean isShowRays() {
        return showRays;
    }
    
    public static void setShowSolidBlocks(boolean enabled) {
        showSolidBlocks = enabled;
    }
    
    public static boolean isShowSolidBlocks() {
        return showSolidBlocks;
    }
    
    public static void setShowSoldierCover(boolean enabled) {
        showSoldierCover = enabled;
    }
    
    public static boolean isShowSoldierCover() {
        return showSoldierCover;
    }
    
    public static void setShowPeekCandidates(boolean enabled) {
        showPeekCandidates = enabled;
    }
    
    public static boolean isShowPeekCandidates() {
        return showPeekCandidates;
    }
    
    public static void setSoldierPeekCandidates(int soldierId, PeekCandidateDebugData data) {
        soldierPeekCandidates.put(soldierId, data);
    }
    
    public static PeekCandidateDebugData getSoldierPeekCandidates(int soldierId) {
        return soldierPeekCandidates.get(soldierId);
    }
    
    public static void clearPeekCandidates() {
        soldierPeekCandidates.clear();
    }
    
    public static void clear() {
        coverPoints = Collections.emptyList();
        bestCoverPoint = null;
        threatEntity = null;
        visualizationEnabled = false;
        showRays = false;
        showSolidBlocks = false;
        showSoldierCover = false;
        showPeekCandidates = false;
        soldierPeekCandidates.clear();
        soldierTopCovers.clear();
    }
    
    public static class PeekCandidateDebugData {
        public static final int REASON_PROTECTED_DIR = 1;
        public static final int REASON_INVALID_POS = 2;
        public static final int REASON_NO_LOS = 3;
        public static final int REASON_BAD_ANGLE = 4;
        public static final int REASON_CHOSEN = 5;
        public static final int REASON_ACCEPTED = 6;
        
        public final BlockPos coverPos;
        public final List<BlockPos> candidatePositions;
        public final List<Integer> rejectionReasons;
        public final List<Double> angleScores;
        public final List<Boolean> losResults;
        public final BlockPos chosenPosition;
        public final Vec3 targetEyePosition;
        
        public PeekCandidateDebugData(BlockPos coverPos, List<BlockPos> candidatePositions,
                                       List<Integer> rejectionReasons, List<Double> angleScores,
                                       List<Boolean> losResults, BlockPos chosenPosition,
                                       Vec3 targetEyePosition) {
            this.coverPos = coverPos;
            this.candidatePositions = candidatePositions;
            this.rejectionReasons = rejectionReasons;
            this.angleScores = angleScores;
            this.losResults = losResults;
            this.chosenPosition = chosenPosition;
            this.targetEyePosition = targetEyePosition;
        }
    }
}
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
        public static final int REASON_NONE = 0;
        public static final int REASON_CHOSEN = 1;
        public static final int REASON_RESERVED = 2;
        public static final int REASON_BLACKLISTED = 3;
        public static final int REASON_ALREADY_CURRENT = 4;
        
        public final CoverFinder.ScoredCover[] topCovers;
        public final int[] rejectionReasons;
        public final BlockPos chosenCoverPos;
        public final float currentCoverScore;
        public final float penalty;
        public final int peekCount;
        public final Map<BlockPos, BlacklistDebugEntry> blacklistInfo;
        
        public TopCoversDebugData(CoverFinder.ScoredCover[] topCovers, int[] rejectionReasons, BlockPos chosenCoverPos,
                                  float currentCoverScore, float penalty, int peekCount,
                                  Map<BlockPos, BlacklistDebugEntry> blacklistInfo) {
            this.topCovers = topCovers;
            this.rejectionReasons = rejectionReasons != null ? rejectionReasons : new int[0];
            this.chosenCoverPos = chosenCoverPos;
            this.currentCoverScore = currentCoverScore;
            this.penalty = penalty;
            this.peekCount = peekCount;
            this.blacklistInfo = blacklistInfo != null ? blacklistInfo : Collections.emptyMap();
        }
        
        public String getRejectionReason(int index) {
            if (index < 0 || index >= rejectionReasons.length) return "?";
            switch (rejectionReasons[index]) {
                case REASON_NONE: return "VALID";
                case REASON_CHOSEN: return "CHOSEN";
                case REASON_RESERVED: return "RESERVED";
                case REASON_BLACKLISTED: return "BLACKLISTED";
                case REASON_ALREADY_CURRENT: return "CURRENT";
                default: return "?";
            }
        }
        
        public String getBlacklistDetail(int index) {
            if (index < 0 || index >= topCovers.length) return "";
            BlockPos pos = topCovers[index].cover.getPosition();
            BlacklistDebugEntry entry = blacklistInfo.get(pos);
            if (entry == null) return "";
            return entry.reason + " " + entry.ageSeconds + "s";
        }
    }
    
    public static class BlacklistDebugEntry {
        public final String reason;
        public final int ageSeconds;
        
        public BlacklistDebugEntry(String reason, int ageSeconds) {
            this.reason = reason;
            this.ageSeconds = ageSeconds;
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
        public final List<Vec3> peekEyePositions;
        public final List<Float> coneCoverageScores;
        public final double soldierY;
        
        public PeekCandidateDebugData(BlockPos coverPos, List<BlockPos> candidatePositions,
                                       List<Integer> rejectionReasons, List<Double> angleScores,
                                       List<Boolean> losResults, BlockPos chosenPosition,
                                       Vec3 targetEyePosition, List<Vec3> peekEyePositions,
                                       List<Float> coneCoverageScores, double soldierY) {
            this.coverPos = coverPos;
            this.candidatePositions = candidatePositions;
            this.rejectionReasons = rejectionReasons;
            this.angleScores = angleScores;
            this.losResults = losResults;
            this.chosenPosition = chosenPosition;
            this.targetEyePosition = targetEyePosition;
            this.peekEyePositions = peekEyePositions;
            this.coneCoverageScores = coneCoverageScores;
            this.soldierY = soldierY;
        }
    }
}
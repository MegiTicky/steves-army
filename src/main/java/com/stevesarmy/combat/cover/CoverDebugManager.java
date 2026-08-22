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
    private static boolean showMachineGunners = false;
    private static MachineGunnerEvaluationDebugData machineGunnerEvaluation = null;
    private static boolean showSearchCenter = false;
    private static BlockPos searchCenterPos = null;
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

    public static void setShowMachineGunners(boolean enabled) {
        showMachineGunners = enabled;
    }

    public static boolean isShowMachineGunners() {
        return showMachineGunners;
    }

    public static void setMachineGunnerEvaluation(MachineGunnerEvaluationDebugData data) {
        machineGunnerEvaluation = data;
        visualizationEnabled = data != null;
    }

    public static MachineGunnerEvaluationDebugData getMachineGunnerEvaluation() {
        return machineGunnerEvaluation;
    }

    public static void setShowSearchCenter(boolean enabled) {
        showSearchCenter = enabled;
    }

    public static boolean isShowSearchCenter() {
        return showSearchCenter;
    }

    public static void setSearchCenterPos(BlockPos pos) {
        searchCenterPos = pos;
    }

    public static BlockPos getSearchCenterPos() {
        return searchCenterPos;
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
        showMachineGunners = false;
        machineGunnerEvaluation = null;
        showSearchCenter = false;
        searchCenterPos = null;
        soldierPeekCandidates.clear();
        soldierTopCovers.clear();
    }

    public static class MachineGunnerEvaluationDebugData {
        public final int entityId;
        public final BlockPos center;
        public final BlockPos anchor;
        public final int targetCount;
        public final int coverTargetCount;
        public final boolean gridFallback;
        public final int coverChecked;
        public final int proneChecked;
        public final int rejectedAccess;
        public final int activeTargetCount;
        public final int lastSeenCount;
        public final int peekTargetCount;
        public final List<Vec3> targets;
        public final List<FiringPositionDebugEntry> candidates;
        public final String failure;

        public MachineGunnerEvaluationDebugData(int entityId, BlockPos center, BlockPos anchor,
                                                 int targetCount, int coverTargetCount, boolean gridFallback,
                                                 int coverChecked, int proneChecked, int rejectedAccess,
                                                 int activeTargetCount, int lastSeenCount, int peekTargetCount,
                                                 List<Vec3> targets, List<FiringPositionDebugEntry> candidates,
                                                String failure) {
            this.entityId = entityId;
            this.center = center;
            this.anchor = anchor;
            this.targetCount = targetCount;
            this.coverTargetCount = coverTargetCount;
            this.gridFallback = gridFallback;
            this.coverChecked = coverChecked;
            this.proneChecked = proneChecked;
            this.rejectedAccess = rejectedAccess;
            this.activeTargetCount = activeTargetCount;
            this.lastSeenCount = lastSeenCount;
            this.peekTargetCount = peekTargetCount;
            this.targets = targets != null ? targets : Collections.emptyList();
            this.candidates = candidates != null ? candidates : Collections.emptyList();
            this.failure = failure != null ? failure : "unknown";
        }
    }

    public static class FiringPositionDebugEntry {
        public final BlockPos position;
        public final int rank;
        public final int posture;
        public final float access;
        public final float protection;
        public final float score;
        public final boolean pathChecked;
        public final boolean pathExists;
        public final boolean canReach;

        public FiringPositionDebugEntry(BlockPos position, int rank, int posture, float access,
                                       float protection, float score, boolean pathChecked,
                                       boolean pathExists, boolean canReach) {
            this.position = position;
            this.rank = rank;
            this.posture = posture;
            this.access = access;
            this.protection = protection;
            this.score = score;
            this.pathChecked = pathChecked;
            this.pathExists = pathExists;
            this.canReach = canReach;
        }
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

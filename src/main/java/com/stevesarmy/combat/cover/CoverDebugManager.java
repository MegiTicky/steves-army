package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import java.util.List;
import java.util.Collections;

public class CoverDebugManager {
    private static List<CoverPoint> coverPoints = Collections.emptyList();
    private static CoverPoint bestCoverPoint = null;
    private static LivingEntity threatEntity = null;
    private static boolean visualizationEnabled = false;
    private static boolean showRays = false;
    private static boolean showSolidBlocks = false;
    private static boolean showSoldierCover = false;
    
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
    
    public static void clear() {
        coverPoints = Collections.emptyList();
        bestCoverPoint = null;
        threatEntity = null;
        visualizationEnabled = false;
        showRays = false;
        showSolidBlocks = false;
        showSoldierCover = false;
    }
}
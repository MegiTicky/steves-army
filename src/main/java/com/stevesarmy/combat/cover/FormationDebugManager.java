package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public class FormationDebugManager {
    private static boolean visualizationEnabled = false;
    private static final Map<Integer, FormationSoldierData> soldierData = new HashMap<>();

    public static void setVisualizationEnabled(boolean enabled) {
        visualizationEnabled = enabled;
    }

    public static boolean isVisualizationEnabled() {
        return visualizationEnabled;
    }

    public static void setSoldierData(int soldierId, FormationSoldierData data) {
        if (data != null) {
            soldierData.put(soldierId, data);
        }
    }

    public static FormationSoldierData getSoldierData(int soldierId) {
        return soldierData.get(soldierId);
    }

    public static Map<Integer, FormationSoldierData> getAllSoldierData() {
        return soldierData;
    }

    public static void clear() {
        soldierData.clear();
        visualizationEnabled = false;
    }

    public static class FormationSoldierData {
        public final BlockPos targetPos;
        public final Vec3 forwardDirection;
        public final BlockPos anchorPos;
        public final BlockPos offset;
        public final int memberIndex;
        public final int squadSize;
        public final String formationName;
        public final boolean hasLeader;
        public final int leaderId;

        public FormationSoldierData(BlockPos targetPos, Vec3 forwardDirection, BlockPos anchorPos,
                                    BlockPos offset, int memberIndex, int squadSize,
                                    String formationName, boolean hasLeader, int leaderId) {
            this.targetPos = targetPos;
            this.forwardDirection = forwardDirection;
            this.anchorPos = anchorPos;
            this.offset = offset;
            this.memberIndex = memberIndex;
            this.squadSize = squadSize;
            this.formationName = formationName;
            this.hasLeader = hasLeader;
            this.leaderId = leaderId;
        }
    }
}
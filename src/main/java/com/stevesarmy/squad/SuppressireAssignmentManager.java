package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.*;

public class SuppressireAssignmentManager {

    public static void assignSuppressionTargets(
        SquadData squad,
        SquadThreatIntel intel,
        ServerLevel level,
        UUID requestingSoldierId
    ) {
        if (intel == null || level == null) {
            return;
        }

        long currentTime = level.getGameTime();
        
        for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
            if (threat.isSuppressed && threat.suppressedBy != null) {
                Entity suppressor = level.getEntity(threat.suppressedBy);
                if (suppressor == null || !suppressor.isAlive()) {
                    intel.clearThreatSuppression(threat.threatEntityId);
                    StevesArmyMod.LOGGER.info("[SuppressAssign] Cleared stale suppression for threat {} (suppressor dead)",
                        threat.threatEntityId);
                } else if (intel.isSuppressionStale(threat.threatEntityId, currentTime)) {
                    intel.clearThreatSuppression(threat.threatEntityId);
                    StevesArmyMod.LOGGER.info("[SuppressAssign] Cleared stale suppression for threat {} (heartbeat timeout)",
                        threat.threatEntityId);
                }
            }
        }
    }

    public static Optional<SquadThreatIntel.ThreatKnowledge> getAssignmentForSoldier(
        SquadThreatIntel intel,
        UUID soldierId
    ) {
        if (intel == null) return Optional.empty();
        return intel.getAssignedThreatForSoldier(soldierId);
    }

    public static void requestAssignment(
        SquadData squad,
        SquadThreatIntel intel,
        ServerLevel level,
        UUID soldierId
    ) {
        Optional<SquadThreatIntel.ThreatKnowledge> currentAssignment = getAssignmentForSoldier(intel, soldierId);
        
        if (currentAssignment.isPresent()) {
            SquadThreatIntel.ThreatKnowledge threat = currentAssignment.get();
            if (threat.isAlive && !intel.isThreatStale(threat.threatEntityId, level.getGameTime())) {
                return;
            } else {
                intel.clearThreatSuppression(threat.threatEntityId);
            }
        }

        List<SquadThreatIntel.ThreatKnowledge> unsuppressedThreats = intel.getUnsuppressedThreats();
        for (SquadThreatIntel.ThreatKnowledge threat : unsuppressedThreats) {
            if (intel.tryMarkThreatSuppressed(threat.threatEntityId, soldierId)) {
                StevesArmyMod.LOGGER.info("[SuppressAssign] Soldier {} claimed suppression of threat {} (accuracy={})",
                    soldierId, threat.threatEntityId, String.format("%.2f", threat.accuracy));
                return;
            }
        }
        
        StevesArmyMod.LOGGER.info("[SuppressAssign] Soldier {} failed to claim any threat (all already suppressed)",
            soldierId);
    }

    public static void clearAllAssignmentsForSoldier(SquadThreatIntel intel, UUID soldierId) {
        if (intel == null) return;
        intel.clearSuppressionBySoldier(soldierId);
    }
}
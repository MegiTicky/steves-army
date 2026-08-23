package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
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
        
        for (SquadThreatIntel.ThreatKnowledge threat : intel.getThreatsView()) {
            Iterator<UUID> suppressors = threat.suppressors.iterator();
            while (suppressors.hasNext()) {
                UUID suppressorId = suppressors.next();
                Entity suppressor = level.getEntity(suppressorId);
                long heartbeat = threat.suppressionHeartbeats.getOrDefault(suppressorId, 0L);
                if (suppressor == null || !suppressor.isAlive() || currentTime - heartbeat > 20) {
                    suppressors.remove();
                    threat.suppressionHeartbeats.remove(suppressorId);
                    threat.isSuppressed = !threat.suppressors.isEmpty();
                    threat.suppressedBy = threat.isSuppressed
                        ? threat.suppressors.iterator().next() : null;
                    if (DiagnosticLogManager.isSuppressionLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[SuppressAssign] Released stale suppressor {} for threat {}",
                            suppressorId, threat.threatEntityId);
                    }
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
        SquadThreatIntel.ThreatKnowledge currentAssignment =
            intel.findAssignedThreatForSoldier(soldierId);
        
        if (currentAssignment != null) {
            SquadThreatIntel.ThreatKnowledge threat = currentAssignment;
            if (threat.isAlive && !intel.isThreatStale(threat.threatEntityId, level.getGameTime())) {
                return;
            } else {
                intel.clearThreatSuppression(threat.threatEntityId);
            }
        }

        SquadThreatIntel.ThreatKnowledge threat =
            intel.tryClaimHighestAccuracyUnsuppressedThreat(soldierId, level.getGameTime());
        if (threat != null) {
            if (DiagnosticLogManager.isSuppressionLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[SuppressAssign] Soldier {} claimed suppression of threat {} (accuracy={})",
                    soldierId, threat.threatEntityId, String.format("%.2f", threat.accuracy));
            }
            return;
        }
        
        if (DiagnosticLogManager.isSuppressionLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[SuppressAssign] Soldier {} failed to claim any threat (all already suppressed)",
                soldierId);
        }
    }

    public static void clearAllAssignmentsForSoldier(SquadThreatIntel intel, UUID soldierId) {
        if (intel == null) return;
        intel.clearSuppressionBySoldier(soldierId);
    }
}

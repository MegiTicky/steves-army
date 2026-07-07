package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.stream.Collectors;

public class SuppressireAssignmentManager {

    public static void assignSuppressionTargets(
        SquadData squad,
        SquadThreatIntel intel,
        ServerLevel level,
        UUID requestingSoldierId
    ) {
        if (intel == null || level == null) return;

        List<UUID> allMemberIds = new ArrayList<>();
        allMemberIds.add(squad.getLeaderId());
        allMemberIds.addAll(squad.getMemberIds());

        for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
            if (threat.isSuppressed && threat.suppressedBy != null) {
                Entity suppressor = level.getEntity(threat.suppressedBy);
                if (suppressor == null || !suppressor.isAlive()) {
                    intel.clearThreatSuppression(threat.threatEntityId);
                    if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                        StevesArmyMod.LOGGER.info("[SuppressireAssign] Clearing stale suppression assignment for threat {} (suppressor dead)",
                            threat.threatEntityId);
                    }
                }
            }
        }

        Set<UUID> availableSoldiers = new HashSet<>();
        Map<UUID, LivingEntity> visibleTargets = new HashMap<>();

        for (UUID memberId : allMemberIds) {
            Entity member = level.getEntity(memberId);
            if (!(member instanceof SoldierEntity soldier)) continue;
            if (!soldier.isAlive()) continue;

            LivingEntity currentTarget = soldier.getTarget();
            if (currentTarget != null && currentTarget.isAlive() && 
                TargetAcquisition.hasLineOfSight(soldier, currentTarget)) {
                visibleTargets.put(memberId, currentTarget);
                continue;
            }

            boolean alreadySuppressing = intel.getAllThreats().stream()
                .anyMatch(t -> t.isSuppressed && memberId.equals(t.suppressedBy));
            
            if (!alreadySuppressing) {
                availableSoldiers.add(memberId);
            }
        }

        List<SquadThreatIntel.ThreatKnowledge> unsuppressedThreats = intel.getUnsuppressedThreats();

        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[SuppressireAssign] Squad has {} available soldiers, {} unsuppressed threats",
                availableSoldiers.size(), unsuppressedThreats.size());
        }

        Iterator<UUID> soldierIter = availableSoldiers.iterator();
        for (SquadThreatIntel.ThreatKnowledge threat : unsuppressedThreats) {
            if (!soldierIter.hasNext()) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[SuppressireAssign] No more available soldiers for threat {}",
                        threat.threatEntityId);
                }
                break;
            }

            UUID soldierId = soldierIter.next();
            intel.markThreatSuppressed(threat.threatEntityId, soldierId);

            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[SuppressireAssign] Assigned soldier {} to suppress threat {} (accuracy={})",
                    soldierId, threat.threatEntityId, String.format("%.2f", threat.accuracy));
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

        assignSuppressionTargets(squad, intel, level, soldierId);
    }

    public static void clearAllAssignmentsForSoldier(SquadThreatIntel intel, UUID soldierId) {
        if (intel == null) return;
        intel.clearSuppressionBySoldier(soldierId);
    }
}
package com.stevesarmy.util;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadLaneAssignment;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class SpacingHelper {

    private static final Map<UUID, SquadLaneAssignment> assignments = new HashMap<>();

    public static BlockPos applySpacing(BlockPos target, SoldierEntity soldier) {
        if (soldier.isCQB()) return target;

        SquadLaneAssignment assignment = getOrCreateAssignment(target, soldier);
        if (assignment == null) return target;

        SquadLaneAssignment.LaneSlot slot = assignment.getSlot(soldier.getUUID());
        if (slot == null) return target;

        BlockPos laneTarget = assignment.getLaneTarget(soldier.getUUID(), soldier.position());
        if (DiagnosticLogManager.isSpacingLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[Spacing] Soldier {} lane={}/{} offset=({}) formation={} target={}",
                soldier.getId(), slot.laneIndex, slot.totalLanes,
                slot.laneOffset, slot.forwardOffset, assignment.getFormation(), laneTarget);
        }

        return laneTarget;
    }

    public static SquadLaneAssignment getOrCreateAssignment(BlockPos target, SoldierEntity soldier) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return null;

        UUID squadId = soldier.getSquadId();
        if (squadId == null) return null;

        SquadLaneAssignment existing = assignments.get(squadId);
        if (existing != null && !existing.isExpired() && existing.getRawTarget().equals(target)) {
            return existing;
        }

        FireTeam myTeam = soldier.getFireTeam();
        SquadManager mgr = SquadManager.get(serverLevel);
        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);

        List<SoldierEntity> team = new ArrayList<>();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    if (s.position().distanceToSqr(targetCenter) < 2500) {
                        team.add(s);
                    }
                }
            }
        }
        if (soldier.position().distanceToSqr(targetCenter) < 2500) {
            team.add(soldier);
        }

        Set<SoldierEntity> unique = new LinkedHashSet<>(team);
        team = new ArrayList<>(unique);

        SquadFormation formation = soldier.getSquadFormation();
        SquadLaneAssignment assignment = new SquadLaneAssignment(squadId, target, team, formation);
        assignments.put(squadId, assignment);
        if (DiagnosticLogManager.isSpacingLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[Spacing] Created lane assignment: squad={} target={} soldiers={} formation={}",
                squadId, target, team.size(), formation);
        }
        return assignment;
    }

    public static SquadLaneAssignment createAssignment(BlockPos target, List<SoldierEntity> team) {
        if (team.isEmpty()) return null;
        UUID squadId = team.get(0).getSquadId();
        if (squadId == null) return null;
        Set<SoldierEntity> unique = new LinkedHashSet<>(team);
        List<SoldierEntity> deduped = new ArrayList<>(unique);
        // Filter to soldiers within 50 blocks of target
        Vec3 targetCenter = Vec3.atCenterOf(target);
        deduped = deduped.stream()
            .filter(s -> s.position().distanceToSqr(targetCenter) < 2500)
            .collect(java.util.stream.Collectors.toList());
        if (deduped.isEmpty()) return null;
        SquadFormation formation = deduped.get(0).getSquadFormation();
        SquadLaneAssignment assignment = new SquadLaneAssignment(squadId, target, deduped, formation);
        assignments.put(squadId, assignment);
        if (DiagnosticLogManager.isSpacingLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[Spacing] Created lane assignment via createAssignment: squad={} target={} soldiers={} formation={}",
                squadId, target, deduped.size(), formation);
        }
        return assignment;
    }

    public static SquadLaneAssignment getAssignment(UUID squadId) {
        return assignments.get(squadId);
    }

    public static void clearAssignment(UUID squadId) {
        assignments.remove(squadId);
    }

    public static List<SoldierEntity> getFireTeamSoldiers(
            List<? extends LivingEntity> allMembers,
            SoldierEntity soldier) {
        FireTeam myTeam = soldier.getFireTeam();
        List<SoldierEntity> result = new ArrayList<>();
        for (LivingEntity member : allMembers) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    result.add(s);
                }
            }
        }
        return result;
    }
}
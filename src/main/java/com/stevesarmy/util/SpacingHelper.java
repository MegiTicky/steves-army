package com.stevesarmy.util;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
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

        // Use look-ahead lane target during travel
        BlockPos laneTarget = assignment.getLaneTarget(soldier.getUUID(), soldier.position());
        StevesArmyMod.LOGGER.info("[Spacing] Soldier {} lane={}/{} fwd=({:.2f},{:.2f}) perp=({:.2f},{:.2f}) offset={:.1f} target={}",
            soldier.getId(), slot.laneIndex, slot.totalLanes,
            assignment.getForward().x, assignment.getForward().z,
            assignment.getPerpendicular().x, assignment.getPerpendicular().z,
            slot.laneOffset, laneTarget);

        return laneTarget;
    }

    public static SquadLaneAssignment getOrCreateAssignment(BlockPos target, SoldierEntity soldier) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return null;

        UUID squadId = soldier.getSquadId();
        if (squadId == null) return null;

        // Check existing assignment for this squad + target
        SquadLaneAssignment existing = assignments.get(squadId);
        if (existing != null && !existing.isExpired() && existing.getRawTarget().equals(target)) {
            return existing;
        }

        // Create new assignment
        FireTeam myTeam = soldier.getFireTeam();
        SquadManager mgr = SquadManager.get(serverLevel);
        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);

        List<SoldierEntity> team = new ArrayList<>();
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    team.add(s);
                }
            }
        }
        team.add(soldier);

        // Deduplicate
        Set<SoldierEntity> unique = new LinkedHashSet<>(team);
        team = new ArrayList<>(unique);

        SquadLaneAssignment assignment = new SquadLaneAssignment(squadId, target, team);
        assignments.put(squadId, assignment);
        StevesArmyMod.LOGGER.info("[Spacing] Created lane assignment: squad={} target={} soldiers={}",
            squadId, target, team.size());
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
package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.FormationDebugManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.util.FormationPositionCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class SoldierMoveToPingGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos targetPos;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;
    private boolean isFormationLeader;

    public SoldierMoveToPingGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.2D;
        this.closeDistance = 2.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        
        this.targetPos = soldier.getPingMoveTarget();
        boolean result = targetPos != null;
        com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] canUse={}, targetPos={}", result, targetPos);
        return result;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidPingMoveTarget()) return false;
        
        double distSq = soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        if (distSq <= closeDistance * closeDistance) {
            if (isFormationLeader) {
                disbandFormation();
            }
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        isFormationLeader = false;
        BlockPos target = getNavigationTarget();
        if (target != null) {
            boolean ok = soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] start: nav.moveTo target={} result={}", target, ok);
        }
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        
        soldier.clearPingMoveTarget();
        targetPos = null;
        isFormationLeader = false;
        FormationDebugManager.setSoldierData(soldier.getId(), null);
    }

    @Override
    public void tick() {
        soldier.getLookControl().setLookAt(
            targetPos.getX() + 0.5,
            targetPos.getY() + 1.0,
            targetPos.getZ() + 0.5,
            30.0F, 30.0F
        );
        
        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = adjustTicks(10);
            
            double distance = soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (distance > closeDistance * closeDistance) {
                BlockPos target = getNavigationTarget();
                if (target != null) {
                    soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
                }
                
                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.clearPingMoveTarget();
                soldier.getNavigation().stop();
            }
        }
    }

    private void disbandFormation() {
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        
        SquadManager mgr = SquadManager.get(serverLevel);
        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s != soldier) {
                s.clearPingMoveTarget();
                s.getNavigation().stop();
            }
        }
        StevesArmyMod.LOGGER.info("[PingGoal] Leader arrived at target - disbanded formation for {} soldiers", members.size() - 1);
    }

    private BlockPos getNavigationTarget() {
        SquadFormation formation = soldier.getSquadFormation();
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB || targetPos == null) {
            FormationDebugManager.setSoldierData(soldier.getId(), null);
            return targetPos;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            FormationDebugManager.setSoldierData(soldier.getId(), null);
            return targetPos;
        }

        SquadManager mgr = SquadManager.get(serverLevel);

        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);
        List<SoldierEntity> aliveSoldiers = new ArrayList<>();
        for (LivingEntity member : members) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                aliveSoldiers.add(s);
            }
        }
        aliveSoldiers.sort(Comparator.comparing(e -> e.getUUID()));

        int squadSize = aliveSoldiers.size();
        int memberIndex = aliveSoldiers.indexOf(soldier);

        SoldierEntity soldierLeader = aliveSoldiers.isEmpty() ? null : aliveSoldiers.get(0);

        Vec3 fwd = soldier.getFormationForwardDirection(targetPos);
        BlockPos offset = FormationPositionCalculator.getFormationOffset(fwd, formation, memberIndex, squadSize);

        BlockPos anchor;
        boolean hasLeader;
        int leaderId;
        if (soldierLeader != null && soldierLeader != soldier) {
            anchor = soldierLeader.blockPosition();
            hasLeader = true;
            leaderId = soldierLeader.getId();
            isFormationLeader = false;
        } else {
            anchor = targetPos;
            hasLeader = false;
            leaderId = -1;
            isFormationLeader = true;
        }

        BlockPos target;
        if (hasLeader) {
            target = anchor.offset(offset);
        } else {
            target = targetPos;
        }
        target = FormationPositionCalculator.adjustToSurface(soldier.level(), target);

        StevesArmyMod.LOGGER.info("[FormationTarget] MoveToPing soldier={} idx={}/{} fwd=({},{},{}) anchor={} offset={} target={}",
            soldier.getId(), memberIndex, squadSize,
            String.format("%.2f", fwd.x), String.format("%.2f", fwd.y), String.format("%.2f", fwd.z),
            anchor, offset, target);

        FormationDebugManager.setSoldierData(soldier.getId(), new FormationDebugManager.FormationSoldierData(
            target, fwd, anchor, offset, memberIndex, squadSize, formation.name(), hasLeader, leaderId));

        return target;
    }

    private int adjustTicks(int ticks) {
        return ticks;
    }
}
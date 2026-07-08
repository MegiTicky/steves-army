package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.util.FormationPositionCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

public class SoldierMoveToPingGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos targetPos;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;

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
        
        return soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ()) > (closeDistance * closeDistance);
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        BlockPos target = getFormationTarget();
        if (target != null) {
            boolean ok = soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] start: nav.moveTo formation {} result={}", target, ok);
        } else {
            boolean ok = soldier.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[PingGoal] start: nav.moveTo result={}", ok);
        }
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        
        soldier.clearPingMoveTarget();
        targetPos = null;
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
                BlockPos target = getFormationTarget();
                if (target != null) {
                    soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
                } else {
                    soldier.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier);
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

    private BlockPos getFormationTarget() {
        SquadFormation formation = soldier.getSquadFormation();
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB || targetPos == null) {
            return null;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        SquadManager mgr = SquadManager.get(serverLevel);
        int squadSize = mgr.getSquadSize(squadId);
        int memberIndex = mgr.getMemberIndex(squadId, soldier.getUUID());

        Vec3 fwd = soldier.getFormationForwardDirection(targetPos);
        BlockPos offset = FormationPositionCalculator.getFormationOffset(fwd, formation, memberIndex, squadSize);
        BlockPos target = targetPos.offset(offset);

        StevesArmyMod.LOGGER.info("[FormationTarget] MoveToPing soldier={} idx={}/{} formation={} fwd=({},{},{}) anchor={} offset={} target={}",
            soldier.getId(), memberIndex, squadSize, formation,
            String.format("%.2f", fwd.x), String.format("%.2f", fwd.y), String.format("%.2f", fwd.z),
            targetPos, offset, target);

        return target;
    }

    private int adjustTicks(int ticks) {
        return ticks;
    }
}
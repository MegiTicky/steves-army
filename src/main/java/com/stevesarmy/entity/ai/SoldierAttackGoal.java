package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
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

public class SoldierAttackGoal extends Goal {
    private final SoldierEntity soldier;
    private BlockPos targetPos;
    private final double speedModifier;
    private final float closeDistance;
    private int timeToRecalcPath;
    private boolean isFormationLeader;

    public SoldierAttackGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 0.8D;
        this.closeDistance = 4.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;

        this.targetPos = soldier.getAttackTargetPos();
        return targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive()) return false;
        if (!soldier.hasValidAttackTarget()) return false;

        double distSq = soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        if (distSq <= closeDistance * closeDistance) {
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
            soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
            StevesArmyMod.LOGGER.info("[AttackGoal] start: nav.moveTo target={}", target);
        }
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
        soldier.clearAttackTarget();
        targetPos = null;
        isFormationLeader = false;
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
            timeToRecalcPath = 10;

            double distance = soldier.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
            if (distance > closeDistance * closeDistance) {
                CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
                boolean coverIsMoving = coverState == CoverBehaviorManager.CoverState.SEEKING_COVER ||
                                        coverState == CoverBehaviorManager.CoverState.REPOSITIONING;
                if (!coverIsMoving) {
                    BlockPos target = getNavigationTarget();
                    if (target != null) {
                        soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
                    }
                }

                if (soldier.horizontalCollision || soldier.minorHorizontalCollision) {
                    soldier.getJumpControl().jump();
                }
            } else {
                soldier.clearAttackTarget();
                soldier.getNavigation().stop();
            }
        }
    }

    private BlockPos getNavigationTarget() {
        SquadFormation formation = soldier.getSquadFormation();
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB || targetPos == null) {
            return targetPos;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
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
        if (soldierLeader != null && soldierLeader != soldier) {
            anchor = soldierLeader.blockPosition();
            isFormationLeader = false;
        } else {
            anchor = targetPos;
            isFormationLeader = true;
        }

        BlockPos target;
        if (isFormationLeader) {
            target = targetPos;
        } else {
            target = anchor.offset(offset);
        }
        target = FormationPositionCalculator.adjustToSurface(soldier.level(), target);

        return target;
    }
}
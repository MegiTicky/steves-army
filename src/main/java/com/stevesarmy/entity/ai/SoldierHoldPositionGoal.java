package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.util.FormationPositionCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

public class SoldierHoldPositionGoal extends Goal {
    private static final float HOLD_RADIUS_SQ = 100.0f;
    private static final float RETURN_TO_COVER_DISTANCE_SQ = 9.0f;
    private final SoldierEntity soldier;
    private BlockPos holdPos;
    private final double speedModifier;

    public SoldierHoldPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.speedModifier = 1.0D;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                return false;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        this.holdPos = soldier.getHoldPosition();
        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return false;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        return distToHold > HOLD_RADIUS_SQ;
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getSquadMode() != SquadMode.HOLD) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                return false;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return false;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        return distToHold > HOLD_RADIUS_SQ;
    }

    @Override
    public void start() {
        BlockPos target = getFormationTarget();
        if (target != null) {
            soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
        } else if (holdPos != null) {
            soldier.getNavigation().moveTo(holdPos.getX(), holdPos.getY(), holdPos.getZ(), speedModifier);
        }
    }

    @Override
    public void stop() {
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (holdPos == null || holdPos.equals(BlockPos.ZERO)) {
            return;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint currentCover = coverManager.getCurrentCover();

        if (currentCover != null) {
            double distToCover = soldier.position().distanceToSqr(currentCover.getPosition().getCenter());
            if (distToCover <= RETURN_TO_COVER_DISTANCE_SQ) {
                soldier.getNavigation().stop();
                return;
            }
        }

        if (coverManager.getState() == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverManager.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return;
        }

        double distToHold = soldier.distanceToSqr(holdPos.getX(), holdPos.getY(), holdPos.getZ());
        if (distToHold > HOLD_RADIUS_SQ) {
            BlockPos target = getFormationTarget();
            if (target != null) {
                soldier.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), speedModifier);
            } else {
                soldier.getNavigation().moveTo(holdPos.getX(), holdPos.getY(), holdPos.getZ(), speedModifier);
            }
        } else {
            soldier.getNavigation().stop();
        }
    }

    private BlockPos getFormationTarget() {
        SquadFormation formation = soldier.getSquadFormation();
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB || holdPos == null) {
            return null;
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        SquadManager mgr = SquadManager.get(serverLevel);
        int squadSize = mgr.getSquadSize(squadId);
        int memberIndex = mgr.getMemberIndex(squadId, soldier.getUUID());

        Vec3 fwd = soldier.getFormationForwardDirection(holdPos);
        BlockPos offset = FormationPositionCalculator.getFormationOffset(fwd, formation, memberIndex, squadSize);
        BlockPos target = holdPos.offset(offset);

        StevesArmyMod.LOGGER.info("[FormationTarget] HoldPosGoal soldier={} idx={}/{} formation={} fwd=({},{},{}) anchor={} offset={} target={}",
            soldier.getId(), memberIndex, squadSize, formation,
            String.format("%.2f", fwd.x), String.format("%.2f", fwd.y), String.format("%.2f", fwd.z),
            holdPos, offset, target);

        return target;
    }
}
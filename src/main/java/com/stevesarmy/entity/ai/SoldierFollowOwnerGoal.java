package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.FormationDebugManager;
import com.stevesarmy.entity.SoldierEntity;

import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.util.FormationPositionCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class SoldierFollowOwnerGoal extends Goal {
    private final SoldierEntity soldier;
    private LivingEntity owner;
    private Level level;
    private final double speedModifier;
    private final float stopDistance;
    private final float followDistance;
    private int timeToRecalcPath;
    private BlockPos lastAnchor;
    private static final double RE_ANCHOR_THRESHOLD_SQ = 64.0; // 8 blocks

    public SoldierFollowOwnerGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.level = soldier.level();
        this.speedModifier = 1.2D;
        this.stopDistance = 10.0F;
        this.followDistance = 15.0F;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverBehaviorManager.CoverState coverState = coverManager.getState();
        if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverState == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        if (coverManager.isSuppressed()) {
            return false;
        }

        LivingEntity owner = soldier.getOwner();
        if (owner == null) {
            return false;
        }
        if (owner.isSpectator()) {
            return false;
        }
        if (soldier.distanceToSqr(owner) < (double)(followDistance * followDistance)) {
            return false;
        }

        this.owner = owner;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverBehaviorManager.CoverState coverState = coverManager.getState();
        if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER ||
            coverState == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }

        if (coverManager.isSuppressed()) {
            return false;
        }

        if (owner == null || !owner.isAlive()) {
            return false;
        }
        if (owner.isSpectator()) {
            return false;
        }
        if (soldier.distanceToSqr(owner) < (double)(stopDistance * stopDistance)) {
            return soldier.getNavigation().isDone();
        }
        return true;
    }

    @Override
    public void start() {
        timeToRecalcPath = 0;
        lastAnchor = null;
    }

    @Override
    public void stop() {
        owner = null;
        lastAnchor = null;
        soldier.getNavigation().stop();
        FormationDebugManager.setSoldierData(soldier.getId(), null);
    }

    @Override
    public void tick() {
        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = 10;
            if (!soldier.isLeashed() && !soldier.isPassenger()) {
                if (soldier.distanceToSqr(owner) >= (double)(followDistance * followDistance)) {
                    pathToOwner();
                } else {
                    BlockPos formationTarget = getFormationTarget(owner.blockPosition());
                    if (formationTarget != null) {
                        soldier.getNavigation().moveTo(
                            formationTarget.getX(), formationTarget.getY(), formationTarget.getZ(), speedModifier);
                    } else {
                        soldier.getNavigation().moveTo(owner, speedModifier);
                    }
                }
            }
        }
    }

    private void pathToOwner() {
        BlockPos ownerPos = owner.blockPosition();
        PathNavigation nav = soldier.getNavigation();

        BlockPos formationTarget = getFormationTarget(ownerPos);
        if (formationTarget != null) {
            for (int i = 0; i < 10; i++) {
                if (canPathTo(formationTarget)) {
                    nav.moveTo(formationTarget.getX(), formationTarget.getY(), formationTarget.getZ(), speedModifier);
                    return;
                }
                formationTarget = formationTarget.offset(
                    soldier.getRandom().nextIntBetweenInclusive(-1, 1),
                    0,
                    soldier.getRandom().nextIntBetweenInclusive(-1, 1));
            }
            nav.moveTo(owner, speedModifier);
            return;
        }

        for (int i = 0; i < 10; i++) {
            BlockPos targetPos = new BlockPos(
                ownerPos.getX() + soldier.getRandom().nextIntBetweenInclusive(-3, 3),
                ownerPos.getY(),
                ownerPos.getZ() + soldier.getRandom().nextIntBetweenInclusive(-3, 3)
            );

            if (canPathTo(targetPos)) {
                nav.moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), speedModifier);
                return;
            }
        }

        nav.moveTo(owner, speedModifier);
    }

    private BlockPos getFormationTarget(BlockPos anchor) {
        SquadFormation formation = soldier.getSquadFormation();
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB) {
            return null;
        }

        // Re-anchor only when player moved significantly
        BlockPos effectiveAnchor = lastAnchor != null ? lastAnchor : anchor;
        if (lastAnchor == null || anchor.distSqr(lastAnchor) > RE_ANCHOR_THRESHOLD_SQ) {
            effectiveAnchor = anchor;
            lastAnchor = anchor;
            // Reset formation slots when re-anchoring to prevent stale positions
            soldier.setFormationSlotIndex(-1);
        }

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        SquadManager mgr = SquadManager.get(serverLevel);
        List<LivingEntity> members = mgr.getSquadMembers(serverLevel, squadId, null);
        List<SoldierEntity> fireTeam = FormationPositionCalculator.getFireTeamSoldiers(members, soldier);

        if (fireTeam.isEmpty()) {
            return null;
        }

        int mySlot = FormationPositionCalculator.assignFormationSlots(fireTeam, soldier);
        int teamSize = fireTeam.size();

        Vec3 fwd = soldier.getFormationForwardDirection(effectiveAnchor);
        BlockPos target = FormationPositionCalculator.getFormationTarget(
            fwd, formation, mySlot, teamSize, effectiveAnchor, level);

        BlockPos offset = FormationPositionCalculator.getFormationOffset(fwd, formation, mySlot, teamSize);
        FormationDebugManager.setSoldierData(soldier.getId(), new FormationDebugManager.FormationSoldierData(
            target, fwd, effectiveAnchor, offset, mySlot, teamSize, formation.name(), false, -1));

        return target;
    }

    private boolean canPathTo(BlockPos pos) {
        BlockPathTypes pathType = WalkNodeEvaluator.getBlockPathTypeStatic(level, pos.mutable());
        if (pathType == BlockPathTypes.WALKABLE || pathType == BlockPathTypes.OPEN) {
            BlockState state = level.getBlockState(pos.below());
            return !(state.getBlock() instanceof LeavesBlock);
        }
        return false;
    }
}
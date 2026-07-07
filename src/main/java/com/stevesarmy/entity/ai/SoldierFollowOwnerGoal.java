package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
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
import java.util.UUID;

public class SoldierFollowOwnerGoal extends Goal {
    private static final com.stevesarmy.StevesArmyMod LOGGER = null;
    private final SoldierEntity soldier;
    private LivingEntity owner;
    private Level level;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private final float followDistance;
    private int timeToRecalcPath;

    public SoldierFollowOwnerGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.level = soldier.level();
        this.speedModifier = 1.2D;
        this.startDistance = 20.0F;
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
            coverState == CoverBehaviorManager.CoverState.REPOSITIONING ||
            coverState == CoverBehaviorManager.CoverState.IN_COVER ||
            coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
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
        if (soldier.distanceToSqr(owner) < (double)(stopDistance * stopDistance)) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("[FollowGoal] canUse=false: too close to owner (distSq={} < {})",
                soldier.distanceToSqr(owner), stopDistance * stopDistance);
            return false;
        }
        
        this.owner = owner;
        com.stevesarmy.StevesArmyMod.LOGGER.info("[FollowGoal] canUse=true, soldier={}, coverState={}, distSq={})",
            soldier.getId(), coverState, soldier.distanceToSqr(owner));
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
            coverState == CoverBehaviorManager.CoverState.REPOSITIONING ||
            coverState == CoverBehaviorManager.CoverState.IN_COVER ||
            coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
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
    }

    @Override
    public void stop() {
        owner = null;
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--timeToRecalcPath <= 0) {
            timeToRecalcPath = adjustTicks(10);
            if (!soldier.isLeashed() && !soldier.isPassenger()) {
                if (soldier.distanceToSqr(owner) >= (double)(followDistance * followDistance)) {
                    com.stevesarmy.StevesArmyMod.LOGGER.info("[FollowGoal] tick: pathToOwner (distSq={} >= {})",
                        soldier.distanceToSqr(owner), followDistance * followDistance);
                    pathToOwner();
                } else {
                    BlockPos formationTarget = getFormationTarget(owner.blockPosition());
                    if (formationTarget != null) {
                        boolean ok = soldier.getNavigation().moveTo(
                            formationTarget.getX(), formationTarget.getY(), formationTarget.getZ(), speedModifier);
                        com.stevesarmy.StevesArmyMod.LOGGER.info("[FollowGoal] tick: moveToFormation {} result={}", formationTarget, ok);
                    } else {
                        boolean ok = soldier.getNavigation().moveTo(owner, speedModifier);
                        com.stevesarmy.StevesArmyMod.LOGGER.info("[FollowGoal] tick: moveToOwner result={}", ok);
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

        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        SquadManager mgr = SquadManager.get(serverLevel);
        int squadSize = mgr.getSquadSize(squadId);
        int memberIndex = mgr.getMemberIndex(squadId, soldier.getUUID());

        Vec3 fwd = soldier.getFormationForwardDirection(anchor);
        BlockPos offset = FormationPositionCalculator.getFormationOffset(fwd, formation, memberIndex, squadSize);
        return anchor.offset(offset);
    }

    private boolean canPathTo(BlockPos pos) {
        BlockPathTypes pathType = WalkNodeEvaluator.getBlockPathTypeStatic(level, pos.mutable());
        if (pathType == BlockPathTypes.WALKABLE || pathType == BlockPathTypes.OPEN) {
            BlockState state = level.getBlockState(pos.below());
            if (state.getBlock() instanceof LeavesBlock) {
                return false;
            }
            return true;
        }
        return false;
    }

    private int adjustTicks(int ticks) {
        return ticks;
    }
}
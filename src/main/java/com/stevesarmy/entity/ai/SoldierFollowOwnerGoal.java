package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SoldierEntity;

import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.EnumSet;

public class SoldierFollowOwnerGoal extends Goal {
    private final SoldierEntity soldier;
    private LivingEntity owner;
    private Level level;
    private final double speedModifier;
    private final float startDistance;
    private final float stopDistance;
    private final float followDistance;
    private int timeToRecalcPath;
    private BlockPos holdPos;

    public SoldierFollowOwnerGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.level = soldier.level();
        this.speedModifier = 1.2D;
        this.startDistance = 15.0F;
        this.stopDistance = 10.0F;
        this.followDistance = 12.0F;
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
        if (soldier.distanceToSqr(owner) < (double)(stopDistance * stopDistance)) {
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
                    pathToOwner();
                } else {
                    soldier.getNavigation().moveTo(owner, speedModifier);
                }
            }
        }
    }

    private void pathToOwner() {
        BlockPos ownerPos = owner.blockPosition();
        PathNavigation nav = soldier.getNavigation();
        
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
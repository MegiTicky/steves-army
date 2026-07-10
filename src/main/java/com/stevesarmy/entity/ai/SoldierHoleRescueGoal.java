package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

public class SoldierHoleRescueGoal extends Goal {
    private static final int STUCK_THRESHOLD_TICKS = 100;
    private static final int RESCUE_COOLDOWN_TICKS = 200;

    private final SoldierEntity soldier;
    private BlockPos lastBlockPos;
    private int stuckTicks;
    private int cooldownTicks;

    public SoldierHoleRescueGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (soldier.level().isClientSide) return false;
        if (cooldownTicks > 0) return false;
        
        // Don't rescue if in cover - soldier is intentionally holding position
        if (soldier.getCoverBehaviorManager().isInCover()) return false;
        
        // Don't rescue if has active target (in combat)
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) return false;
        
        // Don't rescue if suppressing
        if (soldier.hasValidPingSuppressPos()) return false;
        
        // Check if actually stuck in a hole
        if (!isInHole()) return false;

        BlockPos current = soldier.blockPosition();
        if (current.equals(lastBlockPos)) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            lastBlockPos = current;
            return false;
        }

        if (stuckTicks < STUCK_THRESHOLD_TICKS) return false;

        return hasSurfaceExit();
    }

    @Override
    public void start() {
        tryRescue();
        stuckTicks = 0;
        cooldownTicks = RESCUE_COOLDOWN_TICKS;
    }

    @Override
    public void tick() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    private boolean isInHole() {
        BlockPos pos = soldier.blockPosition();
        Level level = soldier.level();
        
        // Check if surrounded by solid blocks on all horizontal sides
        int solidWalls = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState adjacent = level.getBlockState(pos.relative(dir));
            if (adjacent.isSolid() && adjacent.isCollisionShapeFullBlock(level, pos.relative(dir))) {
                solidWalls++;
            }
        }
        
        // If surrounded by 3+ solid walls, likely in a hole
        if (solidWalls >= 3) return true;
        
        // Check if can't jump out (no space above or ceiling)
        BlockState above = level.getBlockState(pos.above());
        BlockState above2 = level.getBlockState(pos.above(2));
        
        boolean hasCeiling = !above.isAir() || !above2.isAir();
        boolean feetBlocked = !level.getBlockState(pos.below()).isSolid();
        
        // In a hole if has walls + can't jump out
        return solidWalls >= 2 && hasCeiling && feetBlocked;
    }
    
    private boolean hasSurfaceExit() {
        BlockPos surfaceLevel = soldier.blockPosition().above(2);
        Level level = soldier.level();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = surfaceLevel.relative(dir);
            if (isStandable(level, candidate)) {
                return true;
            }
        }
        return false;
    }

    private void tryRescue() {
        BlockPos surfaceLevel = soldier.blockPosition().above(2);
        Level level = soldier.level();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = surfaceLevel.relative(dir);
            if (isStandable(level, candidate)) {
                soldier.teleportTo(
                    candidate.getX() + 0.5,
                    candidate.getY(),
                    candidate.getZ() + 0.5
                );
                soldier.getNavigation().stop();
                break;
            }
        }
    }

    private boolean isStandable(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        BlockState above2 = level.getBlockState(pos.above(2));

        return below.isSolid()
            && at.isAir()
            && above.isAir()
            && above2.isAir();
    }
}
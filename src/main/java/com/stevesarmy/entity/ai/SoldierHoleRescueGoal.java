package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumSet;

public class SoldierHoleRescueGoal extends Goal {
    private static final int STUCK_THRESHOLD_TICKS = 60;
    private static final int RESCUE_COOLDOWN_TICKS = 40;
    private static final double BLOCKING_WALL_HEIGHT = 1.5;
    private static final double ESCAPE_RADIUS_SQ = 6.25;

    private final SoldierEntity soldier;
    private Vec3 stuckAnchorPos;
    private int stuckTicks;
    private int lastRescueTick = -RESCUE_COOLDOWN_TICKS;

    public SoldierHoleRescueGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (soldier.level().isClientSide()) return false;
        
        // Timestamp-based cooldown check
        int ticksSinceLastRescue = soldier.tickCount - lastRescueTick;
        if (ticksSinceLastRescue < RESCUE_COOLDOWN_TICKS) {
            return false;
        }
        
        // Don't rescue if in cover - soldier is intentionally holding position
        if (soldier.getCoverBehaviorManager().isInCover()) return false;
        
        // Don't rescue if has active target (in combat)
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) return false;
        
        // Don't rescue if suppressing
        if (soldier.hasValidPingSuppressPos()) return false;
        
        Vec3 currentPos = soldier.position();
        
        // Initialize anchor if needed
        if (stuckAnchorPos == null) {
            stuckAnchorPos = currentPos;
            stuckTicks = 0;
            StevesArmyMod.LOGGER.info("[HoleRescue DEBUG] Soldier {} initialized anchor at {}", 
                soldier.getId(), currentPos);
            return false;
        }
        
        // Check if soldier escaped the anchor radius
        double distFromAnchorSq = currentPos.distanceToSqr(stuckAnchorPos);
        double distFromAnchor = Math.sqrt(distFromAnchorSq);
        
        if (distFromAnchorSq < ESCAPE_RADIUS_SQ) {
            // Still within anchor radius = stuck
            stuckTicks++;
            
            // Debug logging every 20 ticks
            if (stuckTicks % 20 == 0) {
                StevesArmyMod.LOGGER.info("[HoleRescue DEBUG] Soldier {} - distFromAnchor={}, stuckTicks={}, threshold=1.5, cooldownRemaining={}",
                    soldier.getId(), 
                    String.format("%.2f", distFromAnchor), 
                    stuckTicks,
                    RESCUE_COOLDOWN_TICKS - ticksSinceLastRescue);
            }
            
            if (stuckTicks >= STUCK_THRESHOLD_TICKS && hasSurfaceExit()) {
                StevesArmyMod.LOGGER.info("[HoleRescue DEBUG] Soldier {} STUCK condition met! stuckTicks={}, will rescue",
                    soldier.getId(), stuckTicks);
                return true;
            }
        } else {
            // Escaped the radius = making progress
            stuckAnchorPos = currentPos;
            stuckTicks = 0;
            StevesArmyMod.LOGGER.info("[HoleRescue DEBUG] Soldier {} escaped anchor (dist={}), resetting", 
                soldier.getId(), String.format("%.2f", distFromAnchor));
        }
        
        return false;
    }

    @Override
    public void start() {
        tryRescue();
        stuckTicks = 0;
        stuckAnchorPos = null;
        lastRescueTick = soldier.tickCount;  // Record exact tick of rescue
        StevesArmyMod.LOGGER.info("[HoleRescue] Soldier {} rescued at tick {}, next rescue available at tick {}",
            soldier.getId(), lastRescueTick, lastRescueTick + RESCUE_COOLDOWN_TICKS);
    }
    
    private void tryRescue() {
        BlockPos surfaceLevel = soldier.blockPosition().above(2);
        Level level = soldier.level();
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = surfaceLevel.relative(dir);
            if (isStandable(level, candidate)) {
                StevesArmyMod.LOGGER.info("[HoleRescue] Soldier {} teleported to {} (surface rescue, walls: {})",
                    soldier.getId(), candidate, countBlockingWalls());
                soldier.teleportTo(
                    candidate.getX() + 0.5,
                    candidate.getY(),
                    candidate.getZ() + 0.5
                );
                soldier.getNavigation().stop();
                return;
            }
        }
        
        // No valid rescue position found
        StevesArmyMod.LOGGER.warn("[HoleRescue] Soldier {} at {} - no valid rescue position found, stopping navigation",
            soldier.getId(), soldier.blockPosition());
        soldier.getNavigation().stop();
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

    private boolean isStandable(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        BlockState above2 = level.getBlockState(pos.above(2));

        return below.isSolid()
            && isPassable(at, level, pos)
            && isPassable(above, level, pos.above())
            && isPassable(above2, level, pos.above(2));
    }
    
    private boolean isPassable(BlockState state, Level level, BlockPos pos) {
        if (state.isAir()) return true;
        
        // No collision = passable (flowers, grass, etc.)
        VoxelShape collisionShape = state.getCollisionShape(level, pos);
        if (collisionShape.isEmpty()) return true;
        
        // Shallow water is passable (fluid level <= 4)
        if (state.is(Blocks.WATER)) {
            int fluidLevel = state.getFluidState().getAmount();
            return fluidLevel <= 4;
        }
        
        // Partial height blocks are passable if height < 1.0
        double height = collisionShape.max(Direction.Axis.Y);
        return height < 1.0;
    }
    
    private int countBlockingWalls() {
        BlockPos pos = soldier.blockPosition();
        Level level = soldier.level();
        int count = 0;
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wallPos = pos.relative(dir);
            BlockState state = level.getBlockState(wallPos);
            if (isBlockingWall(level, wallPos, state)) {
                count++;
            }
        }
        return count;
    }
    
    private boolean isBlockingWall(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        
        VoxelShape collisionShape = state.getCollisionShape(level, pos);
        if (collisionShape.isEmpty()) return false;
        
        // Full block = definitely blocking
        if (state.isCollisionShapeFullBlock(level, pos)) return true;
        
        // Height check: can soldier jump over? (jump height = 1 block, need clearance for 1.5+)
        double height = collisionShape.max(Direction.Axis.Y);
        return height >= BLOCKING_WALL_HEIGHT;
    }
}
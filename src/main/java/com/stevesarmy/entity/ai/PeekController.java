package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

public class PeekController {

    public enum State {
        HIDING,
        MOVING_TO_PEEK,
        EXPOSED,
        RETURNING_TO_COVER
    }

    private static final long EXPOSURE_TIME_MS = 1500;
    private static final long MIN_EXPOSURE_TIME_MS = 800;
    private static final long DUCK_COOLDOWN_MS = 1000;
    private static final long SUPPRESSED_HIDE_EXTRA_MS = 2000;
    private static final double PEEK_REACHED_DISTANCE = 0.05;
    private static final double RETURN_REACHED_DISTANCE = 0.5;
    private static final double PEEK_SPEED = 0.75;
    private static final double RETURN_TOLERANCE = 0.3;
    private static final double RETURN_SPEED = 1.0;
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 60;

    private State state = State.HIDING;
    private Vec3 peekTarget = Vec3.ZERO;
    private Vec3 coverReturnTarget = Vec3.ZERO;
    private BlockPos currentPeekPos = null;
    private long stateStartTime = 0;
    private long lastPeekEndTime = 0;
    private int nonPeekableTicks = 0;
    private int peekCountSameCover = 0;
    private BlockPos lastCoverPosition = null;
    private long currentMaxExposureTime = EXPOSURE_TIME_MS;

    public State getState() { return state; }

    public long getTimeInCurrentState() {
        if (stateStartTime == 0) return 0;
        return System.currentTimeMillis() - stateStartTime;
    }

    public long getTimeSinceLastPeek() {
        if (lastPeekEndTime == 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastPeekEndTime;
    }

    public void setLastPeekEndTime(long time) { this.lastPeekEndTime = time; }

    public int getPeekCountSameCover() { return peekCountSameCover; }

    public boolean isExposed() { return state == State.EXPOSED; }

    public boolean isMovingToPeek() { return state == State.MOVING_TO_PEEK; }

    public boolean isReturning() { return state == State.RETURNING_TO_COVER; }

    public boolean isHiding() { return state == State.HIDING; }

    public void reset(SoldierEntity soldier) {
        setState(soldier, State.HIDING);
        stateStartTime = 0;
        currentPeekPos = null;
        nonPeekableTicks = 0;
        currentMaxExposureTime = EXPOSURE_TIME_MS;
    }

    public void reset() {
        state = State.HIDING;
        stateStartTime = 0;
        currentPeekPos = null;
        nonPeekableTicks = 0;
        currentMaxExposureTime = EXPOSURE_TIME_MS;
    }

    private void setState(SoldierEntity soldier, State newState) {
        this.state = newState;
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekState(newState.ordinal());
        }
    }

    public void resetForNewCover(BlockPos coverPosition) {
        reset();
        if (coverPosition != null && coverPosition.equals(lastCoverPosition)) {
            peekCountSameCover = Math.max(peekCountSameCover, 0);
        } else {
            peekCountSameCover = 0;
        }
        lastCoverPosition = coverPosition;
    }

    public void recordPeekCycle(SoldierEntity soldier) {
        peekCountSameCover++;
        
        if (soldier != null) {
            soldier.getCoverBehaviorManager().recordPeekCycle();
        }
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} recordPeekCycle: count={}",
                soldier != null ? soldier.getId() : "null", peekCountSameCover);
        }
    }

    public boolean needsReposition() {
        return false; // Will be set by tickHiding
    }

    public void tick(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (cover == null) return;

        switch (state) {
            case HIDING:
                tickHiding(soldier, cover, mover);
                break;
            case MOVING_TO_PEEK:
                tickMovingToPeek(soldier, cover, mover);
                break;
            case EXPOSED:
                tickExposed(soldier, cover, mover);
                break;
            case RETURNING_TO_COVER:
                tickReturning(soldier, cover, mover);
                break;
        }
    }

    private void tickHiding(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        boolean isHalf = cover.getType() == CoverType.HALF;
        boolean isFull = cover.getType() == CoverType.FULL;

        if (soldier.getCoverBehaviorManager().isNonPeekableCover()) {
            nonPeekableTicks++;
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} non-peekable for {} ticks, repositioning",
                        soldier.getId(), nonPeekableTicks);
                }
                nonPeekableTicks = 0;
                soldier.getCoverBehaviorManager().setNonPeekableCover(false);
                setState(soldier, State.HIDING);
                return;
            }
        } else {
            nonPeekableTicks = 0;
        }

        long cooldown = soldier.getCoverBehaviorManager().isSuppressed() ?
            DUCK_COOLDOWN_MS + SUPPRESSED_HIDE_EXTRA_MS : DUCK_COOLDOWN_MS;
        if (getTimeSinceLastPeek() < cooldown) {
            return;
        }

        Vec3 threatDir = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
        if (threatDir == null || threatDir.lengthSqr() <= 0.001) {
            return;
        }

        currentMaxExposureTime = EXPOSURE_TIME_MS;
        preAimToward(soldier, threatDir);

        if (isHalf) {
            GunIntegration.crawl(soldier, true);
            lockRotationToCoverWall(soldier, cover, null);
            enterExposed(soldier, cover);
        } else if (isFull) {
            BlockPos peekPos = findBestPeekPositionTowardThreat(soldier, cover, threatDir);
            if (peekPos == null) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} no valid peek position toward threat",
                        soldier.getId());
                }
                soldier.getCoverBehaviorManager().setNonPeekableCover(true);
                return;
            }

            currentPeekPos = peekPos;
            Vec3 targetPos = peekPos.getCenter();
            Vec3 soldierPos = soldier.position();
            double dist = Math.sqrt(
                Math.pow(targetPos.x - soldierPos.x, 2) +
                Math.pow(targetPos.z - soldierPos.z, 2));

            if (dist < PEEK_REACHED_DISTANCE) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} already at peek position, exposing",
                        soldier.getId());
                }
                enterExposed(soldier, cover);
                return;
            }

            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek toward threat: moving to {}",
                    soldier.getId(), peekPos);
            }

            this.peekTarget = targetPos;
            this.coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            mover.moveTo(targetPos, PEEK_REACHED_DISTANCE, PEEK_SPEED, "PeekController", "slide to peek");
            setState(soldier, State.MOVING_TO_PEEK);
            stateStartTime = System.currentTimeMillis();
        }
    }

    private BlockPos findBestPeekPositionTowardThreat(SoldierEntity soldier, CoverPoint cover, Vec3 threatDirection) {
        BlockPos coverPos = cover.getPosition();
        BlockPos bestPeekPos = null;
        double bestScore = -1;
        
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                int distSq = dx * dx + dz * dz;
                if (distSq > 4) continue;
                
                BlockPos peekPos = coverPos.offset(dx, 0, dz);
                
                if (!isPathClearForPeek(soldier, coverPos, peekPos)) {
                    continue;
                }
                
                double score = calculatePeekPositionScore(peekPos, coverPos, threatDirection);
                if (score > bestScore) {
                    bestScore = score;
                    bestPeekPos = peekPos;
                }
            }
        }
        
        return bestPeekPos;
    }

    private double calculatePeekPositionScore(BlockPos candidate, BlockPos coverPos, Vec3 threatDirection) {
        Vec3 toPeek = new Vec3(candidate.getX() - coverPos.getX(), 0, candidate.getZ() - coverPos.getZ()).normalize();
        double alignment = toPeek.dot(threatDirection);
        double distance = Math.sqrt(Math.pow(candidate.getX() - coverPos.getX(), 2) + Math.pow(candidate.getZ() - coverPos.getZ(), 2));
        return alignment * 2.0 - distance * 0.5;
    }

    private void preAimToward(SoldierEntity soldier, Vec3 direction) {
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        soldier.setYRot((float) yaw);
        soldier.setYHeadRot((float) yaw);
        soldier.setYBodyRot((float) yaw);
    }

    private void tickMovingToPeek(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        CoverPositionController.MovementResult result = mover.getLastResult();

        if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek slide done, exposing",
                    soldier.getId());
            }
            enterExposed(soldier, cover);
        } else if (result == CoverPositionController.MovementResult.FAILED) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek slide failed, staying hidden",
                    soldier.getId());
            }
            lastPeekEndTime = System.currentTimeMillis();
            setState(soldier, State.HIDING);
            stateStartTime = 0;
        }
    }

    private void tickExposed(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        long timeInState = getTimeInCurrentState();

        if (timeInState > currentMaxExposureTime) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} exposure time exceeded ({}ms), ducking back",
                    soldier.getId(), currentMaxExposureTime);
            }
            enterReturning(soldier, cover, mover);
            return;
        }

        LivingEntity target = soldier.getTarget();
        if (target != null && !target.isAlive()) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} target dead, ducking back sooner",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover);
            return;
        }

        if (soldier.getCoverBehaviorManager().isSuppressed()) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} suppressed while exposed, ducking back",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover);
            return;
        }
    }

    private void tickReturning(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (cover == null) {
            setState(soldier, State.HIDING);
            stateStartTime = 0;
            return;
        }

        // Half cover: instant return after time
        if (cover.getType() == CoverType.HALF) {
            lockRotationToCoverWall(soldier, cover, soldier.getTarget());
            if (getTimeInCurrentState() > 200) {
                completeReturn(soldier, cover);
            }
            return;
        }

        // Full cover: check movement
        CoverPositionController.MovementResult result = mover.getLastResult();

        if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
            completeReturn(soldier, cover);
        } else if (result == CoverPositionController.MovementResult.FAILED) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} return failed, forcing HIDING",
                    soldier.getId());
            }
            completeReturn(soldier, cover);
        } else if (result == CoverPositionController.MovementResult.NONE) {
            // Not moving — start return
            coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            mover.moveTo(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED, "PeekController", "return to cover");
        }
        // IN_PROGRESS — wait
    }

    private void enterExposed(SoldierEntity soldier, CoverPoint cover) {
        setState(soldier, State.EXPOSED);
        stateStartTime = System.currentTimeMillis();
        
        boolean isHalf = cover.getType() == CoverType.HALF;
        if (isHalf) {
            GunIntegration.crawl(soldier, false);
        } else {
            CoverPositionController mover = (CoverPositionController) soldier.getMoveControl();
            mover.clear();
            soldier.getNavigation().stop();
            soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
        }
        
        soldier.refreshDimensions();

        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} state: HIDING -> EXPOSED (exposure={}ms)",
                soldier.getId(), EXPOSURE_TIME_MS);
        }
    }

    private void enterReturning(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        setState(soldier, State.RETURNING_TO_COVER);
        stateStartTime = System.currentTimeMillis();
        
        boolean isHalf = cover.getType() == CoverType.HALF;
        if (isHalf) {
            // Half cover: duck back into crawl
            GunIntegration.crawl(soldier, true);
            lockRotationToCoverWall(soldier, cover, soldier.getTarget());
        } else {
            // Full cover: start slide movement back to cover position
            coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            mover.moveTo(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED, "PeekController", "return to cover");
        }
        
        soldier.refreshDimensions();

        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} state: EXPOSED -> RETURNING_TO_COVER",
                soldier.getId());
        }
    }

    private void completeReturn(SoldierEntity soldier, CoverPoint cover) {
        lastPeekEndTime = System.currentTimeMillis();
        recordPeekCycle(soldier);
        setState(soldier, State.HIDING);
        stateStartTime = 0;
        currentPeekPos = null;
        soldier.refreshDimensions();
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} state: RETURNING_TO_COVER -> HIDING",
                soldier.getId());
        }
    }

    // --- LOS and target evaluation helpers (moved from CoverTacticalGoal) ---

    private boolean hasLineOfSight(SoldierEntity soldier, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, soldier);
        HitResult result = soldier.level().clip(context);
        return result.getType() == HitResult.Type.MISS;
    }
    
    private boolean isPathClearForPeek(SoldierEntity soldier, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        
        if (steps == 0) return true;
        
        for (int i = 1; i <= steps; i++) {
            int x = from.getX() + (dx * i) / steps;
            int z = from.getZ() + (dz * i) / steps;
            BlockPos checkPos = new BlockPos(x, from.getY(), z);
            
            net.minecraft.world.level.block.state.BlockState state = soldier.level().getBlockState(checkPos);
            if (!state.isAir() && !state.getCollisionShape(soldier.level(), checkPos).isEmpty()) {
                return false;
            }
        }
        
        return true;
    }
    
    private void lockRotationToCoverWall(SoldierEntity soldier, CoverPoint cover, LivingEntity target) {
        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs.isEmpty()) return;
        
        Direction wallDir = protectedDirs.iterator().next();
        float baseYaw = wallDir.toYRot();
        
        float offset = 0.0f;
        if (target != null && target.isAlive()) {
            Vec3 toTarget = target.position().subtract(soldier.position()).normalize();
            float targetAngle = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            float angleDiff = Mth.wrapDegrees(targetAngle - baseYaw);
            
            if (Math.abs(angleDiff) < 90) {
                offset = Math.signum(angleDiff) * 15.0f;
            }
        }
        
        float finalYaw = Mth.wrapDegrees(baseYaw + offset);
        
        soldier.setYBodyRot(finalYaw);
        soldier.setYHeadRot(finalYaw);
        soldier.yBodyRotO = finalYaw;
        soldier.yHeadRotO = finalYaw;
    }
}
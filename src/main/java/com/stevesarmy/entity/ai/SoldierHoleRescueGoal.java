package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.HazardBlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Last-resort observer. Existing goals retain movement ownership and destination selection. */
public class SoldierHoleRescueGoal extends Goal {
    private static final int REPATH_TICKS = 60;
    private static final int FALLBACK_TICKS = 180;
    private final SoldierEntity soldier;
    private Vec3 anchor;
    private Vec3 intent;
    private Vec3 recentGood;
    private int recentGoodTick;
    private int failureStart;
    private int nextAttempt;
    private Vec3 rescueDestination;

    public SoldierHoleRescueGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        int now = soldier.tickCount;
        Vec3 requested = movementTarget();
        if (excluded() || requested == null || soldier.position().distanceToSqr(requested) < .64) {
            reset();
            return false;
        }
        if (!soldier.onGround()) return false;
        if (intent == null || intent.distanceToSqr(requested) > 4) {
            intent = requested;
            anchor = soldier.position();
            failureStart = now;
            nextAttempt = now + REPATH_TICKS;
            return false;
        }
        if (anchor == null || anchor.subtract(soldier.position()).horizontalDistanceSqr() >= .25
            || Math.abs(anchor.y - soldier.getY()) >= 1.25) {
            if (safePosition(soldier.position())) {
                recentGood = soldier.position();
                recentGoodTick = now;
            }
            anchor = soldier.position();
            failureStart = now;
            nextAttempt = now + REPATH_TICKS;
            return false;
        }
        if (now < nextAttempt) return false;
        nextAttempt = now + REPATH_TICKS;

        // Recompute the owner's route first, including safe stairs/descent from roofs.
        var nav = soldier.getNavigation();
        Path route = nav.createPath(BlockPos.containing(requested), 0);
        if (route != null && route.canReach()) {
            nav.moveTo(route, recoverySpeed());
            // A reachable route deserves time, but a physically blocked route must
            // not indefinitely renew the failure window.
            if (now - failureStart < FALLBACK_TICKS) return false;
        } else if (now - failureStart < FALLBACK_TICKS) {
            nav.recomputePath();
            return false;
        }

        // Never teleport for a distant unreachable objective on ordinary flat ground.
        // Require either a real two-block pocket or a lower objective below a roof.
        boolean pit = blockingSides() >= 3;
        boolean roof = requested.y < soldier.getY() - 1.5;
        if (!pit && !roof) return false;

        if (recentGood != null && now - recentGoodTick <= 400
            && recentGood.distanceToSqr(soldier.position()) <= 36
            && safePosition(recentGood) && hasConnectedExit(recentGood, requested)) {
            rescueDestination = recentGood;
            return true;
        }

        // Bounded scan only after sustained failure (at most once per 60 ticks).
        // Try connected local paths before considering any physical relocation.
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int pathProbes = 0;
        for (int dy = -3; dy <= 2; dy++) {
            if (roof && dy >= 0 || !roof && dy <= 0) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int radius = 1; radius <= 3; radius++) {
                    Vec3 candidate = Vec3.atBottomCenterOf(soldier.blockPosition().relative(dir, radius).offset(0, dy, 0));
                    if (!safePosition(candidate)) continue;
                    if (pathProbes++ < 8) {
                        Path local = nav.createPath(BlockPos.containing(candidate), 0);
                        if (local != null && local.canReach()) {
                            nav.moveTo(local, recoverySpeed());
                            return false;
                        }
                    }
                    if (!hasConnectedExit(candidate, requested)) continue;
                    double score = candidate.distanceToSqr(requested) + candidate.distanceToSqr(soldier.position());
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        rescueDestination = best;
        return best != null;
    }

    private double recoverySpeed() {
        return soldier.getNavigation() instanceof SoldierGroundNavigation nav
            ? Math.max(.5, Math.min(1, nav.getNavSpeedModifier())) : 1;
    }

    private Vec3 movementTarget() {
        if (soldier.getMoveControl() instanceof CoverPositionController control
            && control.getLastResult() == CoverPositionController.MovementResult.IN_PROGRESS) {
            return control.getDebugTargetPos();
        }
        var nav = soldier.getNavigation();
        var path = nav.getPath();
        if (path != null && !path.isDone()) return Vec3.atBottomCenterOf(path.getTarget());
        if (nav instanceof SoldierGroundNavigation ground && ground.getRecoveryIntentTarget() != null)
            return Vec3.atBottomCenterOf(ground.getRecoveryIntentTarget());
        return null;
    }

    private boolean excluded() {
        return !soldier.isAlive() || soldier.level().isClientSide || soldier.isPassenger()
            || soldier.isInWaterOrBubble()
            || soldier.isPreparingOrReloading() || soldier.isHealing()
            || soldier.isVehicleCrewActive() || soldier.isInVehicleEngagement()
            || soldier.isNavigationTraversalLocked() || soldier.cqbHoldFreezeEffective()
            || soldier.getCoverBehaviorManager().isInCover()
            || soldier.getPeekController().isMovingToPeek() || soldier.getPeekController().isReturning();
    }

    private void reset() {
        anchor = null;
        intent = null;
        rescueDestination = null;
    }

    private int blockingSides() {
        int count = 0;
        BlockPos feet = soldier.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = feet.relative(d);
            if (!soldier.level().getBlockState(p).getCollisionShape(soldier.level(), p).isEmpty()
                && !soldier.level().getBlockState(p.above()).getCollisionShape(soldier.level(), p.above()).isEmpty()) count++;
        }
        return count;
    }

    /** Require a dry, full supporting face and clearance for the standing body. */
    private boolean safePosition(Vec3 p) {
        var level = soldier.level();
        BlockPos feet = BlockPos.containing(p);
        AABB body = new AABB(p.x - .31, p.y, p.z - .31, p.x + .31, p.y + 1.8, p.z + .31);
        if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
            || p.y < level.getMinBuildHeight() + 1 || p.y + 2 >= level.getMaxBuildHeight()
            || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
            || !level.noCollision(soldier, body) || level.containsAnyLiquid(body)
            || VS2Compat.shouldRejectNavigation(level, feet)) return false;
        for (BlockPos block : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))) {
            if (!level.hasChunkAt(block) || HazardBlockHelper.isHazardBlock(level, block)
                || !level.getFluidState(block).isEmpty()) return false;
        }
        return level.getEntities(soldier, body).isEmpty();
    }

    /** Validate destination-to-goal connectivity without moving the actor or mutating its path. */
    private boolean hasConnectedExit(Vec3 p, Vec3 requested) {
        // A remote path probe from the soldier would still start in the trap.
        // Require a dry, unobstructed local step away from the landing, directed
        // toward the owner's destination; the owner then resumes normal pathfinding.
        Vec3 delta = requested.subtract(p);
        Direction d = Math.abs(delta.x) >= Math.abs(delta.z)
            ? (delta.x >= 0 ? Direction.EAST : Direction.WEST)
            : (delta.z >= 0 ? Direction.SOUTH : Direction.NORTH);
        return safePosition(p.add(d.getStepX(), 0, d.getStepZ()));
    }

    @Override
    public void start() {
        Vec3 destination = rescueDestination;
        if (destination == null || excluded() || !safePosition(destination)) {
            reset();
            return;
        }
        soldier.getNavigation().stop();
        if (soldier.getMoveControl() instanceof CoverPositionController control) control.clear();
        soldier.teleportTo(destination.x, destination.y, destination.z);
        soldier.setDeltaMovement(Vec3.ZERO);
        soldier.fallDistance = 0;
        StevesArmyMod.LOGGER.info("[HoleRescue] Soldier {} recovered after sustained movement failure to {}", soldier.getId(), destination);
        reset();
    }

    @Override
    public boolean canContinueToUse() { return false; }
}

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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import com.stevesarmy.squad.SquadMode;

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
    private String orderKey;

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
        String key = soldier.hasValidPingMoveTarget() ? "goto:" + soldier.getPingMoveGeneration()
            : soldier.getSquadMode() == SquadMode.FOLLOW && soldier.getOwner() != null
                ? "follow:" + soldier.getOwner().getUUID() : "navigation";
        if (!key.equals(orderKey)) { reset(); orderKey = key; }
        if (!soldier.onGround()) return false;
        if (intent == null) {
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

        // A bounded component must be trapped locally; an open plain with a remote
        // unreachable order is not evidence for relocation.
        boolean roof = requested.y < soldier.getY() - 1.5;
        Set<BlockPos> region = reachableRegion(soldier.position(), 8);
        if (region.size() >= 256 || region.stream().anyMatch(p -> Math.abs(p.getX() - soldier.getBlockX()) >= 8
            || Math.abs(p.getZ() - soldier.getBlockZ()) >= 8)) return false;

        if (recentGood != null && now - recentGoodTick <= 400
            && recentGood.distanceToSqr(soldier.position()) <= 36
            && !region.contains(BlockPos.containing(recentGood))
            && safePosition(recentGood) && hasConnectedExit(recentGood, requested)) {
            rescueDestination = recentGood;
            return true;
        }

        // Bounded scan only after sustained failure (at most once per 60 ticks).
        // Try connected local paths before considering any physical relocation.
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int pathProbes = 0;
        for (int dy = -3; dy <= 3; dy++) {
            if (roof && dy >= 0 || !roof && dy < 0 || !roof && blockingSides() >= 3 && dy == 0) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int radius = 1; radius <= 6; radius++) {
                    Vec3 candidate = Vec3.atBottomCenterOf(soldier.blockPosition().relative(dir, radius).offset(0, dy, 0));
                    if (!safePosition(candidate) || region.contains(BlockPos.containing(candidate))) continue;
                    if (pathProbes++ < 8) {
                        Path local = nav.createPath(BlockPos.containing(candidate), 0);
                        if (local != null && local.canReach() && now - failureStart < FALLBACK_TICKS) {
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
        if (soldier.hasValidPingMoveTarget()) return Vec3.atBottomCenterOf(soldier.getPingMoveTarget());
        if (soldier.isGoToHolding()) return null;
        if (soldier.getSquadMode() == SquadMode.FOLLOW) {
            var owner = soldier.getOwner();
            if (owner != null && owner.isAlive() && !owner.isSpectator() && soldier.distanceToSqr(owner) >= 225)
                return owner.position();
            return null;
        }
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
            || soldier.hasValidPingSuppressPos() || soldier.getCoverBehaviorManager().isSuppressed()
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

    private boolean hasSupport(Vec3 p) {
        BlockPos support = BlockPos.containing(p.x, p.y - .01, p.z);
        var shape = soldier.level().getBlockState(support).getCollisionShape(soldier.level(), support);
        return !shape.isEmpty() && Math.abs(support.getY() + shape.max(Direction.Axis.Y) - p.y) < .02
            && shape.bounds().minX <= .19 && shape.bounds().maxX >= .81
            && shape.bounds().minZ <= .19 && shape.bounds().maxZ >= .81;
    }

    /** Require a dry, full supporting face and clearance for the standing body. */
    private boolean safePosition(Vec3 p) {
        var level = soldier.level();
        BlockPos feet = BlockPos.containing(p);
        AABB body = new AABB(p.x - .31, p.y, p.z - .31, p.x + .31, p.y + 1.8, p.z + .31);
        if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
            || p.y < level.getMinBuildHeight() + 1 || p.y + 2 >= level.getMaxBuildHeight()
            || !hasSupport(p)
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
        Set<BlockPos> exit = reachableRegion(p, 6);
        return exit.size() >= 8 && exit.stream().anyMatch(b -> Vec3.atBottomCenterOf(b).distanceToSqr(requested)
            < p.distanceToSqr(requested) - 4);
    }

    /** Small dry walk component, including one-block steps; never invokes A*. */
    private Set<BlockPos> reachableRegion(Vec3 start, int radius) {
        BlockPos origin = BlockPos.containing(start);
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && visited.size() < 256) {
            BlockPos from = queue.removeFirst();
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
                BlockPos next = from.relative(d).offset(0, dy, 0);
                if (Math.abs(next.getX() - origin.getX()) > radius || Math.abs(next.getZ() - origin.getZ()) > radius
                    || Math.abs(next.getY() - origin.getY()) > 3 || visited.contains(next)) continue;
                Vec3 point = standingPosition(next);
                if (point == null || !safePosition(point)) continue;
                // Clearance across the shared edge, including the head during a step.
                Vec3 a = standingPosition(from);
                if (a == null) a = start;
                if (Math.abs(point.y - a.y) > 1.001) continue;
                double top = Math.max(a.y, point.y);
                AABB sweep = new AABB(Math.min(a.x, point.x) - .3, top, Math.min(a.z, point.z) - .3,
                    Math.max(a.x, point.x) + .3, top + 1.8, Math.max(a.z, point.z) + .3);
                if (!soldier.level().noCollision(soldier, sweep)) continue;
                visited.add(next);
                queue.addLast(next);
            }
        }
        return visited;
    }

    private Vec3 standingPosition(BlockPos feet) {
        var level = soldier.level();
        for (int offset = 0; offset >= -1; offset--) {
            BlockPos support = feet.offset(0, offset, 0);
            var shape = level.getBlockState(support).getCollisionShape(level, support);
            if (!shape.isEmpty()) {
                double y = support.getY() + shape.max(Direction.Axis.Y);
                if (Math.abs(y - feet.getY()) <= 1) return new Vec3(feet.getX() + .5, y, feet.getZ() + .5);
            }
        }
        return null;
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

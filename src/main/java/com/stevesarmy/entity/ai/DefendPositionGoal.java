package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Holds a soldier near its defend position, patrolling the immediate area while idle. */
public class DefendPositionGoal extends Goal {

    /** Retry interval for a request whose path never closed meaningful distance (failed, unreachable, or stale-cancelled). */
    private static final int DEFEND_REPATH_FAILURE_INTERVAL_TICKS = 100;
    /** Distance the soldier must close per finished path before the retry is treated as a failure. */
    private static final double DEFEND_PATH_PROGRESS_SQ = 2.25D;

    private final SoldierEntity soldier;
    private BlockPos defendPosition;
    private int cooldown = 0;
    private int repathTimer;
    private double defendDistAtRequestSq;

    public DefendPositionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (soldier.getThreatAwareness().hasActiveThreat()) return false;
        if (soldier.getTarget() != null) return false;

        defendPosition = soldier.getDefendPosition();
        return defendPosition != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (soldier.getTarget() != null) return false;
        return defendPosition != null;
    }

    @Override
    public void tick() {
        if (defendPosition == null) return;

        double distSqr = soldier.position().distanceToSqr(defendPosition.getX() + 0.5, soldier.getY(), defendPosition.getZ() + 0.5);
        double maxDistSqr = soldier.getDefendRadius() * soldier.getDefendRadius();

        if (distSqr > maxDistSqr) {
            // Same gate as SoldierHoldPositionGoal: re-path only when the previous
            // path is finished and the interval has elapsed, instead of requesting
            // a fresh pathfinding snapshot every tick.
            if (repathTimer > 0) {
                repathTimer--;
            }
            if (repathTimer <= 0 && soldier.getNavigation().isDone()) {
                boolean stalled = defendDistAtRequestSq > 0.0D
                    && defendDistAtRequestSq - distSqr < DEFEND_PATH_PROGRESS_SQ;
                soldier.getNavigation().moveTo(defendPosition.getX(), defendPosition.getY(), defendPosition.getZ(), 1.0);
                defendDistAtRequestSq = distSqr;
                repathTimer = stalled
                    ? DEFEND_REPATH_FAILURE_INTERVAL_TICKS
                    : com.stevesarmy.StevesArmyConfig.getMoveGoalRepathTicks();
            }
        } else if (soldier.getNavigation().isDone() && --cooldown <= 0) {
            Vec3 wanderTarget = DefaultRandomPos.getPos(soldier, 8, 4);
            if (wanderTarget != null) {
                double wanderDistSqr = wanderTarget.distanceToSqr(defendPosition.getX() + 0.5, wanderTarget.y, defendPosition.getZ() + 0.5);
                if (wanderDistSqr <= maxDistSqr) {
                    soldier.getNavigation().moveTo(wanderTarget.x, wanderTarget.y, wanderTarget.z, 0.5);
                }
            }
            cooldown = 40 + soldier.getRandom().nextInt(60);
        }
    }
}

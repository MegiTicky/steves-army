package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.ai.pathfinding.AsyncSoldierPath;
import com.stevesarmy.entity.ai.pathfinding.AsyncSoldierPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

public class SoldierGroundNavigation extends GroundPathNavigation {
    private boolean asyncMoveRequest;
    private BlockPos recoveryIntentTarget;
    private int recoveryIntentTick;

    /** A short lease renewed only by the owning goal's movement requests. */
    public BlockPos getRecoveryIntentTarget() {
        return recoveryIntentTarget != null && mob.tickCount - recoveryIntentTick <= 40
            ? recoveryIntentTarget : null;
    }

    private void recordRecoveryIntent(BlockPos target) {
        recoveryIntentTarget = target.immutable();
        recoveryIntentTick = mob.tickCount;
    }
    private AsyncSoldierPath validatedAsyncPath;
    /** The pathfinder createPathFinder built — vanilla keeps it private. */
    private PathFinder soldierPathFinder;

    public SoldierGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setCanOpenDoors(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new HazardAwareWalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        if (StevesArmyConfig.isAsyncPathfindingEnabled()) {
            this.soldierPathFinder = new AsyncSoldierPathfinder(this.nodeEvaluator, maxVisitedNodes, this.level,
                () -> asyncMoveRequest);
            return this.soldierPathFinder;
        }
        this.soldierPathFinder = new PathFinder(this.nodeEvaluator, maxVisitedNodes);
        return this.soldierPathFinder;
    }

    /**
     * Public createPath calls are used synchronously by cover validation code. Movement
     * requests opt into async processing after the vanilla navigation has prepared its
     * region and called the configured pathfinder.
     */
    @Override
    public Path createPath(BlockPos target, int accuracy) {
        if (asyncMoveRequest) {
            return super.createPath(target, accuracy);
        }
        return createSynchronousPath(target, accuracy);
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        if (asyncMoveRequest) {
            return super.createPath(entity, accuracy);
        }
        return createSynchronousPath(entity.blockPosition(), accuracy);
    }

    public Path createPathToBlock(BlockPos target, int accuracy) {
        return this.createPath(target, accuracy);
    }

    /**
     * Wide-lens probe for far guides. The normal probe derives BOTH the
     * search region and {@code maxDistance} — the walked-path-length cap —
     * from FOLLOW_RANGE (32): from inside a building the door detour spends
     * ~20 walked blocks before the route even heads at the guide, so every
     * cross-building guide reads as unreachable and the search collapses to
     * a best-effort stub at the interior wall nearest the straight line.
     * Raising the lens lifts region and walked cap together, which makes the
     * real detour route reachable and returns the true full path.
     *
     * <p>The lens does NOT lift the visited-node budget (that comes from the
     * navigation's own multiplier field, default 1.0 → 512 nodes at
     * FOLLOW_RANGE 32), so a wide probe from inside a building routinely
     * exhausts its budget and returns a random-length stub. Wide probes are
     * rare and caller-paced, so the budget is raised for the duration of
     * this one call and restored after.
     */
    @javax.annotation.Nullable
    public Path createWidePath(BlockPos target, int accuracy, float lensRange) {
        // Mirrors the vanilla createPath region capture, but passes an
        // explicit visited-node budget multiplier: the default 512-node
        // budget (FOLLOW_RANGE × 16 nodes at multiplier 1.0) routinely
        // exhausts on cross-building probes and returns random-length
        // stubs. Sync call — the same corridor-aware pipeline runs on the
        // calling thread.
        BlockPos center = this.mob.blockPosition();
        int extent = (int) lensRange + 8;
        net.minecraft.world.level.PathNavigationRegion region = new net.minecraft.world.level.PathNavigationRegion(
            this.level, center.offset(-extent, -extent, -extent),
            center.offset(extent, extent, extent));
        return this.soldierPathFinder.findPath(region, this.mob,
            java.util.Collections.singleton(target), lensRange, accuracy, 8.0F);
    }

    /** Read-back of the current navigation speed (no vanilla getter in 1.20.1). */
    public double getNavSpeedModifier() {
        return this.speedModifier;
    }

    @Override
    public boolean moveTo(double x, double y, double z, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "preparing or reloading", "target=" + x + "," + y + "," + z);
            this.stop();
            return false;
        }
        if (VS2Compat.shouldRejectNavigation(this.level, BlockPos.containing(x, y, z))) {
            if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "VS2 navigation veto", "target=" + x + "," + y + "," + z);
            this.stop();
            return false;
        }
        asyncMoveRequest = true;
        try {
            boolean accepted = super.moveTo(x, y, z, speedModifier);
            recordRecoveryIntent(BlockPos.containing(x, y, z));
            if (SoldierFlightRecorder.isRecording()) traceNavigation(accepted ? "accepted" : "rejected", accepted ? "path request accepted" : "path request failed",
                "target=" + x + "," + y + "," + z + ",speed=" + speedModifier);
            return accepted;
        } finally {
            asyncMoveRequest = false;
        }
    }

    @Override
    public boolean moveTo(Entity entity, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "preparing or reloading", "entity=" + entity.getId());
            this.stop();
            return false;
        }
        if (VS2Compat.shouldRejectNavigation(entity)) {
            if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "VS2 navigation veto", "entity=" + entity.getId());
            this.stop();
            return false;
        }
        asyncMoveRequest = true;
        try {
            boolean accepted = super.moveTo(entity, speedModifier);
            recordRecoveryIntent(entity.blockPosition());
            if (SoldierFlightRecorder.isRecording()) traceNavigation(accepted ? "accepted" : "rejected", accepted ? "path request accepted" : "path request failed",
                "entity=" + entity.getId() + ",speed=" + speedModifier);
            return accepted;
        } finally {
            asyncMoveRequest = false;
        }
    }

    @Override
    public boolean moveTo(Path path, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "preparing or reloading", "path=" + pathSummary(path));
            this.stop();
            return false;
        }
        if (path != null) {
            for (int index = 0; index < path.getNodeCount(); index++) {
                if (VS2Compat.shouldRejectNavigation(this.level, path.getNode(index).asBlockPos())) {
                    if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "VS2 path node veto", "node=" + path.getNode(index).asBlockPos());
                    this.stop();
                    return false;
                }
            }
        }
        boolean accepted = super.moveTo(path, speedModifier);
        if (path != null) recordRecoveryIntent(path.getTarget());
        if (SoldierFlightRecorder.isRecording()) traceNavigation(accepted ? "accepted" : "rejected", accepted ? "path request accepted" : "path request failed",
            "path=" + pathSummary(path) + ",speed=" + speedModifier);
        return accepted;
    }

    @Override
    public void tick() {
        Path path = this.getPath();
        if (path instanceof AsyncSoldierPath asyncPath) {
            if (!asyncPath.isProcessed()) {
                if (asyncPath.isStale(this.mob.blockPosition(), this.level.getGameTime())) {
                    PerformanceMetrics.recordAsyncPathStaleCancelled();
                    if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "async path stale", "path=" + pathSummary(asyncPath));
                    this.stop();
                }
                return;
            }
            if (validatedAsyncPath != asyncPath) {
                validatedAsyncPath = asyncPath;
                if (!isValidAsyncPath(asyncPath)) {
                    if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "async path validation failed", "path=" + pathSummary(asyncPath));
                    this.stop();
                    return;
                }
            }
            if (asyncPath.getNodeCount() == 0) {
                if (SoldierFlightRecorder.isRecording()) traceNavigation("rejected", "async path has no nodes", "path=" + pathSummary(asyncPath));
                this.stop();
                return;
            }
            // A budget-exhausted partial path is still real progress toward the
            // target (vanilla A* returns best-effort partials and walks them).
            // Hard-stopping here froze any march whose search lens outgrows the
            // node budget - e.g. danger-cost detours while marching under fire -
            // because the soldier can never close distance for a cheaper retry.
            // Goal-level stall detection (no path progress) handles pathology.
        }
        super.tick();
    }

    @Override
    public void stop() {
        recoveryIntentTarget = null;
        if (SoldierFlightRecorder.isRecording()) traceNavigation("stopped", "navigation stop() called", "path=" + pathSummary(this.getPath()));
        validatedAsyncPath = null;
        super.stop();
    }

    private void traceNavigation(String event, String reason, String detail) {
        if (this.mob instanceof SoldierEntity soldier && SoldierFlightRecorder.isRecording()) {
            SoldierFlightRecorder.event(soldier, "navigation", event + ": " + reason, detail);
        }
    }

    private String pathSummary(Path path) {
        if (path == null) return "none";
        return "nodes=" + path.getNodeCount() + ",target=" + path.getTarget();
    }

    private boolean isValidAsyncPath(AsyncSoldierPath path) {
        for (int index = 0; index < path.getNodeCount(); index++) {
            if (VS2Compat.shouldRejectNavigation(this.level, path.getNode(index).asBlockPos())) {
                return false;
            }
        }
        return true;
    }

    private Path createSynchronousPath(BlockPos target, int accuracy) {
        boolean previous = asyncMoveRequest;
        asyncMoveRequest = false;
        try {
            return super.createPath(target, accuracy);
        } finally {
            asyncMoveRequest = previous;
        }
    }

    private boolean isPreparingOrReloading() {
        return this.mob instanceof SoldierEntity soldier && soldier.isPreparingOrReloading();
    }

    private boolean isCoverApproachDuringReload() {
        return this.mob instanceof SoldierEntity soldier
            && soldier.isMovingToUnoccupiedCoverDuringReload();
    }
}

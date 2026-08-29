package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.debug.PerformanceMetrics;
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
    private AsyncSoldierPath validatedAsyncPath;

    public SoldierGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setCanOpenDoors(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new HazardAwareWalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        if (StevesArmyConfig.isAsyncPathfindingEnabled()) {
            return new AsyncSoldierPathfinder(this.nodeEvaluator, maxVisitedNodes, this.level,
                () -> asyncMoveRequest);
        }
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
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

    @Override
    public boolean moveTo(double x, double y, double z, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            this.stop();
            return false;
        }
        if (VS2Compat.shouldRejectNavigation(this.level, BlockPos.containing(x, y, z))) {
            this.stop();
            return false;
        }
        asyncMoveRequest = true;
        try {
            return super.moveTo(x, y, z, speedModifier);
        } finally {
            asyncMoveRequest = false;
        }
    }

    @Override
    public boolean moveTo(Entity entity, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            this.stop();
            return false;
        }
        if (VS2Compat.shouldRejectNavigation(entity)) {
            this.stop();
            return false;
        }
        asyncMoveRequest = true;
        try {
            return super.moveTo(entity, speedModifier);
        } finally {
            asyncMoveRequest = false;
        }
    }

    @Override
    public boolean moveTo(Path path, double speedModifier) {
        if (isPreparingOrReloading() && !isCoverApproachDuringReload()) {
            this.stop();
            return false;
        }
        if (path != null) {
            for (int index = 0; index < path.getNodeCount(); index++) {
                if (VS2Compat.shouldRejectNavigation(this.level, path.getNode(index).asBlockPos())) {
                    this.stop();
                    return false;
                }
            }
        }
        return super.moveTo(path, speedModifier);
    }

    @Override
    public void tick() {
        Path path = this.getPath();
        if (path instanceof AsyncSoldierPath asyncPath) {
            if (!asyncPath.isProcessed()) {
                if (asyncPath.isStale(this.mob.blockPosition(), this.level.getGameTime())) {
                    PerformanceMetrics.recordAsyncPathStaleCancelled();
                    this.stop();
                }
                return;
            }
            if (validatedAsyncPath != asyncPath) {
                validatedAsyncPath = asyncPath;
                if (!isValidAsyncPath(asyncPath)) {
                    this.stop();
                    return;
                }
            }
            if (!asyncPath.canReach() || asyncPath.getNodeCount() == 0) {
                this.stop();
                return;
            }
        }
        super.tick();
    }

    @Override
    public void stop() {
        validatedAsyncPath = null;
        super.stop();
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

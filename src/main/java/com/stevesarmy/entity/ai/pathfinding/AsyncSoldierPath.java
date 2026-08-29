package com.stevesarmy.entity.ai.pathfinding;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** A Path placeholder which becomes readable only after worker-side A* completes. */
public final class AsyncSoldierPath extends Path {
    private enum State { WAITING, PROCESSING, COMPLETED }

    private final Set<BlockPos> requestedPositions;
    private final Supplier<Path> computation;
    private final BlockPos sourcePosition;
    private final long sourceTick;
    private final Node pendingNode;
    private volatile State state = State.WAITING;
    private volatile Path computedPath;

    public AsyncSoldierPath(Set<BlockPos> requestedPositions, BlockPos sourcePosition, long sourceTick,
                            Supplier<Path> computation) {
        super(new ArrayList<>(), BlockPos.ZERO, false);
        this.requestedPositions = Set.copyOf(requestedPositions);
        this.sourcePosition = sourcePosition.immutable();
        this.sourceTick = sourceTick;
        this.pendingNode = new Node(sourcePosition.getX(), sourcePosition.getY(), sourcePosition.getZ());
        this.computation = computation;
    }

    public boolean isProcessed() {
        return state == State.COMPLETED;
    }

    public boolean hasSameProcessingPositions(Set<BlockPos> positions) {
        return requestedPositions.equals(positions);
    }

    public boolean isStale(BlockPos currentPosition, long currentTick) {
        return !sourcePosition.equals(currentPosition)
            || currentTick - sourceTick > StevesArmyConfigAccess.maxPendingTicks();
    }

    public Set<BlockPos> requestedPositions() {
        return requestedPositions;
    }

    public void process() {
        synchronized (this) {
            if (state != State.WAITING) return;
            state = State.PROCESSING;
        }

        try {
            computedPath = computation.get();
        } catch (RuntimeException exception) {
            StevesArmyMod.LOGGER.warn("Async soldier path computation failed", exception);
            computedPath = null;
        } finally {
            state = State.COMPLETED;
        }
    }

    private Path readyPath() {
        return isProcessed() ? computedPath : null;
    }

    @Override
    public BlockPos getTarget() {
        Path path = readyPath();
        return path == null ? requestedPositions.iterator().next() : path.getTarget();
    }

    @Override
    public float getDistToTarget() {
        Path path = readyPath();
        return path == null ? Float.MAX_VALUE : path.getDistToTarget();
    }

    @Override
    public boolean canReach() {
        Path path = readyPath();
        return path != null && path.canReach();
    }

    @Override
    public boolean isDone() {
        Path path = readyPath();
        return path != null && path.isDone();
    }

    @Override
    public void advance() {
        Path path = readyPath();
        if (path != null) path.advance();
    }

    @Override
    public boolean notStarted() {
        Path path = readyPath();
        return path == null || path.notStarted();
    }

    @Override
    public Node getEndNode() {
        Path path = readyPath();
        return path == null ? pendingNode : path.getEndNode();
    }

    @Override
    public Node getNode(int index) {
        Path path = readyPath();
        if (path == null) {
            if (index == 0) return pendingNode;
            throw new IndexOutOfBoundsException(index);
        }
        return path.getNode(index);
    }

    @Override
    public int getNodeCount() {
        Path path = readyPath();
        return path == null ? 1 : path.getNodeCount();
    }

    @Override
    public int getNextNodeIndex() {
        Path path = readyPath();
        return path == null ? 0 : path.getNextNodeIndex();
    }

    @Override
    public void setNextNodeIndex(int nodeIndex) {
        Path path = readyPath();
        if (path != null) path.setNextNodeIndex(nodeIndex);
    }

    @Override
    public Vec3 getEntityPosAtNode(Entity entity, int index) {
        Path path = readyPath();
        return path == null ? entity.position() : path.getEntityPosAtNode(entity, index);
    }

    @Override
    public Vec3 getNextEntityPos(Entity entity) {
        Path path = readyPath();
        return path == null ? entity.position() : path.getNextEntityPos(entity);
    }

    @Override
    public BlockPos getNodePos(int index) {
        Path path = readyPath();
        return path == null ? pendingNode.asBlockPos() : path.getNodePos(index);
    }

    @Override
    public BlockPos getNextNodePos() {
        Path path = readyPath();
        return path == null ? pendingNode.asBlockPos() : path.getNextNodePos();
    }

    @Override
    public Node getNextNode() {
        Path path = readyPath();
        if (path == null) return pendingNode;
        return path.getNextNode();
    }

    @Override
    public Node getPreviousNode() {
        Path path = readyPath();
        return path == null ? null : path.getPreviousNode();
    }

    @Override
    public void truncateNodes(int length) {
        Path path = readyPath();
        if (path != null) path.truncateNodes(length);
    }

    @Override
    public void replaceNode(int index, Node node) {
        Path path = readyPath();
        if (path != null) path.replaceNode(index, node);
    }

    @Override
    public boolean sameAs(Path other) {
        if (other == this) return true;
        Path path = readyPath();
        return path != null && path.sameAs(other);
    }

    /** Avoid a dependency cycle from the path object into the config class. */
    private static final class StevesArmyConfigAccess {
        private static int maxPendingTicks() {
            return com.stevesarmy.StevesArmyConfig.getAsyncPathfindingMaxPendingTicks();
        }
    }
}

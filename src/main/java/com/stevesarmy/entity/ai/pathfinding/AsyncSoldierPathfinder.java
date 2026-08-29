package com.stevesarmy.entity.ai.pathfinding;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.PerformanceMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Prepares a vanilla path search on the server thread and runs only the A* graph
 * traversal on a worker. This follows Villager Recruits' AsyncPathfinder split.
 */
public final class AsyncSoldierPathfinder extends PathFinder {
    private final int maxVisitedNodes;
    private final Level level;
    private final BooleanSupplier asyncRequest;
    private final NodeEvaluator synchronousEvaluator;

    public AsyncSoldierPathfinder(NodeEvaluator unusedEvaluator, int maxVisitedNodes, Level level,
                                  BooleanSupplier asyncRequest) {
        super(unusedEvaluator, maxVisitedNodes);
        this.maxVisitedNodes = maxVisitedNodes;
        this.level = level;
        this.asyncRequest = asyncRequest;
        this.synchronousEvaluator = unusedEvaluator;
    }

    @Override
    public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> positions,
                         float maxDistance, int reachRange, float maxVisitedNodesMultiplier) {
        if (positions.isEmpty()) return null;
        if (!asyncRequest.getAsBoolean()) {
            return new PathFinder(synchronousEvaluator, maxVisitedNodes)
                .findPath(region, mob, positions, maxDistance, reachRange, maxVisitedNodesMultiplier);
        }

        HazardAwarePathPreparation preparation = prepare(region, mob, positions, maxDistance, reachRange);
        if (preparation == null) {
            PerformanceMetrics.recordAsyncPathFallback();
            return new PathFinder(synchronousEvaluator, maxVisitedNodes)
                .findPath(region, mob, positions, maxDistance, reachRange, maxVisitedNodesMultiplier);
        }

        PerformanceMetrics.recordAsyncPathRequest();
        Set<BlockPos> requestedPositions = Set.copyOf(positions);
        AsyncSoldierPath path = new AsyncSoldierPath(requestedPositions, mob.blockPosition(),
            level.getGameTime(), () -> {
            try {
                return processPath(preparation.evaluator(), preparation.start(), preparation.targets(),
                    maxDistance, reachRange, maxVisitedNodesMultiplier);
            } finally {
                NodeEvaluatorPool.release(preparation.evaluator());
            }
        });

        if (!AsyncPathfindingService.submit(path::process)) {
            PerformanceMetrics.recordAsyncPathFallback();
            path.process();
        }
        return path;
    }

    private HazardAwarePathPreparation prepare(PathNavigationRegion region, Mob mob,
                                                Set<BlockPos> positions, float maxDistance,
        int reachRange) {
        NodeEvaluator evaluator = NodeEvaluatorPool.take();
        boolean prepared = false;
        try {
            SnapshotPathNavigationRegion snapshot = SnapshotPathNavigationRegion.capture(
                level, region, mob, maxDistance, reachRange);
            evaluator.prepare(snapshot, PathfindingMobSnapshot.capture(mob));
            prepared = true;
            Node start = evaluator.getStart();
            if (start == null) {
                NodeEvaluatorPool.release(evaluator);
                return null;
            }

            List<Map.Entry<Target, BlockPos>> targets = new ArrayList<>(positions.size());
            for (BlockPos position : positions) {
                targets.add(new AbstractMap.SimpleImmutableEntry<>(
                    evaluator.getGoal(position.getX(), position.getY(), position.getZ()),
                    position.immutable()));
            }
            return new HazardAwarePathPreparation(evaluator, start, List.copyOf(targets));
        } catch (RuntimeException exception) {
            if (prepared) {
                NodeEvaluatorPool.release(evaluator);
            } else {
                NodeEvaluatorPool.discard(evaluator);
            }
            StevesArmyMod.LOGGER.warn("Failed to prepare async soldier path", exception);
            return null;
        }
    }

    private Path processPath(NodeEvaluator evaluator, Node start,
                             List<Map.Entry<Target, BlockPos>> targets,
                             float maxDistance, int reachRange,
                             float maxVisitedNodesMultiplier) {
        long workerStart = System.nanoTime();
        try {
            return processPathInternal(evaluator, start, targets, maxDistance, reachRange,
                maxVisitedNodesMultiplier);
        } finally {
            PerformanceMetrics.recordAsyncPathCompleted(System.nanoTime() - workerStart);
        }
    }

    private Path processPathInternal(NodeEvaluator evaluator, Node start,
                             List<Map.Entry<Target, BlockPos>> targets,
                             float maxDistance, int reachRange,
                             float maxVisitedNodesMultiplier) {
        start.g = 0.0F;
        start.h = getBestHeuristic(start, targets);
        start.f = start.h;

        BinaryHeap openSet = new BinaryHeap();
        openSet.insert(start);
        Node[] neighbors = new Node[32];
        Set<Target> reachedTargets = new HashSet<>();
        int maxNodes = Math.max(1, (int) (maxVisitedNodes * maxVisitedNodesMultiplier));
        int visited = 0;

        while (!openSet.isEmpty() && visited++ < maxNodes) {
            Node node = openSet.pop();
            node.closed = true;

            for (Map.Entry<Target, BlockPos> entry : targets) {
                if (node.distanceManhattan(entry.getKey()) <= reachRange) {
                    entry.getKey().setReached();
                    reachedTargets.add(entry.getKey());
                }
            }
            if (!reachedTargets.isEmpty()) break;

            if (node.distanceTo(start) >= maxDistance) continue;
            int neighborCount = evaluator.getNeighbors(neighbors, node);
            for (int index = 0; index < neighborCount; index++) {
                Node neighbor = neighbors[index];
                float distance = distance(node, neighbor);
                neighbor.walkedDistance = node.walkedDistance + distance;
                float cost = node.g + distance + neighbor.costMalus;
                if (neighbor.walkedDistance < maxDistance
                    && (!neighbor.inOpenSet() || cost < neighbor.g)) {
                    neighbor.cameFrom = node;
                    neighbor.g = cost;
                    neighbor.h = getBestHeuristic(neighbor, targets) * 1.5F;
                    if (neighbor.inOpenSet()) {
                        openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                    } else {
                        neighbor.f = neighbor.g + neighbor.h;
                        openSet.insert(neighbor);
                    }
                }
            }
        }

        Map<Target, BlockPos> targetToPos = new java.util.HashMap<>();
        for (Map.Entry<Target, BlockPos> entry : targets) {
            targetToPos.put(entry.getKey(), entry.getValue());
        }

        boolean reachedAny = !reachedTargets.isEmpty();
        Path best = null;
        if (reachedAny) {
            best = reachedTargets.stream()
                .map(t -> reconstructPath(t.getBestNode(), targetToPos.get(t), true))
                .min(Comparator.comparingInt(Path::getNodeCount))
                .orElse(null);
        } else {
            best = targets.stream()
                .map(e -> {
                    Node end = e.getKey().getBestNode();
                    return end == null ? null : reconstructPath(end, e.getValue(), false);
                })
                .filter(p -> p != null)
                .min(Comparator.comparingDouble(Path::getDistToTarget).thenComparingInt(Path::getNodeCount))
                .orElse(null);
        }

        if (best == null) {
            PerformanceMetrics.recordAsyncPathNullResult();
        }
        return best;
    }

    private float getBestHeuristic(Node node, List<Map.Entry<Target, BlockPos>> targets) {
        float best = Float.MAX_VALUE;
        for (Map.Entry<Target, BlockPos> entry : targets) {
            float distance = node.distanceTo(entry.getKey());
            entry.getKey().updateBest(distance, node);
            best = Math.min(best, distance);
        }
        return best;
    }

    @Override
    protected float distance(Node first, Node second) {
        return first.distanceTo(second);
    }

    private Path reconstructPath(Node end, BlockPos target, boolean reached) {
        List<Node> nodes = new ArrayList<>();
        for (Node node = end; node != null; node = node.cameFrom) nodes.add(0, node);
        return new Path(nodes, target, reached);
    }

    private record HazardAwarePathPreparation(NodeEvaluator evaluator, Node start,
                                              List<Map.Entry<Target, BlockPos>> targets) {}
}

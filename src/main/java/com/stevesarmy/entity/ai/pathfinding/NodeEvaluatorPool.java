package com.stevesarmy.entity.ai.pathfinding;

import com.stevesarmy.entity.ai.HazardAwareWalkNodeEvaluator;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Reuses evaluators without allowing two path jobs to share mutable search state. */
public final class NodeEvaluatorPool {
    private static final ConcurrentLinkedQueue<HazardAwareWalkNodeEvaluator> POOL =
        new ConcurrentLinkedQueue<>();

    private NodeEvaluatorPool() {}

    public static HazardAwareWalkNodeEvaluator take() {
        HazardAwareWalkNodeEvaluator evaluator = POOL.poll();
        if (evaluator == null) evaluator = new HazardAwareWalkNodeEvaluator(true);
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        return evaluator;
    }

    public static void release(NodeEvaluator evaluator) {
        if (evaluator instanceof HazardAwareWalkNodeEvaluator hazardEvaluator) {
            hazardEvaluator.done();
            POOL.offer(hazardEvaluator);
        }
    }

    /**
     * Drops an evaluator whose prepare call did not complete. Vanilla done() assumes
     * that prepare assigned a mob, so it cannot safely clean up a partial preparation.
     */
    public static void discard(NodeEvaluator evaluator) {
        // Intentionally do not return partially initialized evaluators to the pool.
    }
}

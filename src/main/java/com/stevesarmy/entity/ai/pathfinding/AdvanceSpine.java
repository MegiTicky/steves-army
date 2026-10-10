package com.stevesarmy.entity.ai.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * An "advance spine": the wide-lens route from a soldier toward the attack
 * objective, kept as an immutable polyline so ATTACK cover progression can be
 * measured against the ROUTE instead of raw euclidean distance. The straight
 * line and the real route disagree wherever a building sits between them —
 * the route walks away from the objective to round it — and every
 * straight-line progress metric inherits that lie (interior covers "advance",
 * the true exit cover regresses).
 *
 * <p>The spine is built once per attack command from the same wide-lens probe
 * the assemble walk uses, and consumed by CoverFinder scoring, the corridor
 * filters, the frontier, the dock grading, and the fallback legs. {@code null}
 * spines fall back to the legacy straight-line math everywhere, so a failed
 * probe degrades to the pre-spine behavior rather than blocking the advance.
 */
public final class AdvanceSpine {

    /** Age after which the spine is re-probed (terrain/tactics drift). */
    public static final long MAX_AGE_TICKS = 400L;
    /** Perpendicular drift from the polyline that invalidates the spine.
     * Matched to the dock corridor width: docks sit up to that far off the
     * route by design, and invalidating the spine at every such dock reverts
     * the soldier to the legacy 4s dwell while the probe retries. */
    public static final double MAX_DRIFT = 10.0D;

    private final BlockPos objective;
    private final boolean canReach;
    private final long bornTick;
    private final double[] xs;
    private final double[] zs;
    /** Cumulative arc length from the route start to node i. */
    private final double[] cum;
    private final double length;

    private AdvanceSpine(BlockPos objective, boolean canReach, long bornTick,
                         double[] xs, double[] zs, double[] cum) {
        this.objective = objective;
        this.canReach = canReach;
        this.bornTick = bornTick;
        this.xs = xs;
        this.zs = zs;
        this.cum = cum;
        this.length = cum[cum.length - 1];
    }

    /** Builds a spine from a path, or null when the path carries no usable route. */
    @javax.annotation.Nullable
    public static AdvanceSpine of(@javax.annotation.Nullable Path path,
                                  BlockPos objective, long tick) {
        if (path == null || path.getNodeCount() == 0) {
            return null;
        }
        int count = path.getNodeCount();
        double[] xs = new double[count];
        double[] zs = new double[count];
        double[] cum = new double[count];
        for (int i = 0; i < count; i++) {
            Node node = path.getNode(i);
            xs[i] = node.asBlockPos().getX() + 0.5D;
            zs[i] = node.asBlockPos().getZ() + 0.5D;
            if (i > 0) {
                double dx = xs[i] - xs[i - 1];
                double dz = zs[i] - zs[i - 1];
                cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dz * dz);
            }
        }
        return new AdvanceSpine(objective.immutable(), path.canReach(), tick, xs, zs, cum);
    }

    /** Result of projecting a world point onto the spine polyline. */
    public record Projection(double arc, double distance) {}

    /**
     * Projects a world point onto the polyline: {@link Projection#arc} is the
     * walked distance from the route start to the closest point on the route
     * (THE progress metric), {@link Projection#distance} the perpendicular
     * distance to it. Degenerate single-node spines project everything onto
     * that node.
     */
    public Projection project(double px, double pz) {
        double bestDist = Double.MAX_VALUE;
        double bestArc = 0.0D;
        for (int i = 1; i < xs.length; i++) {
            double ax = xs[i - 1];
            double az = zs[i - 1];
            double dx = xs[i] - ax;
            double dz = zs[i] - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0e-6 ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, ((px - ax) * dx + (pz - az) * dz) / lengthSq));
            double closestX = ax + t * dx;
            double closestZ = az + t * dz;
            double dist = Math.hypot(px - closestX, pz - closestZ);
            if (dist < bestDist) {
                bestDist = dist;
                bestArc = cum[i - 1] + t * Math.sqrt(lengthSq);
            }
        }
        if (xs.length == 1) {
            bestDist = Math.hypot(px - xs[0], pz - zs[0]);
        }
        return new Projection(bestArc, bestDist);
    }

    /** Route-walked progress of a block position (blocks from the spine start). */
    public double progress(BlockPos pos) {
        return project(pos.getX() + 0.5D, pos.getZ() + 0.5D).arc();
    }

    /** Perpendicular distance from a block position to the spine polyline. */
    public double corridorDistance(BlockPos pos) {
        return project(pos.getX() + 0.5D, pos.getZ() + 0.5D).distance();
    }

    /**
     * The world position at a given arc length along the route — the target
     * for a spine-hop advance leg. Clamped to both ends. The spine is a
     * horizontal polyline, so the caller supplies the ground Y.
     */
    public BlockPos pointAt(double arc, int y) {
        arc = Math.max(0.0D, Math.min(length, arc));
        for (int i = 1; i < xs.length; i++) {
            double seg = cum[i] - cum[i - 1];
            if (arc <= cum[i] || i == xs.length - 1) {
                double t = seg < 1.0e-6 ? 0.0D : (arc - cum[i - 1]) / seg;
                t = Math.max(0.0D, Math.min(1.0D, t));
                return BlockPos.containing(xs[i - 1] + t * (xs[i] - xs[i - 1]),
                    (double) y, zs[i - 1] + t * (zs[i] - zs[i - 1]));
            }
        }
        return BlockPos.containing(xs[xs.length - 1], (double) y, zs[zs.length - 1]);
    }

    /**
     * The spine dies when it ages out, when the order moved to a different
     * objective, or when the soldier was pushed (knockback, suppression
     * relocations) too far off the route for his progress to mean anything.
     */
    public boolean isStale(long tick, BlockPos currentObjective, double soldierX, double soldierZ) {
        if (tick - bornTick > MAX_AGE_TICKS) return true;
        if (!objective.equals(currentObjective.immutable())) return true;
        return project(soldierX, soldierZ).distance() > MAX_DRIFT;
    }

    public double length() {
        return length;
    }

    /** Horizontal distance from the route's END node to a block position —
     * tells a real route (ends at the objective) from an interior stub. */
    public double endHorizontalDist(BlockPos pos) {
        double dx = xs[xs.length - 1] - (pos.getX() + 0.5D);
        double dz = zs[zs.length - 1] - (pos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public BlockPos objective() {
        return objective;
    }

    public boolean canReach() {
        return canReach;
    }
}

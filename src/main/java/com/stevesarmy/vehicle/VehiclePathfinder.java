package com.stevesarmy.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Coarse waypoint pathfinding for AI driven vehicles, rewritten for smoothness.
 *
 * The previous version searched a 4 block grid and handed the raw cell centres
 * to the driver, which is why a vehicle visibly stepped from goal to goal and
 * hesitated in front of obstacles. This one:
 *   - searches a 2 block grid with no diagonal corner cutting;
 *   - charges a small cost for hugging walls so routes stay in the open;
 *   - string pulls the result, then applies two Chaikin passes and validates
 *     every smoothed segment, producing a flowing polyline;
 *   - returns a partial route toward the closest reached node when the goal is
 *     unreachable, so a vehicle keeps making progress instead of stopping.
 */
public final class VehiclePathfinder {

    private static final int CELL = 2;
    private static final int MAX_NODES = 2200;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;
    private static final int GROUND_PROBE = 6;
    private static final double CLEARANCE_PENALTY = 1.6;

    private VehiclePathfinder() {
    }

    private record Node(BlockPos pos, double g, double f) {
    }

    // -- public API -----------------------------------------------------------

    /** Legacy entry point: queue of waypoints from start to goal. */
    public static Deque<Vec3> findPath(Level level, BlockPos start, BlockPos goal, double halfWidth) {
        return findPath(level, start, goal, center(goal), halfWidth, 2.0);
    }

    /** Legacy entry point with an exact final point and vertical clearance. */
    public static Deque<Vec3> findPath(Level level, BlockPos start, BlockPos goal, Vec3 exactGoal,
                                       double halfWidth, double height) {
        return new ArrayDeque<>(route(level, Vec3.atCenterOf(start), exactGoal, halfWidth, height));
    }

    /**
     * Preferred entry point. Returns a smoothed list of points ending on the
     * exact goal, or an empty list when nothing usable was found.
     */
    public static List<Vec3> route(Level level, Vec3 from, Vec3 to, double halfWidth, double height) {
        List<Vec3> result = new ArrayList<>();
        BlockPos start = snapToGround(level, BlockPos.containing(from));
        BlockPos goal = snapToGround(level, BlockPos.containing(to));
        if (start == null || goal == null) return result;

        // Straight shot first: most orders need no search at all.
        if (isClearLine(level, from, to, halfWidth, height)) {
            result.add(to);
            return result;
        }

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

        open.add(new Node(start, 0.0, heuristic(start, goal)));
        best.put(start, 0.0);

        int expanded = 0;
        BlockPos reached = null;
        BlockPos closest = start;
        double closestScore = heuristic(start, goal);

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node current = open.poll();
            BlockPos pos = current.pos();
            if (!closed.add(pos)) continue;
            expanded++;

            double score = heuristic(pos, goal);
            if (score < closestScore) {
                closestScore = score;
                closest = pos;
            }
            if (score <= CELL * 1.5) {
                reached = pos;
                break;
            }
            for (BlockPos next : neighbours(level, pos, halfWidth, height)) {
                if (closed.contains(next)) continue;
                double step = horizontalDistance(pos, next)
                    + Math.abs(next.getY() - pos.getY()) * 1.2
                    + clearanceCost(level, next, halfWidth, height);
                double tentative = current.g() + step;
                Double known = best.get(next);
                if (known != null && tentative >= known) continue;
                best.put(next, tentative);
                cameFrom.put(next, pos);
                open.add(new Node(next, tentative, tentative + heuristic(next, goal)));
            }
        }

        BlockPos tail = reached != null ? reached : closest;
        // A partial route still has to be worth driving.
        if (tail.equals(start)) return result;

        List<Vec3> raw = new ArrayList<>();
        List<BlockPos> reversed = new ArrayList<>();
        for (BlockPos p = tail; p != null && !p.equals(start); p = cameFrom.get(p)) {
            reversed.add(p);
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            raw.add(center(reversed.get(i)));
        }
        if (reached != null) raw.add(to);
        if (raw.isEmpty()) return result;

        List<Vec3> pulled = stringPull(level, from, raw, halfWidth, height);
        return chaikin(level, from, pulled, halfWidth, height);
    }

    public static double footprintRadius(Entity vehicle) {
        if (vehicle == null) return 1.5;
        AABB bounds = vehicle.getBoundingBox();
        return Math.max(0.75, Math.max(bounds.getXsize(), bounds.getZsize()) * 0.5 + 0.5);
    }

    public static double footprintHeight(Entity vehicle) {
        return vehicle == null ? 2.0 : Math.max(1.0, vehicle.getBbHeight() + 0.5);
    }

    // -- smoothing ------------------------------------------------------------

    /** Remove every point that can be skipped on a clear line. */
    private static List<Vec3> stringPull(Level level, Vec3 origin, List<Vec3> points,
                                         double halfWidth, double height) {
        List<Vec3> out = new ArrayList<>();
        Vec3 anchor = origin;
        int i = 0;
        while (i < points.size()) {
            int furthest = i;
            for (int j = points.size() - 1; j > i; j--) {
                if (isClearLine(level, anchor, points.get(j), halfWidth, height)) {
                    furthest = j;
                    break;
                }
            }
            out.add(points.get(furthest));
            anchor = points.get(furthest);
            i = furthest + 1;
        }
        return out;
    }

    /** Two corner cutting passes, each segment re validated against the hull. */
    private static List<Vec3> chaikin(Level level, Vec3 origin, List<Vec3> points,
                                      double halfWidth, double height) {
        List<Vec3> current = points;
        for (int pass = 0; pass < 2 && current.size() > 2; pass++) {
            List<Vec3> next = new ArrayList<>(current.size() * 2);
            Vec3 previous = origin;
            for (int i = 0; i < current.size() - 1; i++) {
                Vec3 a = current.get(i);
                Vec3 b = current.get(i + 1);
                Vec3 q = a.add(b.subtract(a).scale(0.25));
                Vec3 r = a.add(b.subtract(a).scale(0.75));
                if (isClearLine(level, previous, q, halfWidth, height)
                    && isClearLine(level, q, r, halfWidth, height)) {
                    next.add(q);
                    next.add(r);
                    previous = r;
                } else {
                    next.add(a);
                    previous = a;
                }
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return current;
    }

    // -- grid -----------------------------------------------------------------

    private static double heuristic(BlockPos a, BlockPos b) {
        return horizontalDistance(a, b);
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static List<BlockPos> neighbours(Level level, BlockPos pos, double halfWidth, double height) {
        List<BlockPos> out = new ArrayList<>(8);
        int[][] straight = {{CELL, 0}, {-CELL, 0}, {0, CELL}, {0, -CELL}};
        boolean[] open = new boolean[4];
        for (int i = 0; i < straight.length; i++) {
            BlockPos candidate = step(level, pos, straight[i][0], straight[i][1], halfWidth, height);
            if (candidate != null) {
                open[i] = true;
                out.add(candidate);
            }
        }
        // Diagonals only when both touching orthogonals are open: no clipping
        // through the corner of a building.
        addDiagonal(level, pos, out, halfWidth, height, open[0] && open[2], CELL, CELL);
        addDiagonal(level, pos, out, halfWidth, height, open[0] && open[3], CELL, -CELL);
        addDiagonal(level, pos, out, halfWidth, height, open[1] && open[2], -CELL, CELL);
        addDiagonal(level, pos, out, halfWidth, height, open[1] && open[3], -CELL, -CELL);
        return out;
    }

    private static void addDiagonal(Level level, BlockPos pos, List<BlockPos> out, double halfWidth,
                                    double height, boolean allowed, int dx, int dz) {
        if (!allowed) return;
        BlockPos candidate = step(level, pos, dx, dz, halfWidth, height);
        if (candidate != null) out.add(candidate);
    }

    private static BlockPos step(Level level, BlockPos pos, int dx, int dz, double halfWidth, double height) {
        BlockPos candidate = snapToGround(level, pos.offset(dx, 0, dz));
        if (candidate == null) return null;
        int rise = candidate.getY() - pos.getY();
        if (rise > MAX_STEP_UP || rise < -MAX_DROP) return null;
        if (!isFootprintClear(level, candidate, halfWidth, height)) return null;
        return candidate;
    }

    /** Small penalty for cells whose shoulders are blocked, keeps routes open. */
    private static double clearanceCost(Level level, BlockPos pos, double halfWidth, double height) {
        double probe = halfWidth + 1.25;
        int blocked = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos side = pos.offset((int) Math.round(d[0] * probe), 0, (int) Math.round(d[1] * probe));
            if (!isFootprintClear(level, side, halfWidth * 0.6, height)) blocked++;
        }
        return blocked * CLEARANCE_PENALTY;
    }

    public static BlockPos snapToGround(Level level, BlockPos pos) {
        for (int dy = 0; dy <= GROUND_PROBE; dy++) {
            BlockPos down = pos.below(dy);
            if (isStandable(level, down)) return down;
            BlockPos up = pos.above(dy);
            if (dy > 0 && isStandable(level, up)) return up;
        }
        return null;
    }

    private static boolean isStandable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState floor = level.getBlockState(pos.below());
        if (floor.isAir() || !floor.getFluidState().isEmpty()) return false;
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    public static boolean isFootprintClear(Level level, BlockPos pos, double halfWidth, double height) {
        int radius = Mth.ceil(halfWidth);
        double radiusSqr = halfWidth * halfWidth;
        int levels = Math.max(1, Mth.ceil(height));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSqr + 0.5) continue;
                for (int dy = 0; dy < levels; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (!level.isLoaded(check)) return false;
                    if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Hull wide line of travel test between two world points. */
    public static boolean isClearLine(Level level, Vec3 from, Vec3 to, double halfWidth, double height) {
        double distance = Math.sqrt((to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z));
        if (distance > 96.0) return false;
        int steps = Math.max(1, Mth.ceil(distance / 1.5));
        BlockPos previous = snapToGround(level, BlockPos.containing(from));
        if (previous == null) return false;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 point = from.add(to.subtract(from).scale(t));
            BlockPos sample = snapToGround(level, BlockPos.containing(point));
            if (sample == null) return false;
            if (Math.abs(sample.getY() - previous.getY()) > MAX_STEP_UP + MAX_DROP) return false;
            if (!isFootprintClear(level, sample, halfWidth, height)) return false;
            previous = sample;
        }
        return true;
    }

    /** Legacy block variant kept for callers outside the driver. */
    public static boolean isClearLine(Level level, BlockPos from, BlockPos to, double halfWidth, double height) {
        return isClearLine(level, center(from), center(to), halfWidth, height);
    }
}

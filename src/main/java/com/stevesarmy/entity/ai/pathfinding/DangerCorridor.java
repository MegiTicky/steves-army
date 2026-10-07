package com.stevesarmy.entity.ai.pathfinding;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadDangerMap;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Server-thread capture of danger costs along a requested path's corridor,
 * consumed by the async A* cost line on the worker thread. Doctrine: every
 * role gets a graded avoidance weight — SUPPORT strongest (logistics never
 * crosses killing ground), then AT (hard targets approached through shadow),
 * MG (base-of-fire cautious), garrison and rifleman lightest (assault roles
 * still cross exposed ground when the objective demands it, but prefer
 * shadow when the detour is cheap). VEHICLE_CREW is exempt (crew move with
 * their vehicle). The weight applies symmetrically: enemy soldiers run the
 * same stack and detour around their opponents' kill zones too.
 *
 * <p>Cells within {@code CORRIDOR_WIDTH} of the straight segment to any
 * target are sampled from the squad's live danger map (O(1) lookups, no
 * rays); fully-exposed cells contribute the full role weight, partial leaks
 * contribute proportionally. Danger is a COST, never a wall: a soldier with
 * no safe route still arrives.</p>
 */
public final class DangerCorridor {
    /** Corridor half-width around the straight-line segment (blocks). */
    private static final double CORRIDOR_WIDTH = 6.0;

    /** packed cell key → danger multiplier in [0,1] (score/100). */
    private final Long2FloatOpenHashMap cellDanger;
    private final float roleWeight;

    private DangerCorridor(Long2FloatOpenHashMap cellDanger, float roleWeight) {
        this.cellDanger = cellDanger;
        this.roleWeight = roleWeight;
    }

    /** Debug snapshot of one soldier's last captured corridor, for the /stevesarmy_debug dangermap overlay. */
    public record CorridorDebug(long gameTime, float roleWeight, BlockPos target,
                                java.util.Map<Long, Float> cellDanger) {}

    private static final java.util.Map<java.util.UUID, CorridorDebug> LAST_CORRIDORS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Last corridor captured for this soldier, or null. Entries age out; callers must check recency. */
    @Nullable
    public static CorridorDebug debugFor(java.util.UUID soldierId) {
        return LAST_CORRIDORS.get(soldierId);
    }

    public static void resetForSim() {
        LAST_CORRIDORS.clear();
    }

    @Nullable
    public static DangerCorridor capture(ServerLevel level, Mob mob, Set<BlockPos> targets, long gameTime) {
        if (!(mob instanceof SoldierEntity soldier) || targets.isEmpty()) return null;
        float weight = pathWeightFor(soldier.getRole());
        if (weight <= 0.0f || soldier.getSquadId() == null) return null;
        SquadData squad = SquadManager.get(level).getSquadById(soldier.getSquadId()).orElse(null);
        if (squad == null) return null;
        SquadDangerMap map = squad.getDangerMap();

        // synchronous on-demand bake: a path requested before the ambient ring
        // has warmed the route still gets danger-aware costing; the allowance
        // bounds the one-time tick spike (≈40 cells at 6 rays)
        int[] rayAllowance = {240};

        Long2FloatOpenHashMap captured = new Long2FloatOpenHashMap();
        BlockPos origin = mob.blockPosition();
        for (BlockPos target : targets) {
            sampleSegment(level, map, captured, origin, target, gameTime, squad.getThreatIntel(), rayAllowance);
        }
        if (captured.isEmpty()) return null;
        LAST_CORRIDORS.put(soldier.getUUID(), new CorridorDebug(gameTime, weight,
            targets.iterator().next(), new java.util.HashMap<>(captured)));
        return new DangerCorridor(captured, weight);
    }


    private static void sampleSegment(ServerLevel level, SquadDangerMap map, Long2FloatOpenHashMap captured,
                                      BlockPos from, BlockPos to, long gameTime,
                                      com.stevesarmy.squad.SquadThreatIntel intel, int[] rayAllowance) {
        int minX = Math.min(from.getX(), to.getX()) - (int) CORRIDOR_WIDTH - 2;
        int maxX = Math.max(from.getX(), to.getX()) + (int) CORRIDOR_WIDTH + 2;
        int minZ = Math.min(from.getZ(), to.getZ()) - (int) CORRIDOR_WIDTH - 2;
        int maxZ = Math.max(from.getZ(), to.getZ()) + (int) CORRIDOR_WIDTH + 2;

        // midpoint-outward bake order: the threat (the danger source) usually
        // sits mid-segment, so the allowance must reach the hot middle before
        // the safe ends — an arbitrary scan order spends it on shadow cells
        record Candidate(int cx, int cz, double centerAlong) {}
        double segLengthSq = squaredSegmentLength(from, to);
        List<Candidate> candidates = new ArrayList<>();
        for (int cellX = Math.floorDiv(minX, 2); cellX <= Math.floorDiv(maxX, 2); cellX++) {
            for (int cellZ = Math.floorDiv(minZ, 2); cellZ <= Math.floorDiv(maxZ, 2); cellZ++) {
                double centerX = cellX * 2.0 + 1.0;
                double centerZ = cellZ * 2.0 + 1.0;
                if (distanceToSegment(centerX, centerZ, from, to) > CORRIDOR_WIDTH) continue;
                double along = segLengthSq < 1.0e-6 ? 0.0
                    : ((centerX - (from.getX() + 0.5)) * (to.getX() - from.getX())
                        + (centerZ - (from.getZ() + 0.5)) * (to.getZ() - from.getZ())) / segLengthSq;
                candidates.add(new Candidate(cellX, cellZ, along));
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> Math.abs(c.centerAlong() - 0.5)));

        for (Candidate candidate : candidates) {
            double centerX = candidate.cx() * 2.0 + 1.0;
            double centerZ = candidate.cz() * 2.0 + 1.0;
            long key = SquadDangerMap.cellKeyFor(centerX, centerZ);
            if (captured.containsKey(key)) continue;
            com.stevesarmy.squad.SquadDangerMap.Cell cell =
                map.bakeCellNow(level, centerX, centerZ, intel, gameTime, rayAllowance);
            if (cell != null && cell.score > 0.0f) {
                captured.put(key, cell.score / 100.0f);
            }
        }
    }

    private static double squaredSegmentLength(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return dx * dx + dz * dz;
    }

    private static double distanceToSegment(double px, double pz, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5;
        double bz = b.getZ() + 0.5;
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq < 1.0e-6 ? 0.0
            : Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / lengthSq));
        double closestX = ax + t * dx;
        double closestZ = az + t * dz;
        return Math.hypot(px - closestX, pz - closestZ);
    }

    private static float pathWeightFor(com.stevesarmy.entity.SoldierRole role) {
        return switch (role) {
            case ANTI_TANK -> (float) StevesArmyConfig.getDangerPathAntiTankWeight();
            case SUPPORT -> (float) StevesArmyConfig.getDangerPathSupportWeight();
            case MACHINE_GUNNER -> (float) StevesArmyConfig.getDangerPathMachineGunnerWeight();
            case GARRISON -> (float) StevesArmyConfig.getDangerPathGarrisonWeight();
            case RIFLEMAN -> (float) StevesArmyConfig.getDangerPathRiflemanWeight();
            case VEHICLE_CREW -> 0.0f;
        };
    }

    /** A* node cost addition: role weight × cell danger multiplier (0 outside the corridor). */
    public float costAt(Node node) {
        long key = SquadDangerMap.cellKeyFor(node.x + 0.5, node.z + 0.5);
        float danger = cellDanger.getOrDefault(key, 0.0f);
        return danger * roleWeight;
    }
}

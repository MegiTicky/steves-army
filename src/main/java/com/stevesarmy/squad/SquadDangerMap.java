package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.combat.VisibilityRay;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-squad spatial map of line-of-sight danger: which nearby positions are
 * exposed to the squad's known threats, probed at leg (0.4) and eye (1.6)
 * heights with {@link VisibilityRay} (smoke- and foliage-aware — cells behind
 * smoke or dense leaves read safe). Transient runtime object, deliberately
 * not persisted; rebuilt within seconds from live intel after a reload.
 *
 * <p>Grid convention matches the sim harness's {@code DangerOracle}: 2x2
 * horizontal cells keyed by {@code (floorDiv(x,2), floorDiv(z,2))}. Score =
 * min(100, sum of 100*weight over threats with any exposure) — a walking body
 * occupies both probe heights; the per-height BITS carry the posture nuance
 * consumers need (prone protection = leg bit clear, cover quality = eye
 * bit).</p>
 *
 * <p>Cost model: reverse rays (threat origin → cell point) are spent from a
 * GLOBAL per-tick budget shared by every squad, so many squads cannot
 * ray-spam one tick. Ambient baking walks a nearest-first ring cursor around
 * the squad anchor, skips fresh cells (TTL), and never loads chunks (the
 * VAW lesson). Queries are O(1) — no rays on the consumer side.</p>
 */
public final class SquadDangerMap {
    public static final double LEG_HEIGHT = 0.4;
    public static final double EYE_HEIGHT = 1.6;
    private static final int CELL_SIZE = 2;
    /** Lateral half-span of a soldier body the fan rays bracket. */
    private static final double BODY_HALF_SPAN = 0.45;
    /** Rays per threat per cell: 3 lateral offsets x 2 heights. */
    private static final int RAYS_PER_THREAT = 6;
    /** Intel freshness window for the active-threat view (matches FiringContact.MAX_AGE_TICKS). */
    private static final long CONTACT_FRESHNESS_TICKS = 200;
    private static final long VIEW_REFRESH_INTERVAL_TICKS = 10;
    /** How far a threat origin may drift before the cell set is invalidated. */
    private static final double ORIGIN_DRIFT_INVALIDATION = 2.0;
    private static final int MAX_CELLS = 4_096;

    /** One baked grid cell. Bits are independent (an open cell reads both true). */
    public static final class Cell {
        public final float score;
        public final boolean eyeExposed;
        public final boolean legExposed;
        public final int threatCount;
        public final long bakedAtTick;
        /** Standing-surface world Y the probes were cast against (rendering + consumer geometry). */
        public final int surfaceY;

        Cell(float score, boolean eyeExposed, boolean legExposed, int threatCount, long bakedAtTick,
             int surfaceY) {
            this.score = score;
            this.eyeExposed = eyeExposed;
            this.legExposed = legExposed;
            this.threatCount = threatCount;
            this.bakedAtTick = bakedAtTick;
            this.surfaceY = surfaceY;
        }
    }

    /** Read-only cell snapshot for debug overlay / harness telemetry. */
    public record NearCell(double centerX, double surfaceY, double centerZ,
                           float score, boolean eyeExposed, boolean legExposed) {}

    /** Read-only active-threat snapshot for debug overlay. */
    public record NearThreat(double x, double y, double z, float weight, boolean hardTarget) {}

    private record ActiveThreat(UUID threatId, Vec3 origin, double weight, boolean hardTarget) {}

    // --- global per-tick ray budget (shared across all squads) ---------------

    private static final class RayBudget {
        private static long tick = Long.MIN_VALUE;
        private static int used;
        private static int maxUsedPerTick;

        static boolean trySpend(int rays, long gameTime, int budget) {
            if (tick != gameTime) {
                tick = gameTime;
                used = 0;
            }
            if (used + rays > budget) return false;
            used += rays;
            if (used > maxUsedPerTick) maxUsedPerTick = used;
            return true;
        }

        static void resetDebug() {
            maxUsedPerTick = 0;
        }
    }

    /** Worst per-tick ray spend since the last reset — sim + debug telemetry. */
    public static int debugMaxRaysUsedPerTick() {
        return RayBudget.maxUsedPerTick;
    }

    /** Simulation-harness isolation: clear the per-tick budget telemetry. */
    public static void resetForSim() {
        RayBudget.resetDebug();
    }

    // --- state -----------------------------------------------------------------

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    /** Packed cell indices, nearest to the anchor first — the ambient bake order. */
    private long[] bakeOrder = new long[0];
    private int bakeCursor;
    /** The anchor cell the current bake order was built around. */
    private long bakeOrderAnchorCell = Long.MIN_VALUE;
    @Nullable
    private Vec3 anchor;
    private long lastTickDriven = Long.MIN_VALUE;
    private long lastViewRefresh = Long.MIN_VALUE;
    private List<ActiveThreat> activeThreats = List.of();

    // --- driving ----------------------------------------------------------------

    /**
     * Per-tick maintenance, called from the first squad member to tick (the
     * map de-duplicates by game time). Refreshes the active-threat view every
     * {@value VIEW_REFRESH_INTERVAL_TICKS} ticks and spends leftover ray
     * budget on stale cells nearest the anchor.
     */
    public void tick(ServerLevel level, long gameTime, Vec3 anchorSample, SquadThreatIntel intel) {
        if (!StevesArmyConfig.isDangerMapEnabled()) return;
        if (lastTickDriven == gameTime) return;
        lastTickDriven = gameTime;

        anchor = anchor == null
            ? anchorSample
            : anchor.scale(0.9).add(anchorSample.scale(0.1));

        if (lastViewRefresh == Long.MIN_VALUE
            || gameTime - lastViewRefresh >= VIEW_REFRESH_INTERVAL_TICKS) {
            lastViewRefresh = gameTime;
            refreshActiveThreats(level, intel, gameTime);
        }

        if (activeThreats.isEmpty()) {
            if (!cells.isEmpty()) {
                cells.clear();
            }
            return;
        }
        ambientBake(level, gameTime);
    }

    private void refreshActiveThreats(ServerLevel level, SquadThreatIntel intel, long gameTime) {
        double range = StevesArmyConfig.getDangerThreatRange();
        double rangeSq = range * range;
        int maxThreats = StevesArmyConfig.getDangerMaxThreats();
        List<ActiveThreat> fresh = new ArrayList<>();
        for (SquadThreatIntel.ThreatKnowledge knowledge : intel.getAllThreats()) {
            if (!knowledge.isAlive) continue;
            if (gameTime - knowledge.lastSeenTime > CONTACT_FRESHNESS_TICKS) continue;
            Vec3 origin = knowledge.lastVisibleHeadPoint != null
                ? knowledge.lastVisibleHeadPoint
                : new Vec3(knowledge.lastKnownPosition.getX() + 0.5,
                    knowledge.lastKnownPosition.getY() + EYE_HEIGHT,
                    knowledge.lastKnownPosition.getZ() + 0.5);
            if (anchor != null && origin.distanceToSqr(anchor) > rangeSq) continue;
            float accuracy = Math.max(0.25f, Math.min(1.0f, knowledge.accuracy));
            double weight = accuracy
                * (knowledge.isHardTarget ? 1.5 : 1.0)
                * (knowledge.isSuppressed ? 0.5 : 1.0);
            fresh.add(new ActiveThreat(knowledge.threatEntityId, origin, weight, knowledge.isHardTarget));
        }
        fresh.sort(Comparator.comparingDouble((ActiveThreat t) -> -t.weight));
        if (fresh.size() > maxThreats) {
            fresh = fresh.subList(0, maxThreats);
        }
        List<ActiveThreat> view = List.copyOf(fresh);

        if (!sameThreatView(view)) {
            activeThreats = view;
            // the shadow geometry belongs to the old threat set — rebuild
            cells.clear();
            bakeOrder = new long[0];
            bakeCursor = 0;
        }
    }

    private boolean sameThreatView(List<ActiveThreat> fresh) {
        if (fresh.size() != activeThreats.size()) return false;
        Set<UUID> known = new HashSet<>();
        for (ActiveThreat t : activeThreats) known.add(t.threatId());
        for (ActiveThreat t : fresh) {
            if (!known.remove(t.threatId())) return false;
        }
        for (int i = 0; i < fresh.size(); i++) {
            ActiveThreat old = findThreat(activeThreats, fresh.get(i).threatId());
            if (old != null && old.origin().distanceToSqr(fresh.get(i).origin())
                > ORIGIN_DRIFT_INVALIDATION * ORIGIN_DRIFT_INVALIDATION) {
                return false;
            }
        }
        return true;
    }

    private static ActiveThreat findThreat(List<ActiveThreat> threats, UUID id) {
        for (ActiveThreat t : threats) {
            if (t.threatId().equals(id)) return t;
        }
        return null;
    }

    // --- ambient baking -----------------------------------------------------------

    private void ambientBake(ServerLevel level, long gameTime) {
        if (anchor == null) return;
        long anchorCell = packCell(floorDiv2(anchor.x), floorDiv2(anchor.z));
        if (bakeOrder.length == 0 || anchorCell != bakeOrderAnchorCell) {
            rebuildBakeOrder(anchorCell);
        }
        if (bakeOrder.length == 0) return;

        int budget = StevesArmyConfig.getDangerRayBudgetPerTick();
        int raysPerCell = activeThreats.size() * RAYS_PER_THREAT;
        long ttl = StevesArmyConfig.getDangerCellTtlTicks();
        int visited = 0;
        while (visited < bakeOrder.length) {
            long key = bakeOrder[bakeCursor];
            bakeCursor = (bakeCursor + 1) % bakeOrder.length;
            visited++;
            Cell existing = cells.get(key);
            if (existing != null && gameTime - existing.bakedAtTick < ttl) continue;
            if (cells.size() >= MAX_CELLS) {
                cells.clear();
                bakeOrder = new long[0];
                bakeCursor = 0;
                return;
            }
            if (!isCellInLoadedChunk(level, key)) continue;
            if (!RayBudget.trySpend(raysPerCell, gameTime, budget)) return;
            bakeCell(level, key, gameTime);
        }
    }

    private void bakeCell(ServerLevel level, long key, long gameTime) {
        long cx = key >> 32;
        long cz = (key << 32) >> 32;
        int surfaceY = surfaceY(level, (int) cx, (int) cz);
        if (surfaceY == Integer.MIN_VALUE) {
            surfaceMisses++;
            return;
        }
        double centerX = cx * (double) CELL_SIZE + 1.0;
        double centerZ = cz * (double) CELL_SIZE + 1.0;

        double score = 0.0;
        boolean anyEye = false;
        boolean anyLeg = false;
        for (ActiveThreat threat : activeThreats) {
            // 3-ray body-span fan per height: center + ±0.45 across the body
            // (perpendicular to the threat axis), so the fan-width leaks that
            // kill soldiers beside 1-wide blocks register as PARTIAL danger
            // instead of reading safe on a lucky center line.
            Vec3 toCell = new Vec3(centerX - threat.origin().x, 0, centerZ - threat.origin().z);
            Vec3 perpendicular = toCell.lengthSqr() < 1.0e-6
                ? new Vec3(1, 0, 0)
                : new Vec3(-toCell.z, 0, toCell.x).normalize().scale(BODY_HALF_SPAN);
            int exposedEye = 0;
            int exposedLeg = 0;
            for (int i = -1; i <= 1; i++) {
                Vec3 lateral = perpendicular.scale(i);
                Vec3 eyePoint = new Vec3(centerX + lateral.x, surfaceY + EYE_HEIGHT, centerZ + lateral.z);
                Vec3 legPoint = new Vec3(centerX + lateral.x, surfaceY + LEG_HEIGHT, centerZ + lateral.z);
                if (VisibilityRay.trace(level, threat.origin(), eyePoint, null).clear()) exposedEye++;
                if (VisibilityRay.trace(level, threat.origin(), legPoint, null).clear()) exposedLeg++;
            }
            int exposed = exposedEye + exposedLeg;
            anyEye |= exposedEye > 0;
            anyLeg |= exposedLeg > 0;
            if (exposed > 0) {
                score += threat.weight() * 100.0 * exposed / (double) RAYS_PER_THREAT;
            }
            if ((cx == 22 || cx == 23) && (cz == 9 || cz == 19) && debugBakes < 4) {
                debugBakes++;
                com.stevesarmy.StevesArmyMod.LOGGER.info(
                    "[DangerMap][dbg] bake ({},{}) surface={} origin={} eyeExposed={}/3 legExposed={}/3 score={}",
                    cx, cz, surfaceY, threat.origin(), exposedEye, exposedLeg, score);
            }
        }
        cells.put(key, new Cell((float) Math.min(100.0, score), anyEye, anyLeg,
            activeThreats.size(), gameTime, surfaceY));
    }

    private byte debugBakes;
    private int surfaceMisses;

    /** Cells bake nearest-first around the anchor and never leave the ambient radius. */
    private void rebuildBakeOrder(long anchorCell) {
        if (anchor == null) return;
        bakeOrderAnchorCell = anchorCell;
        double radius = StevesArmyConfig.getDangerAmbientRadius();
        double radiusSq = radius * radius;
        int cellRadius = (int) Math.ceil(radius / CELL_SIZE);
        int anchorCx = floorDiv2(anchor.x);
        int anchorCz = floorDiv2(anchor.z);
        List<long[]> ordered = new ArrayList<>();
        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                int cx = anchorCx + dx;
                int cz = anchorCz + dz;
                double centerX = cx * (double) CELL_SIZE + 1.0;
                double centerZ = cz * (double) CELL_SIZE + 1.0;
                double distSq = (centerX - anchor.x) * (centerX - anchor.x)
                    + (centerZ - anchor.z) * (centerZ - anchor.z);
                if (distSq > radiusSq) continue;
                ordered.add(new long[] {packCell(cx, cz), (long) distSq});
            }
        }
        ordered.sort(Comparator.comparingLong(a -> a[1]));
        bakeOrder = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            bakeOrder[i] = ordered.get(i)[0];
        }
        bakeCursor = 0;
    }

    private static boolean isCellInLoadedChunk(ServerLevel level, long key) {
        long cx = key >> 32;
        long cz = (key << 32) >> 32;
        for (int dx = 0; dx < CELL_SIZE; dx++) {
            for (int dz = 0; dz < CELL_SIZE; dz++) {
                int x = (int) cx * CELL_SIZE + dx;
                int z = (int) cz * CELL_SIZE + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) return false;
            }
        }
        return true;
    }

    /**
     * Standing-surface Y for probing the cell: the SECOND-LOWEST of the four
     * column heights. Plain heightmap would put a cell containing a cover
     * block on TOP of the cover — scoring the soldier's own cover hot — while
     * a single tall column (fence, tree trunk, lone block) inside the cell
     * must not drag the body probe off the walkable ground. With all four
     * columns open this is just ground level.
     */
    private static int surfaceY(ServerLevel level, int cx, int cz) {
        int[] heights = new int[CELL_SIZE * CELL_SIZE];
        int filled = 0;
        for (int dx = 0; dx < CELL_SIZE; dx++) {
            for (int dz = 0; dz < CELL_SIZE; dz++) {
                int x = cx * CELL_SIZE + dx;
                int z = cz * CELL_SIZE + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) return Integer.MIN_VALUE;
                heights[filled++] = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
        java.util.Arrays.sort(heights);
        // second-lowest: robust to one outlier column inside the cell
        return heights[1];
    }

    // --- queries (O(1), the Phase 2 consumer API) ---------------------------------

    /**
     * Danger score at world (x, z): 0..100, or -1 when unknown (no squad map,
     * unbaked or stale cell while threats are active). With no active threats
     * this returns 0 — verified safe, distinct from unknown.
     */
    public double dangerAt(double x, double z, long gameTime) {
        Cell cell = cellAt(x, z, gameTime);
        if (cell != null) return cell.score;
        return activeThreats.isEmpty() ? 0.0 : -1.0;
    }

    /** Fresh cell containing (x, z), or null when unbaked/stale. */
    @Nullable
    public Cell cellAt(double x, double z, long gameTime) {
        long ttl = StevesArmyConfig.getDangerCellTtlTicks();
        Cell cell = cells.get(cellKey(x, z));
        if (cell == null) return null;
        if (gameTime - cell.bakedAtTick >= ttl) return null;
        return cell;
    }

    /**
     * O(1) crawl signal for suppressed travelers: the position is seen by a
     * threat at standing eye height but the legs are shielded, i.e. crawling
     * hides here. Unknown/unbaked ground returns false - never crawl blind.
     */
    public boolean isCrawlHiddenAt(double x, double z, long gameTime) {
        Cell cell = cellAt(x, z, gameTime);
        return cell != null && cell.eyeExposed && !cell.legExposed;
    }

    /** Rays one cell bake spends at the current threat count (for capture-side budgeting). */
    public int raysPerCell() {
        return Math.max(1, activeThreats.size()) * RAYS_PER_THREAT;
    }

    /**
     * Synchronous on-demand bake for corridor capture: returns a fresh cell,
     * baking it first (within the caller's ray allowance) when missing or
     * stale. Paths computed at scenario start — before the ambient ring has
     * reached the route — still get danger-aware costing. Never loads chunks.
     */
    @Nullable
    public Cell bakeCellNow(ServerLevel level, double x, double z, SquadThreatIntel intel,
                            long gameTime, int[] rayAllowance) {
        if (!StevesArmyConfig.isDangerMapEnabled() || rayAllowance[0] <= 0) {
            return cellAt(x, z, gameTime);
        }
        int raysPerCell = raysPerCell();
        if (rayAllowance[0] < raysPerCell) return cellAt(x, z, gameTime);
        Cell fresh = cellAt(x, z, gameTime);
        if (fresh != null) return fresh;
        // align the threat view (the tick driver refreshes it every 10t; a
        // fresh capture right after a sighting needs it now)
        if (lastViewRefresh == Long.MIN_VALUE || gameTime - lastViewRefresh >= VIEW_REFRESH_INTERVAL_TICKS) {
            lastViewRefresh = gameTime;
            refreshActiveThreats(level, intel, gameTime);
        }
        if (activeThreats.isEmpty()) return null;
        long key = cellKey(x, z);
        if (cells.size() >= MAX_CELLS) {
            cells.clear();
            bakeOrder = new long[0];
            bakeCursor = 0;
        }
        int cx = floorDiv2(x);
        int cz = floorDiv2(z);
        if (!isCellInLoadedChunk(level, key)) return null;
        if (!RayBudget.trySpend(raysPerCell, gameTime, StevesArmyConfig.getDangerRayBudgetPerTick())) {
            return null;
        }
        rayAllowance[0] -= raysPerCell;
        bakeCell(level, key, gameTime);
        return cellAt(x, z, gameTime);
    }

    public int bakedCellCount() {
        return cells.size();
    }

    public int activeThreatCount() {
        return activeThreats.size();
    }

    /** Debug: bakes abandoned because the cell's chunk was not loaded (surface probe failed). */
    public int debugSurfaceMissCount() {
        return surfaceMisses;
    }

    /** Debug: the current ambient-bake anchor (EMA of member positions), or null. */
    @Nullable
    public Vec3 debugAnchor() {
        return anchor;
    }

    /** Debug-overlay snapshot: baked cells whose centers lie within {@code radius} of (centerX, centerZ). */
    public List<NearCell> snapshotCells(double centerX, double centerZ, double radius,
                                        long gameTime, int maxCells) {
        long ttl = StevesArmyConfig.getDangerCellTtlTicks();
        double radiusSq = radius * radius;
        List<NearCell> out = new ArrayList<>();
        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<Cell> entry : cells.long2ObjectEntrySet()) {
            Cell cell = entry.getValue();
            if (gameTime - cell.bakedAtTick >= ttl) continue;
            long cx = entry.getLongKey() >> 32;
            long cz = (entry.getLongKey() << 32) >> 32;
            double x = cx * (double) CELL_SIZE + 1.0;
            double z = cz * (double) CELL_SIZE + 1.0;
            double dx = x - centerX;
            double dz = z - centerZ;
            if (dx * dx + dz * dz > radiusSq) continue;
            out.add(new NearCell(x, cell.surfaceY, z, cell.score, cell.eyeExposed, cell.legExposed));
            if (out.size() >= maxCells) break;
        }
        return out;
    }

    /** Debug-overlay snapshot: the active threat view the map is currently baking from. */
    public List<NearThreat> snapshotThreats() {
        List<NearThreat> out = new ArrayList<>(activeThreats.size());
        for (ActiveThreat threat : activeThreats) {
            out.add(new NearThreat(threat.origin().x, threat.origin().y, threat.origin().z,
                (float) threat.weight(), threat.hardTarget()));
        }
        return out;
    }

    @Nullable
    public Vec3 anchor() {
        return anchor;
    }

    // --- grid math (mirrors sim/DangerOracle; the oracle scenarios verify agreement) ---

    private static int floorDiv2(double v) {
        return Math.floorDiv((int) Math.floor(v), CELL_SIZE);
    }

    private static long cellKey(double x, double z) {
        return packCell(floorDiv2(x), floorDiv2(z));
    }

    /** Public cell-key helper for consumers (pathfinding corridor capture). */
    public static long cellKeyFor(double x, double z) {
        return cellKey(x, z);
    }

    private static long packCell(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}

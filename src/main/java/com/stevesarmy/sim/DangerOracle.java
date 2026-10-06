package com.stevesarmy.sim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent ground truth for danger-map verification: recomputes per-cell
 * line-of-sight exposure from known threat origins with plain vanilla clips —
 * deliberately NOT through {@code VisibilityRay} or any future map code path —
 * so a wrong map cannot agree with its own oracle by construction.
 *
 * <p>Grid convention (the real map in squad/ must match): cells quantize to
 * 2x2 horizontal blocks ({@code floorDiv(x,2), floorDiv(z,2)}), the sample
 * point is the cell center at the standing surface, probed at LEG (0.4) and
 * EYE (1.6) heights. Per threat, ANY exposed probe height marks the cell
 * exposed (a walking body occupies both); the per-height BITS stay on the
 * cell for posture-aware consumers (prone protection = leg bit clear, cover
 * quality = eye bit). Cell score = sum of {@code weight * 100} over threats
 * with any exposure, clamped to 100 — center-line truth; the 15-ray fan
 * width problem (lone narrow blocks leaking flank rays) is the fidelity
 * probe + DangerProbeDisagreement detector's domain, not this oracle's.</p>
 */
public final class DangerOracle {
    private DangerOracle() {}

    public static final double LEG_HEIGHT = 0.4;
    public static final double EYE_HEIGHT = 1.6;
    public static final int CELL_SIZE = 2;

    public record ThreatOrigin(Vec3 eye, double weight) {}

    public record OracleCell(double score, boolean eyeExposed, boolean legExposed,
                             double centerX, double centerZ) {}

    /**
     * Quantized-cell key shared by the oracle and the live danger map.
     * Packs {@code (floorDiv(x,2), floorDiv(z,2))}.
     */
    public static long cellKey(double x, double z) {
        long cx = Math.floorDiv((int) Math.floor(x), CELL_SIZE);
        long cz = Math.floorDiv((int) Math.floor(z), CELL_SIZE);
        return (cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Center world position of the 2x2 cell containing (x, z). */
    public static double cellCenterX(double x) {
        return Math.floorDiv((int) Math.floor(x), CELL_SIZE) * (double) CELL_SIZE + 1.0;
    }

    public static double cellCenterZ(double z) {
        return Math.floorDiv((int) Math.floor(z), CELL_SIZE) * (double) CELL_SIZE + 1.0;
    }

    /** Immutable oracle result for one verification sweep. */
    public record Oracle(Map<Long, OracleCell> cells) {
        /** Cell containing world (x, z), or null outside the verified region. */
        public OracleCell at(double x, double z) {
            return cells.get(cellKey(x, z));
        }

        public int size() {
            return cells.size();
        }
    }

    /**
     * Result of comparing a danger sampler against the oracle over the
     * verified region. Produced by SimManager at scenario end, judged by the
     * DangerOracleMismatch detector.
     */
    public record SweepReport(int compared, int mismatches, double mismatchFraction,
                              double worstDelta, double worstX, double worstZ,
                              double worstExpected, double worstActual) {}

    /**
     * Computes expected exposure for every 2x2 cell overlapping the
     * fixture-local region [x1..x2] x [z1..z2] (inclusive), with cells resting
     * on {@code surfaceY} (the standing surface world Y).
     */
    public static Oracle compute(ServerLevel level, List<ThreatOrigin> threats,
                                 int x1, int z1, int x2, int z2, double surfaceY) {
        Map<Long, OracleCell> cells = new HashMap<>();
        int cx1 = Math.floorDiv(x1, CELL_SIZE);
        int cz1 = Math.floorDiv(z1, CELL_SIZE);
        int cx2 = Math.floorDiv(x2, CELL_SIZE);
        int cz2 = Math.floorDiv(z2, CELL_SIZE);
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                double centerX = cx * (double) CELL_SIZE + 1.0;
                double centerZ = cz * (double) CELL_SIZE + 1.0;
                boolean anyEye = false;
                boolean anyLeg = false;
                double score = 0.0;
                for (ThreatOrigin threat : threats) {
                    // bits are independent: an open cell reads both true, a
                    // slit wall reads leg-only, a waist-high wall reads eye-only
                    boolean eye = rayClear(level, threat.eye(), centerX, surfaceY + EYE_HEIGHT, centerZ);
                    boolean leg = rayClear(level, threat.eye(), centerX, surfaceY + LEG_HEIGHT, centerZ);
                    anyEye |= eye;
                    anyLeg |= leg;
                    if (eye || leg) {
                        score += threat.weight() * 100.0;
                    }
                }
                cells.put(packCell(cx, cz), new OracleCell(
                    Math.min(100.0, score), anyEye, anyLeg, centerX, centerZ));
            }
        }
        return new Oracle(Map.copyOf(cells));
    }

    /** Compares a live sampler against the oracle cell by cell. */
    public static SweepReport sweep(Oracle oracle, DangerSampler sampler, double tolerance) {
        int compared = 0;
        int mismatches = 0;
        double worstDelta = 0;
        double worstX = 0;
        double worstZ = 0;
        double worstExpected = 0;
        double worstActual = 0;
        for (OracleCell cell : oracle.cells().values()) {
            double actual = sampler.dangerScoreAt(cell.centerX(), cell.centerZ());
            if (actual < 0) continue; // sampler reports "no data here"
            compared++;
            double delta = Math.abs(actual - cell.score());
            if (delta > worstDelta) {
                worstDelta = delta;
                worstX = cell.centerX();
                worstZ = cell.centerZ();
                worstExpected = cell.score();
                worstActual = actual;
            }
            if (delta > tolerance) {
                mismatches++;
            }
        }
        double fraction = compared == 0 ? 1.0 : (double) mismatches / compared;
        return new SweepReport(compared, mismatches, fraction, worstDelta, worstX, worstZ, worstExpected, worstActual);
    }

    private static boolean rayClear(ServerLevel level, Vec3 from, double toX, double toY, double toZ) {
        BlockHitResult hit = level.clip(new ClipContext(from,
            new Vec3(toX, toY, toZ), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static long packCell(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}

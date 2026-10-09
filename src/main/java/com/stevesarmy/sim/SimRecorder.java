package com.stevesarmy.sim;

import com.stevesarmy.combat.CombatStateSnapshot;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.sim.terrain.SimTerrain;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the HTML animation viewer needs: the terrain height map
 * (top-down), per-sample frames of soldier states and enemy positions, and
 * scripted-event markers. Serializes to the JSON document embedded in the
 * generated {@code <scenario>_<stamp>_sim.html} player.
 */
public final class SimRecorder {
    private final int width;
    private final int depth;
    private final int[] heights; // (w+3)*(d+3), origin at (-1,-1); 0 = floor, 1..3 = wall height
    private final List<String> frames = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> shots = new ArrayList<>();
    private final List<double[]> shotTracers = new ArrayList<>();
    private final List<Long> shotTicks = new ArrayList<>();
    private int lastFrameTick;
    private final List<String> dangerOverlay = new ArrayList<>();
    private StringBuilder current;
    private boolean firstSoldier;

    public SimRecorder(int width, int depth) {
        this.width = width;
        this.depth = depth;
        this.heights = new int[(width + 3) * (depth + 3)];
    }

    /** Top-down height map from everything the fixture placed. */
    public void captureTerrain(SimTerrain terrain) {
        for (BlockPos pos : terrain.placedBlocks()) {
            int x = pos.getX() - terrain.origin.getX();
            int z = pos.getZ() - terrain.origin.getZ();
            if (x < -1 || z < -1 || x > width + 1 || z > depth + 1) continue;
            int h = pos.getY() - terrain.origin.getY() + 1; // floor slab -> 0, wall layer 0 -> 1, ...
            int idx = (x + 1) * (depth + 3) + (z + 1);
            if (h > heights[idx]) heights[idx] = Math.min(h, 3);
        }
    }

    public void event(int tick, String describe) {
        events.add("[" + tick + "," + quote(describe) + "]");
    }

    /** Static ground-truth danger overlay (oracle cells) rendered as a tint layer. */
    public void setDangerOverlay(DangerOracle.Oracle oracle) {
        dangerOverlay.clear();
        for (DangerOracle.OracleCell cell : oracle.cells().values()) {
            if (cell.score() < 0.5) continue;
            dangerOverlay.add("[" + num(cell.centerX()) + "," + num(cell.centerZ()) + ","
                + (int) Math.round(cell.score()) + "]");
        }
    }

    /**
     * Live-map overlay for scenarios with no injected threats: the squad's
     * actual baked danger cells, appended to the same tint layer so the
     * animation shows the real gradient the soldiers acted on.
     */
    public void addDangerOverlayCells(java.util.List<com.stevesarmy.squad.SquadDangerMap.NearCell> cells) {
        for (com.stevesarmy.squad.SquadDangerMap.NearCell cell : cells) {
            if (cell.score() < 0.5) continue;
            dangerOverlay.add("[" + num(cell.centerX()) + "," + num(cell.centerZ()) + ","
                + (int) Math.round(cell.score()) + "]");
        }
    }

    /** One fired round, drawn as a tracer for the frame being built. */
    public void shot(double fromX, double fromZ, double toX, double toZ) {
        shots.add("[" + num(fromX) + "," + num(fromZ) + "," + num(toX) + "," + num(toZ) + "]");
        shotTracers.add(new double[]{fromX, fromZ, toX, toZ});
        shotTicks.add((long) lastFrameTick);
    }

    /** Cumulative fire-ordered tracer endpoints (frame export never drains this). */
    public List<double[]> shotTracers() {
        return shotTracers;
    }

    /** Fire-ordered shot ticks, parallel to {@link #shotTracers()}. */
    public List<Long> shotTicks() {
        return shotTicks;
    }

    public void beginFrame(int tick) {
        lastFrameTick = tick;
        current = new StringBuilder();
        current.append("{\"t\":").append(tick).append(",\"s\":[");
        firstSoldier = true;
        // NOTE: do not clear `shots` here — shots fire during the level tick,
        // before this Phase.END sampling point; endFrame drains the buffer.
    }

    public void soldier(String label, CombatStateSnapshot s, Boolean los,
                        Double nearestEnemyX, Double nearestEnemyZ,
                        int shotsFired, boolean hasGun, int ammo, int magazineSize, float health,
                        double slotOffset, int anchorPulls, int exposedRays, int exposedHeadRays,
                        int probedRays, boolean reloading,
                        String posture, boolean rising, float syncedCoverHeight,
                        double dangerAtSoldier, boolean dangerLegExposed) {
        if (current == null) return;
        if (!firstSoldier) current.append(',');
        firstSoldier = false;
        current.append("{\"l\":").append(quote(label))
            .append(",\"x\":").append(num(s.position.x))
            .append(",\"z\":").append(num(s.position.z))
            .append(",\"hp\":").append(num(health))
            .append(",\"cs\":").append(quote(s.coverState))
            .append(",\"ps\":").append(quote(s.peekState))
            .append(",\"sup\":").append(String.format("%.2f", s.suppressionLevel))
            .append(",\"pin\":").append(s.pinned)
            .append(",\"fb\":").append(quote(s.followBlockedBy))
            .append(",\"wc\":").append(quote(s.wantCoverReason))
            .append(",\"qm\":").append(quote(s.queuedMode))
            .append(",\"rt\":").append(quote(s.relocType))
            .append(",\"sk\":").append(s.seekingTicks)
            .append(",\"fs\":").append(s.failedSearches)
            .append(",\"st\":").append(s.stuckTicks)
            .append(",\"los\":").append(quote(los == null ? "?" : los ? "y" : "n"))
            .append(",\"yh\":").append(String.format("%.0f", s.headYaw));
        if (!"none".equals(s.fireGate)) {
            current.append(",\"fg\":").append(quote(s.fireGate))
                .append(",\"fgt\":").append(s.fireGateTicks);
        }
        if (s.ticksSinceLastShot >= 0) {
            current.append(",\"tsl\":").append(s.ticksSinceLastShot);
        }
        if (s.facingErrorDeg >= 0.0f) {
            current.append(",\"fe\":").append(String.format("%.0f", s.facingErrorDeg));
        }
        if (!s.movingSupported) {
            current.append(",\"mv\":").append(quote(
                s.movementBlockReason.isEmpty() ? "blocked" : s.movementBlockReason));
        }
        appendPos(current, "c", s.currentCover);
        appendPos(current, "t", s.targetCover);
        if (s.currentCoverType != null) {
            current.append(",\"ck\":").append(quote(s.currentCoverType));
        }
        if (s.targetCoverType != null) {
            current.append(",\"tk\":").append(quote(s.targetCoverType));
        }
        if (s.relocCenter != null) {
            current.append(",\"rx\":").append(num(s.relocCenter.x))
                .append(",\"rz\":").append(num(s.relocCenter.z));
        }
        if (nearestEnemyX != null) {
            current.append(",\"nx\":").append(num(nearestEnemyX))
                .append(",\"nz\":").append(num(nearestEnemyZ));
        }
        if (s.lastSearch != null) {
            CombatStateSnapshot.LastSearch ls = s.lastSearch;
            current.append(",\"ls\":").append(quote(
                ls.mode + "/" + ls.phase + "/" + ls.result
                    + " @" + (ls.center != null ? (int) ls.center.x + "," + (int) ls.center.z : "?")
                    + " r" + ls.radius + " t" + ls.tick + " s" + ls.failStreak));
        }
        current.append(",\"sf\":").append(shotsFired);
        if (hasGun) {
            current.append(",\"am\":").append(ammo).append(",\"mg\":").append(magazineSize);
        }
        if (slotOffset >= 0) {
            current.append(",\"cd\":").append(String.format("%.3f", slotOffset));
        }
        if (anchorPulls >= 0) {
            current.append(",\"ap\":").append(anchorPulls);
        }
        if (exposedRays >= 0) {
            current.append(",\"xr\":").append(exposedRays).append(",\"pr\":").append(probedRays);
            if (exposedHeadRays > 0) {
                current.append(",\"xh\":").append(exposedHeadRays);
            }
        }
        if (reloading) {
            current.append(",\"rl\":1");
        }
        if (posture != null && !"STANDING".equals(posture)) {
            current.append(",\"po\":").append(quote(posture));
        }
        if (rising) {
            current.append(",\"ri\":1");
        }
        if (syncedCoverHeight >= 0) {
            current.append(",\"ch\":").append(String.format("%.2f", syncedCoverHeight));
        }
        if (dangerAtSoldier >= 0) {
            current.append(",\"dm\":").append((int) Math.round(dangerAtSoldier));
            if (dangerLegExposed) {
                current.append(",\"dle\":1");
            }
        }
        current.append('}');
    }

    public void endFrame(List<EnemySoldierEntity> enemies) {
        if (current == null) return;
        current.append("],\"e\":[");
        boolean first = true;
        for (EnemySoldierEntity enemy : enemies) {
            if (enemy.isRemoved()) continue;
            if (!first) current.append(',');
            first = false;
            current.append('[').append(num(enemy.getX())).append(',').append(num(enemy.getZ())).append(']');
        }
        current.append("],\"shots\":[");
        for (int i = 0; i < shots.size(); i++) {
            if (i > 0) current.append(',');
            current.append(shots.get(i));
        }
        current.append("]}");
        frames.add(current.toString());
        current = null;
        shots.clear();
    }

    /** Full JSON document for the HTML player (and the machine-readable recording file). */
    public String toJson(String scenario, String outcome, List<SimViolation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"scenario\":").append(quote(scenario))
            .append(",\"outcome\":").append(quote(outcome))
            .append(",\"terrain\":{\"w\":").append(width).append(",\"d\":").append(depth)
            .append(",\"cells\":[");
        for (int i = 0; i < heights.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(heights[i]);
        }
        sb.append("]},\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(events.get(i));
        }
        sb.append("],\"violations\":[");
        boolean first = true;
        for (SimViolation v : violations) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(v.tick).append(',').append(quote(v.detector))
                .append(',').append(quote(v.soldierLabel)).append(',').append(quote(v.severity.name())).append(']');
        }
        sb.append("],\"danger\":[");
        for (int i = 0; i < dangerOverlay.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(dangerOverlay.get(i));
        }
        sb.append("],\"frames\":[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(frames.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private void appendPos(StringBuilder sb, String key, BlockPos pos) {
        if (pos == null) return;
        sb.append(",\"").append(key).append("x\":").append(pos.getX() + 0.5)
            .append(",\"").append(key).append("z\":").append(pos.getZ() + 0.5);
    }

    private static String num(double v) {
        return String.format("%.2f", v);
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

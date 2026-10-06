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

    /** One fired round, drawn as a tracer for the frame being built. */
    public void shot(double fromX, double fromZ, double toX, double toZ) {
        if (current == null) return;
        shots.add("[" + num(fromX) + "," + num(fromZ) + "," + num(toX) + "," + num(toZ) + "]");
    }

    public void beginFrame(int tick) {
        current = new StringBuilder();
        current.append("{\"t\":").append(tick).append(",\"s\":[");
        firstSoldier = true;
        shots.clear();
    }

    public void soldier(String label, CombatStateSnapshot s, Boolean los,
                        Double nearestEnemyX, Double nearestEnemyZ,
                        int shotsFired, boolean hasGun, int ammo, int magazineSize) {
        if (current == null) return;
        if (!firstSoldier) current.append(',');
        firstSoldier = false;
        current.append("{\"l\":").append(quote(label))
            .append(",\"x\":").append(num(s.position.x))
            .append(",\"z\":").append(num(s.position.z))
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
            .append(",\"los\":").append(quote(los == null ? "?" : los ? "y" : "n"));
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
        current.append(",\"sf\":").append(shotsFired);
        if (hasGun) {
            current.append(",\"am\":").append(ammo).append(",\"mg\":").append(magazineSize);
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

package com.stevesarmy.sim.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Programmatic terrain fixture built from real blocks so the real VisibilityRay
 * DDA, real pathfinding, and real cover classification operate on real geometry.
 * Every placed block is tracked so the scenario cleanup can remove exactly what
 * it built.
 */
public final class SimTerrain {
    public final BlockPos origin;
    public final int width;
    public final int depth;
    private final ServerLevel level;
    private final List<BlockPos> placed = new ArrayList<>();
    /** Placed block states by world position — the boot-race heal pass re-applies these. */
    private final Map<BlockPos, BlockState> placedStates = new LinkedHashMap<>();

    public SimTerrain(ServerLevel level, BlockPos origin, int width, int depth) {
        this.level = level;
        this.origin = origin;
        this.width = width;
        this.depth = depth;
    }

    /** The level this fixture builds into (also used for map-color sampling). */
    public ServerLevel level() {
        return level;
    }

    /** Solid slab the scenario plays on, ringed by a 3-high rim so actors can't path off the edge. */
    public void platform() {
        fill(origin.offset(-1, -1, -1), origin.offset(width + 1, -1, depth + 1), Blocks.SMOOTH_STONE.defaultBlockState());
        wall(-1, -1, width + 1, -1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(-1, depth + 1, width + 1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(-1, -1, -1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(width + 1, -1, width + 1, depth + 1, 3, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /**
     * platform() floor without the containment border walls: for scenarios
     * that must be genuinely coverless — any wall, including the border, is
     * a cover candidate within search radius.
     */
    public void floorOnly() {
        fill(origin.offset(-1, -1, -1), origin.offset(width + 1, -1, depth + 1), Blocks.SMOOTH_STONE.defaultBlockState());
    }

    /** Ground-level box wall: x1..x2 × z1..z2 footprint, h tall, hollow opening-free. */
    public void wall(int x1, int z1, int x2, int z2, int h, BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = 0; y < h; y++) {
                    block(x, y, z, state);
                }
            }
        }
    }

    /** Three-sided pocket open toward openDir ("N"=-z, "S"=+z, "W"=-x, "E"=+x). */
    public void pocket(int cx, int cz, int half, int h, String openDir) {
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                boolean edge = x == cx - half || x == cx + half || z == cz - half || z == cz + half;
                if (!edge) continue;
                if ("N".equals(openDir) && z == cz - half) continue;
                if ("S".equals(openDir) && z == cz + half) continue;
                if ("W".equals(openDir) && x == cx - half) continue;
                if ("E".equals(openDir) && x == cx + half) continue;
                for (int y = 0; y < h; y++) block(x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    /** Hard-cover blocks in a row (waist-high wall of full blocks). */
    public void coverRow(int x1, int z1, int x2, int z2, int h) {
        wall(x1, z1, x2, z2, h, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** Single hard-cover stack (h tall) at one cell. */
    public void coverBlock(int x, int z, int h) {
        wall(x, z, x, z, h, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /**
     * Hollow building shell (the CQB course primitive): h-high walls on the
     * rectangle's edge, each door a 1-wide 2-high gap at a wall cell. Door
     * cells must lie on the rectangle's edge; interior doors are carved by
     * leaving a gap between two partition segments. No roof: sim fighting is
     * same-level, and the open top keeps fixtures under the build limit.
     */
    public void building(int x1, int z1, int x2, int z2, int h, int[][] doors) {
        java.util.Set<Long> doorSet = new java.util.HashSet<>();
        for (int[] d : doors) doorSet.add(encode(d[0], d[1]));
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (!edge || doorSet.contains(encode(x, z))) continue;
                for (int y = 0; y < h; y++) block(x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    private static long encode(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * The rework's CQB course: a two-room house with doorways on both ends
     * and an interior partition door, plus a street corridor to its south
     * with sparse rubble. Layout (fixture-local coords, platform ~48x48):
     *   house x 14..30, z 6..18, walls h3, doors W(14,12) E(30,12), partition x=22 z7..17 with door (22,12)
     *   street z 24..34 between the house row and the south rim, rubble rows at z=28 and z=31
     * Scenarios spawn squads on the street's south edge and enemies in or
     * beyond the house so approach, threshold, and crossing all matter.
     */
    public void cqbCourse() {
        platform();
        // House shell: 3-high walls, west + east doorways on the mid line.
        building(14, 6, 30, 18, 3, new int[][]{{14, 12}, {30, 12}});
        // Interior partition with its own door, carved as two segments.
        wall(22, 7, 22, 11, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(22, 13, 22, 17, 3, Blocks.STONE_BRICKS.defaultBlockState());
        // Street: sparse rubble rows between the house front and the south rim.
        coverRow(8, 28, 12, 28, 1);
        coverRow(20, 28, 24, 28, 1);
        coverRow(32, 28, 36, 28, 1);
        coverRow(10, 33, 14, 33, 1);
        coverRow(28, 33, 32, 33, 1);
    }

    /**
     * Multi-room training complex: five rooms off a central hall, interior
     * cover in each — the sim-scale twin of the in-game /stevesarmy_debug
     * course. The main doors sit on the NORTH wall (z=8), facing the assault
     * strip, exactly as the in-game course faces the player. Layout
     * (fixture-local, needs a 64x64 field):
     *   NW room x 11..23, z 9..21;  SW room x 11..23, z 23..35
     *   HALL    x 25..45, z 9..21
     *   SE-W    x 25..35, z 23..35; SE-E x 37..45, z 23..35
     * Doors: north main (20,8)+(21,8), west (10,22), east (46,28),
     * south (40,36), wall A x=24 doors (24,14)/(24,29), wall B z=22 doors
     * (16,22)/(30,22), wall D x=36 door (36,29).
     */
    public void trainingCourse() {
        platform();
        building(10, 8, 46, 36, 3, new int[][]{
            {20, 8}, {21, 8}, {10, 22}, {46, 28}, {40, 36}});
        // Wall A: x=24 (west rooms vs hall/SE), doors at z=14 and z=29.
        wall(24, 9, 24, 13, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(24, 15, 24, 21, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(24, 23, 24, 28, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(24, 30, 24, 35, 3, Blocks.STONE_BRICKS.defaultBlockState());
        // Wall B: z=22 (north rooms vs south rooms), doors at x=16 and x=30.
        wall(10, 22, 15, 22, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(17, 22, 29, 22, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(31, 22, 45, 22, 3, Blocks.STONE_BRICKS.defaultBlockState());
        // Wall D: x=36 (SE-W vs SE-E), door at z=29.
        wall(36, 23, 36, 28, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(36, 30, 36, 35, 3, Blocks.STONE_BRICKS.defaultBlockState());
        // Interior cover: hall crate pocket + rubble row, one fixture per side room.
        coverBlock(30, 13, 1);
        coverBlock(31, 13, 1);
        coverRow(38, 17, 40, 17, 1);
        coverBlock(14, 16, 1);
        coverBlock(13, 29, 1);
        coverBlock(28, 31, 1);
        coverBlock(41, 26, 1);
    }

    /**
     * Two rooms under one roof for the divided-rooms acceptance. The interior
     * partition (x=16) is solid except for an opening at z=14..15 — inside
     * the order's radius-8 circle of the west-room ping, so the east room is
     * genuinely part of the order — while the east room's north corners face
     * the west room's floor directly across the wall: two blocks away as the
     * crow flies, reachable only by walking around through the opening.
     *   shell x 8..24, z 6..20, walls h3, single south door (12,20)
     *   partition x=16: z7..13 solid, z14..15 open, z16..19 solid
     */
    public void dividedRooms() {
        platform();
        building(8, 6, 24, 20, 3, new int[][]{{12, 20}});
        wall(16, 7, 16, 13, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(16, 16, 16, 19, 3, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /**
     * Two single-story houses facing each other across an open street for the
     * contact-plan strand (clear_house_exit): an exposed lane crossing into a
     * structure whose back room still has to be checked. Layout (fixture-local
     * coords, standard 48x48 field):
     *   House A (west)  shell x 6..16,  z 10..20, walls h3, ONE door (16,15)
     *                   on its east wall — the only way in or out.
     *   House B (east)  shell x 25..37, z 10..20, walls h3, ONE door (25,15)
     *                   on its west wall, interior partition x=32 with its own
     *                   door (32,15) carving the small back room x 33..36 —
     *                   clearing B means entering AND checking.
     *   Street          x 17..24 (8 open blocks, no rubble by design): the
     *                   exposed crossing is the leg this fixture exists for.
     */
    public void streetBlock() {
        platform();
        // House A: sealed box, single east door.
        building(6, 10, 16, 20, 3, new int[][]{{16, 15}});
        // House B: single west door; the partition is carved as two segments
        // so the door cell (32,15) stays open — same technique as dividedRooms.
        building(25, 10, 37, 20, 3, new int[][]{{25, 15}});
        wall(32, 11, 32, 14, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(32, 16, 32, 19, 3, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /**
     * Long-course egress fixture (~74-block span): house A (west) is
     * partitioned into a front room (east) and a back room (west) with one
     * interior door, so soldiers spawned deep must path through both doors
     * to leave. A 50-block open street with scattered waist-high cover
     * separates it from house B (east, single west door). Long enough that
     * dock-window quantization reads as in-game minutes.
     */
    public void longStreetBlock() {
        platform();
        // House A MIRRORED: front room x7..10, back room x12..15, and the
        // single door on the WEST wall's south half (6,21) — the squad exits
        // away from the objective and must round the house to head east. The
        // south offset matters: the defender's street nest sits on the z=18.5
        // lane, and a door at z=18 would let his fire rake the whole interior
        // through the west door and the partition gap (7 deaths in one run);
        // at z=21 the lane terminates on the west wall and only the doorway
        // itself stays exposed.
        building(6, 12, 16, 24, 3, new int[][]{{6, 21}});
        wall(11, 13, 11, 17, 3, Blocks.STONE_BRICKS.defaultBlockState());
        wall(11, 19, 11, 23, 3, Blocks.STONE_BRICKS.defaultBlockState());
        // House B: single west door; the order pings its center. The 1-high
        // stub at x=73 gives the defender a dock-valid hold cell behind it —
        // without adjacent cover his HOLD relocation wanders (street nests,
        // doorway camps), and a door-camping guard makes the entrance a
        // point-blank death funnel the egress asserts cannot pace against.
        building(67, 12, 77, 24, 3, new int[][]{{67, 18}});
        coverRow(73, 13, 73, 17, 1);
        // Vertical cover lines (north-south walls) so the advance hops
        // block-to-block sideways around them, like street-side walls.
        coverRow(28, 12, 28, 17, 1);
        coverRow(40, 18, 40, 23, 1);
        coverRow(52, 12, 52, 17, 1);
        coverRow(62, 18, 62, 23, 1);
    }

    public void block(int x, int y, int z, BlockState state) {
        BlockPos pos = origin.offset(x, y, z);
        // The world silently rejects blocks above the build limit; surface that
        // here because a rejected cover block turns a scenario into an
        // un diagnosable empty-air run.
        if (pos.getY() > level.getMaxBuildHeight()) {
            throw new IllegalStateException("Sim fixture block above build limit at " + pos
                + " — lower the scenario yLevel (SimManager Y_SEPARATION/Y_BASE)");
        }
        level.setBlockAndUpdate(pos, state);
        placed.add(pos);
        placedStates.put(pos, state);
    }

    public void fill(BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            block(pos.getX() - origin.getX(), pos.getY() - origin.getY(),
                pos.getZ() - origin.getZ(), state);
        }
    }

    /** World position for a fixture-local coordinate (y=0 is standing surface). */
    public BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    /** Blocks placed so far (used by the recording height map). */
    public List<BlockPos> placedBlocks() {
        return placed;
    }

    /** Placed block states by world position (the boot-race heal pass re-applies these). */
    public Map<BlockPos, BlockState> placedStates() {
        return placedStates;
    }

    /** Removes exactly the blocks this fixture placed. */
    public void clear() {
        for (BlockPos pos : placed) {
            level.removeBlock(pos, false);
        }
        placed.clear();
        placedStates.clear();
    }
}

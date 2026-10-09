package com.stevesarmy.command;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.item.EnemySoldierSpawnEggItem;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the live CQB training course: a five-room building with interior
 * cover and an armed enemy garrison, spawned in front of the player so the
 * ATTACK clearing progression (ADVANCING → CLEARING → SECURING →
 * "Area secured") can be watched in game. Room-by-room ping practice works:
 * one ATTACK ping bounds a radius around the ping, so a building this size
 * is cleared a wing at a time.
 */
public final class CqbCourseBuilder {

    private CqbCourseBuilder() {}

    /* Course footprint in local coords: x 0..41, z 0..27, walls y 0..3 on the
     * origin's ground level. Layout:
     *   NW room   x 1..15,  z 1..13   (door south at (7,14), door east at (16,6))
     *   SW room   x 1..15,  z 15..26  (door west wall (0,8), door east at (16,22))
     *   HALL      x 17..40, z 1..13   (main doors north (20,0)(21,0), door south (26,14))
     *   SE-W room x 17..27, z 15..26  (door east (28,20))
     *   SE-E room x 29..40, z 15..26  (door south (34,27))
     */

    public static void build(ServerLevel level, ServerPlayer player) {
        BlockPos origin = player.blockPosition().offset(-21, 0, 30);
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState crate = Blocks.SMOOTH_STONE.defaultBlockState();

        // Flatten: clear the volume, pack the ground under it.
        for (int x = -3; x <= 44; x++) {
            for (int z = -3; z <= 30; z++) {
                for (int y = 0; y <= 9; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                for (int y = -3; y <= -1; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z),
                        y == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState());
                }
            }
        }

        // Exterior shell with doorways.
        placeBuilding(level, origin, 0, 0, 41, 27, brick, new int[][]{
            {20, 0}, {21, 0},   // main double door, north wall (toward the player)
            {41, 20},           // east door
            {0, 8},             // west door
            {34, 27}});         // south door

        // Eye-level windows on the north wall (peek/LoS dynamics from outside).
        carve(level, origin, 8, 2, 0);
        carve(level, origin, 32, 2, 0);

        // Interior wall A: x=16 (NW/SW vs HALL/SE), doors at z=6 and z=22.
        placeWallX(level, origin, 16, 1, 5, brick);
        placeWallX(level, origin, 16, 7, 13, brick);
        placeWallX(level, origin, 16, 15, 21, brick);
        placeWallX(level, origin, 16, 23, 26, brick);

        // Interior wall B: z=14 (north rooms vs south rooms), doors at x=7 and x=26.
        for (int x = 0; x <= 41; x++) {
            if (x == 7 || x == 26) continue;
            placeColumn(level, origin, x, 14, brick);
        }

        // Interior wall D: x=28 (SE-W vs SE-E), door at z=20.
        placeWallX(level, origin, 28, 15, 19, brick);
        placeWallX(level, origin, 28, 21, 26, brick);

        // Interior cover.
        cover(level, origin, crate, 22, 5);
        cover(level, origin, crate, 23, 5);
        cover(level, origin, crate, 33, 4);
        cover(level, origin, crate, 33, 5);
        cover(level, origin, crate, 28, 9);
        cover(level, origin, crate, 29, 9);
        cover(level, origin, crate, 30, 9);
        cover(level, origin, crate, 6, 4);
        cover(level, origin, crate, 10, 9);
        cover(level, origin, crate, 5, 20);
        cover(level, origin, crate, 11, 23);
        cover(level, origin, crate, 20, 22);
        cover(level, origin, crate, 35, 18);
        cover(level, origin, crate, 33, 24);

        // Enemy garrison: one per room, facing its doorway, defending its post.
        spawnEnemy(level, origin, 8, 3, 0.0f);    // NW, facing the north-south door line
        spawnEnemy(level, origin, 8, 24, 180.0f); // SW, facing north
        spawnEnemy(level, origin, 31, 4, 90.0f);  // HALL east pocket, facing west
        spawnEnemy(level, origin, 22, 25, 180.0f);// SE-W, facing north
        spawnEnemy(level, origin, 37, 22, 90.0f); // SE-E, facing west

        BlockPos mainDoor = origin.offset(20, 0, 0);
        player.sendSystemMessage(Component.literal(
            "[Course] CQB training course built ahead: 5 rooms, 5 armed defenders. Main double "
                + "door at " + mainDoor.getX() + ", " + mainDoor.getY() + ", " + mainDoor.getZ()
                + " (north wall, facing you; windows at x" + (origin.getX() + 8)
                + " and x" + (origin.getX() + 32) + ")."));
        player.sendSystemMessage(Component.literal(
            "[Course] Spawn your squad between you and the building, then ATTACK-ping just inside "
                + "the main door — the order bounds ~8 blocks around the ping, so clear wing by "
                + "wing: north rooms first, then ping into each remaining room. Stage messages "
                + "play in chat; FOV cones were enabled (/stevesarmy_debug render fov toggles)."));
        StevesArmyMod.LOGGER.info("[Course] Built CQB course at {} by {}", origin, player.getName().getString());
    }

    private static void placeBuilding(ServerLevel level, BlockPos origin,
                                      int x1, int z1, int x2, int z2,
                                      BlockState state, int[][] doors) {
        java.util.Set<Long> doorSet = new java.util.HashSet<>();
        for (int[] d : doors) doorSet.add(((long) d[0] << 32) ^ (d[1] & 0xffffffffL));
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (!edge || doorSet.contains(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                placeColumn(level, origin, x, z, state);
            }
        }
    }

    private static void placeWallX(ServerLevel level, BlockPos origin,
                                   int x, int z1, int z2, BlockState state) {
        for (int z = z1; z <= z2; z++) {
            placeColumn(level, origin, x, z, state);
        }
    }

    private static void placeColumn(ServerLevel level, BlockPos origin, int x, int z, BlockState state) {
        for (int y = 0; y <= 3; y++) {
            level.setBlockAndUpdate(origin.offset(x, y, z), state);
        }
    }

    private static void cover(ServerLevel level, BlockPos origin, BlockState state, int x, int z) {
        level.setBlockAndUpdate(origin.offset(x, 0, z), state);
    }

    private static void carve(ServerLevel level, BlockPos origin, int x, int y, int z) {
        level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
    }

    private static void spawnEnemy(ServerLevel level, BlockPos origin, int x, int z, float yaw) {
        EnemySoldierEntity enemy = ModEntities.ENEMY_SOLDIER.get().create(level);
        if (enemy == null) return;
        BlockPos post = origin.offset(x, 0, z);
        enemy.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, yaw, 0);
        enemy.setYHeadRot(yaw);
        enemy.setYBodyRot(yaw);
        enemy.setDefendPosition(post.immutable());
        enemy.setPersistenceRequired();
        EnemySoldierSpawnEggItem.equipAk47(enemy);
        enemy.configureInfiniteReserveAmmo();
        level.addFreshEntity(enemy);
    }
}

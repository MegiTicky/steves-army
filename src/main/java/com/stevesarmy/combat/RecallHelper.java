package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RecallHelper {

    private static final int[] DISTANCES = {10, 15, 20, 25, 30};
    private static final int ANGLES = 16;
    private static final int ENEMY_SEARCH_RADIUS = 32;
    private static final int ENEMY_SAFE_DISTANCE = 10;
    private static final int VERTICAL_SEARCH_RANGE = 12;

    @Nullable
    public static SoldierEntity executeRecall(SoldierEntity soldier, ServerPlayer player) {
        if (soldier.level().isClientSide) return null;
        ServerLevel destinationLevel = player.serverLevel();

        BlockPos pos = findSafeRecallPosition(destinationLevel, soldier, player);
        if (pos == null) {
            soldier.cancelRecall();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "No safe recall position found for " + soldier.getName().getString()));
            return null;
        }

        SoldierEntity recalled = soldier;
        if (soldier.level() != destinationLevel) {
            Entity transferred = soldier.changeDimension(destinationLevel);
            if (!(transferred instanceof SoldierEntity transferredSoldier)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Could not transfer " + soldier.getName().getString() + " to your dimension"));
                return null;
            }
            recalled = transferredSoldier;
        }

        recalled.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        recalled.setSquadMode(com.stevesarmy.squad.SquadMode.FOLLOW);
        recalled.setTarget(null);
        recalled.getNavigation().stop();

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "Recalled " + recalled.getName().getString()));
        recalled.setRecallTicks(0);
        return recalled;
    }

    @Nullable
    public static BlockPos findSafeRecallPosition(ServerLevel level, SoldierEntity soldier, Player player) {
        BlockPos playerPos = player.blockPosition();
        Vec3 soldierPos = soldier.level() == level ? soldier.position() : player.position();

        List<BlockPos> candidates = new ArrayList<>();
        double angleOffset = level.random.nextDouble() * (Math.PI * 2 / ANGLES);

        for (int dist : DISTANCES) {
            for (int i = 0; i < ANGLES; i++) {
                double angle = angleOffset + (Math.PI * 2 * i / ANGLES);
                int x = playerPos.getX() + (int) Math.round(dist * Math.cos(angle));
                int z = playerPos.getZ() + (int) Math.round(dist * Math.sin(angle));
                BlockPos surface = findSurfaceAt(level, x, z, playerPos.getY());
                if (surface != null) {
                    candidates.add(surface);
                }
            }
        }

        List<LivingEntity> enemies = findEnemies(level, soldier, player, ENEMY_SEARCH_RADIUS);

        List<ScoredPosition> scored = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (!isValidStanding(level, candidate)) continue;
            if (isNearHazard(level, candidate)) continue;
            if (isNearEnemy(level, candidate, enemies, ENEMY_SAFE_DISTANCE)) continue;

            double enemyDist = distanceToNearestEnemy(candidate, enemies);
            double soldierDist = candidate.distToCenterSqr(soldierPos.x, soldierPos.y, soldierPos.z);
            int vertDiff = Math.abs(candidate.getY() - playerPos.getY());

            scored.add(new ScoredPosition(candidate, enemyDist, soldierDist, vertDiff));
        }

        if (scored.isEmpty()) return null;

        scored.sort(Comparator
            .<ScoredPosition>comparingDouble(s -> -s.enemyDist)
            .thenComparingDouble(s -> s.soldierDist)
            .thenComparingInt(s -> s.vertDiff));

        return scored.get(0).pos;
    }

    @Nullable
    private static BlockPos findSurfaceAt(ServerLevel level, int x, int z, int playerY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, playerY + VERTICAL_SEARCH_RANGE, z);
        int minY = Math.max(level.getMinBuildHeight(), playerY - VERTICAL_SEARCH_RANGE);

        while (cursor.getY() >= minY) {
            BlockState state = level.getBlockState(cursor);
            BlockState below = level.getBlockState(cursor.below());
            if (state.isAir() && below.isSolid()) {
                return cursor.immutable();
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    private static boolean isValidStanding(ServerLevel level, BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.isSolid()) return false;
        if (!level.getBlockState(pos).isAir()) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        if (level.getFluidState(pos).is(FluidTags.WATER) || level.getFluidState(pos.above()).is(FluidTags.WATER)) return false;
        return true;
    }

    private static boolean isNearHazard(ServerLevel level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockState state = level.getBlockState(pos.offset(dx, dy, dz));
                    if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)) {
                        return true;
                    }
                    if (state.is(Blocks.POWDER_SNOW)) return true;
                }
            }
        }
        return false;
    }

    private static List<LivingEntity> findEnemies(ServerLevel level, SoldierEntity soldier, Player player, int radius) {
        AABB searchBox = player.getBoundingBox().inflate(radius);
        List<LivingEntity> enemies = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, searchBox)) {
            if (entity == player || entity == soldier) continue;
            if (entity.isAlliedTo(player)) continue;
            if (entity instanceof com.stevesarmy.entity.EnemySoldierEntity) {
                enemies.add(entity);
                continue;
            }
            if (entity instanceof SoldierEntity other) {
                if (other.isOwnedBy((Player) player)) continue;
                enemies.add(entity);
                continue;
            }
            if (entity instanceof Player otherPlayer && !otherPlayer.isAlliedTo(player)) {
                enemies.add(entity);
                continue;
            }
            if (entity instanceof Mob mob && mob.getTarget() == player) {
                enemies.add(entity);
                continue;
            }
        }
        return enemies;
    }

    private static boolean isNearEnemy(ServerLevel level, BlockPos pos, List<LivingEntity> enemies, int safeDist) {
        double safeDistSqr = safeDist * safeDist;
        Vec3 posVec = Vec3.atCenterOf(pos);
        for (LivingEntity enemy : enemies) {
            if (enemy.distanceToSqr(posVec) < safeDistSqr) return true;
        }
        return false;
    }

    private static double distanceToNearestEnemy(BlockPos pos, List<LivingEntity> enemies) {
        Vec3 posVec = Vec3.atCenterOf(pos);
        double min = Double.MAX_VALUE;
        for (LivingEntity enemy : enemies) {
            double d = enemy.distanceToSqr(posVec);
            if (d < min) min = d;
        }
        return min;
    }

    private static class ScoredPosition {
        final BlockPos pos;
        final double enemyDist;
        final double soldierDist;
        final int vertDiff;

        ScoredPosition(BlockPos pos, double enemyDist, double soldierDist, int vertDiff) {
            this.pos = pos;
            this.enemyDist = enemyDist;
            this.soldierDist = soldierDist;
            this.vertDiff = vertDiff;
        }
    }
}

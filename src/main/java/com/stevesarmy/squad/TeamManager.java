package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.UUID;

public class TeamManager {
    private static final String FRIENDLY_TEAM_PREFIX = "steves_army_friendly_";
    private static final String ENEMY_TEAM_NAME = "steves_army_enemy";

    public static void assignToFriendlyTeam(Entity entity, UUID ownerUUID) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = getOwnerTeam(scoreboard, entity, ownerUUID);
        addToTeam(scoreboard, entity, team);
    }

    public static void assignToEnemyTeam(Entity entity) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = getOrCreateTeam(scoreboard, ENEMY_TEAM_NAME, ChatFormatting.RED);
        addToTeam(scoreboard, entity, team);
    }

    public static void addPlayerToFriendlyTeam(ServerPlayer player, UUID ownerUUID) {
        if (player.level().isClientSide) return;
        Scoreboard scoreboard = player.level().getScoreboard();
        if (player.getTeam() == null) {
            addPlayerToTeam(scoreboard, player, getOrCreateOwnerTeam(scoreboard, ownerUUID));
        }
    }

    public static void synchronizeOwnedSoldiers(MinecraftServer server, ServerPlayer owner) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof SoldierEntity soldier && soldier.isOwnedBy(owner)) {
                    synchronizeSoldierTeam(soldier, owner);
                }
            }
        }
    }

    public static void synchronizeSoldierTeam(SoldierEntity soldier, ServerPlayer owner) {
        if (soldier.level().isClientSide) return;

        Scoreboard scoreboard = soldier.level().getScoreboard();
        PlayerTeam desiredTeam = resolveOwnerTeam(scoreboard, owner);
        PlayerTeam currentTeam = soldier.getTeam() instanceof PlayerTeam team ? team : null;
        if (currentTeam == desiredTeam) return;

        String oldTeam = currentTeam == null ? "none" : currentTeam.getName();
        addToTeam(scoreboard, soldier, desiredTeam);
        StevesArmyMod.LOGGER.info("[SoldierTeam] owner={} old={} new={} soldier={}",
            owner.getScoreboardName(), oldTeam, desiredTeam.getName(), soldier.getStringUUID());
    }

    public static void removePlayerFromTeam(ServerPlayer player) {
        if (player.level().isClientSide) return;
        Scoreboard scoreboard = player.level().getScoreboard();
        if (player.getTeam() instanceof PlayerTeam playerTeam) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), playerTeam);
        }
    }

    public static void removeFromTeam(Entity entity) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        if (entity.getTeam() instanceof PlayerTeam playerTeam) {
            scoreboard.removePlayerFromTeam(entity.getStringUUID(), playerTeam);
        }
    }

    public static boolean isOnFriendlyTeam(Entity entity) {
        return entity.getTeam() != null && entity.getTeam().getName().startsWith(FRIENDLY_TEAM_PREFIX);
    }

    public static boolean isOnEnemyTeam(Entity entity) {
        return entity.getTeam() != null && ENEMY_TEAM_NAME.equals(entity.getTeam().getName());
    }

    private static PlayerTeam getOrCreateTeam(Scoreboard scoreboard, String name, ChatFormatting color) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setColor(color);
            team.setAllowFriendlyFire(false);
            team.setSeeFriendlyInvisibles(true);
            team.setNameTagVisibility(PlayerTeam.Visibility.HIDE_FOR_OTHER_TEAMS);
            StevesArmyMod.LOGGER.info("Created scoreboard team: {} with color {}", name, color.getName());
        }
        return team;
    }

    private static PlayerTeam getOrCreateOwnerTeam(Scoreboard scoreboard, UUID ownerUUID) {
        return getOrCreateTeam(scoreboard, FRIENDLY_TEAM_PREFIX + ownerUUID, ChatFormatting.WHITE);
    }

    private static PlayerTeam resolveOwnerTeam(Scoreboard scoreboard, ServerPlayer owner) {
        if (owner.getTeam() instanceof PlayerTeam ownerTeam && !isPrivateFriendlyTeam(ownerTeam)) {
            PlayerTeam team = scoreboard.getPlayerTeam(ownerTeam.getName());
            if (team != null) return team;
        }
        return getOrCreateOwnerTeam(scoreboard, owner.getUUID());
    }

    private static PlayerTeam getOwnerTeam(Scoreboard scoreboard, Entity entity, UUID ownerUUID) {
        if (entity.level() instanceof ServerLevel serverLevel) {
            ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(ownerUUID);
            if (owner != null) return resolveOwnerTeam(scoreboard, owner);
        }
        return getOrCreateOwnerTeam(scoreboard, ownerUUID);
    }

    private static boolean isPrivateFriendlyTeam(PlayerTeam team) {
        return team.getName().startsWith(FRIENDLY_TEAM_PREFIX);
    }

    private static void addToTeam(Scoreboard scoreboard, Entity entity, PlayerTeam team) {
        if (entity.getTeam() instanceof PlayerTeam currentTeam) {
            if (currentTeam == team) return;
            scoreboard.removePlayerFromTeam(entity.getStringUUID(), currentTeam);
        }
        scoreboard.addPlayerToTeam(entity.getStringUUID(), team);
    }

    private static void addPlayerToTeam(Scoreboard scoreboard, ServerPlayer player, PlayerTeam team) {
        if (player.getTeam() instanceof PlayerTeam currentTeam) {
            if (currentTeam == team) return;
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), currentTeam);
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }
}

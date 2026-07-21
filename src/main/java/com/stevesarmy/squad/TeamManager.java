package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public class TeamManager {
    private static final String FRIENDLY_TEAM_NAME = "steves_army_friendly";
    private static final String ENEMY_TEAM_NAME = "steves_army_enemy";

    public static void assignToFriendlyTeam(Entity entity) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = getOrCreateTeam(scoreboard, FRIENDLY_TEAM_NAME, ChatFormatting.BLUE);
        addToTeam(scoreboard, entity, team);
    }

    public static void assignToEnemyTeam(Entity entity) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = getOrCreateTeam(scoreboard, ENEMY_TEAM_NAME, ChatFormatting.RED);
        addToTeam(scoreboard, entity, team);
    }

    public static void removeFromTeam(Entity entity) {
        if (entity.level().isClientSide) return;
        Scoreboard scoreboard = entity.level().getScoreboard();
        if (entity.getTeam() instanceof PlayerTeam playerTeam) {
            scoreboard.removePlayerFromTeam(entity.getStringUUID(), playerTeam);
        }
    }

    public static boolean isOnFriendlyTeam(Entity entity) {
        return entity.getTeam() != null && FRIENDLY_TEAM_NAME.equals(entity.getTeam().getName());
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
            StevesArmyMod.LOGGER.info("Created scoreboard team: {} with color {}", name, color.getName());
        }
        return team;
    }

    private static void addToTeam(Scoreboard scoreboard, Entity entity, PlayerTeam team) {
        if (entity.getTeam() instanceof PlayerTeam currentTeam) {
            if (currentTeam == team) return;
            scoreboard.removePlayerFromTeam(entity.getStringUUID(), currentTeam);
        }
        scoreboard.addPlayerToTeam(entity.getStringUUID(), team);
    }
}
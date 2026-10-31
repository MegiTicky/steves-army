package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TeamGarrisonEntity;
import com.stevesarmy.squad.FofRelations;
import com.stevesarmy.squad.FofStance;
import com.stevesarmy.squad.TeamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.UUID;

/** The relationship and server-policy checks shared by soldier combat paths. */
public final class FofCombat {
    private FofCombat() {}

    private record Identity(@Nullable UUID player, @Nullable String team, boolean affiliated) {}

    private static Identity identity(LivingEntity entity) {
        if (entity instanceof EnemySoldierEntity) return new Identity(null, null, false);
        UUID player = entity instanceof Player p ? p.getUUID()
            : entity instanceof SoldierEntity s ? s.getOwnerUUID().orElse(null) : null;
        Team team = entity instanceof SoldierEntity s ? TeamManager.getEffectiveTeam(s) : entity.getTeam();
        String name = team == null ? null : team.getName();
        if (name != null && name.startsWith("steves_army_friendly_")) name = null;
        return new Identity(player, name, player != null || entity instanceof TeamGarrisonEntity);
    }

    public static FofRelations.Resolution relation(LivingEntity first, LivingEntity second) {
        if (first == second) return new FofRelations.Resolution(FofStance.FRIENDLY, "self");
        if (first instanceof SoldierEntity firstSoldier && firstSoldier.getOwnerUUID().filter(second.getUUID()::equals).isPresent()
            || second instanceof SoldierEntity secondSoldier && secondSoldier.getOwnerUUID().filter(first.getUUID()::equals).isPresent()) {
            return new FofRelations.Resolution(FofStance.FRIENDLY, "owner");
        }
        if (first instanceof EnemySoldierEntity || second instanceof EnemySoldierEntity) {
            if (first instanceof EnemySoldierEntity && second instanceof EnemySoldierEntity)
                return new FofRelations.Resolution(FofStance.FRIENDLY, "enemy-faction");
            LivingEntity other = first instanceof EnemySoldierEntity ? second : first;
            Identity otherIdentity = identity(other);
            MinecraftServer server = first.level().getServer();
            if (server != null && otherIdentity.team != null) {
                FofRelations.Resolution configured = FofRelations.get(server).resolve(null, "steves_army_enemy",
                    otherIdentity.player, otherIdentity.team, server.overworld().getGameTime());
                if (configured.source().equals("team-pair")) return configured;
            }
            return new FofRelations.Resolution(FofStance.HOSTILE, "enemy-faction");
        }
        MinecraftServer server = first.level().getServer();
        Identity a = identity(first), b = identity(second);
        if (a.player != null && a.player.equals(b.player)) {
            return new FofRelations.Resolution(FofStance.FRIENDLY, "owner");
        }
        if (server != null && a.affiliated && b.affiliated) {
            return FofRelations.get(server).resolve(a.player, a.team, b.player, b.team,
                server.overworld().getGameTime());
        }
        if (first.getTeam() != null && second.getTeam() != null
            && first.getTeam().isAlliedTo(second.getTeam())) {
            return new FofRelations.Resolution(FofStance.FRIENDLY, "same-team");
        }
        return new FofRelations.Resolution(FofStance.HOSTILE, "other");
    }

    public static boolean friendly(LivingEntity first, LivingEntity second) {
        return relation(first, second).stance() == FofStance.FRIENDLY;
    }

    public static boolean policyAllows(LivingEntity attacker, LivingEntity target) {
        Identity a = identity(attacker), b = identity(target);
        if (!a.affiliated || !b.affiliated) return true;
        StevesArmyConfig.CombatPolicy policy = StevesArmyConfig.getCombatPolicy();
        if (policy == StevesArmyConfig.CombatPolicy.PVE_ONLY) return false;
        if (policy == StevesArmyConfig.CombatPolicy.TEAM_PVP) {
            String source = relation(attacker, target).source();
            return source.startsWith("event:") || source.equals("team-pair");
        }
        return true;
    }

    /** True when a server combat policy forbids deliberate fire or suppression. */
    public static boolean blocksCombat(LivingEntity attacker, LivingEntity target) {
        return !policyAllows(attacker, target);
    }

    public static boolean mayEngage(LivingEntity attacker, LivingEntity target) {
        if (attacker == target || !policyAllows(attacker, target)) return false;
        FofStance stance = relation(attacker, target).stance();
        if (stance == FofStance.FRIENDLY) return false;
        if (stance == FofStance.HOSTILE) return true;
        Identity a = identity(attacker), b = identity(target);
        MinecraftServer server = attacker.level().getServer();
        return server != null && a.player != null && b.player != null
            && FofRelations.get(server).mayRetaliate(a.player, b.player, server.overworld().getGameTime());
    }

    public static void recordAttack(LivingEntity attacker, LivingEntity victim) {
        MinecraftServer server = attacker.level().getServer();
        Identity a = identity(attacker), b = identity(victim);
        if (server != null && a.player != null && b.player != null && policyAllows(attacker, victim)) {
            FofRelations.get(server).recordHostileAction(a.player, b.player, server.overworld().getGameTime());
        }
    }
}

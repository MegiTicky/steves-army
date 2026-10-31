package com.stevesarmy.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.stevesarmy.network.FofSyncPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SetFofStancePacket;
import com.stevesarmy.squad.FofRelations;
import com.stevesarmy.squad.FofStance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Operator controls for shared diplomacy and temporary events. */
public final class DiplomacyCommand {
    private DiplomacyCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("diplomacy")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Diplomacy: team <teamA> <teamB> <friendly|neutral|hostile|default>; player <playerA> <playerB> <stance>; "
                    + "event start <id> <teamA> <teamB> <minutes>; event end <id>; event list. "
                    + "A duration of 0 keeps the event active until ended."), false);
                return 1;
            })
            .then(Commands.literal("team")
                .then(Commands.argument("teamA", StringArgumentType.word())
                    .then(Commands.argument("teamB", StringArgumentType.word())
                        .then(teamStance("friendly", FofStance.FRIENDLY))
                        .then(teamStance("neutral", FofStance.NEUTRAL))
                        .then(teamStance("hostile", FofStance.HOSTILE))
                        .then(teamStance("default", null)))))
            .then(Commands.literal("player")
                .then(Commands.argument("playerA", GameProfileArgument.gameProfile())
                    .then(Commands.argument("playerB", GameProfileArgument.gameProfile())
                        .then(playerStance("friendly", FofStance.FRIENDLY))
                        .then(playerStance("neutral", FofStance.NEUTRAL))
                        .then(playerStance("hostile", FofStance.HOSTILE))
                        .then(playerStance("default", null)))))
            .then(Commands.literal("event")
                .then(Commands.literal("start")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("teamA", StringArgumentType.word())
                            .then(Commands.argument("teamB", StringArgumentType.word())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 1000000))
                                    .executes(DiplomacyCommand::startEvent))))))
                .then(Commands.literal("end")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .executes(DiplomacyCommand::endEvent)))
                .then(Commands.literal("add")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.literal("left").then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .executes(ctx -> changeParticipant(ctx, true, true))))
                        .then(Commands.literal("right").then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .executes(ctx -> changeParticipant(ctx, true, false))))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .executes(ctx -> changeParticipant(ctx, false, false)))))
                .then(Commands.literal("list").executes(DiplomacyCommand::listEvents)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> playerStance(String name, @Nullable FofStance stance) {
        return Commands.literal(name).executes(ctx -> {
            var first = GameProfileArgument.getGameProfiles(ctx, "playerA");
            var second = GameProfileArgument.getGameProfiles(ctx, "playerB");
            if (first.size() != 1 || second.size() != 1) {
                ctx.getSource().sendFailure(Component.literal("Choose exactly one player for each side."));
                return 0;
            }
            GameProfile a = first.iterator().next();
            GameProfile b = second.iterator().next();
            MinecraftServer server = ctx.getSource().getServer();
            if (!FofRelations.get(server).forcePlayerRelation(a.getId(), b.getId(), stance)) {
                ctx.getSource().sendFailure(Component.literal("Choose two different players."));
                return 0;
            }
            sync(server);
            ctx.getSource().sendSuccess(() -> Component.literal("Diplomacy: " + a.getName() + " and " + b.getName()
                + " = " + (stance == null ? "default" : stance.getDisplayName())), true);
            return 1;
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> teamStance(String name, @Nullable FofStance stance) {
        return Commands.literal(name).executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            String first = StringArgumentType.getString(ctx, "teamA");
            String second = StringArgumentType.getString(ctx, "teamB");
            if (server.getScoreboard().getPlayerTeam(first) == null || server.getScoreboard().getPlayerTeam(second) == null
                || !FofRelations.get(server).setTeamRelation(first, second, stance)) {
                ctx.getSource().sendFailure(Component.literal("Choose two different existing teams."));
                return 0;
            }
            sync(server);
            ctx.getSource().sendSuccess(() -> Component.literal("Diplomacy: " + first + " and " + second + " = "
                + (stance == null ? "default" : stance.getDisplayName())), true);
            return 1;
        });
    }

    private static int startEvent(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String id = StringArgumentType.getString(ctx, "id");
        String firstName = StringArgumentType.getString(ctx, "teamA");
        String secondName = StringArgumentType.getString(ctx, "teamB");
        PlayerTeam first = server.getScoreboard().getPlayerTeam(firstName);
        PlayerTeam second = server.getScoreboard().getPlayerTeam(secondName);
        if (first == null || second == null || first == second) {
            ctx.getSource().sendFailure(Component.literal("Choose two different existing teams."));
            return 0;
        }
        Set<UUID> left = snapshot(server, first);
        Set<UUID> right = snapshot(server, second);
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Each team needs at least one player with a known profile; unresolved scoreboard members prevent an incomplete snapshot."));
            return 0;
        }
        long tick = server.overworld().getGameTime();
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        long expires = minutes == 0 ? 0 : tick + minutes * 1200L;
        FofRelations relations = FofRelations.get(server);
        relations.events(tick);
        if (!relations.startEvent(id, left, right, Set.of(firstName), Set.of(secondName), FofStance.HOSTILE, expires)) {
            ctx.getSource().sendFailure(Component.literal("Event could not start: duplicate ID, overlapping players, or conflicting event."));
            return 0;
        }
        sync(server);
        server.getPlayerList().broadcastSystemMessage(Component.literal("Military event " + id + ": "
            + firstName + " and " + secondName + " are hostile (" + left.size() + " vs " + right.size() + " players)."), false);
        return 1;
    }

    @Nullable
    private static Set<UUID> snapshot(MinecraftServer server, PlayerTeam team) {
        Set<UUID> result = new HashSet<>();
        for (String member : team.getPlayers()) {
            // Soldier scoreboard entries are UUIDs, not player profile names.
            try { UUID.fromString(member); continue; } catch (IllegalArgumentException ignored) {}
            ServerPlayer online = server.getPlayerList().getPlayerByName(member);
            if (online != null) { result.add(online.getUUID()); continue; }
            GameProfile profile = server.getProfileCache() == null ? null : server.getProfileCache().get(member).orElse(null);
            if (profile == null || profile.getId() == null) return null;
            result.add(profile.getId());
        }
        return result;
    }

    private static int endEvent(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String id = StringArgumentType.getString(ctx, "id");
        if (!FofRelations.get(server).endEvent(id)) {
            ctx.getSource().sendFailure(Component.literal("No active event named " + id));
            return 0;
        }
        sync(server);
        server.getPlayerList().broadcastSystemMessage(Component.literal("Military event " + id + " ended."), false);
        return 1;
    }

    private static int changeParticipant(CommandContext<CommandSourceStack> ctx, boolean add, boolean left)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.literal("Choose exactly one player."));
            return 0;
        }
        GameProfile player = profiles.iterator().next();
        MinecraftServer server = ctx.getSource().getServer();
        String id = StringArgumentType.getString(ctx, "id");
        FofRelations relations = FofRelations.get(server);
        relations.events(server.overworld().getGameTime());
        boolean changed = add ? relations.addEventParticipant(id, left, player.getId())
            : relations.removeEventParticipant(id, player.getId());
        if (!changed) {
            ctx.getSource().sendFailure(Component.literal("Roster change rejected: event/player missing, invalid side, or conflicting event."));
            return 0;
        }
        sync(server);
        server.getPlayerList().broadcastSystemMessage(Component.literal("Military event " + id + ": " + player.getName()
            + (add ? " joined " + (left ? "left" : "right") + " side." : " was removed.")), false);
        return 1;
    }

    private static int listEvents(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        long tick = server.overworld().getGameTime();
        var events = FofRelations.get(server).events(tick);
        StringBuilder result = new StringBuilder("Military events:");
        if (events.isEmpty()) result.append(" none");
        for (var event : events) {
            result.append("\n").append(event.id()).append(": ").append(event.left().size()).append(" vs ")
                .append(event.right().size()).append(", ").append(event.stance().getDisplayName()).append(", ")
                .append(event.expiresAt() == 0 ? "no expiry" : Math.max(0, (event.expiresAt() - tick + 19) / 20) + "s remaining");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(result.toString()), false);
        return events.size();
    }

    private static void sync(MinecraftServer server) {
        SetFofStancePacket.syncAll(server);
    }
}

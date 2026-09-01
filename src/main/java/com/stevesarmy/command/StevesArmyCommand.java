package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.stevesarmy.client.CombatDebugRenderer;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.GarrisonEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.TeamGarrisonEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.GrenadeTacticalController;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.Function;

public class StevesArmyCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(createRoot("stevesarmy"));
        dispatcher.register(createRoot("steves_army"));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> createRoot(String name) {
        return Commands.literal(name)
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Steve's Army Commands:\n" +
                    "  /stevesarmy debug - Enable all debug (shortcut for /stevesarmy_debug all)\n" +
                    "  /stevesarmy grenade <soldier> <target> - Force a safe grenade throw and report the reason if blocked\n" +
                    "  /stevesarmy loadout save <rifleman> - Copy a rifleman inventory loadout\n" +
                    "  /stevesarmy spawn rifleman <owner> [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn machine_gunner <owner> [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn support <owner> [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn garrison <owner> [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn team_garrison <team> [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn enemy [squad <callsign>] <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy squad create <callsign> [owner|team <team>|enemy]\n" +
                    "  /stevesarmy squad list\n" +
                    "  /stevesarmy squad info <callsign>\n" +
                    "  /stevesarmy squad disband <callsign>\n" +
                    "  /stevesarmy squad order <callsign> <goto|send|attack|threat|suppress|hold|follow> [pos|player]"
                ), false);
                return 1;
            })
            .then(Commands.literal("debug")
                .executes(StevesArmyCommand::enableAllDebug)
            )
            .then(Commands.literal("grenade")
                .then(Commands.argument("soldier", EntityArgument.entity())
                    .then(Commands.argument("target", EntityArgument.entity())
                        .executes(StevesArmyCommand::forceGrenadeThrow)
                    )
                )
            )
            .then(Commands.literal("loadout")
                .then(Commands.literal("save")
                    .then(Commands.argument("rifleman", EntityArgument.entity())
                        .executes(StevesArmyCommand::saveLoadout)
                    )
                )
            )
            .then(Commands.literal("spawn")
                .then(createOwnedSpawnBranch("rifleman", ModEntities.SOLDIER.get()))
                .then(createOwnedSpawnBranch("machine_gunner", ModEntities.MACHINE_GUNNER.get()))
                .then(createOwnedSpawnBranch("support", ModEntities.SUPPORT.get()))
                .then(createOwnedSpawnBranch("garrison", ModEntities.GARRISON.get()))
                .then(Commands.literal("team_garrison")
                    .then(Commands.argument("team", StringArgumentType.word())
                        .then(Commands.literal("squad")
                            .then(Commands.argument("callsign", StringArgumentType.word())
                                .then(createTeamGarrisonSquadSpawnArgs())))
                        .then(createTeamGarrisonSpawnArgs())))
                .then(Commands.literal("enemy")
                    .then(Commands.literal("squad")
                        .then(Commands.argument("callsign", StringArgumentType.word())
                            .then(createEnemySquadSpawnArgs())))
                    .then(createSpawnArguments(ModEntities.ENEMY_SOLDIER.get(), false)))
            )
            .then(Commands.literal("squad")
                .then(Commands.literal("create")
                    .then(Commands.argument("callsign", StringArgumentType.word())
                        .executes(ctx -> squadCreate(ctx, StringArgumentType.getString(ctx, "callsign"), null, null))
                        .then(Commands.argument("owner", EntityArgument.player())
                            .executes(ctx -> squadCreate(ctx, StringArgumentType.getString(ctx, "callsign"), EntityArgument.getPlayer(ctx, "owner").getUUID(), null)))
                        .then(Commands.literal("team")
                            .then(Commands.argument("team", StringArgumentType.word())
                                .executes(ctx -> {
                                    String cs = StringArgumentType.getString(ctx, "callsign");
                                    String team = StringArgumentType.getString(ctx, "team");
                                    return squadCreate(ctx, cs, SoldierSpawner.teamLeaderId(team), team);
                                })))
                        .then(Commands.literal("enemy")
                            .executes(ctx -> {
                                String cs = StringArgumentType.getString(ctx, "callsign");
                                return squadCreate(ctx, cs, SoldierSpawner.enemyLeaderId(cs), null);
                            }))))
                .then(Commands.literal("list")
                    .executes(StevesArmyCommand::squadList))
                .then(Commands.literal("info")
                    .then(Commands.argument("callsign", StringArgumentType.word())
                        .executes(ctx -> squadInfo(ctx, StringArgumentType.getString(ctx, "callsign")))))
                .then(Commands.literal("disband")
                    .then(Commands.argument("callsign", StringArgumentType.word())
                        .executes(ctx -> squadDisband(ctx, StringArgumentType.getString(ctx, "callsign")))))
                .then(Commands.literal("order")
                    .then(Commands.argument("callsign", StringArgumentType.word())
                        .then(Commands.literal("goto")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.GO_TO, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("go_to")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.GO_TO, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("send")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.SEND, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("attack")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.ATTACK, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("threat")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.THREAT_DIRECTION, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("suppress")
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrder(ctx, StringArgumentType.getString(ctx, "callsign"), PingType.SUPPRESS_AREA, Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("hold")
                            .executes(ctx -> squadOrderHold(ctx, StringArgumentType.getString(ctx, "callsign"), null))
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> squadOrderHold(ctx, StringArgumentType.getString(ctx, "callsign"), Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.literal("follow")
                            .executes(ctx -> squadOrderFollow(ctx, StringArgumentType.getString(ctx, "callsign"), null))
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> squadOrderFollow(ctx, StringArgumentType.getString(ctx, "callsign"), EntityArgument.getPlayer(ctx, "player")))))))
            );
    }

    private static int squadCreate(CommandContext<CommandSourceStack> ctx, String rawCallsign, UUID leaderId, String teamName) {
        ServerLevel level = ctx.getSource().getLevel();
        String n = rawCallsign.toLowerCase(java.util.Locale.ROOT);
        if (!SquadData.CALLSIGN_PATTERN.matcher(n).matches()) {
            ctx.getSource().sendFailure(Component.literal("Invalid callsign '" + rawCallsign + "': must match [a-z0-9_-]{1,16}"));
            return 0;
        }
        SquadManager mgr = SquadManager.get(level);
        if (mgr.getSquadByCallsign(n).isPresent()) {
            ctx.getSource().sendFailure(Component.literal("Callsign already exists: " + n));
            return 0;
        }
        UUID leader = leaderId;
        if (leader == null) {
            try {
                Entity src = ctx.getSource().getEntity();
                if (src instanceof Player p) leader = p.getUUID();
            } catch (Exception ignored) {}
            if (leader == null) {
                ctx.getSource().sendFailure(Component.literal("Must specify owner, team <name> or enemy when creating squad from non-player (CC)"));
                return 0;
            }
        }
        try {
            SquadData squad = mgr.createCallsignSquad(leader, rawCallsign);
            String desc = teamName != null ? " team " + teamName : " leader " + leader.toString().substring(0, 8);
            ctx.getSource().sendSuccess(() -> Component.literal("Created squad '" + squad.getCallsign() + "' (" + squad.getDisplayCallsign() + ") for" + desc + " id " + squad.getSquadId()), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Failed to create squad: " + e.getMessage()));
            return 0;
        }
    }

    private static int squadList(CommandContext<CommandSourceStack> ctx) {
        SquadManager mgr = SquadManager.get(ctx.getSource().getLevel());
        var squads = mgr.getAllSquads().stream().filter(SquadData::hasCallsign).toList();
        if (squads.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No callsign squads"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Squads (" + squads.size() + "):\n");
        for (SquadData s : squads) {
            sb.append("  ").append(s.getCallsign()).append(" [").append(s.getDisplayCallsign()).append("] members=").append(s.getMemberCount()).append(" leader=").append(s.getLeaderId().toString().substring(0, 8)).append("\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int squadInfo(CommandContext<CommandSourceStack> ctx, String raw) {
        ServerLevel level = ctx.getSource().getLevel();
        SquadManager mgr = SquadManager.get(level);
        var opt = mgr.getSquadByCallsign(raw);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown squad: " + raw));
            return 0;
        }
        SquadData squad = opt.get();
        StringBuilder sb = new StringBuilder("Squad '" + squad.getCallsign() + "' (" + squad.getDisplayCallsign() + ") id " + squad.getSquadId() + " leader " + squad.getLeaderId() + " members " + squad.getMemberCount() + " mode " + squad.getMode() + "\n");
        for (UUID id : squad.getMemberIds()) {
            Entity e = level.getEntity(id);
            if (e == null) sb.append("  ").append(id.toString().substring(0, 8)).append(" <unloaded>\n");
            else if (e instanceof LivingEntity le) sb.append("  ").append(id.toString().substring(0, 8)).append(" ").append(e.getType().toString()).append(" pos ").append(e.blockPosition().toShortString()).append(" hp ").append(String.format("%.0f/%.0f", le.getHealth(), le.getMaxHealth())).append(le.isAlive() ? "" : " DEAD").append("\n");
            else sb.append("  ").append(id.toString().substring(0, 8)).append(" ").append(e.blockPosition().toShortString()).append("\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int squadDisband(CommandContext<CommandSourceStack> ctx, String raw) {
        SquadManager mgr = SquadManager.get(ctx.getSource().getLevel());
        if (!mgr.disbandByCallsign(raw)) {
            ctx.getSource().sendFailure(Component.literal("Unknown squad: " + raw));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Disbanded squad '" + raw.toLowerCase(java.util.Locale.ROOT) + "'"), false);
        return 1;
    }

    private static int squadOrder(CommandContext<CommandSourceStack> ctx, String raw, PingType type, Vec3 pos) {
        ServerLevel level = ctx.getSource().getLevel();
        SquadManager mgr = SquadManager.get(level);
        var opt = mgr.getSquadByCallsign(raw);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown squad: " + raw));
            return 0;
        }
        SquadData squad = opt.get();
        int count = 0;
        for (UUID id : squad.getMemberIds()) {
            Entity e = level.getEntity(id);
            if (e instanceof SoldierEntity soldier && soldier.isAlive() && !soldier.isRemoved()) {
                soldier.receivePing(type, pos);
                count++;
            }
        }
        int finalCount = count;
        Vec3 p = pos;
        ctx.getSource().sendSuccess(() -> Component.literal("Ordered squad '" + squad.getCallsign() + "' " + type.name() + " -> " + p + " (" + finalCount + " soldiers)"), false);
        return finalCount > 0 ? 1 : 0;
    }

    private static int squadOrderHold(CommandContext<CommandSourceStack> ctx, String raw, Vec3 pos) {
        ServerLevel level = ctx.getSource().getLevel();
        SquadManager mgr = SquadManager.get(level);
        var opt = mgr.getSquadByCallsign(raw);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown squad: " + raw));
            return 0;
        }
        SquadData squad = opt.get();
        int count = 0;
        for (UUID id : squad.getMemberIds()) {
            Entity e = level.getEntity(id);
            if (e instanceof SoldierEntity soldier && soldier.isAlive() && !soldier.isRemoved()) {
                Vec3 p = pos != null ? pos : new Vec3(soldier.getX(), soldier.getY(), soldier.getZ());
                soldier.receivePing(PingType.HOLD, p);
                if (pos != null) soldier.setHoldPosition(net.minecraft.core.BlockPos.containing(pos));
                count++;
            }
        }
        int finalCount = count;
        ctx.getSource().sendSuccess(() -> Component.literal("Ordered squad '" + squad.getCallsign() + "' HOLD" + (pos != null ? " -> " + pos : "") + " (" + finalCount + ")"), false);
        return finalCount > 0 ? 1 : 0;
    }

    private static int squadOrderFollow(CommandContext<CommandSourceStack> ctx, String raw, Player target) {
        ServerLevel level = ctx.getSource().getLevel();
        SquadManager mgr = SquadManager.get(level);
        var opt = mgr.getSquadByCallsign(raw);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Unknown squad: " + raw));
            return 0;
        }
        SquadData squad = opt.get();
        int count = 0;
        Vec3 dummy = target != null ? target.position() : new Vec3(0, 0, 0);
        for (UUID id : squad.getMemberIds()) {
            Entity e = level.getEntity(id);
            if (e instanceof SoldierEntity soldier && soldier.isAlive() && !soldier.isRemoved()) {
                soldier.receivePing(PingType.FOLLOW, dummy);
                count++;
            }
        }
        int finalCount = count;
        ctx.getSource().sendSuccess(() -> Component.literal("Ordered squad '" + squad.getCallsign() + "' FOLLOW" + (target != null ? " -> " + target.getName().getString() : "") + " (" + finalCount + ")"), false);
        return finalCount > 0 ? 1 : 0;
    }

    private static int forceGrenadeThrow(CommandContext<CommandSourceStack> context) {
        Entity soldierEntity;
        Entity targetEntity;
        try {
            soldierEntity = EntityArgument.getEntity(context, "soldier");
            targetEntity = EntityArgument.getEntity(context, "target");
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal("Could not resolve grenade command entities: "
                + exception.getMessage()));
            return 0;
        }
        if (!(soldierEntity instanceof SoldierEntity soldier)) {
            context.getSource().sendFailure(Component.literal("Soldier argument must be a Steve's Army soldier"));
            return 0;
        }
        if (!(targetEntity instanceof LivingEntity target)) {
            context.getSource().sendFailure(Component.literal("Target argument must be a living entity"));
            return 0;
        }

        GrenadeTacticalController.ForceThrowResult result =
            soldier.getGrenadeTacticalController().forceThrow(target);
        Component message = Component.literal("[Grenade] " + result.message());
        if (result.success()) {
            context.getSource().sendSuccess(() -> message, true);
            return 1;
        }
        context.getSource().sendFailure(message);
        return 0;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createOwnedSpawnBranch(
        String name,
        EntityType<? extends SoldierEntity> entityType
    ) {
        return Commands.literal(name)
            .then(Commands.argument("owner", EntityArgument.player())
                .then(Commands.literal("squad")
                    .then(Commands.argument("callsign", StringArgumentType.word())
                        .then(createOwnedSquadSpawnArguments(entityType))))
                .then(createSpawnArguments(entityType, true)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createTeamGarrisonSpawnArgs() {
        Function<CommandContext<CommandSourceStack>, Integer> execute = context ->
            spawnTeamGarrison(context, StringArgumentType.getString(context, "team"));
        var position = Commands.argument("position", Vec3Argument.vec3())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg())
            .executes(execute::apply);
        var pitch = Commands.argument("pitch", FloatArgumentType.floatArg())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        yaw.then(pitch);
        position.then(yaw);
        return position;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createTeamGarrisonSquadSpawnArgs() {
        Function<CommandContext<CommandSourceStack>, Integer> execute = context ->
            spawnTeamGarrisonWithCallsign(context, StringArgumentType.getString(context, "team"), StringArgumentType.getString(context, "callsign"));
        var position = Commands.argument("position", Vec3Argument.vec3())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg())
            .executes(execute::apply);
        var pitch = Commands.argument("pitch", FloatArgumentType.floatArg())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        yaw.then(pitch);
        position.then(yaw);
        return position;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createEnemySquadSpawnArgs() {
        Function<CommandContext<CommandSourceStack>, Integer> execute = context ->
            spawnEnemyWithCallsign(context, StringArgumentType.getString(context, "callsign"));
        var position = Commands.argument("position", Vec3Argument.vec3())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg())
            .executes(execute::apply);
        var pitch = Commands.argument("pitch", FloatArgumentType.floatArg())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        yaw.then(pitch);
        position.then(yaw);
        return position;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createSpawnArguments(
        EntityType<? extends SoldierEntity> entityType,
        boolean ownerRequired
    ) {
        Function<CommandContext<CommandSourceStack>, Integer> execute = context ->
            spawnEntity(context, entityType, ownerRequired);

        var position = Commands.argument("position", Vec3Argument.vec3())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg())
            .executes(execute::apply);
        var pitch = Commands.argument("pitch", FloatArgumentType.floatArg())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        yaw.then(pitch);
        position.then(yaw);
        return position;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createOwnedSquadSpawnArguments(
        EntityType<? extends SoldierEntity> entityType
    ) {
        Function<CommandContext<CommandSourceStack>, Integer> execute = context ->
            spawnEntityWithCallsign(context, entityType, true, StringArgumentType.getString(context, "callsign"));
        var position = Commands.argument("position", Vec3Argument.vec3())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        var yaw = Commands.argument("yaw", FloatArgumentType.floatArg())
            .executes(execute::apply);
        var pitch = Commands.argument("pitch", FloatArgumentType.floatArg())
            .executes(execute::apply)
            .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                .executes(execute::apply));
        yaw.then(pitch);
        position.then(yaw);
        return position;
    }

    private static int saveLoadout(CommandContext<CommandSourceStack> context) {
        Entity target;
        try {
            target = EntityArgument.getEntity(context, "rifleman");
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Could not resolve rifleman: " + e.getMessage()));
            return 0;
        }

        if (!(target instanceof SoldierEntity soldier)) {
            context.getSource().sendFailure(Component.literal("Target must be a Steve's Army rifleman, machine gunner, or enemy soldier"));
            return 0;
        }

        if (!soldier.isAlive() || soldier.isRemoved()) {
            context.getSource().sendFailure(Component.literal("Target rifleman is not active"));
            return 0;
        }

        String snbt = SoldierSpawner.saveLoadout(soldier).toString();
        MutableComponent copyButton = Component.literal("[Copy loadout NBT]")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, snbt)));

        context.getSource().sendSuccess(() -> Component.literal(
            "Loadout exported from rifleman " + soldier.getUUID() + ". Click "
        ).append(copyButton), false);
        context.getSource().sendSuccess(() -> Component.literal(
            "The copied value is portable and contains the soldier inventory item NBT only."
        ), false);
        return 1;
    }

    private static int spawnEntity(
        CommandContext<CommandSourceStack> context,
        EntityType<? extends SoldierEntity> entityType,
        boolean ownerRequired
    ) {
        CommandSourceStack source = context.getSource();
        Player owner;
        try {
            owner = ownerRequired
                ? EntityArgument.getPlayer(context, "owner")
                : null;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not resolve owner: " + e.getMessage()));
            return 0;
        }

        float yaw = optionalFloat(context, "yaw", 0.0F);
        float pitch = optionalFloat(context, "pitch", 0.0F);
        CompoundTag loadout = optionalLoadout(context);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        SoldierSpawner.SpawnResult result = SoldierSpawner.spawn(
            source.getLevel(), entityType, owner, position, yaw, pitch, loadout);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        SoldierEntity soldier = result.soldier();
        String loadoutDescription = loadout == null ? "empty loadout" : "provided loadout";
        String entityName = soldier instanceof MachineGunnerEntity
            ? "machine gunner"
            : soldier instanceof SupportEntity ? "support"
            : soldier instanceof EnemySoldierEntity ? "enemy soldier"
            : soldier instanceof TeamGarrisonEntity ? "team garrison"
            : soldier instanceof GarrisonEntity ? "garrison" : "rifleman";
        String ownerDescription = owner == null ? "without an owner" : "for " + owner.getName().getString();
        source.sendSuccess(() -> Component.literal(
            "Spawned " + entityName + " " + soldier.getUUID() + " " + ownerDescription
                + " with " + loadoutDescription + "."), false);
        return 1;
    }

    private static int spawnEntityWithCallsign(
        CommandContext<CommandSourceStack> context,
        EntityType<? extends SoldierEntity> entityType,
        boolean ownerRequired,
        String callsign
    ) {
        CommandSourceStack source = context.getSource();
        Player owner;
        try {
            owner = ownerRequired ? EntityArgument.getPlayer(context, "owner") : null;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not resolve owner: " + e.getMessage()));
            return 0;
        }
        if (callsign.equals("-")) callsign = null;
        else {
            String n = callsign.toLowerCase(java.util.Locale.ROOT);
            if (!SquadData.CALLSIGN_PATTERN.matcher(n).matches()) {
                source.sendFailure(Component.literal("Invalid callsign: " + callsign));
                return 0;
            }
        }
        float yaw = optionalFloat(context, "yaw", 0.0F);
        float pitch = optionalFloat(context, "pitch", 0.0F);
        CompoundTag loadout = optionalLoadout(context);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        SoldierSpawner.SpawnResult result;
        if (callsign != null) result = SoldierSpawner.spawnWithCallsign(source.getLevel(), entityType, owner, callsign, position, yaw, pitch, loadout);
        else result = SoldierSpawner.spawn(source.getLevel(), entityType, owner, position, yaw, pitch, loadout);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }
        SoldierEntity soldier = result.soldier();
        String entityName = soldier instanceof MachineGunnerEntity ? "machine gunner" : soldier instanceof SupportEntity ? "support" : soldier instanceof GarrisonEntity ? "garrison" : "rifleman";
        String extra = callsign != null ? " into squad '" + callsign.toLowerCase(java.util.Locale.ROOT) + "'" : "";
        String ownerDescription = owner == null ? "without an owner" : "for " + owner.getName().getString();
        source.sendSuccess(() -> Component.literal("Spawned " + entityName + " " + soldier.getUUID() + " " + ownerDescription + extra + "."), false);
        return 1;
    }

    private static int spawnTeamGarrison(CommandContext<CommandSourceStack> context, String teamName) {
        CommandSourceStack source = context.getSource();
        float yaw = optionalFloat(context, "yaw", 0.0F);
        float pitch = optionalFloat(context, "pitch", 0.0F);
        CompoundTag loadout = optionalLoadout(context);
        Vec3 position = Vec3Argument.getVec3(context, "position");

        if (loadout != null) {
            var validation = SoldierSpawner.validateLoadout(loadout);
            if (validation.isPresent()) {
                source.sendFailure(Component.literal(validation.get()));
                return 0;
            }
        }

        TeamGarrisonEntity garrison = ModEntities.TEAM_GARRISON.get().create(source.getLevel());
        if (garrison == null) {
            source.sendFailure(Component.literal("Failed to create team garrison"));
            return 0;
        }
        garrison.moveTo(position.x, position.y, position.z, yaw, pitch);
        garrison.setTeamName(teamName);
        if (loadout != null) {
            SoldierInventory inventory = garrison.getSoldierInventory();
            inventory.load(loadout);
            inventory.syncArmorToEntity(garrison);
        }

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(source.getLevel(), garrison, null, false);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "Spawned team garrison " + garrison.getUUID() + " for team " + teamName + "."), false);
        return 1;
    }

    private static int spawnTeamGarrisonWithCallsign(CommandContext<CommandSourceStack> context, String teamName, String callsign) {
        CommandSourceStack source = context.getSource();
        float yaw = optionalFloat(context, "yaw", 0.0F);
        float pitch = optionalFloat(context, "pitch", 0.0F);
        CompoundTag loadout = optionalLoadout(context);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        if (loadout != null) {
            var validation = SoldierSpawner.validateLoadout(loadout);
            if (validation.isPresent()) { source.sendFailure(Component.literal(validation.get())); return 0; }
        }
        if (!callsign.equals("-")) {
            String n = callsign.toLowerCase(java.util.Locale.ROOT);
            if (!SquadData.CALLSIGN_PATTERN.matcher(n).matches()) { source.sendFailure(Component.literal("Invalid callsign: " + callsign)); return 0; }
        }
        TeamGarrisonEntity garrison = ModEntities.TEAM_GARRISON.get().create(source.getLevel());
        if (garrison == null) { source.sendFailure(Component.literal("Failed to create team garrison")); return 0; }
        garrison.moveTo(position.x, position.y, position.z, yaw, pitch);
        garrison.setTeamName(teamName);
        if (loadout != null) { SoldierInventory inv = garrison.getSoldierInventory(); inv.load(loadout); inv.syncArmorToEntity(garrison); }
        SoldierSpawner.SpawnResult result;
        if (callsign.equals("-")) result = SoldierSpawner.finishSpawn(source.getLevel(), garrison, null, false);
        else result = SoldierSpawner.finishSpawnWithCallsign(source.getLevel(), garrison, null, false, callsign, SoldierSpawner.teamLeaderId(teamName));
        if (!result.success()) { source.sendFailure(Component.literal(result.message())); return 0; }
        String extra = callsign.equals("-") ? "" : " into squad '" + callsign.toLowerCase(java.util.Locale.ROOT) + "'";
        source.sendSuccess(() -> Component.literal("Spawned team garrison " + garrison.getUUID() + " for team " + teamName + extra + "."), false);
        return 1;
    }

    private static int spawnEnemyWithCallsign(CommandContext<CommandSourceStack> context, String callsign) {
        CommandSourceStack source = context.getSource();
        String n = callsign.toLowerCase(java.util.Locale.ROOT);
        if (!SquadData.CALLSIGN_PATTERN.matcher(n).matches()) { source.sendFailure(Component.literal("Invalid callsign: " + callsign)); return 0; }
        float yaw = optionalFloat(context, "yaw", 0.0F);
        float pitch = optionalFloat(context, "pitch", 0.0F);
        CompoundTag loadout = optionalLoadout(context);
        Vec3 position = Vec3Argument.getVec3(context, "position");
        SoldierSpawner.SpawnResult result = SoldierSpawner.spawnWithCallsign(source.getLevel(), ModEntities.ENEMY_SOLDIER.get(), null, callsign, position, yaw, pitch, loadout);
        if (!result.success()) { source.sendFailure(Component.literal(result.message())); return 0; }
        source.sendSuccess(() -> Component.literal("Spawned enemy soldier " + result.soldier().getUUID() + " into squad '" + n + "'."), false);
        return 1;
    }

    private static float optionalFloat(CommandContext<CommandSourceStack> context, String name, float fallback) {
        try {
            return FloatArgumentType.getFloat(context, name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static CompoundTag optionalLoadout(CommandContext<CommandSourceStack> context) {
        try {
            return CompoundTagArgument.getCompoundTag(context, "loadout");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int enableAllDebug(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.setDebugMode(CombatDebugRenderer.DEBUG_MODE_MINIMAL);
        CoverDebugManager.setShowSoldierCover(true);
        CoverDebugManager.setShowPeekCandidates(true);
        CoverDebugManager.setVisualizationEnabled(true);
        CoverTacticalGoal.setDebugLogging(true);

        context.getSource().sendSuccess(() -> Component.literal(
            "=== Steve's Army Debug: ALL ON (redirecting to /stevesarmy_debug all) ===" +
            "\nUse /stevesarmy_debug status to check current toggles"
        ), true);
        return 1;
    }
}

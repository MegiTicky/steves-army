package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.stevesarmy.client.CombatDebugRenderer;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

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
                    "  /steves_army loadout save <soldier> - Copy a soldier inventory loadout\n" +
                    "  /steves_army spawn <owner> <x> <y> <z> [yaw] [pitch] [loadout_nbt]"
                ), false);
                return 1;
            })
            .then(Commands.literal("debug")
                .executes(StevesArmyCommand::enableAllDebug)
            )
            .then(Commands.literal("loadout")
                .then(Commands.literal("save")
                    .then(Commands.argument("soldier", EntityArgument.entity())
                        .executes(StevesArmyCommand::saveLoadout)
                    )
                )
            )
            .then(Commands.literal("spawn")
                .then(Commands.argument("owner", EntityArgument.player())
                    .then(Commands.argument("position", Vec3Argument.vec3())
                        .executes(ctx -> spawnSoldier(ctx, 0.0F, 0.0F, null))
                        .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                            .executes(ctx -> spawnSoldier(ctx, 0.0F, 0.0F,
                                CompoundTagArgument.getCompoundTag(ctx, "loadout")))
                        )
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                            .executes(ctx -> spawnSoldier(ctx,
                                FloatArgumentType.getFloat(ctx, "yaw"), 0.0F, null))
                            .then(Commands.argument("pitch", FloatArgumentType.floatArg())
                                .executes(ctx -> spawnSoldier(ctx,
                                    FloatArgumentType.getFloat(ctx, "yaw"),
                                    FloatArgumentType.getFloat(ctx, "pitch"), null))
                                .then(Commands.argument("loadout", CompoundTagArgument.compoundTag())
                                    .executes(ctx -> spawnSoldier(ctx,
                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                        FloatArgumentType.getFloat(ctx, "pitch"),
                                        CompoundTagArgument.getCompoundTag(ctx, "loadout")))
                                )
                            )
                        )
                    )
                )
            );
    }

    private static int saveLoadout(CommandContext<CommandSourceStack> context) {
        Entity target;
        try {
            target = EntityArgument.getEntity(context, "soldier");
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Could not resolve soldier: " + e.getMessage()));
            return 0;
        }

        if (!(target instanceof SoldierEntity soldier)) {
            context.getSource().sendFailure(Component.literal("Target must be a Steve's Army soldier"));
            return 0;
        }

        if (!soldier.isAlive() || soldier.isRemoved()) {
            context.getSource().sendFailure(Component.literal("Target soldier is not active"));
            return 0;
        }

        String snbt = SoldierSpawner.saveLoadout(soldier).toString();
        MutableComponent copyButton = Component.literal("[Copy loadout NBT]")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, snbt)));

        context.getSource().sendSuccess(() -> Component.literal(
            "Loadout exported from soldier " + soldier.getUUID() + ". Click "
        ).append(copyButton), false);
        context.getSource().sendSuccess(() -> Component.literal(
            "The copied value is portable and contains the soldier inventory item NBT only."
        ), false);
        return 1;
    }

    private static int spawnSoldier(
        CommandContext<CommandSourceStack> context,
        float yaw,
        float pitch,
        CompoundTag loadout
    ) {
        CommandSourceStack source = context.getSource();
        Player owner;
        try {
            owner = EntityArgument.getPlayer(context, "owner");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not resolve owner: " + e.getMessage()));
            return 0;
        }

        Vec3 position = Vec3Argument.getVec3(context, "position");
        SoldierSpawner.SpawnResult result = SoldierSpawner.spawnOwned(
            source.getLevel(), owner, position, yaw, pitch, loadout);
        if (!result.success()) {
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        SoldierEntity soldier = result.soldier();
        String loadoutDescription = loadout == null ? "empty loadout" : "provided loadout";
        source.sendSuccess(() -> Component.literal(
            "Spawned soldier " + soldier.getUUID() + " for " + owner.getName().getString()
                + " with " + loadoutDescription + ", fire team " + soldier.getFireTeam()
                + ", and squad " + (soldier.getSquadId() == null ? "none" : soldier.getSquadId()) + "."
        ), false);
        return 1;
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

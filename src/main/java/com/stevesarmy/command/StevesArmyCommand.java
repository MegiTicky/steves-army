package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.stevesarmy.client.CombatDebugRenderer;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.GrenadeTacticalController;
import com.stevesarmy.registry.ModEntities;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

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
                    "  /stevesarmy spawn rifleman <owner> <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn machine_gunner <owner> <position> [yaw] [pitch] [loadout_nbt]\n" +
                    "  /stevesarmy spawn enemy <position> [yaw] [pitch] [loadout_nbt]"
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
                .then(Commands.literal("enemy")
                    .then(createSpawnArguments(ModEntities.ENEMY_SOLDIER.get(), false)))
            );
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
            new GrenadeTacticalController(soldier).forceThrow(target);
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
                .then(createSpawnArguments(entityType, true)));
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
            : soldier instanceof EnemySoldierEntity ? "enemy soldier" : "rifleman";
        String ownerDescription = owner == null ? "without an owner" : "for " + owner.getName().getString();
        source.sendSuccess(() -> Component.literal(
            "Spawned " + entityName + " " + soldier.getUUID() + " " + ownerDescription
                + " with " + loadoutDescription + "."), false);
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

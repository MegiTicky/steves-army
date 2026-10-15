package com.stevesarmy.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class SoldierTraceCommand {
    private static final double LOOK_RANGE = 64.0D;

    private SoldierTraceCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("record")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("start")
                .executes(context -> start(context, 60, 5))
                .then(Commands.argument("historySeconds", IntegerArgumentType.integer(10, 60))
                    .executes(context -> start(context,
                        IntegerArgumentType.getInteger(context, "historySeconds"), 5))
                    .then(Commands.argument("sampleTicks", IntegerArgumentType.integer(1, 20))
                        .executes(context -> start(context,
                            IntegerArgumentType.getInteger(context, "historySeconds"),
                            IntegerArgumentType.getInteger(context, "sampleTicks"))))))
            .then(Commands.literal("status")
                .executes(context -> reply(context, SoldierFlightRecorder.status())))
            .then(Commands.literal("stop")
                .executes(context -> reply(context, SoldierFlightRecorder.stop())))
            .then(Commands.literal("export")
                .executes(SoldierTraceCommand::exportLookedAt)
                .then(Commands.literal("entity")
                    .then(Commands.argument("entity", EntityArgument.entity())
                        .executes(SoldierTraceCommand::exportEntity)))
                .then(Commands.literal("uuid")
                    .then(Commands.argument("uuid", UuidArgument.uuid())
                        .executes(SoldierTraceCommand::exportUuid))));
    }

    private static int start(CommandContext<CommandSourceStack> context, int historySeconds, int sampleTicks) {
        return reply(context, SoldierFlightRecorder.start(historySeconds, sampleTicks));
    }

    private static int exportLookedAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(LOOK_RANGE));
        BlockHitResult blockHit = player.level().clip(new net.minecraft.world.level.ClipContext(
            start, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        Vec3 clippedEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
        AABB search = new AABB(start, clippedEnd).inflate(1.0D);

        Entity selected = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (Entity candidate : player.level().getEntities(player, search,
            entity -> entity instanceof SoldierEntity && entity.isAlive())) {
            var hit = candidate.getBoundingBox().inflate(0.3D).clip(start, clippedEnd);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = start.distanceToSqr(hit.get());
            if (distance < closestDistance) {
                selected = candidate;
                closestDistance = distance;
            }
        }
        if (selected == null) {
            context.getSource().sendFailure(Component.literal("No soldier is in your line of sight within 64 blocks."));
            return 0;
        }
        return export(context, selected.getUUID());
    }

    private static int exportEntity(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        if (!(entity instanceof SoldierEntity)) {
            context.getSource().sendFailure(Component.literal("The selected entity is not a soldier."));
            return 0;
        }
        return export(context, entity.getUUID());
    }

    private static int exportUuid(CommandContext<CommandSourceStack> context) {
        return export(context, UuidArgument.getUuid(context, "uuid"));
    }

    private static int export(CommandContext<CommandSourceStack> context, UUID selectedId) {
        CommandSourceStack source = context.getSource();
        SoldierFlightRecorder.export(source, selectedId);
        return 1;
    }

    private static int reply(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}

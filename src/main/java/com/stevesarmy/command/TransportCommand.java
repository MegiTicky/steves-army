package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class TransportCommand {

    private static boolean reflectionChecked;
    private static Class<?> createSeatBlockClass;
    private static Class<?> createSeatEntityClass;
    private static Method createSeatSitDown;
    private static Method getShipObjectManagingPos;
    private static Method getShipId;
    private static Method toWorldCoordinates;
    private static Method getShipObjectManagingPosDouble;
    private static Class<?> shipClass;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("transport")
                .then(Commands.literal("inspect")
                    .then(Commands.argument("shipBlockPos", BlockPosArgument.blockPos())
                        .executes(ctx -> inspectShip(ctx, BlockPosArgument.getBlockPos(ctx, "shipBlockPos")))))
                .then(Commands.literal("seat")
                    .then(Commands.argument("soldier", EntityArgument.entity())
                        .then(Commands.argument("seatBlockPos", BlockPosArgument.blockPos())
                            .executes(TransportCommand::seatSoldier))))
                .then(Commands.literal("status")
                    .then(Commands.argument("soldier", EntityArgument.entity())
                        .executes(TransportCommand::showStatus)))
                .then(Commands.literal("release")
                    .then(Commands.argument("soldier", EntityArgument.entity())
                        .executes(TransportCommand::releaseSoldier)))
            )
        );
    }

    private static int inspectShip(CommandContext<CommandSourceStack> ctx, BlockPos origin) {
        CommandSourceStack source = ctx.getSource();
        Level level = source.getLevel();

        if (!(level instanceof ServerLevel)) {
            source.sendFailure(Component.literal("Must be executed on server level"));
            return 0;
        }

        if (!ensureReflection(level)) {
            source.sendFailure(Component.literal("Transport reflection not available"));
            return 0;
        }

        try {
            // Resolve the ship from the given block position
            Object ship = getShipObjectManagingPos.invoke(null, level, origin);
            if (ship == null) {
                source.sendFailure(Component.literal("No ship found at " + origin));
                return 0;
            }
            long shipId = ((Number) getShipId.invoke(ship)).longValue();

            source.sendSuccess(() -> Component.literal(
                "=== Ship " + shipId + " Seat Inspection ==="), false);
            source.sendSuccess(() -> Component.literal(
                "  Origin: " + origin), false);

            // Scan blocks around the origin for seat blocks
            List<SeatInfo> seats = new ArrayList<>();
            for (int radius = 0; radius <= 10; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        for (int y = -2; y <= 2; y++) {
                            BlockPos candidate = origin.offset(x, y, z);
                            BlockState blockState = level.getBlockState(candidate);
                            if (!createSeatBlockClass.isInstance(blockState.getBlock())) continue;
                            Object seatShip = getShipObjectManagingPos.invoke(null, level, candidate);
                            if (seatShip == null || ((Number) getShipId.invoke(seatShip)).longValue() != shipId) continue;
                            seats.add(new SeatInfo(candidate, false));
                        }
                    }
                }
            }

            // Also check SeatEntity instances (owner seat, etc.)
            for (Entity entity : ((ServerLevel) level).getAllEntities()) {
                if (createSeatEntityClass.isInstance(entity)) {
                    Object seatShip = getShipObjectManagingPos.invoke(null, level, entity.blockPosition());
                    if (seatShip != null && ((Number) getShipId.invoke(seatShip)).longValue() == shipId) {
                        BlockPos bp = entity.blockPosition();
                        SeatInfo existing = null;
                        for (SeatInfo si : seats) {
                            if (si.pos.equals(bp)) { existing = si; break; }
                        }
                        if (existing != null) {
                            existing.hasEntity = true;
                            existing.passengerCount = entity.getPassengers().size();
                        } else {
                            SeatInfo info = new SeatInfo(bp, true);
                            info.passengerCount = entity.getPassengers().size();
                            seats.add(info);
                        }
                    }
                }
            }

            // Sort by position
            seats.sort((a, b) -> {
                int cmp = Integer.compare(a.pos.getX(), b.pos.getX());
                if (cmp != 0) return cmp;
                cmp = Integer.compare(a.pos.getY(), b.pos.getY());
                if (cmp != 0) return cmp;
                return Integer.compare(a.pos.getZ(), b.pos.getZ());
            });

            source.sendSuccess(() -> Component.literal("  Total seats: " + seats.size()), false);
            int index = 0;
            for (SeatInfo seat : seats) {
                index++;
                final int idx = index;
                final SeatInfo s = seat;
                source.sendSuccess(() -> Component.literal(
                    "  [" + idx + "] block=" + s.pos
                        + " entity=" + (s.hasEntity ? "YES" : "NO")
                        + " passengers=" + s.passengerCount), false);
                try {
                    org.joml.Vector3dc worldPos = (org.joml.Vector3dc) toWorldCoordinates.invoke(null, ship,
                        s.pos.getX() + 0.5, s.pos.getY(), s.pos.getZ() + 0.5);
                    source.sendSuccess(() -> Component.literal(
                        "    worldPos=" + String.format("(%.2f, %.2f, %.2f)", worldPos.x(), worldPos.y(), worldPos.z())), false);
                } catch (ReflectiveOperationException ignored) {}
            }

        } catch (ReflectiveOperationException e) {
            source.sendFailure(Component.literal("Reflection error: " + e.getMessage()));
        }

        return 1;
    }

    private static int seatSoldier(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Level level = source.getLevel();

        if (!(level instanceof ServerLevel)) {
            source.sendFailure(Component.literal("Must be executed on server level"));
            return 0;
        }

        if (!ensureReflection(level)) {
            source.sendFailure(Component.literal("Transport reflection not available"));
            return 0;
        }

        try {
            Entity target = EntityArgument.getEntity(ctx, "soldier");
            if (!(target instanceof SoldierEntity soldier)) {
                source.sendFailure(Component.literal("Target must be a SoldierEntity"));
                return 0;
            }
            BlockPos seatBlockPos = BlockPosArgument.getBlockPos(ctx, "seatBlockPos");

            // If soldier is already mounted, dismount first to avoid duplicates
            if (soldier.isPassenger()) {
                source.sendSuccess(() -> Component.literal(
                    "  Dismounting soldier " + soldier.getId() + " from current vehicle"), false);
                soldier.stopRiding();
            }

            // Verify the position has a seat block
            BlockState blockState = level.getBlockState(seatBlockPos);
            if (!createSeatBlockClass.isInstance(blockState.getBlock())) {
                source.sendFailure(Component.literal("No seat block at " + seatBlockPos));
                return 0;
            }

            // Stop AI movement
            soldier.getNavigation().stop();
            if (soldier.getCoverBehaviorManager() != null) {
                soldier.cancelCoverMovement();
            }
            soldier.setDeltaMovement(Vec3.ZERO);

            source.sendSuccess(() -> Component.literal(
                "Mounting soldier " + soldier.getId() + " to seat at " + seatBlockPos), false);
            source.sendSuccess(() -> Component.literal(
                "  Soldier pre-mount pos: " + formatPos(soldier.position())), false);

            VS2Compat.clearAuthorizedMount(soldier);
            boolean mounted = VS2Compat.seatSoldierDirect(soldier, level, seatBlockPos);

            if (mounted && soldier.isPassenger()) {
                source.sendSuccess(() -> Component.literal(
                    "  SUCCESS: soldier=" + soldier.getId()
                        + " vehicle=" + soldier.getVehicle().getId()
                        + " vehicleClass=" + soldier.getVehicle().getClass().getSimpleName()
                        + " soldierPos=" + formatPos(soldier.position())
                        + " vehiclePos=" + formatPos(soldier.getVehicle().position())), false);
            } else {
                source.sendFailure(Component.literal(
                    "  FAILED: passenger=" + soldier.isPassenger()
                        + " vehicle=" + (soldier.getVehicle() == null ? "null" : soldier.getVehicle().getId())));
            }

        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            StevesArmyMod.LOGGER.error("[TransportCmd] Error", e);
        }

        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        try {
            Entity target = EntityArgument.getEntity(ctx, "soldier");
            if (!(target instanceof SoldierEntity soldier)) {
                source.sendFailure(Component.literal("Target must be a SoldierEntity"));
                return 0;
            }

            Level level = soldier.level();
            ensureReflection(level);

            source.sendSuccess(() -> Component.literal(
                "=== Transport Status: Soldier " + soldier.getId() + " ==="), false);

            Vec3 pos = soldier.position();
            source.sendSuccess(() -> Component.literal(
                "  Position: " + formatPos(pos)), false);
            source.sendSuccess(() -> Component.literal(
                "  Is passenger: " + soldier.isPassenger()), false);

            if (soldier.isPassenger()) {
                Entity vehicle = soldier.getVehicle();
                source.sendSuccess(() -> Component.literal(
                    "  Vehicle id=" + vehicle.getId()
                        + " class=" + vehicle.getClass().getSimpleName()
                        + " pos=" + formatPos(vehicle.position())
                        + " blockPos=" + vehicle.blockPosition()), false);

                try {
                    Object ship = getShipObjectManagingPos.invoke(null, level, vehicle.blockPosition());
                    if (ship != null) {
                        long foundShipId = ((Number) getShipId.invoke(ship)).longValue();
                        source.sendSuccess(() -> Component.literal(
                            "  Vehicle ship id=" + foundShipId), false);

                        BlockPos seatBP = vehicle.blockPosition();
                        org.joml.Vector3dc worldPos = (org.joml.Vector3dc) toWorldCoordinates.invoke(null, ship,
                            seatBP.getX() + 0.5, seatBP.getY(), seatBP.getZ() + 0.5);
                        source.sendSuccess(() -> Component.literal(
                            "  Seat world pos: " + formatPos(new Vec3(worldPos.x(), worldPos.y(), worldPos.z()))), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                            "  Vehicle is NOT on any ship"), false);
                    }
                } catch (ReflectiveOperationException e) {
                    source.sendSuccess(() -> Component.literal(
                        "  Ship lookup error: " + e.getMessage()), false);
                }
            }

            source.sendSuccess(() -> Component.literal(
                "  Bounding box: " + soldier.getBoundingBox()), false);

        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
        }

        return 1;
    }

    private static int releaseSoldier(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        try {
            Entity target = EntityArgument.getEntity(ctx, "soldier");
            if (!(target instanceof SoldierEntity soldier)) {
                source.sendFailure(Component.literal("Target must be a SoldierEntity"));
                return 0;
            }

            Vec3 prePos = soldier.position();
            source.sendSuccess(() -> Component.literal(
                "Releasing soldier " + soldier.getId()
                    + " from transport. Pre-release pos=" + formatPos(prePos)), false);

            if (soldier.isPassenger()) {
                soldier.stopRiding();
            }

            VS2Compat.clearShipDraggingStateDirect(soldier);
            VS2Compat.clearTransportState(soldier);

            Vec3 postPos = soldier.position();
            source.sendSuccess(() -> Component.literal(
                "  Post-release pos=" + formatPos(postPos)
                    + " isPassenger=" + soldier.isPassenger()), false);

        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
        }

        return 1;
    }

    private static String formatPos(Vec3 pos) {
        return String.format("(%.2f, %.2f, %.2f)", pos.x, pos.y, pos.z);
    }

    private static boolean ensureReflection(Level level) {
        if (reflectionChecked) return true;
        reflectionChecked = true;
        try {
            Class<?> utils = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            getShipObjectManagingPos = utils.getMethod("getShipObjectManagingPos", Level.class, net.minecraft.core.Vec3i.class);
            getShipObjectManagingPosDouble = utils.getMethod("getShipObjectManagingPos", Level.class, double.class, double.class, double.class);
            createSeatBlockClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock");
            createSeatEntityClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
            createSeatSitDown = createSeatBlockClass.getMethod("sitDown", Level.class, BlockPos.class, Entity.class);
            shipClass = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            getShipId = shipClass.getMethod("getId");
            toWorldCoordinates = utils.getMethod("toWorldCoordinates", shipClass,
                double.class, double.class, double.class);
            return true;
        } catch (ReflectiveOperationException e) {
            StevesArmyMod.LOGGER.error("[TransportCmd] Reflection init failed", e);
            return false;
        }
    }

    private static class SeatInfo {
        final BlockPos pos;
        boolean hasEntity;
        int passengerCount;
        SeatInfo(BlockPos pos, boolean hasEntity) {
            this.pos = pos;
            this.hasEntity = hasEntity;
            this.passengerCount = 0;
        }
    }
}
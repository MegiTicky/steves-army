package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.combat.StationGunnerAI;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.SquadTargeting;
import com.stevesarmy.transport.CrewAssignment;
import com.stevesarmy.transport.TransportOrder;
import com.stevesarmy.vehicle.VehicleDriver;
import com.stevesarmy.vehicle.VehicleDriverRegistry;
import com.stevesarmy.vehicle.VehicleBoardingManager;
import com.stevesarmy.vehicle.VehicleWreckHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Vehicle wheel orders for scoped transport, crew, and SBW driver actions. */
public class TransportOrderMessage {
    private final TransportOrder order;
    private final double x;
    private final double y;
    private final double z;
    private final FireTeam scope;

    public TransportOrderMessage(TransportOrder order, Vec3 aimPosition, FireTeam scope) {
        this.order = order;
        this.x = aimPosition.x;
        this.y = aimPosition.y;
        this.z = aimPosition.z;
        this.scope = scope;
    }

    public TransportOrderMessage(FriendlyByteBuf buf) {
        this.order = buf.readEnum(TransportOrder.class);
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.scope = buf.readEnum(FireTeam.class);
    }

    public static void encode(TransportOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.order);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeEnum(msg.scope);
    }

    public TransportOrder getOrder() { return order; }
    public Vec3 getAimPosition() { return new Vec3(x, y, z); }
    public FireTeam getScope() { return scope; }

    public static void handle(TransportOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            ServerLevel level = sender.serverLevel();

            switch (msg.getOrder()) {
                case MOUNT -> handleMount(sender, level,
                    SquadTargeting.resolveOrderedSoldiers(level, sender, msg.getScope()), msg.getAimPosition());
                case DISMOUNT -> handleDismount(sender,
                    SquadTargeting.resolveOrderedSoldiers(level, sender, msg.getScope()));
                case MOUNT_CREW -> handleMountCrew(sender, level, msg.getAimPosition(), msg.getScope());
                case DISMOUNT_CREW -> handleDismountCrew(sender, level, msg.getScope());
                case DRIVE_GO_TO -> handleCrewDrivers(sender, level, msg.getScope(), PingType.GO_TO, msg.getAimPosition());
                case DRIVE_ATTACK -> handleCrewDrivers(sender, level, msg.getScope(), PingType.ATTACK, msg.getAimPosition());
                case DRIVE_FOLLOW -> handleCrewDrivers(sender, level, msg.getScope(), PingType.FOLLOW, msg.getAimPosition());
                case DRIVE_HOLD -> handleCrewDrivers(sender, level, msg.getScope(), PingType.HOLD, msg.getAimPosition());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleMountCrew(ServerPlayer sender, ServerLevel level, Vec3 aimPosition,
                                        FireTeam scope) {
        if (!VS2Compat.isEnabled() && !com.stevesarmy.compat.SbwCompat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }

        // Dedicated vehicle crew board separately from the ordinary infantry taxi order.
        Entity aimedVehicle = resolveVehicleForMount(sender, level, aimPosition);
        if (aimedVehicle != null && com.stevesarmy.compat.SbwCompat.isOperational(aimedVehicle)) {
            List<SoldierEntity> crew = crewInScope(
                level, sender, CrewAssignment.stationlessCrewNear(level, sender, aimPosition, 64), scope);
            if (crew.isEmpty()) {
                sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew"), true);
                return;
            }
            int boarding = CrewAssignment.mountCrewOnVehicle(level, aimedVehicle, crew);
            StevesArmyMod.LOGGER.info("[Transport] MOUNT_CREW by {}: vehicle={} type={} crew={} boarding={}",
                sender.getName().getString(), aimedVehicle.getId(),
                com.stevesarmy.compat.SbwCompat.getVehicleTypeName(aimedVehicle), crew.size(), boarding);
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.boarding", boarding, crew.size()), true);
            return;
        }

        Vec3 searchCenter = aimPosition;
        Object ship = resolveAimedShip(sender, level, aimPosition);
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        List<SoldierEntity> crew = crewInScope(
            level, sender, CrewAssignment.stationlessCrewNear(level, sender, searchCenter, 64), scope);
        if (crew.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew"), true);
            return;
        }

        int seated = CrewAssignment.mountCrewOnShip(level, ship, searchCenter, crew);
        StevesArmyMod.LOGGER.info("[Transport] MOUNT_CREW by {}: crew={} seated={}",
            sender.getName().getString(), crew.size(), seated);

        sender.displayClientMessage(
            Component.translatable("transport.steves_army.feedback.seated", seated, crew.size()), true);
    }

    private static List<SoldierEntity> crewInScope(ServerLevel level, ServerPlayer sender,
                                                   List<SoldierEntity> crew, FireTeam scope) {
        if (scope == FireTeam.ALL) {
            return crew;
        }
        List<java.util.UUID> teamIds = FireTeamAssignment.get(level, sender.getUUID()).getSoldiersInTeam(scope);
        return crew.stream().filter(soldier -> teamIds.contains(soldier.getUUID())).toList();
    }

    /** Vehicle-wheel dismount for crew; ordinary Dismount deliberately targets infantry only. */
    private static void handleDismountCrew(ServerPlayer sender, ServerLevel level, FireTeam scope) {
        List<SoldierEntity> crew = SquadTargeting.resolveOrderedVehicleCrew(level, sender, scope);
        int dismounted = 0;
        for (SoldierEntity soldier : crew) {
            VehicleBoardingManager.cancel(soldier);
            boolean hadDuty = StationGunnerAI.soldierHasStation(soldier);
            StationGunnerAI.deactivateForSoldier(soldier);
            boolean wasMounted = false;
            Entity vehicle = soldier.getVehicle();
            if (SbwCompat.isVehicle(vehicle)) {
                VehicleWreckHandler.dismount(soldier);
                wasMounted = true;
            } else if (VS2Compat.releaseTransport(soldier)) {
                wasMounted = true;
            }
            if (hadDuty || wasMounted) {
                dismounted++;
            }
        }

        if (dismounted == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_crew_mounted"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.crew_dismounted_count", dismounted), true);
        }
    }

    /**
     * Sends vehicle-page orders only to crew currently occupying the SBW driver
     * seat. The ordinary ping handler continues to exclude all vehicle crew.
     */
    private static void handleCrewDrivers(ServerPlayer sender, ServerLevel level, FireTeam scope,
                                          PingType order, Vec3 aimPosition) {
        if (!SbwCompat.hasVehicles()) {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.vehicle_control_requires_sbw"), true);
            return;
        }

        List<SoldierEntity> crew = SquadTargeting.resolveOrderedVehicleCrew(level, sender, scope);
        java.util.Set<java.util.UUID> commandedVehicles = new java.util.HashSet<>();
        for (SoldierEntity soldier : crew) {
            Entity vehicle = soldier.getVehicle();
            if (!SbwCompat.isVehicle(vehicle) || !com.stevesarmy.compat.SbwCompat.isOperational(vehicle)
                || !SbwVehicles.isDriver(vehicle, soldier)
                || !commandedVehicles.add(vehicle.getUUID())) {
                continue;
            }
            VehicleDriverRegistry.onBoarded(soldier, vehicle);
            VehicleDriver driver = VehicleDriverRegistry.get(vehicle);
            if (driver != null) {
                driver.clearAttack();
            }
            soldier.clearAttackTarget();
            if (order == PingType.ATTACK) {
                soldier.clearPingMoveTarget();
            }
            soldier.receivePing(order, aimPosition);
        }

        if (commandedVehicles.isEmpty()) {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.no_vehicle_driver_in_scope"), true);
        } else {
            if (order != PingType.FOLLOW && order != PingType.HOLD) {
                broadcastVehicleCommandMarker(sender, level, scope, order, aimPosition);
            }
            sender.displayClientMessage(Component.translatable(
                "transport.steves_army.feedback.vehicle_orders_issued", commandedVehicles.size()), true);
        }
    }

    private static void broadcastVehicleCommandMarker(ServerPlayer sender, ServerLevel level,
                                                       FireTeam scope, PingType order, Vec3 position) {
        int teamColor = 0xFFFFFFFF;
        if (sender.getTeam() != null) {
            Integer color = sender.getTeam().getColor().getColor();
            if (color != null) teamColor = (255 << 24) | color;
        }
        List<ServerPlayer> recipients = level.getServer().getPlayerList().getPlayers().stream()
            .filter(player -> sender.getTeam() == null || player.getTeam() == sender.getTeam())
            .toList();
        PingBroadcastMessage marker = new PingBroadcastMessage(order, position,
            sender.level().dimension().location().hashCode(), sender.getUUID(),
            sender.getGameProfile().getName(), teamColor, scope.ordinal());
        for (ServerPlayer recipient : recipients) NetworkHandler.sendTo(recipient, marker);
    }

    private static void handleDismount(ServerPlayer sender, List<SoldierEntity> soldiers) {
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        List<SoldierEntity> infantry = soldiers.stream()
            .filter(soldier -> soldier.getRole() != SoldierRole.VEHICLE_CREW)
            .toList();
        if (infantry.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.none_mounted"), true);
            return;
        }

        int dismounted = 0;
        if (SbwCompat.isLoaded() && com.stevesarmy.compat.SbwCompat.isEnabled()) {
            dismounted += VehicleWreckHandler.dismountAll(infantry);
        }

        for (SoldierEntity soldier : infantry) {
            VehicleBoardingManager.cancel(soldier);
            // releaseTransport exits at the hatch: the handle linked to the soldier's
            // seat first, then the nearest handle on their ship (no link required).
            if (VS2Compat.releaseTransport(soldier)) {
                dismounted++;
            }
        }
        StevesArmyMod.LOGGER.info("[Transport] DISMOUNT by {}: resolved={} dismounted={}",
            sender.getName().getString(), infantry.size(), dismounted);

        if (dismounted == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.none_mounted"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.dismounted", dismounted), true);
        }
    }

    /** /vs get-ship clips 10 blocks from the crosshair. */
    private static final double GET_SHIP_PICK_DISTANCE = 10.0;

    /**
     * Plain-entity vehicle under the crosshair (Superb Warfare): an ENTITY pick
     * hit on an operational SBW vehicle. Null for every other hit type — ships
     * keep going through {@link #resolveAimedShip}.
     */
    private static Entity resolveAimedVehicle(ServerPlayer sender) {
        if (!SbwCompat.isLoaded() || !com.stevesarmy.compat.SbwCompat.isEnabled()) {
            return null;
        }
        HitResult clip = sender.pick(GET_SHIP_PICK_DISTANCE, 1.0F, false);
        if (clip.getType() == HitResult.Type.ENTITY && clip instanceof EntityHitResult entityHit
            && SbwCompat.isVehicle(entityHit.getEntity()) && !SbwVehicles.isWrecked(entityHit.getEntity())) {
            return entityHit.getEntity();
        }
        return null;
    }

    /** Uses the same direct-hit, view-ray, and nearby-aim fallbacks for infantry and crew mounting. */
    private static Entity resolveVehicleForMount(ServerPlayer sender, ServerLevel level, Vec3 aimPosition) {
        if (!SbwCompat.hasVehicles()) {
            return null;
        }

        Entity vehicle = resolveAimedVehicle(sender);
        if (vehicle != null) {
            return vehicle;
        }

        Vec3 eye = sender.getEyePosition();
        Vec3 look = sender.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(64.0));
        AABB rayBox = sender.getBoundingBox().expandTowards(look.scale(64.0)).inflate(4.0);
        List<Entity> vehicles = level.getEntitiesOfClass(Entity.class, rayBox, SbwCompat::isVehicle);
        for (Entity candidate : vehicles) {
            if (candidate.getBoundingBox().inflate(1.5).clip(eye, end).isPresent()) {
                return candidate;
            }
        }

        List<Entity> nearAim = level.getEntitiesOfClass(Entity.class,
            new AABB(aimPosition, aimPosition).inflate(8.0), SbwCompat::isVehicle);
        return nearAim.isEmpty() ? null : nearAim.get(0);
    }

    /**
     * Ship resolution in /vs get-ship order (the crew assign stick's order): the
     * vanilla clip's hit block is the authoritative crosshair pick, so the ship
     * managing that exact block wins; the geometric resolver only runs when the
     * hit block maps to nothing — overlapping sub-ships can defeat a raw
     * block-center lookup. Callers keep their near-player last resort.
     */
    private static Object resolveAimedShip(ServerPlayer sender, ServerLevel level, Vec3 aimPosition) {
        HitResult clip = sender.pick(GET_SHIP_PICK_DISTANCE, 1.0F, false);
        if (clip.getType() == HitResult.Type.BLOCK && clip instanceof BlockHitResult blockHit) {
            Object ship = VS2Compat.getShipObjectAtBlockPos(level, blockHit.getBlockPos());
            if (ship != null) {
                StevesArmyMod.LOGGER.info("[Transport] ship resolved via vs get-ship at hit block {}: shipId={}",
                    blockHit.getBlockPos(), VS2Compat.getShipIdOf(ship));
                return ship;
            }
        }
        return VS2Compat.resolveShipAtWorldAnchor(level, aimPosition);
    }

    private static void handleMount(ServerPlayer sender, ServerLevel level,
                                    List<SoldierEntity> soldiers, Vec3 aimPosition) {
        if (soldiers.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_soldiers"), true);
            return;
        }
        List<SoldierEntity> eligible = soldiers.stream()
            .filter(s -> !s.isPassenger() && s.getRole() != SoldierRole.VEHICLE_CREW)
            .toList();
        if (eligible.isEmpty()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.all_mounted"), true);
            return;
        }

        Entity targetedVehicle = resolveVehicleForMount(sender, level, aimPosition);
        if (targetedVehicle != null && !SbwVehicles.isWrecked(targetedVehicle)) {
            int boarding = 0;
            for (SoldierEntity soldier : eligible) {
                if (VehicleBoardingManager.requestBoard(soldier, targetedVehicle)) {
                    boarding++;
                }
            }
            StevesArmyMod.LOGGER.info("[Transport] MOUNT by {}: eligible={} boarding={} (via SBW)", sender.getName().getString(), eligible.size(), boarding);
            if (boarding == 0) {
                sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
            } else {
                sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.boarding", boarding, eligible.size()), true);
            }
            return;
        }

        if (!VS2Compat.isEnabled()) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.vs2_unavailable"), true);
            return;
        }

        // Crosshair ship first (vs get-ship order); otherwise the ship the player
        // rides, else the nearest ship.
        Vec3 searchCenter = aimPosition;
        Object ship = resolveAimedShip(sender, level, aimPosition);
        if (ship == null) {
            searchCenter = sender.position();
            ship = VS2Compat.resolveMountShipNearPlayer(level, sender);
        }
        if (ship == null) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_vehicle"), true);
            return;
        }

        // Crew soldiers board shipyard seats exactly like riflemen; their crew AI
        // keeps ticking once seated (crewSeated), so they still man their station.
        // One shared 4-tier routine — handles → Create contraption seats → static
        // SeatBlocks → raw seat entities (tallyho) — so the wheel boards whatever
        // the crew assign stick boards: ships whose only seats are seat entities
        // have no SeatBlocks for a block scan to find.
        int seated = CrewAssignment.mountCrewOnShip(level, ship, searchCenter, eligible);

        StevesArmyMod.LOGGER.info("[Transport] MOUNT by {}: eligible={} seated={}",
            sender.getName().getString(), eligible.size(), seated);

        if (seated == 0) {
            sender.displayClientMessage(Component.translatable("transport.steves_army.feedback.no_free_seats"), true);
        } else {
            sender.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.seated", seated, eligible.size()), true);
        }
    }
}

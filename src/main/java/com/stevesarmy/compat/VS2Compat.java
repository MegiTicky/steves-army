package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Optional VS2 integration. Ships are transport, never terrain for soldier AI. */
public final class VS2Compat {
    private static final String VS2_MOD_ID = "valkyrienskies";
    private static final String VS_UTILS_CLASS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String CONTRAPTION_ENTITY_CLASS =
        "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final int[] ESCAPE_RADII = {2, 4, 6, 8, 12, 16, 24, 32};
    private static final int FULL_SEAT_RETRY_TICKS = 100;

    private static boolean initialized;
    private static boolean available;
    private static boolean reflectionFailureLogged;
    // VSGameUtilsKt methods: use MethodHandle to avoid ClientLevel class loading on dedicated server
    private static MethodHandle getShipMountedTo;
    private static MethodHandle getShipMountedToData;
    private static MethodHandle toWorldCoordinates;
    private static MethodHandle getShipsIntersecting;
    private static MethodHandle getShipObjectManagingPos;
    private static MethodHandle getShipObjectManagingPosDouble;
    // Non-VSGameUtilsKt reflection (safe to use ordinary Method)
    private static Method getMountPosInShip;
    private static Method createSeatSitDown;
    private static Class<?> createSeatBlockClass;
    private static Class<?> createSeatEntityClass;
    private static Class<?> contraptionEntityClass;
    private static Method getContraption;
    private static Method getSeats;
    private static Method getSeatMapping;
    private static Method addSittingPassenger;
    private static Method getShipId;
    private static Object createInteractiveUtil;
    private static Method getContraptionEntityForShip;
    private static final Map<UUID, SoldierState> states = new HashMap<>();
    private static final Map<UUID, UUID> authorizedMounts = new HashMap<>();
    private static final Map<UUID, BlockPos> authorizedStaticSeats = new HashMap<>();

    private VS2Compat() {}

    public static boolean isEnabled() {
        initialize();
        return available && StevesArmyConfig.VS2_COMPAT_ENABLED.get();
    }

    public static boolean prepareSoldierAi(SoldierEntity soldier) {
        if (soldier.level().isClientSide || !isEnabled()) {
            return false;
        }

        SoldierState state = states.computeIfAbsent(soldier.getUUID(), ignored -> new SoldierState());
        if (state.transportAnchorId != null) {
            updateTransport(soldier, state);
            return isTransported(soldier, state);
        }

        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }

        if (tryStartTransport(soldier, state)) {
            return true;
        }

        if (isInsideShip(soldier) || isBeingDraggedByShip(soldier)) {
            extractToSafeWorldPosition(soldier, state);
            return true;
        }

        rememberSafeWorldPosition(soldier, state);
        return false;
    }

    public static void onSoldierRemoved(SoldierEntity soldier) {
        states.remove(soldier.getUUID());
        authorizedMounts.remove(soldier.getUUID());
        authorizedStaticSeats.remove(soldier.getUUID());
    }

    /** Allows only the immediate Create seat mount initiated by tryContraptionSeat or tryStaticSeat. */
    public static boolean isAuthorizedMount(SoldierEntity soldier, Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        UUID vehicleId = authorizedMounts.get(soldier.getUUID());
        if (vehicleId != null && vehicleId.equals(vehicle.getUUID())) {
            return true;
        }
        // Static-seat authorization: the SeatEntity's block position must match the pending candidate.
        BlockPos pendingPos = authorizedStaticSeats.get(soldier.getUUID());
        if (pendingPos != null && createSeatEntityClass.isInstance(vehicle)) {
            return vehicle.blockPosition().equals(pendingPos);
        }
        // Allow mounts on any Create SeatEntity on the client side.
        // The server is authoritative for mounts; the client should apply server passenger packets.
        if (soldier.level().isClientSide && createSeatEntityClass.isInstance(vehicle)) {
            return true;
        }
        return false;
    }

    public static void clearAuthorizedMount(SoldierEntity soldier) {
        authorizedMounts.remove(soldier.getUUID());
        authorizedStaticSeats.remove(soldier.getUUID());
    }

    /**
     * Directly mounts a soldier to a static Create seat at the given shipyard block position.
     * Bypasses all FOLLOW/owner/transport logic. Returns true if the soldier is now a passenger.
     * After a successful mount, records the transport state so prepareSoldierAi does not
     * immediately dismount the soldier.
     */
    public static boolean seatSoldierDirect(SoldierEntity soldier, Level level, BlockPos seatBlockPos) {
        initialize();
        if (!available) return false;
        try {
            // Resolve the ship at the seat position for logging
            Object ship = null;
            Long shipId = null;
            org.joml.Vector3dc worldPos = null;
            try {
                ship = reflect(getShipObjectManagingPos, level, (net.minecraft.core.Vec3i) seatBlockPos);
                if (ship != null) {
                    shipId = ((Number) getShipId.invoke(ship)).longValue();
                    worldPos = (org.joml.Vector3dc) reflect(toWorldCoordinates, ship,
                        seatBlockPos.getX() + 0.5, seatBlockPos.getY(), seatBlockPos.getZ() + 0.5);
                }
            } catch (ReflectiveOperationException e) {
                StevesArmyMod.LOGGER.warn("[VS2] seatSoldierDirect: ship lookup failed for seat={}", seatBlockPos);
            }

            // Use SeatBlock.sitDown to create the seat at the shipyard block position.
            // This keeps the seat attached to the real seat block on the ship.
            // Mount the soldier after the seat entity is created.
            authorizedStaticSeats.put(soldier.getUUID(), seatBlockPos);
            try {
                createSeatSitDown.invoke(null, level, seatBlockPos, soldier);
            } finally {
                authorizedStaticSeats.remove(soldier.getUUID());
            }

            if (soldier.isPassenger() && soldier.getVehicle() != null) {
                Entity vehicle = soldier.getVehicle();
                SoldierState state = getOrCreateState(soldier);
                state.transportAnchorId = vehicle.getUUID();
                state.transportOwnerId = null;
                state.transportShipId = shipId;
                state.transportSeatPosition = seatBlockPos;
                state.seatRetryCooldownTicks = 0;

                Vec3 prePos = soldier.position();
                soldier.getVehicle().positionRider(soldier);
                Vec3 postPos = soldier.position();

                // Synchronize the shipyard seat entity and passenger to tracking clients.
                // Vanilla entity tracking does not cover shipyard entities, so we must
                // explicitly send the spawn and passenger packets.
                syncSeatEntityToClient(soldier, vehicle);

                StevesArmyMod.LOGGER.info("[VS2] seatSoldierDirect: mounted soldier={} vehicle={} vehicleClass={} seat={} shipId={} soldierPos={} -> {}",
                    soldier.getId(), vehicle.getId(), vehicle.getClass().getSimpleName(),
                    seatBlockPos, shipId,
                    formatVec3(prePos), formatVec3(postPos));

                if (worldPos != null) {
                    StevesArmyMod.LOGGER.info("[VS2] seatSoldierDirect: resolved seat worldPos=({}, {}, {})",
                        String.format("%.2f", worldPos.x()), String.format("%.2f", worldPos.y()), String.format("%.2f", worldPos.z()));
                }

                return true;
            }
            StevesArmyMod.LOGGER.warn("[VS2] seatSoldierDirect: mount did not persist soldier={} seat={}",
                soldier.getId(), seatBlockPos);
            return false;
        } catch (ReflectiveOperationException e) {
            StevesArmyMod.LOGGER.error("[VS2] seatSoldierDirect failed", e);
            return false;
        }
    }

    private static String formatVec3(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** Public wrapper to clear VS2 dragging state after a manual release. */
    public static void clearShipDraggingStateDirect(SoldierEntity soldier) {
        clearShipDraggingState(soldier);
    }

    public static boolean shouldRejectNavigation(Level level, BlockPos position) {
        if (!isEnabled()) {
            return false;
        }
        AABB bounds = new AABB(position).inflate(0.35D, 0.1D, 0.35D);
        return intersectsShip(level, bounds);
    }

    public static boolean shouldRejectNavigation(Entity entity) {
        return isEnabled() && isOnShip(entity);
    }

    public static boolean isOnShip(Entity entity) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return reflect(getShipMountedTo, entity) != null;
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    private static void syncSeatEntityToClient(SoldierEntity soldier, Entity seatEntity) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return;
        // Use the entity's own getAddEntityPacket() which calls
        // NetworkHooks.getEntitySpawningPacket() for Forge custom spawn data.
        net.minecraft.network.protocol.Packet<?> spawnPacket = seatEntity.getAddEntityPacket();
        // Send ClientboundSetPassengersPacket to set the soldier as passenger of the seat
        ClientboundSetPassengersPacket passengersPacket = new ClientboundSetPassengersPacket(seatEntity);
        int sentCount = 0;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(soldier) < 16384.0) { // 128 block radius
                player.connection.send(spawnPacket);
                player.connection.send(passengersPacket);
                sentCount++;
            }
        }
        StevesArmyMod.LOGGER.info("[VS2] syncSeatEntityToClient: sent seat spawn+passengers to {} players (seatId={} soldierId={})",
            sentCount, seatEntity.getId(), soldier.getId());
    }

    private static boolean isInsideShip(Entity entity) {
        return intersectsShip(entity.level(), entity.getBoundingBox());
    }

    private static boolean intersectsShip(Level level, AABB bounds) {
        try {
            Object ships = reflect(getShipsIntersecting, level, bounds);
            return ships instanceof Iterable<?> iterable && iterable.iterator().hasNext();
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    private static boolean isBeingDraggedByShip(Entity entity) {
        try {
            Method getter = entity.getClass().getMethod("getDraggingInformation");
            Object draggingInfo = getter.invoke(entity);
            return draggingInfo != null && (boolean) draggingInfo.getClass()
                .getMethod("isEntityBeingDraggedByAShip").invoke(draggingInfo);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void rememberSafeWorldPosition(SoldierEntity soldier, SoldierState state) {
        if (soldier.onGround() && isSafeWorldPosition(soldier, soldier.blockPosition())) {
            state.lastSafeWorldPosition = soldier.blockPosition();
        }
    }

    private static boolean tryStartTransport(SoldierEntity soldier, SoldierState state) {
        if (!StevesArmyConfig.VS2_AUTO_TRANSPORT.get() || soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }
        LivingEntity owner = soldier.getOwner();
        if (owner == null || !owner.isAlive() || owner.getVehicle() == null || !isOnShip(owner)) {
            state.seatRetryCooldownTicks = 0;
            return false;
        }
        if (state.seatRetryCooldownTicks > 0) {
            state.seatRetryCooldownTicks--;
            return false;
        }
        logSeatAttempt(soldier, state, owner);
        if (countTransportedFor(owner.getUUID()) >= StevesArmyConfig.VS2_MAX_TRANSPORTED_SOLDIERS.get()) {
            StevesArmyMod.LOGGER.info("[VS2] Seat attempt skipped: transport limit reached for owner={}", owner.getId());
            return false;
        }

        if (tryContraptionSeat(soldier, owner, state) || tryStaticSeat(soldier, owner, state)) {
            stopMovement(soldier);
            return true;
        }
        return false;
    }

    private static boolean tryContraptionSeat(SoldierEntity soldier, LivingEntity owner, SoldierState state) {
        try {
            Object ship = reflect(getShipMountedTo, owner);
            if (ship != null && createInteractiveUtil != null) {
                long shipId = ((Number) getShipId.invoke(ship)).longValue();
                Entity mappedVehicle = (Entity) getContraptionEntityForShip.invoke(createInteractiveUtil, shipId, false);
                if (mappedVehicle != null) {
                    StevesArmyMod.LOGGER.info("[VS2] Create Interactive mapped ship={} to contraption vehicle={}",
                        shipId, mappedVehicle.getId());
                    if (tryAssignContraptionSeat(soldier, owner, state, mappedVehicle)) {
                        return true;
                    }
                } else {
                    StevesArmyMod.LOGGER.info("[VS2] Create Interactive has no contraption for ship={}", shipId);
                }
            }
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }

        Entity vehicle = owner.getVehicle();
        while (vehicle != null) {
            if (contraptionEntityClass.isInstance(vehicle)) {
                try {
                    Object contraption = getContraption.invoke(vehicle);
                    if (contraption == null) {
                        StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} has no initialized contraption", vehicle.getId());
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    java.util.List<BlockPos> seats = (java.util.List<BlockPos>) getSeats.invoke(contraption);
                    @SuppressWarnings("unchecked")
                    Map<UUID, Integer> occupied = (Map<UUID, Integer>) getSeatMapping.invoke(contraption);
                    StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} seats={} occupied={}",
                        vehicle.getId(), seats.size(), occupied);
                    for (int index = 0; index < seats.size(); index++) {
                        if (occupied.containsValue(index)) {
                            continue;
                        }
                        authorizedMounts.put(soldier.getUUID(), vehicle.getUUID());
                        try {
                            StevesArmyMod.LOGGER.info("[VS2] Trying contraption seat={} soldier={} vehicle={}",
                                index, soldier.getId(), vehicle.getId());
                            addSittingPassenger.invoke(vehicle, soldier, index);
                            if (soldier.isPassenger() && soldier.getVehicle() == vehicle) {
                                state.transportAnchorId = vehicle.getUUID();
                                state.transportOwnerId = owner.getUUID();
                                state.transportShipId = getShipId.invoke(reflect(getShipMountedTo, owner)) instanceof Number id
                                    ? id.longValue() : null;
                                state.seatRetryCooldownTicks = 0;
                                StevesArmyMod.LOGGER.info("[VS2] Assigned soldier={} to Create contraption seat={} vehicle={}",
                                    soldier.getId(), index, vehicle.getId());
                                return true;
                            }
                            StevesArmyMod.LOGGER.warn("[VS2] Create seat mount failed soldier={} seat={} vehicle={} passenger={} actualVehicle={}",
                                soldier.getId(), index, vehicle.getId(), soldier.isPassenger(),
                                soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                        } finally {
                            authorizedMounts.remove(soldier.getUUID());
                        }
                    }
                    StevesArmyMod.LOGGER.info("[VS2] No empty contraption seats for soldier={} vehicle={}",
                        soldier.getId(), vehicle.getId());
                    return false;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                    return false;
                }
            }
            StevesArmyMod.LOGGER.info("[VS2] Seat chain vehicle={} class={}",
                vehicle.getId(), vehicle.getClass().getName());
            vehicle = vehicle.getVehicle();
        }
        StevesArmyMod.LOGGER.info("[VS2] No AbstractContraptionEntity found in owner vehicle chain owner={}", owner.getId());
        return false;
    }

    private static boolean tryAssignContraptionSeat(SoldierEntity soldier, LivingEntity owner,
        SoldierState state, Entity vehicle) {
        try {
            Object contraption = getContraption.invoke(vehicle);
            if (contraption == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            java.util.List<BlockPos> seats = (java.util.List<BlockPos>) getSeats.invoke(contraption);
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> occupied = (Map<UUID, Integer>) getSeatMapping.invoke(contraption);
            StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} seats={} occupied={}",
                vehicle.getId(), seats.size(), occupied);
            for (int index = 0; index < seats.size(); index++) {
                if (occupied.containsValue(index)) {
                    continue;
                }
                authorizedMounts.put(soldier.getUUID(), vehicle.getUUID());
                try {
                    StevesArmyMod.LOGGER.info("[VS2] Trying contraption seat={} soldier={} vehicle={}",
                        index, soldier.getId(), vehicle.getId());
                    addSittingPassenger.invoke(vehicle, soldier, index);
                    if (soldier.isPassenger() && soldier.getVehicle() == vehicle) {
                        state.transportAnchorId = vehicle.getUUID();
                        state.transportOwnerId = owner.getUUID();
                        state.transportShipId = getShipId.invoke(reflect(getShipMountedTo, owner)) instanceof Number id
                            ? id.longValue() : null;
                        state.seatRetryCooldownTicks = 0;
                        StevesArmyMod.LOGGER.info("[VS2] Assigned soldier={} to Create contraption seat={} vehicle={}",
                            soldier.getId(), index, vehicle.getId());
                        return true;
                    }
                    StevesArmyMod.LOGGER.warn("[VS2] Create seat mount failed soldier={} seat={} vehicle={} passenger={} actualVehicle={}",
                        soldier.getId(), index, vehicle.getId(), soldier.isPassenger(),
                        soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                } finally {
                    authorizedMounts.remove(soldier.getUUID());
                }
            }
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return false;
    }

    private static boolean tryStaticSeat(SoldierEntity soldier, LivingEntity owner, SoldierState state) {
        try {
            Object ownerShip = reflect(getShipMountedTo, owner);
            if (ownerShip == null) {
                StevesArmyMod.LOGGER.info("[VS2] Static seat fallback: owner={} has no mounted ship", owner.getId());
                return false;
            }
            long ownerShipId = ((Number) getShipId.invoke(ownerShip)).longValue();
            Object mountedData = reflect(getShipMountedToData, owner, (Object) null);
            Object mountPosition = mountedData == null ? null : getMountPosInShip.invoke(mountedData);
            if (!(mountPosition instanceof org.joml.Vector3dc localPosition)) {
                StevesArmyMod.LOGGER.info("[VS2] Static seat fallback has no ship-local owner position owner={}", owner.getId());
                return false;
            }
            BlockPos origin = BlockPos.containing(localPosition.x(), localPosition.y(), localPosition.z());
            StevesArmyMod.LOGGER.info("[VS2] Static seat scan owner={} shipOrigin={} worldVehiclePos={}",
                owner.getId(), origin, owner.getVehicle().position());
            int seatBlocks = 0;
            int rejectedShip = 0;
            int occupiedSeats = 0;
            for (int radius = 0; radius <= 10; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        for (int y = -2; y <= 2; y++) {
                            BlockPos candidate = origin.offset(x, y, z);
                            BlockState blockState = owner.level().getBlockState(candidate);
                            if (!createSeatBlockClass.isInstance(blockState.getBlock())) {
                                continue;
                            }
                            seatBlocks++;
                            Object seatShip = reflect(getShipObjectManagingPos, owner.level(), candidate);
                            if (seatShip == null || ((Number) getShipId.invoke(seatShip)).longValue() != ownerShipId) {
                                rejectedShip++;
                                continue;
                            }
                            if (isCreateSeatOccupied(owner.level(), candidate)) {
                                occupiedSeats++;
                                continue;
                            }
                            StevesArmyMod.LOGGER.info("[VS2] Static seat candidate soldier={} pos={} shipId={}",
                                soldier.getId(), candidate, ownerShipId);
                            authorizedStaticSeats.put(soldier.getUUID(), candidate);
                            try {
                                // SeatBlock.sitDown creates the SeatEntity at the shipyard block position
                                // and mounts the soldier without requiring the soldier to be moved there first.
                                // VS2's own rider/rendering mixins handle the world-space transform.
                                createSeatSitDown.invoke(null, owner.level(), candidate, soldier);
                            } finally {
                                authorizedStaticSeats.remove(soldier.getUUID());
                            }
                            if (soldier.isPassenger() && soldier.getVehicle() != null) {
                                state.transportAnchorId = soldier.getVehicle().getUUID();
                                state.transportOwnerId = owner.getUUID();
                                state.transportShipId = ownerShipId;
                                state.transportSeatPosition = candidate;
                                state.seatRetryCooldownTicks = 0;
                                StevesArmyMod.LOGGER.info("[VS2] Static seat attached soldier={} seat={} vehicle={} soldierPos={} vehiclePos={}",
                                    soldier.getId(), candidate, soldier.getVehicle().getId(), soldier.position(),
                                    soldier.getVehicle().position());
                                return true;
                            }
                            StevesArmyMod.LOGGER.warn("[VS2] Static seat mount did not persist soldier={} seat={} passenger={} vehicle={}",
                                soldier.getId(), candidate, soldier.isPassenger(),
                                soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                        }
                    }
                }
            }
            if (seatBlocks > 0 && occupiedSeats == seatBlocks) {
                state.seatRetryCooldownTicks = FULL_SEAT_RETRY_TICKS;
            }
            StevesArmyMod.LOGGER.info("[VS2] Static seat fallback found no usable seat soldier={} owner={} blocks={} shipRejected={} occupied={}",
                soldier.getId(), owner.getId(), seatBlocks, rejectedShip, occupiedSeats);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return false;
    }

    private static boolean isCreateSeatOccupied(Level level, BlockPos position) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (Entity seat : serverLevel.getAllEntities()) {
            if (createSeatEntityClass.isInstance(seat) && seat.blockPosition().equals(position)
            ) {
                return true;
            }
        }
        return false;
    }

    private static void updateTransport(SoldierEntity soldier, SoldierState state) {
        // Command-driven mounts (transportOwnerId == null) are kept regardless of owner state.
        if (state.transportOwnerId == null) {
            if (soldier.isPassenger() && state.transportAnchorId != null
                && state.transportAnchorId.equals(soldier.getVehicle().getUUID())) {
                soldier.getVehicle().positionRider(soldier);
                stopMovement(soldier);
                return;
            }
            StevesArmyMod.LOGGER.info("[VS2] Command-driven transport lost soldier={} passenger={} expectedAnchor={}",
                soldier.getId(), soldier.isPassenger(), state.transportAnchorId);
            Vec3 preDismountPos = soldier.position();
            if (state.transportAnchorId != null && soldier.level() instanceof ServerLevel serverLevel) {
                Entity oldSeat = serverLevel.getEntity(state.transportAnchorId);
                if (oldSeat != null) {
                    ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(oldSeat.getId());
                    for (ServerPlayer player : serverLevel.players()) {
                        if (player.distanceToSqr(oldSeat) < 16384.0) {
                            player.connection.send(removePacket);
                        }
                    }
                    oldSeat.discard();
                }
            }
            // Clear drag state before and after dismount to prevent VS2 ship dragging
            clearShipDraggingState(soldier);
            if (soldier.isPassenger()) {
                soldier.stopRiding();
            }
            clearShipDraggingState(soldier);
            Vec3 postReleasePos = soldier.position();
            StevesArmyMod.LOGGER.info("[VS2] Command transport release soldier={} prePos={} postPos={} dist={}",
                soldier.getId(), formatVec3(preDismountPos), formatVec3(postReleasePos),
                String.format("%.2f", preDismountPos.distanceTo(postReleasePos)));
            state.transportAnchorId = null;
            state.transportSeatPosition = null;
            return;
        }

        Entity anchor = getEntity(soldier, state.transportAnchorId);
        LivingEntity owner = getOwner(soldier, state.transportOwnerId);
        boolean ownerOnShip = owner != null && owner.isAlive() && isTransportOwnerOnShip(owner, state);
        if (anchor != null && soldier.isPassenger() && soldier.getVehicle() == anchor && ownerOnShip) {
            stopMovement(soldier);
            return;
        }

        StevesArmyMod.LOGGER.info("[VS2] Releasing transport soldier={} anchor={} passenger={} owner={} ownerOnShip={}",
            soldier.getId(), anchor == null ? "missing" : anchor.getId(), soldier.isPassenger(),
            owner == null ? "missing" : owner.getId(), ownerOnShip);
        Vec3 preDismountPos = soldier.position();
        // Clear drag state before and after dismount to prevent VS2 ship dragging
        clearShipDraggingState(soldier);
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        clearShipDraggingState(soldier);
        Vec3 postReleasePos = soldier.position();
        StevesArmyMod.LOGGER.info("[VS2] Transport release soldier={} prePos={} postPos={} dist={}",
            soldier.getId(), formatVec3(preDismountPos), formatVec3(postReleasePos),
            String.format("%.2f", preDismountPos.distanceTo(postReleasePos)));
        state.transportAnchorId = null;
        state.transportOwnerId = null;
        state.transportShipId = null;
        state.transportSeatPosition = null;
    }

    private static boolean isTransported(SoldierEntity soldier, SoldierState state) {
        return state.transportAnchorId != null && soldier.isPassenger()
            && state.transportAnchorId.equals(soldier.getVehicle().getUUID());
    }

private static boolean isTransportOwnerOnShip(LivingEntity owner, SoldierState state) {
        Entity vehicle = owner.getVehicle();
        while (vehicle != null) {
            if (createSeatEntityClass.isInstance(vehicle)) {
                try {
                    Vec3 seatWorldPos = vehicle.position();
                    Object ship = reflect(getShipObjectManagingPosDouble, owner.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z);
                    boolean found = ship != null && state.transportShipId != null
                        && ((Number) getShipId.invoke(ship)).longValue() == state.transportShipId;
                    // Throttle diagnostic logging to once per 100 ticks
                    if (found && owner.tickCount % 100 == 0) {
                        StevesArmyMod.LOGGER.info("[VS2] ownerOnShip owner={} seatPos={} shipFound={} expectedShipId={}",
                            owner.getId(), seatWorldPos, ship != null, state.transportShipId);
                    }
                    if (found) return true;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                }
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    private static boolean isMountedToStaticCreateSeat(Entity entity) {
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            if (createSeatEntityClass.isInstance(vehicle)) {
                try {
                    Vec3 seatWorldPos = vehicle.position();
                    return reflect(getShipObjectManagingPosDouble, entity.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z) != null;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                    return false;
                }
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    private static int countTransportedFor(UUID ownerId) {
        int count = 0;
        for (SoldierState state : states.values()) {
            if (ownerId.equals(state.transportOwnerId) && state.transportAnchorId != null) {
                count++;
            }
        }
        return count;
    }

    private static void extractToSafeWorldPosition(SoldierEntity soldier, SoldierState state) {
        stopMovement(soldier);
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        BlockPos destination = findSafePositionOutsideShip(soldier);
        if (destination == null && state.lastSafeWorldPosition != null
            && isSafeWorldPosition(soldier, state.lastSafeWorldPosition)) {
            destination = state.lastSafeWorldPosition;
        }
        if (destination != null) {
            moveToWorldPosition(soldier, destination);
            state.lastSafeWorldPosition = destination;
        }
    }

    @Nullable
    private static BlockPos findSafePositionOutsideShip(SoldierEntity soldier) {
        BlockPos origin = soldier.blockPosition();
        for (int radius : ESCAPE_RADII) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    int worldX = origin.getX() + x;
                    int worldZ = origin.getZ() + z;
                    BlockPos surface = new BlockPos(worldX,
                        soldier.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ), worldZ);
                    if (isSafeWorldPosition(soldier, surface)) {
                        return surface;
                    }
                    for (int y = -3; y <= 3; y++) {
                        BlockPos candidate = origin.offset(x, y, z);
                        if (isSafeWorldPosition(soldier, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findSafePositionNear(SoldierEntity soldier, BlockPos center) {
        for (int[] offset : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = center.offset(offset[0], y, offset[1]);
                if (isSafeWorldPosition(soldier, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isSafeWorldPosition(SoldierEntity soldier, BlockPos position) {
        BlockState floor = soldier.level().getBlockState(position.below());
        if (floor.getCollisionShape(soldier.level(), position.below()).isEmpty()) {
            return false;
        }
        Vec3 location = Vec3.atBottomCenterOf(position);
        AABB bounds = soldier.getDimensions(Pose.STANDING).makeBoundingBox(location);
        return !intersectsShip(soldier.level(), bounds) && soldier.level().noCollision(soldier, bounds);
    }

    private static void moveToWorldPosition(SoldierEntity soldier, BlockPos position) {
        soldier.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, soldier.getYRot(), soldier.getXRot());
        soldier.setDeltaMovement(Vec3.ZERO);
    }

    private static void stopMovement(SoldierEntity soldier) {
        soldier.getNavigation().stop();
        soldier.cancelCoverMovement();
        soldier.setDeltaMovement(Vec3.ZERO);
    }

    /** Clears VS2's per-entity dragging state so the soldier is not dragged by the ship after dismount. */
    private static void clearShipDraggingState(SoldierEntity soldier) {
        try {
            Method getter = soldier.getClass().getMethod("getDraggingInformation");
            Object draggingInfo = getter.invoke(soldier);
            if (draggingInfo != null) {
                Class<?> dc = draggingInfo.getClass();
                try {
                    dc.getMethod("setLastShipStoodOn", Long.class).invoke(draggingInfo, (Long) null);
                } catch (NoSuchMethodException e1) {
                    dc.getMethod("setLastShipStoodOn", Object.class).invoke(draggingInfo, (Object) null);
                }
                dc.getMethod("setAddedMovementLastTick", org.joml.Vector3dc.class)
                    .invoke(draggingInfo, new org.joml.Vector3d());
                try {
                    dc.getMethod("setAddedYawRotLastTick", double.class).invoke(draggingInfo, 0.0);
                } catch (NoSuchMethodException e2) {
                    dc.getMethod("setAddedYawRotLastTick", Double.class).invoke(draggingInfo, 0.0);
                }
            }
        } catch (ReflectiveOperationException e) {
            StevesArmyMod.LOGGER.warn("[VS2] Failed to clear dragging state for soldier {}: {}", soldier.getId(), e.getMessage());
        }
    }

    @Nullable
    private static Entity getEntity(SoldierEntity soldier, @Nullable UUID id) {
        return id == null || !(soldier.level() instanceof ServerLevel level) ? null : level.getEntity(id);
    }

    @Nullable
    private static LivingEntity getOwner(SoldierEntity soldier, @Nullable UUID id) {
        Entity entity = getEntity(soldier, id);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(VS2_MOD_ID)) {
            return;
        }
        try {
            Class<?> utils = Class.forName(VS_UTILS_CLASS);
            Class<?> loadedShipClass = Class.forName("org.valkyrienskies.core.api.ships.LoadedShip");
            Class<?> mountedDataClass = Class.forName("org.valkyrienskies.mod.common.entity.ShipMountedToData");
            Class<?> shipClass = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            Class<?> vector3dClass = Class.forName("org.joml.Vector3d");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // Use MethodHandles to avoid JVM enumerating the entire Kotlin facade method table,
            // which would trigger ClientLevel class loading on dedicated server.
            getShipMountedTo = lookup.findStatic(utils, "getShipMountedTo",
                MethodType.methodType(loadedShipClass, Entity.class));
            getShipsIntersecting = lookup.findStatic(utils, "getShipsIntersecting",
                MethodType.methodType(Iterable.class, Level.class, AABB.class));
            getShipObjectManagingPos = lookup.findStatic(utils, "getShipObjectManagingPos",
                MethodType.methodType(loadedShipClass, Level.class, Vec3i.class));
            getShipObjectManagingPosDouble = lookup.findStatic(utils, "getShipObjectManagingPos",
                MethodType.methodType(loadedShipClass, Level.class, double.class, double.class, double.class));
            getShipMountedToData = lookup.findStatic(utils, "getShipMountedToData",
                MethodType.methodType(mountedDataClass, Entity.class, Float.class));
            toWorldCoordinates = lookup.findStatic(utils, "toWorldCoordinates",
                MethodType.methodType(vector3dClass, shipClass, double.class, double.class, double.class));

            createSeatBlockClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock");
            createSeatEntityClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
            try {
                createSeatSitDown = createSeatBlockClass.getMethod("sitDown",
                    Level.class, BlockPos.class, Entity.class);
            } catch (NoSuchMethodException ignored) {
                // MCP name fallback
                createSeatSitDown = createSeatBlockClass.getDeclaredMethod("m_7600_",
                    Level.class, BlockPos.class, Entity.class);
            }
            getMountPosInShip = mountedDataClass.getMethod("getMountPosInShip");
            contraptionEntityClass = Class.forName(CONTRAPTION_ENTITY_CLASS);
            getContraption = contraptionEntityClass.getMethod("getContraption");
            Class<?> contraptionClass = Class.forName("com.simibubi.create.content.contraptions.Contraption");
            getSeats = contraptionClass.getMethod("getSeats");
            getSeatMapping = contraptionClass.getMethod("getSeatMapping");
            addSittingPassenger = contraptionEntityClass.getMethod("addSittingPassenger", Entity.class, int.class);
            getShipId = shipClass.getMethod("getId");
            if (ModList.get().isLoaded("create_interactive")) {
                Class<?> interactive = Class.forName("org.valkyrienskies.create_interactive.CreateInteractiveUtil");
                createInteractiveUtil = interactive.getField("INSTANCE").get(null);
                getContraptionEntityForShip = interactive.getMethod("getContraptionEntityForShip", long.class, boolean.class);
                StevesArmyMod.LOGGER.info("[VS2] Create Interactive ship-to-contraption lookup enabled");
            }
            available = true;
            StevesArmyMod.LOGGER.info("[VS2] Enabled soldier ship avoidance and transport compatibility");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logReflectionFailure(exception);
        }
    }

    private static Object reflect(MethodHandle handle, Object... args) throws ReflectiveOperationException {
        try {
            return handle.invokeWithArguments(args);
        } catch (ReflectiveOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            // MethodHandle may wrap the real cause
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException roe) {
                throw roe;
            }
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void logReflectionFailure(Throwable exception) {
        available = false;
        if (!reflectionFailureLogged) {
            reflectionFailureLogged = true;
            StevesArmyMod.LOGGER.warn("[VS2] Soldier compatibility is unavailable: {}", exception.toString());
        }
    }

    private static void logSeatAttempt(SoldierEntity soldier, SoldierState state, LivingEntity owner) {
        long now = System.currentTimeMillis();
        if (now - state.lastSeatAttemptLog < 5000L) {
            return;
        }
        state.lastSeatAttemptLog = now;
        StevesArmyMod.LOGGER.info("[VS2] Seat attempt soldier={} owner={} ownerVehicle={} ownerVehicleId={}",
            soldier.getId(), owner.getId(), owner.getVehicle().getClass().getSimpleName(), owner.getVehicle().getId());
    }

    private static SoldierState getOrCreateState(SoldierEntity soldier) {
        return states.computeIfAbsent(soldier.getUUID(), ignored -> new SoldierState());
    }

    private static final class SoldierState {
        @Nullable private BlockPos lastSafeWorldPosition;
        @Nullable private UUID transportAnchorId;
        @Nullable private UUID transportOwnerId;
        @Nullable private Long transportShipId;
        @Nullable private BlockPos transportSeatPosition;
        private int seatRetryCooldownTicks;
        private long lastSeatAttemptLog;
    }
}

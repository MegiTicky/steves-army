package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
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
    private static Method getShipMountedTo;
    private static Method getShipMountedToData;
    private static Method getMountPosInShip;
    private static Method toWorldCoordinates;
    private static Method getShipsIntersecting;
    private static Method getShipObjectManagingPos;
    private static Method getShipObjectManagingPosDouble;
    private static Method createSeatUpdateAfterFallOn;
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

    /** Allows only the immediate Create seat mount initiated by tryContraptionSeat. */
    public static boolean isAuthorizedMount(SoldierEntity soldier, Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        UUID vehicleId = authorizedMounts.get(soldier.getUUID());
        if (vehicleId != null && vehicleId.equals(vehicle.getUUID())) {
            return true;
        }
        return authorizedStaticSeats.containsKey(soldier.getUUID())
            && vehicle.getClass().getName().equals(
                "com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
    }

    public static void clearAuthorizedMount(SoldierEntity soldier) {
        authorizedMounts.remove(soldier.getUUID());
        authorizedStaticSeats.remove(soldier.getUUID());
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
            return getShipMountedTo.invoke(null, entity) != null;
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    private static boolean isInsideShip(Entity entity) {
        return intersectsShip(entity.level(), entity.getBoundingBox());
    }

    private static boolean intersectsShip(Level level, AABB bounds) {
        try {
            Object ships = getShipsIntersecting.invoke(null, level, bounds);
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
            Object ship = getShipMountedTo.invoke(null, owner);
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
                                state.transportShipId = getShipId.invoke(getShipMountedTo.invoke(null, owner)) instanceof Number id
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
                        state.transportShipId = getShipId.invoke(getShipMountedTo.invoke(null, owner)) instanceof Number id
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
            Object ownerShip = getShipMountedTo.invoke(null, owner);
            if (ownerShip == null) {
                StevesArmyMod.LOGGER.info("[VS2] Static seat fallback: owner={} has no mounted ship", owner.getId());
                return false;
            }
            long ownerShipId = ((Number) getShipId.invoke(ownerShip)).longValue();
            Object mountedData = getShipMountedToData.invoke(null, owner, null);
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
                            Object seatShip = getShipObjectManagingPos.invoke(null, owner.level(), candidate);
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
                            Vec3 originalWorldPosition = soldier.position();
                            authorizedStaticSeats.put(soldier.getUUID(), candidate);
                            try {
                                // SeatBlock's collision hook is Create's normal automatic-seat path.
                                // VS stores the block in shipyard coordinates, so present the soldier
                                // there while invoking the same hook, then restore its world position.
                                soldier.setPos(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
                                soldier.setDeltaMovement(0.0D, -0.1D, 0.0D);
                                createSeatUpdateAfterFallOn.invoke(blockState.getBlock(), owner.level(), soldier);
                            } finally {
                                authorizedStaticSeats.remove(soldier.getUUID());
                                if (!soldier.isPassenger()) {
                                    soldier.setPos(originalWorldPosition.x, originalWorldPosition.y, originalWorldPosition.z);
                                    soldier.setDeltaMovement(Vec3.ZERO);
                                }
                            }
                            if (soldier.isPassenger()) {
                                org.joml.Vector3dc transformedSeatPosition = (org.joml.Vector3dc)
                                    toWorldCoordinates.invoke(null, ownerShip, candidate.getX() + 0.5D,
                                        candidate.getY(), candidate.getZ() + 0.5D);
                                Vec3 seatWorldPosition = new Vec3(transformedSeatPosition.x(),
                                    transformedSeatPosition.y(), transformedSeatPosition.z());
                                soldier.moveTo(seatWorldPosition.x, seatWorldPosition.y, seatWorldPosition.z,
                                    soldier.getYRot(), soldier.getXRot());
                                soldier.setDeltaMovement(Vec3.ZERO);
                                state.transportAnchorId = soldier.getVehicle().getUUID();
                                state.transportOwnerId = owner.getUUID();
                                state.transportShipId = ownerShipId;
                                state.transportSeatPosition = candidate;
                                state.seatRetryCooldownTicks = 0;
                                StevesArmyMod.LOGGER.info("[VS2] Static seat attached soldier={} seat={} vehicle={} soldierPos={} seatWorldPos={} vehiclePos={}",
                                    soldier.getId(), candidate, soldier.getVehicle().getId(), soldier.position(),
                                    seatWorldPosition, soldier.getVehicle().position());
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
        Entity anchor = getEntity(soldier, state.transportAnchorId);
        LivingEntity owner = getOwner(soldier, state.transportOwnerId);
        boolean ownerOnShip = owner != null && owner.isAlive() && isTransportOwnerOnShip(owner, state);
        if (anchor != null && soldier.isPassenger() && soldier.getVehicle() == anchor && ownerOnShip) {
            syncStaticSeatPassenger(soldier, anchor, state);
            stopMovement(soldier);
            return;
        }

        StevesArmyMod.LOGGER.info("[VS2] Releasing transport soldier={} anchor={} passenger={} owner={} ownerOnShip={}",
            soldier.getId(), anchor == null ? "missing" : anchor.getId(), soldier.isPassenger(),
            owner == null ? "missing" : owner.getId(), ownerOnShip);
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        state.transportAnchorId = null;
        state.transportOwnerId = null;
        state.transportShipId = null;
        state.transportSeatPosition = null;

        if (owner != null && !ownerOnShip) {
            BlockPos nearOwner = findSafePositionNear(soldier, owner.blockPosition());
            if (nearOwner != null) {
                moveToWorldPosition(soldier, nearOwner);
                state.lastSafeWorldPosition = nearOwner;
                return;
            }
        }
        extractToSafeWorldPosition(soldier, state);
    }

    private static boolean isTransported(SoldierEntity soldier, SoldierState state) {
        return state.transportAnchorId != null && soldier.isPassenger()
            && state.transportAnchorId.equals(soldier.getVehicle().getUUID());
    }

    private static void syncStaticSeatPassenger(SoldierEntity soldier, Entity anchor, SoldierState state) {
        if (state.transportSeatPosition == null || !createSeatEntityClass.isInstance(anchor)) {
            return;
        }
        try {
            Object ship = getShipObjectManagingPos.invoke(null, soldier.level(), state.transportSeatPosition);
            if (ship == null || state.transportShipId == null
                || ((Number) getShipId.invoke(ship)).longValue() != state.transportShipId) {
                return;
            }
            BlockPos seat = state.transportSeatPosition;
            org.joml.Vector3dc transformed = (org.joml.Vector3dc) toWorldCoordinates.invoke(null, ship,
                seat.getX() + 0.5D, seat.getY(), seat.getZ() + 0.5D);
            soldier.setPos(transformed.x(), transformed.y(), transformed.z());
            soldier.setDeltaMovement(Vec3.ZERO);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
    }

    private static boolean isOnOrInsideShip(Entity entity) {
        return isOnShip(entity) || isInsideShip(entity) || isMountedToStaticCreateSeat(entity);
    }

    private static boolean isTransportOwnerOnShip(LivingEntity owner, SoldierState state) {
        Entity vehicle = owner.getVehicle();
        while (vehicle != null) {
            if (createSeatEntityClass.isInstance(vehicle)) {
                try {
                    Vec3 seatWorldPos = vehicle.position();
                    Object ship = getShipObjectManagingPosDouble.invoke(null, owner.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z);
                    boolean found = ship != null && state.transportShipId != null
                        && ((Number) getShipId.invoke(ship)).longValue() == state.transportShipId;
                    StevesArmyMod.LOGGER.info("[VS2] ownerOnShip owner={} seatPos={} shipFound={} expectedShipId={}",
                        owner.getId(), seatWorldPos, ship != null, state.transportShipId);
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
                    return getShipObjectManagingPosDouble.invoke(null, entity.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z) != null;
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
            getShipMountedTo = utils.getMethod("getShipMountedTo", Entity.class);
            getShipsIntersecting = utils.getMethod("getShipsIntersecting", Level.class, AABB.class);
            getShipObjectManagingPos = utils.getMethod("getShipObjectManagingPos", Level.class, Vec3i.class);
            getShipObjectManagingPosDouble = utils.getMethod("getShipObjectManagingPos", Level.class, double.class, double.class, double.class);
            createSeatBlockClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock");
            createSeatEntityClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
            try {
                createSeatUpdateAfterFallOn = createSeatBlockClass.getMethod("updateEntityAfterFallOn",
                    BlockGetter.class, Entity.class);
            } catch (NoSuchMethodException ignored) {
                // Production Forge jars expose the mapped method as its runtime name.
                createSeatUpdateAfterFallOn = createSeatBlockClass.getMethod("m_5548_",
                    BlockGetter.class, Entity.class);
            }
            getShipMountedToData = utils.getMethod("getShipMountedToData", Entity.class, Float.class);
            Class<?> mountedDataClass = Class.forName("org.valkyrienskies.mod.common.entity.ShipMountedToData");
            getMountPosInShip = mountedDataClass.getMethod("getMountPosInShip");
            Class<?> shipClass = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            toWorldCoordinates = utils.getMethod("toWorldCoordinates", shipClass,
                double.class, double.class, double.class);
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
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
    }

    private static void logReflectionFailure(Exception exception) {
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

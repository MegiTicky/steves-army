package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Reflection-only integration with VS Analog Warfare's vehicle mount handles
 * (com.erika.vsanalogwarfare.vehiclemount). A handle block is linked to one or
 * more Create seats with the Analog Screwdriver; its block entity stores a list
 * of {@code VehicleMountSeatLink(role, seatUuid, seatPos, shipId, shipOffset,
 * handlePos)} records and can resolve (or create) the seat entity in shipyard
 * block space, plus report the handle's current world position for dismount.
 *
 * Used by the vehicle wheel orders: MOUNT prefers handle-linked seats, DISMOUNT
 * lets the soldier out at the handle. Ships/handles without links fall back to
 * the plain free-seat behavior. All reflected members are VAW-declared, so their
 * names survive Forge reobfuscation; the only vanilla reflection is the block
 * FACING property, tried by both official and SRG names.
 */
public final class AnalogWarfareCompat {
    private static final String MOD_ID = "vs_analog_warfare";
    private static final String HANDLE_BE_CLASS =
        "com.erika.vsanalogwarfare.vehiclemount.VehicleMountHandleBlockEntity";
    private static final String LINK_CLASS =
        "com.erika.vsanalogwarfare.vehiclemount.VehicleMountSeatLink";
    /** VAW's own handle search radius/stride, mirrored for compatibility. */
    private static final int SCAN_RANGE = 128;
    private static final int SCAN_STRIDE = 16;

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static boolean failureLogged;

    private static Class<?> handleBeClass;
    private static Class<?> linkClass;
    private static Method handleSeats;        // () -> List<VehicleMountSeatLink>
    private static Method handleLocked;       // () -> boolean
    private static Method handleShipId;       // () -> long
    private static Method handleWorldPos;     // () -> Vec3 (currentWorldPosition)
    private static Method linkResolve;        // (Level, VehicleMountHandleBlockEntity) -> Entity
    private static Method linkCreateSeat;     // (Level, VehicleMountHandleBlockEntity) -> Entity
    private static Method linkSeatUuid;       // () -> UUID
    private static Method linkSeatPos;        // () -> BlockPos
    private static Field facingProperty;      // BlockStateProperties.HORIZONTAL_FACING (name varies by mapping)
    private static Method stateGetValue;      // BlockState.getValue (name varies by mapping)

    private AnalogWarfareCompat() {}

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            handleBeClass = Class.forName(HANDLE_BE_CLASS);
            linkClass = Class.forName(LINK_CLASS);

            handleSeats = handleBeClass.getMethod("seats");
            handleLocked = handleBeClass.getMethod("locked");
            handleShipId = handleBeClass.getMethod("shipId");
            handleWorldPos = handleBeClass.getMethod("currentWorldPosition");

            linkResolve = linkClass.getMethod("resolve",
                net.minecraft.world.level.Level.class, handleBeClass);
            linkCreateSeat = linkClass.getMethod("createSeat",
                net.minecraft.world.level.Level.class, handleBeClass);
            linkSeatUuid = linkClass.getMethod("seatUuid");
            linkSeatPos = linkClass.getMethod("seatPos");

            try {
                facingProperty = BlockStateProperties.class.getField("HORIZONTAL_FACING");
            } catch (NoSuchFieldException srgOnly) {
                facingProperty = BlockStateProperties.class.getField("f_61374_");
            }
            stateGetValue = tryGetValueMethod();

            available = true;
            StevesArmyMod.LOGGER.info("[VAW] Vehicle mount handle integration enabled");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logFailure(exception);
        }
    }

    @Nullable
    private static Method tryGetValueMethod() {
        for (String name : new String[] {"getValue", "m_61143_"}) {
            try {
                return BlockState.class.getMethod(name, net.minecraft.world.level.block.state.properties.Property.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static void logFailure(Throwable exception) {
        available = false;
        if (!failureLogged) {
            failureLogged = true;
            StevesArmyMod.LOGGER.warn("[VAW] Vehicle mount handle integration unavailable: {}",
                exception.toString());
        }
    }

    public static boolean isAvailable() {
        initialize();
        return available;
    }

    public static boolean isHandle(@Nullable BlockEntity blockEntity) {
        return available && handleBeClass.isInstance(blockEntity);
    }

    public static boolean isLocked(BlockEntity handle) {
        try {
            return (boolean) handleLocked.invoke(handle);
        } catch (Exception exception) {
            return true;
        }
    }

    public static long getHandleShipId(BlockEntity handle) {
        try {
            return (long) handleShipId.invoke(handle);
        } catch (Exception exception) {
            return -1L;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getLinks(BlockEntity handle) {
        try {
            return (List<Object>) handleSeats.invoke(handle);
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** The linked seat entity, or null when absent (callers may create it). */
    @Nullable
    public static Entity resolveSeat(ServerLevel level, BlockEntity handle, Object link) {
        try {
            return (Entity) linkResolve.invoke(link, level, handle);
        } catch (Exception exception) {
            return null;
        }
    }

    /** Creates a SeatEntity at the linked position; needs the SeatBlock to exist. */
    @Nullable
    public static Entity createSeat(ServerLevel level, BlockEntity handle, Object link) {
        try {
            return (Entity) linkCreateSeat.invoke(link, level, handle);
        } catch (Exception exception) {
            return null;
        }
    }

    @Nullable
    public static Vec3 getHandleWorldPosition(BlockEntity handle) {
        try {
            return (Vec3) handleWorldPos.invoke(handle);
        } catch (Exception exception) {
            return null;
        }
    }

    @Nullable
    public static Direction getHandleFacing(BlockEntity handle) {
        if (facingProperty == null || stateGetValue == null) {
            return null;
        }
        try {
            BlockState state = handle.getBlockState();
            Object facing = stateGetValue.invoke(state, facingProperty.get(null));
            return facing instanceof Direction direction ? direction : null;
        } catch (Exception exception) {
            return null;
        }
    }

    // --- Ship-level operations ------------------------------------------------

    /**
     * Seat the given soldiers on the ship's handle-linked seats. Soldiers that
     * were seated are removed from the list; returns the seated count. Soldiers
     * are filled into links in handle/link order (the builder's intended order).
     */
    public static int mountViaHandles(ServerLevel level, Object ship, Vec3 searchCenter,
                                      List<SoldierEntity> soldiers) {
        if (soldiers.isEmpty()) {
            return 0;
        }
        List<BlockEntity> handles = findHandlesForShip(level, ship, searchCenter);
        int seated = 0;
        for (BlockEntity handle : handles) {
            if (soldiers.isEmpty()) {
                break;
            }
            if (isLocked(handle)) {
                continue;
            }
            for (Object link : getLinks(handle)) {
                if (soldiers.isEmpty()) {
                    break;
                }
                Entity seat = resolveSeat(level, handle, link);
                if (seat == null) {
                    seat = createSeat(level, handle, link);
                }
                if (seat == null || seat.isRemoved() || !seat.isAlive()
                    || !seat.getPassengers().isEmpty()) {
                    continue;
                }
                SoldierEntity soldier = soldiers.get(0);
                soldier.getNavigation().stop();
                soldier.cancelCoverMovement();
                soldier.setDeltaMovement(Vec3.ZERO);
                if (VS2Compat.seatSoldierOnSeatEntity(soldier, seat)) {
                    soldiers.remove(0);
                    seated++;
                    StevesArmyMod.LOGGER.info("[VAW] handle mount: soldier={} -> seat={} handle={}",
                        soldier.getId(), seat.getId(), handle.getBlockPos());
                }
            }
        }
        return seated;
    }

    /**
     * The handle whose links reference this seat (by entity UUID or block
     * position), or null. Used to let dismounting soldiers out at the handle.
     */
    @Nullable
    public static BlockEntity findHandleForSeat(ServerLevel level, Entity seat) {
        for (BlockEntity handle : scanHandles(level, seat.blockPosition(), null)) {
            for (Object link : getLinks(handle)) {
                if (linkMatchesSeat(link, seat)) {
                    return handle;
                }
            }
        }
        return null;
    }

    private static boolean linkMatchesSeat(Object link, Entity seat) {
        try {
            Object uuid = linkSeatUuid.invoke(link);
            if (uuid instanceof java.util.UUID id && id.equals(seat.getUUID())) {
                return true;
            }
            Object pos = linkSeatPos.invoke(link);
            return pos instanceof BlockPos blockPos && blockPos.equals(seat.blockPosition());
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Let a dismounted soldier out at the handle: teleport to the handle's world
     * position, offset outwards along its facing, and grant the extraction grace.
     */
    public static boolean teleportSoldierToHandle(SoldierEntity soldier, BlockEntity handle) {
        Vec3 world = getHandleWorldPosition(handle);
        if (world == null) {
            return false;
        }
        Direction facing = getHandleFacing(handle);
        double dx = facing == null ? 0.0 : facing.getStepX() * 0.8;
        double dz = facing == null ? 0.0 : facing.getStepZ() * 0.8;
        soldier.moveTo(world.x + dx, world.y + 1.05, world.z + dz, soldier.getYRot(), 0.0F);
        soldier.setDeltaMovement(Vec3.ZERO);
        VS2Compat.markHandleDismount(soldier);
        StevesArmyMod.LOGGER.info("[VAW] handle dismount: soldier={} at handle={}",
            soldier.getId(), handle.getBlockPos());
        return true;
    }

    // --- Handle discovery -----------------------------------------------------

    private static List<BlockEntity> findHandlesForShip(ServerLevel level, Object ship,
                                                        Vec3 searchCenter) {
        Long shipId = VS2Compat.getShipIdOf(ship);
        if (shipId == null) {
            return List.of();
        }
        // Anchor candidates in shipyard block space: the aim point mapped onto the
        // ship, plus any seat entity already aboard (exact block-space coordinates).
        List<BlockPos> anchors = new ArrayList<>();
        Vec3 local = VS2Compat.worldToShipLocal(ship, searchCenter);
        BlockPos shipyardMin = VS2Compat.getShipyardMin(ship);
        if (local != null && shipyardMin != null) {
            anchors.add(shipyardMin.offset(BlockPos.containing(local)));
        }
        for (Entity entity : level.getAllEntities()) {
            if (!VS2Compat.isCreateSeatEntity(entity) || entity.isRemoved()) {
                continue;
            }
            Long entityShipId = VS2Compat.getShipIdAt(level,
                entity.getX(), entity.getY(), entity.getZ());
            if (shipId.equals(entityShipId)) {
                anchors.add(entity.blockPosition());
                if (anchors.size() >= 3) {
                    break;
                }
            }
        }

        List<BlockEntity> handles = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (BlockPos anchor : anchors) {
            for (BlockEntity handle : scanHandles(level, anchor, shipId)) {
                if (seen.add(handle.getBlockPos())) {
                    handles.add(handle);
                }
            }
            if (!handles.isEmpty()) {
                break;
            }
        }
        return handles;
    }

    /** Handles around the anchor in shipyard block space, nearest first. */
    private static List<BlockEntity> scanHandles(ServerLevel level, BlockPos anchor,
                                                 @Nullable Long shipId) {
        List<BlockEntity> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x += SCAN_STRIDE) {
            for (int y = -SCAN_RANGE; y <= SCAN_RANGE; y += SCAN_STRIDE) {
                for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z += SCAN_STRIDE) {
                    cursor.setWithOffset(anchor, x, y, z);
                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    if (!isHandle(blockEntity)) {
                        continue;
                    }
                    if (shipId != null) {
                        long handleShipIdValue = getHandleShipId(blockEntity);
                        if (handleShipIdValue != shipId.longValue()) {
                            continue;
                        }
                    }
                    found.add(blockEntity);
                }
            }
        }
        found.sort(Comparator.comparingInt(be -> manhattanDistance(be.getBlockPos(), anchor)));
        return found;
    }

    private static int manhattanDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY())
            + Math.abs(a.getZ() - b.getZ());
    }
}

package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Superb Warfare compatibility, reflection-only like {@link VS2Compat} (zero
 * compile-time dependency). SBW vehicles are plain world-space entities —
 * {@code VehicleEntity extends Entity}, not a LivingEntity and not a VS ship —
 * with JSON-defined seats whose index 0 is the driver. The two features built
 * on this class: hard-target detection for AT soldiers
 * ({@code ArmorThreatScanner}), and crew mounting
 * ({@code VS2Compat.seatSoldierOnEntity} + {@code CrewAssignment}).
 *
 * <p>Gunnery needs no API here: SBW already mirrors a Mob passenger's attack
 * target into its turret/weapon-station AI and fires natively, so a mounted
 * soldier only has to keep a live {@code setTarget} (see
 * {@code combat.SbwVehicleGunnery}). Driving is deliberately unsupported —
 * SBW vehicles move only on client input packets, which a mob cannot send.</p>
 */
public final class SbwCompat {
    private static final String VEHICLE_ENTITY_CLASS =
        "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static final String VEHICLE_DATA_CLASS =
        "com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData";

    private static volatile boolean initialized;
    private static boolean available;
    private static Class<?> vehicleEntityClass;
    private static Class<?> vehicleDataClass;
    private static Method computedMethod;
    private static Method vehicleDataTypeGetter;
    private static Method maxPassengersGetter;
    private static Method isWreckGetter;
    private static Method changeSeatMethod;
    private static Method getNthEntityMethod;
    private static Method getSeatIndexMethod;

    private SbwCompat() {}

    /** One-shot reflection bind; safe (and cheap) to call from any thread state. */
    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!net.minecraftforge.fml.ModList.get().isLoaded("superbwarfare")) {
            return;
        }
        try {
            vehicleEntityClass = Class.forName(VEHICLE_ENTITY_CLASS);
            vehicleDataClass = Class.forName(VEHICLE_DATA_CLASS);
            computedMethod = vehicleEntityClass.getMethod("computed");
            vehicleDataTypeGetter = vehicleDataClass.getMethod("getType");
            // Kotlin val maxPassengers -> getMaxPassengers().
            maxPassengersGetter = vehicleEntityClass.getMethod("getMaxPassengers");
            // Kotlin var isWreck -> isWreck(); tolerate a getWreck() naming variant.
            try {
                isWreckGetter = vehicleEntityClass.getMethod("isWreck");
            } catch (NoSuchMethodException ignored) {
                isWreckGetter = vehicleEntityClass.getMethod("getWreck");
            }
            changeSeatMethod = vehicleEntityClass.getMethod("changeSeat", Entity.class, int.class);
            getNthEntityMethod = vehicleEntityClass.getMethod("getNthEntity", int.class);
            getSeatIndexMethod = vehicleEntityClass.getMethod("getSeatIndex", Entity.class);
            available = true;
            StevesArmyMod.LOGGER.info("[SBW] Superb Warfare compatibility bound (vehicle API resolved)");
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError exception) {
            available = false;
            StevesArmyMod.LOGGER.warn("[SBW] Superb Warfare detected but the vehicle API could not be "
                + "resolved ({}); SBW vehicle compat is disabled", exception.toString());
        }
    }

    public static boolean isEnabled() {
        if (!initialized) {
            initialize();
        }
        return available && StevesArmyConfig.SBW_COMPAT_ENABLED.get();
    }

    /** Cheap subtype test; true for every SBW vehicle entity. Safe when SBW is absent. */
    public static boolean isVehicle(@Nullable Entity entity) {
        if (!initialized) {
            initialize();
        }
        return vehicleEntityClass != null && entity != null
            && vehicleEntityClass.isInstance(entity);
    }

    /** A live SBW vehicle that is not a wreck (wrecks are already destroyed hulls). */
    public static boolean isOperational(@Nullable Entity entity) {
        return entity != null && !entity.isRemoved() && isVehicle(entity) && !isWreck(entity);
    }

    /** SBW VehicleType enum name ("TANK", "APC", "BOAT", ...), or null when unavailable. */
    @Nullable
    public static String getVehicleTypeName(Entity vehicle) {
        if (!isVehicle(vehicle) || computedMethod == null || vehicleDataTypeGetter == null) {
            return null;
        }
        try {
            Object data = computedMethod.invoke(vehicle);
            Object type = data != null ? vehicleDataTypeGetter.invoke(data) : null;
            return type instanceof Enum<?> enumType ? enumType.name() : null;
        } catch (ReflectiveOperationException exception) {
            logOnce("vehicle type", exception);
            return null;
        }
    }

    /** JSON seat count (max passengers), or -1 when unavailable. */
    public static int maxPassengers(Entity vehicle) {
        if (!isVehicle(vehicle) || maxPassengersGetter == null) {
            return -1;
        }
        try {
            return ((Number) maxPassengersGetter.invoke(vehicle)).intValue();
        } catch (ReflectiveOperationException exception) {
            logOnce("max passengers", exception);
            return -1;
        }
    }

    public static boolean isWreck(Entity vehicle) {
        if (isWreckGetter == null) {
            return false;
        }
        try {
            return (Boolean) isWreckGetter.invoke(vehicle);
        } catch (ReflectiveOperationException exception) {
            logOnce("wreck state", exception);
            return false;
        }
    }

    /** The passenger currently in seat {@code index}, or null when the seat is free. */
    @Nullable
    public static Entity passengerInSeat(Entity vehicle, int index) {
        if (!isVehicle(vehicle) || getNthEntityMethod == null) {
            return null;
        }
        try {
            return (Entity) getNthEntityMethod.invoke(vehicle, index);
        } catch (ReflectiveOperationException exception) {
            logOnce("seat lookup", exception);
            return null;
        }
    }

    /** Moves a passenger to the given 0-based seat. False when the move failed. */
    public static boolean changeSeat(Entity vehicle, Entity passenger, int index) {
        if (!isVehicle(vehicle) || changeSeatMethod == null) {
            return false;
        }
        try {
            return (Boolean) changeSeatMethod.invoke(vehicle, passenger, index);
        } catch (ReflectiveOperationException exception) {
            logOnce("seat change", exception);
            return false;
        }
    }

    /** The 0-based seat the passenger currently occupies, or -1. */
    public static int seatIndexOf(Entity vehicle, Entity passenger) {
        if (!isVehicle(vehicle) || getSeatIndexMethod == null) {
            return -1;
        }
        try {
            return ((Number) getSeatIndexMethod.invoke(vehicle, passenger)).intValue();
        } catch (ReflectiveOperationException exception) {
            logOnce("seat index", exception);
            return -1;
        }
    }

    /**
     * First free seat index, searching gunner seats (1..n) before the driver
     * seat when {@code preferGunnerSeat} — the AI cannot drive, so seat 0 is
     * kept for a player who wants to take the wheel. -1 when the vehicle is full.
     */
    public static int firstFreeSeat(Entity vehicle, boolean preferGunnerSeat) {
        int capacity = maxPassengers(vehicle);
        if (capacity <= 0) {
            return -1;
        }
        if (!preferGunnerSeat) {
            for (int index = 0; index < capacity; index++) {
                if (passengerInSeat(vehicle, index) == null) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = 1; index < capacity; index++) {
            if (passengerInSeat(vehicle, index) == null) {
                return index;
            }
        }
        return passengerInSeat(vehicle, 0) == null ? 0 : -1;
    }

    /**
     * Hostility for hard-target detection, through the observer's ordinary
     * {@link SoldierEntity#isFriendlyTo} identity (owner, scoreboard teams,
     * allies): an SBW vehicle is a threat when an unfriendly LivingEntity rides
     * it. Passengers are always living (players, mobs), so the standard test
     * applies without special-casing.
     */
    public static boolean hasHostileOccupant(SoldierEntity observer, Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof LivingEntity living
                && passenger.isAlive()
                && !observer.isFriendlyTo(living)) {
                return true;
            }
        }
        return false;
    }

    /** Friendly-aboard veto: a vehicle carrying any of our side's people is never a rocket target. */
    public static boolean hasFriendlyOccupant(SoldierEntity observer, Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof LivingEntity living
                && passenger.isAlive()
                && observer.isFriendlyTo(living)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves a soldier's mount to a live SBW vehicle, or null. */
    @Nullable
    public static Entity mountedVehicle(SoldierEntity soldier) {
        Entity vehicle = soldier.getVehicle();
        return isOperational(vehicle) ? vehicle : null;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static final Map<String, Long> lastFailureLog = new HashMap<>();
    private static final long FAILURE_LOG_INTERVAL_MS = 30_000L;

    /** Throttled warn so a broken binding cannot flood the log from a hot path. */
    private static void logOnce(String what, ReflectiveOperationException exception) {
        long now = System.currentTimeMillis();
        Long last = lastFailureLog.get(what);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLog.put(what, now);
        StevesArmyMod.LOGGER.warn("[SBW] {} lookup failed: {}", what, exception.toString());
    }
}

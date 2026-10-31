package com.stevesarmy.vehicle;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single authorization policy for every soldier mount, covering SBW vehicles
 * and the existing VS2 / Create seats.
 */
public final class VehicleMountPolicy {

    private static final Map<UUID, UUID> AUTHORIZED = new ConcurrentHashMap<>();

    private VehicleMountPolicy() {
    }

    public static void authorize(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null) return;
        AUTHORIZED.put(soldier.getUUID(), vehicle.getUUID());
    }

    public static void revoke(SoldierEntity soldier) {
        if (soldier == null) return;
        AUTHORIZED.remove(soldier.getUUID());
        VS2Compat.clearAuthorizedMount(soldier);
    }

    public static boolean isAuthorized(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null) return false;

        if (SbwCompat.isVehicle(vehicle) && !SbwVehicles.isDrone(vehicle)) {
            return true;
        }

        UUID pending = AUTHORIZED.get(soldier.getUUID());
        if (pending != null && pending.equals(vehicle.getUUID())) {
            return true;
        }

        return VS2Compat.isAuthorizedMount(soldier, vehicle);
    }

    public static boolean canBoard(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null || !soldier.isAlive()) return false;
        if (!SbwCompat.isVehicle(vehicle) || SbwVehicles.isDrone(vehicle)) return false;
        if (SbwVehicles.isWrecked(vehicle)) return false;
        if (soldier.isPassenger()) return false;
        if (!SbwVehicles.hasFreeSeat(vehicle)) return false;
        return soldier.getRole() == SoldierRole.VEHICLE_CREW
            || com.stevesarmy.compat.SbwCompat.firstFreePassengerSeat(vehicle) >= 1;
    }

    public static boolean board(SoldierEntity soldier, Entity vehicle) {
        if (!canBoard(soldier, vehicle)) return false;
        boolean ok = VehicleBoardingManager.requestBoard(soldier, vehicle);
        if (ok && soldier.getRole() != SoldierRole.VEHICLE_CREW) {
            // A taxi rider's previous infantry target must not become a vehicle
            // gunner target while the soldier's usual combat AI is frozen.
            soldier.setTarget(null);
        }
        return ok;
    }

    /** Re-seat legacy passengers away from the driver position after upgrading. */
    public static boolean enforceCrewDriverSeat(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null || soldier.getRole() == SoldierRole.VEHICLE_CREW
            || !SbwVehicles.isDriver(vehicle, soldier)) {
            return true;
        }

        int passengerSeat = com.stevesarmy.compat.SbwCompat.firstFreePassengerSeat(vehicle);
        if (passengerSeat >= 1
            && com.stevesarmy.compat.SbwCompat.changeSeat(vehicle, soldier, passengerSeat)
            && com.stevesarmy.compat.SbwCompat.seatIndexOf(vehicle, soldier) == passengerSeat) {
            soldier.setTarget(null);
            return true;
        }

        // No passenger seat is available: release this non-crew rider instead of
        // leaving it in the driver's seat or letting it steer the vehicle.
        VS2Compat.releaseTransport(soldier, false);
        soldier.setTarget(null);
        return false;
    }
}

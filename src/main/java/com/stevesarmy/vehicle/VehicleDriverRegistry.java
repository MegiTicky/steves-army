package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VehicleDriverRegistry {

    private static final Map<UUID, VehicleDriver> DRIVERS = new ConcurrentHashMap<>();

    private VehicleDriverRegistry() {
    }

    public static void onBoarded(SoldierEntity soldier, Entity vehicle) {
        if (vehicle == null || soldier == null) return;
        if (!SbwCompat.isVehicle(vehicle) || SbwVehicles.isDrone(vehicle)) return;
        if (vehicle.level().isClientSide) return;

        if (!SbwVehicles.isDriver(vehicle, soldier)) return;

        VehicleDriver driver = DRIVERS.computeIfAbsent(vehicle.getUUID(),
            k -> new VehicleDriver(vehicle));
        driver.setDriverSoldier(soldier);
        StevesArmyMod.LOGGER.debug("[SBW] driver registered: soldier={} vehicle={}",
            soldier.getId(), vehicle.getId());
    }

    public static VehicleDriver get(Entity vehicle) {
        return vehicle == null ? null : DRIVERS.get(vehicle.getUUID());
    }

    public static VehicleDriver getOrCreate(Entity vehicle) {
        if (vehicle == null || !SbwCompat.isVehicle(vehicle) || SbwVehicles.isDrone(vehicle)) return null;
        return DRIVERS.computeIfAbsent(vehicle.getUUID(), k -> new VehicleDriver(vehicle));
    }

    public static void remove(Entity vehicle) {
        if (vehicle != null) {
            DRIVERS.remove(vehicle.getUUID());
            VehicleWeaponController.forget(vehicle);
        }
    }

    public static List<VehicleDriver> all() {
        return new ArrayList<>(DRIVERS.values());
    }

    public static void setFollowTarget(Entity vehicle, Entity target) {
        VehicleDriver d = getOrCreate(vehicle);
        if (d != null) d.orderFollow(target);
    }

    public static void setDestination(Entity vehicle, BlockPos pos) {
        VehicleDriver d = getOrCreate(vehicle);
        if (d != null) d.orderGoTo(pos);
    }

    public static void setAttackTarget(Entity vehicle, UUID targetId, net.minecraft.world.phys.Vec3 pos) {
        VehicleDriver d = getOrCreate(vehicle);
        if (d != null) d.orderAttack(targetId, pos);
    }

    public static void setHold(Entity vehicle) {
        VehicleDriver d = getOrCreate(vehicle);
        if (d != null) d.orderHold();
    }

    public static void setDismount(Entity vehicle) {
        VehicleDriver d = getOrCreate(vehicle);
        if (d != null) d.orderDismount();
    }

    public static Entity vehicleOf(SoldierEntity soldier) {
        if (soldier == null || !soldier.isPassenger()) return null;
        Entity ride = soldier.getVehicle();
        return (SbwCompat.isVehicle(ride) && !SbwVehicles.isDrone(ride)) ? ride : null;
    }

    public static void clearAll() {
        DRIVERS.clear();
        VehicleWeaponController.clearAll();
    }
}
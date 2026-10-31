package com.stevesarmy.compat.sbw;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.vehicle.VehicleDriverRegistry;
import com.stevesarmy.vehicle.VehicleMountPolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

public final class SbwCompat {
    public static final String MOD_ID = "superbwarfare";
    private static boolean loaded = false;

    private SbwCompat() {}

    public static void init() {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (loaded) {
            try {
                MinecraftForge.EVENT_BUS.register(GunshotDetectionHandlerSbw.class);
                StevesArmyMod.LOGGER.info("[SBW] Superb Warfare integration initialized");
            } catch (Throwable t) {
                StevesArmyMod.LOGGER.warn("[SBW] Failed to register SBW gunshot detection handler: {}", t.toString());
            }
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static boolean hasGuns() {
        return loaded;
    }

    public static boolean hasVehicles() {
        return loaded && com.stevesarmy.compat.SbwCompat.isEnabled();
    }

    public static boolean isGun(ItemStack stack) {
        if (!loaded || stack == null || stack.isEmpty()) return false;
        Class<?> gunClass = SbwReflection.cls("com.atsuishio.superbwarfare.item.gun.GunItem");
        Class<?> emptyGunClass = SbwReflection.cls("com.atsuishio.superbwarfare.item.gun.EmptyGunItem");
        if (gunClass != null && gunClass.isInstance(stack.getItem())) {
            return emptyGunClass == null || !emptyGunClass.isInstance(stack.getItem());
        }
        return false;
    }

    public static boolean isSbwProjectile(Entity entity) {
        if (!loaded || entity == null) return false;
        return entity.getClass().getName().startsWith("com.atsuishio.superbwarfare.entity.projectile.");
    }

    public static boolean isVehicle(Entity entity) {
        if (!loaded || entity == null) return false;
        Class<?> vClass = SbwReflection.cls("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity");
        return vClass != null && vClass.isInstance(entity);
    }

    public static void tickVehicle(SoldierEntity soldier) {
        if (!hasVehicles() || soldier == null || soldier.level().isClientSide) return;
        Entity vehicle = soldier.getVehicle();
        if (isVehicle(vehicle)) {
            if (SbwVehicles.isDriver(vehicle, soldier)) {
                if (soldier.getRole() == com.stevesarmy.entity.SoldierRole.VEHICLE_CREW) {
                    VehicleDriverRegistry.onBoarded(soldier, vehicle);
                } else {
                    VehicleMountPolicy.enforceCrewDriverSeat(soldier, vehicle);
                }
            }
        }
    }
}

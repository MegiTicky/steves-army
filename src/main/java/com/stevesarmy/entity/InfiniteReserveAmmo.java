package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Shared TaCZ reflection used to give a soldier a virtual infinite reserve. */
public final class InfiniteReserveAmmo {
    private static final int INFINITE_RESERVE_AMMO = 1_000_000;

    private InfiniteReserveAmmo() {}

    public static boolean hasInfiniteReserveAmmo(SoldierEntity soldier) {
        if (!GunIntegration.isTaczLoaded()) return false;
        try {
            ItemStack gunStack = soldier.getMainHandItem();
            if (!GunIntegration.isGun(gunStack)) return false;

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return false;

            boolean usesDummyAmmo = (boolean) iGunClass.getMethod("useDummyAmmo", ItemStack.class).invoke(iGun, gunStack);
            int reserveAmmo = (int) iGunClass.getMethod("getDummyAmmoAmount", ItemStack.class).invoke(iGun, gunStack);
            int maxReserveAmmo = (int) iGunClass.getMethod("getMaxDummyAmmoAmount", ItemStack.class).invoke(iGun, gunStack);
            return usesDummyAmmo && reserveAmmo > 0 && maxReserveAmmo > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Restores virtual reserve before TaCZ validates a reload. This intentionally does
     * not touch the current magazine or chamber, so each reload remains a normal TaCZ reload.
     */
    public static boolean ensureInfiniteReserveAmmo(SoldierEntity soldier) {
        if (!GunIntegration.isTaczLoaded()) return false;
        try {
            ItemStack gunStack = soldier.getMainHandItem();
            if (!GunIntegration.isGun(gunStack)) return false;

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return false;

            iGunClass.getMethod("setMaxDummyAmmoAmount", ItemStack.class, int.class)
                .invoke(iGun, gunStack, INFINITE_RESERVE_AMMO);
            iGunClass.getMethod("setDummyAmmoAmount", ItemStack.class, int.class)
                .invoke(iGun, gunStack, INFINITE_RESERVE_AMMO);

            return (boolean) iGunClass.getMethod("useDummyAmmo", ItemStack.class).invoke(iGun, gunStack);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[InfiniteAmmo] Failed to configure infinite reserve for soldier {} gun {}: {}",
                soldier.getId(), soldier.getMainHandItem(), e.toString());
            return false;
        }
    }
}

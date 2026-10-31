package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.compat.sbw.SbwCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Shared reflection & provider logic used to give a soldier a virtual infinite reserve. */
public final class InfiniteReserveAmmo {
    private static final int INFINITE_RESERVE_AMMO = 1_000_000;

    private InfiniteReserveAmmo() {}

    public static boolean hasInfiniteReserveAmmo(SoldierEntity soldier) {
        ItemStack gunStack = soldier.getMainHandItem();
        if (gunStack.isEmpty()) return false;

        if (GunIntegration.isSbwLoaded() && SbwCompat.isGun(gunStack)) {
            com.atsuishio.superbwarfare.data.gun.GunData data = com.atsuishio.superbwarfare.data.gun.GunData.from(gunStack);
            return data.virtualAmmo.get() >= 500_000;
        }

        if (!GunIntegration.isTaczLoaded()) return false;
        try {
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

    public static boolean ensureInfiniteReserveAmmo(SoldierEntity soldier) {
        ItemStack gunStack = soldier.getMainHandItem();
        if (gunStack.isEmpty()) return false;

        if (GunIntegration.isSbwLoaded() && SbwCompat.isGun(gunStack)) {
            com.atsuishio.superbwarfare.data.gun.GunData data = com.atsuishio.superbwarfare.data.gun.GunData.from(gunStack);
            data.virtualAmmo.set(INFINITE_RESERVE_AMMO);
            return true;
        }

        if (!GunIntegration.isTaczLoaded()) return false;
        try {
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

    /**
     * Removes the virtual-reserve NBT from a gun stack before it leaves the
     * soldier (death pouch, pick-block egg). The hand stack now persists to
     * loot, and a looted enemy gun must not carry the million-round dummy
     * reserve; {@code prepareEnemyReserveAmmo} re-ensures it before every
     * reload. Keys are TaCZ's GunItemDataAccessor GUN_DUMMY_AMMO /
     * GUN_MAX_DUMMY_AMMO ("DummyAmmo" / "MaxDummyAmmo"); the presence of the
     * first tag is itself the dummy-ammo flag, so removing both fully clears it.
     */
    public static void stripInfiniteReserveNbt(ItemStack stack) {
        if (stack.isEmpty() || !GunIntegration.isGun(stack)) return;
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove("DummyAmmo");
            tag.remove("MaxDummyAmmo");
        }
    }
}

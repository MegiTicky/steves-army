package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import net.minecraft.world.item.ItemStack;

/**
 * Weapon-selection policy over the persistent inventory (sidearm slot +
 * general slots). AT carriers default to a non-AT gun — the sidearm slot
 * when loaded — and raise the launcher only while an armor engagement or a
 * heavy-fire suppression ping is active. Swaps exchange the main hand with
 * the slot holding the wanted gun, so the displaced weapon keeps its slot,
 * NBT, and magazine state. Everyone without an AT gun is untouched.
 */
public final class SoldierWeaponSelector {
    private SoldierWeaponSelector() {}

    /**
     * Aligns the held gun with {@code launcherWanted}. Returns true when a
     * swap happened (callers apply their own cooldown). No-op during reload
     * (cancel is attempted first) and whenever the wanted gun is absent.
     */
    public static boolean update(SoldierEntity soldier, boolean launcherWanted) {
        if (!GunIntegration.isAnyGunLoaded()) {
            return false;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return false;
        }
        ItemStack held = inv.getItem(SoldierInventory.SLOT_MAIN_HAND);
        boolean holdingLauncher = ArmorRoleManager.isAtGunStack(held);
        if (launcherWanted && !holdingLauncher) {
            int launcherSlot = findSlot(inv, true);
            return launcherSlot < 0 ? false : swap(soldier, inv, launcherSlot);
        }
        if (!launcherWanted && holdingLauncher) {
            int defaultSlot = findSlot(inv, false);
            return defaultSlot < 0 ? false : swap(soldier, inv, defaultSlot);
        }
        return false;
    }

    /** True when any persistent slot carries an anti-armor gun. */
    public static boolean hasLauncher(SoldierEntity soldier) {
        SoldierInventory inv = soldier.getSoldierInventory();
        return inv != null && findSlot(inv, true) >= 0;
    }

    /**
     * Launcher rounds available to the soldier: the launcher stack's loaded
     * ammo plus general/sidearm stacks that fit it. Used for the reserve
     * floor that keeps rockets for tanks.
     */
    public static int countLauncherAmmo(SoldierEntity soldier) {
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return 0;
        }
        int launcherSlot = findSlot(inv, true);
        if (launcherSlot < 0) {
            return 0;
        }
        ItemStack launcher = inv.getItem(launcherSlot);
        int total = GunIntegration.getCurrentAmmo(launcher);
        if (soldier.hasInfiniteReserveAmmo()) {
            return Integer.MAX_VALUE;
        }
        for (int slot = SoldierInventory.SLOT_SIDEARM; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && slot != launcherSlot
                && GunIntegration.getAmmoCountForGun(launcher, stack) > 0) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** First persistent slot holding an AT gun ({@code wantAt}) or a non-AT gun. */
    private static int findSlot(SoldierInventory inv, boolean wantAt) {
        for (int slot = SoldierInventory.SLOT_SIDEARM; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && ArmorRoleManager.isAtGunStack(stack) == wantAt) {
                return slot;
            }
        }
        return -1;
    }

    /** Exchanges the main hand with the gun in {@code slot}; both stay count-1. */
    private static boolean swap(SoldierEntity soldier, SoldierInventory inv, int slot) {
        ItemStack replacement = inv.getItem(slot);
        if (replacement.isEmpty() || replacement.getCount() != 1) {
            return false;
        }
        if (GunIntegration.isReloading(soldier)) {
            GunIntegration.cancelReload(soldier);
            if (GunIntegration.isReloading(soldier)) {
                return false;
            }
        }
        ItemStack held = inv.getItem(SoldierInventory.SLOT_MAIN_HAND);
        inv.setItem(SoldierInventory.SLOT_MAIN_HAND, replacement.split(1));
        inv.setItem(slot, held);
        return true;
    }
}

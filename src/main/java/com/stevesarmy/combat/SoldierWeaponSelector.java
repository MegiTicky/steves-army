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
 *
 * The emergency sidearm draw ({@link #swapWithSidearm}) bypasses the policy:
 * a soldier caught in the open with a dry gun swaps straight with the
 * sidearm slot because switching is faster than reloading.
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
     * True when the sidearm slot holds a gun worth drawing in a fight: loaded,
     * count-1, and — unless a launcher is wanted right now — not an AT gun, so
     * a dry rifle never pulls rockets into an infantry engagement.
     */
    public static boolean isUsableSidearm(SoldierEntity soldier, boolean launcherDesired) {
        if (!GunIntegration.isAnyGunLoaded()) {
            return false;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return false;
        }
        ItemStack sidearm = inv.getItem(SoldierInventory.SLOT_SIDEARM);
        if (sidearm.isEmpty() || sidearm.getCount() != 1 || !GunIntegration.isGun(sidearm)) {
            return false;
        }
        if (!launcherDesired && ArmorRoleManager.isAtGunStack(sidearm)) {
            return false;
        }
        return GunIntegration.getCurrentAmmo(sidearm) > 0;
    }

    /**
     * Emergency exchange of the main hand with the sidearm slot; same
     * reload-cancel guard as the policy swap. Returns false when the sidearm
     * slot is unusable or the swap was blocked.
     */
    public static boolean swapWithSidearm(SoldierEntity soldier) {
        if (!GunIntegration.isAnyGunLoaded()) {
            return false;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return false;
        }
        return swap(soldier, inv, SoldierInventory.SLOT_SIDEARM);
    }

    /**
     * Puts the fallback's original main weapon back into the main hand: finds
     * the tracked stack by item in any persistent slot and exchanges it with
     * whatever is held. Falls back to the plain sidearm-slot undo, and to a
     * no-op success when the gun is gone entirely (e.g. taken via the squad
     * menu mid-fallback). Same reload-cancel guard as every swap.
     */
    public static boolean restoreGun(SoldierEntity soldier, ItemStack originalMain) {
        if (!GunIntegration.isAnyGunLoaded()) {
            return false;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return false;
        }
        if (!originalMain.isEmpty()) {
            for (int slot = SoldierInventory.SLOT_SIDEARM; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
                if (slot != SoldierInventory.SLOT_MAIN_HAND
                    && ItemStack.isSameItem(inv.getItem(slot), originalMain)) {
                    return swap(soldier, inv, slot);
                }
            }
        }
        ItemStack sidearm = inv.getItem(SoldierInventory.SLOT_SIDEARM);
        if (!sidearm.isEmpty() && sidearm.getCount() == 1 && GunIntegration.isGun(sidearm)) {
            return swap(soldier, inv, SoldierInventory.SLOT_SIDEARM);
        }
        return true;
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

    /**
     * Rearranges guns into the standard resting layout: a non-launcher gun in
     * the main hand, a pistol — or, for AT carriers, the launcher — in the
     * sidearm slot, remaining guns and all non-gun stacks keeping their
     * relative order in the general range. Non-guns are never relocated
     * otherwise. Guns stacked with count &gt; 1 are left alone.
     */
    public static void normalizeGunSlots(SoldierEntity soldier) {
        if (!GunIntegration.isAnyGunLoaded()) {
            return;
        }
        SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return;
        }

        ItemStack[] snapshot = new ItemStack[SoldierInventory.INVENTORY_SIZE];
        java.util.List<ItemStack> guns = new java.util.ArrayList<>();
        for (int slot = SoldierInventory.SLOT_SIDEARM; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inv.getItem(slot);
            snapshot[slot] = stack;
            if (!stack.isEmpty() && stack.getCount() == 1 && GunIntegration.isGun(stack)) {
                guns.add(stack);
            }
        }
        if (guns.isEmpty()) {
            return;
        }

        // Main gun: held non-launcher wins, else the first non-launcher, else
        // the first gun (launchers only — the soldier must be able to use it).
        ItemStack held = snapshot[SoldierInventory.SLOT_MAIN_HAND];
        ItemStack mainGun = null;
        if (!held.isEmpty() && held.getCount() == 1 && GunIntegration.isGun(held)
            && !ArmorRoleManager.isAtGunStack(held)) {
            mainGun = held;
        }
        if (mainGun == null) {
            for (ItemStack gun : guns) {
                if (!ArmorRoleManager.isAtGunStack(gun)) {
                    mainGun = gun;
                    break;
                }
            }
        }
        if (mainGun == null) {
            mainGun = guns.get(0);
        }

        // Sidearm: first pistol among the rest; AT carriers fall back to the
        // launcher so it rides stowed on the back until an engagement raises it.
        ItemStack sidearmGun = null;
        for (ItemStack gun : guns) {
            if (gun != mainGun && ArmorRoleManager.isSidearmGunStack(gun)) {
                sidearmGun = gun;
                break;
            }
        }
        if (sidearmGun == null && soldier.getRole() == com.stevesarmy.entity.SoldierRole.ANTI_TANK) {
            for (ItemStack gun : guns) {
                if (gun != mainGun && ArmorRoleManager.isAtGunStack(gun)) {
                    sidearmGun = gun;
                    break;
                }
            }
        }

        java.util.Set<ItemStack> placed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        placed.add(mainGun);
        if (sidearmGun != null) {
            placed.add(sidearmGun);
        }

        inv.setItem(SoldierInventory.SLOT_MAIN_HAND, mainGun.copy());
        inv.setItem(SoldierInventory.SLOT_SIDEARM, sidearmGun != null ? sidearmGun.copy() : ItemStack.EMPTY);

        // Everything unplaced — including whatever the old sidearm/main slots
        // held — compacts into the general range in original order.
        int cursor = SoldierInventory.SLOT_GENERAL_START;
        for (int slot = SoldierInventory.SLOT_SIDEARM; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = snapshot[slot];
            if (stack.isEmpty() || placed.contains(stack)) {
                continue;
            }
            if (cursor < SoldierInventory.INVENTORY_SIZE) {
                inv.setItem(cursor++, stack.copy());
            }
        }
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

package com.stevesarmy.inventory;

import com.stevesarmy.combat.GunIntegration;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import javax.annotation.Nonnull;

public class SoldierInventoryHandler implements IItemHandlerModifiable {
    private final SoldierInventory inventory;

    public SoldierInventoryHandler(SoldierInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public int getSlots() {
        return inventory.getContainerSize();
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        return inventory.getItem(slot);
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        if (slot < 0 || slot >= getSlots()) return stack;
        if (slot == SoldierInventory.SLOT_SIDEARM) {
            if (!GunIntegration.isGun(stack) || !inventory.getItem(slot).isEmpty()) return stack;
            if (simulate) return ItemStack.EMPTY;
            inventory.setItem(slot, stack.copyWithCount(1));
            stack.shrink(1);
            return stack;
        }

        ItemStack existing = inventory.getItem(slot);
        int maxStackSize = Math.min(stack.getMaxStackSize(), 64);

        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameTags(existing, stack)) {
                return stack;
            }
            int canInsert = maxStackSize - existing.getCount();
            if (canInsert <= 0) return stack;

            if (simulate) {
                if (stack.getCount() <= canInsert) return ItemStack.EMPTY;
                stack.shrink(canInsert);
                return stack;
            }

            int toInsert = Math.min(stack.getCount(), canInsert);
            existing.grow(toInsert);
            stack.shrink(toInsert);
            inventory.setChanged();
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        }

        if (simulate) {
            int toInsert = Math.min(stack.getCount(), maxStackSize);
            stack.shrink(toInsert);
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        }

        ItemStack toInsert = stack.split(maxStackSize);
        inventory.setItem(slot, toInsert);
        return stack;
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot < 0 || slot >= getSlots() || amount <= 0) return ItemStack.EMPTY;

        ItemStack existing = inventory.getItem(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;

        int toExtract = Math.min(existing.getCount(), amount);

        if (simulate) {
            return existing.copy().split(toExtract);
        }

        ItemStack result = inventory.removeItem(slot, toExtract);
        inventory.setChanged();
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return slot >= 0 && slot < getSlots()
            && (slot != SoldierInventory.SLOT_SIDEARM || GunIntegration.isGun(stack));
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        if (slot != SoldierInventory.SLOT_SIDEARM || stack.isEmpty() || GunIntegration.isGun(stack)) {
            inventory.setItem(slot, stack);
        }
    }
}

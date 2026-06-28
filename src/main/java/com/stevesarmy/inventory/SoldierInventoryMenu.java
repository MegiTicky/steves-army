package com.stevesarmy.inventory;

import com.stevesarmy.registry.ModMenuTypes;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class SoldierInventoryMenu extends AbstractContainerMenu {
    private final Container soldierInventory;
    private final int soldierId;

    public SoldierInventoryMenu(int containerId, Inventory playerInventory, Container soldierInventory, int soldierId) {
        super(ModMenuTypes.SOLDIER_INVENTORY.get(), containerId);
        this.soldierInventory = soldierInventory;
        this.soldierId = soldierId;

        for (int i = 0; i < 9; i++) {
            this.addSlot(new Slot(soldierInventory, i, 8 + i * 18, 17));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 39 + row * 18));
            }
        }

        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 97));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        
        if (slot != null && slot.hasItem()) {
            ItemStack slotItem = slot.getItem();
            result = slotItem.copy();
            
            if (index < 9) {
                if (!this.moveItemStackTo(slotItem, 9, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(slotItem, 0, 9, false)) {
                    return ItemStack.EMPTY;
                }
            }
            
            if (slotItem.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.soldierInventory.stillValid(player);
    }

    public int getSoldierId() {
        return soldierId;
    }
}
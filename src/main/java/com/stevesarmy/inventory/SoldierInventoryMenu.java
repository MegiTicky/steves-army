package com.stevesarmy.inventory;

import com.mojang.datafixers.util.Pair;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModMenuTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

public class SoldierInventoryMenu extends AbstractContainerMenu {
    private static final ResourceLocation[] TEXTURE_EMPTY_ARMOR_SLOTS = new ResourceLocation[]{
        InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
        InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
        InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
        InventoryMenu.EMPTY_ARMOR_SLOT_HELMET
    };
    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final Container soldierInventory;
    private final int soldierId;
    @Nullable
    private final SoldierEntity soldier;

    public SoldierInventoryMenu(int containerId, Inventory playerInventory, Container soldierInventory, 
                                int soldierId, @Nullable SoldierEntity soldier) {
        super(ModMenuTypes.SOLDIER_INVENTORY.get(), containerId);
        this.soldierInventory = soldierInventory;
        this.soldierId = soldierId;
        this.soldier = soldier;

        addArmorSlots();
        addMainHandSlot();
        addGeneralSlots();
        addPlayerInventorySlots(playerInventory);
    }

    private void addArmorSlots() {
        for (int i = 0; i < 4; i++) {
            final EquipmentSlot equipSlot = ARMOR_SLOTS[i];
            final int slotIndex = i;
            this.addSlot(new Slot(soldierInventory, slotIndex, 8, 18 + i * 18) {
                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(equipSlot, null);
                }

                @Override
                public void set(ItemStack stack) {
                    super.set(stack);
                    if (soldier != null) {
                        soldier.setItemSlot(equipSlot, stack);
                    }
                }

                @OnlyIn(Dist.CLIENT)
                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, TEXTURE_EMPTY_ARMOR_SLOTS[slotIndex]);
                }
            });
        }
    }

    private void addMainHandSlot() {
        this.addSlot(new Slot(soldierInventory, SoldierInventory.SLOT_MAIN_HAND, 26, 90) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof SwordItem || !stack.isEmpty();
            }

            @Override
            public void set(ItemStack stack) {
                super.set(stack);
                if (soldier != null) {
                    soldier.setItemSlot(EquipmentSlot.MAINHAND, stack);
                }
            }
        });
    }

    private void addGeneralSlots() {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int slotIndex = SoldierInventory.SLOT_GENERAL_START + col + row * 3;
                this.addSlot(new Slot(soldierInventory, slotIndex, 82 + col * 18, 18 + row * 18));
            }
        }
    }

    private void addPlayerInventorySlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 108 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 162));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotItem = slot.getItem();
            result = slotItem.copy();

            if (index < 15) {
                if (!this.moveItemStackTo(slotItem, 15, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(slotItem, 0, 15, false)) {
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

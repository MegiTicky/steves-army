package com.stevesarmy.inventory;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.Consumer;

public class SoldierInventory implements Container {
    public static final int INVENTORY_SIZE = 26;
    public static final int ARMOR_HEAD = 0;
    public static final int ARMOR_CHEST = 1;
    public static final int ARMOR_LEGS = 2;
    public static final int ARMOR_FEET = 3;
    public static final int SLOT_OFF_HAND = 4;
    public static final int SLOT_MAIN_HAND = 5;
    public static final int SLOT_GENERAL_START = 6;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final NonNullList<ItemStack> items;
    @Nullable
    private Consumer<ItemStack> mainHandChangedCallback;

    public SoldierInventory() {
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    public void setMainHandChangedCallback(@Nullable Consumer<ItemStack> callback) {
        this.mainHandChangedCallback = callback;
    }

    public void syncArmorToEntity(SoldierEntity soldier) {
        for (int i = 0; i < 4; i++) {
            soldier.setItemSlot(ARMOR_SLOTS[i], items.get(i));
        }
        // The offhand is reserved for temporary healing use and is never
        // restored from the persisted soldier inventory.
        soldier.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        soldier.setItemSlot(EquipmentSlot.MAINHAND, items.get(SLOT_MAIN_HAND));
    }

    public void syncFromEntity(SoldierEntity soldier) {
        for (int i = 0; i < 4; i++) {
            ItemStack currentInSlot = items.get(i);
            ItemStack entityItem = soldier.getItemBySlot(ARMOR_SLOTS[i]);
            if (!ItemStack.matches(currentInSlot, entityItem)) {
                items.set(i, entityItem.copy());
            }
        }
        ItemStack currentMainHand = items.get(SLOT_MAIN_HAND);
        ItemStack entityMainHand = soldier.getMainHandItem();
        if (!ItemStack.matches(currentMainHand, entityMainHand)) {
            items.set(SLOT_MAIN_HAND, entityMainHand.copy());
        }
    }

    @Override
    public int getContainerSize() {
        return INVENTORY_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (slot == SLOT_MAIN_HAND && mainHandChangedCallback != null) {
            mainHandChangedCallback.accept(items.get(SLOT_MAIN_HAND));
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(items, slot);
        if (slot == SLOT_MAIN_HAND && mainHandChangedCallback != null) {
            mainHandChangedCallback.accept(items.get(SLOT_MAIN_HAND));
        }
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            if (slot == SLOT_OFF_HAND && !stack.isEmpty()) {
                for (int generalSlot = SLOT_GENERAL_START; generalSlot < INVENTORY_SIZE; generalSlot++) {
                    ItemStack existing = items.get(generalSlot);
                    if (!existing.isEmpty()
                        && ItemStack.isSameItemSameTags(existing, stack)
                        && existing.getCount() + stack.getCount() <= existing.getMaxStackSize()) {
                        existing.grow(stack.getCount());
                        return;
                    }
                }
                for (int generalSlot = SLOT_GENERAL_START; generalSlot < INVENTORY_SIZE; generalSlot++) {
                    if (items.get(generalSlot).isEmpty()) {
                        items.set(generalSlot, stack);
                        return;
                    }
                }
                // Preserve the item in the hidden legacy slot rather than
                // silently deleting it when every general slot is occupied.
            }
            items.set(slot, stack);
            if (slot == SLOT_MAIN_HAND && mainHandChangedCallback != null) {
                mainHandChangedCallback.accept(stack);
            }
        }
    }

    @Override
    public void setChanged() {
        if (mainHandChangedCallback != null) {
            mainHandChangedCallback.accept(items.get(SLOT_MAIN_HAND));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        if (mainHandChangedCallback != null) {
            mainHandChangedCallback.accept(ItemStack.EMPTY);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", i);
                items.get(i).save(itemTag);
                list.add(itemTag);
            }
        }
        tag.put("Items", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        items.clear();
        ItemStack legacyOffhand = ItemStack.EMPTY;
        ListTag list = tag.getList("Items", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < items.size()) {
                ItemStack stack = ItemStack.of(itemTag);
                if (slot == SLOT_OFF_HAND) {
                    legacyOffhand = stack;
                } else {
                    items.set(slot, stack);
                }
            }
        }

        // Move the old persistent offhand item into the regular inventory so
        // it cannot block the temporary offhand used by the healing goal.
        if (!legacyOffhand.isEmpty()) {
            for (int slot = SLOT_GENERAL_START; slot < INVENTORY_SIZE; slot++) {
                if (items.get(slot).isEmpty()) {
                    items.set(slot, legacyOffhand);
                    legacyOffhand = ItemStack.EMPTY;
                    break;
                }
            }
        }
    }

    public NonNullList<ItemStack> getItems() {
        return items;
    }
}

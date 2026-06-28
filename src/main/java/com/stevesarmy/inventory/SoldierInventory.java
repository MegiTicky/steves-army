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
    private final NonNullList<ItemStack> items;
    public static final int INVENTORY_SIZE = 9;
    @Nullable
    private Consumer<ItemStack> slot0ChangedCallback;

    public SoldierInventory() {
        this.items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    }

    public void setSlot0ChangedCallback(@Nullable Consumer<ItemStack> callback) {
        this.slot0ChangedCallback = callback;
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
        if (slot == 0 && slot0ChangedCallback != null && !result.isEmpty()) {
            slot0ChangedCallback.accept(items.get(slot));
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(items, slot);
        if (slot == 0 && slot0ChangedCallback != null) {
            slot0ChangedCallback.accept(ItemStack.EMPTY);
        }
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < items.size()) {
            items.set(slot, stack);
            if (slot == 0 && slot0ChangedCallback != null) {
                slot0ChangedCallback.accept(stack);
            }
        }
    }

    @Override
    public void setChanged() {
        if (slot0ChangedCallback != null) {
            slot0ChangedCallback.accept(items.get(0));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        items.clear();
        if (slot0ChangedCallback != null) {
            slot0ChangedCallback.accept(ItemStack.EMPTY);
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
        ListTag list = tag.getList("Items", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < items.size()) {
                items.set(slot, ItemStack.of(itemTag));
            }
        }
    }

    public NonNullList<ItemStack> getItems() {
        return items;
    }
}
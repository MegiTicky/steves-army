package com.stevesarmy.inventory;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.SoldierWeaponSelector;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.core.NonNullList;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side 27-slot view of a supply pouch's contents. The payload lives in
 * the item stack's NBT using the {@link SoldierInventory#save()} Items format,
 * so a pouch round-trips losslessly between a player's hands, the ground, and
 * soldier inventories. Every change writes straight through to the live stack
 * the player is holding; the close event performs the final write wherever the
 * marked stack ended up (the player can shift-move it while the menu is open).
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class PouchContainer implements Container {
    public static final int SIZE = 27;
    public static final String POUCH_ID_TAG = "PouchId";
    private static final String ITEMS_TAG = "Items";

    private final Player owner;
    private final UUID pouchId;
    private final NonNullList<ItemStack> slots = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    /** Opens the menu-side container for the held pouch stack. */
    public PouchContainer(Player owner, UUID pouchId) {
        this.owner = owner;
        this.pouchId = pouchId;
        loadFrom(getMarkedStack(owner, pouchId));
    }

    // ------------------------------------------------------------------
    // Container over the working slot list
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < SIZE ? slots.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(slots, slot, amount);
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack result = ContainerHelper.takeItem(slots, slot);
        setChanged();
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) return;
        if (stack.getItem() instanceof com.stevesarmy.item.SupplyPouchItem) {
            return; // no pouches inside pouches
        }
        slots.set(slot, stack);
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return !(stack.getItem() instanceof com.stevesarmy.item.SupplyPouchItem);
    }

    @Override
    public void setChanged() {
        writeThrough();
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // held-item menu: no block distance to honor
    }

    @Override
    public void clearContent() {
        slots.clear();
        slots.replaceAll(stack -> ItemStack.EMPTY);
        writeThrough();
    }

    // ------------------------------------------------------------------
    // Persistence into the live stack
    // ------------------------------------------------------------------

    private void loadFrom(@Nullable ItemStack stack) {
        if (stack == null || stack.getTag() == null) return;
        loadFromTag(stack.getTag());
    }

    private void loadFromTag(CompoundTag tag) {
        ListTag list = extractItemsList(tag);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < SIZE) {
                slots.set(slot, ItemStack.of(entry));
            }
        }
    }

    private void writeThrough() {
        ItemStack live = getMarkedStack(owner, pouchId);
        if (live == null) {
            return; // carried stack left the inventory; close write-back decides
        }
        CompoundTag tag = live.getOrCreateTag();
        tag.putUUID(POUCH_ID_TAG, pouchId);
        tag.put(ITEMS_TAG, serializeSlotList());
    }

    /** Final write when the pouch menu closes. */
    private void writeBackAndClose() {
        ItemStack live = getMarkedStack(owner, pouchId);
        if (live != null) {
            CompoundTag tag = live.getOrCreateTag();
            tag.putUUID(POUCH_ID_TAG, pouchId);
            tag.put(ITEMS_TAG, serializeSlotList());
        } else {
            // The opened stack left the inventory entirely — spill the working
            // contents at the player so nothing is lost.
            for (ItemStack stack : slots) {
                if (!stack.isEmpty()) {
                    ItemEntity entity = new ItemEntity(owner.level(),
                        owner.getX(), owner.getY() + 0.5, owner.getZ(), stack.copy());
                    owner.level().addFreshEntity(entity);
                }
            }
        }
        clearWorkingSlots();
    }

    private void clearWorkingSlots() {
        for (int i = 0; i < SIZE; i++) {
            slots.set(i, ItemStack.EMPTY);
        }
    }

    private ListTag serializeSlotList() {
        ListTag list = new ListTag();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = slots.get(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", i);
                stack.save(entry);
                list.add(entry);
            }
        }
        return list;
    }

    /**
     * Readers for the Items tag. Pouches written by the first (broken) build
     * nested the list inside a stray compound — unwrap that too; the next
     * write-through normalizes the shape.
     */
    private static ListTag extractItemsList(@Nullable CompoundTag tag) {
        if (tag == null) return new ListTag();
        if (tag.contains(ITEMS_TAG, Tag.TAG_LIST)) {
            return tag.getList(ITEMS_TAG, Tag.TAG_COMPOUND);
        }
        if (tag.contains(ITEMS_TAG, Tag.TAG_COMPOUND)) {
            return tag.getCompound(ITEMS_TAG).getList(ITEMS_TAG, Tag.TAG_COMPOUND);
        }
        return new ListTag();
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getContainer() instanceof net.minecraft.world.inventory.ChestMenu menu
            && menu.getContainer() instanceof PouchContainer pouch) {
            pouch.writeBackAndClose();
        }
    }

    // ------------------------------------------------------------------
    // Static pouch stack helpers
    // ------------------------------------------------------------------

    /** Wraps a soldier's whole inventory into one fresh pouch stack. */
    public static ItemStack createPouchFromInventory(SoldierInventory inventory) {
        ItemStack pouch = new ItemStack(ModItems.SUPPLY_POUCH.get());
        CompoundTag tag = pouch.getOrCreateTag();
        tag.putUUID(POUCH_ID_TAG, UUID.randomUUID());
        CompoundTag saved = inventory.save();
        tag.put(ITEMS_TAG, saved.getList("Items", Tag.TAG_COMPOUND));
        return pouch;
    }

    /**
     * Packs an arbitrary payload list into a fresh pouch stack. Slot indices
     * start at the general range so a soldier-side restore never lands payload
     * into empty armor/sidearm/main-hand slots.
     */
    public static ItemStack packContents(List<ItemStack> payload) {
        ItemStack pouch = new ItemStack(ModItems.SUPPLY_POUCH.get());
        CompoundTag tag = pouch.getOrCreateTag();
        tag.putUUID(POUCH_ID_TAG, UUID.randomUUID());
        ListTag list = new ListTag();
        int slot = SoldierInventory.SLOT_GENERAL_START;
        for (ItemStack stack : payload) {
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot++);
            stack.save(entry);
            list.add(entry);
        }
        tag.put(ITEMS_TAG, list);
        return pouch;
    }

    /**
     * Death drop: spawn the soldier's entire inventory as ONE pouch item via
     * spawnAtLocation so it rides the normal death-drop pipeline.
     */
    public static void dropInventoryAsPouch(SoldierEntity soldier, SoldierInventory inventory) {
        soldier.spawnAtLocation(createPouchFromInventory(inventory));
    }

    /** Ordered contents of a pouch stack (slot order preserved). */
    public static List<ItemStack> unpackContents(ItemStack pouch) {
        List<ItemStack> contents = new ArrayList<>();
        if (pouch.getTag() == null) return contents;
        ListTag list = extractItemsList(pouch.getTag());
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) contents.add(stack);
        }
        return contents;
    }

    public static int countContents(ItemStack pouch) {
        return unpackContents(pouch).size();
    }

    /** Restores pouch contents into a soldier, keeping the original slot layout. */
    public static void restoreIntoSoldier(SoldierEntity soldier, ItemStack pouch) {
        if (pouch.getTag() == null) return;
        SoldierInventory inv = soldier.getSoldierInventory();
        ListTag list = extractItemsList(pouch.getTag());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack stack = ItemStack.of(entry);
            if (stack.isEmpty()) continue;
            int slot = entry.getInt("Slot");
            if (inv.getItem(slot).isEmpty() && canPlaceDirect(slot, stack)) {
                inv.setItem(slot, stack);
            } else {
                ItemStack leftover = insertIntoGeneralSlots(inv, stack);
                if (!leftover.isEmpty()) soldier.spawnAtLocation(leftover);
            }
        }
        inv.syncArmorToEntity(soldier);
        SoldierWeaponSelector.normalizeGunSlots(soldier);
        NetworkHandler.sendToTracking(soldier,
            new SyncSoldierInventoryPacket(soldier.getId(), inv.save()));
    }

    /**
     * Direct slot placement only when the item semantically belongs there — the
     * GUI and the soldier inventory share one index space, so a player-filled
     * pouch has junk at indices 0–5 that must never be worn or auto-held (and
     * SoldierInventory silently refuses non-guns in the sidearm slot, which
     * would lose the item).
     */
    private static boolean canPlaceDirect(int slot, ItemStack stack) {
        if (slot < SoldierInventory.ARMOR_HEAD || slot >= SoldierInventory.INVENTORY_SIZE) {
            return false;
        }
        if (slot <= SoldierInventory.ARMOR_FEET) {
            EquipmentSlot expected = switch (slot) {
                case SoldierInventory.ARMOR_HEAD -> EquipmentSlot.HEAD;
                case SoldierInventory.ARMOR_CHEST -> EquipmentSlot.CHEST;
                case SoldierInventory.ARMOR_LEGS -> EquipmentSlot.LEGS;
                default -> EquipmentSlot.FEET;
            };
            return LivingEntity.getEquipmentSlotForItem(stack) == expected;
        }
        if (slot == SoldierInventory.SLOT_SIDEARM || slot == SoldierInventory.SLOT_MAIN_HAND) {
            return GunIntegration.isGun(stack);
        }
        return true; // general range
    }

    /** Merge-then-empty insertion over the general range; returns the leftover. */
    private static ItemStack insertIntoGeneralSlots(SoldierInventory inv, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)
                && existing.getCount() < existing.getMaxStackSize()) {
                int amount = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(amount);
                remaining.shrink(amount);
                if (remaining.isEmpty()) return ItemStack.EMPTY;
            }
        }
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (inv.getItem(slot).isEmpty()) {
                inv.setItem(slot, remaining);
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }

    @Nullable
    public static ItemStack getMarkedStack(Player player, UUID pouchId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() instanceof com.stevesarmy.item.SupplyPouchItem && hasPouchId(stack, pouchId)) {
                return stack;
            }
        }
        return null;
    }

    public static boolean hasPouchId(ItemStack stack, UUID pouchId) {
        return stack.getTag() != null && stack.getTag().hasUUID(POUCH_ID_TAG)
            && stack.getTag().getUUID(POUCH_ID_TAG).equals(pouchId);
    }
}

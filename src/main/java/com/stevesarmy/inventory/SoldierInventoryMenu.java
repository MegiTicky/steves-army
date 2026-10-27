package com.stevesarmy.inventory;

import com.mojang.datafixers.util.Pair;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.compat.curios.SoldierCurios;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.ModMenuTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

public class SoldierInventoryMenu extends AbstractContainerMenu {
    private static final int SOLDIER_MENU_SLOT_COUNT = SoldierInventory.INVENTORY_SIZE;
    private static final ResourceLocation[] TEXTURE_EMPTY_ARMOR_SLOTS = new ResourceLocation[]{
        InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
        InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
        InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
        InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
    };
    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Container soldierInventory;
    private final int soldierId;
    @Nullable
    private final SoldierEntity soldier;
    private final java.util.List<SoldierCurios.Descriptor> curiosDescriptors;
    private boolean curiosOpen;

    public SoldierInventoryMenu(int containerId, Inventory playerInventory, Container soldierInventory,
                                int soldierId, @Nullable SoldierEntity soldier) {
        this(containerId, playerInventory, soldierInventory, soldierId, soldier,
            soldier == null ? java.util.List.of() : SoldierCurios.describe(soldier));
    }

    public SoldierInventoryMenu(int containerId, Inventory playerInventory, Container soldierInventory,
                                int soldierId, @Nullable SoldierEntity soldier,
                                java.util.List<SoldierCurios.Descriptor> curiosDescriptors) {
        super(ModMenuTypes.SOLDIER_INVENTORY.get(), containerId);
        this.soldierInventory = soldierInventory;
        this.soldierId = soldierId;
        this.soldier = soldier;
        this.curiosDescriptors = java.util.List.copyOf(curiosDescriptors);

        addArmorSlots();
        addMainHandSlot();
        addGeneralSlots();
        var clientAccessories = new net.minecraft.world.SimpleContainer(this.curiosDescriptors.size());
        for (int i = 0; i < this.curiosDescriptors.size(); i++) {
            int x = -26 - (i / 8) * 18;
            int y = 24 + (i % 8) * 18;
            Slot backing = soldier == null ? new Slot(clientAccessories, i, x, y)
                : SoldierCurios.slot(soldier, this.curiosDescriptors.get(i), x, y);
            final var descriptor = this.curiosDescriptors.get(i);
            addSlot(new Slot(clientAccessories, i, x, y) {
                @Override public boolean isActive() { return curiosOpen && (soldier == null || SoldierCurios.available()); }
                @Override public ItemStack getItem() { return backing.getItem(); }
                @Override public void set(ItemStack stack) { backing.set(stack); }
                @Override public void setChanged() { backing.setChanged(); }
                @Override public ItemStack remove(int amount) { return backing.remove(amount); }
                @Override public boolean mayPlace(ItemStack stack) { return curiosOpen && (soldier == null || SoldierCurios.available()) && backing.mayPlace(stack); }
                @Override public boolean mayPickup(Player player) { return curiosOpen && (soldier == null || SoldierCurios.available()) && backing.mayPickup(player); }
                @Override public int getMaxStackSize() { return backing.getMaxStackSize(); }
                @Override public int getMaxStackSize(ItemStack stack) { return backing.getMaxStackSize(stack); }
                @Override public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return SoldierCurios.icon(descriptor, playerInventory.player.level());
                }
            });
        }
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
        // Sits on the texture's pre-existing second weapon slot graphic.
        this.addSlot(new Slot(soldierInventory, SoldierInventory.SLOT_SIDEARM, 44, 90) {
            @Override
            public int getMaxStackSize() { return 1; }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return GunIntegration.isGun(stack);
            }
        });
        this.addSlot(new Slot(soldierInventory, SoldierInventory.SLOT_MAIN_HAND, 26, 90) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return GunIntegration.isGun(stack);
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
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                int slotIndex = SoldierInventory.SLOT_GENERAL_START + col + row * 5;
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
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.isActive() && slot.hasItem() && slot.mayPickup(player)) {
            ItemStack slotItem = slot.getItem();
            result = slotItem.copy();

            int playerStart = SOLDIER_MENU_SLOT_COUNT + curiosDescriptors.size();
            if (index < playerStart) {
                if (!this.moveItemStackTo(slotItem, playerStart, this.slots.size(), true)) return ItemStack.EMPTY;
            } else {
                boolean moved = curiosOpen && playerStart > SOLDIER_MENU_SLOT_COUNT
                    && this.moveItemStackTo(slotItem, SOLDIER_MENU_SLOT_COUNT, playerStart, false);
                if (!slotItem.isEmpty()) moved |= this.moveItemStackTo(slotItem, 0, SOLDIER_MENU_SLOT_COUNT, false);
                if (!moved) return ItemStack.EMPTY;
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
        return this.soldierInventory.stillValid(player) && (soldier == null ||
            (soldier.isAlive() && !soldier.isRemoved() && curiosDescriptors.equals(SoldierCurios.describe(soldier))));
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if ((id != 0 && id != 1) || !stillValid(player)) return false;
        setCuriosOpen(id == 1);
        return true;
    }

    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType type, Player player) {
        if (slotId >= SOLDIER_MENU_SLOT_COUNT && slotId < SOLDIER_MENU_SLOT_COUNT + curiosDescriptors.size() && !curiosOpen) return;
        if (stillValid(player)) super.clicked(slotId, button, type, player);
    }

    @Override
    public void broadcastChanges() {
        if (soldier == null || (soldier.isAlive() && !soldier.isRemoved()
            && curiosDescriptors.equals(SoldierCurios.describe(soldier)))) super.broadcastChanges();
    }

    public java.util.List<SoldierCurios.Descriptor> getCuriosDescriptors() { return curiosDescriptors; }
    public int getAccessoryPanelWidth() { return curiosOpen && !curiosDescriptors.isEmpty() ? 16 + ((curiosDescriptors.size() + 7) / 8) * 18 : 0; }
    public boolean isCuriosOpen() { return curiosOpen && (soldier == null || SoldierCurios.available()); }
    public void setCuriosOpen(boolean open) { curiosOpen = open && !curiosDescriptors.isEmpty(); }

    @Override
    public void removed(Player player) {
        super.removed(player);
        
        if (!player.level().isClientSide && soldier != null && player instanceof ServerPlayer serverPlayer) {
            SyncSoldierInventoryPacket packet = new SyncSoldierInventoryPacket(
                soldier.getId(),
                soldier.getSoldierInventory().save()
            );
            NetworkHandler.sendTo(serverPlayer, packet);
            NetworkHandler.sendToTracking(soldier, packet);
        }
    }

    public int getSoldierId() {
        return soldierId;
    }

    public ItemStack getSoldierInventoryItem(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot < SoldierInventory.INVENTORY_SIZE) {
            // Soldier container slots and menu indices now align 1:1.
            return this.slots.get(inventorySlot).getItem();
        }
        return ItemStack.EMPTY;
    }
}

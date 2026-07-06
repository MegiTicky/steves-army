package com.stevesarmy.inventory;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

public class SoldierInventoryMenuProvider implements MenuProvider {
    private final SoldierEntity soldier;

    public SoldierInventoryMenuProvider(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("entity.steves_army.soldier");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new SoldierInventoryMenu(containerId, playerInventory, soldier.getSoldierInventory(), soldier.getId(), soldier);
    }

    public void writeExtraData(FriendlyByteBuf buf) {
        buf.writeInt(soldier.getId());
        SoldierInventory inv = soldier.getSoldierInventory();
        buf.writeInt(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            buf.writeBoolean(!stack.isEmpty());
            if (!stack.isEmpty()) {
                buf.writeItem(stack);
            }
        }
    }
}
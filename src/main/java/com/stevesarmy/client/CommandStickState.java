package com.stevesarmy.client;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.CommandStickItem;
import com.stevesarmy.item.CommandStickSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

public class CommandStickState {

    private static final CommandStickState INSTANCE = new CommandStickState();

    private boolean wasActive = false;

    public static CommandStickState get() {
        return INSTANCE;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack mainHand = mc.player.getMainHandItem();
        boolean active = CommandStickItem.isCommandStick(mainHand);

        if (wasActive && !active) {
            CommandStickSelection.clear();
        }
        wasActive = active;
    }

    public void onSelectionChanged() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int count = CommandStickSelection.getSelectedIds().size();
        String text = count == 0
            ? "Selection cleared"
            : "Selected " + count + " soldier" + (count == 1 ? "" : "s");
        mc.player.displayClientMessage(
            net.minecraft.network.chat.Component.literal(text), true);
    }

    public boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return CommandStickItem.isCommandStick(mc.player.getMainHandItem());
    }

    public CommandStickItem getHeldStick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand.getItem() instanceof CommandStickItem stick) {
            return stick;
        }
        return null;
    }

    public Set<Integer> getSelectedIds() {
        return CommandStickSelection.getSelectedIds();
    }

    public boolean isSelected(int entityId) {
        return CommandStickSelection.getSelectedIds().contains(entityId);
    }

    public boolean isTargetable(SoldierEntity soldier) {
        CommandStickItem stick = getHeldStick();
        if (stick == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return stick.isTargetable(soldier, mc.player);
    }
}

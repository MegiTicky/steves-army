package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.ModItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

public class SoldierHealGoal extends Goal {
    private static final float NORMAL_HEALTH_THRESHOLD = 0.50F;
    private static final int RETRY_COOLDOWN_TICKS = 40;

    private final SoldierEntity soldier;
    private int sourceSlot = -1;
    private int cooldownTicks;
    private boolean startedUsingItem;

    public SoldierHealGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        if (!soldier.isAlive() || soldier.isHealing() || soldier.isUsingItem()) return false;
        if (!soldier.getOffhandItem().isEmpty()) return false;
        if (soldier.isRecalling() || soldier.isInWaterOrBubble() || soldier.isOnFire() || soldier.isPassenger()) return false;
        if (GunIntegration.isTaczLoaded() && GunIntegration.isReloading(soldier)) return false;

        float healthFraction = soldier.getHealth() / soldier.getMaxHealth();
        if (healthFraction >= NORMAL_HEALTH_THRESHOLD) return false;
        if (!soldier.getCoverBehaviorManager().isInCover()) return false;
        if (!soldier.getPeekController().isIdleInCover()) return false;

        sourceSlot = findHealingItem();
        return sourceSlot >= SoldierInventory.SLOT_GENERAL_START;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive() || !soldier.isHealing()) return false;
        if (soldier.isRecalling() || soldier.isInWaterOrBubble() || soldier.isOnFire() || soldier.isPassenger()) return false;
        if (!soldier.getPeekController().isIdleInCover()) return false;
        return startedUsingItem && soldier.isUsingItem();
    }

    @Override
    public void start() {
        ItemStack selected = soldier.getSoldierInventory().removeItem(sourceSlot, 1);
        if (selected.isEmpty()) {
            cooldownTicks = RETRY_COOLDOWN_TICKS;
            sourceSlot = -1;
            return;
        }

        soldier.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, selected);
        soldier.setHealing(true);
        soldier.cancelCoverMovement();
        soldier.getNavigation().stop();
        syncInventory();
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }

        soldier.startUsingItem(InteractionHand.OFF_HAND);
        startedUsingItem = soldier.isUsingItem();
        if (!startedUsingItem) {
            restoreOffhandItem();
            soldier.setHealing(false);
            cooldownTicks = RETRY_COOLDOWN_TICKS;
        }
    }

    @Override
    public void tick() {
        soldier.getNavigation().stop();
    }

    @Override
    public void stop() {
        if (soldier.isUsingItem()) {
            soldier.stopUsingItem();
            cooldownTicks = RETRY_COOLDOWN_TICKS;
        }
        restoreOffhandItem();
        soldier.setHealing(false);
        sourceSlot = -1;
        startedUsingItem = false;
    }

    private int findHealingItem() {
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = soldier.getSoldierInventory().getItem(slot);
            if (stack.is(ModItemTags.SOLDIER_HEALING_ITEMS) && stack.getUseDuration() > 0) {
                return slot;
            }
        }
        return -1;
    }

    private void restoreOffhandItem() {
        ItemStack result = soldier.getOffhandItem();
        soldier.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (!result.isEmpty()) {
            returnToInventory(result);
        }
        syncInventory();
    }

    private void returnToInventory(ItemStack stack) {
        ItemStack originalSlot = soldier.getSoldierInventory().getItem(sourceSlot);
        if (originalSlot.isEmpty()) {
            soldier.getSoldierInventory().setItem(sourceSlot, stack);
            return;
        }
        if (ItemStack.isSameItemSameTags(originalSlot, stack)
            && originalSlot.getCount() + stack.getCount() <= originalSlot.getMaxStackSize()) {
            originalSlot.grow(stack.getCount());
            soldier.getSoldierInventory().setChanged();
            return;
        }
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (soldier.getSoldierInventory().getItem(slot).isEmpty()) {
                soldier.getSoldierInventory().setItem(slot, stack);
                return;
            }
        }
        soldier.spawnAtLocation(stack);
    }

    private void syncInventory() {
        NetworkHandler.sendToTracking(soldier, new SyncSoldierInventoryPacket(
            soldier.getId(), soldier.getSoldierInventory().save()
        ));
    }
}

package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public class SoldierRespawnManager {
    
    private static final float RESPAWN_HEALTH = 20.0F;
    private static final int RESPAWN_FOOD = 20;
    private static final double TELEPORT_OFFSET = 0.5;
    
    public static void initiateRespawn(ServerPlayer player, SoldierEntity soldier, SquadManager squadManager, SquadData squad, ServerLevel level) {
        UUID soldierUUID = soldier.getUUID();

        // Snapshot the seat before the discard destroys the transport state and
        // Create's seat mapping, and resolve a world-space position — soldiers
        // seated on VS ships live at shipyard coordinates server-side.
        VS2Compat.SeatCapture seatCapture = VS2Compat.captureSeatForTakeover(level, soldier);
        Vec3 soldierPos = VS2Compat.getSoldierWorldPosition(soldier);
        float soldierYRot = soldier.getYRot();
        float soldierXRot = soldier.getXRot();

        SoldierInventory soldierInventory = soldier.getSoldierInventory();

        player.setHealth(RESPAWN_HEALTH);
        player.getFoodData().setFoodLevel(RESPAWN_FOOD);

        transferEquipment(player, soldier, soldierInventory);

        soldier.stopRiding();
        CoverReservationManager.releaseAll(soldier);
        squadManager.removeMemberFromSquad(soldierUUID);
        soldier.discard();

        // Teleport target: the captured seat when available, else the soldier's
        // converted position. If neither is world-plausible (e.g. the ship was
        // scrapped mid-transition), stay at the vanilla respawn point instead
        // of teleporting into VS2's shipyard allocation region.
        Vec3 targetPos = seatCapture != null && VS2Compat.isWorldPlausible(seatCapture.worldPos())
            ? seatCapture.worldPos()
            : soldierPos;
        if (VS2Compat.isWorldPlausible(targetPos)) {
            player.teleportTo(targetPos.x, targetPos.y + TELEPORT_OFFSET, targetPos.z);
            player.setYRot(soldierYRot);
            player.setXRot(soldierXRot);
            player.setYHeadRot(soldierYRot);
        } else {
            StevesArmyMod.LOGGER.warn("[Respawn] No world-plausible position for soldier {} — player {} stays at respawn point",
                soldierUUID.toString().substring(0, 8), player.getName().getString());
        }

        if (seatCapture != null && seatCapture.hasSeat()
            && !VS2Compat.seatPlayerInCapturedSeat(level, player, seatCapture)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Your crew seat was lost — you respawn beside the vehicle instead."));
        }

        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("Respawned as soldier at " +
                String.format("%.1f, %.1f, %.1f", targetPos.x, targetPos.y, targetPos.z))
        );

        StevesArmyMod.LOGGER.info("[Respawn] Successfully transferred player {} to soldier position (seat taken over: {}). Squad size: {}",
            player.getName().getString(),
            seatCapture != null && seatCapture.hasSeat(),
            squad.getMemberCount());
    }
    
    private static void transferEquipment(ServerPlayer player, SoldierEntity soldier, SoldierInventory soldierInventory) {
        player.getInventory().clearContent();
        
        boolean foundMainHand = false;
        
        for (int i = 0; i < soldierInventory.getContainerSize(); i++) {
            ItemStack stack = soldierInventory.getItem(i).copy();
            if (stack.isEmpty()) continue;
            
            if (i == SoldierInventory.SLOT_MAIN_HAND) {
                player.getInventory().items.set(player.getInventory().selected, stack);
                foundMainHand = true;
            } else if (i >= SoldierInventory.ARMOR_HEAD && i <= SoldierInventory.ARMOR_FEET) {
                int armorIndex = i;
                EquipmentSlot slot = getArmorSlot(armorIndex);
                player.getInventory().armor.set(slot.getIndex(), stack);
            } else {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        
        if (!foundMainHand) {
            ItemStack mainHand = soldier.getMainHandItem().copy();
            if (!mainHand.isEmpty()) {
                StevesArmyMod.LOGGER.info("[Respawn] Main hand not in SoldierInventory, falling back to entity equipment: {}", mainHand.getItem());
                player.getInventory().items.set(player.getInventory().selected, mainHand);
            }
        }
        
        ItemStack transferredGun = player.getMainHandItem();
        if (GunIntegration.isAnyGunLoaded() && !transferredGun.isEmpty() && GunIntegration.hasGun(player)) {
            GunIntegration.refillMagazine(player);
            StevesArmyMod.LOGGER.info("[Respawn] Initializing TaCZ state for player's transferred gun");
            GunIntegration.initialData(player);
            GunIntegration.draw(player);
        }
        
        soldierInventory.clearContent();
        soldier.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        soldier.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                soldier.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }
    
    private static EquipmentSlot getArmorSlot(int index) {
        return switch (index) {
            case 0 -> EquipmentSlot.HEAD;
            case 1 -> EquipmentSlot.CHEST;
            case 2 -> EquipmentSlot.LEGS;
            case 3 -> EquipmentSlot.FEET;
            default -> EquipmentSlot.CHEST;
        };
    }
}

package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
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
        Vec3 soldierPos = soldier.position();
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
        
        player.teleportTo(soldierPos.x, soldierPos.y + TELEPORT_OFFSET, soldierPos.z);
        player.setYRot(soldierYRot);
        player.setXRot(soldierXRot);
        player.setYHeadRot(soldierYRot);
        
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("Respawned as soldier at " + 
                String.format("%.1f, %.1f, %.1f", soldierPos.x, soldierPos.y, soldierPos.z))
        );
        
        StevesArmyMod.LOGGER.info("[Respawn] Successfully transferred player {} to soldier position. Squad size: {}", 
            player.getName().getString(), 
            squad.getMemberCount());
    }
    
    private static void transferEquipment(ServerPlayer player, SoldierEntity soldier, SoldierInventory soldierInventory) {
        player.getInventory().clearContent();
        
        for (int i = 0; i < soldierInventory.getContainerSize(); i++) {
            ItemStack stack = soldierInventory.getItem(i).copy();
            if (stack.isEmpty()) continue;
            
            if (i == SoldierInventory.SLOT_MAIN_HAND) {
                player.getInventory().items.set(player.getInventory().selected, stack);
            } else if (i >= SoldierInventory.ARMOR_FEET && i <= SoldierInventory.ARMOR_HEAD) {
                int armorIndex = i;
                EquipmentSlot slot = getArmorSlot(armorIndex);
                player.getInventory().armor.set(slot.getIndex(), stack);
            } else {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
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
            case 0 -> EquipmentSlot.FEET;
            case 1 -> EquipmentSlot.LEGS;
            case 2 -> EquipmentSlot.CHEST;
            case 3 -> EquipmentSlot.HEAD;
            default -> EquipmentSlot.CHEST;
        };
    }
}
package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.UUID;
import java.util.function.Supplier;

public class SoldierSpawnEggItem extends ForgeSpawnEggItem {
    
    public SoldierSpawnEggItem(Supplier<? extends EntityType<? extends SoldierEntity>> type, int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] === SPAWN EGG USED ===");
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Stack tag present: {}", stack.hasTag());
        if (stack.hasTag()) {
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Stack tag: {}", stack.getTag().toString());
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Has EntityTag: {}", stack.getTag().contains("EntityTag"));
            if (stack.getTag().contains("EntityTag")) {
                CompoundTag entityTag = stack.getTag().getCompound("EntityTag");
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] EntityTag contents: {}", entityTag.toString());
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] EntityTag has Inventory: {}", entityTag.contains("Inventory"));
                if (entityTag.contains("Inventory")) {
                    CompoundTag invTag = entityTag.getCompound("Inventory");
                    StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Inventory tag: {}", invTag.toString());
                    StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Inventory Items count: {}", 
                        invTag.contains("Items") ? invTag.getList("Items", 10).size() : 0);
                }
            }
        }
        
        ServerLevel serverLevel = (ServerLevel) level;
        
        EntityType<?> entityType = this.getType(stack.getTag());
        if (entityType == null) {
            StevesArmyMod.LOGGER.warn("[SoldierSpawnEgg] EntityType is null!");
            return InteractionResult.FAIL;
        }
        
        SoldierEntity soldier = (SoldierEntity) entityType.create(serverLevel);
        if (soldier == null) {
            StevesArmyMod.LOGGER.warn("[SoldierSpawnEgg] Failed to create soldier entity!");
            return InteractionResult.FAIL;
        }
        
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Soldier created, UUID: {}", soldier.getUUID());
        
        CompoundTag stackTag = stack.getTag();
        boolean hasEntityTag = stackTag != null && stackTag.contains("EntityTag");
        
        if (hasEntityTag) {
            CompoundTag entityTag = stackTag.getCompound("EntityTag");
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Calling fillSoldierFromEntityTag...");
            fillSoldierFromEntityTag(soldier, entityTag, pos);
        } else {
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] No EntityTag, using default position");
            soldier.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        }
        
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] After fill, soldier inventory size: {}", soldier.getSoldierInventory().getContainerSize());
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] After fill, soldier inventory items:");
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!item.isEmpty()) {
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg]   Slot {}: {} x{}", i, item.getItem().toString(), item.getCount());
            }
        }
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] After fill, main hand item: {}", soldier.getMainHandItem().toString());
        
        if (!hasEntityTag || !stackTag.getCompound("EntityTag").hasUUID("Owner")) {
            if (player != null) {
                soldier.setOwnerUUID(player.getUUID());
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Set owner to player: {}", player.getUUID());
            }
        }

        SoldierSpawner.SpawnResult spawnResult = SoldierSpawner.finishSpawn(serverLevel, soldier, player, false);
        if (!spawnResult.success()) {
            return InteractionResult.FAIL;
        }
        
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Entity added to world");
        
        if (player != null && !player.isCreative()) {
            stack.shrink(1);
        }

        if (player != null && soldier.getSquadId() != null) {
            player.sendSystemMessage(Component.literal(
                "Soldier added to squad"
            ));
        }
        
        return InteractionResult.SUCCESS;
    }
    
    private void fillSoldierFromEntityTag(SoldierEntity soldier, CompoundTag entityTag, BlockPos pos) {
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] === fillSoldierFromEntityTag ===");
        StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Setting position to: {}", pos);
        soldier.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        
        if (entityTag.hasUUID("Owner")) {
            UUID owner = entityTag.getUUID("Owner");
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Restoring owner: {}", owner);
            soldier.setOwnerUUID(owner);
        } else {
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] No owner in EntityTag");
        }
        
        if (entityTag.contains("FollowState")) {
            int state = entityTag.getInt("FollowState");
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Restoring FollowState: {}", state);
            soldier.setFollowState(state);
        }
        
        if (entityTag.contains("SquadMode")) {
            int modeOrdinal = entityTag.getInt("SquadMode");
            SquadMode[] modes = SquadMode.values();
            if (modeOrdinal >= 0 && modeOrdinal < modes.length) {
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Restoring SquadMode: {} ({})", modeOrdinal, modes[modeOrdinal]);
                soldier.setSquadMode(modes[modeOrdinal]);
            }
        }
        
        if (entityTag.contains("HasSquad") && entityTag.getBoolean("HasSquad")) {
            if (entityTag.hasUUID("SquadId")) {
                UUID squadId = entityTag.getUUID("SquadId");
                StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Restoring SquadId: {}", squadId);
                soldier.setSquadId(squadId);
            }
        }
        
        if (entityTag.contains("HoldPos")) {
            BlockPos holdPos = BlockPos.of(entityTag.getLong("HoldPos"));
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Restoring HoldPos: {}", holdPos);
            soldier.setHoldPosition(holdPos);
        }
        
        if (entityTag.contains("Inventory")) {
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] === Restoring Inventory ===");
            CompoundTag inventoryTag = entityTag.getCompound("Inventory");
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Inventory tag: {}", inventoryTag.toString());
            SoldierInventory inventory = soldier.getSoldierInventory();
            inventory.load(inventoryTag);
            
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] Calling syncArmorToEntity...");
            inventory.syncArmorToEntity(soldier);
            
            StevesArmyMod.LOGGER.info("[SoldierSpawnEgg] After sync, checking soldier equipment:");
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack item = inventory.getItem(i);
                if (!item.isEmpty()) {
                    StevesArmyMod.LOGGER.info("[SoldierSpawnEgg]   Slot {}: {} x{}", i, item.getItem().toString(), item.getCount());
                }
            }
        } else {
            StevesArmyMod.LOGGER.warn("[SoldierSpawnEgg] No Inventory key in EntityTag!");
        }
    }
}

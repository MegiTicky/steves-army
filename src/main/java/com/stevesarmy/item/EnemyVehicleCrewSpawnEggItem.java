package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.EnemyVehicleCrewEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.lang.reflect.Method;

/**
 * Spawn egg for {@link EnemyVehicleCrewEntity}. Mirrors
 * {@link EnemySoldierSpawnEggItem}: use-on-block spawns a hostile crew
 * soldier, EntityTag restores the pick-block inventory, and an empty-handed
 * spawn gets a TaCZ AK47 with an infinite reserve. Ownership and squad are
 * never restored from the egg — the soldier is always hostile.
 */
public class EnemyVehicleCrewSpawnEggItem extends ForgeSpawnEggItem {

    private static final String AK47_GUN_ID = "tacz:ak47";

    public EnemyVehicleCrewSpawnEggItem(Properties props) {
        super(ModEntities.ENEMY_VEHICLE_CREW, 0xFF4444, 0x4682B4, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();

        ServerLevel serverLevel = (ServerLevel) level;

        EntityType<?> entityType = this.getType(stack.getTag());
        if (entityType == null) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] EntityType is null!");
            return InteractionResult.FAIL;
        }

        EnemySoldierEntity enemy = (EnemySoldierEntity) entityType.create(serverLevel);
        if (enemy == null) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] Failed to create enemy crew entity!");
            return InteractionResult.FAIL;
        }

        enemy.maybeRandomizeSkin();

        CompoundTag stackTag = stack.getTag();
        if (stackTag != null && stackTag.contains("EntityTag")) {
            CompoundTag entityTag = stackTag.getCompound("EntityTag");
            fillEnemyFromEntityTag(enemy, entityTag, pos);
        } else {
            enemy.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        }

        enemy.setPersistenceRequired();
        serverLevel.addFreshEntity(enemy);

        if (context.getPlayer() != null && !context.getPlayer().isCreative()) {
            stack.shrink(1);
        }

        ItemStack mainHand = enemy.getMainHandItem();
        if (mainHand.isEmpty()) {
            equipAk47(enemy);
        }
        enemy.configureInfiniteReserveAmmo();

        return InteractionResult.SUCCESS;
    }

    private void fillEnemyFromEntityTag(EnemySoldierEntity enemy, CompoundTag entityTag, BlockPos pos) {
        enemy.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);

        if (entityTag.contains("DefendPosition")) {
            enemy.setDefendPosition(BlockPos.of(entityTag.getLong("DefendPosition")));
        }

        if (entityTag.contains("Inventory")) {
            CompoundTag inventoryTag = entityTag.getCompound("Inventory");
            SoldierInventory inventory = enemy.getSoldierInventory();
            inventory.load(inventoryTag);
            inventory.syncArmorToEntity(enemy);
        }
    }

    private void equipAk47(EnemySoldierEntity enemy) {
        if (!GunIntegration.isTaczLoaded()) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] TaCZ not loaded, cannot equip AK47");
            return;
        }

        try {
            ResourceLocation gunId = new ResourceLocation("tacz", "ak47");

            Class<?> builderClass = Class.forName("com.tacz.guns.api.item.builder.GunItemBuilder");
            Method create = builderClass.getMethod("create");
            Object builder = create.invoke(null);

            builderClass.getMethod("setId", ResourceLocation.class).invoke(builder, gunId);

            Object gunObj;
            Object indexOpt = Class.forName("com.tacz.guns.api.TimelessAPI")
                .getMethod("getCommonGunIndex", ResourceLocation.class)
                .invoke(null, gunId);

            if (indexOpt instanceof java.util.Optional<?> opt && opt.isPresent()) {
                gunObj = builderClass.getMethod("build").invoke(builder);
            } else {
                gunObj = builderClass.getMethod("forceBuild").invoke(builder);
            }

            if (!(gunObj instanceof ItemStack gunStack) || gunStack.isEmpty()) {
                StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] Failed to create AK47 gun stack");
                return;
            }

            enemy.setItemSlot(EquipmentSlot.MAINHAND, gunStack.copy());
            GunIntegration.refillMagazine(enemy);
            GunIntegration.initialData(enemy);
            GunIntegration.draw(enemy);

            StevesArmyMod.LOGGER.info("[EnemyCrewEgg] Equipped AK47 to enemy crew soldier {}", enemy.getId());
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] Failed to equip AK47: {}", e.toString());
        }
    }
}

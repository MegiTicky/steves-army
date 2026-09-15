package com.stevesarmy.item;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.EnemyAntiTankEntity;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

/**
 * Spawn egg for {@link EnemyAntiTankEntity}. Mirrors
 * {@link EnemySoldierSpawnEggItem}: use-on-block spawns a hostile AT soldier,
 * EntityTag restores the pick-block inventory, and an empty-handed spawn gets
 * a TaCZ RPG7 in the main hand plus an AK47 in the sidearm slot with an
 * infinite reserve. Ownership and squad are never restored from the egg — the
 * soldier is always hostile.
 */
public class EnemyAntiTankSpawnEggItem extends ForgeSpawnEggItem {

    private static final String RPG_GUN_ID = "tacz:rpg7";
    private static final String SIDEARM_GUN_ID = "tacz:ak47";

    public EnemyAntiTankSpawnEggItem(Properties props) {
        super(ModEntities.ENEMY_ANTI_TANK, 0xFF4444, 0xB8860B, props);
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
            return InteractionResult.FAIL;
        }
        if (!(entityType.create(serverLevel) instanceof EnemyAntiTankEntity enemy)) {
            return InteractionResult.FAIL;
        }

        enemy.maybeRandomizeSkin();
        CompoundTag stackTag = stack.getTag();
        if (stackTag != null && stackTag.contains("EntityTag")) {
            fillEnemyFromEntityTag(enemy, stackTag.getCompound("EntityTag"), pos);
        } else {
            enemy.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        }

        enemy.setPersistenceRequired();
        serverLevel.addFreshEntity(enemy);

        if (context.getPlayer() != null && !context.getPlayer().isCreative()) {
            stack.shrink(1);
        }

        if (enemy.getMainHandItem().isEmpty()) {
            equipAtLoadout(enemy);
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

    private void equipAtLoadout(EnemyAntiTankEntity enemy) {
        ItemStack rpg = AntiTankSpawnEggItem.buildTaCZGun(RPG_GUN_ID);
        if (rpg == null) {
            return;
        }
        enemy.setItemSlot(EquipmentSlot.MAINHAND, rpg);
        GunIntegration.refillMagazine(enemy);
        GunIntegration.initialData(enemy);
        GunIntegration.draw(enemy);

        ItemStack sidearm = AntiTankSpawnEggItem.buildTaCZGun(SIDEARM_GUN_ID);
        if (sidearm != null) {
            SoldierInventory inv = enemy.getSoldierInventory();
            if (inv != null && inv.getItem(SoldierInventory.SLOT_SIDEARM).isEmpty()) {
                inv.setItem(SoldierInventory.SLOT_SIDEARM, sidearm);
            }
        }
    }
}

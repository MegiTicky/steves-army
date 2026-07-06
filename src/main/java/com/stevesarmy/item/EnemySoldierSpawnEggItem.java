package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.lang.reflect.Method;

public class EnemySoldierSpawnEggItem extends ForgeSpawnEggItem {

    private static final String AK47_GUN_ID = "tacz:ak47";

    public EnemySoldierSpawnEggItem(Properties props) {
        super(ModEntities.ENEMY_SOLDIER, 0xFF4444, 0x444444, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();

        Entity entity = this.getType(stack.getTag()).spawn(
            (ServerLevel) level, stack, context.getPlayer(), pos,
            net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, true, true
        );

        if (entity instanceof EnemySoldierEntity enemy) {
            equipAk47(enemy);
        }

        return InteractionResult.CONSUME;
    }

    private void equipAk47(EnemySoldierEntity enemy) {
        if (!GunIntegration.isTaczLoaded()) {
            StevesArmyMod.LOGGER.warn("[EnemySpawnEgg] TaCZ not loaded, cannot equip AK47");
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
                StevesArmyMod.LOGGER.warn("[EnemySpawnEgg] Failed to create AK47 gun stack");
                return;
            }

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Object iGun = iGunClass.getMethod("getIGunOrNull", ItemStack.class).invoke(null, gunStack);
            if (iGun != null) {
                iGunClass.getMethod("setCurrentAmmoCount", ItemStack.class, int.class).invoke(iGun, gunStack, 999);
                iGunClass.getMethod("setBulletInBarrel", ItemStack.class, boolean.class).invoke(iGun, gunStack, true);
            }

            enemy.setItemSlot(EquipmentSlot.MAINHAND, gunStack.copy());
            GunIntegration.initialData(enemy);
            GunIntegration.draw(enemy);

            StevesArmyMod.LOGGER.info("[EnemySpawnEgg] Equipped AK47 to enemy soldier {}", enemy.getId());
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[EnemySpawnEgg] Failed to equip AK47: {}", e.toString());
        }
    }
}
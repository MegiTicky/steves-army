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
import java.util.Optional;

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
            ResourceLocation gunId = ResourceLocation.of(AK47_GUN_ID, ':');

            Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
            Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
            Object indexOpt = getCommonGunIndex.invoke(null, gunId);

            if (!(indexOpt instanceof Optional<?> opt) || opt.isEmpty()) {
                StevesArmyMod.LOGGER.warn("[EnemySpawnEgg] Gun '{}' not found in TaCZ registry", AK47_GUN_ID);
                return;
            }

            Object gunIndex = opt.get();
            Method getDefaultItemStack = gunIndex.getClass().getMethod("getDefaultItemStack");
            Object defaultStackObj = getDefaultItemStack.invoke(gunIndex);

            if (defaultStackObj instanceof ItemStack gunStack) {
                Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
                Object iGun = getIGunOrNull.invoke(null, gunStack);

                if (iGun != null) {
                    Method setAmmoInBarrel = iGunClass.getMethod("setAmmoInBarrel", ItemStack.class, boolean.class);
                    setAmmoInBarrel.invoke(iGun, gunStack, true);

                    Method getGunData = gunIndex.getClass().getMethod("getGunData");
                    Object gunData = getGunData.invoke(gunIndex);
                    Method getMagazineSize = gunData.getClass().getMethod("getMagazineSize");
                    int magSize = (int) getMagazineSize.invoke(gunData);

                    Method setAmmoCount = iGunClass.getMethod("setAmmoCount", ItemStack.class, int.class);
                    setAmmoCount.invoke(iGun, gunStack, magSize);
                }

                enemy.setItemSlot(EquipmentSlot.MAINHAND, gunStack.copy());
                GunIntegration.initialData(enemy);
                GunIntegration.draw(enemy);

                StevesArmyMod.LOGGER.info("[EnemySpawnEgg] Equipped AK47 to enemy soldier {}", enemy.getId());
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[EnemySpawnEgg] Failed to equip AK47: {}", e.getMessage());
            e.printStackTrace();
        }
    }
}
package com.stevesarmy.item;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.AntiTankEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.entity.SoldierSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Spawn egg for {@link AntiTankEntity}. Mirrors
 * {@link MachineGunnerSpawnEggItem}: use-on-block spawns a friendly AT
 * soldier, EntityTag restores the pick-block inventory. An empty-handed spawn
 * is armed when TaCZ is loaded — RPG7 in the main hand, AK47 in the sidearm
 * slot — so the role is testable immediately; the magazine is the soldier's
 * initial rocket supply and resupply works through the normal squad systems.
 */
public class AntiTankSpawnEggItem extends ForgeSpawnEggItem {

    private static final String RPG_GUN_ID = "tacz:rpg7";
    private static final String SIDEARM_GUN_ID = "tacz:ak47";

    public AntiTankSpawnEggItem(Supplier<? extends EntityType<? extends AntiTankEntity>> type,
                                int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        AntiTankEntity soldier = ModEntities.ANTI_TANK.get().create((ServerLevel) level);
        if (soldier == null) {
            return InteractionResult.FAIL;
        }
        soldier.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
            player != null ? player.getYRot() : 0.0F, 0.0F);
        soldier.maybeRandomizeSkin();

        CompoundTag entityTag = getEntityTag(context.getItemInHand());
        if (entityTag != null) {
            soldier.fillFromPickBlockData(entityTag);
        }

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn((ServerLevel) level, soldier, player, true);
        if (!result.success()) {
            return InteractionResult.FAIL;
        }

        if (soldier.getMainHandItem().isEmpty()) {
            equipAtLoadout(soldier);
        }

        if (player != null && !player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private CompoundTag getEntityTag(net.minecraft.world.item.ItemStack stack) {
        if (stack.getTag() != null && stack.getTag().contains("EntityTag")) {
            return stack.getTag().getCompound("EntityTag");
        }
        return null;
    }

    /** Builds a TaCZ gun stack via the public GunItemBuilder API, or null when unavailable. */
    static ItemStack buildTaCZGun(String gunId) {
        if (!GunIntegration.isTaczLoaded()) {
            return null;
        }
        try {
            ResourceLocation id = new ResourceLocation("tacz", gunId.substring(gunId.indexOf(':') + 1));

            Class<?> builderClass = Class.forName("com.tacz.guns.api.item.builder.GunItemBuilder");
            Object builder = builderClass.getMethod("create").invoke(null);
            builderClass.getMethod("setId", ResourceLocation.class).invoke(builder, id);

            Object gunObj;
            Object indexOpt = Class.forName("com.tacz.guns.api.TimelessAPI")
                .getMethod("getCommonGunIndex", ResourceLocation.class)
                .invoke(null, id);
            if (indexOpt instanceof java.util.Optional<?> opt && opt.isPresent()) {
                gunObj = builderClass.getMethod("build").invoke(builder);
            } else {
                gunObj = builderClass.getMethod("forceBuild").invoke(builder);
            }
            return gunObj instanceof ItemStack gunStack && !gunStack.isEmpty() ? gunStack.copy() : null;
        } catch (Exception e) {
            com.stevesarmy.StevesArmyMod.LOGGER.warn("[AtSpawnEgg] Failed to build TaCZ gun {}: {}", gunId, e.toString());
            return null;
        }
    }

    private void equipAtLoadout(AntiTankEntity soldier) {
        ItemStack rpg = buildTaCZGun(RPG_GUN_ID);
        if (rpg == null) {
            return;
        }
        soldier.setItemSlot(EquipmentSlot.MAINHAND, rpg);
        GunIntegration.refillMagazine(soldier);
        GunIntegration.initialData(soldier);
        GunIntegration.draw(soldier);

        ItemStack sidearm = buildTaCZGun(SIDEARM_GUN_ID);
        if (sidearm != null) {
            SoldierInventory inv = soldier.getSoldierInventory();
            if (inv != null && inv.getItem(SoldierInventory.SLOT_SIDEARM).isEmpty()) {
                inv.setItem(SoldierInventory.SLOT_SIDEARM, sidearm);
            }
        }
    }
}

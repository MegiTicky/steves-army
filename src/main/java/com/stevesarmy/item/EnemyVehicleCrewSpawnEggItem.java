package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.EnemyVehicleCrewEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.transport.CrewAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
    private static final double SEAT_REACH = 6.0;

    public EnemyVehicleCrewSpawnEggItem(Properties props) {
        super(ModEntities.ENEMY_VEHICLE_CREW, 0xFF4444, 0x4682B4, props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = player.isShiftKeyDown() ? findAimHit(player) : null;
        if (hit == null) {
            return super.use(level, player, hand);
        }
        if (!level.isClientSide) {
            spawnEnemyCrewOnSeat((ServerLevel) level, hit.getLocation(), hit.getBlockPos(), player, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            BlockHitResult hit = findAimHit(player);
            if (hit != null) {
                return spawnEnemyCrewOnSeat((ServerLevel) level, hit.getLocation(), hit.getBlockPos(),
                    player, context.getItemInHand());
            }
        }
        return spawnStandingEnemyCrew((ServerLevel) level, context.getClickLocation(),
            player, context.getItemInHand());
    }

    private static BlockHitResult findAimHit(Player player) {
        HitResult hit = player.pick(SEAT_REACH, 1.0F, false);
        return hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
            ? blockHit : null;
    }

    private InteractionResult spawnStandingEnemyCrew(ServerLevel level, Vec3 pos, Player player, ItemStack stack) {
        EnemyVehicleCrewEntity enemy = createEnemyCrew(level, pos, stack);
        if (enemy == null) {
            return InteractionResult.FAIL;
        }
        finishSpawn(level, enemy, player, stack);
        return InteractionResult.SUCCESS;
    }

    /** Spawn a hostile crewman and seat it through the same shared crew mount path. */
    public InteractionResult spawnEnemyCrewOnSeat(ServerLevel level, Vec3 anchor, BlockPos hitBlock,
                                                   Player player, ItemStack stack) {
        EnemyVehicleCrewEntity enemy = createEnemyCrew(level, anchor, stack);
        if (enemy == null) {
            return InteractionResult.FAIL;
        }
        finishSpawn(level, enemy, player, stack);

        Object ship = VS2Compat.getShipObjectAtBlockPos(level, hitBlock);
        if (ship == null) {
            ship = VS2Compat.resolveShipAtWorldAnchor(level, anchor);
        }
        if (ship == null && player instanceof ServerPlayer serverPlayer) {
            ship = VS2Compat.resolveMountShipNearPlayer(level, serverPlayer);
        }
        int seated = ship != null && CrewAssignment.seatAtExactPosition(level, ship, hitBlock, enemy) ? 1 : 0;
        if (seated == 0 && ship != null) {
            seated = CrewAssignment.mountCrewOnShip(level, ship, anchor, java.util.List.of(enemy));
        }
        player.displayClientMessage(Component.translatable(seated > 0
            ? "transport.steves_army.feedback.enemy_crew_deployed"
            : "transport.steves_army.feedback.enemy_crew_standing"), true);
        return InteractionResult.SUCCESS;
    }

    private EnemyVehicleCrewEntity createEnemyCrew(ServerLevel level, Vec3 pos, ItemStack stack) {
        EntityType<?> entityType = this.getType(stack.getTag());
        if (entityType == null) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] EntityType is null!");
            return null;
        }
        if (!(entityType.create(level) instanceof EnemyVehicleCrewEntity enemy)) {
            StevesArmyMod.LOGGER.warn("[EnemyCrewEgg] Refused non-vehicle-crew EntityType {}", entityType);
            return null;
        }

        enemy.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        enemy.maybeRandomizeSkin();
        CompoundTag stackTag = stack.getTag();
        if (stackTag != null && stackTag.contains("EntityTag")) {
            fillEnemyFromEntityTag(enemy, stackTag.getCompound("EntityTag"));
        }
        return enemy;
    }

    private void finishSpawn(ServerLevel level, EnemyVehicleCrewEntity enemy, Player player, ItemStack stack) {
        enemy.setPersistenceRequired();
        level.addFreshEntity(enemy);
        if (player != null && !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        if (enemy.getMainHandItem().isEmpty()) {
            equipAk47(enemy);
        }
        enemy.configureInfiniteReserveAmmo();
    }

    private void fillEnemyFromEntityTag(EnemySoldierEntity enemy, CompoundTag entityTag) {

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

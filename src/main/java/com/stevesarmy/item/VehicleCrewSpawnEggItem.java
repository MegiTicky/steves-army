package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.VehicleCrewEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.transport.CrewAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeSpawnEggItem;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * Vehicle crew spawn egg. Plain right-click spawns a free-standing crew soldier.
 * Sneak-right-click while aiming at the ship spawns the crew directly seated near the
 * aimed block (ships hold SeatBlocks, not pickable seat entities — Create discards an
 * empty seat's entity — so the click raytraces blocks with VS2's ship-aware clip).
 *
 * Both paths restore the pick-block EntityTag (inventory loadout, tactical state) the
 * same way {@link SoldierSpawnEggItem} does for infantry — vanilla ForgeSpawnEggItem
 * spawning would drop the mod's SoldierInventory. Ownership and squad are never
 * restored from the egg: whoever spawns it becomes the owner.
 */
public class VehicleCrewSpawnEggItem extends ForgeSpawnEggItem {

    private static final double SEAT_REACH = 6.0;

    public VehicleCrewSpawnEggItem(Supplier<? extends EntityType<? extends VehicleCrewEntity>> type,
                                   int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Superb Warfare vehicle under the crosshair: spawn the crew seated on it.
        // The vehicle pick is an ENTITY hit, which findAimHit's block filter drops.
        if (player.isShiftKeyDown()) {
            Entity vehicle = findAimVehicle(player);
            if (vehicle != null) {
                if (!level.isClientSide) {
                    spawnCrewOnVehicle((ServerLevel) level, vehicle, player, stack);
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }
        }
        BlockHitResult hit = player.isShiftKeyDown() ? findAimHit(player) : null;
        if (hit != null) {
            if (!level.isClientSide) {
                spawnCrewOnSeat((ServerLevel) level, hit.getLocation(), hit.getBlockPos(), player, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (!level.isClientSide) {
            spawnCrewStanding((ServerLevel) level, player.position(), player, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            BlockHitResult hit = findAimHit(player);
            if (hit != null) {
                if (!level.isClientSide) {
                    spawnCrewOnSeat((ServerLevel) level, hit.getLocation(), hit.getBlockPos(),
                        player, context.getItemInHand());
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        if (player != null && !level.isClientSide) {
            spawnCrewStanding((ServerLevel) level, context.getClickLocation(), player, context.getItemInHand());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Crosshair block hit, exactly the ray {@code /vs get-ship} uses: the vanilla clip,
     * which VS2 makes ship-aware in world space.
     */
    @Nullable
    private static BlockHitResult findAimHit(Player player) {
        HitResult hit = player.pick(SEAT_REACH, 1.0F, false);
        return hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
            ? blockHit : null;
    }

    /** Operational Superb Warfare vehicle under the crosshair, or null. */
    @Nullable
    private static Entity findAimVehicle(Player player) {
        if (!SbwCompat.isEnabled()) {
            return null;
        }
        HitResult hit = player.pick(SEAT_REACH, 1.0F, false);
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit
            && SbwCompat.isOperational(entityHit.getEntity())) {
            return entityHit.getEntity();
        }
        return null;
    }

    /**
     * Server-side "spawn crew seated on this Superb Warfare vehicle": the entity
     * analogue of {@link #spawnCrewOnSeat}, used by the egg's sneak-use on a
     * vehicle and by the right-click hook in CrewSeatInteractHandler.
     */
    public static void spawnCrewOnVehicle(ServerLevel level, Entity vehicle,
                                          Player player, ItemStack eggStack) {
        CompoundTag entityTag = eggEntityTag(eggStack);
        VehicleCrewEntity crew = createCrew(level, vehicle.position(), player, entityTag);
        if (crew == null) {
            return;
        }
        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(level, crew, player, true);
        if (!result.success()) {
            return;
        }
        int seated = CrewAssignment.mountCrewOnVehicle(level, vehicle, List.of(crew));
        if (seated == 0) {
            // Left standing at the vehicle: the unmounted crew goal walks it to a station.
            StevesArmyMod.LOGGER.warn("[Crew] spawned crew={} could not mount vehicle={}",
                crew.getId(), vehicle.getId());
        }
        if (!player.isCreative()) {
            eggStack.shrink(1);
        }
        player.displayClientMessage(Component.translatable(seated > 0
            ? "transport.steves_army.feedback.crew_deployed"
            : "transport.steves_army.feedback.crew_standing"), true);
    }

    /** EntityTag carried by the egg stack, or null. */
    @Nullable
    private static CompoundTag eggEntityTag(ItemStack eggStack) {
        return eggStack.getTag() != null && eggStack.getTag().contains("EntityTag")
            ? eggStack.getTag().getCompound("EntityTag") : null;
    }

    /**
     * Creates the crew entity and restores pick-block state (owner, squad, inventory
     * loadout) — the shared front half of both spawn paths.
     */
    @Nullable
    private static VehicleCrewEntity createCrew(ServerLevel level, Vec3 pos, Player player,
                                                @Nullable CompoundTag entityTag) {
        VehicleCrewEntity crew = ModEntities.VEHICLE_CREW.get().create(level);
        if (crew == null) {
            return null;
        }
        crew.moveTo(pos.x, pos.y, pos.z, player.getYRot(), 0.0F);
        crew.maybeRandomizeSkin();
        if (entityTag != null) {
            crew.fillFromPickBlockData(entityTag);
        }
        return crew;
    }

    /** Non-sneak spawn: identical EntityTag handling to the infantry egg. */
    private static void spawnCrewStanding(ServerLevel level, Vec3 pos, Player player, ItemStack eggStack) {
        VehicleCrewEntity crew = createCrew(level, pos, player, eggEntityTag(eggStack));
        if (crew == null) {
            return;
        }
        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(level, crew, player, true);
        if (!result.success()) {
            return;
        }
        if (!player.isCreative()) {
            eggStack.shrink(1);
        }
    }

    /** Shared server-side "spawn crew seated here" used by the egg item and the seat interact handler. */
    public static void spawnCrewOnSeat(ServerLevel level, Vec3 anchor, BlockPos hitBlock,
                                       Player player, ItemStack eggStack) {
        spawnCrewOnSeat(level, anchor, hitBlock, player, eggStack, null);
    }

    /**
     * Seat spawn with an optional recorded EntityTag used when the egg stack carries
     * none of its own — the VSAW vehicle setup block replays crew spawns this way,
     * passing the inventory loadout recorded at record time.
     */
    public static void spawnCrewOnSeat(ServerLevel level, Vec3 anchor, BlockPos hitBlock,
                                       Player player, ItemStack eggStack,
                                       @Nullable CompoundTag recordedEntityTag) {
        CompoundTag entityTag = eggEntityTag(eggStack);
        if (entityTag == null) {
            entityTag = recordedEntityTag;
        }
        VehicleCrewEntity crew = createCrew(level, anchor, player, entityTag);
        if (crew == null) {
            return;
        }

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(level, crew, player, true);
        if (!result.success()) {
            return;
        }
        // Ship resolution in /vs get-ship order: the ship managing the exact hit block
        // wins; geometric resolver and near-player search stay as fallbacks.
        Object ship = VS2Compat.getShipObjectAtBlockPos(level, hitBlock);
        if (ship != null) {
            StevesArmyMod.LOGGER.info("[Crew] egg ship resolved via vs get-ship at hit block {}: shipId={}",
                hitBlock, VS2Compat.getShipIdOf(ship));
        }
        if (ship == null) {
            ship = VS2Compat.resolveShipAtWorldAnchor(level, anchor);
        }
        if (ship == null && player instanceof ServerPlayer serverPlayer) {
            ship = VS2Compat.resolveMountShipNearPlayer(level, serverPlayer);
        }
        // Exact seat first: the aimed/recorded block, so aiming at a seat seats exactly
        // there. Every miss falls through to the tiered mount below (unchanged).
        int seated = ship != null && CrewAssignment.seatAtExactPosition(level, ship, hitBlock, crew) ? 1 : 0;
        if (seated == 0) {
            seated = ship == null ? 0 : CrewAssignment.mountCrewOnShip(level, ship, anchor, List.of(crew));
        }
        if (seated == 0) {
            // Left standing at the anchor: the unmounted crew goal walks it to a station.
            StevesArmyMod.LOGGER.warn("[Crew] spawned crew={} could not mount near anchor {}",
                crew.getId(), anchor);
        }
        if (!player.isCreative()) {
            eggStack.shrink(1);
        }
        player.displayClientMessage(Component.translatable(seated > 0
            ? "transport.steves_army.feedback.crew_deployed"
            : "transport.steves_army.feedback.crew_standing"), true);
    }
}

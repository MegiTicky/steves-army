package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.VehicleCrewEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.transport.CrewAssignment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
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
        Vec3 anchor = player.isShiftKeyDown() ? findAimAnchor(player) : null;
        if (anchor != null) {
            if (!level.isClientSide) {
                spawnCrewOnSeat((ServerLevel) level, anchor, player, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player != null && player.isShiftKeyDown()) {
            Vec3 anchor = findAimAnchor(player);
            if (anchor != null) {
                if (!level.isClientSide) {
                    spawnCrewOnSeat((ServerLevel) level, anchor, player, context.getItemInHand());
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useOn(context);
    }

    /** World-space anchor: ship-aware block hit within reach, or null. */
    @Nullable
    private static Vec3 findAimAnchor(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(SEAT_REACH));
        return VS2Compat.getShipAwareBlockHitLocation(player.level(), eye, end, player);
    }

    /** Shared server-side "spawn crew seated here" used by the egg item and the seat interact handler. */
    public static void spawnCrewOnSeat(ServerLevel level, Vec3 anchor, Player player, ItemStack eggStack) {
        VehicleCrewEntity crew = ModEntities.VEHICLE_CREW.get().create(level);
        if (crew == null) {
            return;
        }
        crew.moveTo(anchor.x, anchor.y, anchor.z, player.getYRot(), 0.0F);
        crew.maybeRandomizeSkin();

        CompoundTag entityTag = eggStack.getTag() != null && eggStack.getTag().contains("EntityTag")
            ? eggStack.getTag().getCompound("EntityTag") : null;
        if (entityTag != null) {
            crew.fillFromPickBlockData(entityTag);
        }

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn(level, crew, player, false);
        if (!result.success()) {
            return;
        }
        // Seat through the shared crew routine on the ship at the anchor — the
        // soldier flow: ship + anchor position, free SeatBlock scan, never a
        // specific seat entity. The resolver reconciles the shipyard-chunk
        // lookup with world-space intersection, same as the crew stick.
        Object ship = VS2Compat.resolveShipAtWorldAnchor(level, anchor);
        if (ship == null && player instanceof ServerPlayer serverPlayer) {
            ship = VS2Compat.resolveMountShipNearPlayer(level, serverPlayer);
        }
        int seated = ship == null ? 0 : CrewAssignment.mountCrewOnShip(level, ship, anchor, List.of(crew));
        if (seated == 0) {
            // Left standing at the anchor: the unmounted crew goal walks it to a station.
            StevesArmyMod.LOGGER.warn("[Crew] spawned crew={} could not mount near anchor {}",
                crew.getId(), anchor);
        }
        if (!player.isCreative()) {
            eggStack.shrink(1);
        }
        player.displayClientMessage(
            Component.translatable("transport.steves_army.feedback.crew_deployed"), true);
    }
}

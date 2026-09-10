package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.VehicleCrewEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeSpawnEggItem;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Vehicle crew spawn egg. Plain right-click spawns a free-standing crew soldier.
 * Sneak-right-click while aiming at a Create/VSAW seat spawns the crew directly
 * seated on it (Create SeatEntities are not pickable, so both {@link #use} and
 * {@link #useOn} raytrace the view ray for a seat).
 */
public class VehicleCrewSpawnEggItem extends ForgeSpawnEggItem {

    public VehicleCrewSpawnEggItem(Supplier<? extends EntityType<? extends VehicleCrewEntity>> type,
                                   int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Entity seat = player.isShiftKeyDown() ? findSeatAlongLook(player) : null;
        if (seat != null) {
            if (!level.isClientSide) {
                spawnCrewOnSeat((ServerLevel) level, seat, player, stack);
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
            Entity seat = findSeatAlongLook(player);
            if (seat != null) {
                if (!level.isClientSide) {
                    spawnCrewOnSeat((ServerLevel) level, seat, player, context.getItemInHand());
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useOn(context);
    }

    @Nullable
    private static Entity findSeatAlongLook(Player player) {
        return CrewAssignStickItem.findSeatAlongLook(player);
    }

    /** Shared server-side "spawn crew seated here" used by the egg item and the seat interact handler. */
    public static void spawnCrewOnSeat(ServerLevel level, Entity seat, Player player, ItemStack eggStack) {
        if (!seat.getPassengers().isEmpty()) {
            player.displayClientMessage(
                Component.translatable("transport.steves_army.feedback.crew_seat_occupied"), true);
            return;
        }
        VehicleCrewEntity crew = ModEntities.VEHICLE_CREW.get().create(level);
        if (crew == null) {
            return;
        }
        Vec3 worldPos = VS2Compat.getSeatWorldPosition(seat);
        crew.moveTo(worldPos.x, worldPos.y, worldPos.z, player.getYRot(), 0.0F);
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
        if (!VS2Compat.seatSoldierOnSeatEntity(crew, seat)) {
            // Left standing at the seat: the unmounted crew goal walks it to a station.
            StevesArmyMod.LOGGER.warn("[Crew] spawned crew={} could not mount seat={}", crew.getId(), seat.getId());
        }
        if (!player.isCreative()) {
            eggStack.shrink(1);
        }
        player.displayClientMessage(
            Component.translatable("transport.steves_army.feedback.crew_deployed"), true);
    }
}

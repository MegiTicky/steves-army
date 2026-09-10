package com.stevesarmy.interact;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.VehicleCrewEntity;
import com.stevesarmy.item.CommandStickSelection;
import com.stevesarmy.item.CrewAssignStickItem;
import com.stevesarmy.item.VehicleCrewSpawnEggItem;
import com.stevesarmy.network.CommandStickAssignCrewPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-click interactions on Create/VSAW seat entities:
 * <ul>
 *   <li>Crew assign stick + sneak: assign the selected crew to the clicked seat and the
 *       ship's remaining free seats (packet does the server work).</li>
 *   <li>Vehicle crew spawn egg + sneak: spawn a crew soldier directly onto the seat;
 *       duty assignment picks up a station automatically.</li>
 * </ul>
 * Both cancel the event so the player never sits on the seat and the click does not
 * fall through to the stick's reposition action.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class CrewSeatInteractHandler {
    private CrewSeatInteractHandler() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Entity target = event.getTarget();
        if (!VS2Compat.isCreateSeatEntity(target)) {
            return;
        }
        Player player = event.getEntity();
        ItemStack mainHand = player.getMainHandItem();
        if (!player.isShiftKeyDown()) {
            return;
        }

        if (mainHand.getItem() instanceof CrewAssignStickItem) {
            if (event.getLevel().isClientSide) {
                List<Integer> selected = new ArrayList<>(CommandStickSelection.getSelectedIds());
                if (selected.isEmpty()) {
                    player.displayClientMessage(Component.literal("No crew selected"), true);
                } else {
                    NetworkHandler.INSTANCE.sendToServer(new CommandStickAssignCrewPacket(target.getId(), selected));
                }
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (mainHand.getItem() instanceof VehicleCrewSpawnEggItem) {
            if (!event.getLevel().isClientSide) {
                spawnCrewOnSeat((ServerLevel) event.getLevel(), target, player, mainHand);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static void spawnCrewOnSeat(ServerLevel level, Entity seat, Player player, ItemStack eggStack) {
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

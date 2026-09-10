package com.stevesarmy.interact;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.item.CommandStickSelection;
import com.stevesarmy.item.CrewAssignStickItem;
import com.stevesarmy.item.VehicleCrewSpawnEggItem;
import com.stevesarmy.network.CommandStickAssignCrewPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Supplementary right-click hook on seat entities: for seat classes that ARE pickable
 * (e.g. tallyho's FlexibleSeatEntity) the entity interact event fires, so handle the
 * crew stick and crew egg here too. Create's SeatEntity is not pickable and never
 * reaches this handler — the CrewAssignStickItem and VehicleCrewSpawnEggItem cover it
 * with their own view-ray raytrace. Cancelling here prevents the player from sitting
 * on the seat and stops the click falling through to the stick's reposition action.
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
                VehicleCrewSpawnEggItem.spawnCrewOnSeat(
                    (net.minecraft.server.level.ServerLevel) event.getLevel(), target, player, mainHand);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}

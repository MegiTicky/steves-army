package com.stevesarmy.interact;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.CommandStickSelection;
import com.stevesarmy.item.CrewAssignStickItem;
import com.stevesarmy.item.VehicleCrewSpawnEggItem;
import com.stevesarmy.network.CommandStickAssignCrewPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Player-facing seat click guards, plus the supplementary right-click hook on
 * pickable seat entities for the crew stick and crew egg (Create's SeatEntity is
 * not pickable and never reaches the entity handler — the items cover it with a
 * ship-aware block raytrace; cancelling there prevents the player from sitting
 * and stops the click falling through to the stick's reposition action).
 *
 * <p>The block-click guard exists because Create's SeatBlock.use ejects any
 * non-player occupant of a seat entity found in the clicked block's shipyard-space
 * AABB. Seat entities of OTHER ships share that space, so clicking an empty seat
 * could eject a crew soldier seated on a nearby ship and teleport the player into
 * its seat. {@link VS2Compat#classifyPlayerSeatClick} detects that case and the
 * handler re-routes the click to the seat the player actually aimed at, or blocks
 * it with a message when the aimed seat itself is soldier-occupied.</p>
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
            // Plain right-click on a pickable seat entity (tallyho's FlexibleSeatEntity):
            // block the occupant swap when a soldier is seated so crew posts stay manned.
            if (hasSoldierPassenger(target)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (!event.getLevel().isClientSide) {
                    player.displayClientMessage(Component.literal("This seat is occupied by a soldier."), true);
                }
            }
            return;
        }

        if (mainHand.getItem() instanceof CrewAssignStickItem) {
            if (event.getLevel().isClientSide) {
                List<Integer> selected = new ArrayList<>(CommandStickSelection.getSelectedIds());
                if (selected.isEmpty()) {
                    player.displayClientMessage(Component.literal("No crew selected"), true);
                } else {
                    // Anchor on the clicked seat's world position — the same
                    // position-based packet the block raytrace sends.
                    NetworkHandler.INSTANCE.sendToServer(new CommandStickAssignCrewPacket(
                        VS2Compat.getSeatWorldPosition(target), target.blockPosition(), selected));
                }
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (mainHand.getItem() instanceof VehicleCrewSpawnEggItem) {
            if (!event.getLevel().isClientSide) {
                VehicleCrewSpawnEggItem.spawnCrewOnSeat(
                    (net.minecraft.server.level.ServerLevel) event.getLevel(),
                    VS2Compat.getSeatWorldPosition(target), target.blockPosition(), player, mainHand);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * Guard against Create's native seat swap on ship seat blocks: it ejects the
     * occupant of whichever seat entity it finds in the clicked block's shipyard-space
     * AABB, which can be a crew soldier on another ship. Cancelled on both sides so
     * the client does not predict the eject; the re-route mounts are server-side only.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getEntity().isShiftKeyDown()) {
            // Create PASSes crouching clicks so blocks can be placed against seats;
            // mirror that and stay out of the way.
            return;
        }
        Level level = event.getLevel();
        VS2Compat.PlayerSeatClick click = VS2Compat.classifyPlayerSeatClick(level, event.getPos());
        if (click == null) {
            return;
        }
        Player player = event.getEntity();

        if (click.foreignSeatPresent()) {
            // A foreign ship's seat entity shares this shipyard position. Seat the
            // player on the seat they actually aimed at instead of letting Create's
            // scan pick the foreign one and eject its occupant.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                boolean mounted = false;
                if (click.freeSeat() != null) {
                    mounted = VS2Compat.seatPlayerOnSeatEntity(serverLevel, serverPlayer, click.freeSeat());
                } else if (click.soldierOccupant() == null && click.playerOccupant() == null) {
                    mounted = VS2Compat.sitPlayerAtShipyardSeat(serverLevel, serverPlayer, click.seatPos());
                }
                if (mounted) {
                    StevesArmyMod.LOGGER.info("[Seats] Cross-ship seat entity blocked at {}; seated player on the clicked seat",
                        click.seatPos());
                } else if (click.soldierOccupant() != null) {
                    serverPlayer.displayClientMessage(Component.literal("This seat is occupied by a soldier."), true);
                } else if (click.playerOccupant() != null) {
                    serverPlayer.displayClientMessage(Component.literal("This seat is already taken."), true);
                } else {
                    serverPlayer.displayClientMessage(Component.literal("This seat is not available."), true);
                }
            }
            return;
        }

        if (click.soldierOccupant() != null) {
            // The aimed seat itself is soldier-occupied: block Create's eject.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("This seat is occupied by a soldier."), true);
            }
        }
    }

    private static boolean hasSoldierPassenger(Entity seat) {
        for (Entity passenger : seat.getPassengers()) {
            if (passenger instanceof SoldierEntity && passenger.isAlive()) {
                return true;
            }
        }
        return false;
    }
}

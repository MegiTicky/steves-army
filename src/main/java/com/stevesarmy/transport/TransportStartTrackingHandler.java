package com.stevesarmy.transport;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * When a player starts tracking a soldier or a transport anchor, re-send the
 * passenger packet so the new observer sees the correct seat relationship.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class TransportStartTrackingHandler {

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Entity target = event.getTarget();

        // If the target is a soldier, check its transport state
        if (target instanceof SoldierEntity soldier) {
            if (soldier.isPassenger() && soldier.getVehicle() != null) {
                player.connection.send(new ClientboundSetPassengersPacket(soldier.getVehicle()));
                StevesArmyMod.LOGGER.info("[TransportSync] Sent passenger packet to {} for soldier={} vehicle={}",
                    player.getScoreboardName(), soldier.getId(), soldier.getVehicle().getId());
            }
            return;
        }

        // If the target is a vehicle anchor, check if any soldier is a passenger
        // and re-send the passenger list
        if (target != null && target.hasPassenger(e -> e instanceof SoldierEntity)) {
            player.connection.send(new ClientboundSetPassengersPacket(target));
            for (Entity passenger : target.getPassengers()) {
                if (passenger instanceof SoldierEntity) {
                    StevesArmyMod.LOGGER.info("[TransportSync] Sent passenger packet to {} for vehicle={} soldier={}",
                        player.getScoreboardName(), target.getId(), passenger.getId());
                }
            }
        }
    }
}
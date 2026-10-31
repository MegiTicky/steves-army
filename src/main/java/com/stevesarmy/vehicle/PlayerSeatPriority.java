package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class PlayerSeatPriority {

    private PlayerSeatPriority() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isPassenger() || player.isShiftKeyDown()) return;

        Entity vehicle = event.getTarget();
        if (!SbwCompat.isVehicle(vehicle) || SbwVehicles.isWrecked(vehicle) || SbwVehicles.isDrone(vehicle)) return;

        Entity driver = SbwVehicles.driver(vehicle);
        if (driver != null && !(driver instanceof SoldierEntity)) return;

        if (driver instanceof SoldierEntity soldier) {
            VehicleMountPolicy.revoke(soldier);
            soldier.stopRiding();
        }

        if (player.startRiding(vehicle, true)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            StevesArmyMod.LOGGER.debug(
                "[SBW] player {} took driver seat of {}",
                player.getName().getString(), vehicle.getName().getString());
        }
    }
}

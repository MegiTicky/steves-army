package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.vehicle.VehicleMountPolicy;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Prevents unsolicited mounts while allowing Steve's Army's explicit vehicle seat assignment. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierMountHandler {

    private SoldierMountHandler() {
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getEntityMounting() instanceof SoldierEntity soldier)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        boolean authorized = VehicleMountPolicy.isAuthorized(soldier, vehicle);
        String vehicleClass = vehicle == null ? "null" : vehicle.getClass().getName();
        String side = soldier.level().isClientSide ? "CLIENT" : "SERVER";
        if (authorized) {
            StevesArmyMod.LOGGER.info("[MountEvent] {} ALLOWED soldier={} vehicle={} vehicleClass={}",
                side, soldier.getId(), vehicle == null ? -1 : vehicle.getId(), vehicleClass);
            soldier.setLowCrouching(false);
        } else {
            StevesArmyMod.LOGGER.debug("[MountEvent] {} CANCELED soldier={} vehicle={} vehicleClass={}",
                side, soldier.getId(), vehicle == null ? -1 : vehicle.getId(), vehicleClass);
            event.setCanceled(true);
        }
    }
}

package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class VehicleTickHandler {

    private VehicleTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!SbwCompat.hasVehicles()) return;

        List<VehicleDriver> drivers = VehicleDriverRegistry.all();
        for (VehicleDriver driver : drivers) {
            try {
                if (!driver.tick()) {
                    VehicleDriverRegistry.remove(driver.vehicle());
                }
            } catch (Throwable t) {
                StevesArmyMod.LOGGER.error("[SBW] driver tick failed, dropping vehicle", t);
                VehicleDriverRegistry.remove(driver.vehicle());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        VehicleDriverRegistry.clearAll();
    }
}

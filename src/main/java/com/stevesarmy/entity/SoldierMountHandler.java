package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Prevents forced mounts, including Create seats, from accepting soldiers. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierMountHandler {

    private SoldierMountHandler() {
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (event.isMounting() && event.getEntityMounting() instanceof SoldierEntity) {
            event.setCanceled(true);
        }
    }
}

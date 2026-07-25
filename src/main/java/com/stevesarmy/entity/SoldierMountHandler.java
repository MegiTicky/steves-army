package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Prevents unsolicited mounts while allowing Steve's Army's explicit Create seat assignment. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierMountHandler {

    private SoldierMountHandler() {
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getEntityMounting() instanceof SoldierEntity soldier)) {
            return;
        }
        if (VS2Compat.isAuthorizedMount(soldier, event.getEntityBeingMounted())) {
            StevesArmyMod.LOGGER.info("[VS2] Allowed authorized soldier mount soldier={} vehicle={}",
                soldier.getId(), event.getEntityBeingMounted().getId());
        } else {
            StevesArmyMod.LOGGER.debug("[VS2] Canceled unsolicited soldier mount soldier={} vehicle={}",
                soldier.getId(), event.getEntityBeingMounted().getId());
            event.setCanceled(true);
        }
    }
}

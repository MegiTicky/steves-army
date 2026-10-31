package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Mirrors SBW's seat HidePassenger setting for Steve's Army riders. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VehicleCrewRenderHandler {
    private VehicleCrewRenderHandler() {}

    /** Remove packet interpolation before the renderer computes head angles. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof SoldierEntity soldier
                && SbwCompat.isVehicle(soldier.getVehicle())) {
                snapHead(soldier);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof SoldierEntity soldier)) return;
        Entity vehicle = soldier.getVehicle();
        if (!SbwCompat.isVehicle(vehicle)) return;

        snapHead(soldier);
        if (SbwVehicles.hidesPassenger(vehicle, soldier)) event.setCanceled(true);
    }

    private static void snapHead(SoldierEntity soldier) {
        // Do this at render-tick start as well as Pre: Pre fires after some
        // renderers have already interpolated the head angles for this frame.
        soldier.yHeadRotO = soldier.getYHeadRot();
        soldier.xRotO = soldier.getXRot();
    }
}

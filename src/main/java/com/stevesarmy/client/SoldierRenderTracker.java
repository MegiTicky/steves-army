package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Records which soldier (if any) the render thread is currently drawing.
 * The vanilla ItemInHandLayer and the YSM hand layer both run between
 * RenderLivingEvent.Pre and Post, so this is a reliable way for the VPB
 * gun-renderer mixin to know a soldier is being rendered.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class SoldierRenderTracker {
    private static LivingEntity currentSoldier;

    public static LivingEntity current() {
        return currentSoldier;
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (event.getEntity() instanceof SoldierEntity) {
            currentSoldier = event.getEntity();
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity() instanceof SoldierEntity) {
            currentSoldier = null;
        }
    }
}

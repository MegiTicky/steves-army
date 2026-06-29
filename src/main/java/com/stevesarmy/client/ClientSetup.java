package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.renderer.SoldierRenderer;
import com.stevesarmy.client.renderer.TargetRenderer;
import com.stevesarmy.client.screen.SoldierInventoryScreen;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.registry.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    
    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SOLDIER.get(), SoldierRenderer::new);
        event.registerEntityRenderer(ModEntities.TARGET.get(), TargetRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.SOLDIER_INVENTORY.get(), SoldierInventoryScreen::new);
        });
    }
}
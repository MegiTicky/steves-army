package com.stevesarmy;

import com.mojang.logging.LogUtils;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.registry.ModItems;
import com.stevesarmy.registry.ModMenuTypes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(StevesArmyMod.MODID)
public class StevesArmyMod {
    public static final String MODID = "steves_army";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StevesArmyMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ModEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModMenuTypes.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        NetworkHandler.register();
        GunIntegration.init();
        LOGGER.info("Steve's Army mod initialized - Enlisted-style squad system ready!");
    }
}
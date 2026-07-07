package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTab {
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StevesArmyMod.MODID);

    public static final RegistryObject<CreativeModeTab> STEVE_TAB = TABS.register("steves_army",
        () -> CreativeModeTab.builder()
            .icon(() -> new ItemStack(Items.PLAYER_HEAD))
            .title(Component.translatable("itemGroup.steves_army"))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.RECRUIT_ITEM.get());
                output.accept(ModItems.SOLDIER_SPAWN_EGG.get());
                output.accept(ModItems.ENEMY_SOLDIER_SPAWN_EGG.get());
                output.accept(ModItems.TARGET_SPAWN_EGG.get());
            })
            .build()
    );

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}
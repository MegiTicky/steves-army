package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.RecruitItem;
import com.stevesarmy.item.SoldierSpawnEggItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = 
        DeferredRegister.create(ForgeRegistries.ITEMS, StevesArmyMod.MODID);

    public static final RegistryObject<Item> RECRUIT_ITEM = ITEMS.register(
        "recruit_item",
        () -> new RecruitItem(new Item.Properties().stacksTo(16))
    );

    public static final RegistryObject<Item> SOLDIER_SPAWN_EGG = ITEMS.register(
        "soldier_spawn_egg",
        () -> new SoldierSpawnEggItem(ModEntities.SOLDIER, 0x4A7C59, 0x2F4F2F, new Item.Properties())
    );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
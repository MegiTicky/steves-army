package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.CreativeCommandStickItem;
import com.stevesarmy.item.RecruitItem;
import com.stevesarmy.item.SoldierSpawnEggItem;
import com.stevesarmy.item.SurvivalCommandStickItem;
import com.stevesarmy.item.SurgicalKnifeItem;
import com.stevesarmy.item.TargetSpawnEggItem;
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

    public static final RegistryObject<Item> SURGICAL_KNIFE = ITEMS.register(
        "surgical_knife",
        () -> new SurgicalKnifeItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> COMMAND_STICK = ITEMS.register(
        "command_stick",
        () -> new SurvivalCommandStickItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> CREATIVE_COMMAND_STICK = ITEMS.register(
        "creative_command_stick",
        () -> new CreativeCommandStickItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> SOLDIER_SPAWN_EGG = ITEMS.register(
        "soldier_spawn_egg",
        () -> new SoldierSpawnEggItem(ModEntities.SOLDIER, 0x4A7C59, 0x2F4F2F, new Item.Properties())
    );

    public static final RegistryObject<Item> TARGET_SPAWN_EGG = ITEMS.register(
        "target_spawn_egg",
        () -> new TargetSpawnEggItem(new Item.Properties())
    );

    public static final RegistryObject<Item> ENEMY_SOLDIER_SPAWN_EGG = ITEMS.register(
        "enemy_soldier_spawn_egg",
        () -> new com.stevesarmy.item.EnemySoldierSpawnEggItem(new Item.Properties())
    );

    public static final RegistryObject<Item> MACHINE_GUNNER_SPAWN_EGG = ITEMS.register(
        "machine_gunner_spawn_egg",
        () -> new com.stevesarmy.item.MachineGunnerSpawnEggItem(ModEntities.MACHINE_GUNNER, 0x4A7C59, 0x8B8B00, new Item.Properties())
    );

    public static final RegistryObject<Item> SUPPORT_SPAWN_EGG = ITEMS.register(
        "support_spawn_egg",
        () -> new com.stevesarmy.item.SupportSpawnEggItem(ModEntities.SUPPORT, 0x4A7C59, 0xFF6B6B, new Item.Properties())
    );

    public static final RegistryObject<Item> GARRISON_SPAWN_EGG = ITEMS.register(
        "garrison_spawn_egg",
        () -> new com.stevesarmy.item.GarrisonSpawnEggItem(ModEntities.GARRISON, 0x4A7C59, 0x2E4053, new Item.Properties())
    );

    public static final RegistryObject<Item> TEAM_GARRISON_SPAWN_EGG = ITEMS.register(
        "team_garrison_spawn_egg",
        () -> new com.stevesarmy.item.TeamGarrisonSpawnEggItem(ModEntities.TEAM_GARRISON, 0x4A7C59, 0x55FFFF, new Item.Properties())
    );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
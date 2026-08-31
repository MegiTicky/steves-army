package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.GarrisonEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.entity.TeamGarrisonEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, StevesArmyMod.MODID);

    public static final RegistryObject<EntityType<SoldierEntity>> SOLDIER = ENTITIES.register(
        "soldier",
        () -> EntityType.Builder.of(SoldierEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":soldier")
    );

    public static final RegistryObject<EntityType<TargetEntity>> TARGET = ENTITIES.register(
        "target",
        () -> EntityType.Builder.of(TargetEntity::new, MobCategory.MISC)
            .sized(0.5F, 1.975F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":target")
    );

    public static final RegistryObject<EntityType<EnemySoldierEntity>> ENEMY_SOLDIER = ENTITIES.register(
        "enemy_soldier",
        () -> EntityType.Builder.of(EnemySoldierEntity::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":enemy_soldier")
    );

    public static final RegistryObject<EntityType<MachineGunnerEntity>> MACHINE_GUNNER = ENTITIES.register(
        "machine_gunner",
        () -> EntityType.Builder.of(MachineGunnerEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":machine_gunner")
    );

    public static final RegistryObject<EntityType<SupportEntity>> SUPPORT = ENTITIES.register(
        "support",
        () -> EntityType.Builder.of(SupportEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":support")
    );

    public static final RegistryObject<EntityType<GarrisonEntity>> GARRISON = ENTITIES.register(
        "garrison_soldier",
        () -> EntityType.Builder.of(GarrisonEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":garrison_soldier")
    );

    public static final RegistryObject<EntityType<TeamGarrisonEntity>> TEAM_GARRISON = ENTITIES.register(
        "team_garrison",
        () -> EntityType.Builder.of(TeamGarrisonEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(StevesArmyMod.MODID + ":team_garrison")
    );

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(SOLDIER.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());
        
        event.put(TARGET.get(), TargetEntity.createAttributes().build());
        
        event.put(ENEMY_SOLDIER.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());

        event.put(MACHINE_GUNNER.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());

        event.put(SUPPORT.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());

        event.put(GARRISON.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());

        event.put(TEAM_GARRISON.get(), Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.35D)
            .add(Attributes.ARMOR, 4.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .build());
    }

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}
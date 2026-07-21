package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class TeamEventHandler {

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel)) return;

        var entity = event.getEntity();

        try {
            if (entity instanceof EnemySoldierEntity enemy) {
                TeamManager.assignToEnemyTeam(enemy);
                enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));
                StevesArmyMod.LOGGER.info("TeamEventHandler: assigned enemy {} to enemy team, added GLOWING effect", enemy.getName().getString());
            } else if (entity instanceof SoldierEntity soldier) {
                UUID ownerUUID = soldier.getOwnerUUID().orElseGet(() -> {
                    StevesArmyMod.LOGGER.warn("TeamEventHandler: soldier {} has no owner UUID, using random fallback", soldier.getName().getString());
                    return UUID.randomUUID();
                });
                TeamManager.assignToFriendlyTeam(soldier, ownerUUID);
                soldier.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));
                StevesArmyMod.LOGGER.info("TeamEventHandler: assigned soldier {} to friendly team for owner {}, added GLOWING effect", soldier.getName().getString(), ownerUUID);
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.error("Failed to assign team for entity {}: {}", entity, e.getMessage());
        }
    }
}
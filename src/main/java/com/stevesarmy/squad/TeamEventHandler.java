package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
                enemy.setGlowing(true);
            } else if (entity instanceof SoldierEntity soldier) {
                TeamManager.assignToFriendlyTeam(soldier);
                soldier.setGlowing(true);
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.error("Failed to assign team for entity {}: {}", entity, e.getMessage());
        }
    }
}
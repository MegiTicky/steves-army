package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class TeamEventHandler {

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        var entity = event.getEntity();

        if (entity instanceof EnemySoldierEntity enemy) {
            TeamManager.assignToEnemyTeam(enemy);
            enemy.setGlowing(true);
        } else if (entity instanceof SoldierEntity soldier) {
            if (soldier.getOwner() instanceof Player owner) {
                TeamManager.assignToFriendlyTeam(soldier, owner);
                soldier.setGlowing(true);
            }
        } else if (entity instanceof Player player) {
            TeamManager.assignToFriendlyTeam(player, player);
        }
    }
}
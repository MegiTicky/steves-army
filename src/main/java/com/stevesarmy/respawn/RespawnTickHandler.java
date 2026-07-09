package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class RespawnTickHandler {
    
    private static final double REVIVE_RADIUS = 3.0;
    private static int tickCounter = 0;
    
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            RespawnCameraController.tick();
            
            tickCounter++;
            if (tickCounter % 5 == 0) {
                tickSoldierRevival(event);
            }
        }
    }
    
    private static void tickSoldierRevival(TickEvent.ServerTickEvent event) {
        if (!PlayerReviveCompat.isLoaded()) return;
        
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<ServerPlayer> players = level.getPlayers(p -> PlayerReviveCompat.isPlayerBleeding(p));
            
            for (ServerPlayer player : players) {
                processSoldierRevival(player, level);
            }
        }
    }
    
    private static void processSoldierRevival(ServerPlayer downedPlayer, ServerLevel level) {
        AABB searchBox = downedPlayer.getBoundingBox().inflate(REVIVE_RADIUS);
        
        List<SoldierEntity> nearbySoldiers = level.getEntitiesOfClass(
            SoldierEntity.class, searchBox,
            s -> s.isAlive() && s.isFriendlyTo(downedPlayer));
        
        if (nearbySoldiers.isEmpty()) return;
        
        float progressPerSoldier = PlayerReviveCompat.getProgressPerPlayer();
        float totalProgress = nearbySoldiers.size() * progressPerSoldier * 5;
        
        boolean success = PlayerReviveCompat.addReviveProgress(downedPlayer, totalProgress);
        if (success && totalProgress > 0) {
            StevesArmyMod.LOGGER.debug("[SoldierRevive] {} soldiers contributing {} progress to revive {}",
                nearbySoldiers.size(), totalProgress, downedPlayer.getName().getString());
        }
    }
}
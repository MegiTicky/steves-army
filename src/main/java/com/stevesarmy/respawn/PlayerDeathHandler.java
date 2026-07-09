package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class PlayerDeathHandler {
    
    private static final ConcurrentHashMap<UUID, RespawnData> pendingRespawns = new ConcurrentHashMap<>();
    
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOW handler fired, isCanceled={}, entity={}", 
            event.isCanceled(), event.getEntity().getName().getString());
        
        if (event.isCanceled()) {
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOW: Event cancelled, returning");
            return;
        }
        
        if (event.getEntity() instanceof SoldierEntity soldier && !soldier.level().isClientSide()) {
            if (soldier.level() instanceof ServerLevel serverLevel) {
                SquadManager.get(serverLevel).removeMemberFromSquad(soldier.getUUID());
                StevesArmyMod.LOGGER.info("[Respawn] Soldier {} removed from squad on death", soldier.getUUID().toString().substring(0, 8));
            }
            return;
        }
        
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        if (player.level().isClientSide()) {
            return;
        }
        
        boolean isBleeding = PlayerReviveCompat.isPlayerBleeding(player);
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOW: isPlayerBleeding={}", isBleeding);
        
        if (isBleeding) {
            StevesArmyMod.LOGGER.info("[Respawn] Player {} is downed (PlayerRevive), deferring respawn decision", player.getName().getString());
            return;
        }
        
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOW: Proceeding with soldier respawn");
        initiateSoldierRespawn(player);
    }
    
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDelayedDeath(LivingDeathEvent event) {
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOWEST handler fired, isCanceled={}, entity={}", 
            event.isCanceled(), event.getEntity().getName().getString());
        
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (event.isCanceled()) {
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOWEST: Event cancelled, returning");
            return;
        }
        
        boolean isBleeding = PlayerReviveCompat.isPlayerBleeding(player);
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOWEST: isPlayerBleeding={}", isBleeding);
        
        if (!isBleeding) {
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] LOWEST: Player not bleeding, returning");
            return;
        }
        
        StevesArmyMod.LOGGER.info("[Respawn] Player {} bled out/gave up (PlayerRevive), proceeding with soldier respawn", player.getName().getString());
        initiateSoldierRespawn(player);
    }
    
    private static void initiateSoldierRespawn(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        SquadManager squadManager = SquadManager.get(level);
        
        Optional<SquadData> squadOpt = squadManager.getSquadByLeader(player.getUUID());
        if (squadOpt.isEmpty()) {
            StevesArmyMod.LOGGER.info("[Respawn] Player {} has no squad, normal death", player.getName().getString());
            return;
        }
        
        SquadData squad = squadOpt.get();
        List<LivingEntity> livingSoldiers = squadManager.getSquadMembers(level, squad.getSquadId(), player.getUUID());
        
        livingSoldiers.removeIf(entity -> !(entity instanceof SoldierEntity) || !entity.isAlive());
        
        if (livingSoldiers.isEmpty()) {
            StevesArmyMod.LOGGER.info("[Respawn] Player {} has no living soldiers, normal death", player.getName().getString());
            return;
        }
        
        SoldierEntity nearestSoldier = findNearestSoldier(player.position(), livingSoldiers);
        if (nearestSoldier == null) {
            return;
        }
        
        pendingRespawns.put(player.getUUID(), new RespawnData(
            nearestSoldier.getUUID(),
            nearestSoldier.position(),
            nearestSoldier.getYRot(),
            nearestSoldier.getXRot(),
            squad.getSquadId()
        ));
        
        StevesArmyMod.LOGGER.info("[Respawn] Player {} died, will respawn as soldier {} at {}", 
            player.getName().getString(), 
            nearestSoldier.getUUID().toString().substring(0, 8),
            nearestSoldier.blockPosition());
        
        RespawnCameraController.startTransition(player, nearestSoldier, squadManager, squad, level);
    }
    
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) {
            return;
        }
        
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        RespawnData data = pendingRespawns.remove(player.getUUID());
        if (data == null) {
            return;
        }
        
        ServerLevel level = player.serverLevel();
        SquadManager squadManager = SquadManager.get(level);
        
        Optional<SquadData> squadOpt = squadManager.getSquadById(data.squadId);
        if (squadOpt.isEmpty()) {
            return;
        }
        
        SoldierEntity soldier = findSoldierByUUID(level, data.soldierId);
        if (soldier == null || !soldier.isAlive()) {
            StevesArmyMod.LOGGER.warn("[Respawn] Soldier {} not found or dead, fallback to normal respawn", data.soldierId);
            return;
        }
        
        SoldierRespawnManager.initiateRespawn(player, soldier, squadManager, squadOpt.get(), level);
    }
    
    private static SoldierEntity findNearestSoldier(net.minecraft.world.phys.Vec3 deathPos, List<LivingEntity> soldiers) {
        SoldierEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        
        for (LivingEntity entity : soldiers) {
            if (!(entity instanceof SoldierEntity soldier)) {
                continue;
            }
            
            double dist = soldier.distanceToSqr(deathPos);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = soldier;
            }
        }
        
        return nearest;
    }
    
    private static SoldierEntity findSoldierByUUID(ServerLevel level, UUID soldierId) {
        net.minecraft.world.entity.Entity entity = level.getEntity(soldierId);
        return entity instanceof SoldierEntity soldier ? soldier : null;
    }
    
    private static class RespawnData {
        final UUID soldierId;
        final net.minecraft.world.phys.Vec3 soldierPos;
        final float soldierYRot;
        final float soldierXRot;
        final UUID squadId;
        
        RespawnData(UUID soldierId, net.minecraft.world.phys.Vec3 soldierPos, float soldierYRot, float soldierXRot, UUID squadId) {
            this.soldierId = soldierId;
            this.soldierPos = soldierPos;
            this.soldierYRot = soldierYRot;
            this.soldierXRot = soldierXRot;
            this.squadId = squadId;
        }
    }
}
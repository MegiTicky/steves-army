package com.stevesarmy.respawn;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RespawnCameraController {
    
    private static final int TRANSITION_TICKS = 40;
    private static final int TICKS_BEFORE_TELEPORT = 10;
    
    private static final java.util.Map<UUID, CameraState> activeTransitions = new java.util.concurrent.ConcurrentHashMap<>();
    
    public static void startTransition(ServerPlayer player, SoldierEntity targetSoldier, SquadManager squadManager, SquadData squad, ServerLevel level) {
        UUID playerId = player.getUUID();
        
        CameraState state = new CameraState(
            player.getUUID(),
            targetSoldier.getUUID(),
            targetSoldier.position(),
            targetSoldier.getYRot(),
            targetSoldier.getXRot(),
            squadManager,
            squad,
            level,
            0
        );
        
        activeTransitions.put(playerId, state);
        
        StevesArmyMod.LOGGER.info("[CameraTransition] Started for player {} to soldier at {}", 
            player.getName().getString(), targetSoldier.blockPosition());
    }
    
    public static void tick() {
        activeTransitions.entrySet().removeIf(entry -> {
            CameraState state = entry.getValue();
            state.tickCount++;
            
            if (state.tickCount >= TRANSITION_TICKS) {
                ServerPlayer player = state.level.getServer().getPlayerList().getPlayer(state.playerId);
                if (player != null) {
                    SoldierEntity soldier = findSoldierByUUID(state.level, state.soldierId);
                    if (soldier != null && soldier.isAlive()) {
                        SoldierRespawnManager.initiateRespawn(player, soldier, state.squadManager, state.squad, state.level);
                    }
                }
                return true;
            }
            
            if (state.tickCount >= TICKS_BEFORE_TELEPORT) {
                ServerPlayer player = state.level.getServer().getPlayerList().getPlayer(state.playerId);
                if (player != null && !player.isSpectator()) {
                    player.setCamera(null);
                    player.teleportTo(state.soldierPos.x, state.soldierPos.y + 0.5, state.soldierPos.z);
                    player.setYRot(state.soldierYRot);
                    player.setXRot(state.soldierXRot);
                }
            }
            
            return false;
        });
    }
    
    private static SoldierEntity findSoldierByUUID(ServerLevel level, UUID soldierId) {
        net.minecraft.world.entity.Entity entity = level.getEntity(soldierId);
        return entity instanceof SoldierEntity soldier ? soldier : null;
    }
    
    private static class CameraState {
        final UUID playerId;
        final UUID soldierId;
        final Vec3 soldierPos;
        final float soldierYRot;
        final float soldierXRot;
        final SquadManager squadManager;
        final SquadData squad;
        final ServerLevel level;
        int tickCount;
        
        CameraState(UUID playerId, UUID soldierId, Vec3 soldierPos, float soldierYRot, float soldierXRot, 
                    SquadManager squadManager, SquadData squad, ServerLevel level, int tickCount) {
            this.playerId = playerId;
            this.soldierId = soldierId;
            this.soldierPos = soldierPos;
            this.soldierYRot = soldierYRot;
            this.soldierXRot = soldierXRot;
            this.squadManager = squadManager;
            this.squad = squad;
            this.level = level;
            this.tickCount = tickCount;
        }
    }
}
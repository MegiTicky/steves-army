package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.FireTeamScopeSyncPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.util.SoldierNameGenerator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                StevesArmyMod.LOGGER.info("TeamEventHandler: assigned enemy {} to enemy team", enemy.getName().getString());
            } else if (entity instanceof SoldierEntity soldier) {
                UUID ownerUUID = soldier.getOwnerUUID().orElseGet(() -> {
                    StevesArmyMod.LOGGER.warn("TeamEventHandler: soldier {} has no owner UUID, using random fallback", soldier.getName().getString());
                    return UUID.randomUUID();
                });
                TeamManager.assignToFriendlyTeam(soldier, ownerUUID);
                soldier.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));

                if (level instanceof ServerLevel serverLevel) {
                    FireTeamAssignment fta = FireTeamAssignment.get(serverLevel, ownerUUID);
                    FireTeam savedTeam = fta.getTeamFor(soldier.getUUID());
                    soldier.setFireTeam(savedTeam);
                    fta.assignToTeam(soldier.getUUID(), savedTeam);
                }

                if (!soldier.hasCustomName()) {
                    soldier.setCustomName(Component.literal(SoldierNameGenerator.generateForOwner(soldier.getRandom(), ownerUUID)));
                }

                if (level instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
                    ServerPlayer ownerPlayer = serverLevel.getServer().getPlayerList().getPlayer(ownerUUID);
                    if (ownerPlayer != null) {
                        TeamManager.addPlayerToFriendlyTeam(ownerPlayer, ownerUUID);
                        FireTeamAssignment assignment = FireTeamAssignment.get(serverLevel, ownerUUID);
                        NetworkHandler.sendTo(ownerPlayer, new FireTeamScopeSyncPacket(assignment.getTeamCount()));
                    }
                }

                StevesArmyMod.LOGGER.info("TeamEventHandler: assigned soldier {} to friendly team for owner {}, added GLOWING effect", soldier.getName().getString(), ownerUUID);
            } else if (entity instanceof ServerPlayer player) {
                onPlayerLogin(player);
            }
        } catch (Exception e) {
            StevesArmyMod.LOGGER.error("Failed to assign team for entity {}: {}", entity, e.getMessage());
        }
    }

    private static void onPlayerLogin(ServerPlayer player) {
        UUID playerUUID = player.getUUID();
        TeamManager.addPlayerToFriendlyTeam(player, playerUUID);
        FireTeamAssignment assignment = FireTeamAssignment.get((ServerLevel) player.level(), playerUUID);
        NetworkHandler.sendTo(player, new FireTeamScopeSyncPacket(assignment.getTeamCount()));
    }
}

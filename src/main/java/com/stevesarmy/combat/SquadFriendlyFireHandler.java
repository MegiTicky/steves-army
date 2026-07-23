package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army")
public class SquadFriendlyFireHandler {
    
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        Entity attackerEntity = event.getSource().getEntity();
        
        boolean debug = CoverTacticalGoal.isDebugLoggingEnabled();
        
        if (debug) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: victim={}({}) type={} attackerEntity={}({}) source={}",
                victim.getName().getString(), victim.getClass().getSimpleName(), net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(victim.getType()),
                attackerEntity != null ? attackerEntity.getName().getString() : "null",
                attackerEntity != null ? attackerEntity.getClass().getSimpleName() : "null",
                event.getSource().getMsgId());
        }
        
        if (!(attackerEntity instanceof LivingEntity attacker)) {
            if (debug) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: attacker not LivingEntity, passing through");
            }
            return;
        }
        if (victim == attacker) {
            if (debug) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: self-damage, skipping");
            }
            return;
        }
        
        Team attackerTeam = attacker.getTeam();
        Team victimTeam = victim.getTeam();
        
        if (debug) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: attackerTeam={} victimTeam={}",
                attackerTeam != null ? attackerTeam.getName() : "null",
                victimTeam != null ? victimTeam.getName() : "null");
        }
        
        if (attackerTeam != null && victimTeam != null
            && !(attacker instanceof SoldierEntity) && !(victim instanceof SoldierEntity)) {
            boolean sameTeam = attackerTeam.equals(victimTeam);
            boolean allied = attackerTeam.isAlliedTo(victimTeam);
            boolean ff = attackerTeam.isAllowFriendlyFire();
            if (debug) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: sameTeam={} allied={} allowFF={}", sameTeam, allied, ff);
            }
            if (sameTeam || allied) {
                if (!ff) {
                    if (debug) {
                        StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: CANCELLED by team friendlyfire check");
                    }
                    event.setCanceled(true);
                    debugLog(attacker, victim, "team friendlyfire off");
                    return;
                }
            }
        }
        
        if (StevesArmyConfig.getSquadFriendlyFire()) {
            if (attacker instanceof SoldierEntity attackerSoldier) {
                boolean friendly = attackerSoldier.isFriendlyTo(victim);
                if (debug) {
                    StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: attacker is Soldier, isFriendlyTo(victim)={}", friendly);
                }
                if (friendly) {
                    if (debug) {
                        StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: CANCELLED by squad protection (attacker check)");
                    }
                    event.setCanceled(true);
                    debugLog(attacker, victim, "squad protection");
                    return;
                }
            }
            
            if (victim instanceof SoldierEntity victimSoldier) {
                boolean friendly = victimSoldier.isFriendlyTo(attacker);
                if (debug) {
                    StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: victim is Soldier, isFriendlyTo(attacker)={}", friendly);
                }
                if (friendly) {
                    if (debug) {
                        StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: CANCELLED by squad protection (victim check)");
                    }
                    event.setCanceled(true);
                    debugLog(attacker, victim, "squad protection");
                    return;
                }
            }
        }
        
        if (debug) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] onLivingHurt: DAMAGE PASSED THROUGH, amount={}", event.getAmount());
        }
    }
    
    private static void debugLog(LivingEntity attacker, LivingEntity victim, String reason) {
        StevesArmyMod.LOGGER.debug("[FriendlyFire] Blocked: {} → {} ({})", 
            attacker.getName().getString(), 
            victim.getName().getString(), 
            reason);
    }
}
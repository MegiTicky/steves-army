package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
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
        
        if (!(attackerEntity instanceof LivingEntity attacker)) return;
        if (victim == attacker) return;
        
        Team attackerTeam = attacker.getTeam();
        Team victimTeam = victim.getTeam();
        
        if (attackerTeam != null && victimTeam != null) {
            if (attackerTeam.equals(victimTeam) || attackerTeam.isAlliedTo(victimTeam)) {
                if (!attackerTeam.isAllowFriendlyFire()) {
                    event.setCanceled(true);
                    debugLog(attacker, victim, "team friendlyfire off");
                    return;
                }
            }
            return;
        }
        
        if (!StevesArmyConfig.getSquadFriendlyFire()) return;
        
        if (attacker instanceof SoldierEntity attackerSoldier) {
            if (attackerSoldier.isFriendlyTo(victim)) {
                event.setCanceled(true);
                debugLog(attacker, victim, "squad protection");
                return;
            }
        }
        
        if (victim instanceof SoldierEntity victimSoldier) {
            if (victimSoldier.isFriendlyTo(attacker)) {
                event.setCanceled(true);
                debugLog(attacker, victim, "squad protection");
                return;
            }
        }
    }
    
    private static void debugLog(LivingEntity attacker, LivingEntity victim, String reason) {
        StevesArmyMod.LOGGER.debug("[FriendlyFire] Blocked: {} → {} ({})", 
            attacker.getName().getString(), 
            victim.getName().getString(), 
            reason);
    }
}

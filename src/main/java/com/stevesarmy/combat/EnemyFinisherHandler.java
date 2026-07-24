package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public class EnemyFinisherHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        boolean debug = CoverTacticalGoal.isDebugLoggingEnabled();

        // Only re-allow damage if the victim is currently bleeding (PlayerRevive downed state)
        if (!PlayerReviveCompat.isPlayerBleeding(victim)) return;

        // Only re-allow damage if the attacker is an enemy soldier
        if (!(event.getSource().getEntity() instanceof EnemySoldierEntity)) return;

        // Only re-allow damage if it's a TaCZ bullet
        if (!event.getSource().is(com.tacz.guns.init.ModDamageTypes.BULLETS_TAG)) {
            if (debug) {
                StevesArmyMod.LOGGER.info("[EnemyFinisher] Damage not a TaCZ bullet (type={}), skipping",
                    event.getSource().getMsgId());
            }
            return;
        }

        if (debug) {
            StevesArmyMod.LOGGER.info("[EnemyFinisher] Allowing enemy soldier ({}) to finish downed player {} (damage={})",
                event.getSource().getEntity().getId(), victim.getName().getString(), event.getAmount());
        }

        // Re-allow the damage that PlayerRevive cancelled
        event.setCanceled(false);
    }
}

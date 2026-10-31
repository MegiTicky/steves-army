package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army")
public final class SquadFriendlyFireHandler {
    private SquadFriendlyFireHandler() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        Entity source = event.getSource().getEntity();
        if (!(source instanceof LivingEntity attacker) || attacker == victim) return;
        if (!FofCombat.policyAllows(attacker, victim)) {
            event.setCanceled(true);
            return;
        }
        if (StevesArmyConfig.getSquadFriendlyFire() && FofCombat.friendly(attacker, victim)) {
            event.setCanceled(true);
            return;
        }
        FofCombat.recordAttack(attacker, victim);
    }
}

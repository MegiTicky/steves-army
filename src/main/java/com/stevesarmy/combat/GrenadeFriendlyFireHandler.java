package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Hard server-side friendly-fire protection for native LesRaisins explosions. */
@Mod.EventBusSubscriber(modid = "steves_army")
public final class GrenadeFriendlyFireHandler {
    private GrenadeFriendlyFireHandler() {}

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;
        Explosion explosion = event.getExplosion();
        if (!GrenadeIntegration.isGrenadeEntity(explosion.getExploder())) return;
        GrenadeIntegration.logExplosionDiagnostic(explosion);
        LivingEntity owner = GrenadeIntegration.getOwner(explosion.getExploder());
        if (owner == null) return;
        event.getAffectedEntities().removeIf(entity -> entity instanceof LivingEntity living
            && isFriendly(owner, living));
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        Entity source = event.getSource().getEntity();
        Entity grenade = GrenadeIntegration.isGrenadeEntity(direct) ? direct
            : GrenadeIntegration.isGrenadeEntity(source) ? source : null;
        if (grenade == null) return;
        LivingEntity owner = GrenadeIntegration.getOwner(grenade);
        if (owner != null && isFriendly(owner, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean isFriendly(LivingEntity owner, LivingEntity target) {
        if (owner == target) return true;
        if (owner instanceof SoldierEntity soldier && soldier.isFriendlyTo(target)) return true;
        if (target instanceof SoldierEntity soldier && soldier.isFriendlyTo(owner)) return true;
        if (owner.isAlliedTo(target) || target.isAlliedTo(owner)) return true;
        if (owner instanceof Player ownerPlayer && target instanceof Player targetPlayer) {
            return ownerPlayer.getTeam() != null && ownerPlayer.getTeam() == targetPlayer.getTeam();
        }
        return false;
    }
}

package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Registered manually from GunIntegration (not @EventBusSubscriber) so that a
 * TaCZ build missing any of these event classes degrades to the reflection
 * fallback instead of crashing mod loading.
 */
public class IncomingFireHandlerTaCZ {

    @SubscribeEvent
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Post event) {
        if (event.getLogicalSide().isClient()) return;

        Entity bullet = event.getBullet();
        Vec3 startPos = bullet.position();
        Vec3 endPos = startPos.add(bullet.getDeltaMovement());
        float speed = (float)bullet.getDeltaMovement().length();
        LivingEntity shooter = event.getAttacker();
        if (debugLog()) {
            Entity victim = event.getHurtEntity();
            StevesArmyMod.LOGGER.info("[TaCZSuppressionTrace] gunHit bullet={} type={} gun={} owner={} victim={} amount={} speed={}",
                bullet.getId(), bullet.getType().builtInRegistryHolder().key().location(), event.getGunId(),
                shooter != null ? shooter.getName().getString() : "none",
                victim != null ? victim.getName().getString() + "(" + victim.getId() + ")" : "none",
                String.format("%.2f", event.getAmount()), String.format("%.2f", speed));
        }
        IncomingFireHandler.checkNearMissLineSegment(bullet.level(), startPos, endPos, speed, shooter);
    }

    private static boolean debugLog() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }
}

package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army")
public class IncomingFireHandlerTaCZ {

    @SubscribeEvent
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (event.getLevel().isClientSide) return;

        Vec3 startPos = event.getAmmo().position();
        Vec3 endPos = event.getHitResult().getLocation();
        float speed = (float)event.getAmmo().getDeltaMovement().length();
        LivingEntity shooter = event.getAmmo().getOwner() instanceof LivingEntity owner ? owner : null;
        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[TaCZSuppressionTrace] blockImpact bullet={} gun={} owner={} hit=({}, {}, {}) speed={}",
                event.getAmmo().getId(), event.getAmmo().getGunId(),
                shooter != null ? shooter.getName().getString() : "none",
                String.format("%.2f", endPos.x), String.format("%.2f", endPos.y), String.format("%.2f", endPos.z),
                String.format("%.2f", speed));
        }
        IncomingFireHandler.checkNearMissLineSegment(event.getLevel(), startPos, endPos, speed, shooter);
    }

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
        return CoverTacticalGoal.isDebugLoggingEnabled();
    }
}

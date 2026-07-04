package com.stevesarmy.combat.cover;

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
        IncomingFireHandler.checkNearMissLineSegment(bullet.level(), startPos, endPos, speed, shooter);
    }
}
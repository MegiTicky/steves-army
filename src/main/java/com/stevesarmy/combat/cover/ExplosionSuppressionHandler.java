package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "steves_army")
public class ExplosionSuppressionHandler {

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;

        Explosion explosion = event.getExplosion();
        Vec3 explosionPos = explosion.getPosition();
        List<Entity> affected = event.getAffectedEntities();

        for (Entity entity : affected) {
            if (!(entity instanceof SoldierEntity soldier)) continue;

            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager == null) continue;

            float exposure = Explosion.getSeenPercent(explosionPos, soldier);
            coverManager.onExplosion(explosionPos, exposure);
        }
    }
}
package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Converts completed TaCZ shots into observer-specific detection cues. */
@Mod.EventBusSubscriber(modid = "steves_army")
public final class GunshotDetectionHandlerTaCZ {
    private GunshotDetectionHandlerTaCZ() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onGunShoot(GunShootEvent event) {
        if (event.getLogicalSide().isClient()) return;

        LivingEntity shooter = event.getShooter();
        if (shooter == null || !shooter.isAlive()) return;

        GunIntegration.GunshotSignature signature = GunIntegration.getGunshotSignature(shooter);
        for (SoldierEntity observer : shooter.level().getEntitiesOfClass(
                SoldierEntity.class, shooter.getBoundingBox().inflate(DetectionSystem.getMaximumConfiguredFocusedRange()))) {
            if (observer.isAlive()
                && observer.distanceTo(shooter) <= DetectionSystem.getFocusedRangeFor(observer)
                && observer.getCombatGoal() != null) {
                observer.getCombatGoal().onEnemyGunshot(shooter, signature);
            }
        }
    }
}

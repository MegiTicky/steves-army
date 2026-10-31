package com.stevesarmy.compat.sbw;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeamFireSuperiorityTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Converts completed Superb Warfare shots into observer-specific detection cues. */
public final class GunshotDetectionHandlerSbw {
    private GunshotDetectionHandlerSbw() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onSbwGunShoot(ShootEvent.Post event) {
        Entity entity = event.getParameters().shooter;
        if (!(entity instanceof LivingEntity shooter) || !shooter.isAlive()) return;
        if (shooter.level().isClientSide()) return;

        if (shooter instanceof SoldierEntity soldier && soldier.getOwnerUUID().isPresent()) {
            FireTeamFireSuperiorityTracker.onFriendlyShot(soldier);
        }

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

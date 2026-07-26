package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.debug.DiagnosticLogManager;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "steves_army")
public class ExplosionSuppressionHandler {

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (event.getLevel().isClientSide() || !debugLog()) return;

        Vec3 position = event.getExplosion().getPosition();
        StevesArmyMod.LOGGER.info("[ExplosionSuppression] start type={} exploder={} pos=({}, {}, {})",
            event.getExplosion().getClass().getName(),
            event.getExplosion().getExploder() != null ? event.getExplosion().getExploder().getName().getString() : "none",
            String.format("%.2f", position.x), String.format("%.2f", position.y), String.format("%.2f", position.z));
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;

        Explosion explosion = event.getExplosion();
        Vec3 explosionPos = explosion.getPosition();
        float radius = StevesArmyConfig.getExplosionSuppressionRadius();
        AABB suppressionArea = new AABB(explosionPos, explosionPos).inflate(radius + 1.0);
        var nearbySoldiers = event.getLevel().getEntitiesOfClass(SoldierEntity.class, suppressionArea);

        if (debugLog()) {
            StevesArmyMod.LOGGER.info("[ExplosionSuppression] detonate pos=({}, {}, {}), configuredRadius={}, eventCandidates={}, nearbySoldiers={}",
                String.format("%.2f", explosionPos.x), String.format("%.2f", explosionPos.y),
                String.format("%.2f", explosionPos.z), String.format("%.2f", radius),
                event.getAffectedEntities().size(), nearbySoldiers.size());
        }

        // The Forge affected-entity list follows each explosion's damage radius,
        // which can be smaller than Steve's Army's independent suppression radius.
        for (SoldierEntity soldier : nearbySoldiers) {
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager == null) {
                if (debugLog()) {
                    StevesArmyMod.LOGGER.warn("[ExplosionSuppression] soldier {} has no cover manager", soldier.getId());
                }
                continue;
            }

            float exposure = Explosion.getSeenPercent(explosionPos, soldier);
            if (debugLog()) {
                StevesArmyMod.LOGGER.info("[ExplosionSuppression] soldier={} name={} distance={} exposure={} before={} state={}",
                    soldier.getId(), soldier.getName().getString(),
                    String.format("%.2f", soldier.position().distanceTo(explosionPos)),
                    String.format("%.2f", exposure),
                    String.format("%.2f", coverManager.getSuppressionTracker().getSuppressionLevel()),
                    coverManager.getState());
            }
            coverManager.onExplosion(explosionPos, exposure);
        }
    }

    private static boolean debugLog() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }
}

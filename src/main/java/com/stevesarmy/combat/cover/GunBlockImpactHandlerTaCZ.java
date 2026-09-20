package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Registered manually from GunIntegration only when the installed TaCZ build
 * still ships AmmoHitBlockEvent (newer TaCZ lines removed it — an automatic
 * registration here would crash mod loading with NoClassDefFoundError).
 */
public class GunBlockImpactHandlerTaCZ {

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

    private static boolean debugLog() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }
}

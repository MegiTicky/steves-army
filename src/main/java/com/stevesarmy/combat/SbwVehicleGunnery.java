package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spotter AI for soldiers mounted on Superb Warfare vehicles. While a soldier is
 * a passenger, {@code VS2Compat.prepareSoldierAi} freezes its goal system — but
 * SBW's native mob-crew gunnery fires the vehicle's turret and seat weapons at
 * whatever LivingEntity the riding Mob targets ({@code setTarget} is mirrored
 * into the vehicle's {@code aiTurretTargetUUID} and auto-fired at the weapon's
 * RPM with ballistic lead). This ticker therefore keeps exactly one thing alive
 * while the soldier's own AI is frozen: target acquisition. No movement, no
 * cover, no firing of our own — the soldier is a pure spotter; SBW shoots.
 *
 * <p>Called from the frozen-AI branch in {@code SoldierEntity.customServerAiStep},
 * the same structural pattern as {@link StationGunnerAI} (station AI runs outside
 * the goal system while the soldier sits).</p>
 */
public final class SbwVehicleGunnery {
    private static final int RETARGET_INTERVAL_TICKS = 10;
    private static final long PRUNE_INTERVAL_TICKS = 200;
    private static final Map<UUID, Long> lastRetargetBySoldier = new HashMap<>();
    private static long lastPruneGameTime = Long.MIN_VALUE;

    private SbwVehicleGunnery() {}

    /** Safe to call every tick from the frozen-AI branch; throttled and self-gating. */
    public static void tickMounted(SoldierEntity soldier) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity mount = soldier.getVehicle();
        if (!SbwCompat.isOperational(mount)) {
            return;
        }
        long gameTime = serverLevel.getGameTime();
        UUID soldierId = soldier.getUUID();
        Long last = lastRetargetBySoldier.get(soldierId);
        if (last != null && gameTime - last < RETARGET_INTERVAL_TICKS) {
            return;
        }
        lastRetargetBySoldier.put(soldierId, gameTime);
        pruneIfNeeded(gameTime);

        // Keep a still-valid target rather than flip-flopping between equals.
        LivingEntity current = soldier.getTarget();
        if (current != null && current.isAlive() && isValidSpottingTarget(soldier, current)) {
            return;
        }

        double range = StevesArmyConfig.getBasicDetectionDistance();
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class,
            soldier.getBoundingBox().inflate(range),
            t -> t.isAlive() && isValidSpottingTarget(soldier, t));
        LivingEntity nearest = targets.stream()
            .min(Comparator.comparingDouble(soldier::distanceToSqr))
            .orElse(null);
        if (nearest != null) {
            soldier.setTarget(nearest);
        }
    }

    /** Ordinary targeting rules plus a sight check — the gun must actually be able to engage it. */
    private static boolean isValidSpottingTarget(SoldierEntity soldier, LivingEntity target) {
        return target != soldier
            && !soldier.isFriendlyTo(target)
            && TargetAcquisition.isValidTarget(soldier, target)
            && TargetAcquisition.hasLineOfSightToPosition(soldier, target.getEyePosition());
    }

    private static void pruneIfNeeded(long gameTime) {
        if (lastPruneGameTime != Long.MIN_VALUE && gameTime - lastPruneGameTime < PRUNE_INTERVAL_TICKS) {
            return;
        }
        lastPruneGameTime = gameTime;
        lastRetargetBySoldier.entrySet().removeIf(
            entry -> gameTime - entry.getValue() > RETARGET_INTERVAL_TICKS * 20);
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.vehicle.VehicleWeaponController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spotter AI for dedicated vehicle crew mounted on Superb Warfare vehicles. While a soldier is
 * a passenger, {@code VS2Compat.prepareSoldierAi} freezes its goal system — but
 * SBW's native mob-crew gunnery fires at the riding Mob's target. Keep spotting
 * and weapon selection alive while the soldier's normal movement/cover AI is
 * frozen. SBW owns native living-target shots; hull and recent-position shots
 * use SBW's weapon API and cadence while the Mob target is cleared.
 * Ordinary infantry use {@link MountedSoldierGunnery} only in seats that permit
 * handheld weapons.
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
    private static final long CONTACT_MEMORY_TICKS = 60;
    private static final Map<UUID, Contact> recentContacts = new HashMap<>();
    private static final Map<UUID, UUID> vehicleTargets = new HashMap<>();
    private static final Map<UUID, Long> vehicleScanTimes = new HashMap<>();

    private static final class Contact {
        UUID vehicleId;
        UUID targetId;
        Vec3 position;
        Vec3 velocity;
        long seenAt;
        boolean attempted;
    }

    private SbwVehicleGunnery() {}

    /** Safe to call every tick from the frozen-AI branch; throttled and self-gating. */
    public static void tickMounted(SoldierEntity soldier) {
        if (soldier.getRole() != SoldierRole.VEHICLE_CREW) {
            return;
        }
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity mount = soldier.getVehicle();
        if (!SbwCompat.isOperational(mount)) {
            return;
        }
        int seat = SbwVehicles.seatIndexOf(mount, soldier);
        boolean emptyPassengerStation = seat >= 0
            && seat == SbwVehicles.passengerWeaponStationControllerIndex(mount)
            && SbwVehicles.seatHasWeapon(mount, seat)
            && !SbwVehicles.seatHasLoadedWeapon(mount, seat)
            && SbwVehicles.seatAllowsRifleWhenWeaponEmpty(mount, seat)
            && !SbwVehicles.hidesPassenger(mount, soldier);
        if (emptyPassengerStation) {
            // Let rifle targeting own this soldier while the station is empty;
            // otherwise the station muzzle target can repeatedly replace a target
            // that is visible to the rifleman but not from inside the hull.
            SbwVehicles.syncAutoTarget(mount, seat, null);
            MountedSoldierGunnery.tickMounted(soldier);
            return;
        }
        long gameTime = serverLevel.getGameTime();
        UUID soldierId = soldier.getUUID();

        // SBW vehicle hulls are Entity, not LivingEntity, so the ordinary Mob
        // target list cannot contain them. A main-turret controller independently
        // spots visible hostile-occupied hulls and fires through SBW's weapon API.
        boolean mainTurret = seat >= 0 && seat == SbwVehicles.turretControllerIndex(mount)
            && SbwVehicles.seatHasWeapon(mount, seat);
        Entity armorTarget = null;
        Vec3 armorAim = null;
        UUID previousVehicle = vehicleTargets.get(soldierId);
        if (mainTurret && previousVehicle != null) {
            Entity candidate = serverLevel.getEntity(previousVehicle);
            if (validEnemyVehicle(soldier, mount, candidate)) {
                armorAim = visibleVehicleSurface(soldier, mount, seat, candidate);
                if (armorAim != null) armorTarget = candidate;
            }
        }
        Long lastVehicleScan = vehicleScanTimes.get(soldierId);
        if (mainTurret && armorTarget == null
            && (lastVehicleScan == null || gameTime - lastVehicleScan >= RETARGET_INTERVAL_TICKS)) {
            vehicleScanTimes.put(soldierId, gameTime);
            pruneIfNeeded(gameTime);
            double range = StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get();
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Entity candidate : serverLevel.getEntitiesOfClass(Entity.class,
                mount.getBoundingBox().inflate(range), e -> validEnemyVehicle(soldier, mount, e))) {
                double distance = mount.distanceToSqr(candidate);
                if (distance < bestDistance) {
                    Vec3 surface = visibleVehicleSurface(soldier, mount, seat, candidate);
                    if (surface != null) {
                        armorTarget = candidate;
                        armorAim = surface;
                        bestDistance = distance;
                    }
                }
            }
        }
        if (armorTarget != null) {
            vehicleTargets.put(soldierId, armorTarget.getUUID());
            vehicleScanTimes.put(soldierId, gameTime);
            if (soldier.getTarget() != null) soldier.setTarget(null);
            VehicleWeaponController.tickMounted(soldier, mount, seat, null, null, armorTarget, armorAim);
            return;
        }
        vehicleTargets.remove(soldierId);

        // SBW keeps auto-firing a passenger's last target until its Mob target
        // changes. Clear dead, friendly, or hidden targets immediately instead
        // of leaving the turret trained on their last position between scans.
        LivingEntity current = soldier.getTarget();
        if (current != null && !isValidSpottingTarget(soldier, current)) {
            soldier.setTarget(null);
            current = null;
        }

        Long last = lastRetargetBySoldier.get(soldierId);
        if (last == null || gameTime - last >= RETARGET_INTERVAL_TICKS) {
            lastRetargetBySoldier.put(soldierId, gameTime);
            pruneIfNeeded(gameTime);

            if (current == null) {
                double range = StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get();
                List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class,
                    soldier.getBoundingBox().inflate(range),
                    t -> isValidSpottingTarget(soldier, t));
                current = targets.stream()
                    .min(Comparator.comparingDouble(soldier::distanceToSqr))
                    .orElse(null);
                if (current != null) soldier.setTarget(current);
            }
        }

        if (seat >= 0) {
            Contact contact = recentContacts.get(soldierId);
            if (current != null) {
                if (contact == null) contact = new Contact();
                contact.vehicleId = mount.getUUID();
                contact.targetId = current.getUUID();
                contact.position = current.getBoundingBox().getCenter();
                contact.velocity = current.getDeltaMovement();
                contact.seenAt = gameTime;
                contact.attempted = false;
                recentContacts.put(soldierId, contact);
            }
            Vec3 remembered = null;
            if (current == null && contact != null && !contact.attempted
                && mount.getUUID().equals(contact.vehicleId)
                && gameTime >= contact.seenAt && gameTime - contact.seenAt <= CONTACT_MEMORY_TICKS) {
                Entity known = serverLevel.getEntity(contact.targetId);
                // Validate identity/allegiance, but never read the unseen entity's
                // current location or velocity to aim through cover.
                if (known instanceof LivingEntity living && TargetAcquisition.isValidTarget(soldier, living)
                    && !soldier.isFriendlyTo(living) && soldier.isProactiveTarget(living)
                    && living.getVehicle() != mount) {
                    Vec3 lead = contact.velocity.scale(8.0);
                    if (lead.lengthSqr() > 9.0) lead = lead.normalize().scale(3.0);
                    remembered = contact.position.add(lead);
                }
            }
            if (VehicleWeaponController.tickMounted(soldier, mount, seat, current, remembered, null, null)
                && contact != null) contact.attempted = true;
        }
    }

    private static boolean validEnemyVehicle(SoldierEntity observer, Entity own, Entity target) {
        if (target == null || target == own || target.level() != observer.level()
            || !SbwCompat.isOperational(target) || SbwVehicles.isDrone(target)) return false;
        double range = StevesArmyConfig.VEHICLE_CREW_DETECTION_DISTANCE.get();
        if (own.distanceToSqr(target) > range * range) return false;
        boolean hostile = false;
        for (Entity occupant : target.getPassengers()) {
            if (!(occupant instanceof LivingEntity living) || !living.isAlive()) continue;
            if (observer.isFriendlyTo(living)) return false; // never shoot mixed-friendly transports
            if (living.isSpectator() || living instanceof net.minecraft.world.entity.player.Player player
                && player.isCreative()) continue;
            if (observer.isProactiveTarget(living)) hostile = true;
        }
        return hostile;
    }

    /** Require an actual visible hull surface; another hull or wall can occlude it. */
    private static Vec3 visibleVehicleSurface(SoldierEntity observer, Entity own, int seat, Entity enemy) {
        Vec3 from = SbwVehicles.shootPos(own, seat);
        var box = enemy.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3[] samples = {center,
            center.add((box.maxX - box.minX) * 0.2, 0, 0),
            center.add(-(box.maxX - box.minX) * 0.2, 0, 0),
            center.add(0, (box.maxY - box.minY) * 0.2, 0),
            center.add(0, 0, (box.maxZ - box.minZ) * 0.2),
            center.add(0, 0, -(box.maxZ - box.minZ) * 0.2)};
        for (Vec3 sample : samples) {
            Vec3 surface = com.stevesarmy.compat.sbw.SbwVehicleOcclusion.visibleSurface(
                observer.level(), from, sample, enemy, observer);
            if (surface != null) return surface;
        }
        return null;
    }

    /** Spot from the vehicle's muzzle, not the crew's eye inside an enclosed hull. */
    private static boolean isValidSpottingTarget(SoldierEntity soldier, LivingEntity target) {
        Entity vehicle = soldier.getVehicle();
        if (vehicle == null || target == soldier || target.getVehicle() == vehicle
            || target.level() != soldier.level() || !TargetAcquisition.isValidTarget(soldier, target)
            || soldier.isFriendlyTo(target) || !soldier.isProactiveTarget(target)) return false;
        int seat = SbwVehicles.seatIndexOf(vehicle, soldier);
        Vec3 origin = seat >= 0 && SbwVehicles.seatHasWeapon(vehicle, seat)
            ? SbwVehicles.shootPos(vehicle, seat) : soldier.getEyePosition();
        return VisibilityRay.trace(soldier.level(), origin, target.getEyePosition(), soldier).hasContact();
    }

    private static void pruneIfNeeded(long gameTime) {
        if (lastPruneGameTime != Long.MIN_VALUE && gameTime - lastPruneGameTime < PRUNE_INTERVAL_TICKS) {
            return;
        }
        lastPruneGameTime = gameTime;
        lastRetargetBySoldier.entrySet().removeIf(
            entry -> gameTime - entry.getValue() > RETARGET_INTERVAL_TICKS * 20);
        vehicleScanTimes.entrySet().removeIf(e -> gameTime < e.getValue()
            || gameTime - e.getValue() > RETARGET_INTERVAL_TICKS * 20);
        vehicleTargets.keySet().removeIf(id -> !vehicleScanTimes.containsKey(id));
        recentContacts.entrySet().removeIf(entry -> gameTime < entry.getValue().seenAt
            || gameTime - entry.getValue().seenAt > CONTACT_MEMORY_TICKS);
    }
}

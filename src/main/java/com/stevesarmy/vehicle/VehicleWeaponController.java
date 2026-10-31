package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.VehicleTurretRotationPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import com.stevesarmy.compat.sbw.SbwReflection;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Selects crew weapons and supplies deliberately imperfect live and idle aim to SBW. */
public final class VehicleWeaponController {
    private static final int MIN_SWITCH_TICKS = 4;
    private static final float MAX_SCAN_YAW = 48.0f;
    private static final Map<Entity, Map<Integer, TurretState>> STATES = new WeakHashMap<>();

    private VehicleWeaponController() {}

    /** Returns true only when one shot was requested at a recent, unseen contact. */
    public static boolean tickMounted(SoldierEntity gunner, Entity vehicle, int seat,
                                      LivingEntity target, Vec3 lastSeen, Entity armorTarget, Vec3 armorAim) {
        if (gunner == null || gunner.getRole() != SoldierRole.VEHICLE_CREW
            || vehicle == null || vehicle.level().isClientSide || !SbwCompat.isVehicle(vehicle)
            || SbwVehicles.passengerAt(vehicle, seat) != gunner
            || (seat != SbwVehicles.turretControllerIndex(vehicle)
                && seat != SbwVehicles.passengerWeaponStationControllerIndex(vehicle))
            || !SbwVehicles.seatHasWeapon(vehicle, seat)) return false;

        TurretState state = STATES.computeIfAbsent(vehicle, ignored -> new java.util.HashMap<>())
            .computeIfAbsent(seat, ignored -> new TurretState());
        long now = vehicle.level().getGameTime();
        boolean firedAtMemory = false;
        if (armorTarget != null && seat == SbwVehicles.turretControllerIndex(vehicle)) {
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            chooseWeapon(vehicle, seat, armorTarget, state);
            aimAtTarget(vehicle, seat, armorTarget, armorAim, state, now);
            fireAtVehicle(gunner, vehicle, seat, armorTarget, armorAim);
        } else if (target == null && lastSeen != null && seat == SbwVehicles.turretControllerIndex(vehicle)) {
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            firedAtMemory = tryFireAtLastSeen(gunner, vehicle, seat, lastSeen, state);
        } else if (target == null) {
            state.attackTargetId = null;
            state.combatAimInitialized = false;
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                selectReadyIdleWeapon(vehicle, seat, state);
            }
            scanIdle(vehicle, seat, state);
        } else {
            // Keep SBW's native Mob-based fire cadence, but do our own ballistic
            // solution and small, slowly changing aim errors so tanks do not
            // snap to an exact entity UUID every tick.
            SbwVehicles.syncAutoTarget(vehicle, seat, null);
            if (seat == SbwVehicles.turretControllerIndex(vehicle)) {
                chooseWeapon(vehicle, seat, target, state);
            }
            aimAtTarget(vehicle, seat, target, null, state, now);
        }
        // SBW stores these rotations in ordinary fields, not synchronized entity data.
        // Its client normally reproduces UUID aiming itself; our custom scan/aim
        // clears that UUID, so send the authoritative rotations to tracking clients.
        VehicleTurretRotationPacket packet = VehicleTurretRotationPacket.capture(vehicle, seat);
        if (packet != null) NetworkHandler.sendToTracking(vehicle, packet);
        return firedAtMemory;
    }

    /** Avoid leaving an idle crew on an empty or reloading weapon. */
    private static void selectReadyIdleWeapon(Entity vehicle, int seat, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        if (weapons.size() < 2) return;
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        if (weaponAvailable(vehicle, seat, current)) return;

        for (int candidate = 0; candidate < weapons.size(); candidate++) {
            if (candidate == current) continue;
            if (weaponAvailable(vehicle, seat, candidate)) {
                switchWeapon(vehicle, seat, candidate, state, "current weapon empty or reloading");
                return;
            }
        }
    }

    private static void chooseWeapon(Entity vehicle, int seat, Entity target, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        if (weapons.size() < 2) return;

        int primary = 0;
        for (int i = 0; i < weapons.size(); i++) {
            String name = normalizedName(weapons.get(i));
            if (name.contains("cannon") || name.contains("maingun") || name.equals("main")
                || name.contains("laser")) {
                primary = i;
                break;
            }
        }

        // A visible passenger remains a person target even when riding a tank;
        // aim at the rider and use the coax rather than treating the whole
        // occupied vehicle as an armored hull.
        boolean armored = SbwCompat.isVehicle(target);
        int machineGun = -1;
        for (int i = 0; i < weapons.size(); i++) {
            if (i == primary) continue;
            String name = normalizedName(weapons.get(i));
            if (name.contains("machinegun") || name.contains("coax") || name.startsWith("mg")) {
                machineGun = i;
                break;
            }
        }
        // Some vehicle data calls the secondary weapon something generic. Keep
        // a useful alternate on tanks such as the T-90 (Cannon + MachineGun).
        if (machineGun < 0 && "TANK".equals(
            com.stevesarmy.compat.SbwCompat.getVehicleTypeName(vehicle))) {
            for (int i = 0; i < weapons.size(); i++) {
                if (i != primary) {
                    machineGun = i;
                    break;
                }
            }
        }

        // Use the cannon/launcher against armor and the coaxial gun against
        // nearby infantry. At long range the cannon has the better hit chance.
        // If the preferred weapon is empty or reloading, use the other loaded
        // station weapon until it can take over again.
        double distance = vehicle.distanceTo(target);
        int preferred;
        if (armored || machineGun < 0 || distance >= 60.0) {
            preferred = primary;
        } else if (distance <= 50.0) {
            preferred = machineGun;
        } else {
            // Keep the tactical preference in the 10-block transition band so a
            // moving target does not make the crew flick between weapons.
            preferred = state.preferredWeapon == primary || state.preferredWeapon == machineGun
                ? state.preferredWeapon : primary;
        }
        // Keep tactical preference separate from the temporary reload fallback.
        state.preferredWeapon = preferred;
        int alternate = preferred == primary ? machineGun : primary;
        if (!weaponAvailable(vehicle, seat, preferred)) {
            if (alternate >= 0 && weaponAvailable(vehicle, seat, alternate)) {
                preferred = alternate;
            } else {
                // More than two weapons may be present in this seat.
                int ready = -1;
                for (int i = 0; i < weapons.size(); i++) {
                    if (i != preferred && weaponAvailable(vehicle, seat, i)) {
                        ready = i;
                        break;
                    }
                }
                if (ready < 0) return;
                preferred = ready;
            }
        }

        switchWeapon(vehicle, seat, preferred, state,
            armored ? "armored target" : "target range and type");
    }

    private static void switchWeapon(Entity vehicle, int seat, int selected, TurretState state, String reason) {
        long now = vehicle.level().getGameTime();
        int current = SbwVehicles.weaponIndex(vehicle, seat);
        if (current == selected) return;
        // A reloading/empty weapon must not hold up an available alternative.
        if (weaponAvailable(vehicle, seat, current)
            && now - state.lastSwitchAt < MIN_SWITCH_TICKS) return;
        if (SbwVehicles.setWeaponIndex(vehicle, seat, selected)) {
            state.lastSwitchAt = now;
            StevesArmyMod.LOGGER.debug("[VehicleGunnery] vehicle={} seat={} weapon {} -> {} ({})",
                vehicle.getId(), seat, current, selected, reason);
        }
    }

    private static boolean weaponAvailable(Entity vehicle, int seat, int weapon) {
        return SbwVehicles.weaponAvailable(vehicle, seat, weapon);
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void aimAtTarget(Entity vehicle, int seat, Entity target, Vec3 observedPoint,
                                    TurretState state, long now) {
        UUID targetId = target.getUUID();
        var random = vehicle.level().random;
        boolean newTarget = !targetId.equals(state.attackTargetId);
        if (newTarget || now >= state.nextAttackAimAt) {
            state.attackTargetId = targetId;
            // Keep the errors small and slowly varying, but infantry is a much
            // smaller target than a hull. Previously a new engagement began at
            // exactly zero error and took many ticks to reach even 0.3 degrees.
            boolean armor = SbwCompat.isVehicle(target);
            float yawSigma = armor ? 0.38f : 0.45f;
            float pitchSigma = armor ? 0.25f : 0.28f;
            state.desiredAttackYawOffset = Mth.clamp((float) (random.nextGaussian() * yawSigma),
                armor ? -0.8f : -0.9f, armor ? 0.8f : 0.9f);
            state.desiredAttackPitchOffset = Mth.clamp((float) (random.nextGaussian() * pitchSigma),
                armor ? -0.55f : -0.6f, armor ? 0.55f : 0.6f);
            if (newTarget) {
                state.attackYawOffset = state.desiredAttackYawOffset;
                state.attackPitchOffset = state.desiredAttackPitchOffset;
            }
            state.nextAttackAimAt = now + 50 + random.nextInt(41);
        }

        state.attackYawOffset = Mth.lerp(0.035f, state.attackYawOffset, state.desiredAttackYawOffset);
        state.attackPitchOffset = Mth.lerp(0.035f, state.attackPitchOffset, state.desiredAttackPitchOffset);
        Vec3 aimPoint = observedPoint != null ? observedPoint : target.getBoundingBox().getCenter();
        Vec3 velocity = SbwVehicles.firingSolution(vehicle, seat, aimPoint, target.getDeltaMovement());
        if (velocity.lengthSqr() < 1.0e-6) return;

        float yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(velocity.y, Math.max(0.01, horizontal)));
        if (newTarget || !state.combatAimInitialized) {
            state.smoothedYaw = yaw;
            state.smoothedPitch = pitch;
            state.combatAimInitialized = true;
        } else {
            state.smoothedYaw = Mth.rotLerp(0.2f, state.smoothedYaw, yaw);
            state.smoothedPitch = Mth.lerp(0.2f, state.smoothedPitch, pitch);
        }
        Vec3 direction = Vec3.directionFromRotation(
            state.smoothedPitch + state.attackPitchOffset, state.smoothedYaw + state.attackYawOffset);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    /** SBW's mob firing loop accepts only living targets. Fire a non-living hull
     * explicitly, using the selected weapon, SBW cadence, ballistics and ammo gate. */
    private static void fireAtVehicle(SoldierEntity gunner, Entity vehicle, int seat, Entity target,
                                      Vec3 visibleSurface) {
        if (!Boolean.TRUE.equals(SbwReflection.call(vehicle, new String[]{"canShoot"}, gunner))) return;
        Vec3 origin = SbwVehicles.shootPos(vehicle, seat);
        if (visibleSurface == null) return;
        Vec3 targetPoint = visibleSurface;
        Vec3 solution = SbwVehicles.firingSolution(vehicle, seat, targetPoint, target.getDeltaMovement());
        Object barrelObj = SbwReflection.call(vehicle, new String[]{"getShootVec"}, seat, 1.0f);
        if (solution.lengthSqr() < 1.0e-6 || !(barrelObj instanceof Vec3 barrel)
            || barrel.lengthSqr() < 1.0e-6
            || barrel.normalize().dot(solution.normalize()) < Math.cos(Math.toRadians(3.0))
            || !friendlyLaneClear(gunner, vehicle, origin, targetPoint, targetPoint)) return;
        int rpm = SbwReflection.asInt(SbwReflection.call(vehicle, new String[]{"vehicleWeaponRpm"}, seat), 60);
        int interval = Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, rpm)));
        if (vehicle.tickCount % interval == 0) {
            SbwVehicles.shoot(vehicle, gunner, target.getUUID(), targetPoint);
        }
    }

    /** One deliberately bounded attempt, aimed at the stored position, never the hidden entity. */
    private static boolean tryFireAtLastSeen(SoldierEntity gunner, Entity vehicle, int seat,
                                             Vec3 position, TurretState state) {
        List<String> weapons = SbwVehicles.seatWeapons(vehicle, seat);
        int ap = -1;
        for (int i = 0; i < weapons.size(); i++) {
            if (weaponAvailable(vehicle, seat, i) && SbwVehicles.weaponCanPenetrateCover(vehicle, seat, i)) {
                ap = i;
                break;
            }
        }
        if (ap < 0) {
            // Still escape a reloading/empty cannon while waiting for an AP shot.
            selectReadyIdleWeapon(vehicle, seat, state);
            scanIdle(vehicle, seat, state);
            return false;
        }
        switchWeapon(vehicle, seat, ap, state, "recent contact behind cover");
        if (SbwVehicles.weaponIndex(vehicle, seat) != ap) return false;

        Vec3 origin = SbwVehicles.shootPos(vehicle, seat);
        Vec3 solution = SbwVehicles.firingSolution(vehicle, seat, position, Vec3.ZERO);
        if (solution.lengthSqr() < 1.0e-6) return false;
        SbwVehicles.aimWeapon(vehicle, seat, solution);
        // Only fire at a real nearby wall; smoke or an open lane isn't proof of
        // penetrable cover. SBW's AP block pass runs at most 15 blocks past impact.
        BlockHitResult hit = vehicle.level().clip(new ClipContext(origin, position,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getLocation().distanceTo(position) > 14.0
            || origin.distanceTo(position) < 12.0
            || vehicle.level().getBlockState(hit.getBlockPos()).getDestroySpeed(vehicle.level(), hit.getBlockPos()) < 0
            || !friendlyLaneClear(gunner, vehicle, origin, position, hit.getLocation())) return false;

        Object barrelObj = SbwReflection.call(vehicle, new String[]{"getShootVec"}, seat, 1.0f);
        if (!(barrelObj instanceof Vec3 barrel) || barrel.lengthSqr() < 1.0e-6
            || barrel.normalize().dot(solution.normalize()) < Math.cos(Math.toRadians(2.0))) return false;
        int rpm = SbwReflection.asInt(SbwReflection.call(vehicle, new String[]{"vehicleWeaponRpm"}, seat), 60);
        int interval = Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, rpm)));
        if (vehicle.tickCount % interval != 0) return false;
        return SbwVehicles.shootAtPosition(vehicle, gunner, seat, position);
    }

    private static boolean friendlyLaneClear(SoldierEntity gunner, Entity vehicle, Vec3 origin, Vec3 target,
                                             Vec3 impact) {
        AABB bounds = new AABB(origin, target).inflate(5.0);
        for (LivingEntity other : vehicle.level().getEntitiesOfClass(LivingEntity.class, bounds)) {
            if (other == gunner || other.getVehicle() == vehicle || !other.isAlive()
                || !gunner.isFriendlyTo(other)) continue;
            if (other.getBoundingBox().inflate(1.0).clip(origin, target).isPresent()
                || other.distanceToSqr(target) < 25.0 || other.distanceToSqr(impact) < 25.0) return false;
        }
        return true;
    }

    private static void scanIdle(Entity vehicle, int seat, TurretState state) {
        long now = vehicle.level().getGameTime();
        var random = vehicle.level().random;
        boolean moving = vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.0036;

        if (!state.scanInitialized) {
            state.scanInitialized = true;
            state.wasMoving = moving;
        }
        if (now >= state.nextScanAt || moving != state.wasMoving) {
            state.wasMoving = moving;
            // Give SBW a stable bearing to track. Its own turret turn speed provides
            // the mechanical slew; slowly walking a synthetic bearing made this
            // movement too small to see and let each new idle target cancel the last.
            float target = (float) (random.nextGaussian() * (moving ? 14.0 : 19.0));
            if (random.nextFloat() < (moving ? 0.12f : 0.18f)) {
                target = (random.nextBoolean() ? 1.0f : -1.0f)
                    * (29.0f + random.nextFloat() * 17.0f);
            }
            state.scanTarget = Mth.clamp(target, -MAX_SCAN_YAW, MAX_SCAN_YAW);
            // Vary above and below horizontal, not only upwards. SBW enforces
            // the particular vehicle's real elevation/depression limits.
            state.scanPitch = -3.0f + random.nextFloat() * 6.0f;
            state.nextScanAt = now + (moving ? 48 + random.nextInt(45) : 65 + random.nextInt(80));
        }

        Vec3 direction = Vec3.directionFromRotation(state.scanPitch,
            vehicle.getYRot() + state.scanTarget);
        SbwVehicles.aimWeapon(vehicle, seat, direction);
    }

    public static void forget(Entity vehicle) {
        if (vehicle != null) STATES.remove(vehicle);
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static final class TurretState {
        private float scanTarget;
        private float scanPitch = -2.0f;
        private UUID attackTargetId;
        private float attackYawOffset;
        private float attackPitchOffset;
        private long nextAttackAimAt;
        private float desiredAttackYawOffset;
        private float desiredAttackPitchOffset;
        private boolean combatAimInitialized;
        private float smoothedYaw;
        private float smoothedPitch;
        private long nextScanAt;
        private boolean scanInitialized;
        private boolean wasMoving;
        private int preferredWeapon = -1;
        private long lastSwitchAt = Long.MIN_VALUE / 2;
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.combat.ArmorRoleManager;
import com.stevesarmy.combat.FriendlyFireChecker;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/** Makes dismounted soldiers create distance from hostile SBW drones. */
public final class DroneEvasionGoal extends Goal {
    private static final double START_RADIUS = 24.0;
    private static final double CONTINUE_RADIUS = 30.0;
    private static final double FIRE_RANGE = 36.0;
    private static final double FLEE_DISTANCE = 10.0;
    private static final double SPEED = 1.2;
    private static final int REPATH_TICKS = 12;
    private static final int[] ANGLE_OFFSETS = {0, 35, -35, 70, -70, 110, -110};

    private final SoldierEntity soldier;
    private Entity drone;
    private int repathIn;
    private int nextShotTick;

    public DroneEvasionGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!eligible()) return false;
        drone = nearestHostileDrone(START_RADIUS);
        return drone != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!eligible()) return false;
        drone = nearestHostileDrone(CONTINUE_RADIUS);
        return drone != null;
    }

    @Override
    public void start() {
        repathIn = 0;
        moveAway();
    }

    @Override
    public void tick() {
        if (drone == null || drone.isRemoved()) {
            drone = nearestHostileDrone(CONTINUE_RADIUS);
        }
        if (drone == null) return;
        soldier.getLookControl().setLookAt(drone, 30.0F, 30.0F);
        shootAtDrone();
        if (--repathIn <= 0) moveAway();
    }

    @Override
    public void stop() {
        drone = null;
        soldier.getNavigation().stop();
    }

    private boolean eligible() {
        return soldier.isAlive() && !soldier.isPassenger()
            && !soldier.isVehicleCrewActive() && !soldier.isCrewSeated()
            && SbwCompat.hasVehicles();
    }

    private void shootAtDrone() {
        if (drone == null || soldier.distanceToSqr(drone) > FIRE_RANGE * FIRE_RANGE
            || !GunIntegration.isAnyGunLoaded() || !GunIntegration.hasGun(soldier)
            || ArmorRoleManager.isAtGunStack(soldier.getMainHandItem())) return;

        Vec3 aimPoint = drone.getBoundingBox().getCenter().add(drone.getDeltaMovement().scale(3.0));
        Vec3 eye = soldier.getEyePosition();
        if (!VisibilityRay.trace(soldier.level(), eye, aimPoint, soldier).hasContact()) return;
        GunIntegration.aim(soldier, true);
        if (GunIntegration.isDrawing(soldier) || GunIntegration.isReloading(soldier)
            || GunIntegration.isBolting(soldier)) return;
        if (GunIntegration.getCurrentAmmo(soldier) <= 0) {
            if (GunIntegration.canReload(soldier)) GunIntegration.reload(soldier);
            return;
        }

        Vec3 toDrone = aimPoint.subtract(eye);
        double horizontal = Math.sqrt(toDrone.x * toDrone.x + toDrone.z * toDrone.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-toDrone.x, toDrone.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(toDrone.y, Math.max(0.01, horizontal)));
        if (Math.abs(Mth.wrapDegrees(yaw - soldier.getYHeadRot())) > 12.0f
            || Math.abs(pitch - soldier.getXRot()) > 16.0f
            || !FriendlyFireChecker.isSafeToShoot(soldier, aimPoint, 0.7f)
            || soldier.tickCount < nextShotTick) return;

        nextShotTick = soldier.tickCount + 5;
        switch (GunIntegration.shootAtPosition(soldier, aimPoint)) {
            case NO_AMMO -> {
                if (GunIntegration.canReload(soldier)) GunIntegration.reload(soldier);
            }
            case NEED_BOLT -> GunIntegration.bolt(soldier);
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
    }

    private Entity nearestHostileDrone(double radius) {
        AABB area = soldier.getBoundingBox().inflate(radius);
        List<Entity> drones = soldier.level().getEntities(soldier, area,
            entity -> SbwVehicles.isHostileDrone(soldier, entity));
        return drones.stream()
            .filter(entity -> soldier.distanceToSqr(entity) <= radius * radius)
            .min(java.util.Comparator.comparingDouble(soldier::distanceToSqr))
            .orElse(null);
    }

    private void moveAway() {
        repathIn = REPATH_TICKS;
        if (drone == null) return;

        Vec3 away = soldier.position().subtract(drone.position()).multiply(1.0, 0.0, 1.0);
        if (away.lengthSqr() < 1.0e-4) {
            double angle = soldier.getRandom().nextDouble() * Math.PI * 2.0;
            away = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        } else {
            away = away.normalize();
        }

        for (int offset : ANGLE_OFFSETS) {
            double angle = Math.atan2(away.z, away.x) + Math.toRadians(offset);
            double x = soldier.getX() + Math.cos(angle) * FLEE_DISTANCE;
            double z = soldier.getZ() + Math.sin(angle) * FLEE_DISTANCE;
            BlockPos destination = BlockPos.containing(x, soldier.getY(), z);
            Path path = soldier.getNavigation().createPath(destination, 0);
            if (path != null && path.canReach() && path.getNodeCount() > 1) {
                soldier.getNavigation().moveTo(path, SPEED);
                return;
            }
        }
    }
}

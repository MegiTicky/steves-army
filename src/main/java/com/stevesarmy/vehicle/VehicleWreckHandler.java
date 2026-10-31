package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class VehicleWreckHandler {

    private VehicleWreckHandler() {
    }

    public static void ejectAll(Entity vehicle) {
        if (vehicle == null) return;

        List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
        int ejected = 0;

        for (Entity passenger : passengers) {
            if (!(passenger instanceof SoldierEntity soldier)) continue;
            dismount(soldier);
            ejected++;
        }

        VehicleDriverRegistry.remove(vehicle);
        VehicleWeaponController.forget(vehicle);

        if (ejected > 0) {
            StevesArmyMod.LOGGER.info("[SBW] ejected {} soldiers from {}",
                ejected, vehicle.getName().getString());
        }
    }

    public static void dismount(SoldierEntity soldier) {
        if (soldier == null) return;
        Entity vehicle = soldier.getVehicle();
        VehicleBoardingManager.cancel(soldier);
        if (vehicle != null && SbwCompat.isVehicle(vehicle) && SbwVehicles.isDriver(vehicle, soldier)) {
            VehicleDriver driver = VehicleDriverRegistry.get(vehicle);
            if (driver != null) driver.orderDismount();
            VehicleDriverRegistry.remove(vehicle);
            SbwVehicles.clearInputs(vehicle);
        }
        soldier.stopRiding();
        VehicleMountPolicy.revoke(soldier);
        if (vehicle == null) return;

        Vec3 safePosition = findSafeDismountPosition(soldier, vehicle);
        soldier.setPos(safePosition.x, safePosition.y, safePosition.z);
        soldier.setDeltaMovement(Vec3.ZERO);
        soldier.setOnGround(true);
    }

    public static int dismountAll(List<SoldierEntity> soldiers) {
        int count = 0;
        for (SoldierEntity soldier : soldiers) {
            Entity ride = soldier.getVehicle();
            if (ride == null || !SbwCompat.isVehicle(ride)) continue;
            dismount(soldier);
            count++;
        }
        return count;
    }

    private static Vec3 findSafeDismountPosition(SoldierEntity soldier, Entity vehicle) {
        Level level = vehicle.level();
        AABB hull = vehicle.getBoundingBox();
        double centerX = (hull.minX + hull.maxX) * 0.5;
        double centerZ = (hull.minZ + hull.maxZ) * 0.5;
        double margin = soldier.getBbWidth() * 0.5 + 0.75;
        double extentX = hull.getXsize() * 0.5 + margin;
        double extentZ = hull.getZsize() * 0.5 + margin;
        Vec3 previous = soldier.position();
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < 16; i++) {
            double angle = (i + Math.floorMod(vehicle.getId(), 16)) * (Math.PI * 2.0 / 16.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double edgeScale = 1.0 / Math.max(Math.abs(cos), Math.abs(sin));
            double x = centerX + cos * edgeScale * extentX;
            double z = centerZ + sin * edgeScale * extentZ;
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(x, vehicle.getY(), z).getX(),
                BlockPos.containing(x, vehicle.getY(), z).getZ());
            if (Math.abs(surfaceY - previous.y) > Math.max(4.0, vehicle.getBbHeight() + 2.0)) continue;
            Vec3 candidate = new Vec3(x, surfaceY, z);
            if (!isSafePosition(soldier, vehicle, candidate)) continue;
            double distance = candidate.distanceToSqr(previous);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best != null) return best;

        // Flying vehicles and roofs over uneven terrain may have no ground-level
        // exit. Try clear points beside the hull at its current elevation.
        for (int i = 0; i < 16; i++) {
            double angle = (i + Math.floorMod(vehicle.getId(), 16)) * (Math.PI * 2.0 / 16.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double edgeScale = 1.0 / Math.max(Math.abs(cos), Math.abs(sin));
            Vec3 candidate = new Vec3(
                centerX + cos * edgeScale * extentX,
                vehicle.getY() + 0.5,
                centerZ + sin * edgeScale * extentZ);
            if (isSafePosition(soldier, vehicle, candidate)) return candidate;
        }

        // A final above-hull exit avoids leaving the soldier embedded if all
        // surrounding ground is blocked.
        return new Vec3(centerX, hull.maxY + 0.25, centerZ);
    }

    private static boolean isSafePosition(SoldierEntity soldier, Entity vehicle, Vec3 position) {
        AABB body = soldier.getBoundingBox().move(position.subtract(soldier.position()));
        if (body.intersects(vehicle.getBoundingBox().inflate(0.05))
            || !vehicle.level().noCollision(soldier, body)
            || vehicle.level().containsAnyLiquid(body)) {
            return false;
        }
        return vehicle.level().getEntitiesOfClass(LivingEntity.class, body.inflate(0.05),
            entity -> entity != soldier && entity.isAlive()).isEmpty();
    }
}

package com.stevesarmy.vehicle;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reserves an SBW seat and has the soldier walk to the vehicle before boarding. */
public final class VehicleBoardingManager {
    private static final double WALK_SPEED = 1.15;
    private static final int REPATH_TICKS = 10;
    private static final int REQUEST_TIMEOUT_TICKS = 1200;
    private static final Map<UUID, BoardingRequest> REQUESTS = new HashMap<>();

    private VehicleBoardingManager() {}

    public static boolean requestBoard(SoldierEntity soldier, Entity vehicle) {
        if (soldier == null || vehicle == null || !(soldier.level() instanceof ServerLevel)
            || !VehicleMountPolicy.canBoard(soldier, vehicle)) {
            return false;
        }
        BoardingRequest old = REQUESTS.get(soldier.getUUID());
        if (old != null && old.vehicle.get() == vehicle) return true;
        if (old != null) REQUESTS.remove(soldier.getUUID());

        int seat = reserveSeat(soldier, vehicle);
        if (seat < 0) return false;

        soldier.getNavigation().stop();
        soldier.cancelCoverMovement();
        soldier.clearAttackTarget();
        soldier.setTarget(null);
        soldier.setFiringProne(false);
        soldier.setDeltaMovement(Vec3.ZERO);
        REQUESTS.put(soldier.getUUID(), new BoardingRequest(vehicle, seat,
            soldier.level().getGameTime() + REQUEST_TIMEOUT_TICKS));
        return true;
    }

    public static boolean isBoarding(SoldierEntity soldier) {
        return soldier != null && REQUESTS.containsKey(soldier.getUUID());
    }

    public static void cancel(SoldierEntity soldier) {
        if (soldier == null) return;
        if (REQUESTS.remove(soldier.getUUID()) != null && !soldier.isPassenger()) {
            soldier.getNavigation().stop();
        }
    }

    public static void tick(SoldierEntity soldier) {
        if (soldier == null) return;
        BoardingRequest request = REQUESTS.get(soldier.getUUID());
        if (request == null) return;

        Entity vehicle = request.vehicle.get();
        Level level = soldier.level();
        if (!soldier.isAlive() || !(level instanceof ServerLevel)
            || vehicle == null || vehicle.isRemoved() || vehicle.level() != level
            || SbwVehicles.isWrecked(vehicle) || soldier.isPassenger()
            || level.getGameTime() >= request.expiresAt
            || SbwVehicles.passengerAt(vehicle, request.seat) != null) {
            cancel(soldier);
            return;
        }

        if (isAtVehicleSide(soldier, vehicle)) {
            soldier.getNavigation().stop();
            if (VS2Compat.seatSoldierOnEntityAtSeat(soldier, vehicle, request.seat)) {
                REQUESTS.remove(soldier.getUUID());
                if (request.seat == 0) VehicleDriverRegistry.onBoarded(soldier, vehicle);
                if (soldier.getRole() != SoldierRole.VEHICLE_CREW) soldier.setTarget(null);
            } else {
                cancel(soldier);
            }
            return;
        }

        long now = level.getGameTime();
        Vec3 vehiclePosition = vehicle.position();
        if (now >= request.nextPathAt
            || request.lastVehiclePosition == null
            || request.lastVehiclePosition.distanceToSqr(vehiclePosition) > 2.25) {
            request.lastVehiclePosition = vehiclePosition;
            request.nextPathAt = now + REPATH_TICKS;
            navigateToSide(soldier, vehicle, request);
        }
    }

    private static int reserveSeat(SoldierEntity soldier, Entity vehicle) {
        int capacity = SbwVehicles.maxPassengers(vehicle);
        if (capacity <= 0) return -1;
        boolean crew = soldier.getRole() == SoldierRole.VEHICLE_CREW;
        if (crew && seatAvailable(vehicle, 0)) return 0;
        if (!crew) {
            int rifleSeat = SbwVehicles.firstFreeVisibleHandheldPassengerSeat(vehicle);
            if (rifleSeat >= 1 && seatAvailable(vehicle, rifleSeat)) return rifleSeat;
        }
        for (int seat = 1; seat < capacity; seat++) {
            if (seatAvailable(vehicle, seat)) return seat;
        }
        return -1;
    }

    private static boolean seatAvailable(Entity vehicle, int seat) {
        if (SbwVehicles.passengerAt(vehicle, seat) != null) return false;
        long now = vehicle.level().getGameTime();
        REQUESTS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now
            || entry.getValue().vehicle.get() == null);
        for (BoardingRequest request : REQUESTS.values()) {
            if (request.vehicle.get() == vehicle && request.seat == seat) return false;
        }
        return true;
    }

    private static boolean isAtVehicleSide(SoldierEntity soldier, Entity vehicle) {
        AABB box = vehicle.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - soldier.getX(), 0), soldier.getX() - box.maxX);
        double dz = Math.max(Math.max(box.minZ - soldier.getZ(), 0), soldier.getZ() - box.maxZ);
        return dx * dx + dz * dz <= 2.89 && Math.abs(soldier.getY() - vehicle.getY()) <= 3.0;
    }

    private static void navigateToSide(SoldierEntity soldier, Entity vehicle, BoardingRequest request) {
        if (!(soldier.level() instanceof ServerLevel level)) return;
        AABB box = vehicle.getBoundingBox();
        double halfWidth = soldier.getBbWidth() * 0.5 + 0.8;
        double extentX = box.getXsize() * 0.5 + halfWidth;
        double extentZ = box.getZsize() * 0.5 + halfWidth;
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        List<ApproachCandidate> candidates = new ArrayList<>(16);

        for (int i = 0; i < 16; i++) {
            double angle = i * (Math.PI * 2.0 / 16.0);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double edgeScale = 1.0 / Math.max(Math.abs(cos), Math.abs(sin));
            int x = net.minecraft.util.Mth.floor(centerX + cos * extentX * edgeScale);
            int z = net.minecraft.util.Mth.floor(centerZ + sin * extentZ * edgeScale);
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(surfaceY - soldier.getY()) > 4.0) continue;
            Vec3 target = new Vec3(x + 0.5, surfaceY, z + 0.5);
            AABB body = soldier.getBoundingBox().move(target.subtract(soldier.position()));
            if (!level.noCollision(soldier, body) || body.intersects(box.inflate(0.05))) continue;
            candidates.add(new ApproachCandidate(target, soldier.position().distanceToSqr(target)));
        }

        candidates.sort(Comparator.comparingDouble(ApproachCandidate::distance));
        for (ApproachCandidate candidate : candidates) {
            Path path = soldier.getNavigation().createPath(BlockPos.containing(candidate.position), 0);
            if (path != null && path.canReach()
                && soldier.getNavigation().moveTo(path, WALK_SPEED)) {
                return;
            }
        }
    }

    private record ApproachCandidate(Vec3 position, double distance) {}

    private static final class BoardingRequest {
        private final WeakReference<Entity> vehicle;
        private final int seat;
        private final long expiresAt;
        private long nextPathAt;
        private Vec3 lastVehiclePosition;

        private BoardingRequest(Entity vehicle, int seat, long expiresAt) {
            this.vehicle = new WeakReference<>(vehicle);
            this.seat = seat;
            this.expiresAt = expiresAt;
        }
    }
}

package com.stevesarmy.vehicle;

import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Spacing rules for vehicles whose drivers belong to the same fireteam. */
public final class VehicleConvoy {

    private static final double MIN_FOLLOW_GAP = 8.0;
    private static final double MAX_FOLLOW_GAP = 14.0;
    private static final double EXTRA_FOLLOW_GAP = 4.0;

    private VehicleConvoy() {
    }

    /** Nearest same-fireteam vehicle ahead on the route from {@code self} to {@code lead}. */
    public static Entity vehicleAheadOf(Entity self, Entity lead) {
        if (self == null || lead == null || self.level() != lead.level()) return null;

        Vec3 route = horizontal(lead.position().subtract(self.position()));
        double routeLength = route.length();
        if (routeLength < 1.0) return null;
        Vec3 direction = route.scale(1.0 / routeLength);
        Vec3 lateralAxis = new Vec3(-direction.z, 0.0, direction.x);

        VehicleDriver selfDriver = VehicleDriverRegistry.get(self);
        SoldierEntity selfSoldier = selfDriver != null ? selfDriver.driverSoldier() : null;
        if (selfSoldier == null) return null;

        Entity best = null;
        double bestAlong = Double.MAX_VALUE;
        for (VehicleDriver driver : VehicleDriverRegistry.all()) {
            Entity other = driver.vehicle();
            SoldierEntity otherSoldier = driver.driverSoldier();
            if (other == null || other == self || other == lead || otherSoldier == null) continue;
            if (other.level() != self.level() || !SbwCompat.isVehicle(other)
                || SbwVehicles.isWrecked(other) || !sameFireTeam(selfSoldier, otherSoldier)) {
                continue;
            }

            Vec3 offset = horizontal(other.position().subtract(self.position()));
            double along = offset.dot(direction);
            if (along <= 0.0 || along >= routeLength || along >= bestAlong) continue;
            double lateral = Math.abs(offset.dot(lateralAxis));
            double corridor = (self.getBbWidth() + other.getBbWidth()) * 0.75 + 2.0;
            if (lateral > corridor) continue;

            best = other;
            bestAlong = along;
        }
        return best;
    }

    /** Centre-to-centre gap that leaves room for both hulls and the lead's movement. */
    public static double safeFollowDistance(Entity follower, Entity target) {
        if (follower == null || target == null) return MIN_FOLLOW_GAP;
        double hullGap = follower.getBbWidth() / 2.0 + target.getBbWidth() / 2.0
            + EXTRA_FOLLOW_GAP;
        return Math.max(MIN_FOLLOW_GAP, Math.min(MAX_FOLLOW_GAP, hullGap));
    }

    public static boolean sameFireTeam(SoldierEntity first, SoldierEntity second) {
        if (first == null || second == null || first == second) return false;
        Optional<UUID> firstOwner = first.getOwnerUUID();
        Optional<UUID> secondOwner = second.getOwnerUUID();
        return firstOwner.isPresent() && firstOwner.equals(secondOwner)
            && first.getFireTeam() == second.getFireTeam();
    }

    private static Vec3 horizontal(Vec3 value) {
        return new Vec3(value.x, 0.0, value.z);
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class FriendlyFireChecker {

    private static final float MIN_SPREAD_ANGLE = 5.0f;
    private static final float MAX_SPREAD_ANGLE = 10.0f;
    private static final double SEARCH_RADIUS = 25.0;

    public static boolean isSafeToShoot(SoldierEntity shooter,
                                         Vec3 aimPoint,
                                         float accuracy) {
        List<LivingEntity> friendlies = getNearbyFriendlies(shooter);
        if (friendlies.isEmpty()) return true;

        float spreadAngle = calculateSpreadAngle(accuracy);
        Vec3 origin = shooter.getEyePosition();

        for (LivingEntity friendly : friendlies) {
            if (isInFiringLane(shooter, origin, aimPoint, spreadAngle, friendly)) {
                return false;
            }
        }

        return true;
    }

    private static List<LivingEntity> getNearbyFriendlies(SoldierEntity shooter) {
        List<LivingEntity> friendlies = new ArrayList<>();

        LivingEntity owner = shooter.getOwner();
        if (owner != null && owner.isAlive()) {
            friendlies.add(owner);
        }

        AABB searchBox = shooter.getBoundingBox().inflate(SEARCH_RADIUS);
        List<SoldierEntity> nearbySoldiers = shooter.level().getEntitiesOfClass(
            SoldierEntity.class, searchBox);

        for (SoldierEntity soldier : nearbySoldiers) {
            if (soldier != shooter && shooter.isFriendlyTo(soldier)) {
                friendlies.add(soldier);
            }
        }

        return friendlies;
    }

    private static boolean isInFiringLane(SoldierEntity shooter,
                                          Vec3 origin,
                                          Vec3 targetPoint,
                                          float spreadAngleDegrees,
                                          LivingEntity entity) {
        AABB box = entity.getBoundingBox();
        double distanceToTarget = origin.distanceTo(targetPoint);

        if (origin.distanceTo(entity.position()) > distanceToTarget + 2.0) {
            return false;
        }

        Vec3 toTarget = targetPoint.subtract(origin).normalize();

        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = toTarget.cross(up).normalize();
        if (right.lengthSqr() < 0.001) {
            right = new Vec3(1, 0, 0);
        }
        Vec3 vertical = right.cross(toTarget).normalize();

        double halfAngleRad = Math.toRadians(spreadAngleDegrees / 2.0);
        double offset = Math.sin(halfAngleRad);
        Vec3[] rayDirections = {
            toTarget,
            toTarget.add(vertical.scale(offset)).normalize(),
            toTarget.subtract(vertical.scale(offset)).normalize()
        };

        for (Vec3 rayDirection : rayDirections) {
            Vec3 rayEnd = origin.add(rayDirection.scale(distanceToTarget + 2.0));
            java.util.Optional<Vec3> hit = box.clip(origin, rayEnd);
            if (hit.isPresent()
                && VisibilityRay.trace(shooter.level(), origin, hit.get(), shooter).hasContact()) {
                return true;
            }
        }

        return false;
    }

    private static float calculateSpreadAngle(float accuracy) {
        return MIN_SPREAD_ANGLE + (1.0f - accuracy) * (MAX_SPREAD_ANGLE - MIN_SPREAD_ANGLE);
    }
}

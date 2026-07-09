package com.stevesarmy.combat;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FriendlyFireChecker {
    
    private static final float MIN_CONE_ANGLE = 5.0f;
    private static final float MAX_CONE_ANGLE = 15.0f;
    private static final double SEARCH_RADIUS = 25.0;
    
    public static boolean isSafeToShoot(SoldierEntity shooter, 
                                         Vec3 aimPoint,
                                         float accuracy) {
        List<LivingEntity> friendlies = getNearbyFriendlies(shooter);
        if (friendlies.isEmpty()) return true;
        
        float coneAngle = calculateConeAngle(accuracy);
        Vec3 origin = shooter.getEyePosition();
        
        for (LivingEntity friendly : friendlies) {
            if (isInCone(origin, aimPoint, coneAngle, friendly)) {
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
    
    private static boolean isInCone(Vec3 origin, Vec3 targetPoint, 
                                     float coneAngleDegrees, 
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
        Vec3 actualUp = right.cross(toTarget).normalize();
        
        double halfAngleRad = Math.toRadians(coneAngleDegrees / 2.0);
        
        int rayCount = 8;
        for (int i = 0; i < rayCount; i++) {
            double angle = 2 * Math.PI * i / rayCount;
            
            double offsetRight = Math.sin(angle) * Math.sin(halfAngleRad);
            double offsetUp = Math.cos(angle) * Math.sin(halfAngleRad);
            
            Vec3 rayDir = toTarget
                .add(right.scale(offsetRight))
                .add(actualUp.scale(offsetUp))
                .normalize();
            
            Vec3 rayEnd = origin.add(rayDir.scale(distanceToTarget + 2.0));
            
            if (rayIntersectsAABB(origin, rayEnd, box)) {
                return true;
            }
        }
        
        if (rayIntersectsAABB(origin, targetPoint, box)) {
            return true;
        }
        
        return false;
    }
    
    private static boolean rayIntersectsAABB(Vec3 start, Vec3 end, AABB box) {
        return box.clip(start, end).isPresent();
    }
    
    private static float calculateConeAngle(float accuracy) {
        return MIN_CONE_ANGLE + (1.0f - accuracy) * (MAX_CONE_ANGLE - MIN_CONE_ANGLE);
    }
}

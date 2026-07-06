package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = "steves_army")
public class IncomingFireHandler {

    private static final double NEAR_MISS_THRESHOLD = 3.0;

    private static final Map<Entity, BulletSnapshot> trackedBullets = new HashMap<>();

    private record BulletSnapshot(Vec3 pos, Vec3 delta) {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof SoldierEntity soldier) {
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager != null) {
                LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity a ? a : null;
                coverManager.onTakeDamage(attacker);

                if (attacker != null) {
                    coverManager.onIncomingFire(attacker);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity().getType() == EntityKineticBullet.TYPE) {
            trackedBullets.put(event.getEntity(), null);
        }
    }

    public static void tick() {
        trackedBullets.entrySet().removeIf(entry -> {
            Entity bullet = entry.getKey();
            if (!bullet.isAlive()) return true;

            LivingEntity shooter = null;
            if (bullet instanceof net.minecraft.world.entity.projectile.Projectile proj) {
                shooter = proj.getOwner() instanceof LivingEntity owner ? owner : null;
            }

            BulletSnapshot prev = entry.getValue();
            Vec3 currentPos = bullet.position();
            Vec3 currentDelta = bullet.getDeltaMovement();
            float speed = (float)currentDelta.length();

            if (prev != null) {
                Vec3 prevEnd = prev.pos.add(prev.delta);
                checkNearMissLineSegment(bullet.level(), prev.pos, prevEnd, speed, shooter);
            }

            entry.setValue(new BulletSnapshot(currentPos, currentDelta));
            return false;
        });
    }

    public static void checkNearMissLineSegment(Level level, Vec3 start, Vec3 end) {
        checkNearMissLineSegment(level, start, end, 1.0f, null);
    }

    public static void checkNearMissLineSegment(Level level, Vec3 start, Vec3 end, float bulletSpeed) {
        checkNearMissLineSegment(level, start, end, bulletSpeed, null);
    }

    public static void checkNearMissLineSegment(Level level, Vec3 start, Vec3 end, float bulletSpeed, @Nullable LivingEntity shooter) {
        Vec3 segment = end.subtract(start);
        double segmentLenSq = segment.lengthSqr();
        if (segmentLenSq < 0.01) return;

        AABB searchBox = new AABB(
            Math.min(start.x, end.x) - NEAR_MISS_THRESHOLD,
            Math.min(start.y, end.y) - NEAR_MISS_THRESHOLD,
            Math.min(start.z, end.z) - NEAR_MISS_THRESHOLD,
            Math.max(start.x, end.x) + NEAR_MISS_THRESHOLD,
            Math.max(start.y, end.y) + NEAR_MISS_THRESHOLD,
            Math.max(start.z, end.z) + NEAR_MISS_THRESHOLD
        );

        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, searchBox)) {
            if (shooter != null && soldier == shooter) continue;

            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager == null) continue;

            Vec3 toPoint = soldier.position().subtract(start);
            double t = toPoint.dot(segment) / segmentLenSq;
            t = Mth.clamp(t, 0.0, 1.0);
            Vec3 closestPoint = start.add(segment.scale(t));

            if (soldier.position().distanceTo(closestPoint) < NEAR_MISS_THRESHOLD) {
                coverManager.onNearMiss(closestPoint, soldier, bulletSpeed, shooter);
            }
        }
    }

    public static void checkNearMiss(SoldierEntity soldier, Vec3 bulletPosition) {
        if (soldier == null || bulletPosition == null) return;

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager == null) return;

        double distance = soldier.position().distanceTo(bulletPosition);
        if (distance < NEAR_MISS_THRESHOLD) {
            coverManager.onNearMiss(bulletPosition, soldier);
        }
    }
}
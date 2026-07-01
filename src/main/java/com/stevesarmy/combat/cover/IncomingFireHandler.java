package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "steves_army")
public class IncomingFireHandler {
    
    private static final Map<UUID, Vec3> recentProjectilePaths = new HashMap<>();
    private static final long PROJECTILE_MEMORY_MS = 500;
    private static final double NEAR_MISS_THRESHOLD = 3.0;
    
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof SoldierEntity soldier) {
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager != null) {
                coverManager.onTakeDamage();
                
                if (event.getSource().getEntity() instanceof LivingEntity attacker) {
                    coverManager.onIncomingFire(attacker);
                }
            }
        }
    }
    
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        Vec3 projectilePos = projectile.position();
        Vec3 projectileMotion = projectile.getDeltaMovement();
        
        if (projectileMotion.lengthSqr() > 0.01) {
            Vec3 projectilePath = projectilePos.add(projectileMotion.normalize().scale(10));
            recentProjectilePaths.put(projectile.getUUID(), projectilePath);
        }
        
        checkNearMissForSoldiers(projectile, projectilePos, projectileMotion);
    }
    
    private static void checkNearMissForSoldiers(Projectile projectile, Vec3 projectilePos, Vec3 projectileMotion) {
        if (projectileMotion.lengthSqr() < 0.01) return;
        
        Level level = projectile.level();
        if (level.isClientSide) return;
        
        Vec3 bulletPath = projectilePos.add(projectileMotion.normalize().scale(10));
        
        for (SoldierEntity soldier : level.getEntitiesOfClass(
                SoldierEntity.class,
                AABB.ofSize(projectilePos, 16, 16, 16))) {
            double dist = soldier.position().distanceTo(bulletPath);
            if (dist < NEAR_MISS_THRESHOLD) {
                CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
                if (coverManager != null) {
                    coverManager.onNearMiss(bulletPath, soldier);
                }
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
    
    public static void clear() {
        recentProjectilePaths.clear();
    }
    
    public static void tick() {
        long currentTime = System.currentTimeMillis();
        recentProjectilePaths.entrySet().removeIf(entry -> {
            return currentTime - entry.getKey().hashCode() > PROJECTILE_MEMORY_MS;
        });
    }
}
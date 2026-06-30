package com.stevesarmy.combat.cover;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityEvent;
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
        
        checkNearMissForSoldiers(projectilePos, projectileMotion);
    }
    
    private static void checkNearMissForSoldiers(Vec3 projectilePos, Vec3 projectileMotion) {
        if (projectileMotion.lengthSqr() < 0.01) return;
        
        Vec3 projectileDir = projectileMotion.normalize();
        Vec3 futurePath = projectilePos.add(projectileDir.scale(NEAR_MISS_THRESHOLD * 2));
        
        // This would need to iterate over nearby soldiers in the level
        // For now, we'll handle this via the hurt event and soldier tick
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
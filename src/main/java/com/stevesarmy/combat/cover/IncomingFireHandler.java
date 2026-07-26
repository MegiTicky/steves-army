package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = "steves_army")
public class IncomingFireHandler {

    private static final double NEAR_MISS_THRESHOLD = 3.0;

    private static final Map<Entity, BulletSnapshot> trackedBullets = new HashMap<>();

    // CBC projectile tracking
    private static final String CBC_PACKAGE = "rbasamoyai.createbigcannons.munitions.";
    private static final String CBC_CANNON_PROJECTILE = CBC_PACKAGE + "AbstractCannonProjectile";
    private static final Map<Entity, BulletSnapshot> trackedCbcProjectiles = new HashMap<>();
    private static boolean cbcChecked = false;
    private static boolean cbcLoaded = false;

    private record BulletSnapshot(Vec3 pos, Vec3 delta) {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        // SoldierEntity applies suppression in its own hurt() method.
        // Skip it here to avoid double-counting damage suppression.
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() == EntityKineticBullet.TYPE) {
            trackedBullets.put(entity, null);
        } else if (isCbcLoaded() && isCbcProjectile(entity)) {
            trackedCbcProjectiles.put(entity, null);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick();
            CoverReservationManager.tick();
        }
    }

    public static void tick() {
        trackedBullets.entrySet().removeIf(entry -> {
            Entity bullet = entry.getKey();
            if (!bullet.isAlive()) return true;

            LivingEntity shooter = null;
            if (bullet instanceof Projectile proj) {
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

        trackedCbcProjectiles.entrySet().removeIf(entry -> {
            Entity projectile = entry.getKey();
            if (!projectile.isAlive()) return true;

            LivingEntity shooter = null;
            if (projectile instanceof Projectile proj) {
                shooter = proj.getOwner() instanceof LivingEntity owner ? owner : null;
            }

            BulletSnapshot prev = entry.getValue();
            Vec3 currentPos = projectile.position();
            Vec3 currentDelta = projectile.getDeltaMovement();
            float speed = (float)currentDelta.length();

            if (prev != null) {
                // Normal two-tick segment check
                Vec3 prevEnd = prev.pos.add(prev.delta);
                checkNearMissCbcSegment(projectile.level(), prev.pos, prevEnd, speed * 2.0f, shooter);
            } else {
                // First-tick: use currentPos - delta as pseudo-departure point
                Vec3 estimatedPrevPos = currentPos.subtract(currentDelta);
                double segmentLen = currentDelta.length();
                checkNearMissCbcSegment(projectile.level(), estimatedPrevPos, currentPos, speed * 2.0f, shooter);
                if (debugLog()) {
                    StevesArmyMod.LOGGER.info("[CBCSuppressionTrace] first-tick segment for {}: start={}, end={}, segmentLen={}",
                        projectile.getClass().getSimpleName(),
                        estimatedPrevPos, currentPos, String.format("%.2f", segmentLen));
                }
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
        checkNearMissGeneric(level, start, end, bulletSpeed, shooter, false);
    }

    /**
     * CBC near-miss check. Uses the same line-segment geometry as
     * checkNearMissLineSegment, but always applies enough suppression to
     * reach the suppressed threshold (0.5) in one hit. A CBC round passing
     * within 3 blocks is an intense, close-quarters event that should
     * fully suppress the soldier.
     */
    public static void checkNearMissCbcSegment(Level level, Vec3 start, Vec3 end, float bulletSpeed, @Nullable LivingEntity shooter) {
        checkNearMissGeneric(level, start, end, bulletSpeed, shooter, true);
    }

    private static void checkNearMissGeneric(Level level, Vec3 start, Vec3 end, float bulletSpeed, @Nullable LivingEntity shooter, boolean isCbc) {
        Vec3 segment = end.subtract(start);
        double segmentLenSq = segment.lengthSqr();
        if (segmentLenSq < 0.01) {
            if (isCbc && debugLog()) {
                StevesArmyMod.LOGGER.info("[CBCSuppressionTrace] segment too short, skipping");
            }
            return;
        }

        AABB searchBox = new AABB(
            Math.min(start.x, end.x) - NEAR_MISS_THRESHOLD,
            Math.min(start.y, end.y) - NEAR_MISS_THRESHOLD,
            Math.min(start.z, end.z) - NEAR_MISS_THRESHOLD,
            Math.max(start.x, end.x) + NEAR_MISS_THRESHOLD,
            Math.max(start.y, end.y) + NEAR_MISS_THRESHOLD,
            Math.max(start.z, end.z) + NEAR_MISS_THRESHOLD
        );

        int matchCount = 0;
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, searchBox)) {
            if (shooter != null && soldier == shooter) continue;

            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (coverManager == null) continue;

            Vec3 toPoint = soldier.position().subtract(start);
            double t = toPoint.dot(segment) / segmentLenSq;
            t = Mth.clamp(t, 0.0, 1.0);
            Vec3 closestPoint = start.add(segment.scale(t));

            if (soldier.position().distanceTo(closestPoint) < NEAR_MISS_THRESHOLD) {
                if (isCbc) {
                    coverManager.onCbcNearMiss(closestPoint, soldier, shooter);
                    if (debugLog()) {
                        StevesArmyMod.LOGGER.info("[CBCSuppressionTrace] SUPPRESSED soldier {} at dist={}",
                            soldier.getId(),
                            String.format("%.2f", soldier.position().distanceTo(closestPoint)));
                    }
                } else {
                    coverManager.onNearMiss(closestPoint, soldier, bulletSpeed, shooter);
                }
                matchCount++;
            }
        }

        if (isCbc && debugLog()) {
            StevesArmyMod.LOGGER.info("[CBCSuppressionTrace] segment check: start={} end={}, matched {} soldiers",
                start, end, matchCount);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity deadEntity = event.getEntity();
        if (!(deadEntity.level() instanceof ServerLevel serverLevel)) return;
        
        SquadManager manager = SquadManager.get(serverLevel);
        for (SquadData squad : manager.getAllSquads()) {
            SquadThreatIntel intel = squad.getThreatIntel();
            if (intel.hasThreat(deadEntity.getUUID())) {
                intel.markThreatDead(deadEntity.getUUID());
                
                if (DiagnosticLogManager.isSuppressionLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[IncomingFireHandler] Threat {} killed, marked dead in squad intel",
                        deadEntity.getName().getString());
                }
                
                for (UUID memberId : squad.getMemberIds()) {
                    Entity member = serverLevel.getEntity(memberId);
                    if (member instanceof SoldierEntity soldier) {
                        SoldierCombatGoal combatGoal = soldier.getCombatGoal();
                        if (combatGoal != null) {
                            combatGoal.onTargetKilledByTeammate(deadEntity.getUUID());
                        }
                    }
                }
                
                if (squad.getLeaderId() != null) {
                    Entity leader = serverLevel.getEntity(squad.getLeaderId());
                    if (leader instanceof SoldierEntity soldierLeader) {
                        SoldierCombatGoal leaderGoal = soldierLeader.getCombatGoal();
                        if (leaderGoal != null) {
                            leaderGoal.onTargetKilledByTeammate(deadEntity.getUUID());
                        }
                    }
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

    private static boolean isCbcLoaded() {
        if (!cbcChecked) {
            cbcLoaded = ModList.get().isLoaded("createbigcannons");
            cbcChecked = true;
        }
        return cbcLoaded;
    }

    private static boolean isCbcProjectile(Entity entity) {
        String name = entity.getClass().getName();
        return name.startsWith(CBC_PACKAGE)
            && entity instanceof Projectile
            && !name.contains("PrimedPropellant")
            && !name.contains("GasCloud")
            && !name.contains("SmokeEmitter")
            && !name.contains("Burst")
            && !name.contains("Renderer");
    }

    public static void onCbcProjectileSpawn(Entity entity) {
        if (isCbcLoaded() && isCbcProjectile(entity)) {
            trackedCbcProjectiles.put(entity, null);
        }
    }

    private static boolean debugLog() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }
}

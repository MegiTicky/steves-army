package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.compat.TallyhoCompat;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Periodic scan for enemy-crewed vehicle turrets (tallyho hull MG / periscope
 * cameras). A hostile sighting is published into the squad's shared threat
 * intel as a "hard target" — small arms cannot destroy it, so soldiers react
 * by role: squads without an anti-armor gun hide, keep out of the vehicle's
 * sight, and displace off its path; squads with one designate its carrier as
 * the armor hunter while everyone else keeps the vehicle suppressed.
 *
 * Cameras may sit in shipyard space on VS2 ships, so world positions go
 * through {@link VS2Compat#stationCameraWorldPos} and are plausibility-gated
 * (same contract as the vehicle crew role). Velocity is derived from
 * consecutive world sightings; a station with no possessing player and no
 * crew soldier is a parked vehicle and not an active threat.
 */
public final class ArmorThreatScanner {
    public record ArmorContact(UUID threatId, Vec3 aimPoint, Vec3 hullCenter,
                               @Nullable Vec3 velocity, @Nullable Vec3[] hullCorners,
                               long seenTick) {}

    private static final int SCAN_INTERVAL_TICKS = 10;
    /** Sighting trail length for velocity estimation (ticks). */
    private static final long VELOCITY_TRAIL_MAX_TICKS = 100;
    /** Soldier-side caches must be refreshed by a scan within this window. */
    private static final long SOLDIER_CACHE_FRESH_TICKS = 60;
    private static final int PRUNE_INTERVAL_TICKS = 200;
    private static final double LOS_TOLERANCE = 2.0;
    /** Ticks the projected-path corridor looks ahead of a moving vehicle (~5s). */
    private static final int PATH_LOOKAHEAD_TICKS = 100;

    private static final Map<UUID, Long> lastScanTickBySoldier = new HashMap<>();
    private static final Map<UUID, SoldierCache> cacheBySoldier = new HashMap<>();
    private static final Map<UUID, PrevSighting> lastSightingByThreat = new HashMap<>();
    /** Last gate that blocked the armor hunter's engagement, for debug rendering. */
    private static final Map<UUID, String> engageBlockReasonBySoldier = new HashMap<>();
    private static long lastPruneGameTime = Long.MIN_VALUE;

    private record PrevSighting(Vec3 hullCenter, long gameTime) {}

    private record SoldierCache(boolean exposedToArmor, long scanGameTime) {}

    private ArmorThreatScanner() {}

    /**
     * Runs the armor scan for the soldier at a staggered interval. Safe to
     * call every tick; a no-op for vehicle crew soldiers (they man a station)
     * and wherever tallyho or the feature is disabled.
     */
    public static void maybeScan(SoldierEntity soldier) {
        if (!StevesArmyConfig.isArmorAwarenessEnabled() || !TallyhoCompat.isAvailable()) {
            return;
        }
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (soldier.isVehicleCrewActive() || soldier.isCrewSeated()) {
            return;
        }
        long gameTime = serverLevel.getGameTime();
        UUID soldierId = soldier.getUUID();
        Long last = lastScanTickBySoldier.get(soldierId);
        // Per-soldier offset de-synchronizes scans across a squad.
        int stagger = SCAN_INTERVAL_TICKS + Math.floorMod(soldierId.hashCode(), SCAN_INTERVAL_TICKS);
        if (last != null && gameTime - last < stagger) {
            return;
        }
        lastScanTickBySoldier.put(soldierId, gameTime);

        SquadThreatIntel intel = intelFor(soldier, serverLevel);
        if (intel == null) {
            cacheBySoldier.remove(soldierId);
            return;
        }

        double range = StevesArmyConfig.getArmorDetectionDistance();
        AABB searchBox = soldier.getBoundingBox().inflate(range);
        List<Entity> cameras = serverLevel.getEntitiesOfClass(Entity.class, searchBox,
            cam -> TallyhoCompat.isCameraEntity(cam) && isHostileCamera(serverLevel, cam, soldier));

        ArmorContact nearest = null;
        for (Entity camera : cameras) {
            Object ship = VS2Compat.getShipUnder(camera);
            Vec3 aimPoint = VS2Compat.stationCameraWorldPos(ship, camera);
            if (!VS2Compat.isWorldPlausible(aimPoint)) {
                continue;
            }
            Vec3 hullCenter = ship != null ? shipCenterWorld(ship) : aimPoint;
            if (hullCenter == null || !VS2Compat.isWorldPlausible(hullCenter)) {
                continue;
            }
            Vec3[] hullCorners = ship != null ? shipHullCorners(ship) : null;

            Vec3 velocity = estimateVelocity(camera.getUUID(), hullCenter, gameTime);
            intel.reportHardTarget(soldier.getUUID(), camera.getUUID(),
                BlockPos.containing(hullCenter), aimPoint, velocity, hullCorners, 1.0f, serverLevel);

            double distSqr = soldier.distanceToSqr(aimPoint);
            if (nearest == null || distSqr < soldier.distanceToSqr(nearest.aimPoint())) {
                nearest = new ArmorContact(camera.getUUID(), aimPoint, hullCenter, velocity,
                    hullCorners, gameTime);
            }
        }

        boolean exposed = nearest != null
            && TargetAcquisition.hasNearLineOfSightToPosition(soldier, nearest.aimPoint(), LOS_TOLERANCE);
        cacheBySoldier.put(soldierId, new SoldierCache(exposed, gameTime));
        pruneIfNeeded(gameTime);
    }

    /** Nearest fresh hard target known to the soldier's squad, or null. */
    @Nullable
    public static ArmorContact getPrimaryArmorThreat(SoldierEntity soldier) {
        SquadThreatIntel intel = intelFor(soldier, soldier.level());
        if (intel == null) {
            return null;
        }
        List<SquadThreatIntel.ThreatKnowledge> hardTargets =
            intel.getHardTargetThreats(soldier.level().getGameTime());
        for (SquadThreatIntel.ThreatKnowledge knowledge : hardTargets) {
            if (knowledge.lastKnownPosition == null) {
                continue;
            }
            Vec3 hull = Vec3.atCenterOf(knowledge.lastKnownPosition);
            Vec3 aim = knowledge.lastVisibleAimPoint != null ? knowledge.lastVisibleAimPoint : hull;
            return new ArmorContact(knowledge.threatEntityId, aim, hull,
                knowledge.lastKnownVelocity, knowledge.lastKnownHullCorners, knowledge.lastSeenTime);
        }
        return null;
    }

    /** True when the soldier has line of sight to the vehicle's gun position. */
    public static boolean isExposedToArmor(SoldierEntity soldier) {
        SoldierCache cache = cacheBySoldier.get(soldier.getUUID());
        if (cache == null || soldier.level().getGameTime() - cache.scanGameTime > SOLDIER_CACHE_FRESH_TICKS) {
            return false;
        }
        return cache.exposedToArmor;
    }

    /**
     * Peek gate for infantry facing armor: stay under cover while the vehicle
     * has line of sight. Two soldiers are exempt because their doctrine job is
     * to shoot at the vehicle, which requires rising out of cover: the
     * designated hunter (its peek cadence is the normal peek cycle), and any
     * soldier currently assigned to suppress the vehicle's crew. Without
     * anti-armor support nobody holds either job, so a no-AT squad still never
     * peeks at a tank at all.
     */
    public static boolean shouldStayDuckedForArmor(SoldierEntity soldier) {
        if (!StevesArmyConfig.isArmorAwarenessEnabled() || !TallyhoCompat.isAvailable()) {
            return false;
        }
        if (getPrimaryArmorThreat(soldier) == null) {
            return false;
        }
        if (!isExposedToArmor(soldier)) {
            return false;
        }
        if (ArmorRoleManager.isArmorHunter(soldier) || hasHardTargetSuppressionAssignment(soldier)) {
            return false;
        }
        return true;
    }

    /** True when this soldier's current suppression assignment is the hard target. */
    public static boolean hasHardTargetSuppressionAssignment(SoldierEntity soldier) {
        UUID squadId = soldier.getSquadId();
        if (squadId == null || !(soldier.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return false;
        }
        SquadThreatIntel intel = SquadManager.get(serverLevel).getSquadById(squadId)
            .map(SquadData::getThreatIntel).orElse(null);
        if (intel == null) {
            return false;
        }
        return intel.getAssignedThreatForSoldier(soldier.getUUID())
            .map(assignment -> assignment.isHardTarget).orElse(false);
    }

    /**
     * True when the vehicle's projected path (hull position plus velocity)
     * passes within {@code corridorWidth} of {@code pos}. Stationary vehicles
     * threaten only their own footprint.
     */
    public static boolean pathThreatens(SoldierEntity soldier, Vec3 pos, double corridorWidth) {
        ArmorContact armor = getPrimaryArmorThreat(soldier);
        if (armor == null) {
            return false;
        }
        Vec3 velocity = armor.velocity();
        double speed;
        if (velocity == null || (speed = velocity.horizontalDistance()) < 0.01) {
            return armor.hullCenter().distanceToSqr(pos) <= corridorWidth * corridorWidth;
        }
        Vec3 direction = velocity.normalize();
        Vec3 rel = pos.subtract(armor.hullCenter());
        double along = rel.dot(direction);
        double reach = speed * PATH_LOOKAHEAD_TICKS;
        if (along < -corridorWidth || along > reach + corridorWidth) {
            return false;
        }
        double lateral = rel.subtract(direction.scale(along)).horizontalDistance();
        return lateral <= corridorWidth;
    }

    /**
     * Displacement trigger for the no-anti-armor doctrine: the vehicle has
     * line of sight to the soldier, or its projected path is closing on the
     * soldier's position. Hunters opt out — they pick firing positions, not
     * escape routes.
     */
    public static boolean shouldDisplaceFromArmor(SoldierEntity soldier) {
        if (!StevesArmyConfig.isArmorAwarenessEnabled()
            || !StevesArmyConfig.isArmorPathDisplacementEnabled()
            || !TallyhoCompat.isAvailable()) {
            return false;
        }
        if (ArmorRoleManager.isArmorHunter(soldier)) {
            return false;
        }
        if (isExposedToArmor(soldier)) {
            return true;
        }
        return pathThreatens(soldier, soldier.position(), StevesArmyConfig.getArmorPathCorridorWidth());
    }

    /**
     * Fire window for the armor hunter: squad-mates have the vehicle's crew
     * suppressed, the hunter is already in the open, pinned, or committed to
     * an exposed peek — hiding has stopped paying, so take the shot.
     */
    public static boolean mayHunterEngage(SoldierEntity soldier) {
        if (isArmorSuppressed(soldier)) {
            return true;
        }
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        if (!inCover || soldier.getCoverBehaviorManager().isPinned()) {
            return true;
        }
        return soldier.getPeekController().getState()
            == com.stevesarmy.entity.ai.PeekController.State.EXPOSED;
    }

    /** Records the gate currently blocking this hunter's armor engagement (debug). */
    public static void setEngageBlockReason(SoldierEntity soldier, String reason) {
        engageBlockReasonBySoldier.put(soldier.getUUID(), reason);
    }

    /** Last recorded armor-engagement block reason, or "idle". */
    public static String getEngageBlockReason(SoldierEntity soldier) {
        return engageBlockReasonBySoldier.getOrDefault(soldier.getUUID(), "idle");
    }

    private static boolean isArmorSuppressed(SoldierEntity soldier) {
        ArmorContact armor = getPrimaryArmorThreat(soldier);
        if (armor == null) {
            return false;
        }
        SquadThreatIntel intel = intelFor(soldier, soldier.level());
        Optional<SquadThreatIntel.ThreatKnowledge> knowledge =
            intel != null ? intel.getThreat(armor.threatId()) : Optional.empty();
        return knowledge.map(k -> k.isSuppressed).orElse(false);
    }

    /** A station is hostile when an enemy player possesses it or an enemy soldier crews it. */
    private static boolean isHostileCamera(ServerLevel level, Entity camera, SoldierEntity observer) {
        for (Entity passenger : camera.getPassengers()) {
            if (passenger instanceof Player player && !observer.isFriendlyTo(player)) {
                return true;
            }
        }
        UUID claimant = VehicleCrewManager.claimantOf(camera.getUUID());
        if (claimant == null) {
            return false;
        }
        Entity crew = level.getEntity(claimant);
        return crew instanceof SoldierEntity crewSoldier && !observer.isFriendlyTo(crewSoldier);
    }

    @Nullable
    private static Vec3 shipCenterWorld(Object ship) {
        BlockPos min = VS2Compat.getShipyardMin(ship);
        BlockPos max = VS2Compat.getShipyardMax(ship);
        if (min == null || max == null) {
            return null;
        }
        Vec3 local = new Vec3(
            (min.getX() + max.getX()) / 2.0,
            (min.getY() + max.getY()) / 2.0,
            (min.getZ() + max.getZ()) / 2.0);
        return VS2Compat.shipToWorldPosition(ship, local);
    }

    /**
     * The ship-space bounding-box corners transformed to world space, so line
     * of sight can be tested against the whole hull silhouette instead of only
     * the gun position. Null when the ship gives no bounds or a corner fails
     * the world-plausibility gate (degrades callers to gun-point checks).
     */
    @Nullable
    private static Vec3[] shipHullCorners(Object ship) {
        BlockPos min = VS2Compat.getShipyardMin(ship);
        BlockPos max = VS2Compat.getShipyardMax(ship);
        if (min == null || max == null) {
            return null;
        }
        Vec3[] corners = new Vec3[8];
        int index = 0;
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    Vec3 local = new Vec3(
                        cx == 0 ? min.getX() : max.getX(),
                        cy == 0 ? min.getY() : max.getY(),
                        cz == 0 ? min.getZ() : max.getZ());
                    Vec3 world = VS2Compat.shipToWorldPosition(ship, local);
                    if (world == null || !VS2Compat.isWorldPlausible(world)) {
                        return null;
                    }
                    corners[index++] = world;
                }
            }
        }
        return corners;
    }

    /**
     * Resolves the point the soldier can actually shoot at on a hard target:
     * the gun position when visible, otherwise the hull center or any hull
     * corner with a clear (near-tolerated) ray. Doctrine: line of sight to any
     * block of the vehicle counts, not just the gun optic. Null when nothing
     * on the vehicle is visible.
     */
    @Nullable
    public static Vec3 findFiringSolution(SoldierEntity soldier, ArmorContact armor) {
        if (TargetAcquisition.hasNearLineOfSightToPosition(soldier, armor.aimPoint(), LOS_TOLERANCE)) {
            return armor.aimPoint();
        }
        if (TargetAcquisition.hasNearLineOfSightToPosition(soldier, armor.hullCenter(), LOS_TOLERANCE)) {
            return armor.hullCenter();
        }
        if (armor.hullCorners() != null) {
            for (Vec3 corner : armor.hullCorners()) {
                if (TargetAcquisition.hasNearLineOfSightToPosition(soldier, corner, LOS_TOLERANCE)) {
                    return corner;
                }
            }
        }
        return null;
    }

    /**
     * {@link #findFiringSolution} for a squad-intel knowledge entry — used by
     * the suppression path, which reads the shared threat store rather than a
     * fresh scan contact.
     */
    @Nullable
    public static Vec3 findFiringSolution(SoldierEntity soldier, SquadThreatIntel.ThreatKnowledge knowledge) {
        if (knowledge.lastKnownPosition == null) {
            return null;
        }
        Vec3 hull = Vec3.atCenterOf(knowledge.lastKnownPosition);
        Vec3 aim = knowledge.lastVisibleAimPoint != null ? knowledge.lastVisibleAimPoint : hull;
        return findFiringSolution(soldier, new ArmorContact(knowledge.threatEntityId, aim, hull,
            knowledge.lastKnownVelocity, knowledge.lastKnownHullCorners, knowledge.lastSeenTime));
    }

    /** Blocks per tick between this and the previous world sighting of the vehicle. */
    @Nullable
    private static Vec3 estimateVelocity(UUID threatId, Vec3 hullCenter, long gameTime) {
        PrevSighting prev = lastSightingByThreat.get(threatId);
        lastSightingByThreat.put(threatId, new PrevSighting(hullCenter, gameTime));
        if (prev == null) {
            return null;
        }
        long dt = gameTime - prev.gameTime();
        if (dt <= 0 || dt > VELOCITY_TRAIL_MAX_TICKS) {
            return null;
        }
        return hullCenter.subtract(prev.hullCenter()).scale(1.0 / dt);
    }

    private static void pruneIfNeeded(long gameTime) {
        if (lastPruneGameTime != Long.MIN_VALUE && gameTime - lastPruneGameTime < PRUNE_INTERVAL_TICKS) {
            return;
        }
        lastPruneGameTime = gameTime;
        lastSightingByThreat.entrySet().removeIf(
            entry -> gameTime - entry.getValue().gameTime() > VELOCITY_TRAIL_MAX_TICKS * 4);
        lastScanTickBySoldier.clear();
        cacheBySoldier.entrySet().removeIf(
            entry -> gameTime - entry.getValue().scanGameTime > SOLDIER_CACHE_FRESH_TICKS * 100);
    }

    @Nullable
    private static SquadThreatIntel intelFor(SoldierEntity soldier, net.minecraft.world.level.Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return null;
        }
        return SquadManager.get(serverLevel).getSquadById(squadId)
            .map(SquadData::getThreatIntel)
            .orElse(null);
    }
}

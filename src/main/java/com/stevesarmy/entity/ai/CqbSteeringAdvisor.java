package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Computes caution-steering state for a soldier following a path while a
 * hostile is known to be near that path. While caution is active the soldier
 * slows to a cautious pace and hugs the side of the path that keeps cover
 * between it and the enemy (continuous corner-pie, never stopping).
 *
 * The advisor never owns navigation; it only suggests a steer target, a speed
 * cap, and whether caution should be active at all. The move controller feeds
 * the suggestions into its normal steering.
 */
public class CqbSteeringAdvisor {

    private static final double CAUTION_EXIT_RADIUS = 13.0;
    private static final int CAUTION_DEBOUNCE_TICKS = 40;
    private static final int PATH_SAMPLE_NODE_COUNT = 8;
    private static final double LATERAL_OFFSET = 0.35;
    private static final double CAUTION_SPEED_CAP = 0.24;
    private static final double AUTO_CAUTION_SPEED_CAP = 0.37;
    private static final double BODY_SAMPLE_HEIGHT = 0.9;
    private static final double ENEMY_EYE_HEIGHT = 1.2;
    private static final String MANUAL_CQB_REASON = "manual CQB";
    private static final String NEAR_PATH_THREAT_REASON = "enemy near path";
    private static final String NO_CAUTION_REASON = "none";

    private boolean cautionActive = false;
    private int lastCautionChangeTick = 0;
    private String cautionReason = NO_CAUTION_REASON;
    private double currentSpeedFactor = 0.0;

    /** True while an enemy is within the caution radius of the remaining path. */
    public boolean isCautionActive(SoldierEntity soldier) {
        double nearest = nearestEnemyDistance(soldier);
        boolean manualCqb = soldier.isCQB();
        boolean autoCaution = cautionActive
            ? nearest <= CAUTION_EXIT_RADIUS
            : nearest <= SoldierEntity.CQB_CAUTION_RADIUS;
        boolean wouldBeActive = manualCqb || autoCaution;
        String nextReason = manualCqb ? MANUAL_CQB_REASON
            : autoCaution ? NEAR_PATH_THREAT_REASON : NO_CAUTION_REASON;
        if (wouldBeActive == cautionActive) {
            if (!nextReason.equals(cautionReason)) {
                cautionReason = nextReason;
                logCautionTransition(soldier, nearest);
            }
            return cautionActive;
        }

        // Manual CQB changes must take effect immediately. The automatic mode
        // retains debounce, except when releasing a manual CQB override.
        boolean releasingManualCqb = MANUAL_CQB_REASON.equals(cautionReason) && !manualCqb;
        if (!manualCqb && !releasingManualCqb
            && soldier.tickCount - lastCautionChangeTick < CAUTION_DEBOUNCE_TICKS) {
            return cautionActive;
        }
        cautionActive = wouldBeActive;
        lastCautionChangeTick = soldier.tickCount;
        cautionReason = nextReason;
        logCautionTransition(soldier, nearest);
        if (!cautionActive) {
            currentSpeedFactor = 0.0;
        }
        return cautionActive;
    }

    public String getCautionReason() {
        return cautionReason;
    }

    private void logCautionTransition(SoldierEntity soldier, double nearest) {
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            String nearestLabel = Double.isFinite(nearest) ? String.format("%.2f", nearest) : "none";
            StevesArmyMod.LOGGER.info("[CqbSteer] Soldier {} caution {} source={} nearestEnemy={}",
                soldier.getId(), cautionActive ? "ON" : "OFF", cautionReason, nearestLabel);
        }
    }

    /**
     * Returns the adjusted waypoint for cautious steering, or null when the
     * soldier should follow the path normally (just at the capped speed).
     * When the current node is already protected from the enemy, the raw node
     * is returned; when it is exposed, the node is offset laterally toward the
     * side that keeps cover between the soldier and the enemy.
     */
    public Vec3 getSteerTarget(SoldierEntity soldier) {
        Path path = soldier.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return null;
        }

        Vec3 node = path.getNextEntityPos(soldier);
        Vec3 soldierPos = soldier.position();
        Vec3 travelDir = new Vec3(node.x - soldierPos.x, 0, node.z - soldierPos.z);
        if (travelDir.lengthSqr() < 0.001) {
            return node;
        }
        travelDir = travelDir.normalize();

        Vec3 enemy = choosePrimaryEnemy(soldier, node);
        if (enemy == null) {
            return node;
        }

        Vec3 enemyEye = enemy.add(0.0, ENEMY_EYE_HEIGHT, 0.0);
        boolean nodeExposed = VisibilityRay.trace(soldier.level(), enemyEye,
            node.add(0.0, BODY_SAMPLE_HEIGHT, 0.0), soldier).clear();
        if (!nodeExposed) {
            return node;
        }

        Vec3 lateral = new Vec3(-travelDir.z, 0.0, travelDir.x);
        Vec3 sideA = node.add(lateral.scale(LATERAL_OFFSET));
        Vec3 sideB = node.subtract(lateral.scale(LATERAL_OFFSET));
        boolean sideAProtected = !VisibilityRay.trace(soldier.level(), enemyEye,
            sideA.add(0.0, BODY_SAMPLE_HEIGHT, 0.0), soldier).clear();
        boolean sideBProtected = !VisibilityRay.trace(soldier.level(), enemyEye,
            sideB.add(0.0, BODY_SAMPLE_HEIGHT, 0.0), soldier).clear();

        if (sideAProtected && !sideBProtected) {
            return sideA;
        }
        if (sideBProtected && !sideAProtected) {
            return sideB;
        }
        if (sideAProtected) {
            return sideA.distanceToSqr(soldierPos) <= sideB.distanceToSqr(soldierPos) ? sideA : sideB;
        }
        return node;
    }

    /**
     * Doorway queueing (phase-4 CQB doctrine): true when the next path node is
     * a wall-narrow passage (solid on both lateral sides - a door, window
     * breach, or rubble gap) and a squadmate is still inside or just past it.
     * The move control holds the soldier back so the stack crosses single-file
     * instead of clumping in the fatal funnel.
     */
    public boolean isDoorwayBlocked(SoldierEntity soldier) {
        if (!com.stevesarmy.StevesArmyConfig.isCqbDoorwayQueueEnabled()) {
            return false;
        }
        Path path = soldier.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        // Look ahead along the path: A* approaches a wall door by hugging the
        // wall and TURNING into the opening, so the doorway node sits well out
        // on the path when the follower still needs to slow. Eight nodes
        // (~5 blocks) keeps the funnel inside the window on a staggered
        // approach; the mate-proximity test below does the rest.
        int start = Math.max(0, path.getNextNodeIndex());
        int end = Math.min(path.getNodeCount(), start + 8);
        Vec3 soldierPos = soldier.position();
        double spacing = com.stevesarmy.StevesArmyConfig.getCqbDoorwaySpacing();
        for (int j = start; j < end; j++) {
            Vec3 node = Vec3.atCenterOf(path.getNode(j).asBlockPos());
            Vec3 travelDir = new Vec3(node.x - soldierPos.x, 0, node.z - soldierPos.z);
            if (travelDir.lengthSqr() < 0.001) {
                continue;
            }
            travelDir = travelDir.normalize();
            if (!isDoorwayNode(soldier, node)) {
                continue;
            }
            for (SoldierEntity mate : soldier.level().getEntitiesOfClass(SoldierEntity.class,
                soldier.getBoundingBox().inflate(8.0))) {
                if (mate == soldier || !mate.isAlive() || !isSquadmate(soldier, mate)) {
                    continue;
                }
                Vec3 rel = mate.position().subtract(soldierPos);
                if (rel.dot(travelDir) <= 0.3) {
                    continue; // not ahead of me on the approach
                }
                if (mate.position().distanceTo(node) <= spacing) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Direction-invariant 1-wide-gap signature: exactly ONE opposing neighbour
     * pair around the node is solid (the wall the gap sits in) and the other
     * pair is the passage. This must NOT be keyed to the follower's
     * instantaneous travel direction - A* approaches a wall door by hugging
     * the wall and turning into the opening, so at the doorway node the
     * travel direction still points along the wall and a travel-relative
     * frame test reads the door as open ground. A 1-wide corridor or trench
     * reads the same signature, and queuing in one is correct doctrine anyway.
     */
    private boolean isDoorwayNode(SoldierEntity soldier, Vec3 node) {
        boolean ew = isSolidBeside(soldier, node.add(0.7, 0.0, 0.0))
            && isSolidBeside(soldier, node.add(-0.7, 0.0, 0.0));
        boolean ns = isSolidBeside(soldier, node.add(0.0, 0.0, 0.7))
            && isSolidBeside(soldier, node.add(0.0, 0.0, -0.7));
        return ew != ns;
    }

    private boolean isSolidBeside(SoldierEntity soldier, Vec3 at) {
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(
            at.x, soldier.getY() + 0.6, at.z);
        return !soldier.level().getBlockState(bp)
            .getCollisionShape(soldier.level(), bp).isEmpty();
    }

    private boolean isSquadmate(SoldierEntity soldier, SoldierEntity other) {
        return soldier.isFriendlyTo(other);
    }

    /**
     * Corner pie-slicing (phase-4 CQB doctrine), consumed by the combat goal's
     * scanning chain: while an enemy is near the remaining path, find the first
     * bend in the next few nodes and return an eye-height point past the corner
     * on the threat side - the arc the corner opens into. The soldier pre-aims
     * that arc instead of staring at the wall he is hugging. Static and
     * stateless (no debounce) so the aim layer can call it every tick.
     */
    public static Vec3 getCornerWatchPos(SoldierEntity soldier) {
        if (!com.stevesarmy.StevesArmyConfig.isCqbCornerWatchEnabled()) {
            return null;
        }
        Path path = soldier.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return null;
        }
        int start = Math.max(0, path.getNextNodeIndex());
        int end = Math.min(path.getNodeCount(), start + PATH_SAMPLE_NODE_COUNT);
        if (end - start < 2) {
            return null;
        }
        Vec3 prev = Vec3.atCenterOf(path.getNode(start).asBlockPos());
        Vec3 prevDir = null;
        Vec3 apex = null;
        for (int i = start + 1; i < end; i++) {
            Vec3 cur = Vec3.atCenterOf(path.getNode(i).asBlockPos());
            Vec3 dir = new Vec3(cur.x - prev.x, 0, cur.z - prev.z);
            if (dir.lengthSqr() >= 0.001) {
                dir = dir.normalize();
                if (prevDir != null && prevDir.dot(dir) < 0.707) {
                    apex = prev;
                    break;
                }
                prevDir = dir;
            }
            prev = cur;
        }
        if (apex == null) {
            return null;
        }
        Vec3 enemy = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Vec3 candidate : collectEnemyPositionsStatic(soldier)) {
            double distSq = candidate.distanceToSqr(apex);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                enemy = candidate;
            }
        }
        if (enemy == null) {
            return null;
        }
        // Only worth pre-aiming when the threat actually sits near the bend.
        if (bestDistSq > 12.0 * 12.0) {
            return null;
        }
        Vec3 watchDir = new Vec3(enemy.x - apex.x, 0, enemy.z - apex.z);
        if (watchDir.lengthSqr() < 0.001) {
            return null;
        }
        return apex.add(watchDir.normalize().scale(4.0)).add(0.0, 1.2, 0.0);
    }

    private static List<Vec3> collectEnemyPositionsStatic(SoldierEntity soldier) {
        List<Vec3> positions = new ArrayList<>();
        ThreatAwareness awareness = soldier.getThreatAwareness();
        for (ThreatAwareness.ThreatInfo info : awareness.getThreatInfos()) {
            positions.add(Vec3.atCenterOf(info.position));
        }
        UUID squadId = soldier.getSquadId();
        if (squadId != null && soldier.level() instanceof ServerLevel serverLevel) {
            SquadData squad = SquadManager.get(serverLevel).getSquadById(squadId).orElse(null);
            if (squad != null) {
                for (SquadThreatIntel.ThreatKnowledge knowledge : squad.getThreatIntel().getAllThreats()) {
                    if (knowledge.isAlive && knowledge.lastKnownPosition != null) {
                        positions.add(Vec3.atCenterOf(knowledge.lastKnownPosition));
                    }
                }
            }
        }
        return positions;
    }

    /**
     * Manual CQB retains its original cautious pace. Automatic path caution
     * keeps its lateral steering but travels at the normal sprint pace when
     * the CQB toggle is off.
     */
    public double getCautionSpeed(SoldierEntity soldier, double requestedSpeed) {
        if (!soldier.isCQB()) {
            currentSpeedFactor = 0.0;
            return Math.min(requestedSpeed, AUTO_CAUTION_SPEED_CAP);
        }

        // Ramp into the cap so entering CQB does not look like an abrupt sprint
        // or an abrupt velocity override.
        currentSpeedFactor = Math.min(CAUTION_SPEED_CAP, currentSpeedFactor + 0.02);
        return Math.min(requestedSpeed, currentSpeedFactor);
    }

    private double nearestEnemyDistance(SoldierEntity soldier) {
        List<Vec3> pathSamples = collectPathSamples(soldier);
        double nearest = Double.MAX_VALUE;
        for (Vec3 enemy : collectEnemyPositions(soldier)) {
            for (Vec3 sample : pathSamples) {
                nearest = Math.min(nearest, enemy.distanceToSqr(sample));
            }
        }
        return nearest == Double.MAX_VALUE ? Double.POSITIVE_INFINITY : Math.sqrt(nearest);
    }

    private Vec3 choosePrimaryEnemy(SoldierEntity soldier, Vec3 near) {
        Vec3 best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Vec3 enemy : collectEnemyPositions(soldier)) {
            double distSq = enemy.distanceToSqr(near);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = enemy;
            }
        }
        return best;
    }

    private List<Vec3> collectPathSamples(SoldierEntity soldier) {
        List<Vec3> samples = new ArrayList<>();
        samples.add(soldier.position());
        Path path = soldier.getNavigation().getPath();
        if (path == null) {
            return samples;
        }
        int start = Math.max(0, path.getNextNodeIndex());
        int end = Math.min(path.getNodeCount(), start + PATH_SAMPLE_NODE_COUNT);
        for (int i = start; i < end; i++) {
            samples.add(Vec3.atCenterOf(path.getNode(i).asBlockPos()));
        }
        return samples;
    }

    private List<Vec3> collectEnemyPositions(SoldierEntity soldier) {
        List<Vec3> positions = new ArrayList<>();
        ThreatAwareness awareness = soldier.getThreatAwareness();
        for (ThreatAwareness.ThreatInfo info : awareness.getThreatInfos()) {
            positions.add(Vec3.atCenterOf(info.position));
        }

        UUID squadId = soldier.getSquadId();
        if (squadId != null && soldier.level() instanceof ServerLevel serverLevel) {
            SquadData squad = SquadManager.get(serverLevel).getSquadById(squadId).orElse(null);
            if (squad != null) {
                for (SquadThreatIntel.ThreatKnowledge knowledge : squad.getThreatIntel().getAllThreats()) {
                    if (knowledge.isAlive && knowledge.lastKnownPosition != null) {
                        positions.add(Vec3.atCenterOf(knowledge.lastKnownPosition));
                    }
                }
            }
        }
        return positions;
    }
}

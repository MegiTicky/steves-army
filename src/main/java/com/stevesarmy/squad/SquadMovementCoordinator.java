package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Squad street-crossing doctrine (gpt6 positioning rework, phase 3): crossing
 * dangerous open ground is a SQUAD movement problem, not N private ones.
 *
 * Within a fire team that must advance across a hot lane toward an objective,
 * one soldier bounds at a time and the others hold overwatch; the bounder is
 * released when it reaches the objective, times out, or dies, and the next
 * pick rotates (staggered crossing instead of a swarm). Machine gunners are
 * overwatch-anchored by preference: their lane is the covering fire the
 * bounder advances under.
 *
 * Covering fire is REAL threat influence, not a flag: a teammate counts as
 * covering only when it actually fired recently (its own shots, tracked from
 * the combat goal). A bounder without covering fire waits out a soft window
 * before advancing anyway — bounded by the bound timeout, so the team never
 * stalls permanently.
 *
 * Solo soldiers (or lone survivors of a team) bypass the coordinator
 * entirely: with nobody to hold a lane, bounding is meaningless and the
 * existing exposed-combat doctrine governs.
 *
 * State is static per team (same pattern as FireTeamSuppressionTracker) and
 * cleared by {@link #resetAll()} between sim scenarios.
 */
public final class SquadMovementCoordinator {

    /** One team bound: who crosses, who anchored, since when. */
    private static final class Bound {
        UUID bounderId;
        long startedTick;
        long lastScanTick;
    }

    /** team key (owner:fireTeam) -> active bound. */
    private static final Map<UUID, Bound> BOUNDS = new HashMap<>();
    /** soldier UUID -> tick of its last successful shot (covering-fire evidence). */
    private static final Map<UUID, Long> SHOT_TICKS = new HashMap<>();
    /** last bounder per team, so the next bound rotates to another soldier. */
    private static final Map<UUID, UUID> LAST_BOUNDERS = new HashMap<>();

    private SquadMovementCoordinator() {}

    /** Called by the combat goal on every successful shot. */
    public static void noteShot(SoldierEntity shooter) {
        // Server-tick clock everywhere: soldier.tickCount is each entity's own
        // age, and mixing the two breaks every comparison between soldiers
        // spawned at different times (shot freshness, cover waits).
        net.minecraft.server.MinecraftServer server = shooter.getServer();
        if (server == null) return;
        SHOT_TICKS.put(shooter.getUUID(), (long) server.getTickCount());
    }

    /**
     * Tick of this soldier's most recent successful shot, or null when it has
     * never fired. Contact-plan base-of-fire support reads this directly: a
     * soldier who fired recently is producing covering fire regardless of
     * whether a formal bound is active.
     */
    public static Long lastSuccessfulShotTick(SoldierEntity soldier) {
        return SHOT_TICKS.get(soldier.getUUID());
    }

    /** True while this soldier should hold overwatch for a teammate's bound. */
    public static boolean isOverwatching(SoldierEntity soldier) {
        if (!StevesArmyConfig.isSquadBoundingEnabled()) return false;
        Bound bound = boundFor(soldier);
        return bound != null && soldier.getUUID().equals(bound.bounderId) == false;
    }

    /** True while this soldier is the team's active bounder (should keep moving). */
    public static boolean isBounder(SoldierEntity soldier) {
        if (!StevesArmyConfig.isSquadBoundingEnabled()) return false;
        Bound bound = boundFor(soldier);
        return bound != null && soldier.getUUID().equals(bound.bounderId);
    }

    /**
     * Real covering fire: while this soldier is the active bounder, a teammate
     * on overwatch that actually fired within the cover window. Double.MAX
     * distance semantics don't apply here - it is a pure boolean gate.
     */
    public static boolean coveringFireActive(SoldierEntity bounder) {
        Bound bound = boundFor(bounder);
        if (bound == null || !bounder.getUUID().equals(bound.bounderId)) return false;
        net.minecraft.server.MinecraftServer server = bounder.getServer();
        if (server == null) return false;
        long now = server.getTickCount();
        for (SoldierEntity mate : teammates(bounder, bound)) {
            Long lastShot = SHOT_TICKS.get(mate.getUUID());
            if (lastShot != null && now - lastShot
                    <= StevesArmyConfig.getSquadBoundCoverWindowTicks()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this bounder should wait for covering fire before advancing:
     * the soft requirement - bounded by the cover-wait window, after which the
     * advance proceeds regardless.
     */
    public static boolean awaitingCoveringFire(SoldierEntity soldier) {
        if (!StevesArmyConfig.isSquadBoundingEnabled()) return false;
        Bound bound = boundFor(soldier);
        if (bound == null || !soldier.getUUID().equals(bound.bounderId)) return false;
        if (coveringFireActive(soldier)) return false;
        net.minecraft.server.MinecraftServer server = soldier.getServer();
        if (server == null) return false;
        return server.getTickCount() - bound.startedTick
            < StevesArmyConfig.getSquadBoundCoverWaitTicks();
    }

    /** Per-server-tick scan (cheap; heavy work throttled to every 20 ticks). */
    public static void tick(MinecraftServer server) {
        if (!StevesArmyConfig.isSquadBoundingEnabled() || server.getTickCount() % 20 != 0) {
            return;
        }
        // Expire stale bounds first (dead bounder, timeout, reached objective).
        BOUNDS.entrySet().removeIf(entry -> {
            Bound bound = entry.getValue();
            SoldierEntity bounder = findSoldier(server, bound.bounderId);
            if (bounder == null || !bounder.isAlive()) return true;
            if (server.getTickCount() - bound.startedTick
                    > StevesArmyConfig.getSquadBoundMaxTicks()) return true;
            if (!bounderIsAdvancing(bounder)) return true;
            return false;
        });

        // Scan soldiers for new bound candidates.
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (!(entity instanceof SoldierEntity soldier)) continue;
                if (!soldier.isAlive() || soldier.getTarget() == null) continue;
                UUID teamKey = teamKey(soldier);
                if (teamKey == null || BOUNDS.containsKey(teamKey)) continue;
                // Advance context: an objective meaningfully far from the soldier.
                if (!bounderIsAdvancing(soldier)) continue;
                java.util.List<SoldierEntity> mates = teammates(soldier, null);
                if (mates.isEmpty()) continue; // solo bypass

                Bound bound = new Bound();
                bound.startedTick = server.getTickCount();
                bound.lastScanTick = server.getTickCount();
                bound.bounderId = pickBounder(server, soldier, mates);
                BOUNDS.put(teamKey, bound);
            }
        }
    }

    /** Sim-harness isolation hook. */
    public static void resetAll() {
        BOUNDS.clear();
        SHOT_TICKS.clear();
        LAST_BOUNDERS.clear();
    }

    // ------------------------------------------------------------------

    private static Bound boundFor(SoldierEntity soldier) {
        UUID teamKey = teamKey(soldier);
        return teamKey == null ? null : BOUNDS.get(teamKey);
    }

    /** owner:fireTeam identity, or null when the soldier has neither. */
    private static UUID teamKey(SoldierEntity soldier) {
        LivingEntity owner = soldier.getOwner();
        if (owner == null) return null;
        return UUID.nameUUIDFromBytes(
            (owner.getUUID() + ":" + soldier.getFireTeam()).getBytes());
    }

    private static boolean bounderIsAdvancing(SoldierEntity soldier) {
        // ATTACK: bounding stays alive under a clearing order even inside
        // close combat — the objective is the attack target, and the contact
        // plan (not the close-combat bypass) now owns whether advancing is
        // safe. Without this, the coordinator silently stood down in exactly
        // the fights it exists for.
        if (soldier.hasValidAttackTarget()) {
            return soldier.blockPosition().distSqr(soldier.getAttackTargetPos()) > 100;
        }
        if (soldier.isInCloseCombatContext()) return false;
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            net.minecraft.core.BlockPos hold = soldier.getHoldPosition();
            return hold != null && !hold.equals(net.minecraft.core.BlockPos.ZERO)
                && soldier.blockPosition().distSqr(hold) > 100;
        }
        LivingEntity owner = soldier.getOwner();
        return owner != null && owner.isAlive()
            && soldier.distanceToSqr(owner) > 100;
    }

    /** Alive same-team squadmates within coordination range (never the soldier itself). */
    private static java.util.List<SoldierEntity> teammates(SoldierEntity soldier, Bound bound) {
        java.util.List<SoldierEntity> out = new java.util.ArrayList<>();
        LivingEntity owner = soldier.getOwner();
        if (owner == null) return out;
        double range = StevesArmyConfig.getSquadBoundThreatRange();
        for (SoldierEntity other : soldier.level().getEntitiesOfClass(SoldierEntity.class,
                soldier.getBoundingBox().inflate(range))) {
            if (other == soldier || !other.isAlive()) continue;
            if (other.getOwner() != owner) continue;
            if (other.getFireTeam() != soldier.getFireTeam()) continue;
            if (!soldier.isFriendlyTo(other)) continue;
            out.add(other);
        }
        return out;
    }

    /**
     * Machine gunners anchor the overwatch by preference - the lane they hold
     * IS the covering fire. Otherwise the soldier closest to the objective
     * bounds first, and the previous bounder is deferred for rotation.
     */
    private static UUID pickBounder(MinecraftServer server, SoldierEntity soldier,
                                    java.util.List<SoldierEntity> mates) {
        UUID teamKey = teamKey(soldier);
        UUID last = teamKey != null ? LAST_BOUNDERS.get(teamKey) : null;
        SoldierEntity best = null;
        double bestScore = Double.MAX_VALUE;
        java.util.List<SoldierEntity> pool = new java.util.ArrayList<>(mates);
        pool.add(soldier);
        for (SoldierEntity candidate : pool) {
            if (candidate.getUUID().equals(last)) continue; // rotation
            double score = objectiveDistanceSqr(candidate);
            if (candidate.getRole() == SoldierRole.MACHINE_GUNNER) score += 10000;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) best = soldier; // single-candidate team
        if (teamKey != null) LAST_BOUNDERS.put(teamKey, best.getUUID());
        return best.getUUID();
    }

    private static double objectiveDistanceSqr(SoldierEntity soldier) {
        // Same ATTACK objective rule as bounderIsAdvancing.
        if (soldier.hasValidAttackTarget()) {
            return soldier.blockPosition().distSqr(soldier.getAttackTargetPos());
        }
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            net.minecraft.core.BlockPos hold = soldier.getHoldPosition();
            if (hold != null && !hold.equals(net.minecraft.core.BlockPos.ZERO)) {
                return soldier.blockPosition().distSqr(hold);
            }
        }
        LivingEntity owner = soldier.getOwner();
        return owner != null ? soldier.distanceToSqr(owner) : Double.MAX_VALUE;
    }

    private static SoldierEntity findSoldier(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof SoldierEntity soldier && soldier.isAlive()) {
                return soldier;
            }
        }
        return null;
    }
}

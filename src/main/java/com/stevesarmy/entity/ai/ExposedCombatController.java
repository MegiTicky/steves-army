package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Deliberate behavior for soldiers WITHOUT usable cover. "No cover" is a
 * supported combat situation with a small, dependable response set - not
 * merely a failed search:
 *
 * - PLANT_AND_ENGAGE: clear shot, weapon ready, no better protection near -
 *   stand and fight for a bounded stability window.
 * - BOUNDED_ADJUST: the firing lane is blocked (friendly in the cone) - one
 *   sidestep perpendicular to the aim, then re-evaluate; attempts are capped
 *   so a failed sidestep never loops.
 * - WITHDRAW: the threat is overrunning and nothing shields - checked distance
 *   away from the threat while engagement remains possible.
 * - DELIBERATE_HOLD: no shot available and movement more dangerous than
 *   staying - hold with a reassessment clock.
 *
 * The controller DECIDES; existing machinery EXECUTES. Firing stays in
 * SoldierCombatGoal, nudges/withdrawals run through CoverPositionController,
 * posture through the prone/crawl systems. State is static per soldier
 * (same pattern as FireTeamSuppressionTracker) so goal restarts cannot
 * reset an in-flight bounded adjustment.
 */
public final class ExposedCombatController {

    public enum ExposedAction { NONE, PLANT_AND_ENGAGE, BOUNDED_ADJUST, WITHDRAW, DELIBERATE_HOLD }

    /** A chosen combat-position action; {@code moveTarget} is null unless the action moves. */
    public static final class Decision {
        public final ExposedAction action;
        public final Vec3 moveTarget;
        public final String reason;

        Decision(ExposedAction action, Vec3 moveTarget, String reason) {
            this.action = action;
            this.moveTarget = moveTarget;
            this.reason = reason;
        }

        static Decision passive(ExposedAction action, String reason) {
            return new Decision(action, null, reason);
        }
    }

    private static final class State {
        ExposedAction action = ExposedAction.NONE;
        long actionStartedTick;
        long lastDecisionTick = -1;
        int adjustAttempts;
        long shotBlockedAtTick = -1;
        Vec3 shotBlockedAim;
        long plantHoldUntilTick;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** A fresh withdrawal gets at least this many ticks of retreat before re-evaluation. */
    private static final int WITHDRAW_MIN_REACTION_TICKS = 10;

    private static State state(SoldierEntity soldier) {
        return STATES.computeIfAbsent(soldier.getUUID(), k -> new State());
    }

    /** Called by the combat goal whenever FriendlyFireChecker rejects a shot. */
    public static void noteShotBlocked(SoldierEntity soldier, Vec3 aimPoint) {
        State s = state(soldier);
        // A blocked event long after the last one is a new engagement, not the
        // old obstruction: restore the sidestep allowance. Production never
        // calls reset(), so without this the one-attempt cap stays spent for
        // the soldier's whole session.
        if (s.shotBlockedAtTick > 0 && soldier.tickCount - s.shotBlockedAtTick > 200) {
            s.adjustAttempts = 0;
        }
        s.shotBlockedAtTick = soldier.tickCount;
        s.shotBlockedAim = aimPoint;
    }

    /** True while a bounded move chosen here should not be interrupted by a re-seek. */
    public static boolean hasActiveMove(SoldierEntity soldier) {
        State s = STATES.get(soldier.getUUID());
        if (s == null) return false;
        if (s.action != ExposedAction.BOUNDED_ADJUST && s.action != ExposedAction.WITHDRAW) return false;
        int timeout = s.action == ExposedAction.WITHDRAW
            ? StevesArmyConfig.getExposedWithdrawTimeoutTicks()
            : StevesArmyConfig.getExposedAdjustTimeoutTicks();
        return soldier.tickCount - s.actionStartedTick < timeout;
    }

    /**
     * Arms the plant stability window after a failed close-seek round: the
     * doctrine action chosen at the search failure point owns the soldier
     * (blocks immediate seek re-entry) until the window expires.
     */
    public static void armPlantHold(SoldierEntity soldier) {
        state(soldier).plantHoldUntilTick = soldier.tickCount + StevesArmyConfig.getExposedPlantMinTicks();
    }

    /** True while a failed-seek plant hold still owns the soldier. */
    public static boolean plantHoldActive(SoldierEntity soldier) {
        State s = STATES.get(soldier.getUUID());
        return s != null && s.plantHoldUntilTick > soldier.tickCount;
    }

    public static void reset(SoldierEntity soldier) {
        STATES.remove(soldier.getUUID());
    }

    /** Sim-harness isolation hook: drop every soldier's action state. */
    public static void resetAll() {
        STATES.clear();
    }

    /**
     * Chooses the soldier's immediate combat-position action. {@code nearestThreatDist}
     * is Double.MAX_VALUE when nothing is known; {@code hasShot} = clear shot,
     * weapon ready. Deterministic given the inputs.
     */
    public static Decision decide(SoldierEntity soldier, double nearestThreatDist, boolean hasLoS,
                                  boolean gunReady, boolean suppressed) {
        State s = state(soldier);
        long now = soldier.tickCount;
        s.lastDecisionTick = now;

        double overrunRange = StevesArmyConfig.getCqbOverrunRange();
        boolean overrun = nearestThreatDist <= overrunRange;
        boolean ffBlockedRecently = s.shotBlockedAtTick > 0
            && now - s.shotBlockedAtTick <= StevesArmyConfig.getExposedAdjustWindowTicks();

        // 1. Overrun: survival response - checked distance away from the threat.
        if (overrun && s.action != ExposedAction.WITHDRAW) {
            s.action = ExposedAction.WITHDRAW;
            s.actionStartedTick = now;
            s.plantHoldUntilTick = 0;
            Vec3 move = withdrawTarget(soldier);
            return new Decision(ExposedAction.WITHDRAW, move, "overrun-withdraw");
        }
        if (s.action == ExposedAction.WITHDRAW) {
            // The withdrawal ends as soon as the soldier is no longer overrun
            // (plus a minimum reaction window so a single spike still creates
            // distance): kiting on past the danger hands the whole fight to
            // movement and the soldier never stands to return fire.
            boolean reactionWindowOver = now - s.actionStartedTick >= WITHDRAW_MIN_REACTION_TICKS;
            if (now - s.actionStartedTick >= StevesArmyConfig.getExposedWithdrawTimeoutTicks()
                || (!overrun && reactionWindowOver)) {
                s.action = ExposedAction.NONE;
            } else {
                return new Decision(ExposedAction.WITHDRAW, withdrawTarget(soldier), "overrun-withdraw");
            }
        }

        // 2. Blocked firing lane: one bounded sidestep, then re-evaluate.
        if (ffBlockedRecently && hasLoS && gunReady && !suppressed
            && s.adjustAttempts < StevesArmyConfig.getExposedAdjustMaxAttempts()
            && s.action != ExposedAction.BOUNDED_ADJUST) {
            s.action = ExposedAction.BOUNDED_ADJUST;
            s.actionStartedTick = now;
            s.adjustAttempts++;
            s.plantHoldUntilTick = 0;
            Vec3 move = sidestepTarget(soldier, s.shotBlockedAim);
            return new Decision(ExposedAction.BOUNDED_ADJUST, move, "lane-blocked-sidestep");
        }
        if (s.action == ExposedAction.BOUNDED_ADJUST
            && now - s.actionStartedTick >= StevesArmyConfig.getExposedAdjustTimeoutTicks()) {
            s.action = ExposedAction.NONE;
        }

        // 3. Stability window: a chosen plant/hold persists long enough to mean
        // something before re-deciding (no constant strafing).
        boolean stable = s.action == ExposedAction.PLANT_AND_ENGAGE || s.action == ExposedAction.DELIBERATE_HOLD;
        if (stable && now - s.actionStartedTick < StevesArmyConfig.getExposedPlantMinTicks()) {
            return Decision.passive(s.action, "stability-window");
        }

        // 4. Default doctrine by shot availability.
        if (hasLoS && gunReady) {
            s.action = ExposedAction.PLANT_AND_ENGAGE;
            s.actionStartedTick = now;
            return Decision.passive(ExposedAction.PLANT_AND_ENGAGE, "plant-engage");
        }
        s.action = ExposedAction.DELIBERATE_HOLD;
        s.actionStartedTick = now;
        return Decision.passive(ExposedAction.DELIBERATE_HOLD, "no-shot-hold");
    }

    /** Sidestep perpendicular to the blocked aim direction, capped, avoiding friendly collisions. */
    private static Vec3 sidestepTarget(SoldierEntity soldier, Vec3 aimPoint) {
        if (aimPoint == null) return null;
        Vec3 aim = new Vec3(aimPoint.x - soldier.getX(), 0, aimPoint.z - soldier.getZ());
        if (aim.lengthSqr() < 0.01) return null;
        aim = aim.normalize();
        Vec3 perp = new Vec3(-aim.z, 0, aim.x);
        double reach = StevesArmyConfig.getExposedSidestepBlocks();
        Vec3 right = soldier.position().add(perp.scale(reach));
        Vec3 left = soldier.position().subtract(perp.scale(reach));
        // Prefer the side without a friendly body in the landing spot.
        if (friendlyNear(soldier, right, 1.2) && !friendlyNear(soldier, left, 1.2)) {
            return left;
        }
        return right;
    }

    /** Bounded retreat: directly away from the nearest threat, capped, toward squadmates when possible. */
    private static Vec3 withdrawTarget(SoldierEntity soldier) {
        double dist = StevesArmyConfig.getExposedWithdrawDistance();
        Vec3 away = null;
        LivingEntity threat = soldier.getTarget();
        if (threat != null) {
            away = new Vec3(soldier.getX() - threat.getX(), 0, soldier.getZ() - threat.getZ());
        }
        if (away == null || away.lengthSqr() < 0.01) {
            // No live target reference: back toward the squad anchor/owner.
            LivingEntity owner = soldier.getOwner();
            if (owner != null) {
                away = new Vec3(owner.getX() - soldier.getX(), 0, owner.getZ() - soldier.getZ());
            } else {
                return null;
            }
        }
        away = away.normalize();
        return soldier.position().add(away.scale(dist));
    }

    private static boolean friendlyNear(SoldierEntity self, Vec3 pos, double radius) {
        for (SoldierEntity other : self.level().getEntitiesOfClass(SoldierEntity.class,
                self.getBoundingBox().inflate(radius * 2))) {
            if (other == self || !other.isAlive()) continue;
            if (other.position().distanceTo(pos) <= radius) return true;
        }
        return false;
    }
}

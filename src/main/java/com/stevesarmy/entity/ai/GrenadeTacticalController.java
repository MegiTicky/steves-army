package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GrenadeIntegration;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plans infrequent, native LesRaisins explosive throws without owning movement. */
public final class GrenadeTacticalController {
    private static final int EVALUATION_INTERVAL = 10;
    private static final int PREPARE_TICKS = 10;
    private static final int RESERVATION_LEASE_TICKS = PREPARE_TICKS + 20;
    private static final double BLAST_RADIUS = 5.5;
    private static final double MAX_LANDING_ERROR = 1.75;
    private static final double THROWER_CLEARANCE_DISTANCE = 2.75;
    private static final int THROWER_CLEARANCE_TICKS = 3;

    private final SoldierEntity soldier;
    private State state = State.IDLE;
    private Plan pendingPlan;
    private int prepareTicks;
    private float savedYaw;
    private float savedPitch;
    private float savedHeadYaw;
    private float savedBodyYaw;
    private boolean grenadeReservationHeld;
    private Arc pendingArc;
    private GrenadeIntegration.BallisticProfile pendingProfile;
    private String lastDecisionReason;
    private long lastDecisionLogTick = Long.MIN_VALUE;
    private boolean lastArcSawFriendlyPathBlock;
    private boolean lastArcSawThrowerCoverBlock;

    private enum State { IDLE, PREPARING }

    private enum TargetSource {
        LIVE_ENTITY,
        THREAT_INTEL,
        SUPPRESSION_INTEL
    }

    private record Candidate(@Nullable LivingEntity entity, UUID targetId, Vec3 landing,
                             TargetSource source, @Nullable SquadThreatIntel.ThreatKnowledge knowledge,
                             int score) {}

    private record Plan(ItemStack stack, int slot, Candidate candidate, Arc arc,
                        GrenadeIntegration.BallisticProfile profile,
                        @Nullable SquadData squad) {}

    private record CandidateResolution(@Nullable Candidate candidate, String reason) {}

    private record Decision(@Nullable Plan plan, String reason) {}

    private record ArbitrationBlocker(UUID soldierId, int score) {}

    public record ForceThrowResult(boolean success, String message) {
        public static ForceThrowResult success(String message) {
            return new ForceThrowResult(true, message);
        }

        public static ForceThrowResult failure(String message) {
            return new ForceThrowResult(false, message);
        }
    }

    public GrenadeTacticalController(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    /** Returns the candidate score used by the shared squad arbitration pass. */
    public int evaluateArbitrationScore(@Nullable LivingEntity target,
                                        @Nullable SquadThreatIntel intel,
                                        long gameTime) {
        Decision decision = evaluate(target, intel, gameTime);
        return decision.plan == null ? Integer.MIN_VALUE : decision.plan.candidate.score;
    }

    /** Attempts a safe administrator/debug throw while bypassing tactical selection and cooldowns. */
    public ForceThrowResult forceThrow(@Nullable LivingEntity target) {
        cancel("force throw takeover");
        if (soldier.level().isClientSide) {
            return ForceThrowResult.failure("must be executed on the server");
        }
        if (!GrenadeIntegration.isAvailable()) {
            return ForceThrowResult.failure("LesRaisins grenade integration is unavailable");
        }
        if (!soldier.isAlive() || soldier.isRemoved()) {
            return ForceThrowResult.failure("soldier is not alive or has been removed");
        }
        if (target == null || !target.isAlive()) {
            return ForceThrowResult.failure("target is missing or not alive");
        }
        if (target == soldier) {
            return ForceThrowResult.failure("soldier cannot target itself");
        }
        if (isFriendly(target)) {
            return ForceThrowResult.failure("target is friendly; refusing a friendly-fire throw");
        }
        if (soldier.isHealing()) {
            return ForceThrowResult.failure("soldier is healing");
        }
        if (soldier.isPassenger()) {
            return ForceThrowResult.failure("soldier is a passenger");
        }
        if (soldier.isNavigationTraversalLocked()) {
            return ForceThrowResult.failure("soldier is navigation-locked");
        }

        int slot = findGrenadeSlot();
        if (slot < 0) {
            return ForceThrowResult.failure(
                "soldier has no supported grenade in general inventory (expected "
                    + GrenadeIntegration.supportedItemDescription() + ")");
        }
        GrenadeIntegration.SupportInfo forceSupport = GrenadeIntegration.inspect(
            soldier.getSoldierInventory().getItem(slot));
        logDebug("force throw selected slot=" + slot + " " + formatSupport(forceSupport), target, null);

        double distance = soldier.distanceTo(target);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return ForceThrowResult.failure(String.format(
                "target is %.1f blocks away; allowed range is %.1f-%.1f",
                distance, StevesArmyConfig.getGrenadeMinRange(), StevesArmyConfig.getGrenadeMaxRange()));
        }

        Vec3 landing = target.position().add(0.0, 0.2, 0.0);
        if (!isSafeLanding(landing)) {
            return ForceThrowResult.failure("a friendly entity is inside the blast safety radius");
        }
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(
            soldier.getSoldierInventory().getItem(slot));
        if (!ballistic.available()) {
            return ForceThrowResult.failure("ballistic profile unavailable: " + ballistic.reason());
        }
        Arc arc = findArc(landing, ballistic.profile());
        if (arc == null) {
            if (lastArcSawFriendlyPathBlock) {
                return ForceThrowResult.failure("a friendly entity is in the grenade path");
            }
            if (lastArcSawThrowerCoverBlock) {
                return ForceThrowResult.failure("thrower cover blocks every safe launch arc");
            }
            return ForceThrowResult.failure(
                "no safe ballistic arc reaches the target; check terrain, throw path, and target position");
        }
        renderDebugTrajectory(arc, landing);

        ItemStack stack = soldier.getSoldierInventory().getItem(slot);
        float oldYaw = soldier.getYRot();
        float oldPitch = soldier.getXRot();
        float oldHeadYaw = soldier.getYHeadRot();
        float oldBodyYaw = soldier.getCrawlFacingYaw();
        applyArcRotation(arc);
        GrenadeIntegration.ThrowResult throwResult;
        try {
            throwResult = GrenadeIntegration.throwGrenadeDetailed(
                soldier, stack, arc.initialVelocity());
        } finally {
            soldier.setYRot(oldYaw);
            soldier.setXRot(oldPitch);
            soldier.setYHeadRot(oldHeadYaw);
            soldier.setYBodyRot(oldBodyYaw);
        }
        if (!throwResult.success()) {
            return ForceThrowResult.failure("LesRaisins throw failed: "
                + throwResult.reason() + " " + formatThrowResult(throwResult));
        }
        soldier.getSoldierInventory().setChanged();
        return ForceThrowResult.success(String.format(
            "threw grenade from slot %d toward %s (%s; %s; cooldowns bypassed)",
            slot, target.getName().getString(), formatArc(arc, ballistic.profile()),
            formatThrowResult(throwResult)));
    }

    /** Returns true while grenade preparation owns this combat tick. */
    public boolean tick(@Nullable LivingEntity target, @Nullable SquadThreatIntel intel) {
        if (soldier.level().isClientSide || !GrenadeIntegration.isAvailable()) {
            logDebug("tick unavailable: client-side or LesRaisins integration unavailable", target, intel);
            cancel("grenade tick unavailable");
            return false;
        }

        if (state == State.PREPARING) {
            String invalidationReason = preparationInvalidationReason(target, intel);
            if (invalidationReason != null) {
                logDecision("preparation invalidated: " + invalidationReason, target, intel);
                cancel("preparation invalidated: " + invalidationReason);
                return false;
            }
            // Repeat the trajectory while the soldier is preparing so the
            // server-side particle preview remains visible to nearby players.
            renderDebugTrajectory(pendingArc, pendingPlan.candidate.landing);
            if (prepareTicks > 0) {
                alignToPlan();
                prepareTicks--;
                return true;
            }
            return throwPendingGrenade();
        }

        long gameTime = soldier.level().getGameTime();
        if (gameTime % EVALUATION_INTERVAL != 0) return false;

        Decision decision = evaluate(target, intel, gameTime);
        if (decision.plan == null) {
            logDecision(decision.reason, target, intel);
            return false;
        }
        Plan plan = decision.plan;
        ArbitrationBlocker blocker = findArbitrationBlocker(plan);
        if (blocker != null) {
            logDecision("squad arbitration selected soldier " + blocker.soldierId()
                + " (score=" + blocker.score() + ") over this candidate", target, intel);
            return false;
        }

        renderDebugTrajectory(plan.arc, plan.candidate.landing);

        if (plan.squad != null) {
            SquadData.GrenadeReservationResult reservation = plan.squad.tryReserveGrenade(
                soldier.getUUID(), gameTime, RESERVATION_LEASE_TICKS);
            if (!reservation.acquired()) {
                if ("squad cooldown".equals(reservation.reason())) {
                    logDecision("squad cooldown active remaining=" + reservation.remainingTicks()
                        + " lastThrow=" + plan.squad.getLastGrenadeTick(), target, intel);
                } else {
                    logDecision("squad reservation denied owner=" + reservation.owner()
                        + " remaining=" + reservation.remainingTicks(), target, intel);
                }
                return false;
            }
            grenadeReservationHeld = true;
            logDecision("squad reservation acquired owner=" + soldier.getUUID()
                + " expires=" + reservation.expiresAtTick(), target, intel);
        }

        logDecision("grenade plan began slot=" + plan.slot
            + " count=" + plan.stack.getCount()
            + " source=" + plan.candidate.source + " " + formatArc(plan.arc, plan.profile), target, intel);

        pendingPlan = plan;
        pendingArc = plan.arc;
        pendingProfile = plan.profile;
        prepareTicks = PREPARE_TICKS;
        savedYaw = soldier.getYRot();
        savedPitch = soldier.getXRot();
        savedHeadYaw = soldier.getYHeadRot();
        savedBodyYaw = soldier.getCrawlFacingYaw();
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }
        alignToPlan();
        state = State.PREPARING;
        return true;
    }

    private Decision evaluate(@Nullable LivingEntity target, @Nullable SquadThreatIntel intel,
                              long gameTime) {
        if (!StevesArmyConfig.areGrenadesEnabled()) {
            return new Decision(null, "grenades disabled in config");
        }
        if (!soldier.isAlive()) {
            return new Decision(null, "soldier is not alive");
        }
        if (soldier.isHealing()) {
            return new Decision(null, "soldier is healing");
        }
        if (soldier.isPassenger()) {
            return new Decision(null, "soldier is a passenger");
        }
        if (soldier.isNavigationTraversalLocked()) {
            return new Decision(null, "soldier is navigation-locked");
        }
        if (soldier.getCoverBehaviorManager().isSuppressed()
            && !soldier.getCoverBehaviorManager().isInCover()) {
            return new Decision(null, "suppression response requires recognized cover");
        }
        if (!soldier.canUseGrenade(gameTime)) {
            return new Decision(null, "personal grenade cooldown active");
        }

        int slot = findGrenadeSlot();
        if (slot < 0) {
            return new Decision(null,
                "no supported grenade in general inventory (expected "
                    + GrenadeIntegration.supportedItemDescription() + ")"
                    + " | " + GrenadeIntegration.describeInventory(soldier.getSoldierInventory()));
        }

        CandidateResolution resolution = resolveCandidate(target, intel);
        Candidate candidate = resolution.candidate;
        if (candidate == null) {
            return new Decision(null, resolution.reason);
        }

        double distance = soldier.position().distanceTo(candidate.landing);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return new Decision(null, String.format(
                "target position is %.1f blocks away; allowed range is %.1f-%.1f",
                distance, StevesArmyConfig.getGrenadeMinRange(), StevesArmyConfig.getGrenadeMaxRange()));
        }
        if (!isSafeLanding(candidate.landing)) {
            return new Decision(null, "friendly entity in blast safety radius");
        }

        ItemStack selectedStack = soldier.getSoldierInventory().getItem(slot);
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(selectedStack);
        if (!ballistic.available()) {
            return new Decision(null, "ballistic profile unavailable: " + ballistic.reason());
        }

        Arc arc = findArc(candidate.landing, ballistic.profile());
        if (arc == null) {
            if (lastArcSawFriendlyPathBlock) {
                return new Decision(null, "friendly entity in grenade path");
            }
            if (lastArcSawThrowerCoverBlock) {
                return new Decision(null, "thrower cover blocks every safe launch arc");
            }
            return new Decision(null, "no native ballistic arc reaches the target");
        }

        return new Decision(new Plan(selectedStack, slot, candidate, arc,
            ballistic.profile(), getSquadData()),
            "candidate=" + candidate.source + " slot=" + slot + " count=" + selectedStack.getCount()
                + " " + formatArc(arc, ballistic.profile()));
    }

    @Nullable
    private CandidateResolution resolveCandidate(@Nullable LivingEntity target, @Nullable SquadThreatIntel intel) {
        if (target != null && target.isAlive() && TargetAcquisition.isValidTarget(soldier, target)) {
            boolean eyeVisible = TargetAcquisition.hasLineOfSight(soldier, target);
            Vec3 footPoint = target.position().add(0.0, 0.25, 0.0);
            boolean footVisible = TargetAcquisition.hasLineOfSightToPosition(soldier, footPoint);
            boolean targetProtected = !eyeVisible || !footVisible;
            boolean suppressionResponse = isSuppressionResponse(intel, target.getUUID());
            boolean defensive = soldier.getCoverBehaviorManager().isInCover()
                && eyeVisible
                && ((target instanceof Mob mob && mob.getTarget() == soldier)
                    || TargetAcquisition.hasLineOfSight(target, soldier));

            if (targetProtected || defensive || suppressionResponse) {
                if (targetProtected) {
                    SquadThreatIntel.ThreatKnowledge knowledge = getFreshThreat(intel, target.getUUID());
                    if (knowledge == null) {
                        return new CandidateResolution(null, hiddenThreatReason(intel, target.getUUID()));
                    }
                    return new CandidateResolution(new Candidate(target, target.getUUID(),
                        threatLanding(knowledge.lastKnownPosition),
                        suppressionResponse ? TargetSource.SUPPRESSION_INTEL : TargetSource.THREAT_INTEL,
                        knowledge, targetProtected ? 3 : 2), "hidden target using squad intel");
                }
                return new CandidateResolution(new Candidate(target, target.getUUID(),
                    target.position().add(0.0, 0.2, 0.0),
                    suppressionResponse ? TargetSource.SUPPRESSION_INTEL : TargetSource.LIVE_ENTITY,
                    getKnownThreat(intel, target.getUUID()), suppressionResponse ? 3 : 2),
                    suppressionResponse ? "suppression response using visible target" : "visible defensive target");
            }

            if (soldier.getCoverBehaviorManager().isSuppressed()
                && !soldier.getCoverBehaviorManager().isInCover()) {
                return new CandidateResolution(null, "suppression response requires recognized cover");
            }
            return new CandidateResolution(null, "target is exposed and no defensive grenade condition is active");
        }

        SquadThreatIntel.ThreatKnowledge threat = selectPositionThreat(intel);
        if (threat == null) return new CandidateResolution(null, positionThreatReason(intel));
        boolean suppressionResponse = isSuppressionResponse(intel, threat.threatEntityId);
        TargetSource source = suppressionResponse ? TargetSource.SUPPRESSION_INTEL : TargetSource.THREAT_INTEL;
        return new CandidateResolution(new Candidate(null, threat.threatEntityId,
            threatLanding(threat.lastKnownPosition), source, threat, 3),
            source == TargetSource.SUPPRESSION_INTEL
                ? "suppression response using threat intel position"
                : "position-only throw using threat intel");
    }

    private String hiddenThreatReason(@Nullable SquadThreatIntel intel, UUID threatId) {
        if (intel == null || getKnownThreat(intel, threatId) == null) {
            return "hidden target has no squad threat intel";
        }
        return "hidden threat intel stale or below minimum accuracy";
    }

    private String positionThreatReason(@Nullable SquadThreatIntel intel) {
        if (intel == null || intel.getAllThreats().isEmpty()) {
            return "no live target or usable threat intel";
        }
        return "threat intel stale, low-confidence, missing position, or out of range";
    }

    private boolean isSuppressionResponse(@Nullable SquadThreatIntel intel, UUID threatId) {
        if (!soldier.getCoverBehaviorManager().isInCover()) return false;
        if (intel == null) return false;
        return intel.getThreat(threatId)
            .map(threat -> threat.isAlive
                && (soldier.getCoverBehaviorManager().isSuppressed()
                    || threat.suppressors.contains(soldier.getUUID())))
            .orElse(false);
    }

    @Nullable
    private SquadThreatIntel.ThreatKnowledge getKnownThreat(@Nullable SquadThreatIntel intel, UUID threatId) {
        return intel == null ? null : intel.getThreat(threatId).orElse(null);
    }

    @Nullable
    private SquadThreatIntel.ThreatKnowledge getFreshThreat(@Nullable SquadThreatIntel intel, UUID threatId) {
        return getFreshThreat(soldier, intel, threatId);
    }

    @Nullable
    private SquadThreatIntel.ThreatKnowledge getFreshThreat(SoldierEntity actor,
                                                            @Nullable SquadThreatIntel intel,
                                                            UUID threatId) {
        SquadThreatIntel.ThreatKnowledge threat = getKnownThreat(intel, threatId);
        if (threat == null || !threat.isAlive || threat.lastKnownPosition == null) return null;
        long age = actor.level().getGameTime() - threat.lastSeenTime;
        if (age < 0 || age > 120 || threat.accuracy < StevesArmyConfig.getGrenadeMinThreatAccuracy()) {
            return null;
        }
        return threat;
    }

    @Nullable
    private SquadThreatIntel.ThreatKnowledge selectPositionThreat(@Nullable SquadThreatIntel intel) {
        if (intel == null) return null;

        // An assigned suppression source is the soldier's current hostile fire
        // contact. Do not silently redirect a suppressed soldier to a different
        // threat if that assignment has gone stale.
        SquadThreatIntel.ThreatKnowledge assigned = intel
            .getAssignedThreatForSoldier(soldier.getUUID()).orElse(null);
        if (assigned != null) {
            return isUsablePositionThreat(assigned) ? assigned : null;
        }

        SquadThreatIntel.ThreatKnowledge best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
            if (!isUsablePositionThreat(threat)) continue;
            long age = soldier.level().getGameTime() - threat.lastSeenTime;
            double distance = soldier.position().distanceTo(threatLanding(threat.lastKnownPosition));
            if (distance < StevesArmyConfig.getGrenadeMinRange()
                || distance > StevesArmyConfig.getGrenadeMaxRange()) continue;
            double score = threat.accuracy * 100.0 - age - distance * 0.01;
            if (isSuppressionResponse(intel, threat.threatEntityId)) score += 10.0;
            if (score > bestScore) {
                best = threat;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean isUsablePositionThreat(@Nullable SquadThreatIntel.ThreatKnowledge threat) {
        if (threat == null || !threat.isAlive || threat.lastKnownPosition == null) return false;
        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        if (age < 0 || age > 120 || threat.accuracy < StevesArmyConfig.getGrenadeMinThreatAccuracy()) {
            return false;
        }
        double distance = soldier.position().distanceTo(threatLanding(threat.lastKnownPosition));
        return distance >= StevesArmyConfig.getGrenadeMinRange()
            && distance <= StevesArmyConfig.getGrenadeMaxRange();
    }

    private Vec3 threatLanding(@Nullable BlockPos position) {
        if (position == null) return soldier.position();
        return new Vec3(position.getX() + 0.5, position.getY() + 0.25, position.getZ() + 0.5);
    }

    private void logDecision(String reason) {
        logDecision(reason, null, null);
    }

    private void logDecision(String reason, @Nullable LivingEntity target,
                             @Nullable SquadThreatIntel intel) {
        if (!DiagnosticLogEnabled()) return;
        long gameTime = soldier.level().getGameTime();
        String message = reason + " | " + debugContext(target, intel);
        if (!message.equals(lastDecisionReason) || gameTime - lastDecisionLogTick >= 40) {
            lastDecisionReason = message;
            lastDecisionLogTick = gameTime;
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(), message);
        }
    }

    private void logDebug(String reason, @Nullable LivingEntity target,
                          @Nullable SquadThreatIntel intel) {
        if (!DiagnosticLogEnabled()) return;
        long gameTime = soldier.level().getGameTime();
        String message = reason + " | " + debugContext(target, intel);
        if (!message.equals(lastDecisionReason) || gameTime - lastDecisionLogTick >= 40) {
            lastDecisionReason = message;
            lastDecisionLogTick = gameTime;
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(), message);
        }
    }

    private String debugContext(@Nullable LivingEntity target, @Nullable SquadThreatIntel intel) {
        String targetState;
        if (target == null) {
            targetState = "target=null";
        } else {
            boolean valid = target.isAlive() && TargetAcquisition.isValidTarget(soldier, target);
            boolean eyeLos = target.isAlive() && TargetAcquisition.hasLineOfSight(soldier, target);
            boolean footLos = target.isAlive() && TargetAcquisition.hasLineOfSightToPosition(
                soldier, target.position().add(0.0, 0.25, 0.0));
            targetState = String.format("target=%s(id=%d,alive=%s,valid=%s,eyeLos=%s,footLos=%s)",
                target.getUUID(), target.getId(), target.isAlive(), valid, eyeLos, footLos);
        }

        String intelState = "intel=null";
        if (intel != null) {
            SquadThreatIntel.ThreatKnowledge assigned = intel
                .getAssignedThreatForSoldier(soldier.getUUID()).orElse(null);
            String assignedState = assigned == null ? "none" : formatThreat(assigned);
            List<SquadThreatIntel.ThreatKnowledge> threats = intel.getAllThreats();
            StringBuilder threatState = new StringBuilder("[");
            for (int i = 0; i < threats.size() && i < 4; i++) {
                if (i > 0) threatState.append(';');
                threatState.append(formatThreat(threats.get(i)));
            }
            if (threats.size() > 4) threatState.append(";...");
            threatState.append(']');
            intelState = String.format("intelCount=%d,assigned=%s,threats=%s",
                intel.getThreatCount(), assignedState, threatState);
        }

        int grenadeSlot = findGrenadeSlot();
        long cooldownRemaining = Math.max(0L,
            soldier.getGrenadeCooldownUntilTick() - soldier.level().getGameTime());
        return String.format("state=%s,cover=%s,suppressed=%s,slot=%d,cooldown=%dt,%s,%s,%s",
            state, soldier.getCoverBehaviorManager().isInCover(),
            soldier.getCoverBehaviorManager().isSuppressed(), grenadeSlot,
            cooldownRemaining, targetState, intelState, grenadeSquadState());
    }

    private String debugSide() {
        return soldier instanceof EnemySoldierEntity ? "ENEMY" : "FRIENDLY";
    }

    private String grenadeSquadState() {
        SquadData squad = pendingPlan != null && pendingPlan.squad != null
            ? pendingPlan.squad : getSquadData();
        if (squad == null) return "squad=none";

        long gameTime = soldier.level().getGameTime();
        long lastThrow = squad.getLastGrenadeTick();
        String lastThrowState = lastThrow == Long.MIN_VALUE ? "never" : Long.toString(lastThrow);
        SquadData.GrenadeReservation reservation = squad.getGrenadeReservation(gameTime);
        if (reservation == null) {
            return String.format("squadCooldown=%dt,lastThrow=%s,reservation=none",
                squad.getGrenadeCooldownRemaining(gameTime), lastThrowState);
        }
        return String.format("squadCooldown=%dt,lastThrow=%s,reservationOwner=%s,reservationExpires=%d,reservationRemaining=%dt",
            squad.getGrenadeCooldownRemaining(gameTime), lastThrowState,
            reservation.owner(), reservation.expiresAtTick(), reservation.remainingTicks());
    }

    private String formatThreat(SquadThreatIntel.ThreatKnowledge threat) {
        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        return String.format("%s(alive=%s,pos=%s,age=%dt,accuracy=%.2f,suppressors=%d)",
            threat.threatEntityId, threat.isAlive, threat.lastKnownPosition, age,
            threat.accuracy, threat.suppressors.size());
    }

    public void cancel() {
        cancel("controller cancelled");
    }

    private void cancel(String reason) {
        releasePendingReservation(reason);
        if (state == State.PREPARING) restoreRotation();
        state = State.IDLE;
        pendingPlan = null;
        pendingArc = null;
        pendingProfile = null;
        prepareTicks = 0;
    }

    private void releasePendingReservation(String reason) {
        if (!grenadeReservationHeld || pendingPlan == null || pendingPlan.squad == null) {
            grenadeReservationHeld = false;
            return;
        }
        pendingPlan.squad.releaseGrenadeReservation(soldier.getUUID());
        grenadeReservationHeld = false;
        logDecision("squad grenade reservation released reason=" + reason);
    }

    @Nullable
    private String preparationInvalidationReason(@Nullable LivingEntity target,
                                                  @Nullable SquadThreatIntel intel) {
        if (pendingPlan == null || soldier.isHealing() || soldier.isPassenger()
            || soldier.isNavigationTraversalLocked() || GunIntegration.isReloading(soldier)) {
            if (pendingPlan == null) return "pending plan missing";
            if (soldier.isHealing()) return "soldier started healing";
            if (soldier.isPassenger()) return "soldier became a passenger";
            if (soldier.isNavigationTraversalLocked()) return "soldier became navigation-locked";
            return "gun started reloading";
        }

        if (pendingPlan.squad != null && grenadeReservationHeld
            && !pendingPlan.squad.isGrenadeReservationOwner(
                soldier.getUUID(), soldier.level().getGameTime())) {
            return "squad reservation expired or was lost";
        }

        Candidate candidate = pendingPlan.candidate;
        if (candidate.entity != null) {
            // Hidden intel-backed plans may outlive the combat goal's target
            // reference for a tick. Revalidate against the entity captured in
            // the plan while still requiring that entity to be alive and valid.
            LivingEntity currentTarget = target;
            if (currentTarget == null && candidate.source != TargetSource.LIVE_ENTITY) {
                currentTarget = candidate.entity;
            }
            if (currentTarget == null) return "live target reference lost";
            if (!currentTarget.isAlive()) return "live target is no longer alive";
            if (!currentTarget.getUUID().equals(candidate.targetId)) return "target identity changed";
            if (!TargetAcquisition.isValidTarget(soldier, currentTarget)) {
                return "live target is no longer valid";
            }
            if (candidate.source == TargetSource.THREAT_INTEL
                || candidate.source == TargetSource.SUPPRESSION_INTEL) {
                SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId);
                if (current == null) return "threat intel became stale or unavailable";
                if (!threatLanding(current.lastKnownPosition).equals(candidate.landing)) {
                    return "threat position changed";
                }
            }
        } else {
            SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId);
            if (current == null) return "position-only threat intel became stale or unavailable";
            if (!threatLanding(current.lastKnownPosition).equals(candidate.landing)) {
                return "position-only threat position changed";
            }
        }

        if (candidate.source == TargetSource.SUPPRESSION_INTEL
            && !isSuppressionResponse(intel, candidate.targetId)) {
            return "suppression response is no longer valid";
        }

        long gameTime = soldier.level().getGameTime();
        if (!soldier.canUseGrenade(gameTime)) return "personal grenade cooldown became active";

        ItemStack current = soldier.getSoldierInventory().getItem(pendingPlan.slot);
        GrenadeIntegration.SupportInfo support = GrenadeIntegration.inspect(current);
        if (!support.supported() || current.getCount() <= 0) {
            return "grenade slot changed: " + formatSupport(support);
        }
        double distance = soldier.position().distanceTo(candidate.landing);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return String.format("target moved out of range (%.1f blocks)", distance);
        }
        if (!isSafeLanding(candidate.landing)) return "friendly entity entered blast safety radius";
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(current);
        if (!ballistic.available()) {
            return "ballistic profile unavailable: " + ballistic.reason();
        }
        if (pendingProfile == null || !pendingProfile.equals(ballistic.profile())) {
            return "native profile changed during preparation";
        }
        Arc currentArc = findArc(candidate.landing, ballistic.profile());
        if (currentArc == null) {
            return lastArcSawFriendlyPathBlock
                ? "friendly entity entered grenade path"
                : lastArcSawThrowerCoverBlock
                ? "thrower cover blocks every safe launch arc"
                : "no native ballistic arc reaches the target";
        }
        pendingArc = currentArc;
        return null;
    }

    private void alignToPlan() {
        if (pendingArc == null) return;
        float yaw = Mth.approachDegrees(soldier.getYRot(), pendingArc.yaw, 30.0f);
        float pitch = Mth.approachDegrees(soldier.getXRot(), pendingArc.pitch, 20.0f);
        soldier.setYRot(yaw);
        soldier.setXRot(pitch);
        soldier.setYHeadRot(yaw);
        soldier.setYBodyRot(yaw);
    }

    private boolean throwPendingGrenade() {
        Plan plan = pendingPlan;
        if (plan == null) {
            cancel("pending plan missing");
            return false;
        }

        SquadData squad = plan.squad;
        long gameTime = soldier.level().getGameTime();
        if (squad != null && grenadeReservationHeld
            && !squad.isGrenadeReservationOwner(soldier.getUUID(), gameTime)) {
            logDecision("preparation invalidated: squad reservation lost before throw");
            cancel("reservation lost before throw");
            return false;
        }

        ItemStack stack = soldier.getSoldierInventory().getItem(plan.slot);
        GrenadeIntegration.SupportInfo support = GrenadeIntegration.inspect(stack);
        if (!support.supported() || support.count() <= 0) {
            releasePendingReservation("throw slot invalidated");
            logDecision("throw slot invalidated: " + formatSupport(support));
            cancel("throw slot invalidated");
            return false;
        }

        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(stack);
        if (!ballistic.available()) {
            logDecision("preparation invalidated: final ballistic profile unavailable: "
                + ballistic.reason());
            cancel("final ballistic profile unavailable");
            return false;
        }
        Arc finalArc = findArc(plan.candidate.landing, ballistic.profile());
        if (finalArc == null) {
            String reason = lastArcSawFriendlyPathBlock
                ? "final trajectory blocked by friendly entity"
                : lastArcSawThrowerCoverBlock
                ? "final trajectory blocked by thrower cover"
                : "final arc changed before throw or landing error exceeded tolerance";
            logDecision("preparation invalidated: " + reason);
            cancel(reason);
            return false;
        }
        renderDebugTrajectory(finalArc, plan.candidate.landing);

        int before = stack.getCount();
        applyArcRotation(finalArc);
        GrenadeIntegration.ThrowResult throwResult;
        try {
            throwResult = GrenadeIntegration.throwGrenadeDetailed(
                soldier, stack, finalArc.initialVelocity());
        } finally {
            restoreRotation();
        }
        if (!throwResult.success()) {
            completePendingSquadThrow(squad, gameTime, false);
            logDecision("LesRaisins rejected slot=" + plan.slot
                + " reason=" + throwResult.reason() + " " + formatThrowResult(throwResult)
                + " " + formatSupport(GrenadeIntegration.inspect(stack))
                + " " + formatArc(finalArc, ballistic.profile()));
            cancel("LesRaisins rejected the grenade throw");
            return false;
        }

        soldier.getSoldierInventory().setChanged();
        SquadData.GrenadeThrowResult squadResult = completePendingSquadThrow(
            squad, gameTime, true);
        soldier.markGrenadeUsed(gameTime);
        state = State.IDLE;
        pendingPlan = null;
        pendingArc = null;
        pendingProfile = null;
        prepareTicks = 0;
        if (DiagnosticLogEnabled()) {
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} threw target={} source={} slot={} countBefore={} countAfter={} landing={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(),
                plan.candidate.targetId, plan.candidate.source,
                plan.slot, before, stack.getCount(), plan.candidate.landing,
                formatArc(finalArc, ballistic.profile()) + " " + formatThrowResult(throwResult));
            if (squad != null && !squadResult.committed()) {
                StevesArmyMod.LOGGER.warn("[GrenadeDebug] soldier={} name={} side={} squad grenade commit failed after native throw: {}",
                    soldier.getId(), soldier.getName().getString(), debugSide(), squadResult.reason());
            }
        }
        return true;
    }

    private SquadData.GrenadeThrowResult completePendingSquadThrow(@Nullable SquadData squad,
                                                                     long gameTime,
                                                                     boolean nativeThrowSucceeded) {
        if (squad == null || !grenadeReservationHeld) {
            return new SquadData.GrenadeThrowResult(false, false,
                "no squad grenade reservation");
        }

        SquadData.GrenadeThrowResult result;
        if (soldier.level() instanceof ServerLevel level) {
            result = SquadManager.get(level).completeGrenadeThrow(
                squad, soldier.getUUID(), gameTime, nativeThrowSucceeded);
        } else {
            result = squad.completeGrenadeThrow(
                soldier.getUUID(), gameTime, nativeThrowSucceeded);
        }
        grenadeReservationHeld = false;
        if (!nativeThrowSucceeded && result.reservationReleased()) {
            logDecision("squad grenade reservation released reason=native throw failed");
        }
        if (nativeThrowSucceeded && result.committed()) {
            logDecision("squad grenade committed owner=" + soldier.getUUID()
                + " throwTick=" + gameTime);
        }
        return result;
    }

    private void restoreRotation() {
        soldier.setYRot(savedYaw);
        soldier.setXRot(savedPitch);
        soldier.setYHeadRot(savedHeadYaw);
        soldier.setYBodyRot(savedBodyYaw);
    }

    private void applyArcRotation(Arc arc) {
        soldier.setYRot(arc.yaw);
        soldier.setXRot(arc.pitch);
        soldier.setYHeadRot(arc.yaw);
        soldier.setYBodyRot(arc.yaw);
    }

    private int findGrenadeSlot() {
        return GrenadeIntegration.findSupportedSlot(soldier.getSoldierInventory());
    }

    private static int findGrenadeSlot(SoldierEntity actor) {
        return GrenadeIntegration.findSupportedSlot(actor.getSoldierInventory());
    }

    private String formatSupport(GrenadeIntegration.SupportInfo support) {
        return String.format("item=%s count=%d supported=%s throwableId=%s reason=%s",
            support.itemId(), support.count(), support.supported(),
            support.throwableId(), support.failureReason());
    }

    private String formatThrowResult(GrenadeIntegration.ThrowResult result) {
        return String.format("countBefore=%d,countAfter=%d,nativeSpeedBeforeCorrection=%.3f,"
                + "appliedSpeed=%.3f,nativeSpreadCorrected=%s,velocitySyncBroadcast=%s,"
                + "nativeVelocity=%s,appliedVelocity=%s",
            result.countBefore(), result.countAfter(), result.nativeSpeed(), result.appliedSpeed(),
            result.spreadCorrected(), result.velocitySyncBroadcast(),
            result.nativeVelocity(), result.appliedVelocity());
    }

    private boolean isSafeLanding(Vec3 landing) {
        double safeRadius = BLAST_RADIUS + StevesArmyConfig.getGrenadeSafetyMargin();
        AABB area = new AABB(landing, landing).inflate(safeRadius);
        for (LivingEntity nearby : soldier.level().getEntitiesOfClass(LivingEntity.class, area)) {
            if (nearby != soldier && isFriendly(nearby)
                && nearby.position().distanceTo(landing) <= safeRadius) {
                return false;
            }
        }
        return true;
    }

    private boolean isFriendly(LivingEntity entity) {
        if (entity == soldier) return true;
        return soldier.isFriendlyTo(entity) || entity.isAlliedTo(soldier) || soldier.isAlliedTo(entity);
    }

    private Arc findArc(Vec3 target, GrenadeIntegration.BallisticProfile profile) {
        lastArcSawFriendlyPathBlock = false;
        lastArcSawThrowerCoverBlock = false;
        Vec3 origin = new Vec3(soldier.getX(), soldier.getEyeY() - 0.1, soldier.getZ());
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float losPitch = (float) -Math.toDegrees(Math.atan2(target.y - origin.y, horizontal));
        Arc best = null;

        for (int pitchDegrees = -80; pitchDegrees <= 20; pitchDegrees++) {
            float pitch = pitchDegrees;
            double pitchRad = Math.toRadians(pitch);
            double yawRad = Math.toRadians(yaw);
            double speed = profile.launchSpeed(soldier.isCrouching());
            Vec3 velocity = new Vec3(
                -Math.sin(yawRad) * Math.cos(pitchRad) * speed,
                -Math.sin(pitchRad) * speed,
                Math.cos(yawRad) * Math.cos(pitchRad) * speed);
            Vec3 initialVelocity = velocity;
            Vec3 position = origin;
            boolean safePath = true;
            Vec3 terminalPosition = null;
            List<Vec3> path = new ArrayList<>();
            path.add(origin);

            for (int tick = 0; tick < profile.lifetime(); tick++) {
                Vec3 start = position;
                Vec3 end = start.add(velocity);
                boolean terminal = false;

                // Match ThrowableItemEntity.doMultiBounce: one native tick can
                // resolve up to three block contacts before drag and gravity.
                for (int bounce = 0; bounce < 3; bounce++) {
                    BlockHitResult blockHit = soldier.level().clip(new ClipContext(
                        start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, soldier));
                    if (blockHit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                        if (intersectsFriendly(start, end)) {
                            lastArcSawFriendlyPathBlock = true;
                            safePath = false;
                        }
                        path.add(end);
                        break;
                    }

                    Vec3 hit = blockHit.getLocation();
                    if (blockHit.getDirection() == Direction.UP && start.y() - hit.y() < 0.01) {
                        hit = new Vec3(hit.x(), start.y(), hit.z());
                    }
                    if (isThrowerSideCoverCollision(blockHit, hit, origin, tick)) {
                        lastArcSawThrowerCoverBlock = true;
                        safePath = false;
                        break;
                    }
                    if (intersectsFriendly(start, hit)) {
                        lastArcSawFriendlyPathBlock = true;
                        safePath = false;
                        break;
                    }
                    path.add(hit);

                    if (!profile.shouldBounce()) {
                        // GrenadeEntity.onDeath uses the same 0.8 interpolation
                        // from the entity position to the collision point.
                        terminalPosition = position.lerp(hit, 0.8);
                        path.add(terminalPosition);
                        terminal = true;
                        break;
                    }

                    if (bounce < 2) {
                        start = start.lerp(hit, 0.8);
                        Vec3 rest = end.subtract(start);
                        end = start.add(bounceVelocity(rest, blockHit.getDirection(), profile.bounceFactor()));
                        velocity = bounceVelocity(velocity, blockHit.getDirection(), profile.bounceFactor());
                    } else {
                        end = start.lerp(hit, 0.8);
                        velocity = Vec3.ZERO;
                    }
                }

                if (!safePath || terminal) break;

                position = end;

                double drag = soldier.level().getFluidState(BlockPos.containing(position))
                    .is(FluidTags.WATER) ? profile.waterDrag() : profile.airDrag();
                velocity = velocity.scale(drag);
                velocity = velocity.add(0.0, -profile.gravity(), 0.0);

                // LesRaisins detonates at the post-movement position when the
                // configured lifetime expires. This is the terminal point for
                // bouncing grenades, rather than the nearest airborne sample.
                if (tick == profile.lifetime() - 1) {
                    terminalPosition = position;
                }
            }

            if (safePath && terminalPosition != null) {
                double terminalError = terminalPosition.distanceTo(target);
                if (terminalError <= MAX_LANDING_ERROR
                    && (best == null || terminalError < best.error - 0.001
                        || (Math.abs(terminalError - best.error) <= 0.001 && pitch > best.pitch))) {
                    best = new Arc(yaw, pitch, losPitch, terminalError, terminalPosition,
                        initialVelocity, path, profile.lifetime(), profile.shouldBounce(), origin);
                }
            }
        }

        return best;
    }

    private Vec3 bounceVelocity(Vec3 velocity, Direction direction, double bounceFactor) {
        return switch (direction.getAxis()) {
            case X -> velocity.multiply(-bounceFactor / 1.5, bounceFactor, bounceFactor);
            case Y -> {
                Vec3 bounced = velocity.multiply(bounceFactor, -bounceFactor / 2.5, bounceFactor);
                if (bounced.y() < 0.07) {
                    bounced = new Vec3(bounced.x(), 0.0, bounced.z());
                }
                yield bounced;
            }
            case Z -> velocity.multiply(bounceFactor, bounceFactor, -bounceFactor / 1.5);
        };
    }

    private boolean isThrowerSideCoverCollision(BlockHitResult hit, Vec3 hitLocation,
                                                 Vec3 origin, int simulationTick) {
        if (!soldier.getCoverBehaviorManager().isInCover()
            || simulationTick > THROWER_CLEARANCE_TICKS
            || origin.distanceTo(hitLocation) > THROWER_CLEARANCE_DISTANCE) {
            return false;
        }

        // A grenade that contacts terrain within the launch envelope while the
        // soldier is in cover is a thrower-side obstruction, not a valid target
        // landing. This deliberately rejects low arcs that would bounce back.
        CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
        if (cover == null) return true;
        BlockPos coverPos = cover.getPosition();
        if (hit.getBlockPos().equals(coverPos)) return true;
        for (Direction protectedDirection : cover.getProtectedDirections()) {
            if (hit.getBlockPos().equals(coverPos.relative(protectedDirection))) {
                return true;
            }
        }
        return true;
    }

    private void renderDebugTrajectory(Arc arc, Vec3 target) {
        if (!DiagnosticLogEnabled() || !(soldier.level() instanceof ServerLevel level)) return;

        DustParticleOptions ballistic = new DustParticleOptions(new Vector3f(1.0f, 0.45f, 0.05f), 0.65f);
        DustParticleOptions lineOfSight = new DustParticleOptions(new Vector3f(0.1f, 0.75f, 1.0f), 0.5f);
        drawDebugParticleLine(level, ballistic, arc.path);
        drawDebugParticleLine(level, lineOfSight, dashedLine(arc.origin, target, 0.35, 0.35));
    }

    private void drawDebugParticleLine(ServerLevel level, DustParticleOptions particle, List<Vec3> points) {
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1);
            Vec3 to = points.get(i);
            double distance = from.distanceTo(to);
            int samples = Math.max(1, (int) Math.ceil(distance / 0.35));
            for (int sample = 0; sample <= samples; sample++) {
                double progress = sample / (double) samples;
                Vec3 point = from.lerp(to, progress);
                for (ServerPlayer player : level.players()) {
                    level.sendParticles(player, particle, true, point.x, point.y, point.z,
                        1, 0, 0, 0, 0);
                }
            }
        }
    }

    private List<Vec3> dashedLine(Vec3 from, Vec3 to, double segmentLength, double gapLength) {
        List<Vec3> points = new ArrayList<>();
        double distance = from.distanceTo(to);
        if (distance < 0.001) return points;
        Vec3 direction = to.subtract(from).normalize();
        double cursor = 0.0;
        while (cursor < distance) {
            double start = cursor;
            double end = Math.min(cursor + segmentLength, distance);
            points.add(from.add(direction.scale(start)));
            points.add(from.add(direction.scale(end)));
            cursor += segmentLength + gapLength;
        }
        return points;
    }

    private boolean intersectsFriendly(Vec3 from, Vec3 to) {
        for (LivingEntity nearby : soldier.level().getEntitiesOfClass(LivingEntity.class,
            new AABB(from, to).inflate(0.5))) {
            if (nearby != soldier && isFriendly(nearby)
                && nearby.getBoundingBox().inflate(0.35).clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private ArbitrationBlocker findArbitrationBlocker(Plan plan) {
        SquadData squad = plan.squad;
        if (squad == null || !(soldier.level() instanceof ServerLevel level)) return null;
        SquadManager manager = SquadManager.get(level);
        int myIndex = manager.getMemberIndex(squad.getSquadId(), soldier.getUUID());

        // The leader is not included in SquadData.memberIds, but must still
        // participate in arbitration or members can incorrectly bypass it.
        if (!squad.getLeaderId().equals(soldier.getUUID())) {
            Entity leaderEntity = level.getEntity(squad.getLeaderId());
            ArbitrationBlocker blocker = getArbitrationBlocker(leaderEntity, plan, manager, myIndex);
            if (blocker != null) return blocker;
        }

        for (UUID memberId : squad.getMemberIds()) {
            if (memberId.equals(soldier.getUUID())) continue;
            Entity entity = level.getEntity(memberId);
            ArbitrationBlocker blocker = getArbitrationBlocker(entity, plan, manager, myIndex);
            if (blocker != null) return blocker;
        }
        return null;
    }

    @Nullable
    private ArbitrationBlocker getArbitrationBlocker(@Nullable Entity entity, Plan plan,
                                                     SquadManager manager, int myIndex) {
        if (!(entity instanceof SoldierEntity other) || !other.isAlive()) return null;
        if (findGrenadeSlot(other) < 0 || !other.canUseGrenade(other.level().getGameTime())) {
            return null;
        }

        // Run the same complete evaluator used by the candidate. A cheap
        // inventory/cooldown preview can block a valid throw even when the
        // other soldier has no safe arc or has friendly fire in the blast.
        SquadData otherSquad = getSquadData(other);
        SquadThreatIntel otherIntel = otherSquad == null ? null : otherSquad.getThreatIntel();
        int otherScore = other.getGrenadeTacticalController().evaluateArbitrationScore(
            other.getTarget(), otherIntel, other.level().getGameTime());
        if (otherScore == Integer.MIN_VALUE) return null;
        int otherIndex = manager.getMemberIndex(plan.squad.getSquadId(), other.getUUID());
        if (otherScore > plan.candidate.score
            || (otherScore == plan.candidate.score && otherIndex < myIndex)) {
            return new ArbitrationBlocker(other.getUUID(), otherScore);
        }
        return null;
    }

    @Nullable
    private SquadData getSquadData() {
        if (!(soldier.level() instanceof ServerLevel level) || soldier.getSquadId() == null) return null;
        return SquadManager.get(level).getSquadById(soldier.getSquadId()).orElse(null);
    }

    @Nullable
    private SquadData getSquadData(SoldierEntity actor) {
        if (!(actor.level() instanceof ServerLevel level) || actor.getSquadId() == null) return null;
        return SquadManager.get(level).getSquadById(actor.getSquadId()).orElse(null);
    }

    private boolean DiagnosticLogEnabled() {
        return com.stevesarmy.debug.DiagnosticLogManager.isAttackLoggingEnabled()
            || com.stevesarmy.debug.DiagnosticLogManager.isGrenadeLoggingEnabled();
    }

    private String formatArc(Arc arc, GrenadeIntegration.BallisticProfile profile) {
        return String.format("yaw=%.1f,pitch=%.1f,losPitch=%.1f,originY=%.2f,predictedLanding=%s,error=%.2f,flightTicks=%d,bounce=%s,pose=%s,configuredSpeed=%.3f,launchSpeed=%.3f,initialVelocity=%s,%s",
            arc.yaw, arc.pitch, arc.losPitch, arc.origin.y, arc.predictedLanding, arc.error, arc.flightTicks,
            arc.bounced, soldier.getPose(), profile.initialSpeed(), arc.initialVelocity.length(),
            arc.initialVelocity, profile.describe());
    }

    private record Arc(float yaw, float pitch, float losPitch, double error, Vec3 predictedLanding,
                       Vec3 initialVelocity, List<Vec3> path, int flightTicks,
                       boolean bounced, Vec3 origin) {}
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GrenadeIntegration;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Plans infrequent, native LesRaisins explosive throws without owning movement. */
public final class GrenadeTacticalController {
    private static final int EVALUATION_INTERVAL = 10;
    private static final int PREPARE_TICKS = 10;
    private static final int MAX_SIMULATION_TICKS = 75;
    private static final double THROW_SPEED = 1.1;
    private static final double THROW_GRAVITY = 0.07;
    private static final double BLAST_RADIUS = 5.5;
    private static final double MAX_LANDING_ERROR = 1.75;

    private final SoldierEntity soldier;
    private State state = State.IDLE;
    private Plan pendingPlan;
    private int prepareTicks;
    private float savedYaw;
    private float savedPitch;
    private float savedHeadYaw;
    private float savedBodyYaw;
    private String lastDecisionReason;
    private long lastDecisionLogTick = Long.MIN_VALUE;
    private boolean lastArcSawFriendlyPathBlock;

    private enum State { IDLE, PREPARING }

    private enum TargetSource {
        LIVE_ENTITY,
        THREAT_INTEL,
        SUPPRESSION_INTEL
    }

    private record Candidate(@Nullable LivingEntity entity, UUID targetId, Vec3 landing,
                             TargetSource source, @Nullable SquadThreatIntel.ThreatKnowledge knowledge,
                             int score) {}

    private record Plan(ItemStack stack, int slot, Candidate candidate, float yaw, float pitch,
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

    /** Attempts a safe administrator/debug throw while bypassing tactical selection and cooldowns. */
    public ForceThrowResult forceThrow(@Nullable LivingEntity target) {
        cancel();
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
        Arc arc = findArc(landing);
        if (arc == null) {
            if (lastArcSawFriendlyPathBlock) {
                return ForceThrowResult.failure("a friendly entity is in the grenade path");
            }
            return ForceThrowResult.failure(
                "no safe ballistic arc reaches the target; check terrain, throw path, and target position");
        }

        ItemStack stack = soldier.getSoldierInventory().getItem(slot);
        float oldYaw = soldier.getYRot();
        float oldPitch = soldier.getXRot();
        float oldHeadYaw = soldier.getYHeadRot();
        float oldBodyYaw = soldier.getCrawlFacingYaw();
        soldier.setYRot(arc.yaw);
        soldier.setXRot(arc.pitch);
        soldier.setYHeadRot(arc.yaw);
        soldier.setYBodyRot(arc.yaw);
        boolean thrown = GrenadeIntegration.throwGrenade(soldier, stack);
        soldier.setYRot(oldYaw);
        soldier.setXRot(oldPitch);
        soldier.setYHeadRot(oldHeadYaw);
        soldier.setYBodyRot(oldBodyYaw);
        if (!thrown) {
            return ForceThrowResult.failure(
                "LesRaisins rejected the throw or did not consume the grenade item");
        }
        soldier.getSoldierInventory().setChanged();
        return ForceThrowResult.success(String.format(
            "threw grenade from slot %d toward %s (landing error %.2f blocks; cooldowns bypassed)",
            slot, target.getName().getString(), arc.error));
    }

    /** Returns true while grenade preparation owns this combat tick. */
    public boolean tick(@Nullable LivingEntity target, @Nullable SquadThreatIntel intel) {
        if (soldier.level().isClientSide || !GrenadeIntegration.isAvailable()) {
            logDebug("tick unavailable: client-side or LesRaisins integration unavailable", target, intel);
            cancel();
            return false;
        }

        if (state == State.PREPARING) {
            String invalidationReason = preparationInvalidationReason(target, intel);
            if (invalidationReason != null) {
                logDecision("preparation invalidated: " + invalidationReason, target, intel);
                cancel();
                return false;
            }
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

        logDecision("grenade plan began slot=" + plan.slot
            + " count=" + plan.stack.getCount()
            + " source=" + plan.candidate.source, target, intel);

        pendingPlan = plan;
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

        Arc arc = findArc(candidate.landing);
        if (arc == null) {
            if (lastArcSawFriendlyPathBlock) {
                return new Decision(null, "friendly entity in grenade path");
            }
            return new Decision(null, "no safe ballistic arc reaches the target");
        }

        ItemStack selectedStack = soldier.getSoldierInventory().getItem(slot);
        return new Decision(new Plan(selectedStack, slot, candidate,
            arc.yaw, arc.pitch, getSquadData()),
            "candidate=" + candidate.source + " slot=" + slot + " count=" + selectedStack.getCount());
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
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} {}", soldier.getId(), message);
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
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} {}", soldier.getId(), message);
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
        return String.format("state=%s,cover=%s,suppressed=%s,slot=%d,cooldown=%dt,%s,%s",
            state, soldier.getCoverBehaviorManager().isInCover(),
            soldier.getCoverBehaviorManager().isSuppressed(), grenadeSlot,
            cooldownRemaining, targetState, intelState);
    }

    private String formatThreat(SquadThreatIntel.ThreatKnowledge threat) {
        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        return String.format("%s(alive=%s,pos=%s,age=%dt,accuracy=%.2f,suppressors=%d)",
            threat.threatEntityId, threat.isAlive, threat.lastKnownPosition, age,
            threat.accuracy, threat.suppressors.size());
    }

    public void cancel() {
        if (state == State.PREPARING) restoreRotation();
        state = State.IDLE;
        pendingPlan = null;
        prepareTicks = 0;
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
        if (findArc(candidate.landing) == null) {
            return lastArcSawFriendlyPathBlock
                ? "friendly entity entered grenade path"
                : "ballistic arc became unavailable";
        }
        return null;
    }

    private void alignToPlan() {
        if (pendingPlan == null) return;
        float yaw = Mth.approachDegrees(soldier.getYRot(), pendingPlan.yaw, 30.0f);
        float pitch = Mth.approachDegrees(soldier.getXRot(), pendingPlan.pitch, 20.0f);
        soldier.setYRot(yaw);
        soldier.setXRot(pitch);
        soldier.setYHeadRot(yaw);
        soldier.setYBodyRot(yaw);
    }

    private boolean throwPendingGrenade() {
        Plan plan = pendingPlan;
        if (plan == null) {
            cancel();
            return false;
        }

        SquadData squad = plan.squad;
        long gameTime = soldier.level().getGameTime();
        if (squad != null && !squad.tryClaimGrenade(gameTime)) {
            logDecision("squad cooldown active or another member claimed the grenade");
            cancel();
            return false;
        }

        ItemStack stack = soldier.getSoldierInventory().getItem(plan.slot);
        GrenadeIntegration.SupportInfo support = GrenadeIntegration.inspect(stack);
        if (!support.supported() || support.count() <= 0) {
            logDecision("throw slot invalidated: " + formatSupport(support));
            if (squad != null) squad.releaseGrenadeClaim(gameTime);
            cancel();
            return false;
        }
        int before = stack.getCount();
        boolean thrown = GrenadeIntegration.throwGrenade(soldier, stack);
        if (!thrown) {
            if (squad != null) squad.releaseGrenadeClaim(gameTime);
            logDecision("LesRaisins rejected the grenade throw: "
                + formatSupport(GrenadeIntegration.inspect(stack)));
            cancel();
            return false;
        }

        soldier.getSoldierInventory().setChanged();
        soldier.markGrenadeUsed(gameTime);
        restoreRotation();
        state = State.IDLE;
        pendingPlan = null;
        prepareTicks = 0;
        if (DiagnosticLogEnabled()) {
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} threw target={} source={} slot={} countBefore={} countAfter={} landing={}",
                soldier.getId(), plan.candidate.targetId, plan.candidate.source,
                plan.slot, before, stack.getCount(), plan.candidate.landing);
        }
        return true;
    }

    private void restoreRotation() {
        soldier.setYRot(savedYaw);
        soldier.setXRot(savedPitch);
        soldier.setYHeadRot(savedHeadYaw);
        soldier.setYBodyRot(savedBodyYaw);
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

    private Arc findArc(Vec3 target) {
        lastArcSawFriendlyPathBlock = false;
        Vec3 origin = soldier.getEyePosition();
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        Arc best = null;

        for (int pitchDegrees = -80; pitchDegrees <= 20; pitchDegrees++) {
            float pitch = pitchDegrees;
            double pitchRad = Math.toRadians(pitch);
            double yawRad = Math.toRadians(yaw);
            Vec3 velocity = new Vec3(
                -Math.sin(yawRad) * Math.cos(pitchRad) * THROW_SPEED,
                -Math.sin(pitchRad) * THROW_SPEED,
                Math.cos(yawRad) * Math.cos(pitchRad) * THROW_SPEED);
            Vec3 position = origin;
            boolean safePath = true;
            double bestDistance = Double.MAX_VALUE;

            for (int tick = 0; tick < MAX_SIMULATION_TICKS; tick++) {
                Vec3 next = position.add(velocity);
                if (intersectsFriendly(position, next)) {
                    lastArcSawFriendlyPathBlock = true;
                    safePath = false;
                    break;
                }
                if (!VisibilityRay.trace(soldier.level(), position, next, soldier).clear()) {
                    safePath = false;
                    break;
                }
                double distanceToTarget = next.distanceTo(target);
                bestDistance = Math.min(bestDistance, distanceToTarget);
                position = next;

                // Once the simulated grenade reaches the target vicinity, its
                // post-impact path is irrelevant. Continuing until the fixed
                // lifetime makes every valid arc eventually collide with the
                // ground and incorrectly rejects it.
                if (distanceToTarget <= MAX_LANDING_ERROR) {
                    break;
                }

                velocity = velocity.add(0.0, -THROW_GRAVITY, 0.0);
            }

            if (safePath && (best == null || bestDistance < best.error)) {
                best = new Arc(yaw, pitch, bestDistance, true);
            }
        }

        return best != null && best.error <= MAX_LANDING_ERROR ? best : null;
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
        Decision otherDecision = new GrenadeTacticalController(other)
            .evaluate(other.getTarget(), otherIntel, other.level().getGameTime());
        if (otherDecision.plan == null) return null;

        int otherScore = otherDecision.plan.candidate.score;
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

    private record Arc(float yaw, float pitch, double error, boolean safePath) {}
}

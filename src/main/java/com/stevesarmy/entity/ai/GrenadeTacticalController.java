package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.AimAccuracyManager;
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
    private static final double MAX_LANDING_ERROR = 3.0;
    private static final double PREMATURE_IMPACT_TOLERANCE = 0.35;
    private static final double THROWER_CLEARANCE_DISTANCE = 2.75;
    private static final int THROWER_CLEARANCE_TICKS = 3;
    private static final int MAX_ARC_CANDIDATES = 12;
    // A bounce search is bounded to MAX_ARC_CANDIDATES. Test the complete
    // bounded set on the next evaluation tick instead of stretching one
    // search over several 10-tick evaluation intervals.
    private static final int MAX_ARC_CANDIDATES_PER_SLICE = MAX_ARC_CANDIDATES;
    private static final double ARC_PITCH_CORRECTION_DEGREES = 3.0;
    private static final double MIN_AIM_YAW_SIGMA = 1.25;
    private static final double MAX_AIM_YAW_SIGMA = 3.0;
    private static final double MIN_AIM_PITCH_SIGMA = 0.65;
    private static final double MAX_AIM_PITCH_SIGMA = 1.8;

    private final SoldierEntity soldier;
    private State state = State.IDLE;
    private ArcSearchState arcSearch;
    private long nextPlanId = 1L;
    private long activePlanId;
    private Plan pendingPlan;
    private int prepareTicks;
    private float savedYaw;
    private float savedPitch;
    private float savedHeadYaw;
    private float savedBodyYaw;
    private boolean grenadeReservationHeld;
    private Arc pendingArc;
    private GrenadeIntegration.BallisticProfile pendingProfile;
    private String lastDecisionKey;
    private long lastDecisionLogTick = Long.MIN_VALUE;
    private boolean lastArcSawFriendlyPathBlock;
    private boolean lastArcSawThrowerCoverBlock;
    private boolean lastArcRejectedAllForLanding;
    private boolean lastArcBounceMode;
    private boolean lastArcSawPrematureImpact;
    private double lastClosestTerminalError = Double.POSITIVE_INFINITY;
    private List<String> lastArcCandidateDescriptions = List.of();
    private EvaluationCache evaluationCache;
    private long lastDebugRenderTick = Long.MIN_VALUE;

    private enum State { IDLE, SEARCHING, PREPARING }

    private enum TargetSource {
        LIVE_ENTITY,
        THREAT_INTEL,
        SUPPRESSION_INTEL
    }

    private record Candidate(@Nullable LivingEntity entity, UUID targetId, Vec3 targetPoint,
                             Vec3 landing, boolean preferHighArc,
                             TargetSource source, @Nullable SquadThreatIntel.ThreatKnowledge knowledge,
                             int score) {}

    private record Plan(ItemStack stack, int slot, Candidate candidate, Arc arc,
                        GrenadeIntegration.BallisticProfile profile,
                        @Nullable SquadData squad, AimDeviation aimDeviation,
                        boolean aimResolved, long planId) {}

    private record CandidateResolution(@Nullable Candidate candidate, String reason) {}

    private record Decision(@Nullable Plan plan, String reason) {}

    private record ArbitrationBlocker(UUID soldierId, int score) {}

    private record AimDeviation(float pitchDegrees, float yawDegrees, float quality) {
        private static final AimDeviation ZERO = new AimDeviation(0.0f, 0.0f, 1.0f);
    }

    private record AimPlanResolution(@Nullable Plan plan, String reason) {}

    private record PitchCandidate(float pitch, String branch) {}

    private record PathSafetyContext(List<AABB> friendlyBoxes) {}

    private record EvaluationCache(long gameTime, @Nullable UUID targetId,
                                   @Nullable BlockPos targetBlock, int slot, int count,
                                   int pose, boolean inCover, boolean suppressed,
                                   int threatCount, @Nullable UUID assignedThreat,
                                   Decision decision) {}

    private static final class ArcSearchState {
        private final @Nullable Candidate candidate;
        private final @Nullable ItemStack stack;
        private final int stackCount;
        private final int slot;
        private final GrenadeIntegration.BallisticProfile profile;
        private final @Nullable SquadData squad;
        private final AimDeviation aimDeviation;
        private final Vec3 target;
        private final Vec3 aimPoint;
        private final Vec3 origin;
        private final LaunchOrigin launchOrigin;
        private final float yaw;
        private final float losPitch;
        private final List<PitchCandidate> candidates;
        private final PathSafetyContext safety;
        private final int pose;
        private final boolean inCover;
        private final boolean suppressed;
        private final boolean collectPath;
        private int nextCandidate;
        private int testedCandidates;

        private ArcSearchState(@Nullable Candidate candidate, @Nullable ItemStack stack, int slot,
                               GrenadeIntegration.BallisticProfile profile, @Nullable SquadData squad,
                               AimDeviation aimDeviation, Vec3 target, Vec3 aimPoint, Vec3 origin,
                               LaunchOrigin launchOrigin, float yaw, float losPitch,
                               List<PitchCandidate> candidates, PathSafetyContext safety,
                               int pose, boolean inCover, boolean suppressed, boolean collectPath) {
            this.candidate = candidate;
            this.stack = stack;
            this.stackCount = stack == null ? 0 : stack.getCount();
            this.slot = slot;
            this.profile = profile;
            this.squad = squad;
            this.aimDeviation = aimDeviation;
            this.target = target;
            this.aimPoint = aimPoint;
            this.origin = origin;
            this.launchOrigin = launchOrigin;
            this.yaw = yaw;
            this.losPitch = losPitch;
            this.candidates = candidates;
            this.safety = safety;
            this.pose = pose;
            this.inCover = inCover;
            this.suppressed = suppressed;
            this.collectPath = collectPath;
        }
    }

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

    /**
     * Uses server time as the single cadence source for both the entity gate
     * and the controller's evaluation gate. Entity-local tickCount can have a
     * different phase after spawning or loading, which would otherwise starve
     * autonomous grenade evaluation when diagnostics are disabled.
     */
    public static boolean isEvaluationTick(long gameTime) {
        return Math.floorMod(gameTime, EVALUATION_INTERVAL) == 0;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    /** Returns the candidate score used by the shared squad arbitration pass. */
    public int evaluateArbitrationScore(@Nullable LivingEntity target,
                                        @Nullable SquadThreatIntel intel,
                                        long gameTime) {
        Decision decision = evaluateCached(target, intel, gameTime);
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
        Arc arc = findArc(landing, landing, ballistic.profile(), AimDeviation.ZERO, false);
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
        renderDebugTrajectory(arc, landing, 0L);

        ItemStack stack = soldier.getSoldierInventory().getItem(slot);
        float oldYaw = soldier.getYRot();
        float oldPitch = soldier.getXRot();
        float oldHeadYaw = soldier.getYHeadRot();
        float oldBodyYaw = soldier.getCrawlFacingYaw();
        applyArcRotation(arc);
        GrenadeIntegration.ThrowResult throwResult;
        try {
            throwResult = GrenadeIntegration.throwGrenadeDetailed(
                soldier, stack, arc.origin(), arc.initialVelocity());
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
            // Repeat the trajectory preview at a low rate while preparing.
            long debugTick = soldier.level().getGameTime();
            if (DiagnosticLogEnabled() && debugTick - lastDebugRenderTick >= 5) {
                renderDebugTrajectory(pendingArc, pendingPlan.candidate.targetPoint, activePlanId);
            }
            if (prepareTicks > 0) {
                alignToPlan();
                prepareTicks--;
                return true;
            }
            return throwPendingGrenade();
        }

        long gameTime = soldier.level().getGameTime();
        if (!isEvaluationTick(gameTime)) return false;

        Decision decision;
        if (state == State.SEARCHING) {
            String invalidationReason = arcSearchInvalidationReason(target, intel);
            if (invalidationReason != null) {
                logDecision("arc search invalidated reason=" + invalidationReason, target, intel);
                arcSearch = null;
                state = State.IDLE;
                decision = new Decision(null, "arc search invalidated: " + invalidationReason);
            } else {
                decision = advanceArcSearch(target, intel);
            }
        } else {
            decision = evaluateCached(target, intel, gameTime);
        }
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

        AimPlanResolution aimed = plan.aimResolved()
            ? new AimPlanResolution(plan, "autonomous aim already resolved")
            : applyAutonomousAim(plan);
        if (aimed.plan() == null) {
            if (grenadeReservationHeld && plan.squad() != null) {
                plan.squad().releaseGrenadeReservation(soldier.getUUID());
                grenadeReservationHeld = false;
                logDecision("squad grenade reservation released reason=" + aimed.reason(), target, intel);
            }
            logDecision(aimed.reason(), target, intel);
            return false;
        }
        plan = aimed.plan();
        renderDebugTrajectory(plan.arc, plan.candidate.targetPoint, plan.planId());

        activePlanId = plan.planId();
        logDecision("plan=" + activePlanId + " grenade plan began slot=" + plan.slot
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

    private AimPlanResolution applyAutonomousAim(Plan nominalPlan) {
        AimDeviation deviation = sampleAimDeviation(nominalPlan.candidate());
        Arc arc = findArc(nominalPlan.candidate().targetPoint(), nominalPlan.candidate().landing(),
            nominalPlan.profile(), deviation, nominalPlan.candidate().preferHighArc());
        if (arc == null) {
            String reason = lastArcSawFriendlyPathBlock
                ? "inaccurate aim would enter friendly grenade path"
                : lastArcSawThrowerCoverBlock
                ? "inaccurate aim is blocked by thrower cover"
                : "inaccurate aim has " + noArcReason();
            return new AimPlanResolution(null, reason);
        }
        if (!isSafeLanding(arc.aimPoint()) || !isSafeLanding(arc.predictedLanding())) {
            return new AimPlanResolution(null,
                "inaccurate aim would place the grenade in an unsafe blast area");
        }
        return new AimPlanResolution(new Plan(nominalPlan.stack(), nominalPlan.slot(),
            nominalPlan.candidate(), arc, nominalPlan.profile(), nominalPlan.squad(), deviation,
            true, nominalPlan.planId()),
            "resolved autonomous aim deviation");
    }

    private AimDeviation sampleAimDeviation(Candidate candidate) {
        double quality;
        if (candidate.entity() != null && candidate.entity().isAlive()) {
            quality = AimAccuracyManager.getTargetAimQuality(soldier, candidate.entity());
        } else if (candidate.knowledge() != null) {
            quality = candidate.knowledge().accuracy;
        } else {
            quality = 0.5;
        }
        quality = Mth.clamp(quality, 0.0, 1.0);
        float scale = StevesArmyConfig.getGrenadeAimErrorScale();
        float yawSigma = (float) (Mth.lerp(quality, MAX_AIM_YAW_SIGMA, MIN_AIM_YAW_SIGMA) * scale);
        float pitchSigma = (float) (Mth.lerp(quality, MAX_AIM_PITCH_SIGMA, MIN_AIM_PITCH_SIGMA) * scale);
        float[] deviation = AimAccuracyManager.sampleGaussianDeviation((float) quality,
            yawSigma, pitchSigma, soldier.level());
        return new AimDeviation(deviation[0], deviation[1], (float) quality);
    }

    private Decision evaluateCached(@Nullable LivingEntity target,
                                    @Nullable SquadThreatIntel intel,
                                    long gameTime) {
        int slot = findGrenadeSlot();
        int count = slot < 0 ? 0 : soldier.getSoldierInventory().getItem(slot).getCount();
        UUID targetId = target == null ? null : target.getUUID();
        BlockPos targetBlock = target == null ? null : target.blockPosition();
        UUID assigned = intel == null
            ? null
            : intel.getAssignedThreatForSoldier(soldier.getUUID())
                .map(threat -> threat.threatEntityId).orElse(null);
        EvaluationCache cached = evaluationCache;
        if (cached != null
            && cached.gameTime() == gameTime
            && java.util.Objects.equals(cached.targetId(), targetId)
            && java.util.Objects.equals(cached.targetBlock(), targetBlock)
            && cached.slot() == slot
            && cached.count() == count
            && cached.pose() == soldier.getPose().ordinal()
            && cached.inCover() == soldier.getCoverBehaviorManager().isInCover()
            && cached.suppressed() == soldier.getCoverBehaviorManager().isSuppressed()
            && cached.threatCount() == (intel == null ? 0 : intel.getThreatCount())
            && java.util.Objects.equals(cached.assignedThreat(), assigned)) {
            return cached.decision();
        }

        Decision decision = evaluateUncached(target, intel, gameTime);
        evaluationCache = new EvaluationCache(gameTime, targetId, targetBlock, slot, count,
            soldier.getPose().ordinal(), soldier.getCoverBehaviorManager().isInCover(),
            soldier.getCoverBehaviorManager().isSuppressed(),
            intel == null ? 0 : intel.getThreatCount(), assigned, decision);
        return decision;
    }

    private Decision evaluateUncached(@Nullable LivingEntity target,
                                      @Nullable SquadThreatIntel intel,
                                      long gameTime) {
        if (state != State.IDLE) {
            return new Decision(null, state == State.SEARCHING
                ? "arc search in progress"
                : "grenade preparation in progress");
        }
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

        double distance = soldier.position().distanceTo(candidate.targetPoint);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return new Decision(null, String.format(
                "target position is %.1f blocks away; allowed range is %.1f-%.1f",
                distance, StevesArmyConfig.getGrenadeMinRange(), StevesArmyConfig.getGrenadeMaxRange()));
        }
        if (!isSafeLanding(candidate.targetPoint) || !isSafeLanding(candidate.landing)) {
            return new Decision(null, "friendly entity in blast safety radius");
        }

        ItemStack selectedStack = soldier.getSoldierInventory().getItem(slot);
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(selectedStack);
        if (!ballistic.available()) {
            return new Decision(null, "ballistic profile unavailable: " + ballistic.reason());
        }

        if (ballistic.profile().shouldBounce()) {
            String searchReason = startArcSearch(candidate, selectedStack, slot,
                ballistic.profile(), getSquadData());
            return new Decision(null, searchReason);
        }

        Arc arc = findArc(candidate.targetPoint, candidate.landing, ballistic.profile(),
            AimDeviation.ZERO, candidate.preferHighArc);
        if (arc == null) {
            if (lastArcSawFriendlyPathBlock) {
                return new Decision(null, "friendly entity in grenade path");
            }
            if (lastArcSawThrowerCoverBlock) {
                return new Decision(null, "thrower cover blocks every safe launch arc");
            }
            return new Decision(null, noArcReason());
        }

        return new Decision(new Plan(selectedStack, slot, candidate, arc,
            ballistic.profile(), getSquadData(), AimDeviation.ZERO, false, nextPlanId++),
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
                    Vec3 targetPoint = threatLanding(knowledge.lastKnownPosition);
                    boolean preferHighArc = true;
                    return new CandidateResolution(new Candidate(target, target.getUUID(),
                        targetPoint, preferredLanding(targetPoint, preferHighArc), preferHighArc,
                        suppressionResponse ? TargetSource.SUPPRESSION_INTEL : TargetSource.THREAT_INTEL,
                        knowledge, targetProtected ? 3 : 2), "hidden target using squad intel");
                }
                Vec3 targetPoint = target.position().add(0.0, 0.2, 0.0);
                boolean preferHighArc = defensive || suppressionResponse;
                return new CandidateResolution(new Candidate(target, target.getUUID(),
                    targetPoint, preferredLanding(targetPoint, preferHighArc), preferHighArc,
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
        Vec3 targetPoint = threatLanding(threat.lastKnownPosition);
        boolean preferHighArc = true;
        return new CandidateResolution(new Candidate(null, threat.threatEntityId,
            targetPoint, preferredLanding(targetPoint, preferHighArc), preferHighArc,
            source, threat, 3),
            source == TargetSource.SUPPRESSION_INTEL
                ? "suppression response using threat intel position"
                : "position-only throw using threat intel");
    }

    private String hiddenThreatReason(@Nullable SquadThreatIntel intel, UUID threatId) {
        if (intel == null || getKnownThreat(intel, threatId) == null) {
            return "target threat intel missing";
        }
        SquadThreatIntel.ThreatKnowledge threat = getKnownThreat(intel, threatId);
        if (!threat.isAlive) return "target threat marked dead";
        if (threat.lastKnownPosition == null) return "target threat position missing";

        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        if (age < 0) return "target threat intel timestamp is in the future";
        if (age > 120) return "target threat intel stale";
        if (threat.accuracy < StevesArmyConfig.getGrenadeMinThreatAccuracy()) {
            return "target threat accuracy below minimum";
        }
        return "target threat intel rejected";
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

    private Vec3 preferredLanding(Vec3 targetPoint, boolean preferHighArc) {
        if (!preferHighArc) return targetPoint;
        double overthrow = StevesArmyConfig.getGrenadeOverthrowDistance();
        Vec3 horizontal = targetPoint.subtract(soldier.position()).multiply(1.0, 0.0, 1.0);
        if (overthrow <= 0.0 || horizontal.lengthSqr() < 1.0e-6) return targetPoint;
        return targetPoint.add(horizontal.normalize().scale(overthrow));
    }

    private void logDecision(String reason) {
        logDecision(reason, null, null);
    }

    private void logDecision(String reason, @Nullable LivingEntity target,
                             @Nullable SquadThreatIntel intel) {
        if (!DiagnosticLogEnabled()) return;
        reason = withActivePlanId(reason);
        long gameTime = soldier.level().getGameTime();
        String message = reason + " | " + debugContext(target, intel);
        if (!reason.equals(lastDecisionKey) || gameTime - lastDecisionLogTick >= 40) {
            lastDecisionKey = reason;
            lastDecisionLogTick = gameTime;
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(), message);
        }
    }

    private void logDebug(String reason, @Nullable LivingEntity target,
                          @Nullable SquadThreatIntel intel) {
        if (!DiagnosticLogEnabled()) return;
        reason = withActivePlanId(reason);
        long gameTime = soldier.level().getGameTime();
        String message = reason + " | " + debugContext(target, intel);
        if (!reason.equals(lastDecisionKey) || gameTime - lastDecisionLogTick >= 40) {
            lastDecisionKey = reason;
            lastDecisionLogTick = gameTime;
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(), message);
        }
    }

    private String withActivePlanId(String reason) {
        if (state == State.PREPARING && activePlanId > 0L && !reason.startsWith("plan=")) {
            return "plan=" + activePlanId + " " + reason;
        }
        return reason;
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

        String targetIntelState = target == null
            ? "targetIntel=none"
            : formatTargetIntel(target.getUUID(), intel);

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
        return String.format("state=%s,cover=%s,suppressed=%s,slot=%d,cooldown=%dt,%s,%s,%s,%s",
            state, soldier.getCoverBehaviorManager().isInCover(),
            soldier.getCoverBehaviorManager().isSuppressed(), grenadeSlot,
            cooldownRemaining, targetState, targetIntelState, intelState, grenadeSquadState());
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

    private String formatTargetIntel(UUID targetId, @Nullable SquadThreatIntel intel) {
        if (intel == null) return String.format("targetIntel=%s(missing-squad-intel)", targetId);
        SquadThreatIntel.ThreatKnowledge threat = getKnownThreat(intel, targetId);
        if (threat == null) return String.format("targetIntel=%s(missing)", targetId);

        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        return String.format(
            "targetIntel=%s(alive=%s,pos=%s,age=%dt,limit=120,accuracy=%.2f,minAccuracy=%.2f,lastSeenBy=%s)",
            targetId, threat.isAlive, threat.lastKnownPosition, age,
            threat.accuracy, StevesArmyConfig.getGrenadeMinThreatAccuracy(),
            threat.lastSeenBySoldier);
    }

    public void cancel() {
        cancel("controller cancelled");
    }

    private void cancel(String reason) {
        releasePendingReservation(reason);
        if (state == State.PREPARING) restoreRotation();
        arcSearch = null;
        state = State.IDLE;
        pendingPlan = null;
        pendingArc = null;
        pendingProfile = null;
        prepareTicks = 0;
        activePlanId = 0L;
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
    private String arcSearchInvalidationReason(@Nullable LivingEntity target,
                                                @Nullable SquadThreatIntel intel) {
        ArcSearchState search = arcSearch;
        if (search == null) return "search state missing";
        if (!soldier.isAlive()) return "soldier is not alive";
        if (soldier.isHealing()) return "soldier started healing";
        if (soldier.isPassenger()) return "soldier became a passenger";
        if (soldier.isNavigationTraversalLocked()) return "soldier became navigation-locked";
        if (soldier.getCoverBehaviorManager().isSuppressed()
            && !soldier.getCoverBehaviorManager().isInCover()) {
            return "suppression response requires recognized cover";
        }
        if (soldier.getPose().ordinal() != search.pose
            || soldier.getCoverBehaviorManager().isInCover() != search.inCover
            || soldier.getCoverBehaviorManager().isSuppressed() != search.suppressed) {
            return "pose or cover state changed";
        }
        Candidate candidate = search.candidate;
        if (candidate == null || search.stack == null) return "search candidate missing";
        LaunchOrigin currentLaunchOrigin = resolveLaunchOrigin(candidate.landing());
        if (currentLaunchOrigin == null) return "throw origin unavailable";
        if (currentLaunchOrigin.position().distanceTo(search.origin) > 0.35) {
            return "throw origin moved";
        }

        // Intel-backed plans are deliberately allowed to outlive a combat
        // target switch. Only a live-entity plan is tied to the current target
        // selected by the combat goal.
        LivingEntity currentTarget = candidate.source() == TargetSource.LIVE_ENTITY
            ? target : candidate.entity();
        if (candidate.entity() != null) {
            if (currentTarget == null) return "live target reference lost";
            if (!currentTarget.isAlive()) return "live target is no longer alive";
            if (!currentTarget.getUUID().equals(candidate.targetId())) return "target identity changed";
            if (!TargetAcquisition.isValidTarget(soldier, currentTarget)) {
                return "live target is no longer valid";
            }
            if (candidate.source() == TargetSource.LIVE_ENTITY
                && currentTarget.position().add(0.0, 0.2, 0.0).distanceTo(candidate.targetPoint()) > 0.35) {
                return "live target position changed";
            }
        }
        if (candidate.source() != TargetSource.LIVE_ENTITY) {
            SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId());
            if (current == null) return "target threat intel became stale or unavailable";
            if (!threatLanding(current.lastKnownPosition).equals(candidate.targetPoint())) {
                return "threat position changed";
            }
        }
        if (candidate.source() == TargetSource.SUPPRESSION_INTEL
            && !isSuppressionResponse(intel, candidate.targetId())) {
            return "suppression response is no longer valid";
        }
        int currentSlot = findGrenadeSlot();
        if (currentSlot != search.slot) return "grenade slot changed";
        ItemStack currentStack = soldier.getSoldierInventory().getItem(search.slot);
        if (!GrenadeIntegration.inspect(currentStack).supported()
            || currentStack.getCount() != search.stackCount) {
            return "grenade stack changed";
        }
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(currentStack);
        if (!ballistic.available() || !ballistic.profile().equals(search.profile)) {
            return "native ballistic profile changed";
        }
        double distance = soldier.position().distanceTo(candidate.targetPoint());
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return "target moved out of range";
        }
        if (!isSafeLanding(candidate.targetPoint()) || !isSafeLanding(candidate.landing())) {
            return "friendly entity entered blast safety radius";
        }
        return null;
    }

    @Nullable
    private Arc validateSelectedArc(Vec3 targetPoint, Vec3 landing,
                                    GrenadeIntegration.BallisticProfile profile,
                                    AimDeviation deviation, Arc expectedArc) {
        ArcSearchState geometry = createArcSearchState(targetPoint, landing, profile, deviation,
            false, null, null, -1, null);
        if (geometry == null) return null;
        PitchCandidate selected = new PitchCandidate(expectedArc.pitch(), expectedArc.pitchBranch());
        return simulateArc(geometry.target, geometry.aimPoint, geometry.origin,
            geometry.launchOrigin, geometry.yaw, geometry.losPitch, selected, profile,
            deviation, geometry.candidates.size(), geometry.safety, geometry.collectPath);
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
            LivingEntity currentTarget = candidate.source == TargetSource.LIVE_ENTITY
                ? target : candidate.entity;
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
                if (!threatLanding(current.lastKnownPosition).equals(candidate.targetPoint)) {
                    return "threat position changed";
                }
            }
        } else {
            SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId);
            if (current == null) return "position-only threat intel became stale or unavailable";
            if (!threatLanding(current.lastKnownPosition).equals(candidate.targetPoint)) {
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
        double distance = soldier.position().distanceTo(candidate.targetPoint);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > StevesArmyConfig.getGrenadeMaxRange()) {
            return String.format("target moved out of range (%.1f blocks)", distance);
        }
        if (!isSafeLanding(candidate.targetPoint) || !isSafeLanding(candidate.landing)) {
            return "friendly entity entered blast safety radius";
        }
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(current);
        if (!ballistic.available()) {
            return "ballistic profile unavailable: " + ballistic.reason();
        }
        if (pendingProfile == null || !pendingProfile.equals(ballistic.profile())) {
            return "native profile changed during preparation";
        }
        Arc currentArc = validateSelectedArc(candidate.targetPoint, candidate.landing,
            ballistic.profile(),
            pendingPlan.aimDeviation(), pendingArc);
        if (currentArc == null) {
            return lastArcSawFriendlyPathBlock
                ? "friendly entity entered grenade path"
                : lastArcSawThrowerCoverBlock
                ? "thrower cover blocks every safe launch arc"
                : noArcReason();
        }
        if (!isSafeLanding(currentArc.aimPoint()) || !isSafeLanding(currentArc.predictedLanding())) {
            return "inaccurate aim entered an unsafe blast area";
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
        Arc finalArc = validateSelectedArc(plan.candidate.targetPoint, plan.candidate.landing,
            ballistic.profile(),
            plan.aimDeviation(), pendingArc);
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
        if (!isSafeLanding(finalArc.aimPoint()) || !isSafeLanding(finalArc.predictedLanding())) {
            logDecision("preparation invalidated: final inaccurate aim entered an unsafe blast area");
            cancel("final inaccurate aim entered an unsafe blast area");
            return false;
        }
        renderDebugTrajectory(finalArc, plan.candidate.targetPoint, plan.planId());

        int before = stack.getCount();
        applyArcRotation(finalArc);
        GrenadeIntegration.ThrowResult throwResult;
        try {
            throwResult = GrenadeIntegration.throwGrenadeDetailed(
                soldier, stack, finalArc.origin(), finalArc.initialVelocity());
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
            StevesArmyMod.LOGGER.info("[GrenadeDebug] soldier={} name={} side={} plan={} threw target={} source={} slot={} countBefore={} countAfter={} landing={} {}",
                soldier.getId(), soldier.getName().getString(), debugSide(),
                plan.planId(), plan.candidate.targetId, plan.candidate.source,
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

    private Arc findArc(Vec3 targetPoint, Vec3 landing,
                         GrenadeIntegration.BallisticProfile profile,
                         AimDeviation deviation, boolean preferHighArc) {
        ArcSearchState search = createArcSearchState(targetPoint, landing, profile, deviation,
            preferHighArc, null, null, -1, null);
        if (search == null) return null;

        while (search.nextCandidate < search.candidates.size()) {
            PitchCandidate candidate = search.candidates.get(search.nextCandidate++);
            search.testedCandidates++;
            Arc arc = simulateArc(search.target, search.aimPoint, search.origin,
                search.launchOrigin, search.yaw, search.losPitch, candidate, search.profile,
                search.aimDeviation, search.candidates.size(), search.safety, search.collectPath);
            if (arc != null) return arc;
        }
        lastArcRejectedAllForLanding = !lastArcSawFriendlyPathBlock
            && !lastArcSawThrowerCoverBlock
            && lastClosestTerminalError < Double.POSITIVE_INFINITY;
        return null;
    }

    @Nullable
    private ArcSearchState createArcSearchState(Vec3 targetPoint, Vec3 landing,
                                                 GrenadeIntegration.BallisticProfile profile,
                                                 AimDeviation deviation,
                                                 boolean preferHighArc,
                                                 @Nullable Candidate candidate,
                                                @Nullable ItemStack stack,
                                                int slot,
                                                @Nullable SquadData squad) {
        lastArcSawFriendlyPathBlock = false;
        lastArcSawThrowerCoverBlock = false;
        lastArcRejectedAllForLanding = false;
        lastArcBounceMode = profile.shouldBounce();
        lastArcSawPrematureImpact = false;
        lastClosestTerminalError = Double.POSITIVE_INFINITY;
        lastArcCandidateDescriptions = List.of();
        LaunchOrigin launchOrigin = resolveLaunchOrigin(landing);
        if (launchOrigin == null) {
            lastArcSawThrowerCoverBlock = true;
            return null;
        }

        Vec3 origin = launchOrigin.position();
        Vec3 aimPoint = displacedAimPoint(origin, landing, deviation);
        double dx = aimPoint.x - origin.x;
        double dz = aimPoint.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) return null;

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float losPitch = (float) -Math.toDegrees(Math.atan2(targetPoint.y - origin.y,
            Math.sqrt((targetPoint.x - origin.x) * (targetPoint.x - origin.x)
                + (targetPoint.z - origin.z) * (targetPoint.z - origin.z))));
        List<PitchCandidate> candidates = estimatePitchCandidates(
            horizontal, aimPoint.y - origin.y, profile,
            preferHighArc || launchOrigin.mode().equals("RAISED_ABOVE_COVER"));
        if (candidates.isEmpty()) return null;
        lastArcCandidateDescriptions = candidates.stream()
            .map(pitchCandidate -> String.format("%.1f/%s", pitchCandidate.pitch(), pitchCandidate.branch()))
            .toList();

        PathSafetyContext safety = createPathSafetyContext(origin, aimPoint, profile);
        return new ArcSearchState(candidate, stack, slot, profile, squad, deviation,
            targetPoint, aimPoint, origin, launchOrigin, yaw, losPitch, candidates, safety,
            soldier.getPose().ordinal(), soldier.getCoverBehaviorManager().isInCover(),
            soldier.getCoverBehaviorManager().isSuppressed(), DiagnosticLogEnabled());
    }

    private String startArcSearch(Candidate candidate, ItemStack stack, int slot,
                                   GrenadeIntegration.BallisticProfile profile,
                                   @Nullable SquadData squad) {
        AimDeviation deviation = sampleAimDeviation(candidate);
        ArcSearchState search = createArcSearchState(candidate.targetPoint, candidate.landing,
            profile, deviation, candidate.preferHighArc,
            candidate, stack, slot, squad);
        if (search == null) return noArcReason();
        if (!isSafeLanding(search.aimPoint) || !isSafeLanding(candidate.targetPoint)
            || !isSafeLanding(candidate.landing)) {
            return "inaccurate aim would place the grenade in an unsafe blast area";
        }
        arcSearch = search;
        state = State.SEARCHING;
        return "arc search started totalCandidates=" + search.candidates.size()
            + " bounce=" + profile.shouldBounce();
    }

    private Decision advanceArcSearch(@Nullable LivingEntity target,
                                      @Nullable SquadThreatIntel intel) {
        ArcSearchState search = arcSearch;
        if (search == null) {
            state = State.IDLE;
            return new Decision(null, "arc search state missing");
        }

        int testedThisSlice = 0;
        while (testedThisSlice++ < MAX_ARC_CANDIDATES_PER_SLICE
            && search.nextCandidate < search.candidates.size()) {
            PitchCandidate pitch = search.candidates.get(search.nextCandidate++);
            search.testedCandidates++;
            Arc arc = simulateArc(search.target, search.aimPoint, search.origin,
                search.launchOrigin, search.yaw, search.losPitch, pitch, search.profile,
                search.aimDeviation, search.candidates.size(), search.safety,
                DiagnosticLogEnabled());
            if (arc != null) {
                Plan plan = new Plan(search.stack, search.slot, search.candidate, arc,
                    search.profile, search.squad, search.aimDeviation, true, nextPlanId++);
                arcSearch = null;
                state = State.IDLE;
                logDecision("arc search completed selectedPitch=" + arc.pitch()
                    + " tested=" + search.testedCandidates + "/" + search.candidates.size(),
                    target, intel);
                return new Decision(plan, "arc search completed");
            }
        }

        if (search.nextCandidate < search.candidates.size()) {
            return new Decision(null, "arc search progress tested=" + search.testedCandidates
                + "/" + search.candidates.size() + " closestError=" + closestArcError());
        }

        lastArcRejectedAllForLanding = !lastArcSawFriendlyPathBlock
            && !lastArcSawThrowerCoverBlock
            && lastClosestTerminalError < Double.POSITIVE_INFINITY;
        String reason = noArcReason();
        logDecision("arc search failed closestError=" + closestArcError()
            + " candidates=" + lastArcCandidateDescriptions, target, intel);
        arcSearch = null;
        state = State.IDLE;
        return new Decision(null, reason);
    }

    private String closestArcError() {
        return lastClosestTerminalError == Double.POSITIVE_INFINITY
            ? "none" : String.format("%.2f", lastClosestTerminalError);
    }

    private List<PitchCandidate> estimatePitchCandidates(double horizontal, double vertical,
                                                          GrenadeIntegration.BallisticProfile profile,
                                                          boolean preferHigh) {
        List<PitchCandidate> candidates = new ArrayList<>(MAX_ARC_CANDIDATES);
        double speed = profile.launchSpeed(soldier.isCrouching());
        double gravity = profile.gravity();
        if (!(speed > 0.0) || !Double.isFinite(speed)) return candidates;

        List<Double> elevations = new ArrayList<>(2);
        if (gravity < 1.0e-6) {
            elevations.add(Math.atan2(vertical, horizontal));
        } else {
            double speedSquared = speed * speed;
            double discriminant = speedSquared * speedSquared
                - gravity * (gravity * horizontal * horizontal + 2.0 * vertical * speedSquared);
            if (discriminant >= 0.0) {
                double root = Math.sqrt(discriminant);
                elevations.add(Math.atan((speedSquared - root) / (gravity * horizontal)));
                elevations.add(Math.atan((speedSquared + root) / (gravity * horizontal)));
            } else if (!profile.shouldBounce()) {
                return candidates;
            }
        }

        if (profile.shouldBounce()) {
            addBounceCandidates(candidates, horizontal, vertical, profile, elevations, preferHigh);
        } else {
            if (preferHigh && elevations.size() > 1) {
                Double low = elevations.get(0);
                elevations.set(0, elevations.get(1));
                elevations.set(1, low);
            }

            for (int index = 0; index < elevations.size() && candidates.size() < MAX_ARC_CANDIDATES; index++) {
                double elevation = correctElevationForDrag(elevations.get(index), horizontal, vertical, profile);
                String branch = preferHigh
                    ? (index == 0 ? "HIGH" : "LOW")
                    : (index == 0 ? "LOW" : "HIGH");
                addPitchCandidate(candidates, elevation, branch);
                addPitchCandidate(candidates, elevation + Math.toRadians(ARC_PITCH_CORRECTION_DEGREES), branch);
                addPitchCandidate(candidates, elevation - Math.toRadians(ARC_PITCH_CORRECTION_DEGREES), branch);
            }
        }
        return candidates;
    }

    /**
     * Bouncing grenades do not have a useful single-flight analytical solution:
     * their terminal position is determined by the complete post-impact
     * lifecycle. Keep the exact simulator bounded while giving M67 a small set
     * of soldier-like release angles that can produce useful bounce distances.
     */
    private void addBounceCandidates(List<PitchCandidate> candidates, double horizontal, double vertical,
                                     GrenadeIntegration.BallisticProfile profile,
                                     List<Double> analyticalElevations, boolean preferHigh) {
        List<Double> analyticalPitches = new ArrayList<>(2);
        for (int index = 0; index < analyticalElevations.size() && analyticalPitches.size() < 2; index++) {
            double elevation = correctElevationForDrag(
                analyticalElevations.get(index), horizontal, vertical, profile);
            analyticalPitches.add(-Math.toDegrees(elevation));
        }

        double shortRange = StevesArmyConfig.getGrenadeMinRange() + 4.0;
        boolean shortThrow = horizontal <= shortRange;

        // Keep analytical branches first when available, then fill the bounded
        // search with soldier-like pitches and nearby corrections.
        if (preferHigh || shortThrow) {
            addAnalyticalPitch(candidates, analyticalPitches, 1, "ANALYTICAL_HIGH");
            addAnalyticalPitch(candidates, analyticalPitches, 0, "ANALYTICAL_LOW");
        } else {
            addAnalyticalPitch(candidates, analyticalPitches, 0, "ANALYTICAL_LOW");
            addAnalyticalPitch(candidates, analyticalPitches, 1, "ANALYTICAL_HIGH");
        }

        double[] tacticalPitches = (preferHigh || shortThrow)
            ? new double[] {-78.0, -75.0, -72.0, -63.0, -60.0, -57.0,
                -48.0, -45.0, -42.0, -33.0, -30.0, -27.0}
            : new double[] {-27.0, -30.0, -33.0, -42.0, -45.0, -48.0,
                -57.0, -60.0, -63.0, -72.0, -75.0, -78.0};
        for (double pitch : tacticalPitches) {
            addPitchCandidate(candidates, Math.toRadians(-pitch), "TACTICAL_BOUNCE");
        }
    }

    private void addAnalyticalPitch(List<PitchCandidate> candidates, List<Double> pitches,
                                    int index, String branch) {
        if (index >= 0 && index < pitches.size()) {
            addPitchCandidate(candidates, Math.toRadians(-pitches.get(index)), branch);
        }
    }

    private double correctElevationForDrag(double elevation, double horizontal, double vertical,
                                           GrenadeIntegration.BallisticProfile profile) {
        double speed = profile.launchSpeed(soldier.isCrouching());
        double drag = profile.airDrag();
        double gravity = profile.gravity();
        for (int iteration = 0; iteration < 3; iteration++) {
            double horizontalSpeed = speed * Math.cos(elevation);
            int ticks = estimateFlightTicks(horizontal, horizontalSpeed, drag, profile.lifetime());
            double sum = geometricSum(drag, ticks);
            double gravitySum = gravitySum(drag, ticks);
            if (sum <= 1.0e-6) break;
            double requiredHorizontalSpeed = horizontal / sum;
            double requiredVerticalSpeed = (vertical + gravity * gravitySum) / sum;
            double requiredElevation = Math.atan2(requiredVerticalSpeed, requiredHorizontalSpeed);
            elevation = elevation * 0.35 + requiredElevation * 0.65;
            elevation = Mth.clamp(elevation, Math.toRadians(-20.0), Math.toRadians(80.0));
        }
        return elevation;
    }

    private int estimateFlightTicks(double horizontal, double horizontalSpeed,
                                    double drag, int lifetime) {
        if (horizontalSpeed <= 1.0e-6) return lifetime;
        for (int ticks = 1; ticks <= lifetime; ticks++) {
            if (horizontalSpeed * geometricSum(drag, ticks) >= horizontal) return ticks;
        }
        return lifetime;
    }

    private double geometricSum(double drag, int ticks) {
        if (ticks <= 0) return 0.0;
        if (Math.abs(1.0 - drag) < 1.0e-8) return ticks;
        return (1.0 - Math.pow(drag, ticks)) / (1.0 - drag);
    }

    private double gravitySum(double drag, int ticks) {
        if (ticks <= 0) return 0.0;
        if (Math.abs(1.0 - drag) < 1.0e-8) return ticks * (ticks - 1) * 0.5;
        return (ticks - geometricSum(drag, ticks)) / (1.0 - drag);
    }

    private void addPitchCandidate(List<PitchCandidate> candidates, double elevation, String branch) {
        if (!Double.isFinite(elevation)) return;
        float pitch = (float) Mth.clamp(-Math.toDegrees(elevation), -80.0, 20.0);
        for (PitchCandidate existing : candidates) {
            if (Math.abs(existing.pitch() - pitch) < 0.01f) return;
        }
        if (candidates.size() < MAX_ARC_CANDIDATES) {
            candidates.add(new PitchCandidate(pitch, branch));
        }
    }

    private Vec3 displacedAimPoint(Vec3 origin, Vec3 target, AimDeviation deviation) {
        Vec3 relative = target.subtract(origin);
        double distance = relative.length();
        if (distance < 1.0e-6 || (deviation.pitchDegrees() == 0.0f && deviation.yawDegrees() == 0.0f)) {
            return target;
        }
        double horizontal = Math.sqrt(relative.x * relative.x + relative.z * relative.z);
        double azimuth = Math.atan2(relative.z, relative.x) + Math.toRadians(deviation.yawDegrees());
        double elevation = Math.atan2(relative.y, Math.max(horizontal, 1.0e-6))
            + Math.toRadians(deviation.pitchDegrees());
        double cosElevation = Math.cos(elevation);
        return origin.add(new Vec3(
            Math.cos(azimuth) * cosElevation * distance,
            Math.sin(elevation) * distance,
            Math.sin(azimuth) * cosElevation * distance));
    }

    private PathSafetyContext createPathSafetyContext(Vec3 origin, Vec3 aimPoint,
                                                      GrenadeIntegration.BallisticProfile profile) {
        double verticalReach = Math.max(8.0,
            profile.launchSpeed(soldier.isCrouching()) * profile.launchSpeed(soldier.isCrouching())
                / Math.max(0.01, profile.gravity()) + 4.0);
        AABB search = new AABB(origin, aimPoint).inflate(4.0, verticalReach, 4.0);
        List<AABB> friendlies = new ArrayList<>();
        for (LivingEntity nearby : soldier.level().getEntitiesOfClass(LivingEntity.class, search)) {
            if (nearby != soldier && isFriendly(nearby)) {
                friendlies.add(nearby.getBoundingBox());
            }
        }
        return new PathSafetyContext(friendlies);
    }

    @Nullable
    private Arc simulateArc(Vec3 target, Vec3 aimPoint, Vec3 origin, LaunchOrigin launchOrigin,
                             float yaw, float losPitch, PitchCandidate candidate,
                             GrenadeIntegration.BallisticProfile profile, AimDeviation deviation,
                             int candidateCount, PathSafetyContext safety, boolean collectPath) {
        float pitch = candidate.pitch();
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
        List<Vec3> path = collectPath ? new ArrayList<>() : List.of();
        if (collectPath) path.add(origin);

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
                    if (intersectsFriendly(start, end, safety.friendlyBoxes())) {
                        lastArcSawFriendlyPathBlock = true;
                        safePath = false;
                    }
                    if (collectPath) path.add(end);
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
                if (intersectsFriendly(start, hit, safety.friendlyBoxes())) {
                    lastArcSawFriendlyPathBlock = true;
                    safePath = false;
                    break;
                }
                if (collectPath) path.add(hit);

                if (!profile.shouldBounce()) {
                    if (isPrematureImpact(hit, origin, target)) {
                        lastArcSawPrematureImpact = true;
                        safePath = false;
                        break;
                    }
                    // The native projectile detonates on contact. Keep the
                    // collision point instead of backing up toward the prior tick.
                    terminalPosition = hit;
                    if (collectPath) path.add(terminalPosition);
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
            if (tick == profile.lifetime() - 1) terminalPosition = position;
        }

        if (!safePath || terminalPosition == null) return null;
        if (!profile.shouldBounce() && isPrematureImpact(terminalPosition, origin, target)) {
            lastArcSawPrematureImpact = true;
            return null;
        }
        double aimError = terminalPosition.distanceTo(aimPoint);
        lastClosestTerminalError = Math.min(lastClosestTerminalError, aimError);
        if (aimError > MAX_LANDING_ERROR) return null;
        return new Arc(yaw, pitch, losPitch, aimError, terminalPosition,
            initialVelocity, path, profile.lifetime(), profile.shouldBounce(), origin,
            launchOrigin.mode(), launchOrigin.coverTopY(), launchOrigin.clearance(), aimPoint,
            terminalPosition.distanceTo(target), deviation, candidateCount, candidate.branch(),
            List.copyOf(lastArcCandidateDescriptions));
    }

    @Nullable
    private LaunchOrigin resolveLaunchOrigin(Vec3 target) {
        Vec3 eyeOrigin = new Vec3(soldier.getX(), soldier.getEyeY() - 0.1, soldier.getZ());
        CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
        boolean lowPose = soldier.getPose() == net.minecraft.world.entity.Pose.CROUCHING
            || soldier.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
        if (cover == null || !soldier.getCoverBehaviorManager().isInCover() || !lowPose) {
            return new LaunchOrigin(eyeOrigin, "STANDING_EYE", Double.NaN, Double.NaN);
        }

        Vec3 towardTarget = target.subtract(soldier.position());
        Direction threatDirection = Direction.getNearest(towardTarget.x, 0.0, towardTarget.z);
        double coverTopY = cover.getPosition().getY() + Math.max(0.0, cover.getCoverHeight(threatDirection));
        double clearance = 0.18;
        double minimumY = soldier.getY() + 1.6;
        double maximumY = soldier.getY() + 2.0;
        double originY = Math.max(minimumY, coverTopY + clearance);
        if (originY > maximumY + 1.0e-4) return null;

        Vec3 horizontal = new Vec3(towardTarget.x, 0.0, towardTarget.z);
        if (horizontal.lengthSqr() < 1.0e-6) return null;
        Vec3 offset = horizontal.normalize().scale(0.2);
        Vec3 raisedOrigin = new Vec3(soldier.getX() + offset.x, originY, soldier.getZ() + offset.z);
        return new LaunchOrigin(raisedOrigin, "RAISED_ABOVE_COVER", coverTopY, originY - coverTopY);
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

    private void renderDebugTrajectory(Arc arc, Vec3 target, long planId) {
        if (!DiagnosticLogEnabled() || !(soldier.level() instanceof ServerLevel level)) return;
        lastDebugRenderTick = soldier.level().getGameTime();
        if (planId > 0L) logDecision("plan=" + planId + " selected arc rendered");

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

    private boolean intersectsFriendly(Vec3 from, Vec3 to, List<AABB> friendlyBoxes) {
        for (AABB box : friendlyBoxes) {
            if (box.inflate(0.35).clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean isPrematureImpact(Vec3 hit, Vec3 origin, Vec3 target) {
        Vec3 targetHorizontal = target.subtract(origin).multiply(1.0, 0.0, 1.0);
        Vec3 hitHorizontal = hit.subtract(origin).multiply(1.0, 0.0, 1.0);
        double targetDistance = targetHorizontal.length();
        double hitDistance = hitHorizontal.length();
        if (targetDistance <= PREMATURE_IMPACT_TOLERANCE
            || hitDistance >= targetDistance - PREMATURE_IMPACT_TOLERANCE) {
            return false;
        }
        return targetHorizontal.dot(hitHorizontal) > 0.0;
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

    private String noArcReason() {
        String candidates = lastArcCandidateDescriptions.isEmpty()
            ? "none" : String.join("|", lastArcCandidateDescriptions);
        String closest = lastClosestTerminalError == Double.POSITIVE_INFINITY
            ? "none" : String.format("%.2f", lastClosestTerminalError);
        return String.format("no native ballistic arc reaches the target (bounce=%s,candidates=%s,"
                + "allCandidatesFailedLanding=%s,prematureImpact=%s,closestTerminalError=%s)",
            lastArcBounceMode, candidates,
            lastArcRejectedAllForLanding, lastArcSawPrematureImpact, closest);
    }

    private String formatArc(Arc arc, GrenadeIntegration.BallisticProfile profile) {
        return String.format("estimator=ANALYTICAL_DRAG,candidates=%d,pitchCandidates=%s,branch=%s,yaw=%.1f,pitch=%.1f,losPitch=%.1f,originY=%.2f,aimPoint=%s,predictedLanding=%s,aimError=%.2f,targetError=%.2f,aimDeviation=(pitch=%.2f,yaw=%.2f,quality=%.2f),flightTicks=%d,bounce=%s,pose=%s,launchOrigin=%s,launchOriginMode=%s,coverTopY=%.2f,coverClearance=%.2f,configuredSpeed=%.3f,launchSpeed=%.3f,initialVelocity=%s,%s",
            arc.candidateCount, arc.candidateDescriptions, arc.pitchBranch, arc.yaw, arc.pitch, arc.losPitch, arc.origin.y,
            arc.aimPoint, arc.predictedLanding, arc.error, arc.targetError,
            arc.aimDeviation.pitchDegrees(), arc.aimDeviation.yawDegrees(), arc.aimDeviation.quality(),
            arc.flightTicks,
            arc.bounced, soldier.getPose(), arc.origin, arc.originMode, arc.coverTopY, arc.coverClearance,
            profile.initialSpeed(), arc.initialVelocity.length(),
            arc.initialVelocity, profile.describe());
    }

    private record Arc(float yaw, float pitch, float losPitch, double error, Vec3 predictedLanding,
                       Vec3 initialVelocity, List<Vec3> path, int flightTicks,
                       boolean bounced, Vec3 origin, String originMode,
                       double coverTopY, double coverClearance, Vec3 aimPoint,
                       double targetError, AimDeviation aimDeviation, int candidateCount,
                       String pitchBranch, List<String> candidateDescriptions) {}

    private record LaunchOrigin(Vec3 position, String mode, double coverTopY, double clearance) {}
}

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
import net.minecraft.server.MinecraftServer;
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
    private static final double TARGET_ZONE_RADIUS = 4.5;
    private static final double TARGET_PLANE_CLEARANCE = 0.35;
    private static final double THROWER_ENDPOINT_CLEARANCE = 2.0;
    private static final double PREMATURE_IMPACT_TOLERANCE = 0.35;
    private static final double THROWER_CLEARANCE_DISTANCE = 2.75;
    private static final int THROWER_CLEARANCE_TICKS = 3;
    private static final int MAX_ARC_CANDIDATES = 24;
    private static final double ARC_PITCH_CORRECTION_DEGREES = 3.0;
    private static final double PREMATURE_IMPACT_SCORE_PENALTY = 3.0;
    private static final double PHYSICAL_RANGE_DRAG_FACTOR = 0.96;

    private final SoldierEntity soldier;
    private State state = State.IDLE;
    private long nextPlanId = 1L;
    private long activePlanId;
    private Plan pendingPlan;
    private int prepareTicks;
    private float savedYaw;
    private float savedPitch;
    private float savedHeadYaw;
    private float savedBodyYaw;
    private boolean grenadeReservationHeld;
    private boolean arcCalculationQueued;
    private boolean finalThrowValidationQueued;
    private int preparationReplans;
    private Arc pendingArc;
    private GrenadeIntegration.BallisticProfile pendingProfile;
    private String lastDecisionKey;
    private long lastDecisionLogTick = Long.MIN_VALUE;
    private boolean lastArcBounceMode;
    private boolean lastArcSawFriendlyPathBlock;
    private boolean lastArcSawThrowerCoverBlock;
    private boolean lastArcRejectedAllForLanding;
    private boolean lastArcSawPrematureImpact;
    private double lastClosestTerminalError = Double.POSITIVE_INFINITY;
    private List<String> lastArcCandidateDescriptions = List.of();
    private EvaluationCache evaluationCache;
    private long lastDebugRenderTick = Long.MIN_VALUE;

    private enum State { IDLE, WAITING_FOR_ARC, PREPARING }

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
                        @Nullable SquadData squad, long planId) {}

    private record CandidateResolution(@Nullable Candidate candidate, String reason) {}

    private record Decision(@Nullable Plan plan, String reason) {}

    private record ArbitrationBlocker(UUID soldierId, int score) {}

    private record PitchCandidate(float pitch, String branch) {}

    private record CollisionInfo(Vec3 position, BlockPos block, Direction direction,
                                 int bounceIndex, double targetPlaneClearance) {
        private boolean clearedTargetPlane() {
            return targetPlaneClearance >= TARGET_PLANE_CLEARANCE;
        }
    }

    private record PathSafetyContext(List<AABB> friendlyBoxes) {}

    private record EvaluationCache(long gameTime, @Nullable UUID targetId,
                                   @Nullable BlockPos targetBlock, int slot, int count,
                                   int pose, boolean inCover, boolean suppressed,
                                   int threatCount, @Nullable UUID assignedThreat,
                                   Decision decision) {}

    private static final class ArcSearchState {
        private final GrenadeIntegration.BallisticProfile profile;
        private final Vec3 target;
        private final Vec3 aimPoint;
        private final Vec3 origin;
        private final LaunchOrigin launchOrigin;
        private final float yaw;
        private final float losPitch;
        private final List<PitchCandidate> candidates;
        private final PathSafetyContext safety;
        private final boolean collectPath;
        private int nextCandidate;

        private ArcSearchState(GrenadeIntegration.BallisticProfile profile,
                               Vec3 target, Vec3 aimPoint, Vec3 origin,
                                LaunchOrigin launchOrigin, float yaw, float losPitch,
                                List<PitchCandidate> candidates,
                                PathSafetyContext safety,
                                boolean collectPath) {
            this.profile = profile;
            this.target = target;
            this.aimPoint = aimPoint;
            this.origin = origin;
            this.launchOrigin = launchOrigin;
            this.yaw = yaw;
            this.losPitch = losPitch;
            this.candidates = candidates;
            this.safety = safety;
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

    MinecraftServer getCalculationServer() {
        return soldier.level() instanceof ServerLevel level ? level.getServer() : null;
    }

    /** Runs one fresh, complete arc request on the server thread. */
    void processQueuedArcCalculation(boolean preparationReplan) {
        arcCalculationQueued = false;
        if (soldier.level().isClientSide || getCalculationServer() == null) {
            cancel("arc calculation server unavailable");
            return;
        }
        if (preparationReplan) {
            if (state != State.PREPARING || pendingPlan == null) return;
            if (finalThrowValidationQueued) {
                finalThrowValidationQueued = false;
                throwPendingGrenade();
                return;
            }
            replanDuringPreparation();
            return;
        }
        if (state != State.WAITING_FOR_ARC) return;

        state = State.IDLE;
        long gameTime = soldier.level().getGameTime();
        Decision decision = evaluateUncached(soldier.getTarget(), getCurrentSquadIntel(), gameTime);
        if (decision.plan() == null) {
            logDecision("queued arc calculation failed: " + decision.reason(),
                soldier.getTarget(), getCurrentSquadIntel());
            return;
        }
        beginPlan(decision.plan(), soldier.getTarget(), getCurrentSquadIntel(), gameTime);
    }

    private void queueArcCalculation(boolean preparationReplan) {
        if (arcCalculationQueued) return;
        if (GrenadeArcCalculationScheduler.request(this, preparationReplan)) {
            arcCalculationQueued = true;
        } else if (!preparationReplan) {
            state = State.IDLE;
        }
    }

    private void replanDuringPreparation() {
        if (preparationReplans >= 2 || pendingPlan == null) {
            cancel("preparation arc retry budget exhausted");
            return;
        }

        Plan previous = pendingPlan;
        state = State.IDLE;
        long gameTime = soldier.level().getGameTime();
        Decision decision = evaluateUncached(soldier.getTarget(), getCurrentSquadIntel(), gameTime);
        if (decision.plan() == null) {
            cancel("preparation replan failed: " + decision.reason());
            return;
        }

        Plan replanned = decision.plan();
        if (!java.util.Objects.equals(previous.squad(), replanned.squad())) {
            cancel("preparation replan changed squad reservation");
            return;
        }
        pendingPlan = replanned;
        pendingArc = replanned.arc();
        pendingProfile = replanned.profile();
        preparationReplans++;
        logDecision("preparation arc replanned attempt=" + preparationReplans,
            soldier.getTarget(), getCurrentSquadIntel());
        alignToPlan();
        state = State.PREPARING;
    }

    @Nullable
    private SquadThreatIntel getCurrentSquadIntel() {
        return soldier.getGrenadeSquadIntel();
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
        if (state != State.IDLE || !soldier.isAlive() || soldier.isHealing()
            || soldier.isPassenger() || !soldier.canUseGrenade(gameTime)
            || findGrenadeSlot() < 0) {
            return Integer.MIN_VALUE;
        }
        CandidateResolution resolution = resolveCandidate(target, intel);
        if (resolution.candidate() == null) return Integer.MIN_VALUE;
        Candidate candidate = resolution.candidate();
        double distance = soldier.position().distanceTo(candidate.targetPoint());
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > currentEffectiveMaxRange()) {
            return Integer.MIN_VALUE;
        }
        return candidate.score();
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

        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(
            soldier.getSoldierInventory().getItem(slot));
        if (!ballistic.available()) {
            return ForceThrowResult.failure("ballistic profile unavailable: " + ballistic.reason());
        }
        double maxRange = physicalMaxRange(ballistic.profile());
        double distance = soldier.distanceTo(target);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > maxRange) {
            return ForceThrowResult.failure(String.format(
                "target is %.1f blocks away; allowed range is %.1f-%.1f",
                distance, StevesArmyConfig.getGrenadeMinRange(), maxRange));
        }

        Vec3 targetPoint = grenadeTargetPoint(target);
        boolean protectedTarget = isProtectedTarget(target);
        Vec3 landing = preferredLanding(targetPoint, protectedTarget);
        Arc arc = findArc(targetPoint, landing, ballistic.profile(), protectedTarget);
        if (arc == null) {
            return ForceThrowResult.failure(
                "no practical grenade arc reaches the target zone; check terrain and target position");
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
        GrenadeIntegration.recordDiagnostic(throwResult, target, targetPoint, arc.predictedLanding(),
            arc.origin(), arc.flightTicks(), "SAFE_FORCE", formatCollision(arc.firstCollision()),
            soldier.level().getGameTime());
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
            if (arcCalculationQueued) return true;
            String invalidationReason = preparationInvalidationReason(target, intel);
            if (invalidationReason != null) {
                logDecision("preparation invalidated: " + invalidationReason, target, intel);
                if (preparationReplans < 1 && GrenadeArcCalculationScheduler.request(this, true)) {
                    arcCalculationQueued = true;
                    return true;
                }
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
            if (!finalThrowValidationQueued
                && GrenadeArcCalculationScheduler.request(this, true)) {
                finalThrowValidationQueued = true;
                arcCalculationQueued = true;
                return true;
            }
            return throwPendingGrenade();
        }

        if (state == State.WAITING_FOR_ARC) return true;
        long gameTime = soldier.level().getGameTime();
        if (!isEvaluationTick(gameTime)) return false;
        state = State.WAITING_FOR_ARC;
        queueArcCalculation(false);
        return true;
    }

    private void beginPlan(Plan plan, @Nullable LivingEntity target,
                           @Nullable SquadThreatIntel intel, long gameTime) {
        finalThrowValidationQueued = false;
        ArbitrationBlocker blocker = findArbitrationBlocker(plan);
        if (blocker != null) {
            logDecision("squad arbitration selected soldier " + blocker.soldierId()
                + " (score=" + blocker.score() + ") over this candidate", target, intel);
            state = State.IDLE;
            return;
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
                state = State.IDLE;
                return;
            }
            grenadeReservationHeld = true;
            logDecision("squad reservation acquired owner=" + soldier.getUUID()
                + " expires=" + reservation.expiresAtTick(), target, intel);
        }

        renderDebugTrajectory(plan.arc, plan.candidate.targetPoint, plan.planId());

        activePlanId = plan.planId();
        logDecision("plan=" + activePlanId + " grenade plan began slot=" + plan.slot
            + " count=" + plan.stack.getCount()
            + " source=" + plan.candidate.source + " " + formatArc(plan.arc, plan.profile), target, intel);

        pendingPlan = plan;
        pendingArc = plan.arc;
        pendingProfile = plan.profile;
        prepareTicks = PREPARE_TICKS;
        preparationReplans = 0;
        savedYaw = soldier.getYRot();
        savedPitch = soldier.getXRot();
        savedHeadYaw = soldier.getYHeadRot();
        savedBodyYaw = soldier.getCrawlFacingYaw();
        if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }
        alignToPlan();
        state = State.PREPARING;
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
            return new Decision(null, state == State.WAITING_FOR_ARC
                ? "arc calculation queued"
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

        ItemStack selectedStack = soldier.getSoldierInventory().getItem(slot);
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(selectedStack);
        if (!ballistic.available()) {
            return new Decision(null, "ballistic profile unavailable: " + ballistic.reason());
        }
        double maxRange = effectiveMaxRange(ballistic.profile());
        double distance = soldier.position().distanceTo(candidate.targetPoint);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > maxRange) {
            return new Decision(null, String.format(
                "target position is %.1f blocks away; allowed range is %.1f-%.1f",
                distance, StevesArmyConfig.getGrenadeMinRange(), maxRange));
        }
        Arc arc = findArc(candidate.targetPoint, candidate.landing, ballistic.profile(),
            candidate.preferHighArc);
        if (arc == null) {
            return new Decision(null, "no practical grenade arc reaches the target zone: " + noArcReason());
        }

        return new Decision(new Plan(selectedStack, slot, candidate, arc,
            ballistic.profile(), getSquadData(), nextPlanId++),
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
                    Vec3 targetPoint = grenadeTargetPoint(knowledge);
                    boolean preferHighArc = true;
                    return new CandidateResolution(new Candidate(target, target.getUUID(),
                        targetPoint, preferredLanding(targetPoint, preferHighArc), preferHighArc,
                        suppressionResponse ? TargetSource.SUPPRESSION_INTEL : TargetSource.THREAT_INTEL,
                        knowledge, targetProtected ? 3 : 2), "hidden target using squad intel");
                }
                Vec3 targetPoint = grenadeTargetPoint(target);
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
        Vec3 targetPoint = grenadeTargetPoint(threat);
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

        double maxRange = currentEffectiveMaxRange();

        // An assigned suppression source is the soldier's current hostile fire
        // contact. Do not silently redirect a suppressed soldier to a different
        // threat if that assignment has gone stale.
        SquadThreatIntel.ThreatKnowledge assigned = intel
            .getAssignedThreatForSoldier(soldier.getUUID()).orElse(null);
        if (assigned != null) {
            return isUsablePositionThreat(assigned, maxRange) ? assigned : null;
        }

        SquadThreatIntel.ThreatKnowledge best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
            if (!isUsablePositionThreat(threat, maxRange)) continue;
            long age = soldier.level().getGameTime() - threat.lastSeenTime;
            double distance = soldier.position().distanceTo(threatLanding(threat.lastKnownPosition));
            if (distance < StevesArmyConfig.getGrenadeMinRange()
                || distance > maxRange) continue;
            double score = threat.accuracy * 100.0 - age - distance * 0.01;
            if (isSuppressionResponse(intel, threat.threatEntityId)) score += 10.0;
            if (score > bestScore) {
                best = threat;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean isUsablePositionThreat(@Nullable SquadThreatIntel.ThreatKnowledge threat,
                                           double maxRange) {
        if (threat == null || !threat.isAlive || threat.lastKnownPosition == null) return false;
        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        if (age < 0 || age > 120 || threat.accuracy < StevesArmyConfig.getGrenadeMinThreatAccuracy()) {
            return false;
        }
        double distance = soldier.position().distanceTo(threatLanding(threat.lastKnownPosition));
        return distance >= StevesArmyConfig.getGrenadeMinRange()
            && distance <= maxRange;
    }

    private Vec3 threatLanding(@Nullable BlockPos position) {
        if (position == null) return soldier.position();
        return new Vec3(position.getX() + 0.5, position.getY() + 0.25, position.getZ() + 0.5);
    }

    private Vec3 grenadeTargetPoint(LivingEntity target) {
        return new Vec3(target.getX(), target.getEyeY(), target.getZ());
    }

    private Vec3 grenadeTargetPoint(SquadThreatIntel.ThreatKnowledge threat) {
        if (threat.lastVisibleHeadPoint != null) return threat.lastVisibleHeadPoint;
        if (threat.lastVisibleAimPoint != null) return threat.lastVisibleAimPoint;
        return threatLanding(threat.lastKnownPosition);
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
        GrenadeArcCalculationScheduler.cancel(this);
        arcCalculationQueued = false;
        releasePendingReservation(reason);
        if (state == State.PREPARING) restoreRotation();
        state = State.IDLE;
        pendingPlan = null;
        pendingArc = null;
        pendingProfile = null;
        prepareTicks = 0;
        finalThrowValidationQueued = false;
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
    private Arc validateSelectedArc(Vec3 targetPoint, Vec3 landing,
                                     GrenadeIntegration.BallisticProfile profile, Arc expectedArc,
                                     boolean requireTargetPlaneClearance) {
        ArcSearchState geometry = createArcSearchState(targetPoint, landing, profile, false);
        if (geometry == null) return null;
        PitchCandidate selected = new PitchCandidate(expectedArc.pitch(), expectedArc.pitchBranch());
        return simulateArc(geometry.target, geometry.aimPoint, geometry.origin,
            geometry.launchOrigin, geometry.yaw, geometry.losPitch, selected, profile,
            geometry.candidates.size(), geometry.safety, geometry.collectPath, requireTargetPlaneClearance);
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
            if (candidate.source == TargetSource.LIVE_ENTITY
                && grenadeTargetPoint(currentTarget).distanceTo(candidate.targetPoint) > 0.35) {
                return "live target position changed";
            }
            if (candidate.source == TargetSource.THREAT_INTEL
                || candidate.source == TargetSource.SUPPRESSION_INTEL) {
                SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId);
                if (current == null) return "threat intel became stale or unavailable";
                if (grenadeTargetPoint(current).distanceTo(candidate.targetPoint) > 0.35) {
                    return "threat position changed";
                }
            }
        } else {
            SquadThreatIntel.ThreatKnowledge current = getFreshThreat(intel, candidate.targetId);
            if (current == null) return "position-only threat intel became stale or unavailable";
            if (grenadeTargetPoint(current).distanceTo(candidate.targetPoint) > 0.35) {
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
        GrenadeIntegration.BallisticResult ballistic = GrenadeIntegration.inspectBallistics(current);
        if (!ballistic.available()) {
            return "ballistic profile unavailable: " + ballistic.reason();
        }
        double distance = soldier.position().distanceTo(candidate.targetPoint);
        if (distance < StevesArmyConfig.getGrenadeMinRange()
            || distance > effectiveMaxRange(ballistic.profile())) {
            return String.format("target moved out of range (%.1f blocks)", distance);
        }
        if (pendingProfile == null || !pendingProfile.equals(ballistic.profile())) {
            return "native profile changed during preparation";
        }
        // The complete trajectory validation is performed by the final throw
        // gate. Keeping this invalidation check state-only lets that gate queue
        // one fresh, serialized replan instead of doing an extra simulation.
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
            ballistic.profile(), pendingArc, plan.candidate.preferHighArc);
        if (finalArc == null) {
            String reason = "final trajectory no longer reaches a safe target zone";
            if (preparationReplans < 1 && GrenadeArcCalculationScheduler.request(this, true)) {
                arcCalculationQueued = true;
                logDecision("preparation replan queued: " + reason);
                return true;
            }
            logDecision("preparation invalidated: " + reason);
            cancel(reason);
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

        GrenadeIntegration.recordDiagnostic(throwResult, plan.candidate.entity,
            plan.candidate.targetPoint, finalArc.predictedLanding(), finalArc.origin(),
            finalArc.flightTicks(), "TACTICAL", formatCollision(finalArc.firstCollision()), gameTime);
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

    /** Physical max horizontal throw reach, ignoring the config cap. */
    private double physicalMaxRange(GrenadeIntegration.BallisticProfile profile) {
        double speed = profile.launchSpeed(false);
        if (profile.shouldBounce()) {
            // Bouncing grenades keep travelling for their full fuse, so reach
            // is set by drag-limited motion over the lifetime, not the arc.
            return speed * geometricSum(profile.airDrag(), profile.lifetime())
                * PHYSICAL_RANGE_DRAG_FACTOR;
        }
        return speed * speed
            / Math.max(0.01, profile.gravity()) * PHYSICAL_RANGE_DRAG_FACTOR;
    }

    /** Physical max horizontal throw range (v^2/g, drag-adjusted), capped by config. */
    private double effectiveMaxRange(GrenadeIntegration.BallisticProfile profile) {
        return Math.min(StevesArmyConfig.getGrenadeMaxRange(), physicalMaxRange(profile));
    }

    /** Effective max range for the grenade currently in the soldier's slot. */
    private double currentEffectiveMaxRange() {
        int slot = findGrenadeSlot();
        if (slot < 0) return StevesArmyConfig.getGrenadeMaxRange();
        GrenadeIntegration.BallisticResult result = GrenadeIntegration.inspectBallistics(
            soldier.getSoldierInventory().getItem(slot));
        return result.available()
            ? effectiveMaxRange(result.profile()) : StevesArmyConfig.getGrenadeMaxRange();
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
                && distanceToBox(landing, nearby.getBoundingBox()) <= safeRadius * safeRadius) {
                return false;
            }
        }
        return true;
    }

    private boolean isProtectedTarget(LivingEntity target) {
        Vec3 footPoint = target.position().add(0.0, 0.25, 0.0);
        return !TargetAcquisition.hasLineOfSight(soldier, target)
            || !TargetAcquisition.hasLineOfSightToPosition(soldier, footPoint);
    }

    private double distanceToBox(Vec3 point, AABB box) {
        double x = Mth.clamp(point.x, box.minX, box.maxX);
        double y = Mth.clamp(point.y, box.minY, box.maxY);
        double z = Mth.clamp(point.z, box.minZ, box.maxZ);
        return point.distanceToSqr(new Vec3(x, y, z));
    }

    private boolean isFriendly(LivingEntity entity) {
        if (entity == soldier) return true;
        return soldier.isFriendlyTo(entity) || entity.isAlliedTo(soldier) || soldier.isAlliedTo(entity);
    }

    private Arc findArc(Vec3 targetPoint, Vec3 landing,
                         GrenadeIntegration.BallisticProfile profile,
                         boolean preferHighArc) {
        return findArcVariant(targetPoint, landing, profile, preferHighArc);
    }

    private double correctElevationForDrag(double elevation, double horizontal, double vertical,
                                           GrenadeIntegration.BallisticProfile profile) {
        double speed = profile.launchSpeed(false);
        for (int iteration = 0; iteration < 3; iteration++) {
            double horizontalSpeed = speed * Math.cos(elevation);
            int ticks = estimateFlightTicks(horizontal, horizontalSpeed,
                profile.airDrag(), profile.lifetime());
            double positionSum = geometricSum(profile.airDrag(), ticks);
            double gravitySum = gravitySum(profile.airDrag(), ticks);
            if (positionSum <= 1.0e-6) break;
            double requiredHorizontalSpeed = horizontal / positionSum;
            double requiredVerticalSpeed = (vertical + profile.gravity() * gravitySum) / positionSum;
            double requiredElevation = Math.atan2(requiredVerticalSpeed, requiredHorizontalSpeed);
            elevation = elevation * 0.35 + requiredElevation * 0.65;
            elevation = Mth.clamp(elevation, Math.toRadians(10.0), Math.toRadians(80.0));
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

    @Nullable
    private Arc findArcVariant(Vec3 targetPoint, Vec3 landing,
                               GrenadeIntegration.BallisticProfile profile,
                               boolean preferHighArc) {
        ArcSearchState search = createArcSearchState(targetPoint, landing, profile, preferHighArc);
        if (search == null) return null;

        Arc bestArc = null;
        while (search.nextCandidate < search.candidates.size()) {
            PitchCandidate candidate = search.candidates.get(search.nextCandidate++);
            Arc arc = simulateArc(search.target, search.aimPoint, search.origin,
                search.launchOrigin, search.yaw, search.losPitch, candidate, search.profile,
                search.candidates.size(), search.safety, search.collectPath, preferHighArc);
            if (arc != null && (bestArc == null || arc.score() < bestArc.score())) {
                bestArc = arc;
            }
        }
        if (bestArc != null) return bestArc;
        lastArcRejectedAllForLanding = !lastArcSawFriendlyPathBlock
            && !lastArcSawThrowerCoverBlock
            && lastClosestTerminalError < Double.POSITIVE_INFINITY;
        return null;
    }

    @Nullable
    private ArcSearchState createArcSearchState(Vec3 targetPoint, Vec3 landing,
                                                  GrenadeIntegration.BallisticProfile profile,
                                                  boolean preferHighArc) {
        lastArcSawFriendlyPathBlock = false;
        lastArcSawThrowerCoverBlock = false;
        lastArcRejectedAllForLanding = false;
        lastArcBounceMode = profile.shouldBounce();
        lastArcSawPrematureImpact = false;
        lastClosestTerminalError = Double.POSITIVE_INFINITY;
        lastArcCandidateDescriptions = List.of();
        LaunchOrigin launchOrigin = resolveLaunchOrigin(landing);
        if (launchOrigin == null) {
            return null;
        }

        Vec3 origin = launchOrigin.position();
        Vec3 aimPoint = landing;
        double dx = aimPoint.x - origin.x;
        double dz = aimPoint.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) return null;

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float losPitch = (float) -Math.toDegrees(Math.atan2(targetPoint.y - origin.y,
            Math.sqrt((targetPoint.x - origin.x) * (targetPoint.x - origin.x)
                + (targetPoint.z - origin.z) * (targetPoint.z - origin.z))));
        List<PitchCandidate> candidates = generatePitchCandidates(
            horizontal, aimPoint.y - origin.y, profile,
            preferHighArc || launchOrigin.mode().equals("RAISED_ABOVE_COVER"));
        if (candidates.isEmpty()) return null;
        lastArcCandidateDescriptions = candidates.stream()
            .map(pitchCandidate -> String.format("%.1f/%s", pitchCandidate.pitch(), pitchCandidate.branch()))
            .toList();

        PathSafetyContext safety = createPathSafetyContext(origin, aimPoint, profile);
        return new ArcSearchState(profile, targetPoint, aimPoint, origin, launchOrigin,
            yaw, losPitch, candidates, safety, DiagnosticLogEnabled());
    }

    private List<PitchCandidate> generatePitchCandidates(double horizontal, double vertical,
                                                          GrenadeIntegration.BallisticProfile profile,
                                                          boolean preferHigh) {
        List<PitchCandidate> candidates = new ArrayList<>(MAX_ARC_CANDIDATES);
        double speed = profile.launchSpeed(false);
        double gravity = profile.gravity();
        if (!(speed > 0.0) || !Double.isFinite(speed)) return candidates;

        List<Double> analyticalElevations = new ArrayList<>(2);
        if (gravity < 1.0e-6) {
            analyticalElevations.add(Math.atan2(vertical, horizontal));
        } else {
            double speedSquared = speed * speed;
            double discriminant = speedSquared * speedSquared
                - gravity * (gravity * horizontal * horizontal + 2.0 * vertical * speedSquared);
            if (discriminant >= 0.0) {
                double root = Math.sqrt(discriminant);
                analyticalElevations.add(Math.atan((speedSquared - root) / (gravity * horizontal)));
                analyticalElevations.add(Math.atan((speedSquared + root) / (gravity * horizontal)));
            }
        }

        // Analytical (drag-corrected) elevations first, ordered high/low by
        // preference so the closest guess leads the candidate list.
        if (preferHigh && analyticalElevations.size() > 1) {
            Double low = analyticalElevations.get(0);
            analyticalElevations.set(0, analyticalElevations.get(1));
            analyticalElevations.set(1, low);
        }
        for (int index = 0; index < analyticalElevations.size(); index++) {
            double elevation = correctElevationForDrag(
                analyticalElevations.get(index), horizontal, vertical, profile);
            String branch = index == 0
                ? (preferHigh ? "HIGH" : "LOW")
                : (preferHigh ? "LOW" : "HIGH");
            addPitchCandidate(candidates, elevation, branch);
            addPitchCandidate(candidates, elevation + Math.toRadians(ARC_PITCH_CORRECTION_DEGREES), branch);
            addPitchCandidate(candidates, elevation - Math.toRadians(ARC_PITCH_CORRECTION_DEGREES), branch);
        }

        // Dense sweep over the usable throw elevations. Scoring, not exact
        // feasibility, decides the winner, so terrain-obstructed arcs still
        // have nearby pitches to try.
        double startElevation = preferHigh ? 75.0 : 15.0;
        double endElevation = preferHigh ? 15.0 : 75.0;
        double step = preferHigh ? -5.0 : 5.0;
        for (double elevation = startElevation;
             preferHigh ? elevation >= endElevation : elevation <= endElevation;
             elevation += step) {
            addPitchCandidate(candidates, Math.toRadians(elevation), "SWEEP");
        }
        return candidates;
    }

    private PathSafetyContext createPathSafetyContext(Vec3 origin, Vec3 aimPoint,
                                                       GrenadeIntegration.BallisticProfile profile) {
        double speed = profile.launchSpeed(false);
        double verticalReach = Math.max(8.0,
            speed * speed / Math.max(0.01, profile.gravity()) + 4.0);
        AABB search = new AABB(origin, aimPoint).inflate(4.0, verticalReach, 4.0);
        List<AABB> friendlies = new ArrayList<>();
        for (LivingEntity nearby : soldier.level().getEntitiesOfClass(LivingEntity.class, search)) {
            if (nearby != soldier && isFriendly(nearby)) {
                friendlies.add(nearby.getBoundingBox());
            }
        }
        return new PathSafetyContext(friendlies);
    }

    private void addPitchCandidate(List<PitchCandidate> candidates, double elevation, String branch) {
        if (!Double.isFinite(elevation)) return;
        float pitch = (float) Mth.clamp(-Math.toDegrees(elevation), -80.0, -10.0);
        for (PitchCandidate existing : candidates) {
            if (Math.abs(existing.pitch() - pitch) < 0.01f) return;
        }
        if (candidates.size() < MAX_ARC_CANDIDATES) {
            candidates.add(new PitchCandidate(pitch, branch));
        }
    }

    @Nullable
    private Arc simulateArc(Vec3 target, Vec3 aimPoint, Vec3 origin, LaunchOrigin launchOrigin,
                             float yaw, float losPitch, PitchCandidate candidate,
                             GrenadeIntegration.BallisticProfile profile, int candidateCount,
                             PathSafetyContext safety, boolean collectPath,
                             boolean requireTargetPlaneClearance) {
        float pitch = candidate.pitch();
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(yaw);
        double speed = profile.launchSpeed(false);
        Vec3 velocity = new Vec3(
            -Math.sin(yawRad) * Math.cos(pitchRad) * speed,
            -Math.sin(pitchRad) * speed,
            Math.cos(yawRad) * Math.cos(pitchRad) * speed);
        Vec3 initialVelocity = velocity;
        Vec3 position = origin;
        boolean safePath = true;
        boolean contactDetonated = false;
        double score = 0.0;
        Vec3 terminalPosition = null;
        CollisionInfo firstCollision = null;
        int bounceCount = 0;
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

                if (firstCollision == null) {
                    double targetDx = target.x - origin.x;
                    double targetDz = target.z - origin.z;
                    double targetHorizontal = Math.sqrt(targetDx * targetDx + targetDz * targetDz);
                    double collisionProjection = ((hit.x - origin.x) * targetDx
                        + (hit.z - origin.z) * targetDz) / targetHorizontal;
                    firstCollision = new CollisionInfo(hit, blockHit.getBlockPos(),
                        blockHit.getDirection(), bounce,
                        collisionProjection - targetHorizontal);
                }

                boolean detonatesOnContact = !profile.shouldBounce()
                    || (profile.brokeOnGround() && blockHit.getDirection() == Direction.UP);
                if (detonatesOnContact) {
                    // The native projectile detonates on contact (or ground
                    // break). The native explosion is centered at 80% of the
                    // way from the pre-move position to the contact point.
                    contactDetonated = true;
                    terminalPosition = start.lerp(hit, 0.8);
                    if (collectPath) path.add(terminalPosition);
                    terminal = true;
                    break;
                }

                if (bounce < 2) {
                    bounceCount++;
                    start = start.lerp(hit, 0.8);
                    Vec3 rest = end.subtract(start);
                    end = start.add(bounceVelocity(rest, blockHit.getDirection(),
                        profile.bounceFactor(), profile.gravity()));
                    velocity = bounceVelocity(velocity, blockHit.getDirection(),
                        profile.bounceFactor(), profile.gravity());
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
        if (contactDetonated && isPrematureImpact(terminalPosition, origin, target)) {
            lastArcSawPrematureImpact = true;
            score += PREMATURE_IMPACT_SCORE_PENALTY;
        }
        // A bouncing grenade's first contact is the ground short of the
        // target by design; the target-plane clearance rule only applies to
        // grenades that detonate on contact.
        if (!profile.shouldBounce() && requireTargetPlaneClearance && firstCollision != null
            && !firstCollision.clearedTargetPlane()) return null;
        double targetError = terminalPosition.distanceTo(target);
        lastClosestTerminalError = Math.min(lastClosestTerminalError, targetError);
        if (targetError > TARGET_ZONE_RADIUS || !isSafeLanding(terminalPosition)
            || distanceToBox(terminalPosition, soldier.getBoundingBox())
                < THROWER_ENDPOINT_CLEARANCE * THROWER_ENDPOINT_CLEARANCE) return null;
        // Prefer arcs whose first contact lands at or beyond the aim point
        // (behind the enemy's cover) over low shots that drop short and have
        // to bounce/roll the rest of the way. The first contact for a
        // bouncing grenade is the ground short of the target by design, so
        // this penalty biases selection toward higher arcs that clear cover.
        Vec3 dropPoint = firstCollision != null ? firstCollision.position() : terminalPosition;
        double aimDx = aimPoint.x - origin.x;
        double aimDz = aimPoint.z - origin.z;
        double aimDistance = Math.sqrt(aimDx * aimDx + aimDz * aimDz);
        double dropDx = dropPoint.x - origin.x;
        double dropDz = dropPoint.z - origin.z;
        double dropDistance = Math.sqrt(dropDx * dropDx + dropDz * dropDz);
        double dropShortfall = Math.max(0.0, aimDistance - dropDistance);
        score += targetError + 2.0 * dropShortfall;
        return new Arc(yaw, pitch, losPitch, terminalPosition,
            initialVelocity, path, profile.lifetime(), profile.shouldBounce(), origin,
            launchOrigin.mode(), launchOrigin.coverTopY(), launchOrigin.clearance(), aimPoint,
            targetError, score, candidateCount, bounceCount, candidate.branch(),
            firstCollision, List.copyOf(lastArcCandidateDescriptions));
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
        if (originY > maximumY + 1.0e-4) {
            // An inaccurate cover-height estimate should not disable grenade
            // use. The native eye-height origin remains a valid fallback.
            return new LaunchOrigin(eyeOrigin, "STANDING_EYE_FALLBACK", coverTopY, Double.NaN);
        }

        Vec3 horizontal = new Vec3(towardTarget.x, 0.0, towardTarget.z);
        if (horizontal.lengthSqr() < 1.0e-6) return null;
        Vec3 offset = horizontal.normalize().scale(0.2);
        Vec3 raisedOrigin = new Vec3(soldier.getX() + offset.x, originY, soldier.getZ() + offset.z);
        return new LaunchOrigin(raisedOrigin, "RAISED_ABOVE_COVER", coverTopY, originY - coverTopY);
    }

    private Vec3 bounceVelocity(Vec3 velocity, Direction direction, double bounceFactor,
                                 double gravity) {
        return switch (direction.getAxis()) {
            case X -> velocity.multiply(-bounceFactor / 1.5, bounceFactor, bounceFactor);
            case Y -> {
                Vec3 bounced = velocity.multiply(bounceFactor, -bounceFactor / 2.5, bounceFactor);
                if (bounced.y() < gravity) {
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

        // Contacts in the launch envelope while the soldier is in cover are
        // thrower-side obstructions, not valid target landings. Only the
        // cover block itself and its protected-direction neighbors count;
        // ordinary ground/wall contacts in the first ticks are not obstructions.
        CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
        if (cover == null) return false;
        BlockPos coverPos = cover.getPosition();
        if (hit.getBlockPos().equals(coverPos)) return true;
        for (Direction protectedDirection : cover.getProtectedDirections()) {
            if (hit.getBlockPos().equals(coverPos.relative(protectedDirection))) {
                return true;
            }
        }
        return false;
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
        return String.format("no practical arc reaches the target zone (bounce=%s,candidates=%s,"
                + "friendlyPath=%s,throwerCover=%s,prematureImpact=%s,allCandidatesFailedLanding=%s,closestTerminalError=%s)",
            lastArcBounceMode, candidates, lastArcSawFriendlyPathBlock,
            lastArcSawThrowerCoverBlock, lastArcSawPrematureImpact,
            lastArcRejectedAllForLanding, closest);
    }

    private String formatArc(Arc arc, GrenadeIntegration.BallisticProfile profile) {
        return String.format("strategy=FORGIVING_SCORE_SEARCH,candidates=%d,pitchCandidates=%s,branch=%s,yaw=%.1f,pitch=%.1f,losPitch=%.1f,originY=%.2f,aimPoint=%s,predictedLanding=%s,targetError=%.2f,score=%.2f,flightTicks=%d,bounce=%s,bounceCount=%d,firstCollision=%s,pose=%s,launchOrigin=%s,launchOriginMode=%s,coverTopY=%.2f,coverClearance=%.2f,configuredSpeed=%.3f,launchSpeed=%.3f,initialVelocity=%s,%s",
            arc.candidateCount, arc.candidateDescriptions, arc.pitchBranch, arc.yaw, arc.pitch, arc.losPitch, arc.origin.y,
            arc.aimPoint, arc.predictedLanding, arc.targetError, arc.score,
            arc.flightTicks,
            arc.bounced, arc.bounceCount, arc.firstCollision, soldier.getPose(), arc.origin, arc.originMode, arc.coverTopY, arc.coverClearance,
            profile.initialSpeed(), arc.initialVelocity.length(),
            arc.initialVelocity, profile.describe());
    }

    private String formatCollision(@Nullable CollisionInfo collision) {
        if (collision == null) return "none";
        return String.format("position=%s,block=%s,direction=%s,bounceIndex=%d,targetPlaneClearance=%.3f,clearedTargetPlane=%s",
            collision.position(), collision.block(), collision.direction(), collision.bounceIndex(),
            collision.targetPlaneClearance(), collision.clearedTargetPlane());
    }

    private record Arc(float yaw, float pitch, float losPitch, Vec3 predictedLanding,
                       Vec3 initialVelocity, List<Vec3> path, int flightTicks,
                       boolean bounced, Vec3 origin, String originMode,
                       double coverTopY, double coverClearance, Vec3 aimPoint,
                        double targetError, double score, int candidateCount, int bounceCount,
                        String pitchBranch, @Nullable CollisionInfo firstCollision,
                        List<String> candidateDescriptions) {}

    private record LaunchOrigin(Vec3 position, String mode, double coverTopY, double clearance) {}
}

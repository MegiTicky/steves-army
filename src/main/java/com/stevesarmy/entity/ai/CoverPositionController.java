package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.HazardBlockHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CoverPositionController extends MoveControl {

    private final SoldierSpeedController movementSpeed;

    public SoldierSpeedController getSpeedController() { return movementSpeed; }

    /** Vanilla consumes a transient resolved modifier; the raw goal request stays intact. */
    private void tickVanillaMovement() {
        // Tactical steering writes local sideways input. Vanilla MOVE_TO,
        // JUMPING and WAIT only replace forward input, so retaining that
        // strafe would rotate it with the route-facing yaw and bend the path.
        // A genuine vanilla STRAFE operation installs its own input below.
        this.mob.setXxa(0.0F);
        double raw = this.speedModifier;
        double absolute = movementSpeed.resolve(raw, this.operation != Operation.WAIT, false);
        if (this.operation == Operation.WAIT) this.mob.setSpeed(0.0F);
        double attribute = this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        this.speedModifier = attribute > 1.0e-6 ? absolute / attribute : 0;
        try {
            Operation consumedOperation = this.operation;
            super.tick();
            // Mob.setSpeed also writes zza=speed. Treating our absolute pace
            // as input strength applies it twice and makes Traverse crawl.
            // Keep vanilla steering/jumping, but use full forward input;
            // SoldierSpeedController still bounds actual SELF displacement.
            if ((consumedOperation == Operation.MOVE_TO || consumedOperation == Operation.JUMPING)
                && this.mob.zza > 0.0F) {
                this.mob.setZza(1.0F);
            }
        } finally {
            this.speedModifier = raw;
        }
    }

    public enum MovementResult {
        NONE,
        IN_PROGRESS,
        REACHED_TARGET,
        FAILED
    }

    public enum FailureReason {
        NONE,
        BLOCKED_PATH,
        HAZARD,
        NO_PROGRESS,
        RELOAD_INTERRUPTED
    }

    private Vec3 targetPos = Vec3.ZERO;
    private double tolerance = 0.5;
    private double targetSpeed = 0.3;
    private MovementResult lastResult = MovementResult.NONE;
    private FailureReason lastFailureReason = FailureReason.NONE;
    private int stuckTicks = 0;
    private Vec3 lastPos = Vec3.ZERO;
    private double bestDistanceToTarget = Double.POSITIVE_INFINITY;

    private String debugMoveSource = "none";
    private String debugMoveReason = "";

    /** Sim-harness telemetry: which subsystem produced the current movement (and why). */
    public String debugMoveSource() { return debugMoveSource; }
    public String debugMoveReason() { return debugMoveReason; }
    /** Current CQB caution reason from the steering advisor ("none" when inactive). */
    public String cautionReason() { return steeringAdvisor.getCautionReason(); }
    private Vec3 debugLastSetVelocity = Vec3.ZERO;
    // Sim-harness telemetry: how often the cover-anchor velocity actually engaged.
    private int debugAnchorTicks;
    private int debugAnchorPulls;
    private double debugLastAnchorDistance = -1;
    private int debugArrivals;
    private boolean controlledReturnToCover;
    private Vec3 coverAnchorTarget;
    private final CqbSteeringAdvisor steeringAdvisor = new CqbSteeringAdvisor();

    // Max steps for swept-box collision check
    private static final int COLLISION_SWEEP_STEPS = 8;
    private static final double COVER_ANCHOR_DEADZONE = 0.08;
    private static final double COVER_ANCHOR_MAX_SPEED = 0.18;
    private static final double COVER_ANCHOR_RESPONSE = 0.75;
    private static final double APPROACH_SLOWDOWN_DISTANCE = 0.35;
    private static final double MIN_APPROACH_SPEED_FACTOR = 0.20;
    private static final double TARGET_PROGRESS_EPSILON = 0.002;

    public CoverPositionController(Mob mob) {
        super(mob);
        this.movementSpeed = new SoldierSpeedController(mob);
    }

    public void moveTo(Vec3 pos, double tolerance, double speed) {
        moveTo(pos, tolerance, speed, "setTarget", "");
    }

    public void moveTo(Vec3 pos, double tolerance, double speed, String source, String reason) {
        if (SoldierFlightRecorder.isRecording()) {
            traceController("request", "moveTo", "target=" + pos + ",tolerance=" + tolerance + ",speed=" + speed
                + ",source=" + source + ",reason=" + reason);
        }
        this.coverAnchorTarget = null;
        if (this.mob instanceof SoldierEntity soldier && soldier.cqbHoldFreezeEffective()
            && !soldier.getMovementController().canBypassCqbHold()
            && !shouldDeferCqbHold()
            && !"PeekController".equals(source)) {
            this.mob.getNavigation().stop();
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setSpeed(0.0F);
            this.movementSpeed.stop();
            this.operation = Operation.WAIT;
            this.debugMoveSource = "cqb-engage";
            this.debugMoveReason = "visible close target";
            if (SoldierFlightRecorder.isRecording()) {
                traceController("rejected", "CQB movement hold", "source=" + source + ",reason=" + reason);
            }
            return;
        }
        if (isPreparingOrReloading()) {
            if (controlledReturnToCover || isCoverApproachDuringReload()
                || isFinishingCoverDockDuringReload()) {
                beginMove(pos, tolerance, speed, source, reason, controlledReturnToCover);
            } else {
                stopForReload();
                this.lastResult = MovementResult.FAILED;
                this.lastFailureReason = FailureReason.RELOAD_INTERRUPTED;
                if (SoldierFlightRecorder.isRecording()) {
                    traceController("rejected", "reload interrupted", "source=" + source + ",reason=" + reason);
                }
            }
            return;
        }
        beginMove(pos, tolerance, speed, source, reason, false);
    }

    public void returnToCoverDuringReload(Vec3 pos, double tolerance, double speed, String source, String reason) {
        beginMove(pos, tolerance, speed, source, reason, true);
    }

    public void reassertControlledReturn() {
        this.controlledReturnToCover = true;
    }

    /**
     * True while the emergency engagement hold should let movement run: an
     * approach is in flight and either about to reach protection (dock finish)
     * or the threat is not overrunning. A point-blank threat always outranks
     * finishing the dock. Shared by the latch path (SoldierEntity), the
     * per-tick freeze gate, and the moveTo veto so all three agree.
     */
    public boolean shouldDeferCqbHold() {
        if (!(this.mob instanceof SoldierEntity soldier) || !soldier.isCqbEngagementHold()) {
            return false;
        }
        net.minecraft.world.entity.LivingEntity threat = soldier.getTarget();
        if (threat != null && threat.isAlive()
            && soldier.distanceToSqr(threat) <= Mth.square(com.stevesarmy.StevesArmyConfig.getCqbOverrunRange())) {
            return false;
        }
        if (this.lastResult != MovementResult.IN_PROGRESS) {
            return false;
        }
        double finishSq = Mth.square(com.stevesarmy.StevesArmyConfig.getCqbDockFinishThreshold());
        return this.mob.distanceToSqr(this.targetPos.x, this.targetPos.y, this.targetPos.z) <= finishSq;
    }

    private void beginMove(Vec3 pos, double tolerance, double speed, String source, String reason,
                           boolean controlledReturn) {
        if (HazardBlockHelper.sweptPathCrossesHazard(this.mob, this.mob.position(), pos)) {
            boolean alreadyInside = HazardBlockHelper.boundingBoxOverlapsHazard(this.mob.level(), this.mob.getBoundingBox());
            if (!alreadyInside) {
                StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} moveTo to ({}, {}, {}) blocked by hazard",
                    ((net.minecraft.world.entity.LivingEntity)this.mob).getId(),
                    pos.x, pos.y, pos.z);
                failWithReason(FailureReason.HAZARD, false, pos, source, reason);
                return;
            }
        }

        // Let vanilla navigation handle vertical step-up/jump geometry. The
        // custom controller is only a final same-level alignment helper; a
        // horizontal sweep must not reject a legal half-cover landing path.
        double verticalDelta = Math.abs(pos.y - this.mob.getY());
        if (verticalDelta < 0.05D && sweptBoxCollidesWithSolid(pos)) {
            StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} moveTo to ({}, {}, {}) blocked by solid collision",
                ((net.minecraft.world.entity.LivingEntity)this.mob).getId(),
                pos.x, pos.y, pos.z);
            failWithReason(FailureReason.BLOCKED_PATH, false, pos, source, reason);
            return;
        }

        this.targetPos = pos;
        this.tolerance = tolerance;
        this.targetSpeed = speed;
        this.lastResult = MovementResult.IN_PROGRESS;
        this.lastFailureReason = FailureReason.NONE;
        this.stuckTicks = 0;
        this.lastPos = this.mob.position();
        this.bestDistanceToTarget = horizontalDistanceTo(pos);
        this.controlledReturnToCover = controlledReturn;

        this.setWantedPosition(pos.x, pos.y, pos.z, speed);

        this.debugMoveSource = source;
        this.debugMoveReason = reason;
        if (SoldierFlightRecorder.isRecording()) {
            traceController("begin", "movement started", "target=" + pos + ",tolerance=" + tolerance
                + ",speed=" + speed + ",source=" + source + ",reason=" + reason);
        }
    }

    /**
     * Sweeps the mob's bounding box from current position toward targetPos.
     * Returns true if any block collision shape is intersected along the path.
     * Uses a stepped horizontal sweep; does not check vertical collision at
     * the destination (the standing block is assumed to be solid ground).
     */
    private boolean sweptBoxCollidesWithSolid(Vec3 targetPos) {
        AABB bb = this.mob.getBoundingBox();
        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            return !this.mob.level().noCollision(this.mob, bb);
        }

        // Start at step 1: a tight current cell (soldier pressed against
        // geometry) must not reject the move — only collision along the swept
        // path counts. The dist<0.01 branch above still validates standing in
        // the current cell.
        for (int i = 1; i <= COLLISION_SWEEP_STEPS; i++) {
            double t = (double) i / COLLISION_SWEEP_STEPS;
            AABB sample = bb.move(dx * t, 0, dz * t);
            for (VoxelShape shape : this.mob.level().getBlockCollisions(this.mob, sample)) {
                if (!shape.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void failWithReason(FailureReason reason, boolean controlledReturn, Vec3 failedTarget,
                                String source, String requestReason) {
        if (SoldierFlightRecorder.isRecording()) {
            traceController("failure", reason.name(), "target=" + failedTarget + ",controlledReturn=" + controlledReturn
                + ",source=" + source + ",reason=" + requestReason);
        }
        this.movementSpeed.stop();
        this.lastResult = MovementResult.FAILED;
        this.lastFailureReason = reason;
        this.controlledReturnToCover = controlledReturn;
        this.operation = Operation.WAIT;
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setSpeed(0.0F);
        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        this.bestDistanceToTarget = Double.POSITIVE_INFINITY;
    }

    private void completeMove() {
        if (SoldierFlightRecorder.isRecording()) {
            traceController("arrival", "target reached", "target=" + targetPos + ",source=" + debugMoveSource
                + ",reason=" + debugMoveReason);
        }
        this.movementSpeed.stop();
        this.operation = MoveControl.Operation.WAIT;
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setSpeed(0.0F);
        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
        this.debugLastSetVelocity = Vec3.ZERO;
        this.lastResult = MovementResult.REACHED_TARGET;
        this.lastFailureReason = FailureReason.NONE;
        this.controlledReturnToCover = false;
        this.bestDistanceToTarget = Double.POSITIVE_INFINITY;
        this.debugArrivals++;
    }

    public FailureReason getLastFailureReason() {
        return lastFailureReason;
    }

    public MovementResult getLastResult() {
        return lastResult;
    }

    public void clear() {
        if (SoldierFlightRecorder.isRecording()) {
            traceController("clear", "controller cleared", "result=" + lastResult + ",target=" + targetPos
                + ",source=" + debugMoveSource + ",reason=" + debugMoveReason);
        }
        this.movementSpeed.stop();
        this.lastResult = MovementResult.NONE;
        this.lastFailureReason = FailureReason.NONE;
        this.controlledReturnToCover = false;
        this.coverAnchorTarget = null;
        this.bestDistanceToTarget = Double.POSITIVE_INFINITY;
        this.operation = MoveControl.Operation.WAIT;
        this.mob.getNavigation().stop();
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
    }

    /**
     * Drops a stale FAILED/REACHED verdict so a newly selected cover is not
     * judged by the previous cover's outcome. Unlike clear(), this leaves any
     * in-flight movement, navigation state, and the controlled duck-back alone.
     */
    public void resetFailureState() {
        if (this.controlledReturnToCover) {
            return;
        }
        this.lastResult = MovementResult.NONE;
        this.lastFailureReason = FailureReason.NONE;
    }

    public void stopForReload() {
        if (!controlledReturnToCover) {
            clear();
        }
    }

    private void traceController(String event, String reason, String detail) {
        if (this.mob instanceof SoldierEntity soldier && SoldierFlightRecorder.isRecording()) {
            SoldierFlightRecorder.event(soldier, "controller", event + ": " + reason, detail);
        }
    }

    public Vec3 getDebugTargetPos() {
        return targetPos;
    }

    public double getDebugTolerance() {
        return tolerance;
    }

    public String getDebugMoveSource() { return debugMoveSource; }
    public String getDebugMoveReason() { return debugMoveReason; }
    public Vec3 getDebugLastSetVelocity() { return debugLastSetVelocity; }

    /** Sim-harness telemetry: ticks the cover-anchor request was active this tick. */
    public int getDebugAnchorTicks() { return debugAnchorTicks; }

    /** Sim-harness telemetry: ticks the anchor actually nudged velocity toward the slot. */
    public int getDebugAnchorPulls() { return debugAnchorPulls; }

    /** Sim-harness telemetry: slot distance at the most recent anchored tick; -1 before the first. */
    public double getDebugLastAnchorDistance() { return debugLastAnchorDistance; }

    /** Sim-harness telemetry: completed move-target arrivals (REACHED_TARGET transitions). */
    public int getDebugArrivals() { return debugArrivals; }

    /** True when passive anchoring can hold the soldier without an explicit move request. */
    public boolean isWithinCoverAnchorDeadzone(Vec3 target) {
        return horizontalDistanceTo(target) <= COVER_ANCHOR_DEADZONE;
    }

    /**
     * Requests short-range position correction while the soldier is hiding in
     * its current cover. The request is consumed by tick() so the correction
     * happens after MoveControl has processed the normal movement command.
     * A reload suspends the anchor EXCEPT for a pending claim→dock alignment,
     * which finishes through the reload.
     */
    public void maintainCoverAnchor(Vec3 target) {
        if (!isPreparingOrReloading() || isFinishingCoverDockDuringReload()) {
            this.coverAnchorTarget = target;
        }
    }

    private void applyCoverAnchorVelocity(Vec3 target) {
        if (target == null) return;
        debugAnchorTicks++;

        double dx = target.x - this.mob.getX();
        double dz = target.z - this.mob.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        debugLastAnchorDistance = distance;
        if (distance < 0.001) return;

        double nx = dx / distance;
        double nz = dz / distance;
        Vec3 velocity = this.mob.getDeltaMovement();

        double desiredSpeed = distance <= COVER_ANCHOR_DEADZONE
            ? 0.0
            : Math.min(COVER_ANCHOR_MAX_SPEED,
                (distance - COVER_ANCHOR_DEADZONE) * COVER_ANCHOR_RESPONSE);
        Vec3 adjusted = movementSpeed.anchorVelocity(velocity, nx, nz, desiredSpeed,
            distance <= COVER_ANCHOR_DEADZONE);
        if (adjusted.distanceToSqr(velocity) > 1.0e-12) debugAnchorPulls++;

        this.mob.setDeltaMovement(adjusted.x, adjusted.y, adjusted.z);
    }

    @Override
    public void tick() {
        movementSpeed.beginTick();
        Vec3 anchorTarget = this.coverAnchorTarget;
        this.coverAnchorTarget = null;

        if (isPreparingOrReloading() && !controlledReturnToCover
            && !isCoverApproachDuringReload() && !isFinishingCoverDockDuringReload()) {
            clear();
            this.debugMoveSource = "reload";
            this.debugMoveReason = "movement held while reloading";
            this.debugLastSetVelocity = Vec3.ZERO;
            return;
        }

        if (this.mob instanceof SoldierEntity soldier && soldier.cqbHoldFreezeEffective()
            && !soldier.getMovementController().canBypassCqbHold()
            && !shouldDeferCqbHold()
            && !soldier.getPeekController().isMovingToPeek()
            && !soldier.getPeekController().isReturning()) {
            this.operation = Operation.WAIT;
            this.mob.getNavigation().stop();
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setSpeed(0.0F);
            this.movementSpeed.stop();
            this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
            this.debugLastSetVelocity = Vec3.ZERO;
            this.debugMoveSource = "cqb-engage";
            this.debugMoveReason = "visible close target";
            return;
        }

        // Doorway queueing runs BEFORE the movement-mode split: it must hold
        // every movement owner alike - cover approaches (controller moves),
        // GO_TO relocations, hold marches, and attack approaches. Caution
        // gating (manual CQB or enemy near path) lives in the check.
        if (this.mob instanceof SoldierEntity queueSoldier
            && !StevesArmyMod.teleportOnlyMode
            && tickDoorwayQueueHold(queueSoldier, anchorTarget)) {
            this.debugMoveSource = "cqb-caution";
            this.debugMoveReason = "doorway queue";
            return;
        }

        if (lastResult != MovementResult.IN_PROGRESS) {
            if (this.mob instanceof SoldierEntity tacticalSoldier
                && canUseTacticalSteering(tacticalSoldier)) {
                Vec3 routeTarget = steeringAdvisor.isCautionActive(tacticalSoldier)
                    ? steeringAdvisor.getSteerTarget(tacticalSoldier)
                    : tacticalSoldier.getNavigation().getPath().getNextEntityPos(tacticalSoldier);
                if (routeTarget != null && !sweptBoxCollidesWithSolid(routeTarget)) {
                    tickTacticalSteering(tacticalSoldier, routeTarget, anchorTarget);
                    return;
                }
            }
            if (tickCautionSteering(anchorTarget)) {
                return;
            }
            // Steering can bail on geometry; the shared speed policy still
            // resolves manual CQB/crawl constraints on the vanilla branch.
            boolean bailCautionActive = this.mob instanceof SoldierEntity bailSoldier
                && steeringAdvisor.isCautionActive(bailSoldier);
            float previousYaw = this.mob.getYRot();
            float previousBodyYaw = this.mob.yBodyRot;
            float previousHeadYaw = this.mob.getYHeadRot();
            tickVanillaMovement();
            traceMoveControlRotation("vanilla-navigation", previousYaw, previousBodyYaw, previousHeadYaw);
            applyCoverAnchorVelocity(anchorTarget);
            this.debugLastSetVelocity = this.mob.getDeltaMovement();
            this.debugMoveSource = "vanilla";
            this.debugMoveReason = bailCautionActive ? "navigation caution" : "navigation";
            return;
        }

        if (StevesArmyMod.teleportOnlyMode) {
            mob.moveTo(targetPos.x, targetPos.y, targetPos.z, mob.getYRot(), mob.getXRot());
            completeMove();
            return;
        }

        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        double distance = Math.sqrt(distSq);
        if (distance <= tolerance || previousSegmentReachedTarget()) {
            completeMove();
            return;
        }

        if (distance < bestDistanceToTarget - TARGET_PROGRESS_EPSILON) {
            bestDistanceToTarget = distance;
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks > 60) {
                failWithReason(FailureReason.NO_PROGRESS, false, targetPos, debugMoveSource, debugMoveReason);
                return;
            }
        }
        lastPos = this.mob.position();

        // Re-assert target position every tick so super.tick() doesn't reset to WAIT.
        // Slow before the arrival radius so vanilla's 90-degree turn cap cannot orbit it.
        double approachSpeed = getApproachSpeed(distance);
        boolean cautionClamped = false;
        if (this.mob instanceof SoldierEntity cautionSoldier
            && steeringAdvisor.isCautionActive(cautionSoldier)) {
            // Keep the caution label; the shared speed policy also handles
            // dock handoffs. Lateral steering would fight the final alignment.
            cautionClamped = true;
        }
        if (this.mob instanceof SoldierEntity peeker && peeker.getPeekController().isCornerPreAiming()) {
            // Movement owns the body frame; head/pitch remain on the remembered contact.
            float routeYaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            peeker.setYRot(this.rotlerp(peeker.getYRot(), routeYaw, 90));
            peeker.setYBodyRot(peeker.getYRot());
            double yaw = peeker.getYRot() * Math.PI / 180.0;
            peeker.setSpeed((float)movementSpeed.resolve(approachSpeed, true, false));
            peeker.setZza((float)((-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / distance));
            peeker.setXxa((float)((Math.cos(yaw) * dx + Math.sin(yaw) * dz) / distance));
            applyCoverAnchorVelocity(anchorTarget);
            this.debugLastSetVelocity = this.mob.getDeltaMovement();
            return;
        }
        this.setWantedPosition(targetPos.x, targetPos.y, targetPos.z, approachSpeed);
        this.debugMoveSource = "controller";
        this.debugMoveReason = cautionClamped ? "handoff caution" : "handoff";

        float previousYaw = this.mob.getYRot();
        float previousBodyYaw = this.mob.yBodyRot;
        float previousHeadYaw = this.mob.getYHeadRot();
        tickVanillaMovement();
        traceMoveControlRotation(debugMoveSource, previousYaw, previousBodyYaw, previousHeadYaw);
        applyCoverAnchorVelocity(anchorTarget);
        this.debugLastSetVelocity = this.mob.getDeltaMovement();
    }

    private double getApproachSpeed(double distance) {
        double slowdownDistance = Math.max(APPROACH_SLOWDOWN_DISTANCE, tolerance * 3.0D);
        double range = Math.max(0.001D, slowdownDistance - tolerance);
        double factor = Math.max(MIN_APPROACH_SPEED_FACTOR,
            Math.min(1.0D, (distance - tolerance) / range));
        return targetSpeed * factor;
    }

    private boolean previousSegmentReachedTarget() {
        Vec3 currentPos = this.mob.position();
        Vec3 segment = currentPos.subtract(lastPos);
        double lengthSqr = segment.x * segment.x + segment.z * segment.z;
        if (lengthSqr <= 0.000001D) {
            return false;
        }
        Vec3 fromStartToTarget = targetPos.subtract(lastPos);
        double projection = Math.max(0.0D, Math.min(1.0D,
            (fromStartToTarget.x * segment.x + fromStartToTarget.z * segment.z) / lengthSqr));
        double closestX = lastPos.x + segment.x * projection;
        double closestZ = lastPos.z + segment.z * projection;
        double dx = targetPos.x - closestX;
        double dz = targetPos.z - closestZ;
        return dx * dx + dz * dz <= tolerance * tolerance;
    }

    private double horizontalDistanceTo(Vec3 target) {
        double dx = target.x - this.mob.getX();
        double dz = target.z - this.mob.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean isCoverApproachDuringReload() {
        return this.mob instanceof SoldierEntity soldier
            && soldier.isMovingToUnoccupiedCoverDuringReload();
    }

    /**
     * True when a reload is holding a soldier off its already-claimed cover
     * slot: the cover is claimed but the claim→dock alignment (recenter +
     * anchor) has not finished. This transit finishes through the reload under
     * the same exception as the approach itself — otherwise a reload that
     * starts mid-alignment leaves the soldier crouched partially outside its
     * cover cell until the magazine is full.
     */
    public boolean isFinishingCoverDockDuringReload() {
        if (!(this.mob instanceof SoldierEntity soldier) || !soldier.isPreparingOrReloading()) {
            return false;
        }
        var current = soldier.getCoverBehaviorManager().getCurrentCover();
        if (current == null) {
            return false;
        }
        return !isWithinCoverAnchorDeadzone(
            CoverTacticalGoal.getCoverStandingPositionStatic(current.getPosition()));
    }

    private void traceMoveControlRotation(String source, float previousYaw, float previousBodyYaw,
                                          float previousHeadYaw) {
        if (this.mob instanceof SoldierEntity soldier
            && Math.abs(Mth.wrapDegrees(this.mob.getYRot() - previousYaw)) > 0.01F) {
            soldier.traceRotationWrite("move-control:" + source, previousYaw, previousBodyYaw, previousHeadYaw,
                "target=" + targetPos + ", speed=" + String.format("%.3f", this.speedModifier));
        }
    }

    /**
     * Doorway queueing (phase-4 CQB doctrine): hold before a wall-narrow
     * passage while a squadmate is still in it. Creep to a stop (never a hard
     * freeze) facing the passage, gun up. Only under CQB caution (manual CQB
     * toggle or an enemy near the remaining path). Returns true when it drove
     * the tick.
     */
    private boolean tickDoorwayQueueHold(SoldierEntity soldier, Vec3 anchorTarget) {
        if (!steeringAdvisor.isCautionActive(soldier)) {
            return false;
        }
        if (soldier.getNavigation().isDone()
            || !steeringAdvisor.isDoorwayBlocked(soldier)) {
            return false;
        }
        net.minecraft.world.level.pathfinder.Path holdPath = soldier.getNavigation().getPath();
        if (holdPath != null && !holdPath.isDone()) {
            Vec3 node = holdPath.getNextEntityPos(soldier);
            float faceYaw = (float) (Mth.atan2(node.z - this.mob.getZ(),
                node.x - this.mob.getX()) * (180.0F / (float) Math.PI)) - 90.0F;
            this.mob.setYRot(this.rotlerp(this.mob.getYRot(), faceYaw, 45.0F));
        }
        this.mob.setSpeed((float) movementSpeed.resolve(this.speedModifier, true, true));
        this.mob.setZza(1.0F);
        this.mob.setXxa(0.0F);
        applyCoverAnchorVelocity(anchorTarget);
        this.debugLastSetVelocity = this.mob.getDeltaMovement();
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverScoreLoggingEnabled()
            && soldier.tickCount % 40 == 0) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("[DoorwayQueue] S{} holding at ({}, {}) t{}",
                soldier.getId(), (int) this.mob.getX(), (int) this.mob.getZ(), soldier.tickCount);
        }
        return true;
    }

    /**
     * Cautious path following while an enemy is near the remaining path:
     * hugging the side of the path that keeps cover between soldier and enemy.
     * The shared speed controller handles pace independently of steering.
     * Returns false to let the caller follow the path normally.
     */
    private boolean tickCautionSteering(Vec3 anchorTarget) {
        if (!(this.mob instanceof SoldierEntity soldier)) {
            return false;
        }
        if (isPreparingOrReloading()) {
            return false;
        }
        if (!steeringAdvisor.isCautionActive(soldier)) {
            return false;
        }
        if (soldier.getNavigation().isDone()) {
            return false;
        }

        Vec3 steerTarget = steeringAdvisor.getSteerTarget(soldier);
        if (steerTarget == null) {
            return false;
        }

        double dx = steerTarget.x - this.mob.getX();
        double dz = steerTarget.z - this.mob.getZ();
        if (dx * dx + dz * dz < 0.01) {
            return false;
        }
        if (sweptBoxCollidesWithSolid(steerTarget)) {
            return false;
        }

        float previousYaw = this.mob.getYRot();
        float previousBodyYaw = this.mob.yBodyRot;
        float previousHeadYaw = this.mob.getYHeadRot();
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0F / (float) Math.PI)) - 90.0F;
        this.mob.setYRot(this.rotlerp(this.mob.getYRot(), targetYaw, 45.0F));
        soldier.traceRotationWrite("cqb-caution-steer", previousYaw, previousBodyYaw, previousHeadYaw,
            "steerTarget=" + steerTarget + ", targetYaw=" + String.format("%.1f", targetYaw)
                + ", reason=" + steeringAdvisor.getCautionReason());
        this.mob.setSpeed((float) movementSpeed.resolve(this.speedModifier, true, false));
        this.mob.setZza(1.0F);
        this.mob.setXxa(0.0F);

        applyCoverAnchorVelocity(anchorTarget);
        this.debugLastSetVelocity = this.mob.getDeltaMovement();
        this.debugMoveSource = "cqb-caution";
        this.debugMoveReason = steeringAdvisor.getCautionReason();
        return true;
    }

    private boolean isPreparingOrReloading() {
        return this.mob instanceof SoldierEntity soldier && soldier.isPreparingOrReloading();
    }

    /** Tactical travel keeps the body on its visible contact and translates the path vector into local input. */
    private boolean canUseTacticalSteering(SoldierEntity soldier) {
        if (soldier.getMovementController().mode() != SoldierMovementController.Mode.TACTICAL
            || soldier.getNavigation().isDone() || soldier.isNavigationTraversalLocked()
            || soldier.horizontalCollision || soldier.minorHorizontalCollision
            || !soldier.onGround() || soldier.getMovementController().isMandatoryCrawl() || soldier.isLowCrouching()
            || soldier.isFiringProne()
            || hasVerticalPathTransition(soldier) || isSharpPathTurn(soldier) || steeringAdvisor.isDoorwayBlocked(soldier)) {
            return false;
        }
        LivingEntity target = soldier.getTarget();
        return target != null && target.isAlive() && soldier.canAttack(target)
            && soldier.hasLineOfSight(target);
    }

    private boolean isSharpPathTurn(SoldierEntity soldier) {
        var path = soldier.getNavigation().getPath();
        if (path == null || path.isDone()) return false;
        int index = path.getNextNodeIndex();
        if (index < 0 || index + 1 >= path.getNodeCount()) return false;
        Vec3 current = soldier.position();
        Vec3 first = path.getNode(index).asBlockPos().getCenter().subtract(current);
        Vec3 second = path.getNode(index + 1).asBlockPos().getCenter()
            .subtract(path.getNode(index).asBlockPos().getCenter());
        first = new Vec3(first.x, 0, first.z);
        second = new Vec3(second.x, 0, second.z);
        return first.lengthSqr() > 0.01 && second.lengthSqr() > 0.01
            && first.normalize().dot(second.normalize()) < 0.5;
    }

    private void tickTacticalSteering(SoldierEntity soldier, Vec3 routeTarget, Vec3 anchorTarget) {
        LivingEntity target = soldier.getTarget();
        if (target == null) return;
        Vec3 direction = new Vec3(routeTarget.x - soldier.getX(), 0, routeTarget.z - soldier.getZ());
        Vec3 look = new Vec3(target.getX() - soldier.getX(), 0, target.getZ() - soldier.getZ());
        if (direction.lengthSqr() < 1.0e-6 || look.lengthSqr() < 1.0e-6) return;
        direction = direction.normalize();
        float aimYaw = (float) (Mth.atan2(look.z, look.x) * (180.0F / (float) Math.PI)) - 90.0F;
        float previousYaw = soldier.getYRot();
        float previousBodyYaw = soldier.yBodyRot;
        float previousHeadYaw = soldier.getYHeadRot();
        float bodyYaw = this.rotlerp(soldier.getYRot(), aimYaw, 30.0F);
        soldier.setYRot(bodyYaw);
        soldier.yBodyRot = bodyYaw;
        soldier.traceRotationWrite("tactical-movement", previousYaw, previousBodyYaw, previousHeadYaw,
            "route=" + routeTarget + ",target=" + target.getId());

        double radians = bodyYaw * (Math.PI / 180.0);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        double forward = direction.x * forwardX + direction.z * forwardZ;
        double strafe = direction.x * rightX + direction.z * rightZ;
        double length = Math.hypot(forward, strafe);
        if (length > 1.0) {
            forward /= length;
            strafe /= length;
        }
        double absolute = movementSpeed.resolve(this.speedModifier, true, false);
        soldier.setSpeed((float) absolute);
        soldier.setZza((float) forward);
        soldier.setXxa((float) strafe);
        applyCoverAnchorVelocity(anchorTarget);
        this.debugLastSetVelocity = soldier.getDeltaMovement();
        this.debugMoveSource = "tactical-steer";
        this.debugMoveReason = steeringAdvisor.isCautionActive(soldier)
            ? steeringAdvisor.getCautionReason() : "visible-contact";
    }

    public boolean requiresRouteFacing() {
        if (!(mob instanceof SoldierEntity soldier)) return false;
        if (soldier.isNavigationTraversalLocked() || lastResult == MovementResult.IN_PROGRESS
            || soldier.horizontalCollision || soldier.minorHorizontalCollision) return true;
        return !soldier.getNavigation().isDone()
            && (steeringAdvisor.isDoorwayBlocked(soldier) || hasVerticalPathTransition(soldier) || isSharpPathTurn(soldier));
    }

    private boolean hasVerticalPathTransition(SoldierEntity soldier) {
        var path = soldier.getNavigation().getPath();
        if (path == null || path.isDone()) return false;
        int index = path.getNextNodeIndex();
        if (index < 0 || index >= path.getNodeCount()) return false;
        int y = soldier.blockPosition().getY();
        return path.getNode(index).y != y
            || (index + 1 < path.getNodeCount() && path.getNode(index + 1).y != y);
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.util.HazardBlockHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CoverPositionController extends MoveControl {

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
    private Vec3 debugLastSetVelocity = Vec3.ZERO;
    private boolean controlledReturnToCover;
    private Vec3 coverAnchorTarget;
    private final CqbSteeringAdvisor steeringAdvisor = new CqbSteeringAdvisor();

    // Max steps for swept-box collision check
    private static final int COLLISION_SWEEP_STEPS = 8;
    private static final double COVER_ANCHOR_DEADZONE = 0.08;
    private static final double COVER_ANCHOR_MAX_SPEED = 0.18;
    private static final double COVER_ANCHOR_RESPONSE = 0.75;
    private static final double APPROACH_SLOWDOWN_DISTANCE = 0.35;
    private static final double MIN_APPROACH_SPEED_FACTOR = 0.08;
    private static final double TARGET_PROGRESS_EPSILON = 0.002;

    public CoverPositionController(Mob mob) {
        super(mob);
    }

    public void moveTo(Vec3 pos, double tolerance, double speed) {
        moveTo(pos, tolerance, speed, "setTarget", "");
    }

    public void moveTo(Vec3 pos, double tolerance, double speed, String source, String reason) {
        this.coverAnchorTarget = null;
        if (this.mob instanceof SoldierEntity soldier && soldier.isCqbEngagementHold()
            && !"PeekController".equals(source)) {
            this.mob.getNavigation().stop();
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setSpeed(0.0F);
            this.operation = Operation.WAIT;
            this.debugMoveSource = "cqb-engage";
            this.debugMoveReason = "visible close target";
            return;
        }
        if (isPreparingOrReloading()) {
            if (!controlledReturnToCover) {
                stopForReload();
                this.lastResult = MovementResult.FAILED;
                this.lastFailureReason = FailureReason.RELOAD_INTERRUPTED;
            }
            return;
        }
        beginMove(pos, tolerance, speed, source, reason, false);
    }

    public void returnToCoverDuringReload(Vec3 pos, double tolerance, double speed, String source, String reason) {
        beginMove(pos, tolerance, speed, source, reason, true);
    }

    private void beginMove(Vec3 pos, double tolerance, double speed, String source, String reason,
                           boolean controlledReturn) {
        if (HazardBlockHelper.sweptPathCrossesHazard(this.mob, this.mob.position(), pos)) {
            boolean alreadyInside = HazardBlockHelper.boundingBoxOverlapsHazard(this.mob.level(), this.mob.getBoundingBox());
            if (!alreadyInside) {
                StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} moveTo to ({}, {}, {}) blocked by hazard",
                    ((net.minecraft.world.entity.LivingEntity)this.mob).getId(),
                    pos.x, pos.y, pos.z);
                failWithReason(FailureReason.HAZARD, false);
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
            failWithReason(FailureReason.BLOCKED_PATH, false);
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

        for (int i = 0; i <= COLLISION_SWEEP_STEPS; i++) {
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

    private void failWithReason(FailureReason reason, boolean controlledReturn) {
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
    }

    public FailureReason getLastFailureReason() {
        return lastFailureReason;
    }

    public MovementResult getLastResult() {
        return lastResult;
    }

    public void clear() {
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

    public void stopForReload() {
        if (!controlledReturnToCover) {
            clear();
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

    /** True when passive anchoring can hold the soldier without an explicit move request. */
    public boolean isWithinCoverAnchorDeadzone(Vec3 target) {
        return horizontalDistanceTo(target) <= COVER_ANCHOR_DEADZONE;
    }

    /**
     * Requests short-range position correction while the soldier is hiding in
     * its current cover. The request is consumed by tick() so the correction
     * happens after MoveControl has processed the normal movement command.
     */
    public void maintainCoverAnchor(Vec3 target) {
        if (!isPreparingOrReloading()) {
            this.coverAnchorTarget = target;
        }
    }

    private void applyCoverAnchorVelocity(Vec3 target) {
        if (target == null) return;

        double dx = target.x - this.mob.getX();
        double dz = target.z - this.mob.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.001) return;

        double nx = dx / distance;
        double nz = dz / distance;
        Vec3 velocity = this.mob.getDeltaMovement();
        double towardSpeed = velocity.x * nx + velocity.z * nz;

        // Remove only radial velocity carrying the soldier away from cover.
        // Tangential movement remains available for normal collision handling.
        if (towardSpeed < 0.0) {
            velocity = velocity.subtract(new Vec3(nx, 0.0, nz).scale(towardSpeed));
            towardSpeed = 0.0;
        }

        double desiredSpeed = distance <= COVER_ANCHOR_DEADZONE
            ? 0.0
            : Math.min(COVER_ANCHOR_MAX_SPEED,
                (distance - COVER_ANCHOR_DEADZONE) * COVER_ANCHOR_RESPONSE);
        if (towardSpeed < desiredSpeed) {
            velocity = velocity.add(new Vec3(nx, 0.0, nz).scale(desiredSpeed - towardSpeed));
        } else if (distance <= COVER_ANCHOR_DEADZONE && towardSpeed > 0.0) {
            velocity = velocity.subtract(new Vec3(nx, 0.0, nz).scale(towardSpeed));
        }

        this.mob.setDeltaMovement(velocity.x, velocity.y, velocity.z);
    }

    @Override
    public void tick() {
        Vec3 anchorTarget = this.coverAnchorTarget;
        this.coverAnchorTarget = null;

        if (isPreparingOrReloading() && !controlledReturnToCover) {
            clear();
            this.debugMoveSource = "reload";
            this.debugMoveReason = "movement held while reloading";
            this.debugLastSetVelocity = Vec3.ZERO;
            return;
        }

        if (this.mob instanceof SoldierEntity soldier && soldier.isCqbEngagementHold()
            && !soldier.getPeekController().isMovingToPeek()
            && !soldier.getPeekController().isReturning()) {
            this.operation = Operation.WAIT;
            this.mob.getNavigation().stop();
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setSpeed(0.0F);
            this.mob.setDeltaMovement(0.0D, this.mob.getDeltaMovement().y, 0.0D);
            this.debugLastSetVelocity = Vec3.ZERO;
            this.debugMoveSource = "cqb-engage";
            this.debugMoveReason = "visible close target";
            return;
        }

        if (lastResult != MovementResult.IN_PROGRESS) {
            if (tickCautionSteering(anchorTarget)) {
                return;
            }
            float previousYaw = this.mob.getYRot();
            float previousBodyYaw = this.mob.yBodyRot;
            float previousHeadYaw = this.mob.getYHeadRot();
            super.tick();
            traceMoveControlRotation("vanilla-navigation", previousYaw, previousBodyYaw, previousHeadYaw);
            applyCoverAnchorVelocity(anchorTarget);
            this.debugLastSetVelocity = this.mob.getDeltaMovement();
            this.debugMoveSource = "vanilla";
            this.debugMoveReason = "navigation";
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
            if (stuckTicks > 40) {
                failWithReason(FailureReason.NO_PROGRESS, false);
                return;
            }
        }
        lastPos = this.mob.position();

        // Re-assert target position every tick so super.tick() doesn't reset to WAIT.
        // Slow before the arrival radius so vanilla's 90-degree turn cap cannot orbit it.
        this.setWantedPosition(targetPos.x, targetPos.y, targetPos.z, getApproachSpeed(distance));

        float previousYaw = this.mob.getYRot();
        float previousBodyYaw = this.mob.yBodyRot;
        float previousHeadYaw = this.mob.getYHeadRot();
        super.tick();
        traceMoveControlRotation(debugMoveSource, previousYaw, previousBodyYaw, previousHeadYaw);
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

    private void traceMoveControlRotation(String source, float previousYaw, float previousBodyYaw,
                                          float previousHeadYaw) {
        if (this.mob instanceof SoldierEntity soldier
            && Math.abs(Mth.wrapDegrees(this.mob.getYRot() - previousYaw)) > 0.01F) {
            soldier.traceRotationWrite("move-control:" + source, previousYaw, previousBodyYaw, previousHeadYaw,
                "target=" + targetPos + ", speed=" + String.format("%.3f", this.speedModifier));
        }
    }

    /**
     * Cautious path following while an enemy is near the remaining path:
     * slower speed and hugging the side of the path that keeps cover between
     * the soldier and the enemy (continuous slow-pie, never stopping).
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
        double cautionSpeed = steeringAdvisor.getCautionSpeed(this.speedModifier);
        this.mob.setSpeed((float) (cautionSpeed * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
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
}

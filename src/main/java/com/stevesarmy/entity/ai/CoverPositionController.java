package com.stevesarmy.entity.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

public class CoverPositionController extends MoveControl {

    public enum MovementIntent {
        NONE,
        NAVIGATING,
        POSITIONING,
        PEEKING,
        RETURNING
    }

    private MovementIntent intent = MovementIntent.NONE;
    private Vec3 targetPos = Vec3.ZERO;
    private double tolerance = 0.5;
    private double targetSpeed = 0.3;
    private Vec3 coverCenter = Vec3.ZERO;
    private Vec3 peekDirection = Vec3.ZERO;
    private double peekOffset = 0.0;
    private double maxPeekOffset = 1.0;

    // Debug tracking
    private String debugMoveSource = "none";
    private String debugMoveReason = "";
    private Vec3 debugLastSetVelocity = Vec3.ZERO;
    private int debugIntentTicks = 0;
    private MovementIntent debugPrevIntent = MovementIntent.NONE;

    public CoverPositionController(Mob mob) {
        super(mob);
    }

    public void setTarget(Vec3 pos, MovementIntent intent, double tolerance) {
        setTarget(pos, intent, tolerance, 0.3);
    }

    public void setTarget(Vec3 pos, MovementIntent intent, double tolerance, double speed) {
        this.targetPos = pos;
        this.intent = intent;
        this.tolerance = tolerance;
        this.targetSpeed = speed;
    }

    public void setTarget(Vec3 pos, MovementIntent intent, double tolerance, double speed, String source, String reason) {
        this.targetPos = pos;
        this.intent = intent;
        this.tolerance = tolerance;
        this.targetSpeed = speed;
        if (!source.equals(this.debugMoveSource)) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} intent={} target=({:.1f},{:.1f},{:.1f}) speed={} source={} reason={}",
                ((net.minecraft.world.entity.LivingEntity)this.mob).getId(),
                intent, pos.x, pos.y, pos.z, speed, source, reason);
        }
        this.debugMoveSource = source;
        this.debugMoveReason = reason;
    }

    public void startPeek(Vec3 coverCenter, Vec3 peekDirection, double maxPeekOffset) {
        this.coverCenter = coverCenter;
        this.peekDirection = peekDirection;
        this.peekOffset = 0.0;
        this.maxPeekOffset = maxPeekOffset;
        this.intent = MovementIntent.PEEKING;
    }

    public void startPeekAt(Vec3 targetPos) {
        this.targetPos = targetPos;
        this.intent = MovementIntent.PEEKING;
        com.stevesarmy.StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} startPeekAt {}",
            ((net.minecraft.world.entity.LivingEntity)this.mob).getId(), targetPos);
        this.debugMoveSource = "startPeekAt";
        this.debugMoveReason = "peek slide";
    }

    public void startReturn(Vec3 coverCenter) {
        this.coverCenter = coverCenter;
        this.intent = MovementIntent.RETURNING;
    }

    public void clearIntent() {
        this.intent = MovementIntent.NONE;
        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
    }

    public MovementIntent getIntent() {
        return intent;
    }

    public void setPeekDirection(Vec3 direction) {
        this.peekDirection = direction;
    }

    public double getPeekOffset() {
        return peekOffset;
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
    public int getDebugIntentTicks() { return debugIntentTicks; }

    @Override
    public void tick() {
        if (this.debugPrevIntent != this.intent) {
            this.debugIntentTicks = 0;
            this.debugPrevIntent = this.intent;
        } else {
            this.debugIntentTicks++;
        }

        switch (intent) {
            case NONE:
                if (!this.mob.getNavigation().isDone()) {
                    super.tick();
                    this.debugLastSetVelocity = this.mob.getDeltaMovement();
                    this.debugMoveSource = "vanilla";
                    this.debugMoveReason = "navigation";
                }
                break;
            case NAVIGATING:
                if (!this.mob.getNavigation().isDone()) {
                    super.tick();
                    this.debugLastSetVelocity = this.mob.getDeltaMovement();
                    this.debugMoveSource = "vanilla";
                    this.debugMoveReason = "navigation";
                }
                break;
            case POSITIONING:
                tickPositioning();
                break;
            case PEEKING:
                tickPeeking();
                break;
            case RETURNING:
                tickReturning();
                break;
        }
    }

    private void tickPositioning() {
        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < tolerance * tolerance) {
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            this.debugLastSetVelocity = Vec3.ZERO;
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = Math.min(targetSpeed, dist * 0.8);
        Vec3 vel = new Vec3((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
        this.mob.setDeltaMovement(vel);
        this.debugLastSetVelocity = vel;
    }

    private void tickPeeking() {
        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq <= 0.09) {
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            this.debugLastSetVelocity = Vec3.ZERO;
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = 0.15;
        Vec3 vel = new Vec3((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
        this.mob.setDeltaMovement(vel);
        this.debugLastSetVelocity = vel;
    }

    private void tickReturning() {
        double dx = coverCenter.x - this.mob.getX();
        double dz = coverCenter.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq <= tolerance * tolerance) {
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            this.debugLastSetVelocity = Vec3.ZERO;
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = Math.min(0.2, dist * 0.6);
        Vec3 vel = new Vec3((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
        this.mob.setDeltaMovement(vel);
        this.debugLastSetVelocity = vel;
    }
}
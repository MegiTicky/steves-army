package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

public class CoverPositionController extends MoveControl {

    public enum MovementResult {
        NONE,
        IN_PROGRESS,
        REACHED_TARGET,
        FAILED
    }

    private Vec3 targetPos = Vec3.ZERO;
    private double tolerance = 0.5;
    private double targetSpeed = 0.3;
    private MovementResult lastResult = MovementResult.NONE;
    private int stuckTicks = 0;
    private Vec3 lastPos = Vec3.ZERO;

    private String debugMoveSource = "none";
    private String debugMoveReason = "";
    private Vec3 debugLastSetVelocity = Vec3.ZERO;

    public CoverPositionController(Mob mob) {
        super(mob);
    }

    public void moveTo(Vec3 pos, double tolerance, double speed) {
        moveTo(pos, tolerance, speed, "setTarget", "");
    }

    public void moveTo(Vec3 pos, double tolerance, double speed, String source, String reason) {
        this.targetPos = pos;
        this.tolerance = tolerance;
        this.targetSpeed = speed;
        this.lastResult = MovementResult.IN_PROGRESS;
        this.stuckTicks = 0;

        this.setWantedPosition(pos.x, pos.y, pos.z, speed);

        if (!source.equals(this.debugMoveSource)) {
            StevesArmyMod.LOGGER.info("[MoveCtl] Soldier {} target=({}, {}, {}) speed={} source={} reason={}",
                ((net.minecraft.world.entity.LivingEntity)this.mob).getId(),
                pos.x, pos.y, pos.z, speed, source, reason);
        }
        this.debugMoveSource = source;
        this.debugMoveReason = reason;
    }

    public MovementResult getLastResult() {
        return lastResult;
    }

    public void clear() {
        this.lastResult = MovementResult.NONE;
        this.operation = MoveControl.Operation.WAIT;
        this.mob.getNavigation().stop();
        this.mob.setZza(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
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

    @Override
    public void tick() {
        if (lastResult != MovementResult.IN_PROGRESS) {
            super.tick();
            this.debugLastSetVelocity = this.mob.getDeltaMovement();
            this.debugMoveSource = "vanilla";
            this.debugMoveReason = "navigation";
            return;
        }

        if (StevesArmyMod.teleportOnlyMode) {
            mob.moveTo(targetPos.x, targetPos.y, targetPos.z, mob.getYRot(), mob.getXRot());
            lastResult = MovementResult.REACHED_TARGET;
            return;
        }

        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < tolerance * tolerance) {
            this.operation = MoveControl.Operation.WAIT;
            this.mob.setZza(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setSpeed(0.0F);
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            this.debugLastSetVelocity = Vec3.ZERO;
            lastResult = MovementResult.REACHED_TARGET;
            return;
        }

        double moved = this.mob.position().distanceToSqr(lastPos);
        lastPos = this.mob.position();
        if (moved < 0.0001) {
            stuckTicks++;
            if (stuckTicks > 40) {
                lastResult = MovementResult.FAILED;
                return;
            }
        } else {
            stuckTicks = 0;
        }

        // Re-assert target position every tick so super.tick() doesn't reset to WAIT
        this.setWantedPosition(targetPos.x, targetPos.y, targetPos.z, targetSpeed);

        super.tick();
        this.debugLastSetVelocity = this.mob.getDeltaMovement();
    }
}
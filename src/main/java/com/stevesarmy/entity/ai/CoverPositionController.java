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

    @Override
    public void tick() {
        switch (intent) {
            case NONE:
                super.tick();
                break;
            case NAVIGATING:
                super.tick();
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
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = Math.min(targetSpeed, dist * 0.8);
        this.mob.setDeltaMovement((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
    }

    private void tickPeeking() {
        double dx = targetPos.x - this.mob.getX();
        double dz = targetPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < 0.25) {
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            this.mob.setPos(targetPos.x, this.mob.getY(), targetPos.z);
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = 0.15;
        this.mob.setDeltaMovement((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
    }

    private void tickReturning() {
        double dx = coverCenter.x - this.mob.getX();
        double dz = coverCenter.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < tolerance * tolerance) {
            this.mob.setDeltaMovement(0, this.mob.getDeltaMovement().y, 0);
            intent = MovementIntent.NONE;
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = Math.min(0.2, dist * 0.6);
        this.mob.setDeltaMovement((dx / dist) * speed, this.mob.getDeltaMovement().y, (dz / dist) * speed);
    }
}
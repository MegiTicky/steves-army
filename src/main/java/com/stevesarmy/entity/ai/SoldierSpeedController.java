package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Resolves raw goal modifiers at consumption time. Never stores attribute compensation in navigation. */
public final class SoldierSpeedController {
    public record Telemetry(double requestedModifier, double targetSpeed, double appliedSpeed,
                            double displacement, String limitingReason) {}
    public static final double MAX_SPEED_INCREASE = 0.06;
    /** Normal vanilla ground sprint; fixed even when jumping or carrying speed buffs. */
    public static final double MAX_LOCOMOTION_SPEED = 0.28;
    private static final double CRAWL_FACTOR = 0.65;
    private static final double MANUAL_CQB_FACTOR = 0.24;
    private static final double ANCHOR_ACCELERATION = 0.04;
    private final Mob mob;
    private double manualRamp;
    private double requestedModifier;
    private double targetSpeed;
    private double appliedSpeed;
    private double displacement;
    private int movementBudgetTick = Integer.MIN_VALUE;
    private double locomotionDistance;
    private boolean externalImpulseThisTick;
    private int telemetryTick = Integer.MIN_VALUE;
    private double incomingSpeedThisTick;
    private double spentBudgetThisTick;
    private boolean impulseBypassThisTick;
    private boolean externalImpulsePending;
    private boolean externalMomentum;
    private Vec3 lastPosition;
    private String limitingReason = "stopped";
    private String previousReason = "";

    public SoldierSpeedController(Mob mob) { this.mob = mob; }

    public void beginTick() {
        // MoveControl can be invoked more than once during one entity tick
        // (for example when navigation and a cover owner both reassert a
        // target). Reopening the budget here would allow each invocation to
        // spend a full blocks-per-tick allowance.
        if (movementBudgetTick == mob.tickCount) {
            return;
        }
        Vec3 position = mob.position();
        displacement = lastPosition == null ? 0 : Math.hypot(position.x - lastPosition.x, position.z - lastPosition.z);
        lastPosition = position;
        movementBudgetTick = mob.tickCount;
        locomotionDistance = 0;
        externalImpulseThisTick = false;
    }

    /** Absolute movement-speed intent, not a velocity clamp or a measured blocks/tick value. */
    public double resolve(double request, boolean moving, boolean doorway) {
        requestedModifier = Double.isFinite(request) ? Math.max(0, request) : 0;
        double attribute = Math.max(0, mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
        var instance = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = instance == null ? attribute : Math.max(0, instance.getBaseValue());
        SoldierEntity soldier = mob instanceof SoldierEntity s ? s : null;
        boolean manual = soldier != null && soldier.isCQB();
        if (!manual) manualRamp = 0;
        double modeSpeed = soldier != null
            ? soldier.getMovementController().resolveModeSpeed(requestedModifier, attribute)
            : requestedModifier * attribute;
        targetSpeed = moving ? modeSpeed : 0;
        limitingReason = moving ? "requested" : "stopped";
        if (moving && soldier != null) {
            if (soldier.getMovementController().mode() == SoldierMovementController.Mode.TACTICAL) {
                limitingReason = "tactical-ads";
            } else if (requestedModifier * GunIntegration.getMovementProfile(soldier).adsNeutralAttribute()
                > MAX_LOCOMOTION_SPEED) {
                limitingReason = "sprint-ceiling";
            }
        }
        boolean mandatoryCrawl = soldier != null && soldier.getMovementController().isMandatoryCrawl();
        boolean crawl = soldier != null && (mandatoryCrawl
            || (!soldier.isFiringProne() && soldier.getTravelCrawlController().isCrawlActive()));
        if (crawl) {
            double crawlLimit = CRAWL_FACTOR * base;
            if (targetSpeed > crawlLimit) {
                targetSpeed = crawlLimit;
                limitingReason = mandatoryCrawl ? "mandatory-crawl" : "crawl";
            }
        }
        if (manual && moving) {
            manualRamp = Math.min(MANUAL_CQB_FACTOR, manualRamp + 0.02);
            if (targetSpeed > manualRamp * base) {
                targetSpeed = manualRamp * base;
                limitingReason = "manual-cqb";
            }
        }
        if (doorway && targetSpeed > 0.03 * attribute) {
            targetSpeed = 0.03 * attribute;
            limitingReason = "doorway";
        }
        if (targetSpeed > MAX_LOCOMOTION_SPEED) {
            targetSpeed = MAX_LOCOMOTION_SPEED;
            limitingReason = "sprint-ceiling";
        }
        // Pinned soldiers escaping toward protection retain an immediate dash.
        boolean emergencyDash = soldier != null && !crawl && !manual && !doorway
            && !soldier.isLowCrouching()
            && soldier.getCoverBehaviorManager().getSuppressionTracker().isPinned()
            && soldier.getCoverBehaviorManager().getCurrentCover() == null
            && soldier.getCoverBehaviorManager().getTargetCover() != null;
        if (!emergencyDash && targetSpeed > appliedSpeed + MAX_SPEED_INCREASE) {
            appliedSpeed += MAX_SPEED_INCREASE;
            limitingReason += "+acceleration";
        } else {
            appliedSpeed = targetSpeed; // slowing and stopping take effect immediately
        }
        if (DiagnosticLogManager.isCoverLoggingEnabled()
            && (!limitingReason.equals(previousReason) || mob.tickCount % 40 == 0)) {
            StevesArmyMod.LOGGER.info("[MovementSpeed] S{} request={} attr={} target={} applied={} moved={} limit={}",
                mob.getId(), requestedModifier, attribute, targetSpeed, appliedSpeed, displacement, limitingReason);
        }
        previousReason = limitingReason;
        return appliedSpeed;
    }

    public void stop() {
        requestedModifier = targetSpeed = appliedSpeed = 0;
        limitingReason = "stopped";
    }

    /**
     * Only budget the anchor's correction. Existing excess momentum is never globally clipped;
     * vertical motion is untouched. Radial stabilization retains the dock's existing hold policy.
     */
    public Vec3 anchorVelocity(Vec3 velocity, double nx, double nz, double desired, boolean insideDeadzone) {
        double incoming = velocity.horizontalDistance();
        double toward = velocity.x * nx + velocity.z * nz;
        if (toward < 0 || (insideDeadzone && toward > 0)) {
            velocity = velocity.subtract(nx * toward, 0, nz * toward);
            toward = 0;
        }
        double tangentSq = Math.max(0, velocity.horizontalDistanceSqr() - toward * toward);
        double correctionBudget = appliedSpeed > 0 ? Math.min(0.18, appliedSpeed) : 0.18;
        double budget = Math.max(incoming, correctionBudget);
        double radialRoom = Math.sqrt(Math.max(0, budget * budget - tangentSq));
        double bounded = Math.min(desired, Math.min(radialRoom, toward + ANCHOR_ACCELERATION));
        if (toward < bounded) velocity = velocity.add(nx * (bounded - toward), 0, nz * (bounded - toward));
        return velocity;
    }

    /** Bound controller-owned self movement without clamping external impulses. */
    public Vec3 limitSelfMovement(Vec3 requested, Vec3 incomingVelocity) {
        if (telemetryTick != mob.tickCount) {
            telemetryTick = mob.tickCount;
            incomingSpeedThisTick = 0;
            spentBudgetThisTick = 0;
            impulseBypassThisTick = false;
        }
        incomingSpeedThisTick = Math.max(incomingSpeedThisTick, incomingVelocity.horizontalDistance());
        if (movementBudgetTick != mob.tickCount) {
            movementBudgetTick = mob.tickCount;
            locomotionDistance = 0;
            externalImpulseThisTick = false;
            spentBudgetThisTick = 0;
            impulseBypassThisTick = false;
        }
        // Zero intent still permits friction/anchor settling, but never an unlimited coast.
        double budget = appliedSpeed > 1.0e-6
            ? Math.min(MAX_LOCOMOTION_SPEED, appliedSpeed)
            : Math.min(MAX_LOCOMOTION_SPEED, mob instanceof SoldierEntity soldier
                ? soldier.getMovementController().coastingCeiling() : MAX_LOCOMOTION_SPEED);
        if (requested.horizontalDistanceSqr() <= 1.0e-10) {
            return requested;
        }
        if (externalImpulseThisTick) return preserveImpulseMovement(requested);
        // Do not lower the impulse threshold when a controller slows down. Ordinary
        // carried walking velocity is not knockback just because the new intent is low.
        if (externalImpulsePending || (externalMomentum && incomingVelocity.horizontalDistance() > MAX_LOCOMOTION_SPEED)
            || incomingVelocity.horizontalDistance() > MAX_LOCOMOTION_SPEED * 1.5 + 0.08) {
            externalImpulsePending = false;
            externalImpulseThisTick = true;
            impulseBypassThisTick = true;
            return preserveImpulseMovement(requested);
        }
        double remaining = Math.max(0, budget - locomotionDistance);
        double horizontal = requested.horizontalDistance();
        if (horizontal <= remaining + 1.0e-6) {
            locomotionDistance += horizontal;
            spentBudgetThisTick = locomotionDistance;
            return requested;
        }
        double scale = remaining / horizontal;
        locomotionDistance = budget;
        spentBudgetThisTick = locomotionDistance;
        return new Vec3(requested.x * scale, requested.y, requested.z * scale);
    }

    /** Explicit knockback provenance also preserves impulses below the fallback threshold. */
    public void markExternalImpulse() { externalImpulsePending = true; }

    private Vec3 preserveImpulseMovement(Vec3 requested) {
        // Preserve the incoming impulse, but do not let steering accelerate it
        // indefinitely and keep the exemption alive after knockback should decay.
        double allowed = Math.max(MAX_LOCOMOTION_SPEED, incomingSpeedThisTick);
        double horizontal = requested.horizontalDistance();
        if (horizontal <= allowed) return requested;
        double scale = allowed / horizontal;
        return new Vec3(requested.x * scale, requested.y, requested.z * scale);
    }

    /**
     * travel() retains its accelerated velocity even when move() clips displacement.
     * Bound that ordinary carry-over so high attributes cannot manufacture a false
     * external impulse next tick. Actual impulses keep their momentum until it settles.
     */
    public Vec3 finishTravel(Vec3 velocity) {
        if (telemetryTick != mob.tickCount) return velocity;
        double horizontal = velocity.horizontalDistance();
        externalMomentum = impulseBypassThisTick && horizontal > MAX_LOCOMOTION_SPEED;
        if (impulseBypassThisTick || externalImpulsePending || horizontal <= MAX_LOCOMOTION_SPEED) return velocity;
        double scale = MAX_LOCOMOTION_SPEED / horizontal;
        return new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
    }

    public double requestedModifier() { return requestedModifier; }
    public double targetSpeed() { return targetSpeed; }
    public double appliedSpeed() { return appliedSpeed; }
    public double displacement() { return displacement; }
    public String limitingReason() { return limitingReason; }
    public double incomingSpeed() { return telemetryTick == mob.tickCount ? incomingSpeedThisTick : 0; }
    public double spentBudget() { return telemetryTick == mob.tickCount ? spentBudgetThisTick : 0; }
    public boolean impulseBypass() { return telemetryTick == mob.tickCount && impulseBypassThisTick; }
    public Telemetry snapshot() {
        return new Telemetry(requestedModifier, targetSpeed, appliedSpeed, displacement, limitingReason);
    }
}

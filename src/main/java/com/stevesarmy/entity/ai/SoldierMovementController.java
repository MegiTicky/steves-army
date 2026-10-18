package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.GameClock;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/** Chooses a bounded Traverse/Tactical phase while leaving route ownership with existing goals. */
public final class SoldierMovementController {
    public enum Mode { TRAVERSE, TACTICAL }
    public enum FireMode { NONE, HIP, ADS }

    public record Snapshot(Mode mode, Mode preferredMode, FireMode fireMode, int phaseTicksRemaining,
                           int phaseDurationTicks, int phaseCounter, double baseShare,
                           double distanceAdjustment, double routeAdjustment, double tacticalShare,
                           double nearestVisibleHostile, double routeDistance, boolean mandatoryCrawl,
                           String postureReason, String selectedTargetUuid, boolean selectedTargetVisible,
                           String facingOwner,
                           double adsNeutralAttribute, double adsRatio, boolean movementProfileAvailable,
                           String transitionReason) {}

    private static final int CONTEXT_REFRESH_TICKS = 10;
    private static final int MIN_PHASE_TICKS = 20;
    private static final int MIN_CYCLE_TICKS = 72;
    private static final int MAX_CYCLE_TICKS = 88;
    private static final int TARGET_MEMORY_RESET_TICKS = 40;
    private final SoldierEntity soldier;
    private Mode mode = Mode.TRAVERSE;
    private FireMode fireMode = FireMode.NONE;
    private int nextContextTick;
    private int phaseCounter;
    private int phaseTicksRemaining;
    private int phaseDurationTicks;
    private int tacticalPhaseTicks = 36;
    private int traversePhaseTicks = 36;
    private int phaseIndex;
    private Mode preferredMode = Mode.TRAVERSE;
    private int ticksWithoutTarget;
    private int ticksSinceEvent;
    private double tacticalShare = 0.4;
    private double baseShare = 0.4;
    private double distanceAdjustment;
    private double routeAdjustment;
    private double nearestVisibleHostile = Double.POSITIVE_INFINITY;
    private double routeDistance = Double.POSITIVE_INFINITY;
    private boolean visibleCombatTarget;
    private boolean mandatoryCrawl;
    private String mandatoryCrawlReason = "none";
    private String transitionReason = "initial";
    @Nullable private String selectedTargetUuid;
    private String eventKey = "";
    private String lastCloseEventKey = "";
    private String lastCoverEventKey = "";
    private long eventOwnerGeneration = Long.MIN_VALUE;
    private int fireConstraintUntil;
    @Nullable private Mode pendingEventMode;
    private double adsNeutralAttribute;
    private double adsRatio = 1.0;
    private boolean movementProfileAvailable;
    private boolean selectedTargetVisible;
    private int overrideStartTick = -1;
    private boolean overrideResetApplied;
    private long lastOwnerGeneration = Long.MIN_VALUE;

    public SoldierMovementController(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    /** Called exactly once before PathfinderMob.tick() consumes goals and movement. */
    public void beginTick() {
        if (soldier.level().isClientSide) return;
        long ownerGeneration = ownerGeneration();
        boolean ownerChanged = ownerGeneration != lastOwnerGeneration;
        if (ownerChanged) {
            lastOwnerGeneration = ownerGeneration;
            resetEventLatch();
            pendingEventMode = null;
            phaseTicksRemaining = 0;
            phaseDurationTicks = 0;
            phaseIndex = 0;
            preferredMode = Mode.TRAVERSE;
            nextContextTick = soldier.tickCount;
            fireConstraintUntil = soldier.tickCount + 1;
            setMode(Mode.TRAVERSE, "movement-owner-changed");
        }
        if (soldier.tickCount >= nextContextTick) {
            nextContextTick = soldier.tickCount + CONTEXT_REFRESH_TICKS;
            refreshContext();
        }

        // A new order gets one route-facing tick before choosing a combat phase.
        if (ownerChanged) {
            updateFireMode();
            return;
        }

        if (!visibleCombatTarget) {
            ticksWithoutTarget++;
            if (mode != Mode.TRAVERSE) setMode(Mode.TRAVERSE, "no-visible-authorized-target");
            preferredMode = Mode.TRAVERSE;
            phaseDurationTicks = phaseTicksRemaining = 0;
            pendingEventMode = null;
            if (ticksWithoutTarget >= TARGET_MEMORY_RESET_TICKS) resetEventLatch();
        } else {
            ticksWithoutTarget = 0;
        }
        boolean pinnedStandingEscape = isPinnedStandingEscape();
        if (pinnedStandingEscape) setMode(Mode.TRAVERSE, "pinned-standing-escape");

        if (isHardOverride()) {
            if (overrideStartTick < 0) overrideStartTick = soldier.tickCount;
            if (!overrideResetApplied
                && soldier.tickCount - overrideStartTick >= TARGET_MEMORY_RESET_TICKS) {
                resetEventLatch();
                phaseTicksRemaining = 0;
                phaseDurationTicks = 0;
                phaseIndex = 0;
                if (visibleCombatTarget) beginNextPhase();
                else setMode(Mode.TRAVERSE, "long-override-reset");
                overrideResetApplied = true;
            }
            updateFireMode();
            return;
        }
        overrideStartTick = -1;
        overrideResetApplied = false;

        if (pinnedStandingEscape) {
            updateFireMode();
            return;
        }

        if (visibleCombatTarget) {
            ticksSinceEvent++;
            applyMeaningfulEvent();
            if (pendingEventMode != null && phaseDurationTicks - phaseTicksRemaining >= MIN_PHASE_TICKS) {
                preferredMode = pendingEventMode;
                phaseIndex = 0;
                phaseDurationTicks = preferredMode == Mode.TACTICAL ? tacticalPhaseTicks : traversePhaseTicks;
                phaseTicksRemaining = phaseDurationTicks;
                setMode(preferredMode, transitionReason);
                pendingEventMode = null;
                updateFireMode();
                return;
            }
        }

        if (phaseTicksRemaining > 0) phaseTicksRemaining--;
        if (visibleCombatTarget && phaseTicksRemaining <= 0) advancePhase();
        updateFireMode();
    }

    private void refreshContext() {
        LivingEntity selected = soldier.getTarget();
        selectedTargetUuid = selected == null ? null : selected.getUUID().toString();
        boolean selectedVisible = selected != null && selected.isAlive()
            && soldier.canAttack(selected) && soldier.hasLineOfSight(selected);
        selectedTargetVisible = selectedVisible;
        double nearest = selectedVisible ? soldier.distanceTo(selected) : Double.POSITIVE_INFINITY;
        long now = GameClock.nowMs();
        for (var info : soldier.getThreatAwareness().getThreatInfos()) {
            LivingEntity entity = info.entity;
            if (entity == null || !entity.isAlive() || !info.wasObservedWithin(now, 120L * 50L)
                || !soldier.canAttack(entity) || !soldier.hasLineOfSight(entity)) continue;
            nearest = Math.min(nearest, soldier.distanceTo(entity));
        }
        nearestVisibleHostile = nearest;
        visibleCombatTarget = Double.isFinite(nearest);
        baseShare = baseShare();
        distanceAdjustment = distanceShare(nearestVisibleHostile);
        routeDistance = remainingRouteDistance();
        routeAdjustment = routeShare(routeDistance);
        tacticalShare = baseShare + distanceAdjustment + routeAdjustment;
        tacticalShare = Mth.clamp(tacticalShare, 0.17, 0.8);
        GunIntegration.MovementProfile profile = GunIntegration.getMovementProfile(soldier);
        adsNeutralAttribute = profile.adsNeutralAttribute();
        adsRatio = profile.adsRatio();
        movementProfileAvailable = profile.available();
    }

    private double baseShare() {
        if (soldier.hasValidPingMoveTarget() || soldier.hasGoToNavigationOwnership()) return 0.20;
        if (soldier.hasValidAttackTarget()) return 0.55;
        if (soldier.getSquadMode() == SquadMode.FOLLOW) return 0.30;
        return 0.40;
    }

    private static double distanceShare(double distance) {
        if (!Double.isFinite(distance)) return -0.15;
        if (distance <= 8.0) return 0.25;
        if (distance <= 16.0) return 0.25 + (distance - 8.0) * (-0.15 / 8.0);
        if (distance <= 32.0) return 0.10 + (distance - 16.0) * (-0.25 / 16.0);
        return -0.15;
    }

    private double routeShare(double remainingDistance) {
        if (!Double.isFinite(remainingDistance)) return 0.0;
        if (hasShortProtectiveCoverCommit()) {
            if (remainingDistance <= 10.0) return -0.20;
            if (remainingDistance < 16.0) return -0.20 * (16.0 - remainingDistance) / 6.0;
            return 0.0;
        }
        return remainingDistance <= 16.0 ? 0.0
            : -0.15 * Math.min(1.0, (remainingDistance - 16.0) / 16.0);
    }

    private double remainingRouteDistance() {
        var path = soldier.getNavigation().getPath();
        if (path != null && !path.isDone() && path.getNodeCount() > 0) {
            Vec3 previous = soldier.position();
            double total = 0.0;
            for (int i = Math.max(0, path.getNextNodeIndex()); i < path.getNodeCount(); i++) {
                Vec3 node = path.getNode(i).asBlockPos().getCenter();
                total += horizontalDistance(previous, node);
                previous = node;
            }
            return total;
        }
        Vec3 route = currentRouteTarget();
        return route == null ? Double.POSITIVE_INFINITY : horizontalDistance(soldier.position(), route);
    }

    @Nullable
    private Vec3 currentRouteTarget() {
        var path = soldier.getNavigation().getPath();
        if (path != null && !path.isDone() && path.getNodeCount() > 0) {
            return path.getNextEntityPos(soldier);
        }
        if (soldier.getMoveControl() instanceof CoverPositionController control
            && control.getLastResult() == CoverPositionController.MovementResult.IN_PROGRESS) {
            return control.getDebugTargetPos();
        }
        return null;
    }

    private boolean hasShortProtectiveCoverCommit() {
        return soldier.getCoverBehaviorManager() != null
            && soldier.getCoverBehaviorManager().getTargetCover() != null;
    }

    private void applyMeaningfulEvent() {
        String nextKey = null;
        if (mode == Mode.TRAVERSE && nearestVisibleHostile <= 8.0) {
            nextKey = "close-contact:" + targetKey();
        } else if (mode == Mode.TACTICAL && hasShortProtectiveCoverCommit()) {
            var cover = soldier.getCoverBehaviorManager().getTargetCover();
            if (cover != null && routeDistance <= 10.0) nextKey = "protective-cover:" + cover.getPosition().asLong();
        }
        long generation = ownerGeneration();
        if (nextKey == null) return;
        String commitment = generation + ":" + nextKey;
        if (nextKey.startsWith("close-contact")) {
            if (commitment.equals(lastCloseEventKey)) return;
            lastCloseEventKey = commitment;
        } else {
            if (commitment.equals(lastCoverEventKey)) return;
            lastCoverEventKey = commitment;
        }
        eventKey = nextKey;
        eventOwnerGeneration = generation;
        transitionReason = nextKey.startsWith("close-contact") ? "close-visible-contact" : "short-cover-commit";
        pendingEventMode = nextKey.startsWith("close-contact") ? Mode.TACTICAL : Mode.TRAVERSE;
    }

    private long ownerGeneration() {
        long attack = Integer.toUnsignedLong(soldier.getAttackGeneration());
        long move = Integer.toUnsignedLong(soldier.getPingMoveGeneration());
        long modeBits = soldier.getSquadMode().ordinal();
        long ownerBits = soldier.getOwnerUUID().map(UUID::getLeastSignificantBits).orElse(0L);
        return Long.rotateLeft(attack, 41) ^ Long.rotateLeft(move, 13) ^ modeBits ^ ownerBits;
    }

    private String targetKey() {
        LivingEntity target = soldier.getTarget();
        return target == null ? "none" : target.getUUID().toString();
    }

    private void beginNextPhase() {
        phaseCounter++;
        long seed = soldier.getUUID().getMostSignificantBits()
            ^ Long.rotateLeft(soldier.getUUID().getLeastSignificantBits(), 19)
            ^ (0x9E3779B97F4A7C15L * phaseCounter);
        java.util.Random random = new java.util.Random(seed);
        int cycleTicks = MIN_CYCLE_TICKS + random.nextInt(MAX_CYCLE_TICKS - MIN_CYCLE_TICKS + 1);
        double jitteredShare = Mth.clamp(tacticalShare + (random.nextDouble() * 0.10 - 0.05), 0.17, 0.8);
        tacticalPhaseTicks = Mth.clamp((int) Math.round(cycleTicks * jitteredShare), MIN_PHASE_TICKS,
            cycleTicks - MIN_PHASE_TICKS);
        traversePhaseTicks = cycleTicks - tacticalPhaseTicks;
        phaseIndex = 0;
        // Start with the opposite of the last completed phase. Randomized
        // durations stagger soldiers without joining identical adjacent phases.
        preferredMode = mode == Mode.TACTICAL ? Mode.TRAVERSE : Mode.TACTICAL;
        phaseDurationTicks = preferredMode == Mode.TACTICAL ? tacticalPhaseTicks : traversePhaseTicks;
        phaseTicksRemaining = phaseDurationTicks;
        setMode(preferredMode, "phase-cycle");
    }

    private void advancePhase() {
        if (phaseDurationTicks == 0 || phaseIndex >= 1) {
            beginNextPhase();
            return;
        }
        phaseIndex = 1;
        preferredMode = preferredMode == Mode.TACTICAL ? Mode.TRAVERSE : Mode.TACTICAL;
        phaseDurationTicks = preferredMode == Mode.TACTICAL ? tacticalPhaseTicks : traversePhaseTicks;
        phaseTicksRemaining = phaseDurationTicks;
        setMode(preferredMode, "phase-cycle");
    }

    private void setMode(Mode next, String reason) {
        if (mode == next) return;
        mode = next;
        transitionReason = reason;
        ticksSinceEvent = 0;
        // Stop an old mode's residual input on the next movement-controller tick.
        fireConstraintUntil = soldier.tickCount + 1;
    }

    private void resetEventLatch() {
        eventKey = "";
        lastCloseEventKey = lastCoverEventKey = "";
        eventOwnerGeneration = Long.MIN_VALUE;
    }

    private void updateFireMode() {
        boolean quickOutward = soldier.getPeekController().isQuickPeek()
            && soldier.getPeekController().isMovingToPeek();
        boolean controllerMove = soldier.getMoveControl() instanceof CoverPositionController control
            && control.requiresRouteFacing() && !quickOutward;
        boolean unsafe = mandatoryCrawl || soldier.isNavigationTraversalLocked()
            || soldier.horizontalCollision || soldier.minorHorizontalCollision || controllerMove;
        boolean moving = hasMovingIntent() || soldier.getDeltaMovement().horizontalDistanceSqr() > 0.0025;
        unsafe |= moving && (soldier.isLowCrouching() || soldier.isFiringProne() || !soldier.onGround());
        if ((!visibleCombatTarget && !quickOutward) || unsafe || soldier.tickCount <= fireConstraintUntil) {
            fireMode = FireMode.NONE;
        } else if (moving) {
            fireMode = quickOutward ? FireMode.ADS : mode() == Mode.TRAVERSE ? FireMode.HIP : FireMode.ADS;
        } else {
            fireMode = FireMode.ADS;
        }
        if (moving && fireMode != FireMode.ADS && !soldier.getPeekController().isCornerPreAiming() && !soldier.isVehicleCrewActive()
            && !soldier.isInVehicleEngagement()) GunIntegration.aim(soldier, false);
    }

    private boolean isHardOverride() {
        if (soldier.isPreparingOrReloading() || soldier.isFiringProne() || soldier.isInVehicleEngagement()) return true;
        if (soldier.isCQB() || soldier.getPeekController().isMovingToPeek()
            || soldier.getPeekController().isReturning()) return true;
        return soldier.cqbHoldFreezeEffective() && !canBypassCqbHold();
    }

    private boolean isPinnedStandingEscape() {
        var covers = soldier.getCoverBehaviorManager();
        return !soldier.isLowCrouching() && covers != null
            && covers.getSuppressionTracker().isPinned()
            && covers.getCurrentCover() == null && covers.getTargetCover() != null;
    }

    private String facingOwner() {
        if (soldier.isNavigationTraversalLocked()) return "terrain";
        if (soldier.getMoveControl() instanceof CoverPositionController control) {
            if (control.getLastResult() == CoverPositionController.MovementResult.IN_PROGRESS
                || soldier.horizontalCollision || soldier.minorHorizontalCollision) return "route-safety";
            if (mode == Mode.TACTICAL && hasMovingIntent()) return "tactical-body";
        }
        if (soldier.getPeekController().isMovingToPeek() || soldier.getPeekController().isReturning()) return "peek";
        return hasMovingIntent() ? "navigation" : "combat";
    }

    public Mode mode() {
        if (mandatoryCrawl || soldier.getTravelCrawlController().isCrawlActive()
            || (soldier.isLowCrouching() && hasMovingIntent())) return Mode.TRAVERSE;
        if (soldier.getMoveControl() instanceof CoverPositionController control && control.requiresRouteFacing())
            return Mode.TRAVERSE;
        return mode;
    }
    public FireMode fireMode() { return fireMode; }
    /** Route-owned prohibitions also hold while a path is being replaced or has just ended. */
    public boolean isStationaryFireConstrained() {
        return mandatoryCrawl || soldier.tickCount <= fireConstraintUntil
            || ownerGeneration() != lastOwnerGeneration;
    }
    public boolean hasMovingIntent() { return currentRouteTarget() != null; }
    public boolean canBypassCqbHold() {
        LivingEntity target = soldier.getTarget();
        double overrun = com.stevesarmy.StevesArmyConfig.getCqbOverrunRange();
        boolean pointBlankSurvival = target != null && target.isAlive()
            && soldier.distanceToSqr(target) <= overrun * overrun;
        return hasMovingIntent() && !soldier.isNavigationTraversalLocked()
            && !soldier.isCQB() && !pointBlankSurvival;
    }

    public boolean canFireAt(Vec3 aimPoint) {
        if (fireMode == FireMode.NONE || aimPoint == null || isFireUnsafe()) return false;
        Vec3 toAim = aimPoint.subtract(soldier.getEyePosition());
        Vec3 look = new Vec3(toAim.x, 0, toAim.z).normalize();
        Vec3 body = Vec3.directionFromRotation(0, soldier.getYRot());
        double dot = Mth.clamp(body.x * look.x + body.z * look.z, -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return fireMode == FireMode.ADS || angle <= 25.0;
    }

    private boolean isFireUnsafe() {
        if (mandatoryCrawl || soldier.isLowCrouching() || soldier.isFiringProne()
            || soldier.isPreparingOrReloading() || soldier.isHealing() || soldier.isVehicleCrewActive()
            || soldier.isInVehicleEngagement() || ownerGeneration() != lastOwnerGeneration
            || soldier.isNavigationTraversalLocked() || soldier.horizontalCollision
            || soldier.minorHorizontalCollision || !soldier.onGround()) return true;
        boolean quickOutward = soldier.getPeekController().isQuickPeek()
            && soldier.getPeekController().isMovingToPeek();
        if (!quickOutward && soldier.getMoveControl() instanceof CoverPositionController control
            && (control.getLastResult() == CoverPositionController.MovementResult.IN_PROGRESS
                || control.requiresRouteFacing())) return true;
        LivingEntity selected = soldier.getTarget();
        if (selected == null || !selected.isAlive() || !soldier.canAttack(selected)
            || !selected.getUUID().toString().equals(selectedTargetUuid)
            || (!quickOutward && !soldier.hasLineOfSight(selected))) return true;
        if (quickOutward && !com.stevesarmy.combat.ExposureCalculator.hasFreshBulletLaneTo(
            soldier.getEyePosition(), soldier.getPeekController().getQuickPeekAim(), soldier)) return true;
        var path = soldier.getNavigation().getPath();
        if (path != null && !path.isDone() && path.getNodeCount() > 0) {
            int index = Math.min(Math.max(0, path.getNextNodeIndex()), path.getNodeCount() - 1);
            int y = soldier.blockPosition().getY();
            if (path.getNode(index).y != y
                || (index + 1 < path.getNodeCount() && path.getNode(index + 1).y != y)) return true;
        }
        return false;
    }

    /** Absolute mode pace; slower posture/approach constraints are applied by SoldierSpeedController. */
    public double resolveModeSpeed(double rawRequest, double liveAttribute) {
        GunIntegration.MovementProfile profile = GunIntegration.getMovementProfile(soldier);
        double neutral = Math.min(Math.max(0, rawRequest) * profile.adsNeutralAttribute(),
            SoldierSpeedController.MAX_LOCOMOTION_SPEED);
        return mode() == Mode.TRAVERSE ? neutral : neutral * 0.40 * Math.max(0, profile.adsRatio());
    }

    /** Residual self-motion and anchor settling retain a ceiling from the active pace mode. */
    public double coastingCeiling() {
        return mode() == Mode.TRAVERSE ? SoldierSpeedController.MAX_LOCOMOTION_SPEED
            : Math.min(SoldierSpeedController.MAX_LOCOMOTION_SPEED,
                SoldierSpeedController.MAX_LOCOMOTION_SPEED * 0.40 * Math.max(0, adsRatio));
    }

    public void setMandatoryCrawl(boolean required, String reason) {
        mandatoryCrawl = required;
        mandatoryCrawlReason = required ? (reason == null ? "route" : reason) : "none";
    }

    public boolean isMandatoryCrawl() { return mandatoryCrawl; }
    public String mandatoryCrawlReason() { return mandatoryCrawlReason; }

    /** Vanilla body rotation follows velocity; Tactical travel deliberately faces another direction. */
    public void finishTick() {
        if (soldier.getPeekController().isCornerPreAiming()) {
            // Vanilla body control clamps head yaw to travel after goal ticks.
            soldier.getPeekController().restoreCornerAim(soldier);
        }
        if (mode() == Mode.TACTICAL && hasMovingIntent()
            && soldier.getMoveControl() instanceof CoverPositionController control
            && "tactical-steer".equals(control.debugMoveSource())) {
            soldier.setYBodyRot(soldier.getYRot());
        }
    }
    public Snapshot snapshot() {
        return new Snapshot(mode(), preferredMode, fireMode, phaseTicksRemaining, phaseDurationTicks,
            phaseCounter, baseShare, distanceAdjustment, routeAdjustment, tacticalShare,
            finiteOrUnknown(nearestVisibleHostile), finiteOrUnknown(routeDistance), mandatoryCrawl, mandatoryCrawlReason,
            selectedTargetUuid, selectedTargetVisible, facingOwner(), adsNeutralAttribute, adsRatio,
            movementProfileAvailable, transitionReason);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static double finiteOrUnknown(double value) {
        return Double.isFinite(value) ? value : -1.0;
    }
}

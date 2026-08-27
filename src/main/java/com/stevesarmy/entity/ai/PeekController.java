package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.*;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

public class PeekController {

    public enum State {
        HIDING,
        MOVING_TO_PEEK,
        EXPOSED,
        RETURNING_TO_COVER,
        STANDING_IN_HALF_COVER
    }

    private static final long EXPOSURE_TIME_MIN_MS = 3000;   // 3 seconds
    private static final long EXPOSURE_TIME_MAX_MS = 8000;   // 8 seconds
    private static final long MIN_EXPOSURE_TIME_MS = 800;
    private static final long DUCK_COOLDOWN_MS = 1000;
    private static final double PEEK_REACHED_DISTANCE = 0.05;
    private static final double RETURN_REACHED_DISTANCE = 0.5;
    private static final double PEEK_SPEED = 0.75;
    private static final double RETURN_TOLERANCE = 0.3;
    private static final double RETURN_SPEED = 1.0;
    private static final int NON_PEEKABLE_REPOSITION_TICKS = 40;
    private static final int BLIND_PEEK_REPOSITION_TICKS = 80;
    private static final long SQUAD_CONTACT_MAX_AGE_TICKS = 200L;

    private State state = State.HIDING;
    private Vec3 peekTarget = Vec3.ZERO;
    private Vec3 coverReturnTarget = Vec3.ZERO;
    private BlockPos currentPeekPos = null;
    private long stateStartTime = 0;
    private long lastPeekEndTime = 0;
    private int nonPeekableTicks = 0;
    private int blindPeekTicks = 0;
    private int peekCountSameCover = 0;
    private BlockPos lastCoverPosition = null;
    private long currentMaxExposureTime = 3000;
    private long suppressionEventSequenceAtExposure = 0L;
    private boolean returnAllowedDuringReload;
    
    private long getRandomExposureTime() {
        return EXPOSURE_TIME_MIN_MS + (long)(Math.random() * (EXPOSURE_TIME_MAX_MS - EXPOSURE_TIME_MIN_MS));
    }

    public State getState() { return state; }

    public long getTimeInCurrentState() {
        if (stateStartTime == 0) return 0;
        return System.currentTimeMillis() - stateStartTime;
    }

    public long getTimeSinceLastPeek() {
        if (lastPeekEndTime == 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastPeekEndTime;
    }

    public void setLastPeekEndTime(long time) { this.lastPeekEndTime = time; }

    public int getPeekCountSameCover() { return peekCountSameCover; }

    public boolean isExposed() { return state == State.EXPOSED; }

    public boolean isMovingToPeek() { return state == State.MOVING_TO_PEEK; }

    public boolean isReturning() { return state == State.RETURNING_TO_COVER; }

    public boolean isHiding() { return state == State.HIDING; }

    public boolean isStandingInHalfCover() { return state == State.STANDING_IN_HALF_COVER; }

    /** True for either non-peeking cover posture. */
    public boolean isIdleInCover() { return isHiding() || isStandingInHalfCover(); }

    /** Restores the standing half-cover posture after a genuine defensive low crouch. */
    public void recoverStandingInHalfCover(SoldierEntity soldier, String riseSource) {
        boolean wasLowCrouching = soldier.isLowCrouching();
        soldier.setLowCrouching(false);
        enterStandingInHalfCover(soldier, wasLowCrouching, riseSource);
    }

    /** Enters a normal standing half-cover idle posture without changing low crouch. */
    public void enterStandingInHalfCover(SoldierEntity soldier, String transitionSource) {
        enterStandingInHalfCover(soldier, false, transitionSource);
    }

    private void enterStandingInHalfCover(SoldierEntity soldier, boolean animateRise, String transitionSource) {
        setState(soldier, State.STANDING_IN_HALF_COVER);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        if (animateRise) {
            soldier.beginHalfCoverRise(transitionSource);
        } else {
            soldier.cancelHalfCoverRise(transitionSource);
        }
    }

    public void enterHiding(SoldierEntity soldier) {
        setState(soldier, State.HIDING);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
    }

    public void reset(SoldierEntity soldier) {
        setState(soldier, State.HIDING);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        nonPeekableTicks = 0;
        blindPeekTicks = 0;
        currentMaxExposureTime = getRandomExposureTime();
    }

    public void reset() {
        state = State.HIDING;
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        nonPeekableTicks = 0;
        blindPeekTicks = 0;
        currentMaxExposureTime = getRandomExposureTime();
    }

    private void setState(SoldierEntity soldier, State newState) {
        State previousState = this.state;
        this.state = newState;
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekState(newState.ordinal());
            if (previousState != newState) {
                soldier.tracePeek("peek-state", "previous=" + previousState + ", current=" + newState);
            }
        }
    }

    public void resetForNewCover(BlockPos coverPosition) {
        reset();
        if (coverPosition != null && coverPosition.equals(lastCoverPosition)) {
            peekCountSameCover = Math.max(peekCountSameCover, 0);
        } else {
            peekCountSameCover = 0;
        }
        lastCoverPosition = coverPosition;
    }

    public void recordPeekCycle(SoldierEntity soldier) {
        peekCountSameCover++;
        
        if (soldier != null) {
            soldier.getCoverBehaviorManager().recordPeekCycle();
        }
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} recordPeekCycle: count={}",
                soldier != null ? soldier.getId() : "null", peekCountSameCover);
        }
    }

    public boolean needsReposition() {
        return false; // Will be set by tickHiding
    }

    public void tick(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        tick(soldier, cover, mover, true);
    }

    public void tick(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover,
                     boolean allowPeekStart) {
        if (cover == null) return;

        switch (state) {
            case HIDING:
            case STANDING_IN_HALF_COVER:
                tickHiding(soldier, cover, mover, allowPeekStart);
                break;
            case MOVING_TO_PEEK:
                tickMovingToPeek(soldier, cover, mover);
                break;
            case EXPOSED:
                tickExposed(soldier, cover, mover);
                break;
            case RETURNING_TO_COVER:
                tickReturning(soldier, cover, mover);
                break;
        }
    }

    public void forceReturnToCover(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (cover == null || isIdleInCover() || state == State.RETURNING_TO_COVER) return;
        soldier.tracePeek("return-request", "reason=forced-return");
        enterReturning(soldier, cover, mover, false);
    }

    /** Allows the full-cover duck-back to complete while reload blocks all other movement. */
    public void forceReturnToCoverDuringReload(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (cover == null || isIdleInCover()) return;

        if (state == State.RETURNING_TO_COVER) {
            returnAllowedDuringReload = true;
            if (cover.getType() == CoverType.FULL) {
                coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
                mover.returnToCoverDuringReload(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover during reload");
            }
            return;
        }

        enterReturning(soldier, cover, mover, true);
    }

    private void tickHiding(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover,
                            boolean allowPeekStart) {
        boolean isHalf = cover.getType() == CoverType.HALF;
        boolean isFull = cover.getType() == CoverType.FULL;

        if (soldier.getCoverBehaviorManager().isNonPeekableCover()) {
            nonPeekableTicks++;
            LivingEntity target = soldier.getTarget();
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS && target != null && target.isAlive()) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} non-peekable for {} ticks with target, requesting reposition",
                        soldier.getId(), nonPeekableTicks);
                }
                nonPeekableTicks = 0;
                soldier.getCoverBehaviorManager().setNonPeekableCover(false);
                soldier.getCoverBehaviorManager().requestReposition();
                setIdleState(soldier, cover);
                return;
            }
        } else {
            nonPeekableTicks = 0;
        }

        if (getTimeSinceLastPeek() < DUCK_COOLDOWN_MS || !allowPeekStart) {
            return;
        }

        Vec3 threatDir = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
        
        if ((threatDir == null || threatDir.lengthSqr() <= 0.001) && soldier.hasValidPingSuppressPos()) {
            BlockPos suppressPos = soldier.getPingSuppressPos();
            threatDir = Vec3.atCenterOf(suppressPos).subtract(soldier.position()).normalize();
        }
        
        if (threatDir == null || threatDir.lengthSqr() <= 0.001) {
            return;
        }

        currentMaxExposureTime = getRandomExposureTime();
        captureSuppressionSequence(soldier);
        preAimToward(soldier, threatDir);

        if (isHalf) {
            lockRotationToCoverWall(soldier, cover, null);
            enterExposed(soldier, cover);
        } else if (isFull) {
            LivingEntity target = soldier.getTarget();
            BlockPos peekPos = CoverTacticalGoal.computePeekPositionStatic(cover, threatDir, target, soldier.level(), soldier.getY());
            if (peekPos == null) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} no valid peek position toward threat",
                        soldier.getId());
                }
                soldier.getCoverBehaviorManager().setNonPeekableCover(true);
                return;
            }

            currentPeekPos = peekPos;
            Vec3 targetPos = peekPos.getCenter();
            Vec3 soldierPos = soldier.position();
            double dist = Math.sqrt(
                Math.pow(targetPos.x - soldierPos.x, 2) +
                Math.pow(targetPos.z - soldierPos.z, 2));

            if (dist < PEEK_REACHED_DISTANCE) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} already at peek position, exposing",
                        soldier.getId());
                }
                enterExposed(soldier, cover);
                return;
            }

            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek toward threat: moving to {}",
                    soldier.getId(), peekPos);
            }

            this.peekTarget = targetPos;
            this.coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            mover.moveTo(targetPos, PEEK_REACHED_DISTANCE, PEEK_SPEED, "PeekController", "slide to peek");
            setState(soldier, State.MOVING_TO_PEEK);
            stateStartTime = System.currentTimeMillis();
        }
    }

    private void preAimToward(SoldierEntity soldier, Vec3 direction) {
        float previousYaw = soldier.getYRot();
        float previousBodyYaw = soldier.yBodyRot;
        float previousHeadYaw = soldier.getYHeadRot();
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        soldier.setYRot((float) yaw);
        soldier.setYHeadRot((float) yaw);
        soldier.setYBodyRot((float) yaw);
        soldier.traceRotationWrite("peek-pre-aim", previousYaw, previousBodyYaw, previousHeadYaw,
            "threatDirection=" + direction + ", targetYaw=" + String.format("%.1f", yaw));
    }

    private void tickMovingToPeek(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (shouldDuckForSuppression(soldier)) {
            soldier.tracePeek("return-request", "reason=suppression-during-peek-movement");
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} suppressed during peek movement, ducking back",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover, false);
            return;
        }

        CoverPositionController.MovementResult result = mover.getLastResult();

        if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek slide done, exposing",
                    soldier.getId());
            }
            enterExposed(soldier, cover);
        } else if (result == CoverPositionController.MovementResult.FAILED) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} peek slide failed, staying hidden",
                    soldier.getId());
            }
            lastPeekEndTime = System.currentTimeMillis();
            setIdleState(soldier, cover);
            stateStartTime = 0;
        }
    }

    private void tickExposed(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        long timeInState = getTimeInCurrentState();

        updateBlindPeekTimer(soldier);

        if (timeInState > currentMaxExposureTime) {
            soldier.tracePeek("return-request", "reason=exposure-timeout, limitMs=" + currentMaxExposureTime);
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} exposure time exceeded ({}ms), ducking back",
                    soldier.getId(), currentMaxExposureTime);
            }
            enterReturning(soldier, cover, mover, false);
            return;
        }

        LivingEntity target = soldier.getTarget();
        if (target != null && !target.isAlive()) {
            soldier.tracePeek("return-request", "reason=target-dead");
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} target dead, ducking back sooner",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover, false);
            return;
        }

        if (shouldDuckForSuppression(soldier)) {
            soldier.tracePeek("return-request", "reason=suppression-while-exposed");
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} suppressed while exposed, ducking back",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover, false);
            return;
        }
    }

    private void tickReturning(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        if (cover == null) {
            setState(soldier, State.HIDING);
            stateStartTime = 0;
            returnAllowedDuringReload = false;
            return;
        }

        // Half cover: instant return after time
        if (cover.getType() == CoverType.HALF) {
            lockRotationToCoverWall(soldier, cover, soldier.getTarget());
            if (getTimeInCurrentState() > 200) {
                completeReturn(soldier, cover);
            }
            return;
        }

        // Full cover: check movement
        CoverPositionController.MovementResult result = mover.getLastResult();

        if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
            completeReturn(soldier, cover);
        } else if (result == CoverPositionController.MovementResult.FAILED) {
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} return failed, retrying",
                    soldier.getId());
            }
            // A failed controller result does not prove that the soldier is
            // protected. Reset the request and let the next tick retry it.
            mover.clear();
        } else if (result == CoverPositionController.MovementResult.NONE) {
            // Not moving — start return
            coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            if (returnAllowedDuringReload) {
                mover.returnToCoverDuringReload(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover during reload");
            } else {
                mover.moveTo(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover");
            }
        }
        // IN_PROGRESS — wait
    }

    private void enterExposed(SoldierEntity soldier, CoverPoint cover) {
        setState(soldier, State.EXPOSED);
        stateStartTime = System.currentTimeMillis();
        
        boolean isHalf = cover.getType() == CoverType.HALF;
        if (isHalf) {
            // A real defensive low-crouch exit gets the visible reaction window;
            // normal standing half-cover peeks have no pending rise and stay still.
            String riseSource = soldier.getCoverBehaviorManager().isSuppressed()
                ? "pressured-peek" : "half-cover-exposure";
            soldier.beginHalfCoverRise(riseSource);
            if (!soldier.isHalfCoverRising()) {
                soldier.cancelHalfCoverRise("normal-half-cover-peek");
            }
        } else {
            CoverPositionController mover = (CoverPositionController) soldier.getMoveControl();
            mover.clear();
            soldier.getNavigation().stop();
            soldier.setDeltaMovement(0, soldier.getDeltaMovement().y, 0);
        }
        
        soldier.refreshDimensions();

        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} entered EXPOSED (exposure={}ms)",
                soldier.getId(), currentMaxExposureTime);
        }
    }

    /** Exposes only half cover after combat has completed an emergency posture change. */
    public boolean exposeForEmergencyEngagement(SoldierEntity soldier, CoverPoint cover) {
        if (!isIdleInCover() || cover.getType() != CoverType.HALF) {
            return false;
        }

        currentMaxExposureTime = getRandomExposureTime();
        captureSuppressionSequence(soldier);
        enterExposed(soldier, cover);
        return true;
    }

    /**
     * Accumulates exposed time spent without a personal LOS contact while the squad still
     * has a recent exposed enemy point. The counter intentionally survives separate peek
     * cycles, but is reset by personal contact or when a new cover is selected.
     */
    private void updateBlindPeekTimer(SoldierEntity soldier) {
        LivingEntity target = soldier.getTarget();
        boolean hasActiveContact = target != null
            && TargetAcquisition.isValidTarget(soldier, target)
            && !soldier.isFriendlyTo(target)
            && TargetAcquisition.hasLineOfSight(soldier, target);

        if (hasActiveContact) {
            if (blindPeekTicks > 0 && CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} blind-peek timer reset by active contact after {} ticks",
                    soldier.getId(), blindPeekTicks);
            }
            blindPeekTicks = 0;
            return;
        }

        int freshContacts = countFreshSquadContacts(soldier);
        if (freshContacts <= 0) {
            return;
        }

        blindPeekTicks = Math.min(BLIND_PEEK_REPOSITION_TICKS, blindPeekTicks + 1);
        if (blindPeekTicks == 1 && CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} started blind-peek timer with {} fresh squad contacts",
                soldier.getId(), freshContacts);
        }

        if (blindPeekTicks >= BLIND_PEEK_REPOSITION_TICKS
            && !soldier.getCoverBehaviorManager().isRepositionRequested()) {
            soldier.getCoverBehaviorManager().requestReposition();
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} blind-peek timeout reached after {} ticks, requesting reposition (freshContacts={})",
                    soldier.getId(), blindPeekTicks, freshContacts);
            }
        }
    }

    private int countFreshSquadContacts(SoldierEntity soldier) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return 0;
        }

        java.util.UUID squadId = soldier.getSquadId();
        if (squadId == null) {
            return 0;
        }

        long now = soldier.level().getGameTime();
        return SquadManager.get(serverLevel).getSquadById(squadId)
            .map(squad -> (int) squad.getThreatIntel().getAllThreats().stream()
                .filter(threat -> threat.isAlive && threat.lastVisibleAimPoint != null)
                .filter(threat -> now - threat.lastSeenTime >= 0
                    && now - threat.lastSeenTime <= SQUAD_CONTACT_MAX_AGE_TICKS)
                .count())
            .orElse(0);
    }

    private void captureSuppressionSequence(SoldierEntity soldier) {
        suppressionEventSequenceAtExposure = soldier.getCoverBehaviorManager()
            .getSuppressionTracker().getSuppressionEventSequence();
    }

    private boolean shouldDuckForSuppression(SoldierEntity soldier) {
        if (soldier.hasEmergencyEngagementPosture()) {
            soldier.tracePeek("suppression-override", "reason=emergency-engagement");
            return false;
        }

        var suppressionTracker = soldier.getCoverBehaviorManager().getSuppressionTracker();
        boolean newSuppressionEvent = suppressionTracker.getSuppressionEventSequence()
            != suppressionEventSequenceAtExposure;
        return suppressionTracker.isPinned()
            || (suppressionTracker.isSuppressed() && newSuppressionEvent);
    }

    private void enterReturning(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover,
                                boolean allowDuringReload) {
        soldier.tracePeek("return-start", "coverType=" + cover.getType() + ", allowDuringReload=" + allowDuringReload);
        setState(soldier, State.RETURNING_TO_COVER);
        stateStartTime = System.currentTimeMillis();
        returnAllowedDuringReload = allowDuringReload;
        
        boolean isHalf = cover.getType() == CoverType.HALF;
        if (isHalf) {
            // Pose managed by CoverTacticalGoal + SoldierEntity.tick() enforcement
            lockRotationToCoverWall(soldier, cover, soldier.getTarget());
        } else {
            // Full cover: start slide movement back to cover position
            coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            if (allowDuringReload) {
                mover.returnToCoverDuringReload(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover during reload");
            } else {
                mover.moveTo(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover");
            }
        }
        
        soldier.refreshDimensions();

        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} state: EXPOSED -> RETURNING_TO_COVER",
                soldier.getId());
        }
    }

    private void completeReturn(SoldierEntity soldier, CoverPoint cover) {
        soldier.tracePeek("return-complete", "coverType=" + cover.getType());
        lastPeekEndTime = System.currentTimeMillis();
        recordPeekCycle(soldier);
        setIdleState(soldier, cover);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        soldier.refreshDimensions();
        
        if (CoverTacticalGoal.isDebugLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PeekController] Soldier {} state: RETURNING_TO_COVER -> {}",
                soldier.getId(), state);
        }
    }

    private void setIdleState(SoldierEntity soldier, CoverPoint cover) {
        if (cover != null && cover.getType() == CoverType.HALF && !soldier.isLowCrouching()) {
            enterStandingInHalfCover(soldier, "peek-return");
        } else {
            enterHiding(soldier);
        }
    }

    // --- LOS and target evaluation helpers (moved from CoverTacticalGoal) ---

    private boolean hasLineOfSight(SoldierEntity soldier, Vec3 from, Vec3 to) {
        return com.stevesarmy.combat.VisibilityRay.trace(soldier.level(), from, to, soldier).hasContact();
    }
    
    private void lockRotationToCoverWall(SoldierEntity soldier, CoverPoint cover, LivingEntity target) {
        Set<Direction> protectedDirs = cover.getProtectedDirections();
        if (protectedDirs.isEmpty()) return;
        
        Direction wallDir = protectedDirs.iterator().next();
        float baseYaw = wallDir.toYRot();
        
        float offset = 0.0f;
        if (target != null && target.isAlive()) {
            Vec3 toTarget = target.position().subtract(soldier.position()).normalize();
            float targetAngle = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            float angleDiff = Mth.wrapDegrees(targetAngle - baseYaw);
            
            if (Math.abs(angleDiff) < 90) {
                offset = Math.signum(angleDiff) * 15.0f;
            }
        }
        
        float finalYaw = Mth.wrapDegrees(baseYaw + offset);
        float previousYaw = soldier.getYRot();
        float previousBodyYaw = soldier.yBodyRot;
        float previousHeadYaw = soldier.getYHeadRot();
        soldier.setYRot(finalYaw);
        soldier.setYBodyRot(finalYaw);
        soldier.setYHeadRot(finalYaw);
        soldier.yBodyRotO = finalYaw;
        soldier.yHeadRotO = finalYaw;
        soldier.traceRotationWrite("peek-cover-wall", previousYaw, previousBodyYaw, previousHeadYaw,
            "wall=" + wallDir + ", baseYaw=" + String.format("%.1f", baseYaw)
                + ", offset=" + String.format("%.1f", offset)
                + ", target=" + (target == null ? "none" : target.getId()));
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.GameClock;
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
        STANDING_IN_HALF_COVER,
        INACTIVE
    }

    private static final long EXPOSURE_TIME_MIN_MS = 3000;   // 3 seconds
    private static final long EXPOSURE_TIME_MAX_MS = 8000;   // 8 seconds
    private static final long MIN_EXPOSURE_TIME_MS = 800;
    private static final long DUCK_COOLDOWN_MS = 1000;
    private static final double PEEK_REACHED_DISTANCE = 0.05;
    private static final double RETURN_REACHED_DISTANCE = 0.5;
    private static final double PEEK_SPEED = 0.75;
    private static final double RETURN_TOLERANCE = 0.05;
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
    private boolean liveCornerPeek;
    private Vec3 cornerDirection = Vec3.ZERO;
    private int lostLaneTicks;
    private Vec3 cornerWaypoint;
    private Vec3 cornerOrigin = Vec3.ZERO;
    private Vec3 cornerBack = Vec3.ZERO;
    private java.util.UUID preAimTarget;
    private Vec3 preAimPoint;
    private int alignedPreAimTicks;
    private boolean ownsPreAim;
    private boolean reacquired;
    private Vec3 returnWaypoint;
    private boolean preAimCreditUsed;
    private boolean quickPeek;
    private Vec3 quickAim;
    private int quickShots;
    private long quickRecoveryUntil;
    private static final long QUICK_MEMORY_MS = 3000;
    private static final long QUICK_RECOVERY_MS = 2000;
    private static final double QUICK_SPEED = 0.75;

    public boolean isQuickPeek() { return quickPeek; }
    public int getQuickPeekShots() { return quickShots; }
    public Vec3 getQuickPeekAim() { return quickPeek && (isMovingToPeek() || isExposed()) ? quickAim : null; }
    public void onQuickPeekShot() { if (quickPeek) quickShots++; }

    private Vec3 freshQuickAim(SoldierEntity soldier, LivingEntity target) {
        if (target == null || soldier.hasValidPingSuppressPos() || soldier.isHealing()
            || soldier.getCoverBehaviorManager().getSuppressionTracker().isSuppressed()
            || GunIntegration.isReloading(soldier) || GunIntegration.isDrawing(soldier)
            || GunIntegration.isBolting(soldier) || !GunIntegration.hasGun(soldier)) return null;
        Vec3 aim = null;
        int contacts = 0;
        for (var threat : soldier.getThreatAwareness().getThreatInfos()) {
            if (threat.entity == null && threat.weight > 0.1f) return null;
            if (threat.wasObservedWithin(GameClock.nowMs(), 10000) && threat.entity != null && threat.entity.isAlive()) {
                contacts++;
                if (threat.entity == target && threat.wasObservedWithin(GameClock.nowMs(), QUICK_MEMORY_MS))
                    aim = threat.lastObservedAim;
            }
        }
        return contacts == 1 && countFreshSquadContacts(soldier) <= 1 ? aim : null;
    }

    public float consumeCornerPreAimCredit(LivingEntity target) {
        float credit = getCornerPreAimCredit(target);
        if (preAimCreditUsed || credit <= 0 || target == null || preAimPoint == null) return 0;
        preAimCreditUsed = true;
        return credit;
    }

    public int getAlignedPreAimTicks() { return alignedPreAimTicks; }

    /** Credit is earned before contact, never by merely entering the exposed state. */
    public float getCornerPreAimCredit(LivingEntity target) {
        return ownsPreAim && state == State.EXPOSED && target != null
            && target.getUUID().equals(preAimTarget) ? Math.min(1.0f, alignedPreAimTicks / 16.0f) : 0;
    }

    /** A small two-dimensional search, once per peek start, rather than once per tick. */
    private Vec3 findCornerOpening(SoldierEntity soldier, CoverPoint cover, Vec3 threatDir, LivingEntity target) {
        Vec3 start = soldier.position();
        Vec3 best = null;
        java.util.List<Vec3> knownEyes = new java.util.ArrayList<>();
        for (ThreatAwareness.ThreatInfo threat : soldier.getThreatAwareness().getThreatInfos()) {
            if (threat.wasObservedWithin(GameClock.nowMs(), 10000)
                && (threat.entity == null || threat.entity.isAlive())) {
                Vec3 knownEye = quickPeek ? threat.lastObservedAim
                    : Vec3.atBottomCenterOf(threat.position).add(0, 1.62, 0);
                if (knownEye != null) knownEyes.add(knownEye);
            }
        }
        if (soldier.level() instanceof ServerLevel server && soldier.getSquadId() != null) {
            SquadManager.get(server).getSquadById(soldier.getSquadId()).ifPresent(squad -> {
                long now = server.getGameTime();
                for (var threat : squad.getThreatIntel().getAllThreats()) {
                    if (threat.isAlive && threat.lastVisibleAimPoint != null
                        && now >= threat.lastSeenTime && now - threat.lastSeenTime <= SQUAD_CONTACT_MAX_AGE_TICKS) {
                        Vec3 eye = threat.lastVisibleAimPoint;
                        if (knownEyes.stream().noneMatch(p -> p.distanceToSqr(eye) < 1.0)) knownEyes.add(eye);
                    }
                }
            });
        }
        double bestScore = Double.POSITIVE_INFINITY;
        int[] bestExposure = null;
        double bestLateral = Double.POSITIVE_INFINITY;
        double bestPath = Double.POSITIVE_INFINITY;
        cornerOrigin = start;
        // Back means away from the cover face, not diagonally away from a moving target.
        Direction face = cover.getProtectedDirections().stream()
            .max(java.util.Comparator.comparingDouble(d -> new Vec3(d.getStepX(), 0, d.getStepZ()).dot(threatDir)))
            .orElse(Direction.SOUTH);
        cornerBack = new Vec3(-face.getStepX(), 0, -face.getStepZ());
        cornerWaypoint = null;
        // Five depths, two sides, twenty fractional samples: <=210 lane probes.
        // Expensive body rays run only at the first stable opening of each row.
        for (int depth = 0; depth <= (knownEyes.size() > 1 ? 0 : 4); depth++) {
            Vec3 back = start.add(cornerBack.scale(depth * 0.25));
            if (!clearCornerSegment(soldier, start, back)) break;
            if (depth > 0) {
                // Rear travel must preserve the dock's protection against every known enemy.
                boolean protectedRear = true;
                int samples = Math.max(1, depth * 2);
                for (int i = 1; i <= samples && protectedRear; i++) {
                    int[] rearExposure = cornerExposure(soldier, start.lerp(back, i / (double)samples), knownEyes);
                    for (int enemy = 2; enemy < rearExposure.length; enemy++)
                        if (rearExposure[enemy] > 0) protectedRear = false;
                }
                if (!protectedRear) break;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Vec3 direction = new Vec3(side.getStepX(), 0, side.getStepZ());
                if (Math.abs(direction.dot(cornerBack)) > 0.1 || cover.getProtectedDirections().contains(side)) continue;
                for (int step = 0; step <= 20; step++) {
                    double distance = step * 0.05;
                    Vec3 feet = back.add(direction.scale(distance));
                    if (!clearCornerPosition(soldier, feet)) break;
                    if (!hasCornerLane(soldier, feet, target)
                        || !hasCornerLane(soldier, feet.subtract(direction.scale(0.04)), target)) continue;
                    int[] endpoint = cornerExposure(soldier, feet, knownEyes);
                    int maxThreats = endpoint[0], maxRays = endpoint[1];
                    // Reject a route which crosses more threats/body than its opening.
                    // Both legs (also used in reverse for return) are checked, including
                    // the diagonal shortcut a direct controller request would otherwise take.
                    boolean safe = true;
                    for (int leg = 0; leg < 2 && safe; leg++) {
                        Vec3 from = leg == 0 ? start : back;
                        Vec3 to = leg == 0 ? back : feet;
                        int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / 0.125));
                        for (int i = 0; i <= samples; i++) {
                            Vec3 p = from.lerp(to, i / (double)samples);
                            if (!clearCornerPosition(soldier, p)) { safe = false; break; }
                            int[] exposure = cornerExposure(soldier, p, knownEyes);
                            maxThreats = Math.max(maxThreats, exposure[0]);
                            maxRays = Math.max(maxRays, exposure[1]);
                            for (int enemy = 2; enemy < exposure.length; enemy++) {
                                if (exposure[enemy] > endpoint[enemy]) safe = false;
                            }
                            if (!safe || exposure[0] > endpoint[0] || exposure[1] > endpoint[1]) { safe = false; break; }
                        }
                    }
                    if (safe) {
                        double pathCost = depth * 0.25 + distance;
                        double score = maxThreats * 1000 + maxRays * 10 + pathCost * 2;
                        boolean noEnemyWorse = bestExposure == null;
                        if (bestExposure != null) {
                            noEnemyWorse = true;
                            for (int enemy = 2; enemy < endpoint.length; enemy++)
                                if (endpoint[enemy] > bestExposure[enemy]) noEnemyWorse = false;
                        }
                        boolean usefulShallowBackstep = depth >= 1 && depth <= 2 && noEnemyWorse
                            && distance <= bestLateral - 0.10 + 1.0e-6 && pathCost <= bestPath + 0.25 + 1.0e-6;
                        if (score < bestScore || usefulShallowBackstep) {
                            bestScore = score;
                            bestExposure = endpoint;
                            bestLateral = distance;
                            bestPath = pathCost;
                            best = feet;
                            cornerWaypoint = depth == 0 ? null : back;
                        }
                    }
                    break;
                }
            }
        }
        return best;
    }

    private int[] cornerExposure(SoldierEntity soldier, Vec3 feet, List<Vec3> eyes) {
        int threats = 0, rays = 0;
        int[] result = new int[eyes.size() + 2];
        for (int i = 0; i < eyes.size(); i++) {
            int n = com.stevesarmy.combat.ExposureCalculator.calculateExposureAt(eyes.get(i), soldier, feet);
            result[i + 2] = n;
            if (n > 0) threats++;
            rays += n;
        }
        result[0] = threats;
        result[1] = rays;
        return result;
    }

    private boolean clearCornerPosition(SoldierEntity soldier, Vec3 feet) {
        var box = soldier.getBoundingBox().move(feet.subtract(soldier.position()));
        if (!soldier.level().noCollision(soldier, box)
            || com.stevesarmy.util.HazardBlockHelper.boundingBoxOverlapsHazard(soldier.level(), box)) return false;
        // Support all four footprint corners; collision uses the planned pose height.
        for (double x : new double[]{box.minX + 0.02, box.maxX - 0.02}) {
            for (double z : new double[]{box.minZ + 0.02, box.maxZ - 0.02}) {
                BlockPos pos = BlockPos.containing(x, feet.y, z);
                if (!soldier.level().isLoaded(pos) || !soldier.level().getBlockState(pos.below()).isSolid()
                    || !soldier.level().getBlockState(pos).getFluidState().isEmpty()) return false;
            }
        }
        return true;
    }

    private boolean clearCornerSegment(SoldierEntity soldier, Vec3 from, Vec3 to) {
        int count = Math.max(1, (int)Math.ceil(from.distanceTo(to) / 0.05));
        for (int i = 0; i <= count; i++) if (!clearCornerPosition(soldier, from.lerp(to, i / (double)count))) return false;
        return true;
    }

    private void clearPreAim(SoldierEntity soldier) {
        if (ownsPreAim && soldier != null) GunIntegration.aim(soldier, false);
        ownsPreAim = false;
        preAimCreditUsed = false;
        alignedPreAimTicks = 0;
        preAimTarget = null;
        preAimPoint = null;
    }

    private void tickCornerPreAim(SoldierEntity soldier) {
        LivingEntity target = soldier.getTarget();
        if (!liveCornerPeek || target == null || !target.isAlive() || soldier.isHealing()
            || !GunIntegration.hasGun(soldier) || GunIntegration.isReloading(soldier)
            || GunIntegration.isDrawing(soldier) || GunIntegration.isBolting(soldier)) {
            clearPreAim(soldier);
            return;
        }
        if (quickPeek) {
            preAimTarget = target.getUUID();
            preAimPoint = quickAim;
        }
        // Use the remembered point while occluded; do not track unseen target motion.
        if (!target.getUUID().equals(preAimTarget)) {
            clearPreAim(soldier);
            preAimTarget = target.getUUID();
        }
        if (preAimPoint == null) {
            for (var threat : soldier.getThreatAwareness().getThreatInfos()) {
                if (threat.entity == target && threat.wasObservedWithin(GameClock.nowMs(), 10000)) {
                    preAimPoint = threat.lastObservedAim;
                    if (preAimPoint == null) continue;
                }
            }
        }
        if (!quickPeek && hasCornerLane(soldier, soldier.position(), target)) preAimPoint = target.getEyePosition();
        if (preAimPoint == null) return;
        Vec3 delta = preAimPoint.subtract(soldier.getEyePosition());
        float yaw = (float)Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        float error = Math.max(Math.abs(Mth.wrapDegrees(yaw - soldier.getYHeadRot())), Math.abs(pitch - soldier.getXRot()));
        if (error > 12) alignedPreAimTicks = 0;
        soldier.getLookControl().setLookAt(preAimPoint.x, preAimPoint.y, preAimPoint.z, 20, 20);
        soldier.setYHeadRot(Mth.approachDegrees(soldier.getYHeadRot(), yaw, 12));
        soldier.setXRot(Mth.approachDegrees(soldier.getXRot(), pitch, 8));
        GunIntegration.aim(soldier, true);
        ownsPreAim = true;
        if (state == State.MOVING_TO_PEEK && error <= 8 && GunIntegration.getAimProgress(soldier) >= 0.8f)
            alignedPreAimTicks = Math.min(16, alignedPreAimTicks + 1);
    }

    public boolean isCornerPreAiming() { return ownsPreAim; }

    /** Restore only the known aim after vanilla head/body clamping; never move the body here. */
    public void restoreCornerAim(SoldierEntity soldier) {
        if (!ownsPreAim || preAimPoint == null) return;
        Vec3 delta = preAimPoint.subtract(soldier.getEyePosition());
        soldier.setYHeadRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        soldier.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }

    private boolean hasCornerLane(SoldierEntity soldier, Vec3 feet, LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        Vec3 eye = feet.add(0, soldier.getEyeHeight(), 0);
        if (quickPeek) return com.stevesarmy.combat.ExposureCalculator.hasFreshBulletLaneTo(eye, quickAim, soldier);
        return com.stevesarmy.combat.ExposureCalculator.hasFreshFiringLaneFrom(eye, target);
    }

    
    private long getRandomExposureTime() {
        return EXPOSURE_TIME_MIN_MS + (long)(Math.random() * (EXPOSURE_TIME_MAX_MS - EXPOSURE_TIME_MIN_MS));
    }

    public State getState() { return state; }

    /** Bounds-safe ordinal decode for client renderers. Returns INACTIVE for unknown ordinals. */
    public static State stateFromOrdinal(int ordinal) {
        State[] values = State.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : State.INACTIVE;
    }

    public long getTimeInCurrentState() {
        if (stateStartTime == 0) return 0;
        return GameClock.nowMs() - stateStartTime;
    }

    public long getTimeSinceLastPeek() {
        if (lastPeekEndTime == 0) return Long.MAX_VALUE;
        return GameClock.nowMs() - lastPeekEndTime;
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
        quickPeek = false;
        quickAim = null;
        quickShots = 0;
        setState(soldier, State.HIDING);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        nonPeekableTicks = 0;
        blindPeekTicks = 0;
        currentMaxExposureTime = getRandomExposureTime();
    }

    public void reset() {
        quickPeek = false;
        quickAim = null;
        quickShots = 0;
        ownsPreAim = false;
        alignedPreAimTicks = 0;
        preAimTarget = null;
        preAimPoint = null;
        state = State.INACTIVE;
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        nonPeekableTicks = 0;
        blindPeekTicks = 0;
        currentMaxExposureTime = getRandomExposureTime();
    }

    private void setState(SoldierEntity soldier, State newState) {
        if (newState != State.MOVING_TO_PEEK && newState != State.EXPOSED) clearPreAim(soldier);
        if (soldier != null && newState != State.MOVING_TO_PEEK && newState != State.EXPOSED
            && newState != State.RETURNING_TO_COVER) soldier.setCornerPeekCrouching(false);
        if (newState == State.INACTIVE || newState == State.HIDING || newState == State.STANDING_IN_HALF_COVER) {
            quickPeek = false;
            quickAim = null;
        }
        State previousState = this.state;
        this.state = newState;
        if (soldier != null && !soldier.level().isClientSide) {
            soldier.syncPeekState(newState.ordinal());
            if (previousState != newState) {
                if (com.stevesarmy.debug.trace.SoldierFlightRecorder.isRecording()) {
                    com.stevesarmy.debug.trace.SoldierFlightRecorder.event(soldier, "peek-state", "PeekController.setState",
                        java.util.Map.of("previous", previousState.name(), "current", newState.name()));
                }
                soldier.tracePeek("peek-state", "previous=" + previousState + ", current=" + newState);
            }
        }
    }

    public void resetToInactive(SoldierEntity soldier) {
        setState(soldier, State.INACTIVE);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        nonPeekableTicks = 0;
        blindPeekTicks = 0;
        currentMaxExposureTime = getRandomExposureTime();
    }

    public void resetForNewCover(SoldierEntity soldier, BlockPos coverPosition) {
        reset(soldier);
        if (coverPosition != null && coverPosition.equals(lastCoverPosition)) {
            peekCountSameCover = Math.max(peekCountSameCover, 0);
        } else {
            peekCountSameCover = 0;
        }
        lastCoverPosition = coverPosition;
    }

    @Deprecated
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
            case INACTIVE:
                enterHiding(soldier);
                tickHiding(soldier, cover, mover, allowPeekStart);
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
                CoverPositionController.MovementResult r = mover.getLastResult();
                Vec3 cur = mover.getDebugTargetPos();
                    boolean sameTarget = cur != null && cur.distanceToSqr(returnWaypoint != null ? returnWaypoint : coverReturnTarget) < 1e-4;
                if (r == CoverPositionController.MovementResult.IN_PROGRESS && sameTarget) {
                    mover.reassertControlledReturn();
                    return;
                }
                mover.returnToCoverDuringReload(returnWaypoint != null ? returnWaypoint : coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
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
            boolean pingActive = soldier.hasValidPingSuppressPos();
            if (nonPeekableTicks >= NON_PEEKABLE_REPOSITION_TICKS
                && ((target != null && target.isAlive()) || pingActive)) {
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} non-peekable for {} ticks (target={}, ping={}), requesting reposition",
                        soldier.getId(), nonPeekableTicks, target != null && target.isAlive(), pingActive);
                }
                nonPeekableTicks = 0;
                soldier.getCoverBehaviorManager().setNonPeekableCover(false);
                if (pingActive) {
                    // A ping order must relocate even without a living target,
                    // and the routine channel refuses movement while
                    // suppressed — use the emergency channel instead.
                    soldier.getCoverBehaviorManager().requestContinuousSuppressionReposition();
                } else if (!CoverTacticalGoal.mgRowHoldActive(soldier)) {
                    // Base-of-fire row hold: a docked MG's dock is the row
                    // position and the firing lane, not the sight picture —
                    // the peek system's "useless dock" verdict does not apply
                    // while the rifleman line has not passed him.
                    soldier.getCoverBehaviorManager().requestReposition();
                }
                setIdleState(soldier, cover);
                return;
            }
        } else {
            nonPeekableTicks = 0;
        }

        // An active suppress ping skips the duck cooldown: repeat orders must
        // not stall behind the previous peek cycle.
        boolean suppressPingActive = soldier.hasValidPingSuppressPos();
        if ((!suppressPingActive && (getTimeSinceLastPeek() < DUCK_COOLDOWN_MS
            || GameClock.nowMs() < quickRecoveryUntil)) || !allowPeekStart) {
            return;
        }

        Vec3 threatDir = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());

        if (threatDir == null || threatDir.lengthSqr() <= 0.001) {
            LivingEntity peekTargetEntity = soldier.getTarget();
            if (peekTargetEntity != null && peekTargetEntity.isAlive()) {
                Vec3 toTarget = peekTargetEntity.position().subtract(soldier.position());
                if (toTarget.lengthSqr() > 0.001) {
                    toTarget = toTarget.normalize();
                    if (toTarget.lengthSqr() > 0.001) {
                        threatDir = toTarget;
                    }
                }
            }
        }
        
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
            quickAim = freshQuickAim(soldier, target);
            quickPeek = quickAim != null;
            quickShots = 0;
            if (quickPeek) currentMaxExposureTime = 850;
            liveCornerPeek = target != null && target.isAlive();
            Vec3 targetPos;
            BlockPos peekPos;
            if (liveCornerPeek) {
                Vec3 targetDirection = (quickPeek ? quickAim : target.position()).subtract(soldier.position()).multiply(1, 0, 1).normalize();
                soldier.setCornerPeekCrouching(true);
                targetPos = findCornerOpening(soldier, cover, targetDirection, target);
                if (targetPos == null && !soldier.isLowCrouching()) {
                    // A high window can require the standing eye and box.
                    soldier.setCornerPeekCrouching(false);
                    targetPos = findCornerOpening(soldier, cover, targetDirection, target);
                }
                peekPos = targetPos == null ? null : BlockPos.containing(targetPos);
            } else {
                // Keep direction/ping-only probing when no live target can validate a lane.
                peekPos = CoverTacticalGoal.computePeekPositionStatic(cover, threatDir, target, soldier.level(), soldier.getY());
                targetPos = peekPos == null ? null : new Vec3(peekPos.getX() + 0.5, soldier.getY(), peekPos.getZ() + 0.5);
            }
            if (targetPos == null) {
                soldier.setCornerPeekCrouching(false);
                if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                    StevesArmyMod.LOGGER.info("[PeekController] Soldier {} no valid peek position toward threat",
                        soldier.getId());
                }
                soldier.getCoverBehaviorManager().setNonPeekableCover(true);
                return;
            }

            currentPeekPos = peekPos;
            cornerDirection = (cornerWaypoint != null ? cornerWaypoint : targetPos).subtract(soldier.position()).multiply(1, 0, 1).normalize();
            lostLaneTicks = 0;
            reacquired = false;
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
            mover.moveTo(cornerWaypoint != null && liveCornerPeek ? cornerWaypoint : targetPos, liveCornerPeek ? 0.015 : PEEK_REACHED_DISTANCE,
                liveCornerPeek ? (quickPeek ? QUICK_SPEED : 0.22) : PEEK_SPEED, "PeekController", "slide to peek");
            setState(soldier, State.MOVING_TO_PEEK);
            stateStartTime = GameClock.nowMs();
            if (liveCornerPeek) tickCornerPreAim(soldier);
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
        if (soldier.getCoverBehaviorManager().getSuppressionTracker().isPinned()
            || shouldDuckForSuppression(soldier)) {
            soldier.tracePeek("return-request", "reason=suppression-during-peek-movement");
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} suppressed during peek movement, ducking back",
                    soldier.getId());
            }
            enterReturning(soldier, cover, mover, false);
            return;
        }

        if (quickPeek && quickShots >= 3) {
            enterReturning(soldier, cover, mover, false);
            return;
        }
        CoverPositionController.MovementResult result = mover.getLastResult();

        if (liveCornerPeek) {
            tickCornerPreAim(soldier);
            LivingEntity target = soldier.getTarget();
            if (cornerWaypoint != null && result == CoverPositionController.MovementResult.REACHED_TARGET) {
                cornerWaypoint = null;
                cornerDirection = peekTarget.subtract(soldier.position()).multiply(1, 0, 1).normalize();
                mover.moveTo(peekTarget, 0.015, quickPeek ? QUICK_SPEED : 0.22, "PeekController", "slice after backstep");
                return;
            }
            if ((!quickPeek || result == CoverPositionController.MovementResult.REACHED_TARGET)
                && hasCornerLane(soldier, soldier.position(), target)
                && hasCornerLane(soldier, soldier.position().subtract(cornerDirection.scale(0.025)), target)) {
                enterExposed(soldier, cover);
                return;
            }
            if (getTimeInCurrentState() > 2500 || result == CoverPositionController.MovementResult.FAILED
                || target == null || !target.isAlive()) {
                enterReturning(soldier, cover, mover, false);
                return;
            }
            if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
                // Target motion may close the planned opening. One bounded reacquisition
                // along the chosen side; never reverse sides while outside the dock.
                Vec3 next = peekTarget.add(cornerDirection.scale(0.05));
                Vec3 offset = next.subtract(cornerOrigin);
                if (!reacquired && offset.dot(cornerBack) >= -0.001 && offset.dot(cornerBack) <= 1.001
                    && Math.abs(offset.x * cornerBack.z - offset.z * cornerBack.x) <= 1.001
                    && clearCornerSegment(soldier, soldier.position(), next)
                    && hasCornerLane(soldier, next, target)) {
                    reacquired = true;
                    peekTarget = next;
                    mover.moveTo(next, 0.015, 0.22, "PeekController", "reacquire corner lane");
                } else enterReturning(soldier, cover, mover, false);
            }
            return;
        }

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
            lastPeekEndTime = GameClock.nowMs();
            setIdleState(soldier, cover);
            stateStartTime = 0;
        }
    }

    private void tickExposed(SoldierEntity soldier, CoverPoint cover, CoverPositionController mover) {
        long timeInState = getTimeInCurrentState();

        if (cover.getType() == CoverType.FULL && liveCornerPeek) {
            tickCornerPreAim(soldier);
            if (hasCornerLane(soldier, soldier.position(), soldier.getTarget())) lostLaneTicks = 0;
            else if (++lostLaneTicks >= 3) {
                enterReturning(soldier, cover, mover, false);
                return;
            }
        }
        updateBlindPeekTimer(soldier);

        if (timeInState > currentMaxExposureTime || (quickPeek && quickShots >= 3)) {
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

        if (getTimeInCurrentState() > 4000) {
            double dx = coverReturnTarget.x - soldier.getX();
            double dz = coverReturnTarget.z - soldier.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            soldier.tracePeek("return-timeout", "durationMs=" + getTimeInCurrentState() + ", result=" + result + ", dist=" + String.format("%.2f", dist));
            if (CoverTacticalGoal.isDebugLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PeekController] Soldier {} RETURNING timeout after {}ms (result={}, dist={}), forcing complete",
                    soldier.getId(), getTimeInCurrentState(), result, String.format("%.2f", dist));
            }
            mover.clear();
            if (dist > RETURN_TOLERANCE + 0.5) {
                soldier.getCoverBehaviorManager().requestReposition();
            }
            completeReturn(soldier, cover);
            return;
        }

        if (result == CoverPositionController.MovementResult.REACHED_TARGET) {
            if (returnWaypoint != null) {
                returnWaypoint = null;
                mover.clear();
                if (returnAllowedDuringReload) mover.returnToCoverDuringReload(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "finish backstep return during reload");
                else mover.moveTo(coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED, "PeekController", "finish backstep return");
                return;
            }
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
                mover.returnToCoverDuringReload(returnWaypoint != null ? returnWaypoint : coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover during reload");
            } else {
                mover.moveTo(returnWaypoint != null ? returnWaypoint : coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover");
            }
        }
        // IN_PROGRESS — wait
    }

    private void enterExposed(SoldierEntity soldier, CoverPoint cover) {
        setState(soldier, State.EXPOSED);
        stateStartTime = GameClock.nowMs();
        
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
            && !soldier.getCoverBehaviorManager().isRepositionRequested()
            && !CoverTacticalGoal.mgRowHoldActive(soldier)) {
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
        stateStartTime = GameClock.nowMs();
        returnAllowedDuringReload = allowDuringReload;
        returnWaypoint = liveCornerPeek && soldier.position().subtract(cornerOrigin).dot(cornerBack) > 0.05
            ? cornerOrigin.add(cornerBack.scale(soldier.position().subtract(cornerOrigin).dot(cornerBack))) : null;
        
        boolean isHalf = cover.getType() == CoverType.HALF;
        if (isHalf) {
            // Pose managed by CoverTacticalGoal + SoldierEntity.tick() enforcement
            lockRotationToCoverWall(soldier, cover, soldier.getTarget());
        } else {
            // Full cover: start slide movement back to cover position
            coverReturnTarget = CoverTacticalGoal.getCoverStandingPositionStatic(cover.getPosition());
            if (allowDuringReload) {
                mover.returnToCoverDuringReload(returnWaypoint != null ? returnWaypoint : coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
                    "PeekController", "return to cover during reload");
            } else {
                mover.moveTo(returnWaypoint != null ? returnWaypoint : coverReturnTarget, RETURN_TOLERANCE, RETURN_SPEED,
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
        lastPeekEndTime = GameClock.nowMs();
        if (quickPeek) quickRecoveryUntil = lastPeekEndTime + QUICK_RECOVERY_MS;
        quickPeek = false;
        quickAim = null;
        recordPeekCycle(soldier);
        setIdleState(soldier, cover);
        stateStartTime = 0;
        currentPeekPos = null;
        returnAllowedDuringReload = false;
        soldier.refreshDimensions();

        // Explicitly notify the attack system that a peek cycle completed
        CoverGoalController goalCtrl = soldier.getCoverTacticalGoal();
        if (goalCtrl instanceof CoverTacticalGoal tactical) {
            tactical.onPeekCycleCompleted();
        }
        // Synchronize CoverBehaviorManager timestamp so attack logic reads
        // the real peek-end time instead of a stale zero.
        soldier.getCoverBehaviorManager().setLastPeekEndTime(lastPeekEndTime);

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

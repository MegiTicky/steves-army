package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Machine gunner cover behavior. Runs the base cover logic and additionally
 * evaluates a dedicated firing position for the active suppression center using
 * raycast-verified firing access rather than the rifleman cover scorer. The
 * chosen position is handed to the base goal's relocation branch, which drives
 * the existing cover / prone movement machinery.
 */
public class MachineGunnerSupportGoal extends CoverTacticalGoal {
    private static final int SUPPORT_EVALUATION_INTERVAL = 60;
    private static final int ACTIVE_LANE_VALIDATION_INTERVAL = 5;
    private static final int POSITION_CHANGE_THRESHOLD_SQ = 4;

    private int evaluationCooldown = 0;
    private int laneValidationCooldown = 0;
    private BlockPos lastIssuedDestination = null;

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        super(soldier);
    }

    @Override
    public void tick() {
        if (--laneValidationCooldown <= 0) {
            laneValidationCooldown = ACTIVE_LANE_VALIDATION_INTERVAL;
            validateActiveFiringPosition();
        }
        if (--evaluationCooldown <= 0) {
            evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
            maybeEvaluateSupportPosition();
        }
        super.tick();
        syncMachineGunnerDebug();
    }

    @Override
    public void start() {
        // Select the dedicated lane before the base goal's start method can
        // consume an older ordinary-cover target.
        maybeEvaluateSupportPosition();
        evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
        super.start();
        syncMachineGunnerDebug();
    }

    @Override
    public boolean canUse() {
        if (super.canUse()) {
            return true;
        }
        return hasSupportObjective() || isFiringPositionActive();
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() || hasSupportObjective() || isFiringPositionActive();
    }

    @Override
    protected boolean shouldRunAttackPhase() {
        return !hasSupportObjective() && !isFiringPositionActive();
    }

    @Override
    protected boolean shouldDeferNormalCoverEvaluation() {
        return isFiringPositionOccupied();
    }

    @Override
    protected void onFiringPositionExecutionFailed(FiringPosition position) {
        if (position == null) {
            return;
        }
        if (getActiveFiringPosition() != null
            && getActiveFiringPosition().destination().equals(position.destination())) {
            releaseFiringPositionForFallback();
            lastIssuedDestination = null;
            evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
        }
    }

    private boolean hasSupportObjective() {
        return soldier instanceof MachineGunnerEntity mg
            && mg.getSuppressionCenter() != null
            && !soldier.isHealing()
            && !soldier.isPreparingOrReloading()
            && !soldier.isRecalling();
    }

    private void syncMachineGunnerDebug() {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return;
        }
        FiringPosition active = getActiveFiringPosition();
        soldier.syncMachineGunnerDebug(
            active != null ? active.destination() : null,
            getFiringPositionMovementDestination(),
            mg.getSuppressionCenter(),
            active != null ? active.firingAccess() : 0.0f,
            active != null ? active.posture().ordinal() + 1 : 0,
            isFiringPositionActive() && active != null,
            hasSupportObjective() && !isFiringPositionActive(),
            soldier.getCoverBehaviorManager().isSuppressed());
    }

    private void validateActiveFiringPosition() {
        if (!(soldier instanceof MachineGunnerEntity mg)
            || !isFiringPositionActive() || getActiveFiringPosition() == null) {
            return;
        }
        BlockPos center = mg.getSuppressionCenter();
        FiringPosition active = getActiveFiringPosition();
        if (!isFiringPositionOccupied()) {
            return;
        }
        if (center == null) {
            clearFiringPosition();
            lastIssuedDestination = null;
            return;
        }
        float access = FiringPositionFinder.evaluateFiringAccess(
            mg, center, active.destination(), active.posture());
        updateActiveFiringAccess(access);
        if (access >= FiringPositionFinder.MIN_FIRING_ACCESS) {
            return;
        }
        if (com.stevesarmy.debug.DiagnosticLogManager.isCoverLoggingEnabled()) {
            com.stevesarmy.StevesArmyMod.LOGGER.info(
                "[FiringPosition] MG {} invalid lane={} posture={} access={} suppressed={}, forcing reposition",
                mg.getId(), active.destination(), active.posture(), String.format("%.2f", access),
                soldier.getCoverBehaviorManager().isSuppressed());
        }
        invalidateFiringPosition();
        maybeEvaluateSupportPosition();
    }

    protected void maybeEvaluateSupportPosition() {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return;
        }
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return;
        }
        BlockPos suppressionCenter = mg.getSuppressionCenter();
        BlockPos supportAnchor = SupportPositionFinder.findSupportPosition(mg);
        if (suppressionCenter == null || supportAnchor == null) {
            if (isFiringPositionActive()) {
                clearFiringPosition();
            }
            lastIssuedDestination = null;
            return;
        }

        FiringPosition best = FiringPositionFinder.findBest(mg, suppressionCenter, supportAnchor);
        if (best == null) {
            if (isFiringPositionActive()) {
                clearFiringPosition();
            }
            lastIssuedDestination = null;
            return;
        }

        // Do not churn: keep re-issuing only when the chosen position moved.
        if (isFiringPositionActive() && getActiveFiringPosition() != null && lastIssuedDestination != null
            && lastIssuedDestination.distSqr(best.destination()) < POSITION_CHANGE_THRESHOLD_SQ) {
            return;
        }
        setFiringPosition(best);
        lastIssuedDestination = best.destination();
    }

    /**
     * Aims the base cover scorer at the suppression center when the threat
     * awareness has no direction (area suppression with no living threat), so
     * emergency/defensive cover also faces the suppression area.
     */
    @Override
    protected Vec3 getCoverThreatDirection() {
        Vec3 base = super.getCoverThreatDirection();
        if (base != null && base.lengthSqr() > 0.001) {
            return base;
        }
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return base;
        }
        BlockPos center = mg.getSuppressionCenter();
        if (center == null) {
            return base;
        }
        Vec3 dir = new Vec3(center.getX() + 0.5 - soldier.getX(), 0, center.getZ() + 0.5 - soldier.getZ());
        return dir.lengthSqr() > 0.001 ? dir : base;
    }
}

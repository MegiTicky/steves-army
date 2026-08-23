package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.MachineGunnerEvaluationPacket;
import com.stevesarmy.network.NetworkHandler;
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
    private int debugSyncCooldown = 0;
    private BlockPos lastIssuedDestination = null;
    private BlockPos activeFiringCenter = null;
    private FiringPositionFinder.EvaluationReport latestEvaluationReport = null;
    private BlockPos latestEvaluationCenter = null;
    private BlockPos latestEvaluationAnchor = null;

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        super(soldier);
    }

    @Override
    public void tick() {
        if (--laneValidationCooldown <= 0) {
            laneValidationCooldown = ACTIVE_LANE_VALIDATION_INTERVAL;
            validateActiveFiringPosition();
        }
        BlockPos currentCenter = soldier instanceof MachineGunnerEntity mg ? mg.getSuppressionCenter() : null;
        if (isFiringPositionActive() && (currentCenter == null || !currentCenter.equals(activeFiringCenter))) {
            evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
            maybeEvaluateSupportPosition();
        } else if (--evaluationCooldown <= 0) {
            evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
            maybeEvaluateSupportPosition();
        }
        if (--debugSyncCooldown <= 0) {
            debugSyncCooldown = SUPPORT_EVALUATION_INTERVAL;
            sendLatestEvaluationDebug();
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
        // A dedicated firing lane owns cover selection from the moment it is
        // selected until it explicitly fails or is cleared. Waiting until the
        // lane is occupied lets generic cover scoring replace it mid-movement.
        return isFiringPositionActive();
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
            activeFiringCenter = null;
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
        // Defensive suppression owns an occupied physical half-cover position.
        // Do not invalidate that position and immediately issue a replacement
        // lane before CoverTacticalGoal can force the normal duck-back posture.
        CoverPoint currentCover = soldier.getCoverBehaviorManager().getCurrentCover();
        if (soldier.getCoverBehaviorManager().isSuppressed()
            && active.posture() == FiringPosition.FiringPosture.COVER_PEEK
            && currentCover != null && currentCover.getType() == CoverType.HALF) {
            return;
        }
        if (center == null) {
            clearFiringPosition();
            lastIssuedDestination = null;
            activeFiringCenter = null;
            return;
        }
        FiringPositionFinder.ConfirmedFiringAccess validation = FiringPositionFinder.evaluateConfirmedFiringAccess(
            mg, center, active.destination(), active.posture());
        if (!validation.hasConfirmedTargets()) {
            return;
        }
        float access = validation.access();
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
        CoverPoint currentCover = soldier.getCoverBehaviorManager().getCurrentCover();
        if (soldier.getCoverBehaviorManager().isSuppressed()
            && currentCover != null && currentCover.getType() == CoverType.HALF) {
            // Keep the ordinary soldier suppression flow in charge until the
            // current half cover has recovered; the lane can be evaluated then.
            return;
        }
        if (suppressionCenter == null || supportAnchor == null) {
            if (isFiringPositionActive()) {
                clearFiringPosition();
            }
            lastIssuedDestination = null;
            activeFiringCenter = null;
            return;
        }

        // A valid occupied or in-progress lane owns the unchanged objective.
        // Do not re-score it periodically just because predicted peek samples or
        // nearby squad members changed after the lane was selected.
        if (isFiringPositionActive() && suppressionCenter.equals(activeFiringCenter)) {
            return;
        }

        FiringPositionFinder.EvaluationReport report = FiringPositionFinder.evaluate(
            mg, suppressionCenter, supportAnchor);
        latestEvaluationReport = report;
        latestEvaluationCenter = suppressionCenter.immutable();
        latestEvaluationAnchor = supportAnchor.immutable();
        sendLatestEvaluationDebug();
        FiringPosition best = report.selected();
        if (best == null) {
            if (isFiringPositionActive()) {
                clearFiringPosition();
            }
            lastIssuedDestination = null;
            activeFiringCenter = null;
            return;
        }

        // Do not churn: keep re-issuing only when the chosen position moved.
        if (isFiringPositionActive() && getActiveFiringPosition() != null && lastIssuedDestination != null
            && lastIssuedDestination.distSqr(best.destination()) < POSITION_CHANGE_THRESHOLD_SQ) {
            return;
        }
        setFiringPosition(best);
        lastIssuedDestination = best.destination();
        activeFiringCenter = suppressionCenter.immutable();
    }

    /** Forces the same firing-lane evaluation used by the support AI. */
    public FiringPositionFinder.EvaluationReport forceEvaluateSupportPosition() {
        MachineGunnerEntity mg = (MachineGunnerEntity) soldier;
        BlockPos suppressionCenter = mg.getSuppressionCenter();
        BlockPos supportAnchor = SupportPositionFinder.findSupportPosition(mg);
        FiringPositionFinder.EvaluationReport report = FiringPositionFinder.evaluate(
            mg, suppressionCenter, supportAnchor);
        latestEvaluationReport = report;
        latestEvaluationCenter = suppressionCenter != null ? suppressionCenter.immutable() : null;
        latestEvaluationAnchor = supportAnchor != null ? supportAnchor.immutable() : null;
        FiringPosition best = report.selected();
        if (best == null) {
            clearFiringPosition();
            lastIssuedDestination = null;
            activeFiringCenter = null;
        } else {
            // The debug command is an explicit reposition request, not merely a
            // preview. Drop the current lane so the selected result is consumed
            // by the normal support-relocation branch.
            invalidateFiringPosition();
            setFiringPosition(best);
            lastIssuedDestination = best.destination();
            activeFiringCenter = suppressionCenter != null ? suppressionCenter.immutable() : null;
        }
        evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
        laneValidationCooldown = ACTIVE_LANE_VALIDATION_INTERVAL;
        return report;
    }

    private void sendLatestEvaluationDebug() {
        if (!(soldier instanceof MachineGunnerEntity mg) || latestEvaluationReport == null) {
            return;
        }
        NetworkHandler.sendToTracking(mg, MachineGunnerEvaluationPacket.from(
            mg.getId(), latestEvaluationCenter, latestEvaluationAnchor, latestEvaluationReport,
            latestEvaluationReport.selected() != null ? "live evaluation" : "no firing lane"));
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

package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.MachineGunnerEvaluationPacket;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.SquadCoverContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Machine-gunner cover selection policy.
 *
 * The base CoverTacticalGoal remains the only movement/state owner. This class
 * only ranks ordinary cover candidates around the adaptive support anchor and
 * leaves reservation, navigation, controller handoff, retries, and arrival to
 * the inherited cover pipeline.
 */
public class MachineGunnerSupportGoal extends CoverTacticalGoal {
    private static final double ACCESS_WEIGHT = 2.5;
    private static final double ANCHOR_DISTANCE_WEIGHT = 0.05;
    private static final double SELECTED_LANE_BONUS = 1.0;

    @Nullable
    private FiringPositionFinder.EvaluationReport latestEvaluationReport;
    @Nullable
    private BlockPos latestEvaluationCenter;
    @Nullable
    private BlockPos latestEvaluationAnchor;

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        super(soldier);
    }

    @Override
    protected Optional<CoverPoint> selectRoleSpecificCover(
        CoverFinder finder, BlockPos searchCenter, int searchRadius,
        Vec3 threatDirection, List<LivingEntity> threats,
        SquadCoverContext squadCtx, List<CoverFinder.ScoredCover> baseCandidates) {
        if (!(soldier instanceof MachineGunnerEntity mg)
            || soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return Optional.empty();
        }

        BlockPos suppressionCenter = mg.getSuppressionCenter();
        BlockPos supportAnchor = SupportPositionFinder.findSupportPosition(mg);
        if (suppressionCenter == null || supportAnchor == null) {
            return Optional.empty();
        }

        latestEvaluationReport = FiringPositionFinder.evaluate(mg, suppressionCenter, supportAnchor);
        latestEvaluationCenter = suppressionCenter.immutable();
        latestEvaluationAnchor = supportAnchor.immutable();
        sendLatestEvaluationDebug(mg);

        List<CoverFinder.ScoredCover> candidates = finder.evaluateAndScoreAllFromCenter(
            supportAnchor, soldier, threatDirection, threats,
            Math.max(searchRadius, FiringPositionFinder.SEARCH_RADIUS), squadCtx);
        CoverPoint currentCover = soldier.getCoverBehaviorManager().getCurrentCover();
        FiringPosition selectedLane = latestEvaluationReport.selected();

        Optional<CoverFinder.ScoredCover> selected = candidates.stream()
            .filter(sc -> !isCoverBlacklisted(sc.cover.getPosition()))
            .filter(sc -> CoverReservationManager.isAvailableFor(sc.cover.getPosition(), soldier))
            .filter(sc -> currentCover == null
                || !currentCover.getPosition().equals(sc.cover.getPosition()))
            .filter(sc -> isExactCoverPathReachable(sc.cover))
            .max(Comparator.comparingDouble(sc -> supportScore(
                sc, selectedLane, suppressionCenter, supportAnchor)));

        if (selected.isEmpty()) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info(
                    "[MGState] soldier={} event=role-cover-fallback center={} anchor={} baseCandidates={}",
                    soldier.getId(), suppressionCenter, supportAnchor, baseCandidates.size());
            }
            return Optional.empty();
        }

        CoverFinder.ScoredCover result = selected.get();
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info(
                "[MGState] soldier={} event=role-cover-selected target={} center={} anchor={} score={} baseScore={} lane={}",
                soldier.getId(), result.cover.getPosition(), suppressionCenter, supportAnchor,
                format(supportScore(result, selectedLane, suppressionCenter, supportAnchor)),
                format(result.score), selectedLane != null ? selectedLane.destination() : "none");
        }
        return Optional.of(result.cover);
    }

    private double supportScore(CoverFinder.ScoredCover candidate,
                                @Nullable FiringPosition selectedLane,
                                BlockPos suppressionCenter,
                                BlockPos supportAnchor) {
        BlockPos destination = candidate.cover.getPosition();
        double access = 0.0;
        FiringPositionFinder.ConfirmedFiringAccess confirmed =
            FiringPositionFinder.evaluateConfirmedFiringAccess(
                soldier, suppressionCenter, destination, FiringPosition.FiringPosture.COVER_PEEK);
        if (confirmed.hasConfirmedTargets()) {
            access = confirmed.access();
        }
        double laneBonus = selectedLane != null && destination.equals(selectedLane.destination())
            ? SELECTED_LANE_BONUS : 0.0;
        double distance = Math.sqrt(destination.distSqr(supportAnchor));
        return candidate.score + ACCESS_WEIGHT * access + laneBonus
            - ANCHOR_DISTANCE_WEIGHT * distance;
    }

    /** Forces a read-only evaluation for the existing debug command. */
    public FiringPositionFinder.EvaluationReport forceEvaluateSupportPosition() {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return FiringPositionFinder.evaluate(null, null, null);
        }
        BlockPos suppressionCenter = mg.getSuppressionCenter();
        BlockPos supportAnchor = SupportPositionFinder.findSupportPosition(mg);
        latestEvaluationReport = FiringPositionFinder.evaluate(mg, suppressionCenter, supportAnchor);
        latestEvaluationCenter = suppressionCenter != null ? suppressionCenter.immutable() : null;
        latestEvaluationAnchor = supportAnchor != null ? supportAnchor.immutable() : null;
        sendLatestEvaluationDebug(mg);
        return latestEvaluationReport;
    }

    private void sendLatestEvaluationDebug(MachineGunnerEntity mg) {
        if (latestEvaluationReport == null) {
            return;
        }
        NetworkHandler.sendToTracking(mg, MachineGunnerEvaluationPacket.from(
            mg.getId(), latestEvaluationCenter, latestEvaluationAnchor, latestEvaluationReport,
            latestEvaluationReport.selected() != null ? "role cover evaluation" : "no firing lane"));
    }

    /**
     * Preserve the MG's area-suppression facing input without giving the role
     * any movement ownership. When threat awareness has no directional sample,
     * ordinary cover scoring should still protect the sticky suppression sector.
     */
    @Override
    @Nullable
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
        Vec3 direction = new Vec3(
            center.getX() + 0.5 - soldier.getX(),
            0.0,
            center.getZ() + 0.5 - soldier.getZ());
        return direction.lengthSqr() > 0.001 ? direction : base;
    }

    @Nullable
    @Override
    protected Float getMachineGunnerDebugAccess() {
        FiringPosition selected = getLatestSelectedLane();
        return selected != null ? selected.firingAccess() : null;
    }

    @Nullable
    @Override
    protected FiringPosition getMachineGunnerDebugLane() {
        return getLatestSelectedLane();
    }

    @Override
    protected int getMachineGunnerDebugPosture() {
        FiringPosition selected = getLatestSelectedLane();
        return selected == null ? 0 : selected.posture().ordinal() + 1;
    }

    @Nullable
    private FiringPosition getLatestSelectedLane() {
        if (!(soldier instanceof MachineGunnerEntity mg)
            || latestEvaluationReport == null
            || latestEvaluationCenter == null) {
            return null;
        }
        BlockPos currentCenter = mg.getSuppressionCenter();
        if (currentCenter == null || !currentCenter.equals(latestEvaluationCenter)) {
            return null;
        }
        return latestEvaluationReport.selected();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

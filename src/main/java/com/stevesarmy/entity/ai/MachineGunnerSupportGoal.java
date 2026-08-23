package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.DefensivePositionCandidate;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    protected boolean isRoleSpecificSupportOrderActive() {
        return soldier instanceof MachineGunnerEntity mg && mg.isAttackSupportActive();
    }

    @Override
    @Nullable
    protected BlockPos getRoleSpecificSuppressionCenter() {
        return soldier instanceof MachineGunnerEntity mg ? mg.getSuppressionCenter() : null;
    }

    @Override
    protected boolean isRoleSpecificPositionPolicyActive() {
        return soldier instanceof MachineGunnerEntity mg && mg.getSuppressionCenter() != null;
    }

    @Override
    protected boolean isRoleSpecificCoverAllowed(CoverFinder finder, CoverPoint cover,
                                                 @Nullable Vec3 threatDirection) {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return true;
        }
        BlockPos suppressionCenter = mg.getSuppressionCenter();
        if (suppressionCenter == null) {
            return true;
        }
        Vec3 suppressionDirection = directionToSuppression(cover, suppressionCenter);
        if (!finder.isDirectionProtected(cover, suppressionDirection)) {
            return false;
        }
        return FiringPositionFinder.evaluateFiringAccess(
            mg, suppressionCenter, cover.getPosition(), FiringPosition.FiringPosture.COVER_PEEK)
            >= FiringPositionFinder.MIN_FIRING_ACCESS;
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
            clearLatestEvaluationDebug(mg, suppressionCenter == null
                ? "suppression center unavailable" : "support anchor unavailable");
            return Optional.empty();
        }

        FiringPositionFinder.EvaluationReport evaluation = evaluateSupport(mg, suppressionCenter, supportAnchor);
        FiringPosition selectedLane = evaluation.selected();
        if (selectedLane != null && selectedLane.posture() == FiringPosition.FiringPosture.OPEN_PRONE) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info(
                    "[MGState] soldier={} event=role-cover-skipped reason=open-prone-scored-higher target={} center={} anchor={}",
                    soldier.getId(), selectedLane.destination(), suppressionCenter, supportAnchor);
            }
            return Optional.empty();
        }

        List<CoverFinder.ScoredCover> candidates = finder.evaluateAndScoreAllFromCenter(
            supportAnchor, soldier, threatDirection, threats,
            Math.max(searchRadius, FiringPositionFinder.SEARCH_RADIUS), squadCtx);
        CoverPoint currentCover = soldier.getCoverBehaviorManager().getCurrentCover();
        Set<BlockPos> noPhysicalPath = new HashSet<>();
        latestEvaluationReport.pathChecks().stream()
            .filter(check -> !check.pathExists())
            .map(check -> check.position().destination())
            .forEach(noPhysicalPath::add);

        Map<BlockPos, FiringPosition> validFiringPositions = new HashMap<>();
        latestEvaluationReport.pathChecks().stream()
            .filter(check -> check.position().posture() == FiringPosition.FiringPosture.COVER_PEEK)
            .filter(check -> check.pathExists() && check.canReach())
            .forEach(check -> validFiringPositions.put(check.position().destination(), check.position()));

        List<CoverFinder.ScoredCover> protectedCandidates = new ArrayList<>();
        candidates.stream()
            .filter(sc -> validFiringPositions.containsKey(sc.cover.getPosition()))
            .filter(sc -> !noPhysicalPath.contains(sc.cover.getPosition()))
            .filter(sc -> !isCoverBlacklisted(sc.cover.getPosition()))
            .filter(sc -> CoverReservationManager.isAvailableFor(sc.cover.getPosition(), soldier))
            .filter(sc -> currentCover == null
                || !currentCover.getPosition().equals(sc.cover.getPosition()))
            .filter(sc -> isExactCoverPathReachable(sc.cover))
            .filter(sc -> finder.isDirectionProtected(sc.cover,
                directionToSuppression(sc.cover, suppressionCenter)))
            .forEach(protectedCandidates::add);
        baseCandidates.stream()
            .filter(sc -> validFiringPositions.containsKey(sc.cover.getPosition()))
            .filter(sc -> !noPhysicalPath.contains(sc.cover.getPosition()))
            .filter(sc -> !isCoverBlacklisted(sc.cover.getPosition()))
            .filter(sc -> CoverReservationManager.isAvailableFor(sc.cover.getPosition(), soldier))
            .filter(sc -> currentCover == null
                || !currentCover.getPosition().equals(sc.cover.getPosition()))
            .filter(sc -> isExactCoverPathReachable(sc.cover))
            .filter(sc -> finder.isDirectionProtected(sc.cover,
                directionToSuppression(sc.cover, suppressionCenter)))
            .forEach(protectedCandidates::add);

        Optional<CoverFinder.ScoredCover> selected = protectedCandidates.stream()
            .max(Comparator.comparingDouble(sc -> supportScore(
                sc, validFiringPositions.get(sc.cover.getPosition()), selectedLane,
                suppressionCenter, supportAnchor)));

        if (selected.isEmpty()) {
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info(
                    "[MGState] soldier={} event=role-cover-no-valid-firing-position center={} anchor={} evaluated={} protected={}",
                    soldier.getId(), suppressionCenter, supportAnchor,
                    validFiringPositions.size(), protectedCandidates.size());
            }
            return Optional.empty();
        }

        CoverFinder.ScoredCover result = selected.get();
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info(
                "[MGState] soldier={} event=role-cover-selected target={} center={} anchor={} score={} baseScore={} lane={}",
                soldier.getId(), result.cover.getPosition(), suppressionCenter, supportAnchor,
                format(supportScore(result, validFiringPositions.get(result.cover.getPosition()),
                    selectedLane, suppressionCenter, supportAnchor)),
                format(result.score), selectedLane != null ? selectedLane.destination() : "none");
        }
        return Optional.of(result.cover);
    }

    @Override
    protected Optional<DefensivePositionCandidate.ProneFiringCandidate> selectRoleSpecificProne(
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

        FiringPositionFinder.EvaluationReport evaluation = evaluateSupport(mg, suppressionCenter, supportAnchor);
        if (evaluation.selected() == null
            || evaluation.selected().posture() != FiringPosition.FiringPosture.OPEN_PRONE) {
            return Optional.empty();
        }
        Optional<DefensivePositionCandidate.ProneFiringCandidate> prone = evaluation.candidates().stream()
            .filter(candidate -> candidate.posture() == FiringPosition.FiringPosture.OPEN_PRONE)
            .filter(candidate -> evaluation.pathChecks().stream().anyMatch(check ->
                check.position().equals(candidate) && check.pathExists() && check.canReach()))
            .max(Comparator.comparingDouble(FiringPosition::score))
            .map(candidate -> new DefensivePositionCandidate.ProneFiringCandidate(
                candidate.destination(), candidate.firingAccess(), candidate.protection(),
                0.0f, (float) Math.sqrt(candidate.destination().distSqr(soldier.blockPosition())),
                "mg-open-prone-evaluator"));
        if (prone.isPresent() && DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info(
                "[MGState] soldier={} event=role-prone-candidate target={} center={} anchor={} access={} protection={}",
                soldier.getId(), prone.get().destination(), suppressionCenter, supportAnchor,
                String.format(Locale.ROOT, "%.2f", prone.get().firingAccess()),
                String.format(Locale.ROOT, "%.2f", prone.get().protection()));
        }
        return prone;
    }

    private static Vec3 directionToSuppression(CoverPoint cover, BlockPos suppressionCenter) {
        BlockPos position = cover.getPosition();
        return new Vec3(
            suppressionCenter.getX() + 0.5 - (position.getX() + 0.5),
            0.0,
            suppressionCenter.getZ() + 0.5 - (position.getZ() + 0.5));
    }

    private FiringPositionFinder.EvaluationReport evaluateSupport(
        MachineGunnerEntity mg, BlockPos suppressionCenter, BlockPos supportAnchor) {
        if (latestEvaluationReport == null
            || latestEvaluationCenter == null || !latestEvaluationCenter.equals(suppressionCenter)
            || latestEvaluationAnchor == null || !latestEvaluationAnchor.equals(supportAnchor)) {
            latestEvaluationReport = FiringPositionFinder.evaluate(mg, suppressionCenter, supportAnchor);
            latestEvaluationCenter = suppressionCenter.immutable();
            latestEvaluationAnchor = supportAnchor.immutable();
            sendLatestEvaluationDebug(mg);
        }
        return latestEvaluationReport;
    }

    private double supportScore(CoverFinder.ScoredCover candidate,
                                @Nullable FiringPosition firingPosition,
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
        double firingScore = firingPosition != null ? firingPosition.score() : candidate.score;
        return firingScore + ACCESS_WEIGHT * access + laneBonus
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
        sendLatestEvaluationDebug(mg, latestEvaluationReport.selected() != null
            ? "role cover evaluation" : "no firing lane");
        return latestEvaluationReport;
    }

    private void sendLatestEvaluationDebug(MachineGunnerEntity mg) {
        sendLatestEvaluationDebug(mg, latestEvaluationReport != null && latestEvaluationReport.selected() != null
            ? "role cover evaluation" : "no firing lane");
    }

    private void clearLatestEvaluationDebug(MachineGunnerEntity mg, String reason) {
        latestEvaluationReport = FiringPositionFinder.evaluate(mg, null, null);
        latestEvaluationCenter = null;
        latestEvaluationAnchor = null;
        sendLatestEvaluationDebug(mg, reason);
    }

    private void sendLatestEvaluationDebug(MachineGunnerEntity mg, String failure) {
        if (latestEvaluationReport == null) {
            return;
        }
        NetworkHandler.sendToTracking(mg, MachineGunnerEvaluationPacket.from(
            mg.getId(), latestEvaluationCenter, latestEvaluationAnchor, latestEvaluationReport,
            failure));
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
        BlockPos currentAnchor = SupportPositionFinder.findSupportPosition(mg);
        if (currentCenter == null || currentAnchor == null
            || !currentCenter.equals(latestEvaluationCenter)
            || latestEvaluationAnchor == null
            || !currentAnchor.equals(latestEvaluationAnchor)) {
            clearLatestEvaluationDebug(mg, currentCenter == null
                ? "suppression center unavailable" : currentAnchor == null
                    ? "support anchor unavailable" : "support geometry changed");
            return null;
        }
        return latestEvaluationReport.selected();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}

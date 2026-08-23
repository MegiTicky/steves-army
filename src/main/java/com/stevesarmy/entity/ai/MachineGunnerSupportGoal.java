package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.AsyncCoverEvaluationManager;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.MachineGunnerEvaluationPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standalone machine-gunner support goal. Generic cover movement is delegated
 * to a role-neutral controller instance; MG support positioning and firing-lane
 * evaluation never enter the rifleman goal selector or rifleman hot path.
 */
public final class MachineGunnerSupportGoal extends Goal implements CoverGoalController {
    private static final double ACCESS_WEIGHT = 2.5;
    private static final double ANCHOR_DISTANCE_WEIGHT = 0.05;
    private static final double SELECTED_LANE_BONUS = 1.0;
    private static final int SEARCH_RADIUS = 12;
    private static final int SELECTION_RETRY_TICKS = 20;

    private final MachineGunnerEntity soldier;
    private final CoverTacticalGoal coverController;
    private int selectionRetryTicks;
    private long lastSelectionRevision = Long.MIN_VALUE;
    private long lastSelectionSuppressionSequence = Long.MIN_VALUE;
    private long lastSelectionSectorGeneration = Long.MIN_VALUE;
    private BlockPos lastSelectionCenter;
    private BlockPos lastSelectionAnchor;

    @Nullable
    private FiringPositionFinder.EvaluationReport latestEvaluationReport;
    @Nullable
    private BlockPos latestEvaluationCenter;
    @Nullable
    private BlockPos latestEvaluationAnchor;
    private long latestEvaluationRevision = Long.MIN_VALUE;
    private long latestEvaluationSuppressionSequence = Long.MIN_VALUE;
    private long latestEvaluationSectorGeneration = Long.MIN_VALUE;
    private boolean evaluationPending;

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        if (!(soldier instanceof MachineGunnerEntity machineGunner)) {
            throw new IllegalArgumentException("MachineGunnerSupportGoal requires a machine gunner");
        }
        this.soldier = machineGunner;
        this.coverController = new CoverTacticalGoal(soldier, true);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (soldier.isHealing()) {
            return false;
        }
        prepareSupportSelection();
        return coverController.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return !soldier.isHealing() && coverController.canContinueToUse();
    }

    @Override
    public void start() {
        prepareSupportSelection();
        coverController.start();
    }

    @Override
    public void stop() {
        coverController.stop();
        evaluationPending = false;
        selectionRetryTicks = 0;
    }

    @Override
    public void tick() {
        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        if (manager.getTargetCover() == null && manager.isSeekingCover()) {
            prepareSupportSelection();
        }
        coverController.tick();
        syncDebugState();
    }

    private void prepareSupportSelection() {
        if (selectionRetryTicks > 0) {
            selectionRetryTicks--;
        }

        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        if (manager.getCurrentCover() != null && manager.getTargetCover() != null) {
            return;
        }

        BlockPos suppressionCenter = soldier.getSuppressionCenter();
        BlockPos supportAnchor = SupportPositionFinder.findSupportPosition(soldier);
        if (suppressionCenter == null || supportAnchor == null) {
            return;
        }

        long revision = manager.getTacticalRevision();
        long suppressionSequence = manager.getSuppressionTracker().getSuppressionEventSequence();
        long sectorGeneration = soldier.getSuppressionSectorGeneration();
        boolean contextChanged = lastSelectionCenter == null
            || !lastSelectionCenter.equals(suppressionCenter)
            || lastSelectionAnchor == null || !lastSelectionAnchor.equals(supportAnchor)
            || lastSelectionRevision != revision
            || lastSelectionSuppressionSequence != suppressionSequence
            || lastSelectionSectorGeneration != sectorGeneration;
        if (!contextChanged && selectionRetryTicks > 0) {
            return;
        }

        lastSelectionCenter = suppressionCenter.immutable();
        lastSelectionAnchor = supportAnchor.immutable();
        lastSelectionRevision = revision;
        lastSelectionSuppressionSequence = suppressionSequence;
        lastSelectionSectorGeneration = sectorGeneration;

        FiringPositionFinder.EvaluationReport evaluation = evaluateSupport(
            suppressionCenter, supportAnchor, revision, suppressionSequence, sectorGeneration);
        if (evaluation.selected() == null
            || evaluation.selected().posture() != FiringPosition.FiringPosture.COVER_PEEK) {
            selectionRetryTicks = SELECTION_RETRY_TICKS;
            return;
        }

        CoverPoint selected = selectPhysicalSupportCover(
            suppressionCenter, supportAnchor, evaluation);
        if (selected != null && (manager.getCurrentCover() == null
            || !manager.getCurrentCover().getPosition().equals(selected.getPosition()))) {
            manager.setTargetCover(selected);
            if (!manager.isInCover()) {
                manager.setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
            }
        } else {
            selectionRetryTicks = SELECTION_RETRY_TICKS;
        }
    }

    @Nullable
    private CoverPoint selectPhysicalSupportCover(BlockPos suppressionCenter,
                                                  BlockPos supportAnchor,
                                                  FiringPositionFinder.EvaluationReport evaluation) {
        Vec3 threatDirection = directionBetween(supportAnchor, suppressionCenter);
        List<LivingEntity> threats = new ArrayList<>(soldier.getCombatGoal().getPotentialTargets());
        LivingEntity target = soldier.getTarget();
        if (target != null && target.isAlive() && !threats.contains(target)) {
            threats.add(0, target);
        }

        Set<BlockPos> reachablePositions = new HashSet<>();
        Map<BlockPos, FiringPosition> firingPositions = new HashMap<>();
        for (FiringPositionFinder.CandidateDiagnostic check : evaluation.pathChecks()) {
            if (check.position().posture() == FiringPosition.FiringPosture.COVER_PEEK
                && check.pathExists() && check.canReach()) {
                reachablePositions.add(check.position().destination());
                firingPositions.put(check.position().destination(), check.position());
            }
        }
        if (reachablePositions.isEmpty()) {
            return null;
        }

        CoverFinder finder = new CoverFinder(soldier.level());
        PerformanceMetrics.recordRoleCoverSearch(true);
        List<CoverFinder.ScoredCover> candidates = finder.findTopCovers(
            soldier, threatDirection, threats, Math.max(SEARCH_RADIUS, FiringPositionFinder.SEARCH_RADIUS), 12, true);
        CoverPoint current = soldier.getCoverBehaviorManager().getCurrentCover();
        CoverFinder.ScoredCover best = candidates.stream()
            .filter(candidate -> reachablePositions.contains(candidate.cover.getPosition()))
            .filter(candidate -> current == null
                || !current.getPosition().equals(candidate.cover.getPosition()))
            .filter(candidate -> CoverReservationManager.isAvailableFor(candidate.cover.getPosition(), soldier))
            .filter(candidate -> finder.isDirectionProtected(candidate.cover,
                directionBetween(candidate.cover.getPosition(), suppressionCenter)))
            .max(Comparator.comparingDouble(candidate -> supportScore(
                candidate, firingPositions.get(candidate.cover.getPosition()), evaluation.selected(), supportAnchor)))
            .orElse(null);
        return best != null ? best.cover : null;
    }

    private double supportScore(CoverFinder.ScoredCover candidate,
                                @Nullable FiringPosition firingPosition,
                                @Nullable FiringPosition selectedLane,
                                BlockPos supportAnchor) {
        BlockPos destination = candidate.cover.getPosition();
        double confirmedAccess = FiringPositionFinder.evaluateConfirmedFiringAccess(
            soldier, soldier.getSuppressionCenter(), destination,
            FiringPosition.FiringPosture.COVER_PEEK).access();
        double laneBonus = selectedLane != null && destination.equals(selectedLane.destination())
            ? SELECTED_LANE_BONUS : 0.0;
        double distance = Math.sqrt(destination.distSqr(supportAnchor));
        double firingScore = firingPosition != null ? firingPosition.score() : candidate.score;
        return firingScore + ACCESS_WEIGHT * confirmedAccess + laneBonus
            - ANCHOR_DISTANCE_WEIGHT * distance;
    }

    private FiringPositionFinder.EvaluationReport evaluateSupport(
        BlockPos suppressionCenter, BlockPos supportAnchor, long revision,
        long suppressionSequence, long sectorGeneration) {
        if (latestEvaluationReport != null
            && suppressionCenter.equals(latestEvaluationCenter)
            && supportAnchor.equals(latestEvaluationAnchor)
            && revision == latestEvaluationRevision
            && suppressionSequence == latestEvaluationSuppressionSequence
            && sectorGeneration == latestEvaluationSectorGeneration) {
            return latestEvaluationReport;
        }

        if (StevesArmyConfig.useAsyncCoverEvaluation()) {
            if (!evaluationPending) {
                evaluationPending = true;
                AsyncCoverEvaluationManager.request(soldier, suppressionCenter, supportAnchor,
                    revision, suppressionSequence, sectorGeneration, (snapshot, report) -> {
                        evaluationPending = false;
                        latestEvaluationReport = report;
                        latestEvaluationCenter = snapshot.suppressionCenter();
                        latestEvaluationAnchor = snapshot.supportAnchor();
                        latestEvaluationRevision = snapshot.tacticalRevision();
                        latestEvaluationSuppressionSequence = snapshot.suppressionSequence();
                        latestEvaluationSectorGeneration = snapshot.sectorGeneration();
                        sendLatestEvaluationDebug(report);
                    });
            }
            return FiringPositionFinder.emptyEvaluationReport();
        }

        latestEvaluationReport = FiringPositionFinder.evaluate(soldier, suppressionCenter, supportAnchor);
        latestEvaluationCenter = suppressionCenter.immutable();
        latestEvaluationAnchor = supportAnchor.immutable();
        latestEvaluationRevision = revision;
        latestEvaluationSuppressionSequence = suppressionSequence;
        latestEvaluationSectorGeneration = sectorGeneration;
        sendLatestEvaluationDebug(latestEvaluationReport);
        return latestEvaluationReport;
    }

    private void sendLatestEvaluationDebug(FiringPositionFinder.EvaluationReport report) {
        if (!DiagnosticLogManager.isCoverLoggingEnabled()) {
            return;
        }
        NetworkHandler.sendToTracking(soldier, MachineGunnerEvaluationPacket.from(
            soldier.getId(), latestEvaluationCenter, latestEvaluationAnchor, report,
            report.selected() != null ? "standalone support evaluation" : "no firing lane"));
    }

    private void syncDebugState() {
        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        CoverPoint target = manager.getTargetCover();
        CoverPoint current = manager.getCurrentCover();
        BlockPos authoritative = target != null ? target.getPosition()
            : current != null ? current.getPosition() : null;
        FiringPosition lane = getLatestSelectedLane();
        boolean laneMatches = lane != null && authoritative != null
            && lane.posture() == FiringPosition.FiringPosture.COVER_PEEK
            && lane.destination().equals(authoritative);
        soldier.syncMachineGunnerDebug(
            authoritative,
            authoritative,
            soldier.getSuppressionCenter(),
            laneMatches ? lane.firingAccess() : 0.0f,
            laneMatches ? lane.posture().ordinal() + 1 : 0,
            laneMatches,
            authoritative != null && !laneMatches,
            manager.isSuppressed());
    }

    private static Vec3 directionBetween(BlockPos origin, BlockPos destination) {
        Vec3 direction = new Vec3(
            destination.getX() + 0.5 - (origin.getX() + 0.5),
            0.0,
            destination.getZ() + 0.5 - (origin.getZ() + 0.5));
        return direction.lengthSqr() > 0.001 ? direction : new Vec3(0.0, 0.0, 1.0);
    }

    public FiringPositionFinder.EvaluationReport forceEvaluateSupportPosition() {
        BlockPos center = soldier.getSuppressionCenter();
        BlockPos anchor = SupportPositionFinder.findSupportPosition(soldier);
        if (center == null || anchor == null) {
            latestEvaluationReport = FiringPositionFinder.emptyEvaluationReport();
            return latestEvaluationReport;
        }
        latestEvaluationReport = FiringPositionFinder.evaluate(soldier, center, anchor);
        latestEvaluationCenter = center.immutable();
        latestEvaluationAnchor = anchor.immutable();
        sendLatestEvaluationDebug(latestEvaluationReport);
        return latestEvaluationReport;
    }

    @Nullable
    public FiringPosition getLatestSelectedLane() {
        return latestEvaluationReport != null ? latestEvaluationReport.selected() : null;
    }

    @Override
    public boolean requestGoToRelocation(BlockPos destination, int commandGeneration) {
        return coverController.requestGoToRelocation(destination, commandGeneration);
    }

    @Override
    public boolean isHandlingGoToRelocation(int commandGeneration) {
        return coverController.isHandlingGoToRelocation(commandGeneration);
    }

    @Override
    @Nullable
    public BlockPos getProneDefensivePosition() {
        return coverController.getProneDefensivePosition();
    }
}

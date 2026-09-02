package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Machine-gunner cover goal. All state machine, movement, ping, peek, and
 * suppression behavior is delegated to the rifleman CoverTacticalGoal. This
 * shell exists only to keep the MG's cover pipeline a distinct goal instance
 * and to carry MG debug rendering; MG-specific cover selection is layered on
 * later as an isolated addition.
 */
public final class MachineGunnerSupportGoal extends Goal implements CoverGoalController {
    private final MachineGunnerEntity soldier;
    private final CoverTacticalGoal coverController;

    @Nullable
    private FiringPositionFinder.EvaluationReport latestDebugReport;

    public CoverTacticalGoal getCoverController() {
        return coverController;
    }

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        if (!(soldier instanceof MachineGunnerEntity machineGunner)) {
            throw new IllegalArgumentException("MachineGunnerSupportGoal requires a machine gunner");
        }
        this.soldier = machineGunner;
        this.coverController = new CoverTacticalGoal(soldier, true);
        this.coverController.setCoverSelectionStrategy(new MachineGunnerLaneFirstSelection(this.soldier));
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return coverController.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return coverController.canContinueToUse();
    }

    @Override
    public void start() {
        coverController.start();
    }

    @Override
    public void stop() {
        coverController.stop();
    }

    @Override
    public void tick() {
        coverController.tick();
        syncDebugState();
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

    /** Read-only debug evaluation. Does not affect the runtime cover flow. */
    public FiringPositionFinder.EvaluationReport forceEvaluateSupportPosition() {
        BlockPos center = soldier.getSuppressionCenter();
        BlockPos anchor = SupportPositionFinder.findSupportPosition(soldier);
        if (center == null || anchor == null) {
            latestDebugReport = FiringPositionFinder.emptyEvaluationReport();
            return latestDebugReport;
        }
        latestDebugReport = FiringPositionFinder.evaluate(soldier, center, anchor);
        return latestDebugReport;
    }

    @Nullable
    public FiringPosition getLatestSelectedLane() {
        return latestDebugReport != null ? latestDebugReport.selected() : null;
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

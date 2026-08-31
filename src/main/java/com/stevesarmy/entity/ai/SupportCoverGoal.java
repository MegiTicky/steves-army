package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Support cover goal. Delegates all state machine, movement, ping, and
 * suppression behavior to CoverTacticalGoal. The support role uses rear
 * positioning (like the MG) but never peeks. This shell keeps the support's
 * cover pipeline a distinct goal instance and disables peeking.
 */
public final class SupportCoverGoal extends Goal implements CoverGoalController {
    private final SupportEntity soldier;
    private final CoverTacticalGoal coverController;
    private final SupportDutyController supportDuty;
    private SupportPhase phase = SupportPhase.COVER;

    private enum SupportPhase {
        COVER,
        DUTY
    }

    public SupportCoverGoal(SoldierEntity soldier) {
        if (!(soldier instanceof SupportEntity support)) {
            throw new IllegalArgumentException("SupportCoverGoal requires a support entity");
        }
        this.soldier = support;
        this.coverController = new CoverTacticalGoal(soldier, true);
        this.supportDuty = new SupportDutyController(support);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (phase == SupportPhase.DUTY) return soldier.isAlive();
        return coverController.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == SupportPhase.DUTY) return soldier.isAlive() && supportDuty.isActive();
        return coverController.canContinueToUse();
    }

    @Override
    public void start() {
        if (phase == SupportPhase.DUTY) {
            supportDuty.start();
            return;
        }
        coverController.start();
    }

    @Override
    public void stop() {
        if (phase == SupportPhase.DUTY) {
            supportDuty.stop();
            phase = SupportPhase.COVER;
            coverController.stop();
            return;
        }
        coverController.stop();
    }

    @Override
    public void tick() {
        if (phase == SupportPhase.DUTY) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            manager.tickSuppression(false);
            if (manager.isSuppressed() || soldier.isPreparingOrReloading()
                || soldier.isRecalling() || soldier.isHealing() || soldier.isUsingItem()) {
                supportDuty.stop();
                phase = SupportPhase.COVER;
                resumeCover();
                return;
            }

            SupportDutyController.DutyResult result = supportDuty.tick();
            if (result != SupportDutyController.DutyResult.RUNNING) {
                phase = SupportPhase.COVER;
                resumeCover();
            }
            return;
        }

        coverController.tick();

        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        if (manager.getState() == CoverBehaviorManager.CoverState.IN_COVER
            && !manager.isSuppressed()
            && supportDuty.tryStartDuty()) {
            phase = SupportPhase.DUTY;
            // Stop delegated cover work before support navigation takes over.
            coverController.stop();
            manager.clearCover();
            supportDuty.start();
        }
    }

    private void resumeCover() {
        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        manager.clearCover();
        manager.setState(CoverBehaviorManager.CoverState.SEEKING_COVER);
        coverController.start();
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

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

    public SupportCoverGoal(SoldierEntity soldier) {
        if (!(soldier instanceof SupportEntity support)) {
            throw new IllegalArgumentException("SupportCoverGoal requires a support entity");
        }
        this.soldier = support;
        this.coverController = new CoverTacticalGoal(soldier, true);
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

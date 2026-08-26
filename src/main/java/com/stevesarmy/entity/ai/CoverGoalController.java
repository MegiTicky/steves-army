package com.stevesarmy.entity.ai;

import net.minecraft.core.BlockPos;
import com.stevesarmy.combat.cover.pure.CoverSearchResult;

import javax.annotation.Nullable;

/** Shared command-facing services exposed by a cover goal. */
public interface CoverGoalController {
    boolean requestGoToRelocation(BlockPos destination, int commandGeneration);

    boolean isHandlingGoToRelocation(int commandGeneration);

    @Nullable
    BlockPos getProneDefensivePosition();

    default void applyAsyncCoverPilotResult(CoverSearchResult result, BlockPos sourcePosition, long sourceTick) {
    }

    default void rejectAsyncCoverPilot() {
    }
}

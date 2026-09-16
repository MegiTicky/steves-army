package com.stevesarmy.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import com.stevesarmy.combat.cover.pure.CoverSearchResult;

import javax.annotation.Nullable;

/** Shared command-facing services exposed by a cover goal. */
public interface CoverGoalController {
    enum SuppressionPositionStatus { IDLE, SEARCHING, MOVING, ARRIVED, BLOCKED, FAILED }

    boolean requestGoToRelocation(BlockPos destination, int commandGeneration);

    boolean isHandlingGoToRelocation(int commandGeneration);

    @Nullable
    BlockPos getProneDefensivePosition();

    /**
     * A suppress-area order owns this request. The generation prevents an old
     * path/search result from moving a soldier after the player issued a new order.
     */
    default SuppressionPositionStatus requestSuppressionPosition(BlockPos area, int generation) {
        return SuppressionPositionStatus.FAILED;
    }

    default SuppressionPositionStatus getSuppressionPositionStatus(int generation) {
        return SuppressionPositionStatus.IDLE;
    }

    /** Debug channel: consecutive search failures for the active suppression-position order. */
    default int getSuppressionPositionFailures(int generation) {
        return 0;
    }

    /** Debug channel: why the last search failed, or null when no order is active. */
    @Nullable
    default String getSuppressionPositionLastFailure(int generation) {
        return null;
    }

    /** Debug channel: anchor position the relocation search fans out from. */
    @Nullable
    default Vec3 getSuppressionPositionSearchOrigin(int generation) {
        return null;
    }

    /** Debug channel: radius of the relocation search fan. */
    default int getSuppressionPositionSearchRadius() {
        return 0;
    }

    default void cancelSuppressionPosition(int generation) {
    }

    default void applyAsyncCoverPilotResult(CoverSearchResult result, BlockPos sourcePosition, long sourceTick) {
    }

    default void rejectAsyncCoverPilot() {
    }

    /** Entity-level anti-stuck watchdog hook: cancel all pending cover work and re-decide. */
    default void resetFromStuckWatchdog() {
    }

    /** Counts a hostile hit taken while peeking/exposed at the occupied cover. */
    default void noteHitWhilePeekingAtCover() {
    }

    /** Reposition decisions over the last ~5 seconds, for churn diagnostics. */
    default int getRecentRepositionCount() {
        return 0;
    }
}

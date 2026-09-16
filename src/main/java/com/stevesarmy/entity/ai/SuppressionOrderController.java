package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Server-side lifetime for an explicit suppress-area order. Combat and cover
 * consume this state, but neither owns or expires the player's command.
 */
public final class SuppressionOrderController {
    public enum Phase { PREPARING, RELOCATING, FIRING, PAUSED, COMPLETED, FAILED, CANCELLED }
    public enum BlockReason { NONE, RELOAD, WEAPON, POSTURE, GEOMETRY, ALLY, SELF_DEFENSE, MOVING, PINNED }

    public static final int FIRST_SHOT_TIMEOUT_TICKS = 200;
    public static final int FIRING_BUDGET_TICKS = 200;
    public static final int TOTAL_TIMEOUT_TICKS = 600;

    @Nullable private BlockPos area;
    @Nullable private BlockPos origin;
    private int generation;
    private int startedTick;
    private int firingTicksRemaining;
    private int successfulShots;
    private Phase phase = Phase.CANCELLED;
    private BlockReason blockReason = BlockReason.NONE;
    private boolean terminalReported;
    private int suspendedTicks;
    private int lastControllerTick = -1;

    public void start(BlockPos target, int gameTick) {
        area = target.immutable();
        origin = null;
        generation++;
        startedTick = gameTick;
        firingTicksRemaining = FIRING_BUDGET_TICKS;
        successfulShots = 0;
        phase = Phase.PREPARING;
        blockReason = BlockReason.NONE;
        terminalReported = false;
        suspendedTicks = 0;
        lastControllerTick = -1;
    }

    public void tick(SoldierEntity soldier) {
        tick(soldier.tickCount);
    }

    /** Kept entity-free so lifecycle guarantees can run in Forge GameTests. */
    public void tick(int currentTick) {
        if (!isActive()) return;
        // Time spent relocating under a live order must not consume the
        // first-shot budget — a walking soldier would otherwise have the
        // order killed out from under him around the moment he arrives.
        // The total timeout stays absolute so an order still cannot live forever.
        boolean relocating = phase == Phase.RELOCATING
            || (phase == Phase.PAUSED && blockReason == BlockReason.MOVING);
        if (relocating && lastControllerTick >= 0) {
            suspendedTicks += currentTick - lastControllerTick;
        }
        lastControllerTick = currentTick;
        int rawElapsed = currentTick - startedTick;
        int effectiveElapsed = rawElapsed - suspendedTicks;
        if (rawElapsed >= TOTAL_TIMEOUT_TICKS || (successfulShots == 0 && effectiveElapsed >= FIRST_SHOT_TIMEOUT_TICKS)) {
            finish(Phase.FAILED);
        }
    }

    public boolean isActive() {
        return area != null && switch (phase) {
            case PREPARING, RELOCATING, FIRING, PAUSED -> true;
            default -> false;
        };
    }

    public void beginRelocation() {
        if (isActive()) phase = Phase.RELOCATING;
    }

    public void setPaused(BlockReason reason) {
        if (isActive()) {
            phase = Phase.PAUSED;
            blockReason = reason;
        }
    }

    public void setPreparing(BlockReason reason) {
        if (isActive()) {
            phase = Phase.PREPARING;
            blockReason = reason;
        }
    }

    public void recordShot() {
        if (!isActive()) return;
        successfulShots++;
        phase = Phase.FIRING;
        blockReason = BlockReason.NONE;
    }

    /** Only firing/cadence time consumes the post-first-shot budget. */
    public void consumeFiringTick() {
        if (successfulShots <= 0 || !isActive()) return;
        if (--firingTicksRemaining <= 0) finish(Phase.COMPLETED);
    }

    public void cancel() {
        if (isActive()) finish(Phase.CANCELLED);
    }

    private void finish(Phase result) {
        phase = result;
        blockReason = BlockReason.NONE;
    }

    @Nullable public BlockPos getArea() { return area; }
    @Nullable public BlockPos getOrigin() { return origin; }
    public void setOrigin(BlockPos value) { if (origin == null) origin = value.immutable(); }
    public int getGeneration() { return generation; }
    public Phase getPhase() { return phase; }
    public BlockReason getBlockReason() { return blockReason; }
    public int getSuccessfulShots() { return successfulShots; }
    public int getFiringTicksRemaining() { return firingTicksRemaining; }
    public boolean markTerminalReported() {
        if (!isActive() && !terminalReported && (phase == Phase.FAILED || phase == Phase.COMPLETED)) {
            terminalReported = true;
            return true;
        }
        return false;
    }
}

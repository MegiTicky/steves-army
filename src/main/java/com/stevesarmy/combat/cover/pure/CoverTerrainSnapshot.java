package com.stevesarmy.combat.cover.pure;

import com.stevesarmy.combat.cover.FiringPositionFinder;
import net.minecraft.core.BlockPos;

import java.util.Map;

/** Immutable block geometry required by the pure cover evaluator. */
public record CoverTerrainSnapshot(Map<BlockPos, FiringPositionFinder.SnapshotCell> cells) {
    public CoverTerrainSnapshot {
        cells = cells == null ? Map.of() : Map.copyOf(cells);
    }

    public FiringPositionFinder.SnapshotCell get(BlockPos position) {
        return cells.get(position);
    }
}

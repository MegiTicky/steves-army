package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;

/**
 * A machine gunner firing position produced by the dedicated firing-position
 * evaluation. The destination is exact: for COVER_PEEK it is the standing block
 * behind a physical cover, for OPEN_PRONE it is a raycast-verified prone lane.
 */
public record FiringPosition(BlockPos destination, FiringPosture posture,
                             float firingAccess, float protection, float score) {

    public enum FiringPosture {
        COVER_PEEK,
        OPEN_PRONE
    }
}

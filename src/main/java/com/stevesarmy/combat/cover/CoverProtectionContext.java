package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** The best available primary threat reference for cover safety evaluation. */
public record CoverProtectionContext(Source source, @Nullable Vec3 threatPosition,
                                     @Nullable Vec3 awarenessDirection) {
    public enum Source {
        VISIBLE_TARGET,
        LAST_SEEN,
        THREAT_AWARENESS,
        NONE
    }

    public static final CoverProtectionContext NONE = new CoverProtectionContext(Source.NONE, null, null);

    @Nullable
    public Vec3 directionFrom(BlockPos coverPosition) {
        Vec3 direction = threatPosition != null
            ? threatPosition.subtract(coverPosition.getCenter())
            : awarenessDirection;
        if (direction == null) return null;

        direction = new Vec3(direction.x, 0.0D, direction.z);
        return direction.lengthSqr() > 0.001D ? direction.normalize() : null;
    }

    public boolean hasThreat() {
        return directionFrom(BlockPos.ZERO) != null;
    }
}

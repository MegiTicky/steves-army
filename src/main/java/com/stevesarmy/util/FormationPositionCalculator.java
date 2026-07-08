package com.stevesarmy.util;

import com.stevesarmy.squad.SquadFormation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public class FormationPositionCalculator {

    private static final double SPREAD = 4.0;

    /**
     * Returns the ideal formation offset relative to an anchor position.
     * Add this to the anchor BlockPos to get the soldier's target position.
     * Returns BlockPos.ZERO for NONE and CQB formations.
     * <p>
     * Uses alternating placement around the leader:
     *   index 0 = 0       (leader)
     *   index 1 = +spread (right 1)
     *   index 2 = -spread (left 1)
     *   index 3 = +2*spread (right 2)
     *   index 4 = -2*spread (left 2)
     *   ...
     */
    public static BlockPos getFormationOffset(
            Vec3 formationForward,
            SquadFormation formation,
            int memberIndex,
            int squadSize) {
        return getFormationOffset(formationForward, formation, memberIndex, squadSize, SPREAD);
    }

    /**
     * Returns the ideal formation offset relative to an anchor position.
     * Add this to the anchor BlockPos to get the soldier's target position.
     * Returns BlockPos.ZERO for NONE and CQB formations.
     * <p>
     * Uses alternating placement around the leader:
     *   index 0 = 0       (leader)
     *   index 1 = +spread (right 1)
     *   index 2 = -spread (left 1)
     *   index 3 = +2*spread (right 2)
     *   index 4 = -2*spread (left 2)
     *   ...
     */
    public static BlockPos getFormationOffset(
            Vec3 formationForward,
            SquadFormation formation,
            int memberIndex,
            int squadSize,
            double spread) {
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB) {
            return BlockPos.ZERO;
        }

        Vec3 fwd = (formationForward != null && formationForward.lengthSqr() > 0.001)
                ? formationForward.normalize()
                : new Vec3(0, 0, -1);
        Vec3 perp = new Vec3(-fwd.z, 0, fwd.x).normalize();

        double alternatingOffset = 0.0;
        if (memberIndex > 0) {
            int step = (memberIndex + 1) / 2;
            boolean isRight = (memberIndex % 2 != 0);
            alternatingOffset = (isRight ? step : -step) * spread;
        }

        return switch (formation) {
            case LINE -> new BlockPos(
                    (int) Math.round(perp.x * alternatingOffset),
                    0,
                    (int) Math.round(perp.z * alternatingOffset));
            case WEDGE -> {
                double forward = 5.0 - Math.abs(alternatingOffset) * 0.5;
                yield new BlockPos(
                        (int) Math.round(perp.x * alternatingOffset + fwd.x * forward),
                        0,
                        (int) Math.round(perp.z * alternatingOffset + fwd.z * forward));
            }
            case COLUMN -> {
                double depthOffset = memberIndex * spread;
                yield new BlockPos(
                        (int) Math.round(-fwd.x * depthOffset),
                        0,
                        (int) Math.round(-fwd.z * depthOffset));
            }
            case DIAMOND -> {
                double angle = memberIndex * Math.PI / 2;
                yield new BlockPos(
                        (int) Math.round(Math.cos(angle) * spread),
                        0,
                        (int) Math.round(Math.sin(angle) * spread));
            }
            default -> BlockPos.ZERO;
        };
    }

    /**
     * Computes the distance from a cover position to the ideal formation position.
     * The ideal position is the anchor position plus the formation offset.
     */
    public static double distanceToFormationPosition(
            BlockPos coverPos,
            BlockPos anchorPos,
            Vec3 formationForward,
            SquadFormation formation,
            int memberIndex,
            int squadSize) {
        BlockPos offset = getFormationOffset(formationForward, formation, memberIndex, squadSize);
        BlockPos ideal = anchorPos.offset(offset);
        return Math.sqrt(coverPos.distSqr(ideal));
    }
}
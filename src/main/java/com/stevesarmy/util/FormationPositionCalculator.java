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
                : new Vec3(0, 0, -1); // default: face north
        Vec3 perp = new Vec3(-fwd.z, 0, fwd.x).normalize();

        double half = (squadSize - 1) * spread / 2.0;
        double positionOffset = memberIndex * spread - half;

        return switch (formation) {
            case LINE -> new BlockPos(
                    (int) Math.round(perp.x * positionOffset),
                    0,
                    (int) Math.round(perp.z * positionOffset));
            case WEDGE -> {
                double forward = 5.0 - Math.abs(positionOffset) * 0.5;
                double side = positionOffset;
                yield new BlockPos(
                        (int) Math.round(perp.x * side + fwd.x * forward),
                        0,
                        (int) Math.round(perp.z * side + fwd.z * forward));
            }
            case COLUMN -> new BlockPos(
                    (int) Math.round(-fwd.x * positionOffset),
                    0,
                    (int) Math.round(-fwd.z * positionOffset));
            case DIAMOND -> {
                double angle = memberIndex * Math.PI / 2;
                double dist = spread;
                yield new BlockPos(
                        (int) Math.round(Math.cos(angle) * dist),
                        0,
                        (int) Math.round(Math.sin(angle) * dist));
            }
            default -> BlockPos.ZERO;
        };
    }

    /**
     * Computes the distance from a position to the ideal formation position.
     * Used by cover scoring to evaluate how well a cover point fits the formation.
     */
    public static double distanceToFormationPosition(
            BlockPos pos,
            Vec3 formationForward,
            SquadFormation formation,
            int memberIndex,
            int squadSize) {
        BlockPos offset = getFormationOffset(formationForward, formation, memberIndex, squadSize);
        BlockPos ideal = pos.offset(offset);
        return Math.sqrt(pos.distSqr(ideal));
    }
}
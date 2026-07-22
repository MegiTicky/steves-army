package com.stevesarmy.util;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadFormation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * Adjusts a formation target position to a walkable surface.
     * If the position is already walkable (solid block below, air/clear above),
     * returns it unchanged. Otherwise snaps to the surface at the same X/Z
     * using Minecraft's heightmap.
     */
    public static BlockPos adjustToSurface(Level level, BlockPos target) {
        BlockPathTypes pathType = WalkNodeEvaluator.getBlockPathTypeStatic(level, target.mutable());
        if (pathType == BlockPathTypes.WALKABLE || pathType == BlockPathTypes.OPEN) {
            BlockState below = level.getBlockState(target.below());
            if (!(below.getBlock() instanceof LeavesBlock)) {
                return target;
            }
        }

        BlockPos surface = level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            new BlockPos(target.getX(), 0, target.getZ())
        );

        if (surface.getY() > level.getMinBuildHeight()) {
            return surface;
        }

        return target;
    }

    /**
     * Assigns formation slot indices to all soldiers in a fire team.
     * Each soldier keeps its previous slot if valid, otherwise gets the first free slot.
     * Returns the slot index assigned to the given soldier.
     */
    public static int assignFormationSlots(List<SoldierEntity> soldiers, SoldierEntity self) {
        if (soldiers.isEmpty()) return -1;
        soldiers.sort(Comparator.comparing(e -> e.getUUID()));

        int n = soldiers.size();
        boolean[] taken = new boolean[n];

        // First pass: reclaim previous slots
        for (SoldierEntity s : soldiers) {
            int slot = s.getFormationSlotIndex();
            if (slot >= 0 && slot < n && !taken[slot]) {
                taken[slot] = true;
            } else {
                s.setFormationSlotIndex(-1);
            }
        }

        // Second pass: assign free slots
        int nextSlot = 0;
        for (SoldierEntity s : soldiers) {
            if (s.getFormationSlotIndex() < 0) {
                while (nextSlot < n && taken[nextSlot]) {
                    nextSlot++;
                }
                if (nextSlot < n) {
                    s.setFormationSlotIndex(nextSlot);
                    taken[nextSlot] = true;
                }
            }
        }

        return self.getFormationSlotIndex();
    }

    /**
     * Computes the formation target for a soldier given the anchor position.
     * If no formation is active, returns the anchor itself.
     */
    public static BlockPos getFormationTarget(
            Vec3 formationForward,
            SquadFormation formation,
            int slotIndex,
            int teamSize,
            BlockPos anchor,
            Level level) {
        if (formation == SquadFormation.NONE || formation == SquadFormation.CQB || slotIndex < 0) {
            return anchor;
        }
        BlockPos offset = getFormationOffset(formationForward, formation, slotIndex, teamSize);
        BlockPos target = anchor.offset(offset);
        return adjustToSurface(level, target);
    }

    /**
     * Collects alive soldiers from the same fire team, filtered by squad.
     */
    public static List<SoldierEntity> getFireTeamSoldiers(
            List<? extends net.minecraft.world.entity.LivingEntity> allMembers,
            SoldierEntity soldier) {
        FireTeam myTeam = soldier.getFireTeam();
        List<SoldierEntity> result = new ArrayList<>();
        for (net.minecraft.world.entity.LivingEntity member : allMembers) {
            if (member instanceof SoldierEntity s && s.isAlive()) {
                if (myTeam == FireTeam.ALL || s.getFireTeam() == myTeam) {
                    result.add(s);
                }
            }
        }
        return result;
    }
}
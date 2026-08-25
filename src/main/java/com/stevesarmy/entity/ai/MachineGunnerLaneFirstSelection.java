package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Fire lane first, protection second, while staying with the group. The lane is
 * aimed at the attack objective during ATTACK (so the MG advances with the
 * squad) and at the suppression center otherwise. Protection is the same strict
 * geometric check the rifleman selection uses (the cover's precomputed shielded
 * directions). Among covers that are competitive on the base rifleman score
 * (which encodes objective progress, proximity, and protection), the best
 * confirmed firing lane wins. Returning empty defers to the rifleman selection,
 * so a missing lane can never stall the MG.
 */
public final class MachineGunnerLaneFirstSelection implements CoverSelectionStrategy {
    private static final float SCORE_MARGIN = 0.15f;
    private static final float LANE_WEIGHT = 0.5f;

    private final MachineGunnerEntity gunner;

    public MachineGunnerLaneFirstSelection(MachineGunnerEntity gunner) {
        this.gunner = gunner;
    }

    @Override
    public Optional<CoverPoint> select(SoldierEntity soldier, List<CoverFinder.ScoredCover> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        BlockPos laneTarget = soldier.hasValidAttackTarget()
            ? soldier.getAttackTargetPos() : gunner.getSuppressionCenter();
        if (laneTarget == null) {
            return Optional.empty();
        }

        float bestBaseScore = Float.NEGATIVE_INFINITY;
        for (CoverFinder.ScoredCover sc : candidates) {
            if (sc.score > bestBaseScore) {
                bestBaseScore = sc.score;
            }
        }
        float scoreCutoff = bestBaseScore - SCORE_MARGIN;

        Optional<CoverPoint> best = Optional.empty();
        float bestCombined = Float.NEGATIVE_INFINITY;
        for (CoverFinder.ScoredCover sc : candidates) {
            CoverPoint cover = sc.cover;
            BlockPos pos = cover.getPosition();
            if (sc.score < scoreCutoff) {
                continue;
            }
            if (!CoverReservationManager.isAvailableFor(pos, soldier)) {
                continue;
            }
            Vec3 coverToTarget = new Vec3(
                laneTarget.getX() + 0.5 - pos.getX() - 0.5, 0.0, laneTarget.getZ() + 0.5 - pos.getZ() - 0.5);
            if (coverToTarget.lengthSqr() < 0.001) {
                continue;
            }
            if (!cover.getProtectedDirections().contains(
                    CoverFinder.getDirectionFromVector(coverToTarget.normalize()))) {
                continue;
            }
            float access = FiringPositionFinder.evaluateFiringAccess(
                soldier, laneTarget, pos, FiringPosition.FiringPosture.COVER_PEEK);
            float combined = sc.score + LANE_WEIGHT * access;
            if (best.isEmpty() || combined > bestCombined) {
                best = Optional.of(cover);
                bestCombined = combined;
            }
        }
        return best;
    }
}

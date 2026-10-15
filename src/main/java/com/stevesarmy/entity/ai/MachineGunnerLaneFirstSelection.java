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
 * aimed at the attack objective during ATTACK and at the suppression center
 * otherwise. Protection is the same strict geometric check the rifleman
 * selection uses (the cover's precomputed shielded directions). Among covers
 * that are competitive on the base rifleman score (which encodes objective
 * progress, proximity, and protection), the best confirmed firing lane wins.
 * Returning empty defers to the base selection, so a missing lane can
 * never stall the MG.
 *
 * Row discipline gates the lane preference during ATTACK: the gunner is the
 * squad's base of fire, not its point man. Candidates ahead of the
 * frontmost rifleman along the attack bearing are rejected outright, not
 * de-scored — forward covers have the best firing lanes by construction, so
 * the lane weight would outbid the row discipline every time. The selector
 * checks lane-valid, protected covers in the preferred 8-10 block rear band
 * first, then any such cover behind the front line.
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
        if (com.stevesarmy.debug.DiagnosticLogManager.isAttackLoggingEnabled()) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("[MGRow] enter t{} candidates={} attackTarget={}",
                soldier.tickCount, candidates.size(), soldier.hasValidAttackTarget());
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        BlockPos laneTarget = soldier.hasValidAttackTarget()
            ? soldier.getAttackTargetPos() : gunner.getSuppressionCenter();
        if (laneTarget == null) {
            return Optional.empty();
        }
        Vec3 reference = new Vec3(
            laneTarget.getX() + 0.5, laneTarget.getY(), laneTarget.getZ() + 0.5);
        // MAX_VALUE with no non-MG mates (solo gunner) or outside ATTACK —
        // the filter is inactive and backRowFloor is -infinity.
        boolean backRowActive = soldier.hasValidAttackTarget()
            && MgAttackMovementPolicy.active(soldier);
        boolean preferRearBand = backRowActive
            && MgAttackMovementPolicy.attackFormationActive(soldier);

        float bestBaseScore = Float.NEGATIVE_INFINITY;
        for (CoverFinder.ScoredCover sc : candidates) {
            if (sc.score > bestBaseScore) {
                bestBaseScore = sc.score;
            }
        }
        float scoreCutoff = bestBaseScore - SCORE_MARGIN;

        Optional<CoverPoint> best;
        if (preferRearBand) {
            best = selectLaneFirst(soldier, reference, candidates, scoreCutoff, true, true, true);
            if (best.isEmpty()) best = selectLaneFirst(soldier, reference, candidates,
                Float.NEGATIVE_INFINITY, true, true, true);
            if (best.isEmpty()) best = selectLaneFirst(soldier, reference, candidates,
                scoreCutoff, true, true, false);
            if (best.isEmpty()) best = selectLaneFirst(soldier, reference, candidates,
                Float.NEGATIVE_INFINITY, true, true, false);
        } else if (backRowActive) {
            best = selectLaneFirst(soldier, reference, candidates, scoreCutoff, true, false, false);
        } else {
            best = selectLaneFirst(soldier, reference, candidates, scoreCutoff, false, false, false);
        }
        if (com.stevesarmy.debug.DiagnosticLogManager.isAttackLoggingEnabled()) {
            int rejected = 0;
            for (CoverFinder.ScoredCover sc : candidates) {
                if (backRowActive && MgAttackMovementPolicy.aheadOfRifleLine(soldier,
                    soldier.getAttackTargetPos(), sc.cover.getPosition())) {
                    rejected++;
                }
            }
            com.stevesarmy.StevesArmyMod.LOGGER.info(
                "[MGRow] t{} candidates={} rejectedAhead={} frontDist={} best={} at {}",
                soldier.tickCount, candidates.size(), rejected,
                backRowActive ? "active" : "none",
                best.isPresent(), best.map(c -> c.getPosition().toShortString()).orElse("-"));
        }
        return best;
    }

    private Optional<CoverPoint> selectLaneFirst(SoldierEntity soldier, Vec3 reference,
                                                 List<CoverFinder.ScoredCover> candidates,
                                                 float scoreCutoff, boolean backRowActive,
                                                 boolean filterRearBand,
                                                 boolean preferredRearBand) {
        Optional<CoverPoint> best = Optional.empty();
        float bestCombined = Float.NEGATIVE_INFINITY;
        for (CoverFinder.ScoredCover sc : candidates) {
            CoverPoint cover = sc.cover;
            BlockPos pos = cover.getPosition();
            if (sc.score < scoreCutoff) {
                continue;
            }
            if (backRowActive && MgAttackMovementPolicy.aheadOfRifleLine(soldier,
                soldier.getAttackTargetPos(), pos)) {
                continue;
            }
            if (filterRearBand && MgAttackMovementPolicy.inPreferredRearBand(soldier,
                soldier.getAttackTargetPos(), pos) != preferredRearBand) {
                continue;
            }
            if (!CoverReservationManager.isAvailableFor(pos, soldier)) {
                continue;
            }
            Vec3 coverToTarget = new Vec3(
                reference.x - pos.getX() - 0.5, 0.0, reference.z - pos.getZ() - 0.5);
            if (coverToTarget.lengthSqr() < 0.001) {
                continue;
            }
            if (!cover.getProtectedDirections().contains(
                    CoverFinder.getDirectionFromVector(coverToTarget.normalize()))) {
                continue;
            }
            float access = FiringPositionFinder.evaluateFiringAccess(
                soldier, BlockPos.containing(reference), pos, FiringPosition.FiringPosture.COVER_PEEK);
            if (filterRearBand && preferredRearBand && access <= 0.0f) {
                continue;
            }
            float combined = sc.score + LANE_WEIGHT * access;
            if (best.isEmpty() || combined > bestCombined) {
                best = Optional.of(cover);
                bestCombined = combined;
            }
        }
        return best;
    }
}

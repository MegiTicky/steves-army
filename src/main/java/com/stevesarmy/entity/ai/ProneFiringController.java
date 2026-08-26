package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.DefensivePositionCandidate;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Persistent firing-prone plan, ticked by CoverTacticalGoal rather than a combat Goal lifecycle. */
final class ProneFiringController {
    private static final double MIN_RANGE = 16.0;
    private static final double CLOSE_THREAT_RANGE = 10.0;
    private static final float FIRING_ARC_DEGREES = 70.0f;

    private enum State { IDLE, MOVING, ACTIVE }

    private final SoldierEntity soldier;
    private State state = State.IDLE;
    private BlockPos destination;
    private int activationTick = -1;

    ProneFiringController(SoldierEntity soldier) {
        this.soldier = soldier;
    }

    boolean begin(DefensivePositionCandidate.ProneFiringCandidate candidate, boolean atDestination) {
        if (state != State.IDLE && candidate.destination().equals(destination)) {
            return false;
        }
        cancel("lane_replaced");
        destination = candidate.destination().immutable();
        state = atDestination ? State.ACTIVE : State.MOVING;
        if (atDestination) {
            soldier.setFiringProne(true);
            activationTick = soldier.tickCount;
        }
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PronePlan] soldier={} event=selected lane={} state={} immediate={}",
                soldier.getId(), destination, state, atDestination);
        }
        return true;
    }

    void onReached() {
        if (state == State.MOVING) {
            state = State.ACTIVE;
            soldier.setFiringProne(true);
            activationTick = soldier.tickCount;
            if (DiagnosticLogManager.isCoverLoggingEnabled()) {
                StevesArmyMod.LOGGER.info("[PronePlan] soldier={} event=entered lane={} immediate=true",
                    soldier.getId(), destination);
            }
        }
    }

    void tick(@Nullable LivingEntity target) {
        if (state != State.ACTIVE) {
            return;
        }

        String blockReason = getBlockReason(target);
        if (blockReason != null) {
            cancel(blockReason);
            return;
        }

    }

    boolean isPlanActive() {
        return state != State.IDLE;
    }

    boolean isMovingToLane() {
        return state == State.MOVING;
    }

    @Nullable BlockPos getDestination() {
        return destination;
    }

    void cancel(String reason) {
        boolean wasActive = state != State.IDLE || soldier.isFiringProne();
        state = State.IDLE;
        destination = null;
        activationTick = -1;
        if (soldier.isFiringProne()) {
            soldier.setFiringProne(false);
        }
        if (wasActive && DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[PronePlan] soldier={} event=cancel reason={}", soldier.getId(), reason);
        }
    }

    @Nullable
    private String getBlockReason(@Nullable LivingEntity target) {
        if (!GunIntegration.isAnyGunLoaded() || !GunIntegration.hasGun(soldier)) return "no_gun";

        CoverBehaviorManager cover = soldier.getCoverBehaviorManager();
        if (cover.getCurrentCover() != null || cover.getTargetCover() != null
            || cover.getState() == CoverBehaviorManager.CoverState.REPOSITIONING) return "cover_active";
        if (soldier.hasValidPingMoveTarget()) return "movement_command";
        if (soldier.isHealing()) return "healing";
        // Path navigation may leave one tick of residual velocity after it
        // reaches the lane. Subsequent real movement still cancels the plan.
        if (soldier.tickCount != activationTick
            && (soldier.isCrawlMoving() || soldier.getDeltaMovement().horizontalDistanceSqr() > 0.0025D)) return "moving";
        if (!isProneTerrainValid()) return "terrain_invalid";

        // A lane remains a valid defensive position through a target gap. When
        // contact resumes, test the new target without resetting the plan.
        if (target == null || !target.isAlive()) return null;
        if (!TargetAcquisition.hasLineOfSight(soldier, target)) return "no_direct_los";

        if (soldier.distanceTo(target) <= CLOSE_THREAT_RANGE || soldier.distanceTo(target) < MIN_RANGE) return "close_target";
        CombatGoalController combatGoal = soldier.getCombatGoal();
        if (combatGoal != null) {
            for (LivingEntity other : combatGoal.getPotentialTargets()) {
                if (other != target && other.isAlive() && !soldier.isFriendlyTo(other)
                    && soldier.distanceTo(other) <= CLOSE_THREAT_RANGE) return "nearby_threat";
            }
        }
        float targetYaw = (float) (Mth.atan2(target.getZ() - soldier.getZ(), target.getX() - soldier.getX()) * (180.0 / Math.PI)) - 90.0f;
        if (Math.abs(Mth.wrapDegrees(targetYaw - soldier.getYRot())) > FIRING_ARC_DEGREES) return "outside_prone_arc";
        return isProneAimVisible(target) ? null : "prone_los_blocked";
    }

    private boolean isProneTerrainValid() {
        BlockPos pos = soldier.blockPosition();
        return soldier.level().isLoaded(pos)
            && soldier.level().getBlockState(pos.below()).isSolid()
            && soldier.level().getBlockState(pos).getCollisionShape(soldier.level(), pos).isEmpty()
            && soldier.level().getBlockState(pos.above()).getCollisionShape(soldier.level(), pos.above()).isEmpty()
            && soldier.level().getFluidState(pos).isEmpty();
    }

    private boolean isProneAimVisible(LivingEntity target) {
        Vec3 aimPoint = target.getEyePosition();
        CombatGoalController combatGoal = soldier.getCombatGoal();
        if (combatGoal != null) {
            aimPoint = combatGoal.getProneFiringAimPoint(target);
        }
        Vec3 proneEye = new Vec3(soldier.getX(), soldier.getY() + soldier.getEyeHeight(Pose.SWIMMING), soldier.getZ());
        return VisibilityRay.trace(soldier.level(), proneEye, aimPoint, soldier).hasContact();
    }
}

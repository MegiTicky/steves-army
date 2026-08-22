package com.stevesarmy.entity.ai;

import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;

/**
 * Machine gunner cover behavior. Runs the base cover logic and additionally
 * re-evaluates the gunner's rear support position relative to the squad line
 * and the active engagement area.
 */
public class MachineGunnerSupportGoal extends CoverTacticalGoal {
    private static final int SUPPORT_EVALUATION_INTERVAL = 60;
    private static final double SUPPORT_RELOCATE_MIN_DISTANCE_SQ = 144.0;
    private int evaluationCooldown = 0;

    public MachineGunnerSupportGoal(SoldierEntity soldier) {
        super(soldier);
    }

    @Override
    public void tick() {
        super.tick();
        if (--evaluationCooldown > 0) {
            return;
        }
        evaluationCooldown = SUPPORT_EVALUATION_INTERVAL;
        maybeEvaluateSupportPosition();
    }

    private void maybeEvaluateSupportPosition() {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return;
        }
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return;
        }
        if (soldier.getCoverBehaviorManager().isSuppressed()) {
            return;
        }

        BlockPos support = SupportPositionFinder.findSupportPosition(mg);
        if (support == null) {
            clearSupportRelocation();
            return;
        }
        if (soldier.distanceToSqr(support.getCenter()) < SUPPORT_RELOCATE_MIN_DISTANCE_SQ) {
            return;
        }
        requestSupportRelocation(support);
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;

/**
 * Machine gunner combat behavior. Tier 1 (active visible target) is handled by
 * the base goal. This adds tier 2 (last-known threat position) and tier 3
 * (likely peek positions) auto-suppression by feeding the proven ping-suppression
 * machinery a suppression center when no live target is available.
 */
public class MachineGunnerCombatGoal extends SoldierCombatGoal {
    private static final int AUTO_SUPPRESS_EVALUATION_INTERVAL = 20;
    private int autoSuppressCooldown = 0;

    public MachineGunnerCombatGoal(SoldierEntity soldier) {
        super(soldier);
    }

    @Override
    public boolean canUse() {
        if (super.canUse()) {
            return true;
        }
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return false;
        }
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return false;
        }
        return mg.getSuppressionCenter() != null;
    }

    @Override
    public void tick() {
        super.tick();
        if (--autoSuppressCooldown > 0) {
            return;
        }
        autoSuppressCooldown = AUTO_SUPPRESS_EVALUATION_INTERVAL;
        maybeAutoSuppress();
    }

    private void maybeAutoSuppress() {
        if (!(soldier instanceof MachineGunnerEntity mg)) {
            return;
        }
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return;
        }
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return;
        }
        if (isSuppressing() || soldier.hasValidPingSuppressPos()) {
            return;
        }
        if (!GunIntegration.isTaczLoaded() || !GunIntegration.hasGun(soldier)) {
            return;
        }

        BlockPos center = mg.getSuppressionCenter();
        if (center == null) {
            return;
        }

        soldier.setPingSuppressPos(center);
        soldier.getCombatGoal().forceRestartPingSuppression();
    }
}

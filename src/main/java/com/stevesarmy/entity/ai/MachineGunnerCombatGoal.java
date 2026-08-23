package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine gunner combat behavior. Tier 1 (active visible target) is handled by
 * the base goal. This adds tier 2 (last-known threat position) and tier 3
 * (likely peek positions) auto-suppression by feeding the proven ping-suppression
 * machinery a suppression center when no live target is available.
 */
public class MachineGunnerCombatGoal extends SoldierCombatGoal {
    private static final int AUTO_SUPPRESS_EVALUATION_INTERVAL = 20;
    private static final double AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE = 4.0;
    private static final double AUTO_SUPPRESS_POINT_CHANGE_DISTANCE_SQ = 0.75 * 0.75;
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
        if (mg.isAutonomousSuppressionActive() && soldier.hasValidPingSuppressPos()) {
            refreshAutonomousSuppression(mg);
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

        mg.beginAutonomousSuppression();
        soldier.setPingSuppressPos(center);
        refreshAutonomousSuppression(mg);
        soldier.getCombatGoal().forceRestartPingSuppression();
    }

    private void refreshAutonomousSuppression(MachineGunnerEntity mg) {
        BlockPos center = mg.getSuppressionCenter();
        if (center == null) {
            mg.clearAutonomousSuppression();
            soldier.clearPingSuppressPos();
            return;
        }

        BlockPos current = soldier.getPingSuppressPos();
        if (current == null
            || current.distSqr(center) > AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE * AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE) {
            soldier.setPingSuppressPos(center);
            forceRestartPingSuppression();
        }

        List<Vec3> points = FiringPositionFinder.findVisibleSuppressionTargets(soldier, center);
        if (points.isEmpty()) {
            points = buildAreaFallbackPoints(center);
        }
        if (suppressionPointsChanged(soldier.getSuppressionAimPoints(), points)) {
            soldier.setSuppressionAimPoints(points);
            forceRestartPingSuppression();
        }
    }

    private List<Vec3> buildAreaFallbackPoints(BlockPos center) {
        Vec3 origin = soldier.getEyePosition();
        Vec3 forward = center.getCenter().subtract(origin);
        forward = new Vec3(forward.x, 0.0, forward.z);
        if (forward.lengthSqr() < 0.001) {
            forward = new Vec3(0.0, 0.0, 1.0);
        } else {
            forward = forward.normalize();
        }
        Vec3 lateral = new Vec3(-forward.z, 0.0, forward.x);
        List<Vec3> points = new ArrayList<>();
        for (int offset = -2; offset <= 2; offset++) {
            Vec3 point = center.getCenter().add(lateral.scale(offset * 3.0)).add(0.0, 1.0, 0.0);
            if (TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, point)) {
                points.add(point);
            }
        }
        return points;
    }

    private boolean suppressionPointsChanged(List<Vec3> current, List<Vec3> updated) {
        if (current.size() != updated.size()) {
            return true;
        }
        for (Vec3 point : updated) {
            boolean matched = current.stream().anyMatch(existing ->
                existing.distanceToSqr(point) <= AUTO_SUPPRESS_POINT_CHANGE_DISTANCE_SQ);
            if (!matched) {
                return true;
            }
        }
        return false;
    }
}

package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.cover.CoverProtectionContext;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Standalone machine-gunner combat goal. The rifleman goal is used only as a
 * private role-neutral combat controller; it is never installed in the
 * machine-gunner goal selector and therefore cannot add work to riflemen.
 */
public final class MachineGunnerCombatGoal extends Goal implements CombatGoalController {
    private static final int AUTO_SUPPRESS_EVALUATION_INTERVAL = 20;
    private static final double AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE = 4.0;
    private static final double AUTO_SUPPRESS_POINT_CHANGE_DISTANCE_SQ = 0.75 * 0.75;

    private final MachineGunnerEntity soldier;
    private final SoldierCombatGoal combatController;
    private int autoSuppressCooldown;

    public MachineGunnerCombatGoal(SoldierEntity soldier) {
        if (!(soldier instanceof MachineGunnerEntity machineGunner)) {
            throw new IllegalArgumentException("MachineGunnerCombatGoal requires a machine gunner");
        }
        this.soldier = machineGunner;
        this.combatController = new SoldierCombatGoal(soldier, true);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (combatController.canUse()) {
            return true;
        }
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return false;
        }
        return soldier.getSuppressionCenter() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return combatController.canContinueToUse()
            || (!soldier.isHealing() && !soldier.isPreparingOrReloading()
                && !soldier.isRecalling() && soldier.getSuppressionCenter() != null);
    }

    @Override
    public void start() {
        combatController.start();
        autoSuppressCooldown = 0;
    }

    @Override
    public void stop() {
        combatController.stop();
        autoSuppressCooldown = 0;
    }

    @Override
    public void tick() {
        combatController.tick();
        if (--autoSuppressCooldown > 0) {
            return;
        }
        autoSuppressCooldown = AUTO_SUPPRESS_EVALUATION_INTERVAL;
        maybeAutoSuppress();
    }

    private void maybeAutoSuppress() {
        if (soldier.isHealing() || soldier.isPreparingOrReloading() || soldier.isRecalling()) {
            return;
        }
        LivingEntity target = soldier.getTarget();
        if (target != null && target.isAlive()) {
            return;
        }
        if (soldier.isAutonomousSuppressionActive() && soldier.hasValidPingSuppressPos()) {
            refreshAutonomousSuppression();
            return;
        }
        if (isSuppressing() || soldier.hasValidPingSuppressPos()) {
            return;
        }
        if (!GunIntegration.isTaczLoaded() || !GunIntegration.hasGun(soldier)) {
            return;
        }

        BlockPos center = soldier.getSuppressionCenter();
        if (center == null) {
            return;
        }

        soldier.beginAutonomousSuppression();
        soldier.setPingSuppressPos(center);
        refreshAutonomousSuppression();
        forceRestartPingSuppression();
    }

    private void refreshAutonomousSuppression() {
        BlockPos center = soldier.getSuppressionCenter();
        if (center == null) {
            soldier.clearAutonomousSuppression();
            soldier.clearPingSuppressPos();
            return;
        }

        BlockPos current = soldier.getPingSuppressPos();
        if (current == null || current.distSqr(center)
            > AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE * AUTO_SUPPRESS_CENTER_SWITCH_DISTANCE) {
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

    @Override
    public Vec3 getProneFiringAimPoint(LivingEntity target) {
        return combatController.getProneFiringAimPoint(target);
    }

    @Override
    public void setFiringPronePositionAuthorized(boolean authorized) {
        combatController.setFiringPronePositionAuthorized(authorized);
    }

    @Override
    public boolean isFiringPronePositionAuthorized() {
        return combatController.isFiringPronePositionAuthorized();
    }

    @Override
    public void tickFiringPronePositionFromCover() {
        combatController.tickFiringPronePositionFromCover();
    }

    @Override
    public List<LivingEntity> getPotentialTargets() {
        return combatController.getPotentialTargets();
    }

    @Override
    public boolean hasDetectedTargets() {
        return combatController.hasDetectedTargets();
    }

    @Override
    @Nullable
    public LivingEntity getCurrentTarget() {
        return combatController.getCurrentTarget();
    }

    @Override
    public DetectionSystem getDetectionSystem() {
        return combatController.getDetectionSystem();
    }

    @Override
    public void onEnemyGunshot(LivingEntity shooter, GunIntegration.GunshotSignature signature) {
        combatController.onEnemyGunshot(shooter, signature);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        combatController.setTarget(target);
    }

    @Override
    public CoverProtectionContext resolveCoverProtectionContext() {
        return combatController.resolveCoverProtectionContext();
    }

    @Override
    public void onTargetKilledByTeammate(UUID killedThreatId) {
        combatController.onTargetKilledByTeammate(killedThreatId);
    }

    @Override
    public boolean isSuppressing() {
        return combatController.isSuppressing();
    }

    @Override
    public boolean canShootPrimaryTarget() {
        return combatController.canShootPrimaryTarget();
    }

    @Override
    public int getTotalAmmo() {
        return combatController.getTotalAmmo();
    }

    @Override
    public void forceRestartPingSuppression() {
        combatController.forceRestartPingSuppression();
    }
}

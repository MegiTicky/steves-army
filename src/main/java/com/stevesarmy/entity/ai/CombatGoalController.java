package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.cover.CoverProtectionContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Narrow combat services shared by entity helpers and role-specific goals.
 * The goal that owns the state may be a rifleman goal or a standalone role goal.
 */
public interface CombatGoalController {
    Vec3 getProneFiringAimPoint(LivingEntity target);

    void setFiringPronePositionAuthorized(boolean authorized);

    boolean isFiringPronePositionAuthorized();

    void tickFiringPronePositionFromCover();

    List<LivingEntity> getPotentialTargets();

    boolean hasDetectedTargets();

    @Nullable
    LivingEntity getCurrentTarget();

    DetectionSystem getDetectionSystem();

    void onEnemyGunshot(LivingEntity shooter, GunIntegration.GunshotSignature signature);

    void setTarget(@Nullable LivingEntity target);

    CoverProtectionContext resolveCoverProtectionContext();

    void onTargetKilledByTeammate(UUID killedThreatId);

    boolean isSuppressing();

    boolean canShootPrimaryTarget();

    int getTotalAmmo();

    void forceRestartPingSuppression();
}

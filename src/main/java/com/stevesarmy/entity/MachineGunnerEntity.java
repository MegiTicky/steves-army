package com.stevesarmy.entity;

import com.stevesarmy.entity.ai.MachineGunnerSupportGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Machine gunner role. Uses the rifleman pipeline: identical goal layout, state
 * machine, ping handling, and movement. MG-specific behavior is layered on as
 * small, isolated additions (e.g. cover evaluation bias) instead of a separate
 * pipeline.
 */
public class MachineGunnerEntity extends SoldierEntity {

    public MachineGunnerEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        getCoverBehaviorManager().setProtectedMachineGunnerPolicy(true);
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.MACHINE_GUNNER;
    }

    @Override
    protected Goal initializeCombatGoal() {
        SoldierCombatGoal goal = new SoldierCombatGoal(this, true);
        this.combatGoal = goal;
        this.combatGoalTask = goal;
        return goal;
    }

    @Override
    protected Goal initializeCoverTacticalGoal() {
        MachineGunnerSupportGoal goal = new MachineGunnerSupportGoal(this);
        this.coverTacticalGoal = goal;
        this.coverTacticalGoalTask = goal;
        return goal;
    }

    /**
     * Immediate suppression target: the weighted squad threat centroid, falling
     * back to the local primary threat and then any ping threat. No sticky
     * sector hysteresis.
     */
    @Nullable
    public BlockPos getSuppressionCenter() {
        BlockPos candidate = getBestSquadThreatPosition();
        if (candidate == null) {
            candidate = getThreatAwareness().getPrimaryThreatPosition();
        }
        if (candidate == null && hasValidPingThreatPos()) {
            candidate = getPingThreatPos();
        }
        return candidate;
    }

    /**
     * Aims cover evaluation (protection filter and fire-lane scoring) at the
     * attack objective during ATTACK so the MG advances with the squad, and at
     * the suppression center otherwise. Falls back to rifleman behavior when no
     * aim point exists.
     */
    @Nullable
    public Vec3 getPreferredCoverEvaluationDirection() {
        BlockPos aim = hasValidAttackTarget() ? getAttackTargetPos() : getSuppressionCenter();
        if (aim == null) {
            return null;
        }
        Vec3 direction = new Vec3(
            aim.getX() + 0.5 - getX(),
            0.0,
            aim.getZ() + 0.5 - getZ());
        return direction.lengthSqr() > 0.001 ? direction.normalize() : null;
    }

    @Nullable
    private BlockPos getBestSquadThreatPosition() {
        if (!(level() instanceof ServerLevel serverLevel) || getSquadId() == null) {
            return null;
        }
        long now = level().getGameTime();
        return SquadManager.get(serverLevel).getSquadById(getSquadId())
            .map(squad -> {
                double x = 0.0;
                double y = 0.0;
                double z = 0.0;
                double totalWeight = 0.0;
                for (SquadThreatIntel.ThreatKnowledge threat : squad.getThreatIntel().getAllThreats()) {
                    if (!threat.isAlive || threat.lastKnownPosition == null
                        || squad.getThreatIntel().isThreatStale(threat.threatEntityId, now)) {
                        continue;
                    }
                    long age = Math.max(0L, now - threat.lastSeenTime);
                    double freshness = Math.max(0.25, 1.0 - age / 120.0);
                    double weight = Math.max(0.25, threat.accuracy) * freshness;
                    x += (threat.lastKnownPosition.getX() + 0.5) * weight;
                    y += (threat.lastKnownPosition.getY() + 0.5) * weight;
                    z += (threat.lastKnownPosition.getZ() + 0.5) * weight;
                    totalWeight += weight;
                }
                return totalWeight > 0.0
                    ? BlockPos.containing(x / totalWeight, y / totalWeight, z / totalWeight)
                    : null;
            })
            .orElse(null);
    }
}

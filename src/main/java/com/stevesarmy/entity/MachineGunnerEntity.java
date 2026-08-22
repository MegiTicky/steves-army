package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.MachineGunnerCombatGoal;
import com.stevesarmy.entity.ai.MachineGunnerSupportGoal;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Machine gunner role. Stays behind the squad's line and covers the engagement
 * area with suppression instead of advancing on ATTACK pings.
 */
public class MachineGunnerEntity extends SoldierEntity {
    private static final long SUPPORT_OBJECTIVE_TIMEOUT_MS = 20_000;

    @Nullable
    private BlockPos supportObjectivePos = null;
    private long supportObjectiveTimestamp = 0;

    public MachineGunnerEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public SoldierRole getRole() {
        return SoldierRole.MACHINE_GUNNER;
    }

    @Override
    protected SoldierCombatGoal initializeCombatGoal() {
        this.combatGoal = new MachineGunnerCombatGoal(this);
        return this.combatGoal;
    }

    @Override
    protected CoverTacticalGoal initializeCoverTacticalGoal() {
        this.coverTacticalGoal = new MachineGunnerSupportGoal(this);
        return this.coverTacticalGoal;
    }

    /**
     * ATTACK pings never trigger an advance for the machine gunner. Instead the
     * pinged position becomes a support objective that the gunner holds and
     * suppresses from its current line. Any other ping supersedes the objective.
     */
    @Override
    public void receivePing(PingType type, @Nullable Vec3 position) {
        if (type == PingType.ATTACK) {
            handleSupportAttackPing(position);
            return;
        }
        this.supportObjectivePos = null;
        this.supportObjectiveTimestamp = 0;
        super.receivePing(type, position);
    }

    private void handleSupportAttackPing(@Nullable Vec3 position) {
        if (position == null) {
            return;
        }
        setSquadMode(SquadMode.HOLD);
        setHoldPosition(blockPosition());
        clearPingMoveTarget();
        clearPingSuppressPos();
        this.supportObjectivePos = BlockPos.containing(position);
        this.supportObjectiveTimestamp = System.currentTimeMillis();
        StevesArmyMod.LOGGER.info("[MachineGunner] ATTACK ping -> support objective at {}", this.supportObjectivePos);
    }

    public boolean hasValidSupportObjective() {
        return this.supportObjectivePos != null
            && System.currentTimeMillis() - this.supportObjectiveTimestamp < SUPPORT_OBJECTIVE_TIMEOUT_MS;
    }

    @Nullable
    public BlockPos getSupportObjectivePos() {
        return hasValidSupportObjective() ? this.supportObjectivePos : null;
    }

    /**
     * Suppression center priority: attack objective, then the strongest tracked
     * threat, then the pinged threat direction. Drives auto-suppression and the
     * rear support position search.
     */
    @Nullable
    public BlockPos getSuppressionCenter() {
        BlockPos objective = getSupportObjectivePos();
        if (objective != null) {
            return objective;
        }
        BlockPos threatPos = getThreatAwareness().getPrimaryThreatPosition();
        if (threatPos != null) {
            return threatPos;
        }
        if (hasValidPingThreatPos()) {
            return getPingThreatPos();
        }
        return null;
    }
}

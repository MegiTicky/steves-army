package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.MachineGunnerCombatGoal;
import com.stevesarmy.entity.ai.MachineGunnerSupportGoal;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.ping.PingType;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Machine gunner role. Stays behind the squad's line and covers the engagement
 * area with suppression instead of advancing on ATTACK pings.
 */
public class MachineGunnerEntity extends SoldierEntity {
    private static final long SUPPORT_OBJECTIVE_TIMEOUT_MS = 20_000;
    private static final int SECTOR_MIN_HOLD_TICKS = 120;
    private static final int SECTOR_SWITCH_CONFIRM_TICKS = 40;
    private static final int SECTOR_CONTACT_GRACE_TICKS = 160;
    private static final int SECTOR_SWITCH_DISTANCE_SQ = 8 * 8;

    public enum SuppressionSectorSource {
        ATTACK_OBJECTIVE,
        SQUAD_THREAT,
        LOCAL_THREAT,
        PING_THREAT
    }

    @Nullable
    private BlockPos supportObjectivePos = null;
    private long supportObjectiveTimestamp = 0;
    @Nullable private BlockPos activeSuppressionCenter;
    @Nullable private BlockPos pendingSuppressionCenter;
    @Nullable private SuppressionSectorSource activeSectorSource;
    private long activeSectorStartedTick;
    private long activeSectorLastConfirmedTick;
    private long pendingSectorStartedTick;

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
        clearSuppressionSector();
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
     * Resolves a sticky engagement sector for firing-lane selection. Direct fire
     * may switch targets immediately, but a new enemy report cannot move the MG
     * unless it remains a distinct sector long enough to justify relocation.
     */
    @Nullable
    public BlockPos getSuppressionCenter() {
        BlockPos objective = getSupportObjectivePos();
        if (objective != null) {
            activateSuppressionSector(objective, SuppressionSectorSource.ATTACK_OBJECTIVE, level().getGameTime());
            return activeSuppressionCenter;
        }

        BlockPos candidate = getBestSquadThreatPosition();
        SuppressionSectorSource source = candidate != null ? SuppressionSectorSource.SQUAD_THREAT : null;
        BlockPos threatPos = getThreatAwareness().getPrimaryThreatPosition();
        if (candidate == null && threatPos != null) {
            candidate = threatPos;
            source = SuppressionSectorSource.LOCAL_THREAT;
        }
        if (candidate == null && hasValidPingThreatPos()) {
            candidate = getPingThreatPos();
            source = SuppressionSectorSource.PING_THREAT;
        }
        return resolveSuppressionSector(candidate, source);
    }

    @Nullable
    private BlockPos getBestSquadThreatPosition() {
        if (!(level() instanceof ServerLevel serverLevel) || getSquadId() == null) {
            return null;
        }
        long now = level().getGameTime();
        return SquadManager.get(serverLevel).getSquadById(getSquadId())
            .flatMap(squad -> squad.getThreatIntel().getAllThreats().stream()
                .filter(threat -> threat.isAlive && threat.lastKnownPosition != null
                    && !squad.getThreatIntel().isThreatStale(threat.threatEntityId, now))
                .max(java.util.Comparator.comparingDouble((SquadThreatIntel.ThreatKnowledge threat) -> threat.accuracy)
                    .thenComparingLong(threat -> threat.lastSeenTime))
                .map(threat -> threat.lastKnownPosition))
            .orElse(null);
    }

    @Nullable
    private BlockPos resolveSuppressionSector(@Nullable BlockPos candidate,
                                              @Nullable SuppressionSectorSource source) {
        long now = level().getGameTime();
        if (activeSuppressionCenter == null) {
            if (candidate != null) {
                activateSuppressionSector(candidate, source, now);
            }
            return activeSuppressionCenter;
        }

        if (candidate == null) {
            if (now - activeSectorLastConfirmedTick > SECTOR_CONTACT_GRACE_TICKS) {
                clearSuppressionSector();
            }
            return activeSuppressionCenter;
        }

        if (activeSuppressionCenter.distSqr(candidate) <= SECTOR_SWITCH_DISTANCE_SQ) {
            activeSectorLastConfirmedTick = now;
            pendingSuppressionCenter = null;
            return activeSuppressionCenter;
        }

        if (now - activeSectorStartedTick < SECTOR_MIN_HOLD_TICKS) {
            updatePendingSector(candidate, now);
            return activeSuppressionCenter;
        }

        updatePendingSector(candidate, now);
        if (now - pendingSectorStartedTick >= SECTOR_SWITCH_CONFIRM_TICKS) {
            activateSuppressionSector(candidate, source, now);
        }
        return activeSuppressionCenter;
    }

    private void updatePendingSector(BlockPos candidate, long now) {
        if (pendingSuppressionCenter == null
            || pendingSuppressionCenter.distSqr(candidate) > SECTOR_SWITCH_DISTANCE_SQ) {
            pendingSuppressionCenter = candidate.immutable();
            pendingSectorStartedTick = now;
        }
    }

    private void activateSuppressionSector(BlockPos center, @Nullable SuppressionSectorSource source, long now) {
        if (activeSuppressionCenter != null && activeSuppressionCenter.equals(center)
            && activeSectorSource == source) {
            activeSectorLastConfirmedTick = now;
            pendingSuppressionCenter = null;
            return;
        }
        activeSuppressionCenter = center.immutable();
        activeSectorSource = source;
        activeSectorStartedTick = now;
        activeSectorLastConfirmedTick = now;
        pendingSuppressionCenter = null;
        pendingSectorStartedTick = 0;
    }

    private void clearSuppressionSector() {
        activeSuppressionCenter = null;
        activeSectorSource = null;
        pendingSuppressionCenter = null;
        activeSectorStartedTick = 0;
        activeSectorLastConfirmedTick = 0;
        pendingSectorStartedTick = 0;
    }

    public String getSuppressionSectorDebug() {
        long now = level().getGameTime();
        String active = activeSuppressionCenter != null
            ? activeSuppressionCenter.getX() + "," + activeSuppressionCenter.getY() + "," + activeSuppressionCenter.getZ()
            : "none";
        String pending = pendingSuppressionCenter != null
            ? pendingSuppressionCenter.getX() + "," + pendingSuppressionCenter.getY() + "," + pendingSuppressionCenter.getZ()
            : "none";
        return "active=" + active + " source=" + (activeSectorSource != null ? activeSectorSource : "none")
            + " held=" + (activeSuppressionCenter != null ? now - activeSectorStartedTick : 0) + "t"
            + " pending=" + pending;
    }
}

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
    private static final int SECTOR_MIN_HOLD_TICKS = 120;
    private static final int SECTOR_SWITCH_CONFIRM_TICKS = 40;
    private static final int SECTOR_CONTACT_GRACE_TICKS = 160;
    private static final int SECTOR_SWITCH_DISTANCE_SQ = 8 * 8;

    public enum SuppressionSectorSource {
        SQUAD_THREAT,
        LOCAL_THREAT,
        PING_THREAT
    }

    @Nullable
    private BlockPos supportObjectivePos = null;
    private boolean supportAttackActive;
    @Nullable private BlockPos activeSuppressionCenter;
    @Nullable private BlockPos pendingSuppressionCenter;
    @Nullable private SuppressionSectorSource activeSectorSource;
    private long activeSectorStartedTick;
    private long activeSectorLastConfirmedTick;
    private long pendingSectorStartedTick;
    private boolean autonomousSuppressionActive;

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
     * ATTACK pings never redirect the machine gunner's suppression sector. They
     * hold the gunner in place while the current sticky sector continues to own
     * firing-lane selection; other pings resume the normal command behavior.
     */
    @Override
    public void receivePing(PingType type, @Nullable Vec3 position) {
        if (type == PingType.ATTACK) {
            handleSupportAttackPing(position);
            return;
        }
        boolean wasAutonomousSuppressionActive = autonomousSuppressionActive;
        clearAutonomousSuppression();
        if (wasAutonomousSuppressionActive) {
            clearPingSuppressPos();
            if (getCombatGoal() != null) {
                getCombatGoal().forceRestartPingSuppression();
            }
        }
        clearSupportAttack();
        clearSuppressionSector();
        super.receivePing(type, position);
    }

    public boolean isAutonomousSuppressionActive() {
        return autonomousSuppressionActive;
    }

    public void beginAutonomousSuppression() {
        autonomousSuppressionActive = true;
    }

    public void clearAutonomousSuppression() {
        autonomousSuppressionActive = false;
    }

    private void handleSupportAttackPing(@Nullable Vec3 position) {
        if (position == null) {
            return;
        }
        this.supportObjectivePos = BlockPos.containing(position).immutable();
        this.supportAttackActive = true;
        setSquadMode(SquadMode.HOLD);
        setHoldPosition(blockPosition());
        clearPingMoveTarget();
        // The attack point is only an order-lifecycle reference. It must never
        // replace the threat-driven suppression center or cancel an active
        // suppression assignment.
        StevesArmyMod.LOGGER.info("[MachineGunner] ATTACK ping -> support order at {} (holding current threat sector)",
            this.supportObjectivePos);
    }

    public boolean hasValidSupportObjective() {
        return supportAttackActive && this.supportObjectivePos != null;
    }

    @Nullable
    public BlockPos getSupportObjectivePos() {
        return hasValidSupportObjective() ? this.supportObjectivePos : null;
    }

    /** True while this MG is supporting a rifle element's active ATTACK order. */
    public boolean isAttackSupportActive() {
        return hasValidSupportObjective();
    }

    /**
     * Called by SquadActivityManager once the rifle element reaches the attack
     * objective. The MG remains in its current cover and may finish suppressing,
     * but it no longer treats the old attack order as an active support request.
     */
    public void completeAttackSupport(@Nullable BlockPos objective) {
        if (!supportAttackActive || supportObjectivePos == null
            || (objective != null && !supportObjectivePos.equals(objective))) {
            return;
        }
        BlockPos completed = supportObjectivePos;
        clearSupportAttack();
        StevesArmyMod.LOGGER.info("[MachineGunner] ATTACK support complete at {}", completed);
    }

    private void clearSupportAttack() {
        supportObjectivePos = null;
        supportAttackActive = false;
    }

    /**
     * Resolves a sticky engagement sector for firing-lane selection. Direct fire
     * may switch targets immediately, but a new enemy report cannot move the MG
     * unless it remains a distinct sector long enough to justify relocation.
     */
    @Nullable
    public BlockPos getSuppressionCenter() {
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

package com.stevesarmy.sim.scenario;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.DangerSampler;
import com.stevesarmy.sim.terrain.SimTerrain;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live handle a scenario script uses: spawn labeled friendlies/enemies/anchor,
 * inject deterministic suppression, teleport actors, apply damage. One instance
 * per scenario run; all actors are discarded at cleanup.
 */
public final class SimContext {
    public final ServerLevel level;
    public final SimTerrain terrain;
    public final List<SoldierEntity> friendlies = new ArrayList<>();
    public final List<EnemySoldierEntity> enemies = new ArrayList<>();
    public final Map<String, SoldierEntity> byLabel = new LinkedHashMap<>();
    public TargetEntity anchor;
    /** Every anchor this scenario spawned — discardAll must remove all of them,
     * not just the last (a leaked anchor persists in run/world and pollutes
     * later runs that spawn at the same coordinates). */
    public final List<TargetEntity> anchors = new ArrayList<>();

    /** Synthetic threat sighting written straight into a squad's shared intel (deterministic, no entity). */
    public record InjectedThreat(UUID threatId, Vec3 eye, float accuracy, BlockPos surface, UUID owningSquadId) {}

    private final List<InjectedThreat> injectedThreats = new ArrayList<>();
    /** Threats killed via markInjectedThreatsDead — excluded from the end-of-run oracle. */
    private final java.util.Set<UUID> deadInjectedThreats = new java.util.HashSet<>();
    private DangerSampler dangerSampler;
    /** Fixture-local verification region {x1,z1,x2,z2} for the danger oracle; null = whole field inset by 3. */
    private int[] dangerVerificationRegion;
    private boolean dangerSweepEnabled = true;

    public SimContext(ServerLevel level, SimTerrain terrain) {
        this.level = level;
        this.terrain = terrain;
    }

    /** Immobile owner anchor the FOLLOW squad tracks (a real LivingEntity for getOwner()). */
    public TargetEntity spawnAnchor(int x, int y, int z) {
        anchor = ModEntities.TARGET.get().create(level);
        anchor.moveTo(terrain.at(x, y, z).getX() + 0.5, terrain.at(x, y, z).getY(),
            terrain.at(x, y, z).getZ() + 0.5, 0, 0);
        anchor.setPersistenceRequired();
        level.addFreshEntity(anchor);
        anchors.add(anchor);
        return anchor;
    }

    public SoldierEntity spawnFriendly(EntityType<? extends SoldierEntity> type, String label,
                                       int x, int y, int z, SquadMode mode) {
        SoldierEntity soldier = type.create(level);
        soldier.moveTo(terrain.at(x, y, z).getX() + 0.5, terrain.at(x, y, z).getY(),
            terrain.at(x, y, z).getZ() + 0.5, 0, 0);
        soldier.setPersistenceRequired();
        if (anchor != null) {
            soldier.setOwnerUUID(anchor.getUUID());
        }
        soldier.setFireTeam(FireTeam.ALPHA);
        soldier.setSquadMode(mode);
        level.addFreshEntity(soldier);
        if (anchor != null) {
            SquadManager mgr = SquadManager.get(level);
            SquadData squad = mgr.getSquadByLeader(anchor.getUUID())
                .orElseGet(() -> mgr.createSquad(anchor.getUUID()));
            squad.setMode(mode);
            soldier.setSquadId(squad.getSquadId());
            mgr.addMemberToSquad(squad.getSquadId(), soldier.getUUID());
        }
        friendlies.add(soldier);
        byLabel.put(label, soldier);
        return soldier;
    }

    public EnemySoldierEntity spawnEnemy(int x, int y, int z) {
        EnemySoldierEntity enemy = ModEntities.ENEMY_SOLDIER.get().create(level);
        enemy.moveTo(terrain.at(x, y, z).getX() + 0.5, terrain.at(x, y, z).getY(),
            terrain.at(x, y, z).getZ() + 0.5, 0, 0);
        enemy.setPersistenceRequired();
        level.addFreshEntity(enemy);
        enemies.add(enemy);
        return enemy;
    }

    /** Enemy with a label so injection/assertion helpers can target it. */
    public EnemySoldierEntity spawnEnemyLabeled(String label, int x, int y, int z) {
        EnemySoldierEntity enemy = spawnEnemy(x, y, z);
        byLabel.put(label, enemy);
        return enemy;
    }

    /** Strip a soldier's guns so it cannot return fire (scenario pressure control). */
    public void disarmSoldier(String label) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier == null) return;
        com.stevesarmy.inventory.SoldierInventory inv = soldier.getSoldierInventory();
        inv.setItem(com.stevesarmy.inventory.SoldierInventory.SLOT_MAIN_HAND,
            net.minecraft.world.item.ItemStack.EMPTY);
        inv.setItem(com.stevesarmy.inventory.SoldierInventory.SLOT_SIDEARM,
            net.minecraft.world.item.ItemStack.EMPTY);
        soldier.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
        com.stevesarmy.sim.SimGunHandler.disarm(soldier);
    }

    /**
     * Makes an entity ignore damage so scenario pressure (getting shot) cannot
     * knock it off scripted movement — used for patrol targets whose motion is
     * part of the measurement.
     */
    public void harden(String label) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier != null) soldier.setInvulnerable(true);
    }

    /** Deterministic pressure: repeated near-misses against the soldier's own tracker. */
    public void injectSuppression(String labelOrNull, int nearMissBursts) {
        List<SoldierEntity> targets = labelOrNull == null
            ? friendlies : List.of(byLabel.get(labelOrNull));
        for (SoldierEntity soldier : targets) {
            if (soldier == null || !soldier.isAlive()) continue;
            for (int i = 0; i < nearMissBursts; i++) {
                soldier.getCoverBehaviorManager().onNearMiss(soldier.position(), soldier, 1.0f);
            }
        }
    }

    /**
     * Deterministic pressure from a fixed firing origin: the near-miss also
     * carries the origin, so the full suppressed-traveler chain (squad intel
     * feed, danger-map bake, travel reroute, crawl posture) sees a shooter
     * position without needing a live enemy entity.
     */
    public void injectSuppressionFrom(String label, int nearMissBursts,
                                      double originX, double originY, double originZ) {
        injectSuppressionFrom(label, nearMissBursts, originX, originY, originZ, 1.0f);
    }

    /**
     * Deterministic pressure from a fixed firing origin: the near-miss also
     * carries the origin, so the full suppressed-traveler chain (squad intel
     * feed, danger-map bake, travel reroute, crawl posture) sees a shooter
     * position without needing a live enemy entity. Real TaCZ rounds travel
     * fast enough to cap the tracker's speed multiplier at 3.0 - pass
     * {@code bulletSpeed=3.0f} for realistic per-round gain.
     */
    public void injectSuppressionFrom(String label, int nearMissBursts,
                                      double originX, double originY, double originZ, float bulletSpeed) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier == null || !soldier.isAlive()) return;
        net.minecraft.world.phys.Vec3 origin = new net.minecraft.world.phys.Vec3(originX, originY, originZ);
        for (int i = 0; i < nearMissBursts; i++) {
            net.minecraft.world.phys.Vec3 bulletPath = soldier.position().add(0.5, 0, 0);
            soldier.getCoverBehaviorManager().onNearMiss(bulletPath, soldier, bulletSpeed, null, origin);
        }
    }

    /**
     * {@link #injectSuppressionFrom} with the origin Y resolved against this
     * scenario's terrain slab - fixtures stack 4Y apart, so absolute Y literals
     * point into another scenario's slab whenever more than one scenario runs.
     */
    public void injectSuppressionFromGround(String label, int nearMissBursts,
                                            double originX, double originZ, double heightAboveSurface) {
        injectSuppressionFromGround(label, nearMissBursts, originX, originZ, heightAboveSurface, 1.0f);
    }

    public void injectSuppressionFromGround(String label, int nearMissBursts,
                                            double originX, double originZ, double heightAboveSurface,
                                            float bulletSpeed) {
        BlockPos surface = terrain.at((int) Math.floor(originX), 0, (int) Math.floor(originZ));
        injectSuppressionFrom(label, nearMissBursts, originX, surface.getY() + heightAboveSurface, originZ, bulletSpeed);
    }

    /** Fresh enemy at a new spot (discard + spawn so threat intel re-detects cleanly). */
    public EnemySoldierEntity moveEnemy(EnemySoldierEntity old, int x, int y, int z) {
        old.discard();
        enemies.remove(old);
        return spawnEnemy(x, y, z);
    }

    /**
     * Re-issues a walk order toward the farther of two lane endpoints on the
     * enemy's current row, so calling it periodically keeps the enemy patrolling
     * the lane between (currentX, zA) and (currentX, zB) without toggle state.
     */
    public void strafeEnemy(String label, double zA, double zB) {
        SoldierEntity entity = byLabel.get(label);
        if (!(entity instanceof EnemySoldierEntity enemy) || !enemy.isAlive()) return;
        double z = enemy.getZ();
        double target = Math.abs(z - zA) >= Math.abs(z - zB) ? zA : zB;
        enemy.getNavigation().moveTo(entity.getX(), entity.blockPosition().getY(), target + 0.5D, 1.0D);
    }

    /**
     * Issues a real activity-backed ATTACK order for the whole squad: the
     * anchor is the order owner (SquadActivityManager records it), every
     * friendly receives the ping. This is the clearing-doctrine path —
     * scenarios that want the legacy proximity semantics call
     * {@code soldier.setAttackTarget(...)} directly instead.
     */
    public void issueAttackOrder(int x, int z) {
        if (anchor == null) {
            throw new IllegalStateException("spawn an anchor before issueAttackOrder — the anchor owns the order");
        }
        BlockPos objective = terrain.at(x, 0, z);
        com.stevesarmy.squad.SquadActivityManager.applyCommandCore(
            anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL,
            com.stevesarmy.ping.PingType.ATTACK, objective,
            level.dimension().location().hashCode(), List.copyOf(friendlies));
        for (SoldierEntity soldier : friendlies) {
            soldier.receivePing(com.stevesarmy.ping.PingType.ATTACK,
                new Vec3(objective.getX() + 0.5, objective.getY(), objective.getZ() + 0.5));
        }
    }

    /**
     * GO_TO ping through the network-path sequence (PingMessage): stale ATTACK
     * state clears first, then each soldier receives the ping (bumping the
     * move generation), then the squad activity is replaced. Re-calling this
     * reproduces the mashed-ping volley the GO_TO strand reacts to.
     */
    public void issueGoTo(int x, int z) {
        if (anchor == null) {
            throw new IllegalStateException("spawn an anchor before issueGoTo — the anchor owns the order");
        }
        BlockPos objective = terrain.at(x, 0, z);
        for (SoldierEntity soldier : friendlies) {
            soldier.clearAttackTarget();
        }
        for (SoldierEntity soldier : friendlies) {
            soldier.receivePing(com.stevesarmy.ping.PingType.GO_TO,
                new Vec3(objective.getX() + 0.5, objective.getY(), objective.getZ() + 0.5));
        }
        com.stevesarmy.squad.SquadActivityManager.applyCommandCore(
            anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL,
            com.stevesarmy.ping.PingType.GO_TO, objective,
            level.dimension().location().hashCode(), List.copyOf(friendlies));
    }

    // --- danger-map verification seams ---------------------------------------

    /**
     * Writes a synthetic threat sighting directly into the friendly squad's
     * shared intel at the platform surface — no entity, fully deterministic.
     * The oracle uses the recorded position, not the intel entry.
     */
    public InjectedThreat injectThreat(double x, double z) {
        return injectThreat(x, z, 1.0f);
    }

    public InjectedThreat injectThreat(double x, double z, float accuracy) {
        SquadData squad = squadForThreats();
        if (squad == null) {
            throw new IllegalStateException("spawn an anchor before injectThreat — the squad owns the intel");
        }
        return injectThreat(squad, x, z, accuracy);
    }

    /** Squad-targeted injection for multi-squad scenarios. */
    public InjectedThreat injectThreat(SquadData squad, double x, double z) {
        return injectThreat(squad, x, z, 1.0f);
    }

    public InjectedThreat injectThreat(SquadData squad, double x, double z, float accuracy) {
        UUID threatId = UUID.randomUUID();
        BlockPos surface = terrain.at((int) x, 0, (int) z);
        squad.getThreatIntel().reportThreatPosition(squad.getLeaderId(), threatId, surface, accuracy, level);
        InjectedThreat threat = new InjectedThreat(threatId,
            new Vec3(surface.getX() + 0.5, surface.getY() + 1.6, surface.getZ() + 0.5), accuracy, surface,
            squad.getSquadId());
        injectedThreats.add(threat);
        return threat;
    }

    /** Re-reports every injected sighting (same ids, same positions) — refreshes intel freshness. */
    public void refreshInjectedThreats() {
        SquadManager mgr = SquadManager.get(level);
        for (InjectedThreat threat : injectedThreats) {
            SquadData squad = mgr.getSquadById(threat.owningSquadId()).orElse(null);
            if (squad == null) continue;
            squad.getThreatIntel().reportThreatPosition(squad.getLeaderId(), threat.threatId(),
                threat.surface(), threat.accuracy(), level);
        }
    }

    /**
     * Teleports an injected sighting: same threat id at a new surface position.
     * The danger map must treat this as origin drift (shadow shifts).
     */
    public InjectedThreat moveInjectedThreat(InjectedThreat threat, double x, double z) {
        SquadManager mgr = SquadManager.get(level);
        SquadData squad = mgr.getSquadById(threat.owningSquadId()).orElse(null);
        BlockPos newSurface = terrain.at((int) x, 0, (int) z);
        Vec3 newEye = new Vec3(newSurface.getX() + 0.5, newSurface.getY() + 1.6, newSurface.getZ() + 0.5);
        InjectedThreat moved = new InjectedThreat(threat.threatId(), newEye, threat.accuracy(), newSurface,
            threat.owningSquadId());
        injectedThreats.set(injectedThreats.indexOf(threat), moved);
        if (squad != null) {
            squad.getThreatIntel().reportThreatPosition(squad.getLeaderId(), threat.threatId(),
                newSurface, threat.accuracy(), level);
        }
        return moved;
    }

    private SquadData squadForThreats() {
        return anchor == null ? null
            : SquadManager.get(level).getSquadByLeader(anchor.getUUID()).orElse(null);
    }

    /** Squad owned by a specific anchor (multi-squad scenarios inject per squad). */
    public SquadData squadForAnchor(TargetEntity anchorEntity) {
        return anchorEntity == null ? null
            : SquadManager.get(level).getSquadByLeader(anchorEntity.getUUID()).orElse(null);
    }

    /** Kills every injected sighting (the intel entry, not an entity) — the decay property probe. */
    public void markInjectedThreatsDead() {
        SquadManager mgr = SquadManager.get(level);
        for (InjectedThreat threat : injectedThreats) {
            deadInjectedThreats.add(threat.threatId());
            mgr.getSquadById(threat.owningSquadId())
                .ifPresent(squad -> squad.getThreatIntel().markThreatDead(threat.threatId()));
        }
    }

    public boolean isInjectedThreatDead(UUID threatId) {
        return deadInjectedThreats.contains(threatId);
    }

    public List<InjectedThreat> injectedThreats() {
        return injectedThreats;
    }

    /** Overrides the danger read SimManager samples per soldier (stub samplers for rig self-tests). */
    public void setDangerSampler(DangerSampler sampler) {
        this.dangerSampler = sampler;
    }

    public DangerSampler dangerSampler() {
        return dangerSampler;
    }

    /** Narrows the oracle verification sweep to a fixture-local region {x1,z1,x2,z2}. */
    public void setDangerVerificationRegion(int x1, int z1, int x2, int z2) {
        this.dangerVerificationRegion = new int[] {x1, z1, x2, z2};
    }

    public int[] dangerVerificationRegion() {
        return dangerVerificationRegion;
    }

    /** Opts a scenario out of the end-of-run sampler-vs-oracle sweep (temporal-property scenarios). */
    public void setDangerSweepEnabled(boolean enabled) {
        this.dangerSweepEnabled = enabled;
    }

    public boolean dangerSweepEnabled() {
        return dangerSweepEnabled;
    }

    public void damageSoldier(String label, float amount) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier != null && soldier.isAlive()) {
            soldier.hurt(level.damageSources().generic(), amount);
        }
    }

    /** Cover-goal snapshot for a label's soldier (null when absent/removed). */
    public com.stevesarmy.combat.CombatStateSnapshot snapshotOf(String label) {
        return snapshotOf(byLabel.get(label));
    }

    public com.stevesarmy.combat.CombatStateSnapshot snapshotOf(SoldierEntity soldier) {
        if (soldier == null || soldier.isRemoved()) return null;
        if (soldier.getCoverTacticalGoal() instanceof com.stevesarmy.entity.ai.CoverTacticalGoal goal) {
            return goal.captureStateSnapshot();
        }
        if (soldier.getCoverTacticalGoal() instanceof com.stevesarmy.entity.ai.SupportCoverGoal supportGoal) {
            return supportGoal.getCoverController().captureStateSnapshot();
        }
        if (soldier.getCoverTacticalGoal() instanceof com.stevesarmy.entity.ai.MachineGunnerSupportGoal mgGoal) {
            return mgGoal.getCoverController().captureStateSnapshot();
        }
        return null;
    }

    /** Knockback impulse (added to delta movement) — stand-in for explosions and melee shoves. */
    public boolean knockback(String label, double x, double z) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier == null || !soldier.isAlive()) return false;
        soldier.push(x, 0.0, z);
        return true;
    }

    /** Shove only while the soldier is idle in claimed cover (hiding or standing in half cover) — the anchor-recovery probe. */
    public boolean knockbackIfIdleInCover(String label, double x, double z) {
        com.stevesarmy.combat.CombatStateSnapshot snap = snapshotOf(label);
        if (snap == null || snap.currentCover == null) return false;
        if (!"HIDING".equals(snap.peekState) && !"STANDING_IN_HALF_COVER".equals(snap.peekState)) return false;
        return knockback(label, x, z);
    }

    /** Shove only while a reload holds the soldier in claimed cover — the reload-damp probe. */
    public boolean knockbackIfReloadingInCover(String label, double x, double z) {
        SoldierEntity soldier = byLabel.get(label);
        if (soldier == null || !soldier.isAlive() || !soldier.isPreparingOrReloading()) return false;
        com.stevesarmy.combat.CombatStateSnapshot snap = snapshotOf(soldier);
        if (snap == null || snap.currentCover == null) return false;
        soldier.push(x, 0.0, z);
        return true;
    }

    public SoldierEntity soldier(String label) {
        return byLabel.get(label);
    }

    public void discardAll() {
        for (SoldierEntity s : friendlies) s.discard();
        for (LivingEntity e : enemies) e.discard();
        for (TargetEntity a : anchors) a.discard();
        friendlies.clear();
        enemies.clear();
        byLabel.clear();
        anchors.clear();
        anchor = null;
    }

    /** Squad cleanup: remove every sim soldier from its squad. */
    public void disbandSquads() {
        SquadManager mgr = SquadManager.get(level);
        for (SoldierEntity soldier : friendlies) {
            mgr.removeMemberFromSquad(soldier.getUUID());
        }
    }
}

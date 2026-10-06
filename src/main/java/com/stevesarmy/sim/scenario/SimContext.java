package com.stevesarmy.sim.scenario;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.terrain.SimTerrain;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

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

    /** Fresh enemy at a new spot (discard + spawn so threat intel re-detects cleanly). */
    public EnemySoldierEntity moveEnemy(EnemySoldierEntity old, int x, int y, int z) {
        old.discard();
        enemies.remove(old);
        return spawnEnemy(x, y, z);
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
        if (anchor != null) anchor.discard();
        friendlies.clear();
        enemies.clear();
        byLabel.clear();
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

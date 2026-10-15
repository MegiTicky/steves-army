package com.stevesarmy.sim;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent ATTACK support fire against a defender that stays behind real cover. */
public final class MgPersistentAttackSuppressionScenario {
    private static final String GUNNER = "gunner";
    private static final String ENEMY = "defender";

    private MgPersistentAttackSuppressionScenario() {}

    public static SimScenario create() {
        final boolean[] hiddenAndActive = {false};
        final boolean[] visibleBeforeHide = {false};
        final boolean[] selectedAimWasPredicted = {false};
        final int[] preObservationShots = {-1};
        final int[] visibleContactShots = {-1};
        final int[] contactsAfterReplacement = {-1};
        final Set<Integer> hiddenShotTicks = new HashSet<>();
        final Set<Integer> predictedShotTicks = new HashSet<>();
        final int[] postReplacementShots = {-1};
        SimScenario.Builder scenario = SimScenario.builder("mg_persistent_attack_suppression")
            .description("An MG sees a defender, then maintains aimed support fire at predicted cover peek openings for over 30 seconds after LOS is lost; replacing ATTACK ends that fire.")
            .duration(950)
            .field(56, 48)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverRow(10, 20, 12, 28, 1);
                t.coverRow(36, 8, 36, 40, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), GUNNER, 14, 0, 24, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "spotter", 14, 0, 34, SquadMode.HOLD);
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled(ENEMY, 42, 0, 24);
                enemy.setNoAi(true);
                enemy.setInvulnerable(true);
                ctx.disarmSoldier(ENEMY);
                ctx.issueAttackOrder(42, 24);
            })
            .action(101, "confirm no MG shot before the defender becomes visible", ctx -> {
                SoldierEntity gunner = ctx.soldier(GUNNER);
                if (gunner != null) preObservationShots[0] = (int) SimGunHandler.acceptedShots().stream()
                    .filter(shot -> gunner.getUUID().equals(shot.shooterId()) && shot.tick() < 101).count();
                logState(ctx, "pre_exposure");
                EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
                if (enemy != null) enemy.moveTo(ctx.terrain.at(30, 0, 24).getX() + 0.5,
                    ctx.terrain.at(30, 0, 24).getY(), ctx.terrain.at(30, 0, 24).getZ() + 0.5, 0, 0);
                logState(ctx, "exposed");
            });

        scenario.action(150, "confirm visible contact and direct fire", ctx -> {
            SoldierEntity gunner = ctx.soldier(GUNNER);
            EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
            if (gunner == null || enemy == null) return;
            visibleBeforeHide[0] = com.stevesarmy.combat.TargetAcquisition.hasLineOfSight(gunner, enemy);
            visibleContactShots[0] = (int) SimGunHandler.acceptedShots().stream()
                .filter(shot -> gunner.getUUID().equals(shot.shooterId()) && shot.tick() >= 101 && shot.tick() <= 150)
                .count();
            logState(ctx, "visible");
        });
        scenario.action(151, "defender returns behind the barrier without changing identity", ctx -> {
            EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
            if (enemy != null) enemy.moveTo(ctx.terrain.at(42, 0, 24).getX() + 0.5,
                ctx.terrain.at(42, 0, 24).getY(), ctx.terrain.at(42, 0, 24).getZ() + 0.5, 0, 0);
            logState(ctx, "hidden");
        });

        for (int tick = 1; tick < 850; tick++) {
            final int at = tick;
            scenario.action(at, "hold firing positions behind the barrier", ctx -> {
                freeze(ctx.soldier(GUNNER), 14.5, 24.5);
                freeze(ctx.soldier("spotter"), 14.5, 34.5);
            });
        }

        for (int tick = 780; tick <= 840; tick += 10) {
            final int sampleTick = tick;
            scenario.action(tick, "sample persistent hidden-target fire", ctx -> {
                SoldierEntity gunner = ctx.soldier(GUNNER);
                EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
                if (gunner == null || enemy == null || !enemy.isAlive()) return;
                boolean noSight = !com.stevesarmy.combat.TargetAcquisition.hasLineOfSight(gunner, enemy);
                if (noSight && gunner.getAttackTargetPos() != null) hiddenAndActive[0] = true;
                List<Vec3> predicted = List.of();
                if (gunner.getCombatGoal() instanceof SoldierCombatGoal combatGoal
                    && combatGoal.getAttackSuppressionDebugContactCount() > 0) {
                    predicted = new CoverFinder(ctx.level).findSuppressionAimPoints(
                        gunner, ctx.terrain.at(30, 0, 24), SoldierEntity.SUPPRESSION_ZONE_RADIUS);
                    Vec3 selectedAim = combatGoal.getAttackSuppressionDebugAimPoint();
                    if (selectedAim != null && predicted.stream().anyMatch(point ->
                        point.distanceToSqr(selectedAim) < 0.04)) selectedAimWasPredicted[0] = true;
                }
                for (SimGunHandler.ShotRecord shot : SimGunHandler.acceptedShots()) {
                    if (!gunner.getUUID().equals(shot.shooterId()) || shot.tick() < 780 || shot.tick() > 840) continue;
                    if (hiddenShotTicks.add(shot.tick()) && predicted.stream().anyMatch(point ->
                        point.distanceToSqr(shot.aimPosition()) < 0.04)) {
                        predictedShotTicks.add(shot.tick());
                    }
                }
                if (sampleTick == 780 || sampleTick == 840) logState(ctx, "hidden_sample_" + sampleTick);
            });
        }
        scenario.action(850, "replace ATTACK with GO_TO", ctx -> {
            ctx.issueGoTo(14, 40);
            logState(ctx, "go_to_replaced");
        });
        scenario.action(890, "count shots after ATTACK replacement", ctx -> {
            SoldierEntity gunner = ctx.soldier(GUNNER);
            if (gunner == null) return;
            if (gunner.getCombatGoal() instanceof SoldierCombatGoal combatGoal) {
                contactsAfterReplacement[0] = combatGoal.getAttackSuppressionDebugContactCount();
            }
            postReplacementShots[0] = (int) SimGunHandler.acceptedShots().stream()
                .filter(shot -> gunner.getUUID().equals(shot.shooterId()) && shot.tick() >= 870)
                .count();
            logState(ctx, "post_replacement");
        });

        return scenario
            .assertThat("no blind MG rounds before observation; visible contact then produced direct fire",
                view -> preObservationShots[0] == 0 && visibleBeforeHide[0] && visibleContactShots[0] > 0)
            .assertThat("hidden observed contact kept ATTACK support active and retained a predicted opening after more than 30 seconds",
                view -> hiddenAndActive[0] && hiddenShotTicks.size() >= 5
                    && predictedShotTicks.size() >= 5 && selectedAimWasPredicted[0])
            .assertThat("MG support memory cleared and fire stopped after ATTACK was replaced",
                view -> contactsAfterReplacement[0] == 0 && postReplacementShots[0] == 0)
            .build();
    }

    private static void freeze(SoldierEntity soldier, double x, double z) {
        if (soldier == null || !soldier.isAlive()) return;
        soldier.setPos(x, soldier.getY(), z);
        soldier.setDeltaMovement(Vec3.ZERO);
        soldier.getNavigation().stop();
    }

    private static void logState(com.stevesarmy.sim.scenario.SimContext ctx, String phase) {
        SoldierEntity gunner = ctx.soldier(GUNNER);
        EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
        if (gunner == null || enemy == null) return;
        SoldierCombatGoal combatGoal = gunner.getCombatGoal() instanceof SoldierCombatGoal goal ? goal : null;
        int contactCount = combatGoal == null ? -1 : combatGoal.getAttackSuppressionDebugContactCount();
        Vec3 selectedAim = combatGoal == null ? null : combatGoal.getAttackSuppressionDebugAimPoint();
        List<SimGunHandler.ShotRecord> shots = SimGunHandler.acceptedShots().stream()
            .filter(shot -> gunner.getUUID().equals(shot.shooterId())).toList();
        SimGunHandler.ShotRecord latest = shots.isEmpty() ? null : shots.get(shots.size() - 1);
        net.minecraft.world.entity.LivingEntity target = gunner.getTarget();
        String recentShotTrace = shots.stream().skip(Math.max(0, shots.size() - 6L))
            .map(shot -> shot.tick() + ":" + shot.aimPosition()).toList().toString();
        com.stevesarmy.StevesArmyMod.LOGGER.info(
            "[MgPersistentAttack] phase={} t={} pos=({}, {}) enemy=({}, {}) alive={} los={} targetId={} targetAlive={} targetLos={} targetRef={} attackPos={} attackValid={} gen={} goal={} contacts={} suppressing={} selectedAim={} hasGun={} anyGunLoaded={} totalAmmo={} currentAmmo={} shots={} recent50={} lastShotTick={} lastShotAim={} recentShotTrace={}",
            phase, ctx.level.getGameTime(), String.format(java.util.Locale.ROOT, "%.2f", gunner.getX()),
            String.format(java.util.Locale.ROOT, "%.2f", gunner.getZ()),
            String.format(java.util.Locale.ROOT, "%.2f", enemy.getX()),
            String.format(java.util.Locale.ROOT, "%.2f", enemy.getZ()), enemy.isAlive(),
            com.stevesarmy.combat.TargetAcquisition.hasLineOfSight(gunner, enemy),
            target == null ? -1 : target.getId(), target != null && target.isAlive(),
            target != null && com.stevesarmy.combat.TargetAcquisition.hasLineOfSight(gunner, target),
            target == enemy, gunner.getAttackTargetPos(), gunner.hasValidAttackTarget(), gunner.getAttackGeneration(),
            combatGoal == null ? "none" : combatGoal.getClass().getSimpleName(), contactCount,
            combatGoal != null && combatGoal.isSuppressing(), selectedAim,
            com.stevesarmy.combat.GunIntegration.hasGun(gunner),
            com.stevesarmy.combat.GunIntegration.isAnyGunLoaded(),
            combatGoal == null ? -1 : combatGoal.getTotalAmmo(),
            com.stevesarmy.combat.GunIntegration.getCurrentAmmo(gunner), shots.size(), shots.stream()
                .filter(shot -> shot.tick() >= gunner.tickCount - 50).count(),
            latest == null ? -1 : latest.tick(), latest == null ? null : latest.aimPosition(), recentShotTrace);
    }
}

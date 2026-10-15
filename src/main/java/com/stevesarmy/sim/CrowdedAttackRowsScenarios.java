package com.stevesarmy.sim;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Crowded live-fire ATTACK traversal fixture for measuring movement in every tick. */
public final class CrowdedAttackRowsScenarios {
    private static final int DURATION = 2400;
    private static final String[] FRIENDLIES = {
        "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
        "golf", "hotel", "india", "juliet", "kilo", "lima"
    };

    private CrowdedAttackRowsScenarios() {}

    public static SimScenario crowdedAttackRows() {
        return crowdedAttackRows(false);
    }

    public static SimScenario crowdedAttackTallRows() {
        return crowdedAttackRows(true);
    }

    private static SimScenario crowdedAttackRows(boolean tallRows) {
        final double[] furthestX = new double[FRIENDLIES.length];
        java.util.Arrays.fill(furthestX, Double.NEGATIVE_INFINITY);
        int rowHeight = tallRows ? 2 : 1;
        String scenarioName = tallRows ? "crowded_attack_tall_rows" : "crowded_attack_rows";
        SimScenario.Builder builder = SimScenario.builder(scenarioName)
            .description("Twelve riflemen with M4A1 movement attributes attack twelve armed defenders across four "
                + "successive " + rowHeight + "-high cover rows. Per-tick traversal sampling measures "
                + "speed during crowding, contact, and row-to-row movement.")
            .duration(DURATION)
            .field(96, 48)
            .armed()
            .terrain(t -> {
                t.platform();
                // Successive cross-lane rows leave alternating flanking gaps.
                t.coverRow(20, 8, 20, 25, rowHeight);
                t.coverRow(33, 23, 33, 40, rowHeight);
                t.coverRow(46, 8, 46, 25, rowHeight);
                t.coverRow(59, 23, 59, 40, rowHeight);
                // Short defender-side cover gives the back line real positions
                // without sealing the objective behind a continuous wall.
                t.coverRow(75, 12, 75, 19, 1);
                t.coverRow(75, 29, 75, 36, 1);
            });

        final int[][] friendlySpawns = {
            {7, 15}, {7, 19}, {7, 23}, {7, 27},
            {10, 13}, {10, 17}, {10, 21}, {10, 25},
            {13, 15}, {13, 19}, {13, 23}, {13, 27}
        };
        final int[][] enemySpawns = {
            {77, 11}, {77, 15}, {77, 19}, {77, 23},
            {77, 27}, {77, 31}, {77, 35}, {77, 39},
            {81, 13}, {81, 18}, {81, 32}, {81, 37}
        };

        builder.setup(ctx -> {
            java.util.Arrays.fill(furthestX, Double.NEGATIVE_INFINITY);
            ctx.spawnAnchor(10, 0, 20);
            for (int i = 0; i < FRIENDLIES.length; i++) {
                int[] spawn = friendlySpawns[i];
                SoldierEntity friendly = ctx.spawnFriendly(
                    ModEntities.SOLDIER.get(), FRIENDLIES[i], spawn[0], 0, spawn[1], SquadMode.HOLD);
                SimGunHandler.useM4A1MovementProfile(friendly);
            }
            for (int i = 0; i < enemySpawns.length; i++) {
                int[] spawn = enemySpawns[i];
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled(
                    "defender_" + i, spawn[0], 0, spawn[1]);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(spawn[0], 0, spawn[1]));
                SimGunHandler.useM4A1MovementProfile(enemy);
            }
            ctx.issueAttackOrder(84, 24);
        });

        TraversalSpeedProbe.attach(builder, DURATION);
        for (int tick = 1; tick <= DURATION; tick++) {
            final int at = tick;
            builder.action(at, "organic route progress", ctx -> {
                for (int i = 0; i < FRIENDLIES.length; i++) {
                    SoldierEntity soldier = ctx.soldier(FRIENDLIES[i]);
                    if (soldier != null && soldier.isAlive()) {
                        furthestX[i] = Math.max(furthestX[i], soldier.getX());
                    }
                }
            });
        }

        return builder
            .assertThat("at least eight friendlies organically cross the first cover row",
                view -> java.util.Arrays.stream(furthestX).filter(x -> x >= 25.0).count() >= 8)
            .assertThat("at least six friendlies progress beyond three cover-row positions",
                view -> java.util.Arrays.stream(furthestX).filter(x -> x >= 50.0).count() >= 6)
            .assertThat("live combat occurs during the advance", view -> !view.shots().isEmpty())
            .build();
    }
}

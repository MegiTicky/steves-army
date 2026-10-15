package com.stevesarmy.sim;

import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.PeekController;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.sim.terrain.SimTerrain;
import com.stevesarmy.squad.SquadMode;

/** Armed crowd probes for traversal during repeated and moved ATTACK orders. */
public final class AttackRepingTraversalScenarios {
    private static final int DURATION = 2400;
    private static final String[] FRIENDLY_LABELS = new String[12];

    static {
        for (int i = 0; i < FRIENDLY_LABELS.length; i++) {
            FRIENDLY_LABELS[i] = "attacker_" + i;
        }
    }

    private AttackRepingTraversalScenarios() {}

    /** Same objective is re-issued while a full squad advances across staggered low cover. */
    public static SimScenario repeatedAttackPing() {
        return build("attack_reping_low_cover_12v12", false,
            new int[][]{{0, 72, 18}, {420, 72, 18}, {900, 72, 18}, {1440, 72, 18}},
            t -> t.longStreetBlock());
    }

    /** Objective moves between lanes behind alternating low and two-block-high chokepoints. */
    public static SimScenario movedAttackPingChokepoints() {
        return build("attack_reping_moved_chokepoints_12v12", true,
            new int[][]{{0, 96, 14}, {480, 94, 32}, {960, 82, 14}, {1440, 96, 32}},
            AttackRepingTraversalScenarios::chokepointCourse);
    }

    private static SimScenario build(String name, boolean movedPing, int[][] orders,
                                     java.util.function.Consumer<SimTerrain> terrain) {
        Participation participation = new Participation();
        SimScenario.Builder builder = SimScenario.builder(name)
            .description("Armed 12v12 ATTACK traversal with M4A1 movement costs, multiple cover lines, "
                + (movedPing ? "alternating chokepoints and moved pings" : "re-issued pings to one objective")
                + ". The traversal probe records per-tick physical displacement; participation checks "
                + "require actual movement and observed cover/peek phases.")
            .duration(DURATION)
            .field(movedPing ? 112 : 96, movedPing ? 48 : 40)
            .armed()
            .terrain(terrain);

        TraversalSpeedProbe.attach(builder, DURATION);
        builder.setup(ctx -> {
            participation.reset();
            spawnFight(ctx, orders[0][1], orders[0][2]);
        });
        for (int i = 1; i < orders.length; i++) {
            int[] order = orders[i];
            builder.action(order[0], movedPing ? "move ATTACK ping to the next lane" : "re-issue ATTACK ping",
                ctx -> ctx.issueAttackOrder(order[1], order[2]));
        }
        for (int tick = 10; tick < DURATION; tick += 5) {
            builder.action(tick, "observe participant movement and cover phases", participation::sample);
        }
        return builder
            .assertThat("at least eight attackers made meaningful physical progress",
                view -> participation.moversAtLeast(8))
            .assertThat("at least four attackers participated in cover approach, docking, or peeking",
                view -> participation.tacticalParticipantsAtLeast(4))
            .assertThat("armed exchange produced tracer fire",
                view -> !view.shots().isEmpty())
            .build();
    }

    private static void spawnFight(SimContext ctx, int objectiveX, int objectiveZ) {
        ctx.spawnAnchor(13, 0, 18);
        for (int i = 0; i < FRIENDLY_LABELS.length; i++) {
            int lane = i / 6;
            int slot = i % 6;
            int x = 13 + lane * 4;
            int z = 11 + slot * 3;
            SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), FRIENDLY_LABELS[i],
                x, 0, z, SquadMode.HOLD);
            SimGunHandler.useM4A1MovementProfile(soldier);
        }
        for (int i = 0; i < 12; i++) {
            int lane = i / 6;
            int slot = i % 6;
            // Keep both ranks clear of House B's x=67 wall and the custom x=75 row.
            int x = objectiveX < 90 ? 64 + lane * 2 : 72 + lane * 4;
            int z = 11 + slot * 3;
            EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("defender_" + i, x, 0, z);
            enemy.setSquadMode(SquadMode.HOLD);
            enemy.setHoldPosition(ctx.terrain.at(x, 0, z));
            SimGunHandler.useM4A1MovementProfile(enemy);
        }
        ctx.issueAttackOrder(objectiveX, objectiveZ);
    }

    private static void chokepointCourse(SimTerrain t) {
        t.platform();
        // Staggered barriers leave alternating north/south gaps, requiring real path and cover choices.
        t.coverRow(27, 10, 27, 19, 1);
        t.coverRow(39, 24, 39, 34, 2);
        t.coverRow(51, 10, 51, 19, 1);
        t.coverRow(63, 24, 63, 34, 2);
        t.coverRow(75, 10, 75, 19, 1);
        t.coverRow(87, 24, 87, 34, 2);
        // Defenders occupy cover on both sides of the terminal approach.
        t.coverRow(94, 11, 94, 20, 1);
        t.coverRow(94, 26, 94, 35, 1);
    }

    private static final class Participation {
        private final double[] startX = new double[FRIENDLY_LABELS.length];
        private final double[] startZ = new double[FRIENDLY_LABELS.length];
        private final boolean[] started = new boolean[FRIENDLY_LABELS.length];
        private final boolean[] moved = new boolean[FRIENDLY_LABELS.length];
        private final boolean[] tactical = new boolean[FRIENDLY_LABELS.length];

        void reset() {
            java.util.Arrays.fill(startX, 0.0);
            java.util.Arrays.fill(startZ, 0.0);
            java.util.Arrays.fill(started, false);
            java.util.Arrays.fill(moved, false);
            java.util.Arrays.fill(tactical, false);
        }

        void sample(SimContext ctx) {
            for (int i = 0; i < FRIENDLY_LABELS.length; i++) {
                SoldierEntity soldier = ctx.soldier(FRIENDLY_LABELS[i]);
                if (soldier == null || !soldier.isAlive()) continue;
                if (!started[i]) {
                    startX[i] = soldier.getX();
                    startZ[i] = soldier.getZ();
                    started[i] = true;
                }
                double dx = soldier.getX() - startX[i];
                double dz = soldier.getZ() - startZ[i];
                if (dx * dx + dz * dz >= 16.0) moved[i] = true;
                CoverBehaviorManager.CoverState state = soldier.getCoverBehaviorManager().getState();
                if (state == CoverBehaviorManager.CoverState.SEEKING_COVER
                    || state == CoverBehaviorManager.CoverState.REPOSITIONING
                    || state == CoverBehaviorManager.CoverState.IN_COVER
                    || state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER
                    || soldier.getSyncedPeekState() == PeekController.State.MOVING_TO_PEEK.ordinal()
                    || soldier.getSyncedPeekState() == PeekController.State.EXPOSED.ordinal()
                    || soldier.getSyncedPeekState() == PeekController.State.RETURNING_TO_COVER.ordinal()) {
                    tactical[i] = true;
                }
            }
        }

        boolean moversAtLeast(int required) {
            int count = 0;
            for (boolean value : moved) if (value) count++;
            return count >= required;
        }

        boolean tacticalParticipantsAtLeast(int required) {
            int count = 0;
            for (boolean value : tactical) if (value) count++;
            return count >= required;
        }
    }
}

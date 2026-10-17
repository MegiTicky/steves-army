package com.stevesarmy.sim;

import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Focused firing-position probes for directional cover and open-prone fallback. */
public final class DirectionalCoverScenarios {
    private DirectionalCoverScenarios() {}

    /** A wall behind the gunner must not count as protection from a north threat. */
    public static SimScenario wrongFacingCoverRejects() {
        final FiringPositionFinder.EvaluationReport[] report = new FiringPositionFinder.EvaluationReport[1];
        return base("directional_cover_rejects_wrong_side", "south wall", report)
            .description("A north threat faces a south-side wall; cover-peek candidates must be rejected for direction and an open-prone lane must remain eligible.")
            .action(20, "evaluate wrong-facing cover and prone fallback", ctx -> report[0] = evaluate(ctx))
            .assertThat("directionally unprotected cover-peek candidates are rejected",
                view -> report[0] != null && report[0].rejectedForProtection() > 0
                    && report[0].protectionDiagnostics().stream().anyMatch(p ->
                        p.posture() == FiringPosition.FiringPosture.COVER_PEEK
                            && !p.directionallyProtected()))
            .assertThat("open-prone fallback is selected when no cover protects the threat direction",
                view -> report[0] != null && report[0].selected() != null
                    && report[0].selected().posture() == FiringPosition.FiringPosture.OPEN_PRONE)
            .build();
    }

    /** A wall between the gunner and the threat is a valid directional control. */
    public static SimScenario protectedCoverControl() {
        final FiringPositionFinder.EvaluationReport[] report = new FiringPositionFinder.EvaluationReport[1];
        return base("directional_cover_protected_control", "north wall", report)
            .description("A north threat is screened by a north-side wall; a reachable protected cover-peek candidate should survive the directional filter.")
            .action(20, "evaluate protected cover control", ctx -> report[0] = evaluate(ctx))
            .assertThat("protected cover-peek geometry is observed",
                view -> report[0] != null && report[0].protectionDiagnostics().stream().anyMatch(p ->
                    p.posture() == FiringPosition.FiringPosture.COVER_PEEK && p.directionallyProtected()))
            .assertThat("protected cover is selected ahead of open prone when reachable",
                view -> report[0] != null && report[0].selected() != null
                    && report[0].selected().posture() == FiringPosition.FiringPosture.COVER_PEEK
                    && report[0].selected().protection() >= FiringPositionFinder.MIN_COVER_PROTECTION)
            .build();
    }

    private static SimScenario.Builder base(String name, String wallSide,
                                             FiringPositionFinder.EvaluationReport[] report) {
        return SimScenario.builder(name)
            .duration(40)
            .field(48, 48)
            .description("Directional cover selection probe")
            .terrain(t -> {
                t.floorOnly();
                if ("south wall".equals(wallSide)) t.coverRow(16, 25, 24, 25, 1);
                else t.coverRow(16, 15, 24, 15, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 20);
                var gunner = ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner",
                    20, 0, 20, SquadMode.HOLD);
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 20, 0, 8);
                enemy.setNoAi(true);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(20, 0, 8));
                gunner.setTarget(enemy);
            });
    }

    private static FiringPositionFinder.EvaluationReport evaluate(SimContext ctx) {
        var gunner = ctx.soldier("gunner");
        return FiringPositionFinder.evaluate((com.stevesarmy.entity.MachineGunnerEntity) gunner,
            ctx.terrain.at(20, 0, 8), ctx.terrain.at(20, 0, 20));
    }
}

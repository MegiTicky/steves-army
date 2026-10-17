package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.FiringPosition;
import com.stevesarmy.combat.cover.FiringPositionFinder;
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
        final FiringPositionFinder.AsyncEvaluationResult[] asyncResult =
            new FiringPositionFinder.AsyncEvaluationResult[1];
        return base("directional_cover_rejects_wrong_side", "south wall", report)
            .description("A north threat faces a south-side wall; wrong-side cover-peek candidates must be rejected and an open-prone lane must remain eligible.")
            .action(5, "evaluate wrong-facing cover and prone fallback", ctx -> {
                var gunner = (com.stevesarmy.entity.MachineGunnerEntity) ctx.soldier("gunner");
                gunner.setOnGround(true);
                gunner.getCombatGoal().setTarget(ctx.byLabel.get("threat"));
                var suppressionCenter = ctx.terrain.at(20, 0, 8);
                var supportAnchor = ctx.terrain.at(20, 0, 20);
                var snapshot = FiringPositionFinder.captureAsyncSnapshot(
                    gunner, suppressionCenter, supportAnchor, 0L, 0L, 0L);
                asyncResult[0] = FiringPositionFinder.evaluateAsyncSnapshot(snapshot);
                report[0] = evaluate(ctx);
                logEvaluation("wrong-side", report[0]);
            })
            .assertThat("async search includes a floor-valid open-prone candidate",
                view -> asyncResult[0] != null && asyncResult[0].candidates().stream().anyMatch(candidate ->
                    candidate.posture() == FiringPosition.FiringPosture.OPEN_PRONE))
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
            .action(5, "evaluate protected cover control", ctx -> {
                ctx.soldier("gunner").setOnGround(true);
                report[0] = evaluate(ctx);
                logEvaluation("protected-control", report[0]);
            })
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
                if ("south wall".equals(wallSide)) {
                    // Span the existing floor so pathfinding cannot walk around
                    // the wall to claim its protected side. Keep the floor
                    // intact for the danger-map oracle.
                    t.coverRow(-1, 24, 49, 25, 2);
                } else t.coverRow(16, 15, 24, 15, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 20);
                var gunner = ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner",
                    20, 0, 20, SquadMode.HOLD);
                var threat = ctx.spawnEnemyLabeled("threat", 20, 0, 8);
                threat.setNoAi(true);
                threat.setInvulnerable(true);
                gunner.setInvulnerable(true);
                // Keep a fixed primary threat without disabling the navigator.
                gunner.getCombatGoal().setTarget(threat);
                gunner.getThreatAwareness().onEnemyPing(ctx.terrain.at(20, 0, 8));
                ctx.injectThreat(20, 8, 1.0f);
            });
    }

    private static FiringPositionFinder.EvaluationReport evaluate(SimContext ctx) {
        var gunner = ctx.soldier("gunner");
        // Ambient mobs in the dev world must not change this directional probe.
        gunner.getCombatGoal().setTarget(ctx.byLabel.get("threat"));
        return FiringPositionFinder.evaluate((com.stevesarmy.entity.MachineGunnerEntity) gunner,
            ctx.terrain.at(20, 0, 8), ctx.terrain.at(20, 0, 20));
    }

    private static void logEvaluation(String label, FiringPositionFinder.EvaluationReport report) {
        var coverGeometry = report.protectionDiagnostics().stream()
            .filter(p -> p.posture() == FiringPosition.FiringPosture.COVER_PEEK)
            .limit(12).toList();
        StevesArmyMod.LOGGER.info(
            "[DirectionalCoverProbe] case={} targets={} coverTargets={} coverChecked={} proneChecked={} "
                + "rejectedProtection={} rejectedAccess={} candidates={} selected={} coverGeometry={} pathChecks={}",
            label, report.suppressionTargetCount(), report.coverTargetCount(), report.coverPositionsChecked(),
            report.pronePositionsChecked(), report.rejectedForProtection(), report.rejectedForAccess(),
            report.candidates().size(), report.selected(), coverGeometry, report.pathChecks());
    }
}

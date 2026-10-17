package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Focused guards for the suppressed open-ground posture fallback. */
public final class NoCoverProneScenarios {
    private NoCoverProneScenarios() {}

    public static SimScenario pressuredOpenGroundFallsProne() {
        SimScenario.Builder scenario = SimScenario.builder("no_cover_prone_fallback")
            .description("A suppressed soldier on genuinely coverless ground settles into the "
                + "firing-prone fallback while no directionally protective cover is available.")
            .duration(360)
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "soldier", 24, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24, 1.0f);
            })
            .action(150, "refresh synthetic threat", ctx -> ctx.refreshInjectedThreats())
            .action(270, "refresh synthetic threat", ctx -> ctx.refreshInjectedThreats());

        for (int tick = 10; tick <= 120; tick += 10) {
            scenario.action(tick, "incoming near-miss pressure", ctx ->
                ctx.injectSuppressionFromGround("soldier", 2, 40, 24, 1.2, 3.0f));
        }

        return scenario
            .assertThat("near-miss pressure reaches the pressured threshold",
                view -> view.timeline().series("soldier").stream()
                    .anyMatch(s -> s.tick >= 10 && s.state.suppressionLevel >= 0.5f))
            .assertThat("the soldier never claims or reports physical cover on the floor-only fixture",
                view -> view.timeline().series("soldier").stream()
                    .filter(s -> s.tick >= 80)
                    .allMatch(s -> s.state.currentCover == null && s.state.targetCover == null
                        && !"IN_COVER".equals(s.state.coverState)
                        && !"SUPPRESSED_IN_COVER".equals(s.state.coverState)))
            .assertThat("suppressed exposed samples use firing-prone posture, not travel crawl",
                view -> hasSustainedOpenGroundProne(view, 80, 20))
            .assertThat("the fallback holds position when no cover route exists",
                view -> view.timeline().travel("soldier", 80, 300) <= 2.0)
            .build();
    }

    private static boolean hasSustainedOpenGroundProne(SimScenario.SimRunResultView view,
                                                        int fromTick, int requiredTicks) {
        int run = 0;
        for (SimSample sample : view.timeline().series("soldier")) {
            boolean eligible = sample.tick >= fromTick
                && sample.state.suppressionLevel >= 0.5f
                && sample.state.currentCover == null && sample.state.targetCover == null
                && "SWIMMING".equals(sample.posture)
                && sample.state.lowCrouching && !sample.state.travelCrawl;
            run = eligible ? run + 1 : 0;
            if (run >= requiredTicks) return true;
        }
        return false;
    }

    public static SimScenario unpressuredOpenGroundStaysUpright() {
        return SimScenario.builder("no_cover_unpressured_upright")
            .description("The no-cover posture fallback stays inactive on open ground without "
                + "suppression or an active threat.")
            .duration(180)
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "soldier", 24, 0, 24, SquadMode.HOLD);
            })
            .assertThat("no suppression or threat is present",
                view -> view.timeline().series("soldier").stream()
                    .allMatch(s -> s.state.suppressionLevel < 0.5f))
            .assertThat("no-cover fallback does not make an idle soldier prone",
                view -> view.timeline().series("soldier").stream()
                    .filter(s -> s.tick >= 40)
                    .allMatch(s -> !s.state.lowCrouching && !"SWIMMING".equals(s.posture)))
            .build();
    }
}

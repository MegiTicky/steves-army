package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Observational GO_TO/cover-search stress fixture. */
public final class GoToPerformanceScenario {
    private GoToPerformanceScenario() {}

    public static SimScenario create() {
        return SimScenario.builder("goto_performance_crowded_cover")
            .description("Observes per-tick elapsed time and GO_TO cover-search cost for 24 friendlies among many candidate cover blocks; no performance threshold is asserted.")
            .duration(500).field(64, 64)
            .terrain(t -> {
                t.platform();
                for (int x = 10; x <= 54; x += 4) {
                    for (int z = 10; z <= 54; z += 4) {
                        if ((x + z) % 8 == 0) t.coverBlock(x, z, 2);
                        else t.coverBlock(x, z, 1);
                    }
                }
            })
            .setup(ctx -> {
                ctx.spawnAnchor(5, 0, 32);
                for (int i = 0; i < 24; i++) {
                    int x = 8 + (i % 6) * 2;
                    int z = 14 + (i / 6) * 3;
                    ctx.spawnFriendly(ModEntities.SOLDIER.get(), "mover_" + i, x, 0, z, SquadMode.HOLD);
                }
                ctx.injectThreat(58, 32, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(56, 32);
                StevesArmyMod.LOGGER.info("[Sim][GoToPerf] fixture friendlies={} candidateCoverBlocks=144 fastTicks={}",
                    ctx.friendlies.size(), SimManager.fastTicks());
            })
            .action(180, "reissue GO_TO to same objective", ctx -> ctx.issueGoTo(56, 32))
            .action(340, "shift GO_TO objective", ctx -> ctx.issueGoTo(52, 38))
            .build();
    }
}

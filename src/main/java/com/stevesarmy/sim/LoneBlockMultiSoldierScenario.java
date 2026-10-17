package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Two soldiers competing around one block: protected side versus wrong side. */
public final class LoneBlockMultiSoldierScenario {
    private LoneBlockMultiSoldierScenario() {}

    public static SimScenario create() {
        return SimScenario.builder("lone_block_multi_soldier_direction")
            .description("One soldier approaches the threat-facing protected side of a lone block; "
                + "a second soldier on the wrong side must reject it and stay prone in the open.")
            .duration(500)
            .terrain(t -> { t.platform(); t.coverBlock(20, 30, 1); })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "covered", 20, 0, 36, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "wrong_side", 20, 0, 24, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("threat", 20, 0, 8);
                ctx.injectThreat(20, 8, 1.0f);
            })
            .action(20, "refresh pressure", ctx -> ctx.refreshInjectedThreats())
            .action(200, "refresh pressure", ctx -> ctx.refreshInjectedThreats())
            .action(380, "refresh pressure", ctx -> ctx.refreshInjectedThreats())
            .assertThat("protected-side soldier claims the lone block as HALF cover",
                view -> view.timeline().series("covered").stream()
                    .anyMatch(s -> s.state.currentCover != null
                        && "HALF".equals(s.state.currentCoverType)
                        && s.tick <= 350))
            .assertThat("protected-side soldier has a sustained covered/prone episode",
                view -> view.timeline().series("covered").stream()
                    .filter(s -> s.tick >= 100 && s.state.currentCover != null)
                    .anyMatch(s -> "IN_COVER".equals(s.state.coverState)
                        || "SUPPRESSED_IN_COVER".equals(s.state.coverState)))
            .assertThat("wrong-side soldier never claims the lone block",
                view -> view.timeline().series("wrong_side").stream()
                    .filter(s -> s.tick >= 100)
                    .noneMatch(s -> s.state.currentCover != null))
            .assertThat("wrong-side soldier uses sustained open-ground prone posture",
                view -> view.timeline().series("wrong_side").stream()
                    .filter(s -> s.tick >= 100 && s.state.currentCover == null)
                    .filter(s -> s.state.suppressionLevel >= 0.5f)
                    .filter(s -> "SWIMMING".equals(s.posture) && s.state.lowCrouching)
                    .count() >= 20)
            .build();
    }
}

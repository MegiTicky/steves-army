package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Focused timing probe for the handoff from a GO_TO objective to nearby cover. */
public final class GoToArrivalCoverScenario {
    private static final String SOLDIER = "mover";
    private static final double OBJECTIVE_X = 38.5;
    private static final double OBJECTIVE_Z = 24.5;

    private GoToArrivalCoverScenario() {}

    public static SimScenario arrivalCoverDelay() {
        boolean[] coverAdded = {false};
        int[] arrivalHolding = {-1};
        int[] holdingAt300 = {-1};
        SimScenario.Builder scenario = SimScenario.builder("goto_arrival_cover_delay")
            .description("A lone soldier receives one GO_TO order toward open ground 30 blocks from a known threat. "
                + "The objective is open ground 30 blocks from the enemy; measure cover handoff after "
                + "failed relocation falls back to direct walking, then cover appears at the 2-block arrival radius.")
            .duration(500)
            .core()
            .field(72, 48)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), SOLDIER, 8, 0, 24, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("threat", 68, 0, 24);
                ctx.injectThreat(68, 24, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(38, 24);
            });
        // Keep the arrival trigger live at the capped walking pace; the final
        // 100 ticks remain available for the unchanged search/docking assertions.
        for (int tick = 25; tick <= 400; tick += 5) {
            scenario.action(tick, "make nearby cover available on objective arrival", ctx -> {
                if (coverAdded[0]) return;
                var soldier = ctx.soldier(SOLDIER);
                if (soldier == null || !soldier.isAlive()) return;
                if (Math.hypot(soldier.getX() - OBJECTIVE_X, soldier.getZ() - OBJECTIVE_Z) <= 2.0) {
                    arrivalHolding[0] = soldier.isGoToHolding() ? 1 : 0;
                    ctx.terrain.coverRow(44, 21, 44, 27, 1);
                    coverAdded[0] = true;
                }
            });
        }
        scenario.action(300, "verify completed GO_TO remains held", ctx -> {
            var soldier = ctx.soldier(SOLDIER);
            if (soldier != null) holdingAt300[0] = soldier.isGoToHolding() ? 1 : 0;
        });
        return scenario.assertThat("reaches the 2-block GO_TO completion radius and records arrival/search/cover evidence",
                view -> {
                    int arrivalTick = -1;
                    int firstSearchStartTick = -1;
                    int firstTargetTick = -1;
                    int firstClaimTick = -1;
                    int dockTick = -1;
                    SimSample arrivalSample = null;
                    SimSample beforeArrivalSample = null;
                    for (SimSample sample : view.timeline().series(SOLDIER)) {
                        if (sample.state.position == null) continue;
                        double distance = Math.hypot(
                            sample.state.position.x - OBJECTIVE_X,
                            sample.state.position.z - OBJECTIVE_Z);
                        if (arrivalTick < 0 && distance <= 2.0) {
                            arrivalTick = sample.tick;
                            arrivalSample = sample;
                        }
                        if (arrivalTick < 0) beforeArrivalSample = sample;
                    }
                    if (arrivalSample == null) return false;
                    for (SimSample sample : view.timeline().series(SOLDIER)) {
                        if (sample.tick < arrivalTick) continue;
                        boolean searchStarted = sample.state.searchPending
                            || "SEEKING_COVER".equals(sample.state.coverState);
                        if (firstSearchStartTick < 0 && searchStarted) {
                            firstSearchStartTick = sample.tick;
                        }
                        if (firstTargetTick < 0 && sample.state.targetCover != null) firstTargetTick = sample.tick;
                        if (firstClaimTick < 0 && sample.state.currentCover != null) firstClaimTick = sample.tick;
                        if (dockTick < 0 && sample.state.currentCover != null
                            && sample.coverSlotOffset >= 0.0 && sample.coverSlotOffset <= 0.12) {
                            dockTick = sample.tick;
                        }
                    }
                    int firstCoverTick = firstTargetTick >= 0 ? firstTargetTick : firstClaimTick;
                    var lastBeforeArrival = beforeArrivalSample == null ? null : beforeArrivalSample.state.lastSearch;
                    com.stevesarmy.StevesArmyMod.LOGGER.info(
                        "[SimGoToArrival] arrival={} cooldown={} relocAtArrival={} searchStart={} "
                            + "target={} claim={} dock={} coverAdded={} preSearch={} preResult={} "
                            + "preFailStreak={} preReloc={} preFailedSearches={} "
                            + "arrivalHolding={} holding300={}",
                        arrivalTick, arrivalSample.state.goalCooldown, arrivalSample.state.relocType,
                        firstSearchStartTick, firstTargetTick, firstClaimTick, dockTick, coverAdded[0],
                        lastBeforeArrival == null ? -1 : lastBeforeArrival.tick,
                        lastBeforeArrival == null ? "none" : lastBeforeArrival.result,
                        lastBeforeArrival == null ? -1 : lastBeforeArrival.failStreak,
                        beforeArrivalSample == null ? "none" : beforeArrivalSample.state.relocType,
                        beforeArrivalSample == null ? -1 : beforeArrivalSample.state.failedSearches,
                        arrivalHolding[0], holdingAt300[0]);
                    return coverAdded[0]
                        && lastBeforeArrival != null && lastBeforeArrival.failStreak > 0
                        && !"GO_TO".equals(arrivalSample.state.relocType)
                        && firstSearchStartTick >= arrivalTick && firstSearchStartTick - arrivalTick <= 20
                        && firstTargetTick >= arrivalTick && firstTargetTick - arrivalTick <= 60
                        && firstCoverTick >= arrivalTick && firstCoverTick - arrivalTick <= 60
                        && dockTick >= arrivalTick && dockTick - arrivalTick <= 100;
                })
            .assertThat("completed GO_TO stays in holding state after arrival",
                view -> arrivalHolding[0] >= 0 && holdingAt300[0] == 1)
            .build();
    }
}

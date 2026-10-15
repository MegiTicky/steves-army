package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/**
 * Long-range GO_TO routes (60+ blocks) — past the FOLLOW_RANGE 32 path lens
 * and past every relocation window that was tuned for ~30-block objectives.
 * Reproduces the in-game halfway stops: the 200-tick relocation clock
 * expiring mid-walk, staged legs idling at their boundaries, and the direct
 * walk stalling at an obstacle whose detour exceeds its 32-block lens.
 */
public final class GoToLongRouteScenarios {
    private static final String MOVER = "mover";

    private GoToLongRouteScenarios() {}

    /**
     * Single ping 68 blocks across open ground with waist-high cover near the
     * destination: the relocation commits a distant staged cover and the march
     * must keep making progress and complete the order instead of stalling.
     */
    public static SimScenario longOpenMarch() {
        boolean[] holdingAtEnd = {false};
        return SimScenario.builder("goto_long_open")
            .description("One GO_TO ping 68 blocks out over open ground, cover row near the ping. "
                + "The relocation walks staged legs toward a distant cover; the march must not "
                + "stand still mid-route and must complete the order.")
            .duration(700)
            .core()
            .field(96, 32)
            .terrain(t -> {
                t.platform();
                t.coverRow(70, 12, 70, 20, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 16);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), MOVER, 8, 0, 16, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("threat", 88, 0, 16);
                ctx.injectThreat(88, 16, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(76, 16);
            })
            .action(680, "record completed GO_TO holding latch", ctx -> {
                var soldier = ctx.soldier(MOVER);
                holdingAtEnd[0] = soldier != null && soldier.isAlive() && soldier.isGoToHolding();
            })
            .assertThat("keeps moving mid-route: no 60-tick window with < 1 block of movement "
                    + "while farther than 16 from both spawn and ping",
                view -> !hasEnRouteStandstill(view, MOVER, 8.5, 16.5, 76.5, 16.5, 60, 1.0, 8.0, 16.0))
            .assertThat("makes real progress (>= 25 blocks from spawn by t200, >= 45 by t400)",
                view -> {
                    SimSample at200 = sampleAt(view, MOVER, 200);
                    SimSample at400 = sampleAt(view, MOVER, 400);
                    return at200 != null && at400 != null
                        && distance(at200, 8.5, 16.5) >= 25.0
                        && distance(at400, 8.5, 16.5) >= 45.0;
                })
            .assertThat("completes the order (holding latch at t680, or docked within 12 of the ping)",
                view -> {
                    if (holdingAtEnd[0]) return true;
                    double best = Double.MAX_VALUE;
                    for (SimSample s : view.timeline().series(MOVER)) {
                        double d = distance(s, 76.5, 16.5);
                        if (d < best) best = d;
                    }
                    return best <= 12.0;
                })
            .build();
    }

    /**
     * Coverless 96-block march whose straight line crosses a full-height wall;
     * the only detour (around the wall's far end) sits more than 32 blocks off
     * the line, beyond the direct walk's path lens. A soldier that reaches the
     * wall and stays there is the in-game halfway stop.
     */
    public static SimScenario longDetourMarch() {
        boolean[] holdingAtEnd = {false};
        return SimScenario.builder("goto_long_detour")
            .description("One GO_TO ping 96 blocks out behind a full-height wall whose only gap "
                + "is 37+ blocks off the straight line. The direct walk's 32-block partial-path "
                + "lens cannot route the detour; the march must still complete the order.")
            .duration(900)
            .core()
            .field(120, 96)
            .terrain(t -> {
                t.floorOnly();
                t.wall(52, 0, 52, 84, 3, net.minecraft.world.level.block.Blocks.STONE_BRICKS
                    .defaultBlockState());
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 48);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), MOVER, 8, 0, 48, SquadMode.HOLD);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(104, 48);
            })
            .action(880, "record completed GO_TO holding latch", ctx -> {
                var soldier = ctx.soldier(MOVER);
                holdingAtEnd[0] = soldier != null && soldier.isAlive() && soldier.isGoToHolding();
            })
            .assertThat("completes the order around the wall (holding latch at t880 or within 4 of the ping)",
                view -> {
                    if (holdingAtEnd[0]) return true;
                    double best = Double.MAX_VALUE;
                    for (SimSample s : view.timeline().series(MOVER)) {
                        double d = distance(s, 104.5, 48.5);
                        if (d < best) best = d;
                    }
                    return best <= 4.0;
                })
            .assertThat("does not strand at the wall face (never stands >= 100 ticks while within "
                    + "2 blocks of the wall plane x=52)",
                view -> {
                    int atWall = 0;
                    for (SimSample s : view.timeline().series(MOVER)) {
                        if (s.state.position == null) continue;
                        boolean nearWall = Math.abs(s.state.position.x - 52.5) <= 2.0
                            && s.state.position.z <= 84.5;
                        atWall = nearWall ? atWall + 1 : 0;
                        if (atWall >= 100) return false;
                    }
                    return true;
                })
            .build();
    }

    /**
     * Scans for the halfway-stop signature: any window of at least
     * {@code windowTicks} scenario ticks in which the soldier moved less than
     * {@code minBlocks} while strictly between {@code nearSpawn} of the spawn
     * and {@code nearPing} of the ping (arrival dwell and departure search are
     * excluded).
     */
    private static boolean hasEnRouteStandstill(SimScenario.SimRunResultView view, String label,
                                                double spawnX, double spawnZ, double pingX, double pingZ,
                                                int windowTicks, double minBlocks,
                                                double nearSpawn, double nearPing) {
        var series = view.timeline().series(label);
        for (int i = 0; i < series.size(); i++) {
            SimSample a = series.get(i);
            if (a.state.position == null || !isEnRoute(a, spawnX, spawnZ, pingX, pingZ, nearSpawn, nearPing)) {
                continue;
            }
            for (int j = i + 1; j < series.size(); j++) {
                SimSample b = series.get(j);
                if (b.state.position == null) continue;
                if (b.tick - a.tick < windowTicks) continue;
                if (b.tick - a.tick > windowTicks + 40) break;
                if (!isEnRoute(b, spawnX, spawnZ, pingX, pingZ, nearSpawn, nearPing)) continue;
                if (distance(a, b.state.position.x, b.state.position.z) < minBlocks) {
                    com.stevesarmy.StevesArmyMod.LOGGER.info(
                        "[GoToLongRoute] {} standstill {}t at tick {}..{} pos {} -> {}",
                        label, b.tick - a.tick, a.tick, b.tick,
                        a.state.position, b.state.position);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEnRoute(SimSample s, double spawnX, double spawnZ,
                                     double pingX, double pingZ, double nearSpawn, double nearPing) {
        return distance(s, spawnX, spawnZ) > nearSpawn && distance(s, pingX, pingZ) > nearPing;
    }

    private static double distance(SimSample s, double x, double z) {
        if (s.state.position == null) return Double.MAX_VALUE;
        return Math.hypot(s.state.position.x - x, s.state.position.z - z);
    }

    private static SimSample sampleAt(SimScenario.SimRunResultView view, String label, int tick) {
        SimSample best = null;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick > tick) break;
            best = s;
        }
        return best;
    }
}

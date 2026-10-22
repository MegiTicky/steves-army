package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.SoldierHoleRescueGoal;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.level.block.Blocks;

/** Real physics/navigation probes with unrelated combat and wander goals removed. */
public final class MovementRecoveryScenarios {
    private MovementRecoveryScenarios() {}

    /** Investigation fixtures: ordinary goals and one real order, with read-only recovery sampling. */
    public static SimScenario diagnostic(String shape, String order) {
        String name = "rescue_diag_" + shape + "_" + order;
        boolean pit = shape.equals("pit");
        boolean roof = shape.equals("roof");
        boolean partial = shape.equals("partial_box");
        double[] maxY = {-999};
        int[] escaped = {-1};
        int[] intentTicks = {0}, excludedTicks = {0}, groundedTicks = {0}, resets = {0};
        int[] previousStart = {-1};
        var b = SimScenario.builder(name).duration(650)
            .terrain(t -> {
                // Clear leaked flowing fluids from earlier fixtures before constructing geometry.
                for (int x = 0; x <= 36; x++) for (int z = 0; z <= 30; z++)
                    for (int y = -4; y < t.level().getMaxBuildHeight() - t.origin.getY(); y++) t.block(x, y, z, Blocks.AIR.defaultBlockState());
                t.platform();
                if (pit) {
                    // Real ground hole: floor at -3, mouth at 0, one-block interior.
                    t.block(10, -1, 10, Blocks.AIR.defaultBlockState());
                    t.block(10, -2, 10, Blocks.AIR.defaultBlockState());
                    t.block(10, -3, 10, Blocks.STONE.defaultBlockState());
                    for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL)
                        for (int y = -2; y < 0; y++) t.block(10 + d.getStepX(), y, 10 + d.getStepZ(), Blocks.STONE.defaultBlockState());
                } else if (roof) {
                    for (int x = 8; x <= 12; x++) for (int z = 8; z <= 12; z++) t.block(x, 2, z, Blocks.STONE.defaultBlockState());
                } else {
                    // 7x7 clear interior x/z 7..13; three-block walls x/z 6 and 14.
                    t.building(6, 6, 14, 14, 3, new int[0][]);
                    if (partial) for (int x = 7; x <= 13; x++) for (int z = 7; z <= 13; z++)
                        t.block(x, -1, z, Blocks.DIRT_PATH.defaultBlockState());
                }
            })
            .setup(ctx -> {
                ctx.spawnAnchor(order.equals("follow") ? 30 : 24, 0, 10);
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, pit ? -2 : roof ? 3 : 0, 10,
                    order.equals("follow") ? SquadMode.FOLLOW : SquadMode.HOLD);
                s.setHoldPosition(s.blockPosition());
            });
        if (order.equals("goto") || order.equals("cancel")) b.action(20, "single authentic GO_TO", ctx -> ctx.issueGoTo(24, 10));
        if (order.equals("cancel")) b.action(100, "cancel order before rescue deadline", ctx -> {
            var s = ctx.soldier("probe");
            s.clearPingMoveTarget();
            s.setHoldPosition(s.blockPosition());
            s.getNavigation().stop();
            if (s.getMoveControl() instanceof com.stevesarmy.entity.ai.CoverPositionController control) control.clear();
        });
        for (int tick = 1; tick < 640; tick++) {
            final int t = tick;
            b.action(tick, "read recovery state", ctx -> {
                var s = ctx.soldier("probe");
                var goal = s.goalSelector.getAvailableGoals().stream().map(g -> g.getGoal())
                    .filter(g -> g instanceof SoldierHoleRescueGoal).findFirst().orElseThrow();
                try {
                    var c = SoldierHoleRescueGoal.class;
                    var mt = c.getDeclaredMethod("movementTarget"); mt.setAccessible(true);
                    var ex = c.getDeclaredMethod("excluded"); ex.setAccessible(true);
                    var bs = c.getDeclaredMethod("blockingSides"); bs.setAccessible(true);
                    var fs = c.getDeclaredField("failureStart"); fs.setAccessible(true);
                    Object target = mt.invoke(goal);
                    boolean excluded = (boolean) ex.invoke(goal);
                    int start = fs.getInt(goal);
                    if (target != null) intentTicks[0]++;
                    if (excluded) excludedTicks[0]++;
                    if (s.onGround()) groundedTicks[0]++;
                    if (previousStart[0] >= 0 && start != previousStart[0]) resets[0]++;
                    previousStart[0] = start;
                    maxY[0] = Math.max(maxY[0], s.getY() - ctx.terrain.origin.getY());
                    boolean out = pit ? s.getY() >= ctx.terrain.origin.getY() - .1
                        : roof ? s.getY() < ctx.terrain.origin.getY() + .5
                        : s.getX() > ctx.terrain.at(14, 0, 10).getX() + 1 || s.getX() < ctx.terrain.at(6, 0, 10).getX();
                    if (out && escaped[0] < 0) escaped[0] = t;
                    if (t % 60 == 0 || t == 639) {
                        var p = s.getNavigation().createPath(ctx.terrain.at(24, 0, 10), 0);
                        com.stevesarmy.StevesArmyMod.LOGGER.info("[RescueDiag] {} t={} pos={} target={} excluded={} ground={} sides={} failureAge={} route={} cover={} intentTicks={} excludedTicks={} groundTicks={} resets={} escaped={} maxY={}",
                            name, t, s.position(), target, excluded, s.onGround(), bs.invoke(goal), s.tickCount-start,
                            p == null ? "null" : p.canReach() + "/" + p.getNodeCount(), s.getCoverBehaviorManager().isInCover(),
                            intentTicks[0], excludedTicks[0], groundedTicks[0], resets[0], escaped[0], maxY[0]);
                    }
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
        }
        boolean stay = order.equals("idle") || order.equals("cancel");
        return b.assertThat(stay ? "idle or cancelled actor stays in trap" : "commanded actor escapes within 639 ticks",
            v -> stay ? escaped[0] < 0 : escaped[0] >= 0).build();
    }

    public static SimScenario roof(boolean reachable) {
        boolean[] descended = {false};
        double[] previousY = {0};
        double[] largestStep = {0};
        var b = SimScenario.builder(reachable ? "recovery_roof_descent" : "recovery_roof_unreachable")
            .duration(420)
            .terrain(t -> {
                t.platform();
                for (int x = 8; x <= 12; x++) for (int z = 8; z <= 12; z++)
                    t.block(x, 2, z, Blocks.STONE.defaultBlockState());
                if (reachable) for (int y = 0; y < 3; y++)
                    t.block(13 + (2 - y), y, 10, Blocks.STONE.defaultBlockState());
            })
            .setup(ctx -> {
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 3, 10, SquadMode.HOLD);
                s.goalSelector.removeAllGoals(g -> true);
                s.goalSelector.addGoal(0, new SoldierHoleRescueGoal(s));
                previousY[0] = s.getY();
            });
        for (int tick = 5; tick < 400; tick += 10) b.action(tick, "request ground destination", ctx -> {
            var p = ctx.terrain.at(reachable ? 18 : 10, 0, 10);
            ctx.soldier("probe").getNavigation().moveTo(p.getX() + .5, p.getY(), p.getZ() + .5, 1);
        });
        for (int tick = 1; tick < 410; tick++) b.action(tick, "sample roof progress", ctx -> {
            var s = ctx.soldier("probe");
            largestStep[0] = Math.max(largestStep[0], Math.abs(s.getY() - previousY[0]));
            previousY[0] = s.getY();
            if (s.getY() < ctx.terrain.origin.getY() + .5) descended[0] = true;
        });
        return b.assertThat("safe ground descent completes", v -> descended[0])
            .assertThat("reachable stairs use physical walking", v -> !reachable || largestStep[0] < 1.5)
            .build();
    }

    public static SimScenario wrongFloorArrival() {
        boolean[] rejected = {false};
        return SimScenario.builder("recovery_wrong_floor_arrival").duration(40)
            .terrain(t -> { t.platform(); t.block(10, 2, 10, Blocks.STONE.defaultBlockState()); })
            .setup(ctx -> {
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 3, 10, SquadMode.HOLD);
                s.goalSelector.removeAllGoals(g -> true);
                var cover = new com.stevesarmy.combat.cover.CoverPoint(ctx.terrain.at(10, 0, 10));
                s.getCoverBehaviorManager().setTargetCover(cover);
                try {
                    var method = com.stevesarmy.entity.ai.CoverTacticalGoal.class.getDeclaredMethod("onCoverReached", com.stevesarmy.combat.cover.CoverPoint.class);
                    method.setAccessible(true);
                    method.invoke(s.getCoverTacticalGoal(), cover);
                    rejected[0] = s.getCoverBehaviorManager().getCurrentCover() == null;
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            })
            .assertThat("wrong-floor arrival never promotes target cover", v -> rejected[0]).build();
    }

    public static SimScenario pit(boolean commanded, boolean unsafe) {
        double[] finalY = {Double.NaN};
        double[] maxY = {Double.NEGATIVE_INFINITY};
        double[] initialY = {0};
        var b = SimScenario.builder(unsafe ? "recovery_unsafe_surface" : commanded ? "recovery_commanded_pit" : "recovery_idle_pit")
            .duration(460)
            .terrain(t -> {
                for (int x = 0; x <= 36; x++) for (int z = 0; z <= 30; z++)
                    for (int y = -4; y < t.level().getMaxBuildHeight() - t.origin.getY(); y++) t.block(x, y, z, Blocks.AIR.defaultBlockState());
                t.platform();
                for (int x = 9; x <= 11; x++) for (int z = 9; z <= 11; z++) {
                    t.block(x, 0, z, Blocks.STONE.defaultBlockState());
                    t.block(x, 1, z, Blocks.STONE.defaultBlockState());
                    if (unsafe && (x != 10 || z != 10)) t.block(x, 2, z, Blocks.WATER.defaultBlockState());
                }
                t.block(10, 0, 10, Blocks.AIR.defaultBlockState());
                t.block(10, 1, 10, Blocks.AIR.defaultBlockState());
            })
            .setup(ctx -> {
                SoldierEntity s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 10, SquadMode.HOLD);
                s.goalSelector.removeAllGoals(g -> true);
                s.goalSelector.addGoal(0, new SoldierHoleRescueGoal(s));
                initialY[0] = s.getY();
            });
        for (int tick = 1; tick < 450; tick++) b.action(tick, "sample physical height", ctx ->
            maxY[0] = Math.max(maxY[0], ctx.soldier("probe").getY()));
        if (commanded) for (int tick = 5; tick < 440; tick += 10) b.action(tick, "request exit", ctx -> {
            var p = ctx.terrain.at(16, 0, 10);
            ctx.soldier("probe").getNavigation().moveTo(p.getX() + .5, p.getY(), p.getZ() + .5, 1);
        });
        return b.action(450, "observe physical result", ctx -> finalY[0] = ctx.soldier("probe").getY())
            .assertThat(commanded && !unsafe ? "commanded pit escaped" : "no idle or unsafe teleport",
                v -> commanded && !unsafe ? maxY[0] >= initialY[0] + 1.9 : maxY[0] < initialY[0] + 1.9 && Math.abs(finalY[0] - initialY[0]) < .1)
            .build();
    }
}

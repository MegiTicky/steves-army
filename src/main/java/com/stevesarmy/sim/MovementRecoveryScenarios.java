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

package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.sim.scenario.SimScenario;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Compare exact boolean lanes against full visibility with real world geometry. */
public final class FiringLanePerformanceScenario {
    private FiringLanePerformanceScenario() { }

    public static SimScenario create() {
        int[] checks = new int[3]; // parity checks, mismatches, expected-result failures
        return SimScenario.builder("firing_lane_exact_parity").duration(2).field(64, 32)
            .description("Exact boolean lane parity across open, blocked, fractional and leaf geometry; blocked-ray benchmark.")
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                BlockState[] materials = {Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                    Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                    Blocks.STONE_SLAB.defaultBlockState()};
                for (BlockState material : materials) {
                    for (int layers = 1; layers <= 2; layers++) {
                        for (int x = 10; x <= 11; x++) {
                            for (int y = 0; y <= 2; y++) {
                                for (int z = 5; z <= 15; z++) {
                                    ctx.terrain.block(x, y, z,
                                        x < 10 + layers ? material : Blocks.AIR.defaultBlockState());
                                }
                            }
                        }
                        for (double height : new double[]{0.4, 0.5, 1.27, 1.62, 2.9, 3.1}) {
                            for (double offset : new double[]{0, 0.04, 0.1, 0.9}) {
                                Vec3 from = Vec3.atLowerCornerOf(ctx.terrain.at(8, 0, 10)).add(0.5, height, 0.5 + offset);
                                Vec3 to = Vec3.atLowerCornerOf(ctx.terrain.at(60, 0, 10)).add(0.5, height, 0.5);
                                for (int reverse = 0; reverse < 2; reverse++) {
                                    Vec3 a = reverse == 0 ? from : to;
                                    Vec3 b = reverse == 0 ? to : from;
                                    boolean reference = VisibilityRay.traceFresh(ctx.level, a, b, null).hasContact();
                                    boolean fast = VisibilityRay.hasFreshFiringLane(ctx.level, a, b, null);
                                    checks[0]++;
                                    if (reference != fast) checks[1]++;
                                    if (material.isAir() && !fast) checks[2]++;
                                    if (material.is(Blocks.STONE) && height < 2.9 && fast) checks[2]++;
                                }
                            }
                        }
                    }
                }
                ctx.terrain.wall(10, 5, 11, 15, 3, Blocks.STONE.defaultBlockState());
                Vec3 from = Vec3.atLowerCornerOf(ctx.terrain.at(8, 0, 10)).add(0.5, 1.27, 0.5);
                Vec3 to = from.add(52, 0, 0);
                // Warm both paths before logging wall-clock measurements; timing is evidence,
                // not an assertion whose result depends on the host's current load.
                for (int i = 0; i < 128; i++) {
                    VisibilityRay.traceFresh(ctx.level, from, to, null);
                    VisibilityRay.hasFreshFiringLane(ctx.level, from, to, null);
                }
                long started = System.nanoTime();
                for (int i = 0; i < 1024; i++) VisibilityRay.traceFresh(ctx.level, from, to, null);
                long referenceNanos = System.nanoTime() - started;
                started = System.nanoTime();
                for (int i = 0; i < 1024; i++) VisibilityRay.hasFreshFiringLane(ctx.level, from, to, null);
                long fastNanos = System.nanoTime() - started;
                StevesArmyMod.LOGGER.info("[FiringLanePerf] checks={} mismatches={} fullMs={} booleanMs={}",
                    checks[0], checks[1], referenceNanos / 1e6, fastNanos / 1e6);
            })
            .assertThat("480 exact lanes match full visibility", v -> checks[0] == 480 && checks[1] == 0)
            .assertThat("open lanes pass and solid walls block", v -> checks[2] == 0)
            .build();
    }
}

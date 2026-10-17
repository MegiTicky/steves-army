package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;

/** Focused guards for the suppressed open-ground posture fallback. */
public final class NoCoverProneScenarios {
    private NoCoverProneScenarios() {}

    public static SimScenario clusteredSquad(int count) {
        SimScenario.Builder scenario = SimScenario.builder("clustered_prone_" + count)
            // The fixed threat is invulnerable; damage is outside this spacing fixture.
            .suppressDetectors("FireStarvation")
            .description("Trace reproduction: HOLD riflemen and MGs clustered on open ground must walk to separate prone lanes.")
            .armed()
            .duration(500)
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 42);
                for (int i = 0; i < count; i++) {
                    ctx.spawnFriendly(i % 3 == 2 ? ModEntities.MACHINE_GUNNER.get() : ModEntities.SOLDIER.get(),
                        "soldier" + i, 23 + i % 3, 0, 23 + i / 3, SquadMode.HOLD);
                }
                var threat = ctx.spawnEnemyLabeled("threat", 24, 0, 2);
                threat.setNoAi(true);
                threat.setInvulnerable(true);
                for (var friendly : ctx.friendlies) {
                    friendly.setInvulnerable(true);
                    friendly.setTarget(threat);
                    friendly.getThreatAwareness().onEnemyPing(threat.blockPosition());
                }
                ctx.injectThreat(24, 2, 1.0f);
            });
        for (int tick = 100; tick <= 400; tick += 100) {
            scenario.action(tick, "refresh fixed threat target and memory", ctx -> {
                var threat = ctx.enemies.get(0);
                ctx.refreshInjectedThreats();
                for (var friendly : ctx.friendlies) {
                    friendly.setTarget(threat);
                    friendly.getThreatAwareness().onEnemyPing(threat.blockPosition());
                }
            });
        }
        for (int i = 0; i < count; i++) {
            String label = "soldier" + i;
            scenario.assertThat(label + " holds a spaced open-ground firing-prone lane in the final tail",
                view -> {
                    var tail = view.timeline().series(label).stream().filter(s -> s.tick >= 400).toList();
                    return tail.size() >= 15 && tail.stream().allMatch(s ->
                        "SWIMMING".equals(s.posture) && s.state.lowCrouching
                            && !s.state.travelCrawl && s.state.currentCover == null);
                });
            for (int j = 0; j < i; j++) {
                String peer = "soldier" + j;
                scenario.assertThat(label + " checks spacing when entering prone beside " + peer,
                    view -> {
                        var samples = view.timeline().series(label);
                        return samples.stream()
                            .filter(s -> "SWIMMING".equals(s.posture) && s.state.lowCrouching
                                && !s.state.travelCrawl && s.state.currentCover == null)
                            .allMatch(s -> {
                                var previous = samples.stream().filter(p -> p.tick < s.tick)
                                    .max(java.util.Comparator.comparingInt(p -> p.tick));
                                if (previous.isPresent() && "SWIMMING".equals(previous.get().posture)
                                    && previous.get().state.lowCrouching && !previous.get().state.travelCrawl
                                    && previous.get().state.currentCover == null) {
                                    return true;
                                }
                                var peerAtTick = view.timeline().series(peer).stream()
                                    .filter(p -> p.tick == s.tick).findFirst();
                                if (peerAtTick.isEmpty()) return false;
                                double dx = s.state.position.x - peerAtTick.get().state.position.x;
                                double dz = s.state.position.z - peerAtTick.get().state.position.z;
                                return dx * dx + dz * dz >= 9.0 - 0.001;
                            });
                    });
                scenario.assertThat(label + " and " + peer + " hold at least 3 blocks apart in the final tail",
                    view -> view.timeline().series(label).stream().filter(s -> s.tick >= 400).allMatch(s -> {
                        var peerAtTick = view.timeline().series(peer).stream()
                            .filter(p -> p.tick == s.tick).findFirst();
                        if (peerAtTick.isEmpty()) return false;
                        double dx = s.state.position.x - peerAtTick.get().state.position.x;
                        double dz = s.state.position.z - peerAtTick.get().state.position.z;
                        return dx * dx + dz * dz >= 9.0 - 0.001;
                    }));
            }
        }
        scenario.assertThat("at least one soldier walks to a lane two blocks away before entering firing-prone posture",
            view -> {
                for (int i = 0; i < count; i++) {
                    var samples = view.timeline().series("soldier" + i);
                    if (samples.isEmpty()) continue;
                    var firstProne = samples.stream()
                        .filter(s -> "SWIMMING".equals(s.posture) && s.state.lowCrouching
                            && !s.state.travelCrawl && s.state.currentCover == null)
                        .findFirst();
                    if (firstProne.isEmpty()) continue;
                    double dx = firstProne.get().state.position.x - (23.5D + i % 3);
                    double dz = firstProne.get().state.position.z - (23.5D + i / 3);
                    // Final positioning accepts a 0.05-block arrival tolerance.
                    if (dx * dx + dz * dz >= 1.95D * 1.95D) return true;
                }
                return false;
            });
        return scenario.build();
    }

    public static SimScenario pressuredOpenGroundFallsProne() {
        SimScenario.Builder scenario = SimScenario.builder("no_cover_prone_fallback")
            .description("A suppressed soldier on genuinely coverless ground settles into the "
                + "firing-prone fallback while no directionally protective cover is available.")
            .armed()
            // Fixed invulnerable threat keeps this posture fixture alive for its full window.
            .suppressDetectors("FireStarvation")
            .duration(360)
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                var soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "soldier", 24, 0, 24, SquadMode.HOLD);
                var threat = ctx.spawnEnemyLabeled("threat", 40, 0, 24);
                threat.setNoAi(true);
                threat.setInvulnerable(true);
                soldier.setInvulnerable(true);
                soldier.getThreatAwareness().onEnemyPing(threat.blockPosition());
                soldier.getCombatGoal().setTarget(threat);
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

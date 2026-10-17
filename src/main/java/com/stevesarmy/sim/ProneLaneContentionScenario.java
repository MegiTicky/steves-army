package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A reserved prone lane becomes occupied while its owner is still travelling. */
public final class ProneLaneContentionScenario {
    private ProneLaneContentionScenario() {}

    public static SimScenario create() {
        BlockPos[] blockedLane = {null};
        BlockPos[] replacementLane = {null};
        boolean[] contentionInjected = {false};

        SimScenario.Builder scenario = SimScenario.builder("prone_lane_contention")
            .description("A teammate occupies a reserved prone lane during approach; the traveller must choose another spaced lane.")
            .armed()
            .suppressDetectors("FireStarvation")
            .duration(300)
            .terrain(t -> t.floorOnly())
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 42);
                SoldierEntity traveler = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "traveler",
                    23, 0, 23, SquadMode.HOLD);
                SoldierEntity blocker = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "blocker",
                    24, 0, 23, SquadMode.HOLD);
                blocker.setNoAi(true);
                blocker.setYRot(180.0F);
                blocker.setYBodyRot(180.0F);
                blocker.setYHeadRot(180.0F);
                traveler.setInvulnerable(true);
                blocker.setInvulnerable(true);

                var threat = ctx.spawnEnemyLabeled("threat", 24, 0, 2);
                threat.setNoAi(true);
                threat.setInvulnerable(true);
                for (SoldierEntity friendly : ctx.friendlies) {
                    friendly.getCombatGoal().setTarget(threat);
                    friendly.getThreatAwareness().onEnemyPing(threat.blockPosition());
                }
                ctx.injectThreat(24, 2, 1.0f);
            });

        for (int tick = 5; tick <= 300; tick += 2) {
            final int actionTick = tick;
            scenario.action(tick, "inject lane contention and observe replacement", ctx -> {
                SoldierEntity traveler = ctx.byLabel.get("traveler");
                SoldierEntity blocker = ctx.byLabel.get("blocker");
                BlockPos lane = traveler.getCoverTacticalGoal() != null
                    ? traveler.getCoverTacticalGoal().getProneDefensivePosition() : null;

                if (blockedLane[0] == null) {
                    if (actionTick > 150 || traveler.isFiringProne() || lane == null
                        || traveler.getNavigation().isDone()) return;
                    blockedLane[0] = lane.immutable();
                    Vec3 occupied = Vec3.atBottomCenterOf(blockedLane[0]);
                    blocker.setNoAi(true);
                    blocker.setPos(occupied.x, occupied.y, occupied.z);
                    contentionInjected[0] = true;
                    return;
                }

                if (lane != null && !lane.equals(blockedLane[0])) {
                    replacementLane[0] = lane.immutable();
                }
            });
        }

        return scenario
            .assertThat("the blocker occupies the traveller's reserved lane while it is moving",
                view -> contentionInjected[0] && blockedLane[0] != null)
            .assertThat("the traveller selects a different lane after contention",
                view -> replacementLane[0] != null && !replacementLane[0].equals(blockedLane[0]))
            .assertThat("the traveller holds the replacement lane prone and spaced in the final tail",
                view -> {
                    var tail = view.timeline().series("traveler").stream()
                        .filter(sample -> sample.tick >= 220).toList();
                    if (tail.size() < 15 || replacementLane[0] == null) return false;
                    Vec3 replacement = Vec3.atBottomCenterOf(replacementLane[0]);
                    return tail.stream().allMatch(sample -> {
                        if (!"SWIMMING".equals(sample.posture) || !sample.state.lowCrouching
                            || sample.state.travelCrawl || sample.state.currentCover != null) return false;
                        var blockerSample = view.timeline().series("blocker").stream()
                            .filter(peer -> peer.tick == sample.tick).findFirst();
                        if (blockerSample.isEmpty()) return false;
                        double dx = sample.state.position.x - blockerSample.get().state.position.x;
                        double dz = sample.state.position.z - blockerSample.get().state.position.z;
                        double laneDx = sample.state.position.x - replacement.x;
                        double laneDz = sample.state.position.z - replacement.z;
                        return dx * dx + dz * dz >= 9.0 - 0.001
                            && laneDx * laneDx + laneDz * laneDz <= 2.25;
                    });
                })
            .build();
    }
}

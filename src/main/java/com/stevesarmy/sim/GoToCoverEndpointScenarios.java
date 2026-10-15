package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;

/** Real-path regression probes for selected-cover GO_TO endpoints. */
public final class GoToCoverEndpointScenarios {
    private static final String SOLDIER = "mover";

    private GoToCoverEndpointScenarios() {}

    public static SimScenario endpointCompletesAtCover() {
        return endpoint(false);
    }

    public static SimScenario crossingPingCenterKeepsClaim() {
        return endpoint(true);
    }

    private static SimScenario endpoint(boolean crossesCenter) {
        int pingX = crossesCenter ? 30 : 32;
        int pingZ = crossesCenter ? 24 : 34;
        Probe probe = new Probe(pingX, pingZ);
        SimScenario.Builder scenario = SimScenario.builder(crossesCenter
                ? "goto_cross_cover_center_keeps_claim" : "goto_cover_endpoint_completion")
            .description("Static cover outside the GO_TO centre radius is the command endpoint; "
                + (crossesCenter ? "crossing the centre must preserve the selected approach."
                    : "the direct cover route avoids the centre and stays docked after quiet dwell."))
            .duration(420)
            .core()
            .field(72, 48)
            .terrain(t -> {
                if (crossesCenter) {
                    // Only this lane has footing: danger costs cannot steer
                    // the route around the raw arrival radius in a full suite.
                    t.fill(t.at(18, -1, 23), t.at(65, -1, 25),
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
                } else {
                    t.floorOnly();
                }
                t.coverRow(44, 21, 44, 27, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 24);
                // Keep the route inside exact-path range so the test observes
                // endpoint ownership rather than distant staging/fallback.
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), SOLDIER, 22, 0, 24, SquadMode.HOLD);
                ctx.setDangerSweepEnabled(false);
            })
            .action(20, "issue GO_TO after the spawned soldier is grounded", ctx -> {
                // A live, stationary threat keeps cover assessment anchored
                // here rather than to incidental mobs below the test slab.
                var enemy = ctx.spawnEnemyLabeled("threat", 62, 0, 24);
                enemy.setNoAi(true);
                enemy.setInvulnerable(true);
                ctx.soldier(SOLDIER).setTarget(enemy);
                ctx.soldier(SOLDIER).getThreatAwareness().onEnemyPing(enemy.blockPosition());
                ctx.injectThreat(62, 24, 1.0f);
                ctx.issueGoTo(pingX, pingZ);
            });
        for (int tick = 21; tick <= 415; tick++) {
            final int observedTick = tick;
            scenario.action(tick, "observe selected-cover command ownership", ctx -> {
                SoldierEntity soldier = ctx.soldier(SOLDIER);
                if (soldier != null && soldier.isAlive()) probe.observe(soldier, observedTick);
            });
        }
        return scenario
            .assertThat("selects and reaches the first cover without cancelling or replacing its approach", view -> {
                probe.log(crossesCenter);
                return probe.target != null && probe.claim != null && probe.target.equals(probe.claim)
                    && !probe.targetChanged && !probe.approachInterrupted && !probe.prematureCompletion;
            })
            .assertThat("selected cover completes GO_TO and clears relocation ownership on first arrival",
                view -> probe.claimTick > 0 && probe.holdingAtClaim && !probe.relocatingAtClaim
                    && probe.dockTick >= probe.claimTick)
            .assertThat(crossesCenter
                    ? "crosses the raw ping radius while the selected cover still owns movement"
                    : "reaches cover over 12.25 blocks from the ping without visiting its centre",
                view -> crossesCenter ? probe.activeAtCenter
                    : probe.claimDistance > 12.25 && probe.closestCenterBeforeClaim > 2.0)
            .assertThat("retains the first claim and GO_TO holding through quiet dwell",
                view -> probe.claimTick > 0 && probe.claimTick < 250
                    && probe.retained && probe.holdingAtEnd && probe.endObserved)
            .build();
    }

    private static final class Probe {
        final int pingX;
        final int pingZ;
        BlockPos target;
        BlockPos claim;
        int targetTick = -1;
        int claimTick = -1;
        int dockTick = -1;
        boolean targetChanged;
        boolean approachInterrupted;
        boolean prematureCompletion;
        boolean activeAtCenter;
        boolean holdingAtClaim;
        boolean relocatingAtClaim;
        boolean retained = true;
        boolean holdingAtEnd;
        boolean endObserved;
        double claimDistance = -1;
        double closestCenterBeforeClaim = Double.MAX_VALUE;

        Probe(int pingX, int pingZ) {
            this.pingX = pingX;
            this.pingZ = pingZ;
        }

        void observe(SoldierEntity soldier, int tick) {
            var manager = soldier.getCoverBehaviorManager();
            var current = manager.getCurrentCover();
            var next = manager.getTargetCover();
            boolean handling = soldier.getCoverTacticalGoal()
                .isHandlingGoToRelocation(soldier.getPingMoveGeneration());
            if (claim == null) {
                double distance = Math.hypot(soldier.getX() - pingX - 0.5,
                    soldier.getZ() - pingZ - 0.5);
                closestCenterBeforeClaim = Math.min(closestCenterBeforeClaim, distance);
                if (next != null && handling) {
                    if (target == null) {
                        target = next.getPosition().immutable();
                        targetTick = tick;
                    } else if (!target.equals(next.getPosition())) {
                        targetChanged = true;
                    }
                }
                if (current == null) {
                    if (soldier.isGoToHolding()) prematureCompletion = true;
                    if (target != null && !handling) approachInterrupted = true;
                    if (distance <= 2.0 && target != null && handling
                        && manager.isGoToRelocationActive() && !soldier.isGoToHolding()) {
                        activeAtCenter = true;
                    }
                } else {
                    claim = current.getPosition().immutable();
                    claimTick = tick;
                    holdingAtClaim = soldier.isGoToHolding();
                    relocatingAtClaim = manager.isGoToRelocationActive();
                    claimDistance = Math.hypot(claim.getX() - pingX, claim.getZ() - pingZ);
                }
            }
            if (claim != null) {
                if (current == null || !claim.equals(current.getPosition())) retained = false;
                if (dockTick < 0 && current != null && claim.equals(current.getPosition())
                    && Math.hypot(soldier.getX() - claim.getX() - 0.5,
                        soldier.getZ() - claim.getZ() - 0.5) <= 0.12) {
                    dockTick = tick;
                }
            }
            if (tick == 415) {
                endObserved = true;
                holdingAtEnd = soldier.isGoToHolding();
            }
        }

        void log(boolean crossesCenter) {
            StevesArmyMod.LOGGER.info(
                "[SimGoToEndpoint] crossing={} target={} targetTick={} claim={} claimTick={} dockTick={} "
                    + "holdingAtClaim={} relocatingAtClaim={} targetChanged={} interrupted={} premature={} "
                    + "activeAtCenter={} closestCenter={} claimDistance={} retained={} holdingAtEnd={}",
                crossesCenter, target, targetTick, claim, claimTick, dockTick, holdingAtClaim,
                relocatingAtClaim, targetChanged, approachInterrupted, prematureCompletion,
                activeAtCenter, closestCenterBeforeClaim, claimDistance, retained, holdingAtEnd);
        }
    }
}

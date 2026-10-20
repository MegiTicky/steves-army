package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CqbSteeringAdvisor;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import java.util.List;

/** Isolated input/controller probes in a real server level; AI disabled to prevent renewed sightings. */
public final class CautionSteeringScenarios {
    private CautionSteeringScenarios() {}

    /** Real pathfinding over the trace's one-block cover row, with a nearby parked friendly. */
    public static SimScenario raisedPathDoorway() {
        boolean[] ok = new boolean[5];
        double[] startZ = new double[1];
        var builder = SimScenario.builder("cqb_raised_path_not_doorway").core().duration(140)
            .description("A future path node above a one-block cover row must sample doorway walls "
                + "at that node's height. Nearby friendlies must not turn the row into a doorway; "
                + "real ground-level and elevated passages still queue and release when empty.")
            .terrain(t -> {
                t.platform();
                t.coverRow(0, 24, 48, 24, 1);
                t.coverRow(29, 20, 29, 20, 2);
                t.coverRow(31, 20, 31, 20, 2);
                // Raised doorway, with its approach standing one block lower.
                t.coverRow(29, 30, 31, 30, 1);
                t.block(29, 1, 30, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                t.block(31, 1, 30, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            })
            .setup(ctx -> {
                java.util.Arrays.fill(ok, false);
                ctx.spawnAnchor(4, 0, 4);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "walker", 20, 0, 19, SquadMode.HOLD).setNoAi(true);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "parked", 18, 0, 23, SquadMode.HOLD).setNoAi(true);
                ctx.setDangerSweepEnabled(false);
            })
            .action(30, "real path crosses the cover row beside a parked friendly", ctx -> {
                var s = ctx.soldier("walker");
                s.getThreatAwareness().onEnemyPing(ctx.terrain.at(20, 0, 28));
                s.setOnGround(true);
                var path = s.getNavigation().createPath(ctx.terrain.at(20, 0, 28), 0);
                com.stevesarmy.StevesArmyMod.LOGGER.info("[DoorwayHeightProbe] start={} ground={} path={}",
                    s.position(), s.onGround(), path == null ? "null" : path.getNodeCount() + "/" + path.canReach());
                ok[0] = path != null && path.canReach();
                if (path != null) {
                    boolean raised = false;
                    for (int i = 0; i < path.getNodeCount(); i++) {
                        var n = path.getNode(i);
                        raised |= n.y == ctx.terrain.origin.getY() + 1 && n.z == 24;
                    }
                    ok[0] &= raised;
                    s.getNavigation().moveTo(path, 1.2);
                }
                startZ[0] = s.getZ();
                ok[1] = !new CqbSteeringAdvisor().isDoorwayBlocked(s);
            })
            .action(65, "measure physical approach progress before the row", ctx -> {
                var s = ctx.soldier("walker");
                double progress = s.getZ() - startZ[0];
                com.stevesarmy.StevesArmyMod.LOGGER.info("[DoorwayHeightProbe] raisedRoute={} admitted={} progress={} limit={}",
                    ok[0], ok[1], progress,
                    ((com.stevesarmy.entity.ai.CoverPositionController) s.getMoveControl()).getSpeedController().limitingReason());
                ok[2] = progress >= 3.0;
            })
            .action(80, "ordinary doorway with a friendly in the passage queues", ctx -> {
                var s = ctx.soldier("walker");
                s.setPos(30.5, ctx.terrain.origin.getY(), 17.5);
                ctx.soldier("parked").setPos(30.5, ctx.terrain.origin.getY(), 20.5);
                s.getNavigation().moveTo(s.getNavigation().createPath(ctx.terrain.at(30, 0, 23), 0), 1.2);
                var advisor = new CqbSteeringAdvisor();
                boolean occupied = advisor.isDoorwayBlocked(s);
                ctx.soldier("parked").setPos(8.5, ctx.terrain.origin.getY(), 8.5);
                ok[3] = occupied && !advisor.isDoorwayBlocked(s);
            })
            .action(100, "raised doorway samples real walls above its supporting row", ctx -> {
                var s = ctx.soldier("walker");
                s.setPos(30.5, ctx.terrain.origin.getY(), 27.5);
                ctx.soldier("parked").setPos(30.5, ctx.terrain.origin.getY() + 1, 30.5);
                var path = s.getNavigation().createPath(ctx.terrain.at(30, 0, 33), 0);
                s.getNavigation().moveTo(path, 1.2);
                ok[4] = path != null && path.canReach() && new CqbSteeringAdvisor().isDoorwayBlocked(s);
            })
            .assertThat("fixture uses a reachable real path over the raised cover row", v -> ok[0])
            .assertThat("cover-row support blocks do not count as doorway walls", v -> ok[1])
            .assertThat("physical approach advances at least 3 blocks in 35 ticks", v -> ok[2])
            .assertThat("occupied ground-level doorway queues and empty doorway releases", v -> ok[3])
            .assertThat("occupied elevated doorway still queues from a lower approach", v -> ok[4]);
        for (int tick = 31; tick < 65; tick++) {
            builder.action(tick, "consume real navigation and controller travel", ctx -> {
                var s = ctx.soldier("walker");
                // Isolate the controller from unrelated cover selection while retaining real
                // navigation, collisions, vanilla travel and the per-tick movement budget.
                s.setNoAi(false);
                try {
                    s.getNavigation().tick();
                    s.getMoveControl().tick();
                    s.travel(new net.minecraft.world.phys.Vec3(s.xxa, 0, s.zza));
                } finally {
                    s.setNoAi(true);
                }
            });
        }
        return builder.build();
    }

    public static SimScenario freshnessAndPace() {
        boolean[] passed = new boolean[16];
        CqbSteeringAdvisor[] advisors = {new CqbSteeringAdvisor(), new CqbSteeringAdvisor(),
            new CqbSteeringAdvisor()};
        return SimScenario.builder("caution_freshness_and_pace")
            .description("Position ping, squad intel and retained live reference expire for caution; "
                + "fresh steering preserves requested pace, manual CQB caps, and release restores pace.")
            .duration(240).core()
            .terrain(t -> t.platform())
            .setup(ctx -> {
                java.util.Arrays.fill(passed, false);
                ctx.spawnAnchor(10, 0, 10);
                for (String label : List.of("ping", "intel", "reference")) {
                    int at = label.equals("reference") ? 32 : 20;
                    SoldierEntity s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), label, at, 0, at, SquadMode.HOLD);
                    s.setNoAi(true);
                    if (!label.equals("intel")) s.setSquadId(null);
                }
                // Keep the live-reference control well away from the ping/intel route.
                ctx.spawnEnemyLabeled("unobserved", 36, 0, 32).setNoAi(true);
                ctx.setDangerSweepEnabled(false);
            })
            .action(60, "one observation per source", ctx -> {
                var ping = ctx.soldier("ping");
                ping.getThreatAwareness().onEnemyPing(ctx.terrain.at(24, 0, 20));
                ctx.injectThreat(24, 20);
                var ref = ctx.soldier("reference");
                ref.getThreatAwareness().onEntityDetected(ctx.soldier("unobserved"), ref.position());
                // Bend with a threat nearby: corner-watch uses the same freshness policy.
                int y = ping.blockPosition().getY();
                ping.getNavigation().moveTo(new Path(List.of(new Node(21, y, 20),
                    new Node(22, y, 20), new Node(22, y, 21)), ctx.terrain.at(22, 0, 21), true), 1.0);
            })
            .action(65, "freshness positive controls and automatic requested speeds", ctx -> {
                int i = 0;
                for (String label : List.of("ping", "intel", "reference")) {
                    passed[i] = advisors[i].isCautionActive(ctx.soldier(label));
                    i++;
                }
                var s = ctx.soldier("ping");
                passed[3] = CqbSteeringAdvisor.getCornerWatchPos(s) != null;
                var attr = s.getAttribute(Attributes.MOVEMENT_SPEED);
                attr.addTransientModifier(new AttributeModifier(java.util.UUID.randomUUID(),
                    "probe weapon tax", -0.2, AttributeModifier.Operation.MULTIPLY_TOTAL));
                s.getNavigation().stop(); // force vanilla fallback, including speed-modifier persistence
                var ctrl = s.getMoveControl();
                for (int n = 0; n < 8; n++) {
                    ctrl.setWantedPosition(s.getX() + 10, s.getY(), s.getZ(), 1.0);
                    ctrl.tick();
                }
                passed[4] = Math.abs(s.getSpeed() - attr.getValue()) < 0.00001;
                ctrl.setWantedPosition(s.getX() + 10, s.getY(), s.getZ(), 0.12);
                ctrl.tick();
                passed[5] = Math.abs(s.getSpeed() - 0.12 * attr.getValue()) < 0.00001;
                s.setCQB(true);
                for (int n = 0; n < 15; n++) {
                    ctrl.setWantedPosition(s.getX() + 10, s.getY(), s.getZ(), 1.0);
                    ctrl.tick();
                }
                passed[6] = Math.abs(s.getSpeed() - 0.24 * attr.getBaseValue()) < 0.00001;
                s.setCQB(false);
                s.getThreatAwareness().clear();
                // Reuse the retained request: a clamp must not overwrite it.
                for (int n = 0; n < 8; n++) {
                    ctrl.setWantedPosition(s.getX() + 10, s.getY(), s.getZ(), ctrl.getSpeedModifier());
                    ctrl.tick();
                }
                passed[7] = Math.abs(s.getSpeed() - attr.getValue()) < 0.00001;
                s.getThreatAwareness().onPingDirection(ctx.terrain.at(24, 0, 20));
            })
            .action(200, "expired memories and retained reference cannot renew observation age", ctx -> {
                boolean expired = true;
                int i = 0;
                for (String label : List.of("ping", "intel", "reference")) {
                    var s = ctx.soldier(label);
                    s.getThreatAwareness().tick();
                    passed[9 + i] = !advisors[i].isCautionActive(s);
                    expired &= passed[9 + i++];
                }
                var ping = ctx.soldier("ping");
                int y = ping.blockPosition().getY();
                ping.getNavigation().moveTo(new Path(List.of(new Node(21, y, 20),
                    new Node(22, y, 20), new Node(22, y, 21)), ctx.terrain.at(22, 0, 21), true), 1.0);
                passed[8] = expired && CqbSteeringAdvisor.getCornerWatchPos(ping) == null
                    && ctx.soldier("reference").getThreatAwareness().getActiveThreatCount() > 0;
            })
            .action(210, "fresh automatic lateral-steering branch uses normal requested pace", ctx -> {
                var s = ctx.soldier("ping");
                s.getThreatAwareness().onEnemyPing(ctx.terrain.at(24, 0, 20));
                for (int n = 0; n < 8; n++) {
                    s.getMoveControl().setWantedPosition(s.getX() + 10, s.getY(), s.getZ(), 1.0);
                    s.getMoveControl().tick();
                }
                passed[12] = "cqb-caution".equals(((com.stevesarmy.entity.ai.CoverPositionController)
                    s.getMoveControl()).debugMoveSource())
                    && Math.abs(s.getSpeed() - s.getAttributeValue(Attributes.MOVEMENT_SPEED)) < 0.00001;
            })
            .action(215, "controller handoff preserves requested slow pace", ctx -> {
                var s = ctx.soldier("ping");
                var ctrl = (com.stevesarmy.entity.ai.CoverPositionController) s.getMoveControl();
                ctrl.moveTo(s.position().add(8, 0, 0), 0.08, 0.12);
                ctrl.tick();
                passed[13] = "controller".equals(ctrl.debugMoveSource())
                    && Math.abs(s.getSpeed() - 0.12 * s.getAttributeValue(Attributes.MOVEMENT_SPEED)) < 0.00001;
            })
            .action(220, "hidden reacquisition does not renew the observation", ctx -> {
                var s = ctx.soldier("reference");
                s.getThreatAwareness().onEntityDetected(ctx.soldier("unobserved"), s.position(), false);
                passed[14] = !advisors[2].isCautionActive(s);
            })
            .action(225, "actual renewed sighting enables caution again", ctx -> {
                var s = ctx.soldier("reference");
                s.getThreatAwareness().onEntityDetected(ctx.soldier("unobserved"), s.position());
                // Fresh advisor avoids conflating the observation assertion with transition debounce.
                passed[15] = new CqbSteeringAdvisor().isCautionActive(s);
            })
            .assertThat("fresh ping activates caution", v -> passed[0])
            .assertThat("fresh squad intel activates caution", v -> passed[1])
            .assertThat("fresh live sighting activates caution", v -> passed[2])
            .assertThat("fresh threat enables corner watch", v -> passed[3])
            .assertThat("automatic fallback uses requested pace with weapon tax", v -> passed[4])
            .assertThat("requested crawl/slow pace is preserved", v -> passed[5])
            .assertThat("manual CQB retains 0.24 base cap after ramp", v -> passed[6])
            .assertThat("release leaves no speed modifier latched", v -> passed[7])
            .assertThat("all stale sources release caution and corner watch without deleting cover memory", v -> passed[8])
            .assertThat("stale position ping releases caution", v -> passed[9])
            .assertThat("stale squad intel releases caution", v -> passed[10])
            .assertThat("retained live reference does not refresh observation", v -> passed[11])
            .assertThat("fresh lateral steering remains active at requested pace", v -> passed[12])
            .assertThat("controller-owned approach preserves requested slow pace", v -> passed[13])
            .assertThat("hidden acquisition preserves observation age", v -> passed[14])
            .assertThat("real observation renews caution", v -> passed[15])
            .build();
    }
}

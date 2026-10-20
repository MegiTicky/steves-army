package com.stevesarmy.sim;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;

/** Focused repro for attack movement continuity between successive MG cover docks. */
public final class MgAdvanceContinuityScenarios {
    private static final String GUNNER = "gunner";

    private MgAdvanceContinuityScenarios() {}

    public static SimScenario docklessRecovery(boolean reload) {
        final net.minecraft.world.phys.Vec3[] start = {null};
        String name = reload ? "mg_dockless_reload_search" : "mg_dockless_stale_target";
        var builder = SimScenario.builder(name)
            .duration(240).field(64, 40).armed()
            .description("Explicit fault injection at t20: MG in NO_COVER with no current dock and stopped navigation; "
                + (reload ? "fresh reload without a destination" : "abandoned target retained")
                + ". Real blocks, ordinary cover search and navigation must provide physical protection.")
            .terrain(t -> { t.platform(); t.coverRow(24, 8, 24, 32, 1); })
            .setup(ctx -> {
                ctx.spawnAnchor(18, 0, 20);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), GUNNER, 20, 0, 20, SquadMode.HOLD);
                var enemy = ctx.spawnEnemyLabeled("threat", 54, 0, 20);
                enemy.setNoAi(true);
                ctx.disarmSoldier("threat");
            })
            .action(20, "inject dockless stopped endpoint under pressure", ctx -> {
                var s = ctx.soldier(GUNNER);
                var m = s.getCoverBehaviorManager();
                m.clearCover();
                ((com.stevesarmy.entity.ai.CoverPositionController) s.getMoveControl()).clear();
                s.setPos(ctx.terrain.at(20, 0, 20).getX() + 0.5,
                    ctx.terrain.at(20, 0, 20).getY(), ctx.terrain.at(20, 0, 20).getZ() + 0.5);
                start[0] = s.position();
                if (!reload) m.setTargetCover(new com.stevesarmy.combat.cover.CoverPoint(ctx.terrain.at(10, 0, 20)));
                m.setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.NO_COVER);
                ctx.injectSuppressionFromGround(GUNNER, 10, 54.5, 20.5, 1.6, 3.0f);
                if (reload) {
                    com.stevesarmy.combat.GunIntegration.shootAtPosition(s, s.position().add(0, -1, 0));
                    com.stevesarmy.combat.GunIntegration.reload(s);
                }
            });
        for (int t = 25; t <= 120; t += 5) builder.action(t, "sustain directional pressure",
            ctx -> ctx.injectSuppressionFromGround(GUNNER, 3, 54.5, 20.5, 1.6, 3.0f));
        return builder
            .assertThat("injected endpoint makes physical progress within 40 ticks", view ->
                start[0] != null && view.timeline().series(GUNNER).stream().anyMatch(s ->
                    s.tick > 20 && s.tick <= 60 && s.state.position != null
                    && Math.hypot(s.state.position.x - start[0].x, s.state.position.z - start[0].z) >= 0.5))
            .assertThat("physical protected dock reached within 120 ticks", view ->
                view.timeline().series(GUNNER).stream().anyMatch(s -> s.tick > 20 && s.tick <= 140
                    && s.state.currentCover != null && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.2
                    && s.exposedBodyRays == 0 && s.exposedHeadRays == 0))
            .assertThat("reload actually overlaps recovery", view -> !reload || view.timeline().series(GUNNER).stream()
                .anyMatch(s -> s.tick >= 20 && s.tick <= 60 && s.reloading))
            .build();
    }

    public static SimScenario advanceContinuity() {
        SimScenario.Builder scenario = SimScenario.builder("mg_advance_continuity")
            .duration(1500)
            .field(112, 40)
            .armed()
            .description("An MG and rifleman attack toward distant enemies through waist-high rows every five blocks; each valid dock should hand off to the next cover destination without an exposed idle at intermediate waypoints.")
            .terrain(t -> {
                t.platform();
                for (int x = 18; x <= 78; x += 5) {
                    t.coverRow(x, 10, x, 17, 1);
                    t.coverRow(x, 23, x, 30, 1);
                }
            })
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 20);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), GUNNER, 10, 0, 15, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 10, 0, 25, SquadMode.HOLD);
                EnemySoldierEntity farA = ctx.spawnEnemyLabeled("far_a", 102, 0, 15);
                EnemySoldierEntity farB = ctx.spawnEnemyLabeled("far_b", 102, 0, 25);
                farA.setNoAi(true);
                farB.setNoAi(true);
                farA.setSquadMode(SquadMode.HOLD);
                farB.setSquadMode(SquadMode.HOLD);
                farA.setHoldPosition(ctx.terrain.at(102, 0, 15));
                farB.setHoldPosition(ctx.terrain.at(102, 0, 25));
                ctx.issueAttackOrder(82, 20);
            });

        scenario.action(1490, "log MG attack continuity endpoint", ctx -> {
            var gunner = ctx.soldier(GUNNER);
            if (gunner != null) {
                var state = gunner.getCoverBehaviorManager().getState();
                var cover = gunner.getCoverBehaviorManager().getCurrentCover();
                var center = cover == null ? null : cover.getPosition().getCenter();
                double slot = cover == null ? -1.0 : Math.hypot(
                    gunner.getX() - center.x, gunner.getZ() - center.z);
                com.stevesarmy.StevesArmyMod.LOGGER.info(
                    "[MgAdvanceContinuity] t={} pos=({}, {}) coverState={} current={} target={} slot={} enemyDistance={} attackObjective={}",
                    ctx.level.getGameTime(), String.format(java.util.Locale.ROOT, "%.2f", gunner.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", gunner.getZ()), state,
                    cover,
                    gunner.getCoverBehaviorManager().getTargetCover(),
                    String.format(java.util.Locale.ROOT, "%.3f", slot),
                    gunner.getTarget() == null ? -1.0 : gunner.distanceTo(gunner.getTarget()),
                    gunner.getAttackTargetPos());
            }
        });

        return scenario
            .assertThat("MG obtains a physically valid cover dock before advancing along the rows",
                view -> view.timeline().series(GUNNER).stream().anyMatch(sample ->
                    sample.state.currentCover != null && sample.coverSlotOffset >= 0.0
                        && sample.coverSlotOffset <= 0.12))
            .assertThat("a released valid dock has its successor cover destination selected first",
                view -> {
                    var frames = view.timeline().series(GUNNER);
                    BlockPos validDock = null;
                    int validDocks = 0;
                    int handoffs = 0;
                    int pendingDepartureTick = -1;
                    int firstMissingSuccessorTick = -1;
                    SimSample firstMissingSuccessor = null;
                    for (SimSample sample : frames) {
                        if (!sample.hasAttackTarget || sample.state.position == null) continue;
                        boolean holdingCover = "IN_COVER".equals(sample.state.coverState)
                            || "SUPPRESSED_IN_COVER".equals(sample.state.coverState);
                        boolean physicallyDocked = holdingCover && sample.state.currentCover != null
                            && sample.coverSlotOffset >= 0.0 && sample.coverSlotOffset <= 0.12;
                        if (physicallyDocked && !sample.state.currentCover.equals(validDock)) {
                            validDock = sample.state.currentCover;
                            validDocks++;
                        }
                        if (validDock != null) {
                            double distanceFromDock = Math.hypot(sample.state.position.x
                                    - validDock.getX() - 0.5,
                                sample.state.position.z - validDock.getZ() - 0.5);
                            if (pendingDepartureTick < 0 && distanceFromDock > 2.5) {
                                pendingDepartureTick = sample.tick;
                            }
                        }
                        boolean successorSelected = validDock != null
                            && ((sample.state.targetCover != null
                                    && !sample.state.targetCover.equals(validDock))
                                || (sample.state.currentCover != null
                                    && !sample.state.currentCover.equals(validDock)));
                        if (pendingDepartureTick >= 0 && successorSelected) {
                            handoffs++;
                            validDock = null;
                            pendingDepartureTick = -1;
                        } else if (pendingDepartureTick >= 0
                            && sample.tick >= pendingDepartureTick) {
                            firstMissingSuccessorTick = pendingDepartureTick;
                            firstMissingSuccessor = sample;
                            break;
                        }
                    }
                    if (firstMissingSuccessor != null) {
                        com.stevesarmy.StevesArmyMod.LOGGER.info(
                            "[MgAdvanceContinuity] missingSuccessorAt={} observedAt={} pos={} current={} target={} state={} sup={} attackTarget={}",
                            firstMissingSuccessorTick, firstMissingSuccessor.tick,
                            firstMissingSuccessor.state.position, firstMissingSuccessor.state.currentCover,
                            firstMissingSuccessor.state.targetCover, firstMissingSuccessor.state.coverState,
                            firstMissingSuccessor.state.suppressionLevel, firstMissingSuccessor.hasAttackTarget);
                    }
                    com.stevesarmy.StevesArmyMod.LOGGER.info(
                        "[MgAdvanceContinuity] samples={} validDockEpisodes={} observedHandoffs={} finalX={}",
                        frames.size(), validDocks, handoffs,
                        frames.isEmpty() || frames.get(frames.size() - 1).state.position == null
                            ? -1.0 : frames.get(frames.size() - 1).state.position.x);
                    return validDocks > 0 && handoffs > 0 && firstMissingSuccessor == null;
                })
            .assertThat("no exposed multi-second idle occurs between intermediate cover waypoints",
                view -> {
                    var frames = view.timeline().series(GUNNER);
                    int stillExposedTicks = 0;
                    SimSample previous = null;
                    for (SimSample sample : frames) {
                        boolean docked = sample.state.currentCover != null
                            && sample.coverSlotOffset >= 0.0 && sample.coverSlotOffset <= 0.12;
                        boolean intermediate = sample.state.position != null
                            && sample.state.position.x >= 16.0 && sample.state.position.x <= 70.0;
                        boolean exposed = sample.exposedBodyRays > 0 || sample.exposedHeadRays > 0;
                        boolean suppressed = sample.state.pinned || sample.state.suppressionLevel >= 0.2f;
                        boolean contact = sample.enemyDistance >= 0.0f && sample.enemyDistance <= 8.0f;
                        boolean movementBlocked = !sample.state.movingSupported
                            || (sample.state.movementBlockReason != null
                                && !sample.state.movementBlockReason.isBlank()
                                && !"NONE".equalsIgnoreCase(sample.state.movementBlockReason));
                        boolean stationary = previous != null && sample.state.position != null
                            && previous.state.position != null
                            && Math.hypot(sample.state.position.x - previous.state.position.x,
                                sample.state.position.z - previous.state.position.z) < 0.03;
                        if (intermediate && !docked && exposed && stationary && !suppressed
                            && !sample.reloading && !contact && !movementBlocked) {
                            stillExposedTicks += Math.max(1, sample.tick - previous.tick);
                            if (stillExposedTicks >= 40) return false;
                        } else {
                            stillExposedTicks = 0;
                        }
                        previous = sample;
                    }
                    return true;
                })
            .build();
    }

    /**
     * Reproduces a stale claim after a valid dock is abandoned at an exposed
     * endpoint while suppression is unrecovered.
     */
    public static SimScenario dockedEndpointRecovery() {
        final int[] repingTick = {-1};
        final BlockPos[] startingDock = {null};
        final com.stevesarmy.combat.cover.CoverPoint[] startingCover = {null};
        final net.minecraft.world.phys.Vec3[] endpointStart = {null};
        final float[] injectedSuppression = {-1.0f};
        final boolean[] suppressionUnrecovered = {false};
        SimScenario.Builder scenario = SimScenario.builder("mg_stale_claim_endpoint")
            .duration(900)
            .field(104, 40)
            .armed()
            .description("After a machine gunner reaches a real row dock, the harness moves it to an exposed endpoint while preserving the claim and unrecovered suppression; stale cover ownership must yield to route or cover search promptly.")
            .terrain(t -> {
                t.platform();
                for (int x = 18; x <= 63; x += 5) {
                    t.coverRow(x, 10, x, 17, 1);
                    t.coverRow(x, 23, x, 30, 1);
                }
            })
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 20);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), GUNNER, 10, 0, 15, SquadMode.HOLD);
                EnemySoldierEntity farA = ctx.spawnEnemyLabeled("far_a", 96, 0, 15);
                EnemySoldierEntity farB = ctx.spawnEnemyLabeled("far_b", 96, 0, 25);
                farA.setNoAi(true);
                farB.setNoAi(true);
                farA.setSquadMode(SquadMode.HOLD);
                farB.setSquadMode(SquadMode.HOLD);
                farA.setHoldPosition(ctx.terrain.at(96, 0, 15));
                farB.setHoldPosition(ctx.terrain.at(96, 0, 25));
                ctx.issueAttackOrder(78, 20);
            });

        for (int tick = 5; tick <= 895; tick += 5) {
            final int at = tick;
            scenario.action(at, "reproduce stale claim after a settled cover dock", ctx -> {
                if (repingTick[0] >= 0) return;
                var gunner = ctx.soldier(GUNNER);
                if (gunner == null || !gunner.isAlive()) return;
                var manager = gunner.getCoverBehaviorManager();
                var cover = manager.getCurrentCover();
                if (cover == null || (manager.getState() != com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                    && manager.getState() != com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER)) return;
                var center = cover.getPosition().getCenter();
                double offset = Math.hypot(gunner.getX() - center.x, gunner.getZ() - center.z);
                if (offset > 0.12) return;
                startingDock[0] = cover.getPosition().immutable();
                startingCover[0] = cover;
                repingTick[0] = at;
                gunner.getNavigation().stop();
                if (gunner.getMoveControl() instanceof com.stevesarmy.entity.ai.CoverPositionController controller) {
                    controller.clear();
                }
                BlockPos endpoint = ctx.terrain.at(40, 0, 20);
                gunner.setPos(endpoint.getX() + 0.5, endpoint.getY(), endpoint.getZ() + 0.5);
                endpointStart[0] = gunner.position();
                manager.setCurrentCover(startingCover[0]);
                manager.clearTargetCover();
                ctx.injectSuppression(GUNNER, 1);
                injectedSuppression[0] = manager.getSuppressionLevel();
                suppressionUnrecovered[0] = !manager.getSuppressionTracker().isRecovered();
                manager.setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.NO_COVER);
            });
        }

        scenario.action(890, "log endpoint recovery evidence", ctx -> {
            var gunner = ctx.soldier(GUNNER);
            if (gunner == null) return;
            var manager = gunner.getCoverBehaviorManager();
            com.stevesarmy.StevesArmyMod.LOGGER.info(
                "[MgDockedEndpoint] repingTick={} startDock={} state={} current={} target={} pos=({}, {}) suppression={} unrecovered={} pinned={} objective={}",
                repingTick[0], startingDock[0], manager.getState(), manager.getCurrentCover(),
                manager.getTargetCover(), String.format(java.util.Locale.ROOT, "%.2f", gunner.getX()),
                String.format(java.util.Locale.ROOT, "%.2f", gunner.getZ()),
                manager.getSuppressionLevel(), !manager.getSuppressionTracker().isRecovered(),
                manager.isPinned(), gunner.getAttackTargetPos());
        });

        return scenario
            .assertThat("endpoint order is issued only after the gunner reaches a physically valid cover dock",
                view -> repingTick[0] > 0 && startingDock[0] != null)
            .assertThat("stale claim begins with moderate unrecovered suppression below the pinned threshold",
                view -> injectedSuppression[0] > 0.0f && injectedSuppression[0] < 0.5f
                    && suppressionUnrecovered[0])
            .assertThat("after the endpoint order, cover search starts or the gunner makes route progress within 20 ticks",
                view -> {
                    if (repingTick[0] <= 0 || endpointStart[0] == null) return false;
                    return view.timeline().series(GUNNER).stream()
                        .filter(sample -> sample.tick >= repingTick[0] && sample.tick <= repingTick[0] + 20)
                        .anyMatch(sample -> sample.hasAttackTarget
                            && ("SEEKING_COVER".equals(sample.state.coverState)
                            || "REPOSITIONING".equals(sample.state.coverState)
                            || sample.state.targetCover != null
                            || (sample.state.position != null && Math.hypot(
                                sample.state.position.x - endpointStart[0].x,
                                sample.state.position.z - endpointStart[0].z) >= 0.5)));
                })
            .assertThat("the departed dock claim is cleared once the gunner remains away from its occupied radius",
                view -> view.timeline().series(GUNNER).stream().allMatch(sample -> {
                    if (sample.tick < repingTick[0] + 30 || sample.state.position == null
                        || startingDock[0] == null) return true;
                    double fromDock = Math.hypot(sample.state.position.x - startingDock[0].getX() - 0.5,
                        sample.state.position.z - startingDock[0].getZ() - 0.5);
                    return fromDock <= 2.5 || !startingDock[0].equals(sample.state.currentCover);
                }))
            .build();
    }
}

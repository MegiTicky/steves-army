package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.CombatStateSnapshot;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.sim.detector.SimDetector;
import com.stevesarmy.sim.detector.StandardDetectors;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.sim.terrain.SimTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulation harness runtime: drives one scenario at a time from the server
 * tick loop — build fixture, spawn actors, execute the scripted action
 * timeline, sample per-soldier telemetry, then run detectors + assertions and
 * write the report. Auto-run mode (system property {@code sim.autorun=true})
 * executes the full registry at server start and halts the server; the
 * {@code /stevesarmy_sim} command runs scenarios interactively.
 */
public final class SimManager {
    private SimManager() {}

    /** Base Y for scenario fixtures: a sky platform, independent of world terrain. */
    private static final int Y_BASE = 100;
    /**
     * Vertical spacing between scenario fixtures. Chunks are full columns, so
     * this only separates the fixtures in the air — the registry must fit
     * under the y=320 build limit: Y_BASE + (registry size - 1) * spacing + 2
     * &lt;= 319 (rim top). At 3Y that allows 73 scenarios (100 + 72*3 + 2 = 318);
     * fixtures are cleared between runs, so a 3Y stack never overlaps, and
     * startup sweeps strays left by crashed sessions.
     */
    private static final int Y_SEPARATION = 3;
    private static final List<SimDetector> DETECTORS = StandardDetectors.all();

    private enum Phase { IDLE, RUNNING, CLEANUP, DONE }

    private static Phase phase = Phase.IDLE;
    private static List<SimScenario> queue = List.of();
    private static int queueIndex = 0;
    private static int yLevel = Y_BASE;
    private static boolean autorun = false;

    /**
     * sim.fastticks: 'auto' (default) = unthrottle iff sim.autorun; explicit
     * true/false overrides. Unthrottled = {@link #onFastTickClock} holds the
     * vanilla tick deadline in the past so the between-tick wait returns
     * instantly and scenarios tick at CPU speed.
     */
    private static final boolean FAST_TICKS = resolveFastTicks();

    private static boolean resolveFastTicks() {
        String mode = System.getProperty("sim.fastticks", "auto");
        if ("auto".equalsIgnoreCase(mode)) return Boolean.getBoolean("sim.autorun");
        return Boolean.parseBoolean(mode);
    }

    /** True when the tick loop should skip real-time pacing. */
    public static boolean fastTicks() {
        return FAST_TICKS;
    }

    // live run state
    private static ServerLevel level;
    private static SimScenario scenario;
    private static SimTerrain terrain;
    private static SimContext context;
    // per-run outcome tally for the SIM COMPLETE line (XPASS counts as a fail:
    // an expected-fail scenario passing means the old behavior came back)
    private static int tallyPass;
    private static int tallyXfail;
    private static final List<String> tallyFails = new ArrayList<>();
    private static SimTimeline timeline;
    private static SimRecorder recorder;
    private static int simTick = 0;
    private static int actionIndex = 0;
    private static float enemyHealthStart;
    /** Rounds fired per shooter UUID this scenario (from the GunIntegration shot tap). */
    private static final Map<UUID, Integer> shotCounts = new HashMap<>();
    private static final List<SimScenario.ScheduledAction> sortedActions = new ArrayList<>();
    private static final List<ChunkPos> forcedChunks = new ArrayList<>();

    private record ChunkPos(int x, int z) {}

    /** Auto-run entry: run every registered scenario headless, then halt. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("sim.autorun")) return;
        // Optional per-candidate score traces: -Dsim.coverlog=true
        com.stevesarmy.debug.DiagnosticLogManager.setCoverScoreLoggingEnabled(
            Boolean.getBoolean("sim.coverlog"));
        // Optional suppression-chain traces: -Dsim.suplog=true
        com.stevesarmy.debug.DiagnosticLogManager.setSuppressionLoggingEnabled(
            Boolean.getBoolean("sim.suplog"));
        // Optional combat-goal decision traces: -Dsim.ailog=true
        com.stevesarmy.debug.DiagnosticLogManager.setAttackLoggingEnabled(
            Boolean.getBoolean("sim.ailog"));
        // Optional cover-state transition + clearCover traces: -Dsim.statedlog=true
        com.stevesarmy.debug.DiagnosticLogManager.setCoverLoggingEnabled(
            Boolean.getBoolean("sim.statedlog"));
        // Optional per-shot gate traces: -Dsim.dmglog=true
        com.stevesarmy.debug.DiagnosticLogManager.setDamageLoggingEnabled(
            Boolean.getBoolean("sim.dmglog"));
        List<SimScenario> scenarios = SimRegistry.all();
        // Optional scenario filter for fast iteration: -Dsim.scenario=<name>[,<name>...]
        // (a server boot costs minutes; one boot can serve several scenarios)
        String only = System.getProperty("sim.scenario", "");
        if (!only.isEmpty()) {
            java.util.Set<String> wanted = java.util.Arrays.stream(only.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
            scenarios = scenarios.stream()
                .filter(s -> wanted.contains(s.name))
                .toList();
            if (scenarios.isEmpty()) {
                StevesArmyMod.LOGGER.error("[Sim] sim.scenario='{}' matches no registered scenario", only);
                return;
            }
        } else {
            // Suite tiers: core (default) runs the stable per-family guards;
            // -Dsim.suite=full runs every registered scenario.
            String suite = System.getProperty("sim.suite", "core");
            if (!"full".equalsIgnoreCase(suite)) {
                scenarios = scenarios.stream().filter(s -> s.coreTier).toList();
            }
        }
        StevesArmyMod.LOGGER.info("[Sim] sim.autorun detected — running {} scenario(s) headless ({}); fastTicks={}",
            scenarios.size(), only.isEmpty() ? System.getProperty("sim.suite", "core") + " suite" : "explicit list",
            FAST_TICKS);
        // Throwaway world: skip mid-run autosaves. Under fast ticks they would
        // otherwise fire far more often in wall time; strays are swept at
        // startup and every fixture is rebuilt per scenario, so nothing reads
        // persisted state.
        event.getServer().getAllLevels().forEach(l -> l.noSave = true);
        start(event.getServer().overworld(), scenarios, true);
    }

    public static boolean running() {
        return phase == Phase.RUNNING || phase == Phase.CLEANUP;
    }

    /** Interactive/command entry. Returns false when already running or the level is unsuitable. */
    public static boolean start(ServerLevel serverLevel, List<SimScenario> scenarios, boolean isAutorun) {
        if (running()) return false;
        if (serverLevel.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            StevesArmyMod.LOGGER.warn("[Sim] difficulty is PEACEFUL — soldiers (Monster subclass) would despawn; aborting");
            return false;
        }
        level = serverLevel;
        queue = new ArrayList<>(scenarios);
        queueIndex = 0;
        tallyPass = 0;
        tallyXfail = 0;
        tallyFails.clear();
        yLevel = Y_BASE;
        autorun = isAutorun;
        discardLeftoverSimEntities();
        sweepLeftoverFixtureBlocks();
        // Unarmed tail state from an aborted run must not leak into this session
        SimGunHandler.setArmed(false);
        beginScenario();
        return true;
    }

    /**
     * Aborted autorun sessions leave fixture blocks floating in the sky (a
     * crash mid-scenario skips that scenario's cleanup — any Y, not just near
     * the build limit). Every stray fixture-palette block above Y_BASE poisons
     * heightmap queries for the danger map and every future scenario. Runs
     * once per session, BEFORE scenario 1 builds, so nothing live is removed.
     */
    private static void sweepLeftoverFixtureBlocks() {
        int removed = 0;
        BlockPos from = new BlockPos(-2, Y_BASE + 1, -2);
        BlockPos to = new BlockPos(64, level.getMaxBuildHeight() - 1, 64);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE)
                || state.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) {
                level.removeBlock(pos, false);
                removed++;
            }
        }
        if (removed > 0) {
            StevesArmyMod.LOGGER.warn("[Sim] swept {} leftover fixture blocks above y={} from a previous session",
                removed, Y_BASE);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase != Phase.RUNNING) return;
        simTick++;

        while (actionIndex < sortedActions.size() && sortedActions.get(actionIndex).tick() <= simTick) {
            SimScenario.ScheduledAction action = sortedActions.get(actionIndex++);
            try {
                action.action().accept(context);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.error("[Sim] action '{}' failed at {}t: {}", action.describe(), simTick, e.toString(), e);
            }
            recorder.event(simTick, action.describe());
        }

        if (simTick % SimTuning.SAMPLE_INTERVAL_TICKS == 0 || simTick >= scenario.durationTicks) {
            sample();
        }

        if (simTick >= scenario.durationTicks) {
            phase = Phase.CLEANUP;
        }
    }

    @SubscribeEvent
    public static void onServerCleanupTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase != Phase.CLEANUP) return;
        finishScenario();
    }

    /**
     * MinecraftServer.nextTickTime drives the vanilla 50 ms/tick pacing: each
     * iteration advances it by 50 and then blocks in waitUntilNextTick until
     * the clock passes it, which locks scenarios to real time. Holding it
     * ~100 ms in the past makes every pacing check fall through instantly, so
     * the server ticks at CPU speed while the task queue still drains through
     * the normal loop. The value must come from Util.getMillis (the monotonic
     * clock vanilla compares against), never the wall clock. Reflection with
     * both name dialects: production classes are SRG-named, dev classes are
     * mojmap-named. Gated on FAST_TICKS, so production never touches this.
     */
    private static volatile java.lang.reflect.Field nextTickTimeField;
    private static boolean fastTicksLogged;

    @SubscribeEvent
    public static void onFastTickClock(TickEvent.ServerTickEvent event) {
        if (!FAST_TICKS || event.phase != TickEvent.Phase.END || level == null) return;
        try {
            java.lang.reflect.Field field = nextTickTimeField;
            if (field == null) {
                for (String name : new String[] {"f_129726_", "nextTickTime"}) {
                    try {
                        field = net.minecraft.server.MinecraftServer.class.getDeclaredField(name);
                        field.setAccessible(true);
                        break;
                    } catch (ReflectiveOperationException ignored) {
                        // wrong name dialect for this environment; try the next
                    }
                }
                nextTickTimeField = field;
                if (field == null) return;
            }
            field.setLong(level.getServer(), net.minecraft.Util.getMillis() - 100L);
            if (!fastTicksLogged) {
                fastTicksLogged = true;
                StevesArmyMod.LOGGER.info("[Sim] fast ticks active — real-time pacing disabled");
            }
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // never let the accelerator break the tick loop
        }
    }

    private static void beginScenario() {
        scenario = queue.get(queueIndex);
        StevesArmyMod.LOGGER.info("[Sim] === scenario {}/{}: {} ===", queueIndex + 1, queue.size(), scenario.name);
        terrain = new SimTerrain(level, new BlockPos(0, yLevel, 0), scenario.width, scenario.depth);
        scenario.terrainBuilder.accept(terrain);
        recorder = new SimRecorder(scenario.width, scenario.depth);
        recorder.captureTerrain(terrain);
        forceChunks();
        // The session-start sweep misses actors leaked into unloaded chunks of
        // the saved world; now that this scenario's chunks are forced, sweep
        // again so a leaked soldier/anchor cannot join the live run.
        discardLeftoverSimEntities();
        context = new SimContext(level, terrain);
        timeline = new SimTimeline();
        enemyHealthStart = 0f;
        shotCounts.clear();
        // tracer tap: every successful shot from anyone lands in the recording
        GunIntegration.shotListener = (shooter, aim) -> {
            recorder.shot(shooter.getX(), shooter.getZ(), aim.x, aim.z);
            shotCounts.merge(shooter.getUUID(), 1, Integer::sum);
        };
        // Armed mode must be settled BEFORE setup runs: transitioning the gun
        // handler clears its per-entity state, which would silently undo any
        // disarmSoldier() the setup performed.
        SimGunHandler.setArmed(scenario.armed);
        scenario.setup.accept(context);
        enemyHealthStart = totalEnemyHealth();
        sortedActions.clear();
        sortedActions.addAll(scenario.actions);
        sortedActions.sort(Comparator.comparingInt(SimScenario.ScheduledAction::tick));
        actionIndex = 0;
        simTick = 0;
        // determinism aids: fixed daytime, no weather, no ambient mob spawns
        level.setDayTime(1000);
        level.setRainLevel(0.0f);
        level.setThunderLevel(0.0f);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        phase = Phase.RUNNING;
    }

    private static void sample() {
        recorder.beginFrame(simTick);
        for (SoldierEntity soldier : context.friendlies) {
            if (soldier.isRemoved()) continue;
            CombatStateSnapshot snap = captureFor(soldier);
            if (snap == null) continue;

            Boolean los = null;
            Double nearestX = null;
            Double nearestZ = null;
            EnemySoldierEntity nearest = nearestEnemy(soldier, context.enemies);
            if (nearest != null) {
                nearestX = nearest.getX();
                nearestZ = nearest.getZ();
                VisibilityRay.Result ray = VisibilityRay.traceFresh(
                    level, soldier.getEyePosition(), nearest.getEyePosition(), soldier);
                los = ray != null && ray.clear();
            }

            int reservation = snap.currentCover != null
                ? CoverReservationManager.getReservationCount(snap.currentCover) : -1;

            String label = labelOf(soldier);
            boolean hasGun = GunIntegration.hasGun(soldier);
            // Live combat-entity target (what the firing/peek chains key on) —
            // NOT hasValidAttackTarget(), which is the player-issued ATTACK
            // objective BlockPos and is never set in scenarios.
            net.minecraft.world.entity.LivingEntity combatTarget = soldier.getTarget();

            // cover-dock fidelity probe: slot offset, anchor telemetry, body exposure
            double slotOffset = -1;
            if (snap.currentCover != null) {
                Vec3 slot = CoverTacticalGoal.getCoverStandingPositionStatic(snap.currentCover);
                double dx = soldier.getX() - slot.x;
                double dz = soldier.getZ() - slot.z;
                slotOffset = Math.sqrt(dx * dx + dz * dz);
            }
            int anchorPulls = -1;
            if (soldier.getMoveControl() instanceof com.stevesarmy.entity.ai.CoverPositionController ctrl) {
                anchorPulls = ctrl.getDebugAnchorPulls();
            }
            int exposedRays = -1;
            int exposedHeadRays = -1;
            int probedRays = 0;
            if (nearest != null) {
                CoverFidelityProbe.Exposures rays = CoverFidelityProbe.probe(
                    level, soldier, nearest.getEyePosition());
                exposedRays = rays.exposed();
                exposedHeadRays = rays.exposedHead();
                probedRays = rays.probed();
            }

            // danger read: scenario-registered stub sampler (rig self-tests)
            // wins; otherwise the soldier's real per-squad danger map, which
            // also exposes the per-height bits the probe detector needs
            double dangerAtSoldier = -1;
            boolean dangerLegExposed = false;
            DangerSampler sampler = context.dangerSampler();
            if (sampler == null && soldier.getSquadId() != null) {
                com.stevesarmy.squad.SquadData squadData = com.stevesarmy.squad.SquadManager.get(level)
                    .getSquadById(soldier.getSquadId()).orElse(null);
                if (squadData != null) {
                    com.stevesarmy.squad.SquadDangerMap.Cell cell = squadData.getDangerMap()
                        .cellAt(soldier.getX(), soldier.getZ(), level.getGameTime());
                    if (cell != null) {
                        dangerAtSoldier = cell.score;
                        dangerLegExposed = cell.legExposed;
                    } else {
                        dangerAtSoldier = squadData.getDangerMap()
                            .dangerAt(soldier.getX(), soldier.getZ(), level.getGameTime());
                    }
                }
            }
            if (sampler != null) {
                dangerAtSoldier = sampler.dangerScoreAt(soldier.getX(), soldier.getZ());
            }

            com.stevesarmy.squad.ClearingCoordinator.ClearingView clearingView =
                com.stevesarmy.squad.ClearingCoordinator.viewFor(soldier);
            timeline.add(new SimSample(simTick, label, snap,
                soldier.getHealth(), soldier.getMaxHealth(),
                combatTarget != null && combatTarget.isAlive(), los,
                nearest == null ? -1f : soldier.distanceTo(nearest), reservation,
                hasGun,
                shotCounts.getOrDefault(soldier.getUUID(), 0),
                hasGun ? GunIntegration.getCurrentAmmo(soldier) : -1,
                hasGun ? GunIntegration.getMagazineSize(soldier) : -1,
                slotOffset, anchorPulls, exposedRays, exposedHeadRays, probedRays,
                soldier.isPreparingOrReloading(),
                soldier.getPose().name(), soldier.isHalfCoverRising(),
                snap.currentCover != null ? soldier.getSyncedCoverCurrentHeight() : -1f,
                dangerAtSoldier, dangerLegExposed, snap.headYaw,
                clearingView == null ? -1 : clearingView.stage().ordinal(),
                clearingView == null ? -1 : clearingView.checkedCount(),
                clearingView != null && clearingView.secureAssemble(),
                soldier.getCoverTacticalGoal().isClearingAdvancing()));
            recorder.soldier(label, snap, los, nearestX, nearestZ,
                shotCounts.getOrDefault(soldier.getUUID(), 0),
                hasGun,
                hasGun ? GunIntegration.getCurrentAmmo(soldier) : -1,
                hasGun ? GunIntegration.getMagazineSize(soldier) : -1,
                soldier.getHealth(),
                slotOffset, anchorPulls, exposedRays, exposedHeadRays, probedRays,
                soldier.isPreparingOrReloading(),
                soldier.getPose().name(), soldier.isHalfCoverRising(),
                snap.currentCover != null ? soldier.getSyncedCoverCurrentHeight() : -1f,
                dangerAtSoldier, dangerLegExposed);
        }
        recorder.endFrame(context.enemies);
    }

    /** Wrapper cover goals (MG, support) shell a real CoverTacticalGoal; unwrap for sampling. */
    private static CombatStateSnapshot captureFor(SoldierEntity soldier) {
        if (soldier.getCoverTacticalGoal() instanceof CoverTacticalGoal goal) {
            return goal.captureStateSnapshot();
        }
        if (soldier.getCoverTacticalGoal() instanceof com.stevesarmy.entity.ai.SupportCoverGoal supportGoal) {
            return supportGoal.getCoverController().captureStateSnapshot();
        }
        if (soldier.getCoverTacticalGoal() instanceof com.stevesarmy.entity.ai.MachineGunnerSupportGoal mgGoal) {
            return mgGoal.getCoverController().captureStateSnapshot();
        }
        return null;
    }

    private static EnemySoldierEntity nearestEnemy(SoldierEntity soldier, List<EnemySoldierEntity> enemies) {
        EnemySoldierEntity nearest = null;
        float best = Float.MAX_VALUE;
        for (EnemySoldierEntity enemy : enemies) {
            if (enemy.isRemoved() || !enemy.isAlive()) continue;
            float d = soldier.distanceTo(enemy);
            if (d < best) {
                best = d;
                nearest = enemy;
            }
        }
        return nearest;
    }

    private static String labelOf(SoldierEntity soldier) {
        for (var entry : context.byLabel.entrySet()) {
            if (entry.getValue() == soldier) return entry.getKey();
        }
        return "soldier-" + soldier.getId();
    }

    private static void finishScenario() {
        float enemyHealthEnd = totalEnemyHealth();
        SimRunResult result = new SimRunResult(scenario, scenario.durationTicks,
            GunIntegration.isAnyGunLoaded() && scenario.armed,
            enemyHealthStart, enemyHealthEnd, timeline);

        // danger-map oracle: independent ground truth over the injected threats,
        // then the sampler-vs-oracle sweep the DangerOracleMismatch detector judges
        if (!context.injectedThreats().isEmpty()) {
            int[] region = context.dangerVerificationRegion();
            int x1 = region != null ? region[0] : 3;
            int z1 = region != null ? region[1] : 3;
            int x2 = region != null ? region[2] : scenario.width - 3;
            int z2 = region != null ? region[3] : scenario.depth - 3;
            List<DangerOracle.ThreatOrigin> origins = new ArrayList<>();
            for (SimContext.InjectedThreat threat : context.injectedThreats()) {
                if (context.isInjectedThreatDead(threat.threatId())) continue;
                origins.add(new DangerOracle.ThreatOrigin(threat.eye(), 1.0));
            }
            result.dangerOracle = DangerOracle.compute(level, origins, x1, z1, x2, z2,
                terrain.origin.getY());
            DangerSampler sweepSampler = context.dangerSampler();
            com.stevesarmy.squad.SquadDangerMap probeMap = null;
            if (sweepSampler == null) {
                // no stub registered: sweep the soldier's REAL danger map against the oracle
                for (SoldierEntity soldier : context.friendlies) {
                    if (soldier.isRemoved() || soldier.getSquadId() == null) continue;
                    com.stevesarmy.squad.SquadData squadData = com.stevesarmy.squad.SquadManager.get(level)
                        .getSquadById(soldier.getSquadId()).orElse(null);
                    if (squadData == null) continue;
                    final com.stevesarmy.squad.SquadDangerMap map = squadData.getDangerMap();
                    probeMap = map;
                    final long now = level.getGameTime();
                    sweepSampler = (x, z) -> map.dangerAt(x, z, now);
                    break;
                }
            }
            if (context.dangerSweepEnabled() && sweepSampler != null) {
                result.oracleSweep = DangerOracle.sweep(result.dangerOracle,
                    sweepSampler, SimTuning.DANGER_CELL_TOLERANCE);
            }
            if (probeMap != null) {
                StevesArmyMod.LOGGER.info("[Sim] danger map: bakedCells={} activeThreats={} maxRaysPerTick={} (budget {})",
                    probeMap.bakedCellCount(), probeMap.activeThreatCount(),
                    com.stevesarmy.squad.SquadDangerMap.debugMaxRaysUsedPerTick(),
                    com.stevesarmy.StevesArmyConfig.getDangerRayBudgetPerTick());
            }
            recorder.setDangerOverlay(result.dangerOracle);
        } else {
            // live-source scenario: no oracle exists — export each friendly
            // squad's actual baked map so the animation shows the real
            // gradient the soldiers acted on
            java.util.Set<java.util.UUID> exportedSquads = new java.util.HashSet<>();
            for (SoldierEntity soldier : context.friendlies) {
                if (soldier.isRemoved() || soldier.getSquadId() == null) continue;
                if (!exportedSquads.add(soldier.getSquadId())) continue;
                com.stevesarmy.squad.SquadData squadData = com.stevesarmy.squad.SquadManager.get(level)
                    .getSquadById(soldier.getSquadId()).orElse(null);
                if (squadData == null) continue;
                com.stevesarmy.squad.SquadDangerMap map = squadData.getDangerMap();
                net.minecraft.world.phys.Vec3 anchor = map.anchor();
                if (anchor == null) continue;
                recorder.addDangerOverlayCells(map.snapshotCells(anchor.x, anchor.z,
                    com.stevesarmy.StevesArmyConfig.getDangerAmbientRadius(),
                    level.getGameTime(), 1000));
            }
        }

        for (SimDetector detector : DETECTORS) {
            if (scenario.suppressedDetectors.contains(detector.name())) continue;
            try {
                result.violations.addAll(detector.evaluate(result));
            } catch (Exception e) {
                StevesArmyMod.LOGGER.error("[Sim] detector {} crashed: {}", detector.name(), e.toString(), e);
            }
        }

        SimScenario.SimRunResultView view = new SimScenario.SimRunResultView() {
            @Override public SimTimeline timeline() { return timeline; }
            @Override public int durationTicks() { return scenario.durationTicks; }
            @Override public float enemyHealthStart() { return enemyHealthStart; }
            @Override public float enemyHealthEnd() { return enemyHealthEnd; }
            @Override public DangerOracle.Oracle dangerOracle() { return result.dangerOracle; }
            @Override public List<SimViolation> violations() { return result.violations; }
            @Override public List<double[]> shots() { return recorder.shotTracers(); }
            @Override public List<Long> shotTicks() { return recorder.shotTicks(); }
        };
        for (SimScenario.Assertion assertion : scenario.assertions) {
            try {
                if (!assertion.predicate().test(view)) {
                    result.assertionFailures.add("assertion failed: " + assertion.description());
                }
            } catch (Exception e) {
                result.assertionFailures.add("assertion crashed: " + assertion.description() + " (" + e + ")");
            }
        }

        // post-run orphan check: reservations outliving any in-cover claimant
        int orphaned = 0;
        for (BlockPos reserved : CoverReservationManager.getReservedPositions()) {
            boolean claimed = false;
            for (SoldierEntity soldier : context.friendlies) {
                if (!soldier.isRemoved() && soldier.getCoverBehaviorManager().isInCover()) {
                    claimed = true;
                    break;
                }
            }
            if (!claimed) orphaned++;
        }
        if (orphaned > 0) {
            result.violations.add(new SimViolation(SimViolation.Severity.WARN, "ReservationLeak",
                simTick, "-", orphaned + " reservation(s) outlived their claimants at cleanup"));
        }

        // danger-map ray budget is a hard perf contract: baking must defer, never overshoot
        int maxRays = com.stevesarmy.squad.SquadDangerMap.debugMaxRaysUsedPerTick();
        int budget = com.stevesarmy.StevesArmyConfig.getDangerRayBudgetPerTick();
        if (maxRays > budget) {
            result.violations.add(new SimViolation(SimViolation.Severity.FAIL, "DangerBudgetExceeded",
                simTick, "-", maxRays + " danger rays spent in one tick vs budget " + budget
                    + " — baking overspent the shared per-tick budget"));
        }

        if (result.failed()) {
            if (scenario.expectedFailReason != null) tallyXfail++;
            else tallyFails.add(scenario.name);
        } else if (scenario.expectedFailReason != null) {
            tallyFails.add(scenario.name + " (XPASS)");
        } else {
            tallyPass++;
        }

        SimReportWriter.write(result, simTick);
        SimRecordingWriter.write(result, recorder);
        // Clearing-order post-mortem: one line naming whatever still blocked
        // completion (open check targets, unresolved contacts, stage) so a
        // starved scenario is diagnosable straight from the run log.
        if (context.anchor != null) {
            StevesArmyMod.LOGGER.info("[AttackClear] final {}: {}",
                scenario.name,
                com.stevesarmy.squad.ClearingCoordinator.debugDump(
                    context.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL));
        }

        // cleanup
        for (SoldierEntity soldier : new ArrayList<>(context.friendlies)) {
            CoverReservationManager.releaseAll(soldier);
        }
        context.discardAll();
        context.disbandSquads();
        terrain.clear();
        unforceChunks();
        SimIsolation.reset(level);
        GunIntegration.shotListener = null;
        yLevel += Y_SEPARATION;
        queueIndex++;

        if (queueIndex < queue.size()) {
            beginScenario();
        } else {
            phase = Phase.DONE;
            StevesArmyMod.LOGGER.info("[Sim] SIM COMPLETE — {} pass, {} xfail, {} fail{} — reports in {}",
                tallyPass, tallyXfail, tallyFails.size(),
                tallyFails.isEmpty() ? "" : " (FAIL: " + String.join(", ", tallyFails) + ")",
                SimReportWriter.reportDir());
            if (autorun) {
                StevesArmyMod.LOGGER.info("[Sim] autorun finished — halting server");
                level.getServer().halt(false);
            }
        }
    }

    private static float totalEnemyHealth() {
        float total = 0f;
        for (EnemySoldierEntity enemy : context.enemies) {
            if (!enemy.isRemoved()) total += enemy.getHealth();
        }
        return total;
    }

    private static void forceChunks() {
        int chunkMinX = -2, chunkMinZ = -2;
        int chunkMaxX = (scenario.width >> 4) + 2, chunkMaxZ = (scenario.depth >> 4) + 2;
        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                level.setChunkForced(cx, cz, true);
                forcedChunks.add(new ChunkPos(cx, cz));
            }
        }
    }

    private static void unforceChunks() {
        for (ChunkPos pos : forcedChunks) {
            level.setChunkForced(pos.x(), pos.z(), false);
        }
        forcedChunks.clear();
    }

    /** An aborted earlier run can leave actors behind; sweep them before a fresh session. */
    private static void discardLeftoverSimEntities() {
        List<? extends SoldierEntity> soldiers = level.getEntities(
            net.minecraft.world.level.entity.EntityTypeTest.forClass(SoldierEntity.class), e -> true);
        List<? extends com.stevesarmy.entity.TargetEntity> targets = level.getEntities(
            net.minecraft.world.level.entity.EntityTypeTest.forClass(com.stevesarmy.entity.TargetEntity.class), e -> true);
        int discarded = 0;
        for (Entity entity : soldiers) {
            StevesArmyMod.LOGGER.info("[Sim] discarding leftover sim entity {}", entity.getId());
            entity.discard();
            discarded++;
        }
        for (Entity entity : targets) {
            StevesArmyMod.LOGGER.info("[Sim] discarding leftover sim anchor {}", entity.getId());
            entity.discard();
            discarded++;
        }
        if (discarded > 0) {
            StevesArmyMod.LOGGER.warn("[Sim] swept {} leftover sim entities from a previous session", discarded);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (phase == Phase.IDLE || phase == Phase.DONE || scenario == null) return;
        StevesArmyMod.LOGGER.warn("[Sim] server stopping mid-scenario '{}'", scenario.name);
        phase = Phase.IDLE;
    }
}

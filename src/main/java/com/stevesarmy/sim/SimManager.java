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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private static final int Y_BASE = 160;
    private static final int Y_SEPARATION = 40;
    private static final List<SimDetector> DETECTORS = StandardDetectors.all();

    private enum Phase { IDLE, RUNNING, CLEANUP, DONE }

    private static Phase phase = Phase.IDLE;
    private static List<SimScenario> queue = List.of();
    private static int queueIndex = 0;
    private static int yLevel = Y_BASE;
    private static boolean autorun = false;

    // live run state
    private static ServerLevel level;
    private static SimScenario scenario;
    private static SimTerrain terrain;
    private static SimContext context;
    private static SimTimeline timeline;
    private static SimRecorder recorder;
    private static int simTick = 0;
    private static int actionIndex = 0;
    private static float enemyHealthStart;
    private static final List<SimScenario.ScheduledAction> sortedActions = new ArrayList<>();
    private static final List<ChunkPos> forcedChunks = new ArrayList<>();

    private record ChunkPos(int x, int z) {}

    /** Auto-run entry: run every registered scenario headless, then halt. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("sim.autorun")) return;
        MinecraftServer server = event.getServer();
        StevesArmyMod.LOGGER.info("[Sim] sim.autorun detected — running all scenarios headless");
        start(server.overworld(), SimRegistry.all(), true);
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
        yLevel = Y_BASE;
        autorun = isAutorun;
        discardLeftoverSimEntities();
        beginScenario();
        return true;
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

    private static void beginScenario() {
        scenario = queue.get(queueIndex);
        StevesArmyMod.LOGGER.info("[Sim] === scenario {}/{}: {} ===", queueIndex + 1, queue.size(), scenario.name);
        terrain = new SimTerrain(level, new BlockPos(0, yLevel, 0), scenario.width, scenario.depth);
        scenario.terrainBuilder.accept(terrain);
        recorder = new SimRecorder(scenario.width, scenario.depth);
        recorder.captureTerrain(terrain);
        forceChunks();
        context = new SimContext(level, terrain);
        timeline = new SimTimeline();
        enemyHealthStart = 0f;
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
            timeline.add(new SimSample(simTick, label, snap,
                soldier.getHealth(), soldier.getMaxHealth(),
                soldier.hasValidAttackTarget(), los,
                nearest == null ? -1f : soldier.distanceTo(nearest), reservation,
                GunIntegration.hasGun(soldier)));
            recorder.soldier(label, snap, los, nearestX, nearestZ);
        }
        recorder.endFrame(context.enemies);
    }

    /** MG goal is a wrapper shell; unwrap to the real CoverTacticalGoal for sampling. */
    private static CombatStateSnapshot captureFor(SoldierEntity soldier) {
        if (soldier.getCoverTacticalGoal() instanceof CoverTacticalGoal goal) {
            return goal.captureStateSnapshot();
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

        for (SimDetector detector : DETECTORS) {
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

        SimReportWriter.write(result, simTick);
        SimRecordingWriter.write(result, recorder);

        // cleanup
        for (SoldierEntity soldier : new ArrayList<>(context.friendlies)) {
            CoverReservationManager.releaseAll(soldier);
        }
        context.discardAll();
        context.disbandSquads();
        terrain.clear();
        unforceChunks();
        SimIsolation.reset(level);
        yLevel += Y_SEPARATION;
        queueIndex++;

        if (queueIndex < queue.size()) {
            beginScenario();
        } else {
            phase = Phase.DONE;
            StevesArmyMod.LOGGER.info("[Sim] SIM COMPLETE — reports in {}", SimReportWriter.reportDir());
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

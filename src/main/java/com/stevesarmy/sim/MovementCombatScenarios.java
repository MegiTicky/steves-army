package com.stevesarmy.sim;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.FriendlyFireChecker;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.SoldierMovementController;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Live movement and fire contracts exercised through the scenario harness. */
public final class MovementCombatScenarios {
    private MovementCombatScenarios() {}

    private record MovementFrame(int tick, SoldierMovementController.Snapshot controller,
                                 double x, double z, float yaw, int shots,
                                 double saturatedTacticalSpeed, double lowTacticalSpeed,
                                 float facingError, String posture,
                                 boolean goToOwned, boolean attackOwned) {}

    public static SimScenario traverseProgress() {
        List<MovementFrame> frames = new ArrayList<>();
        SimScenario.Builder builder = SimScenario.builder("movement_traverse_progress").core().duration(260)
            .description("A soldier with no visible enemy follows a real GO_TO route in Traverse and makes physical progress.")
            .field(64, 48)
            .setup(ctx -> {
                frames.clear();
                ctx.spawnAnchor(8, 0, 22);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "walker", 8, 0, 22, SquadMode.HOLD);
                ctx.issueGoTo(46, 22);
            });
        for (int tick = 1; tick <= 250; tick++) {
            builder.action(tick, "sample no-contact traverse", ctx -> sample(ctx, "walker", frames));
        }
        builder.assertThat("NO CONTACT: mode remains Traverse and fire stays disabled",
            view -> frames.stream().allMatch(f -> f.controller().mode() == SoldierMovementController.Mode.TRAVERSE
                && f.controller().fireMode() == SoldierMovementController.FireMode.NONE));
        builder.assertThat("PHYSICAL ROUTE: GO_TO advances by at least eight blocks",
            view -> movedForward(frames, 8.0));
        return builder.build();
    }

    public static SimScenario attackPhaseCycle() {
        List<MovementFrame> frames = new ArrayList<>();
        final java.util.UUID[] shooterId = {null};
        SimScenario.Builder builder = SimScenario.builder("movement_attack_phase_cycle").armed().duration(360)
            .description("Visible contact drives bounded Traverse/Tactical phases, route movement, and accepted fire.")
            .field(64, 48)
            .setup(ctx -> {
                frames.clear();
                ctx.spawnAnchor(10, 0, 22);
                SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 10, 0, 22, SquadMode.HOLD);
                shooterId[0] = soldier.getUUID();
                ctx.harden("rifle");
                var target = ctx.spawnEnemyLabeled("target", 31, 0, 22);
                makePassiveDurableTarget(target);
                soldier.setTarget(target);
                ctx.issueAttackOrder(48, 22);
            });
        for (int tick = 1; tick <= 350; tick++) {
            builder.action(tick, "record live controller phases", ctx -> sample(ctx, "rifle", frames));
        }
        builder.action(359, "movement phase diagnostic", ctx -> logDiagnostics("attack_phase_cycle", frames, shooterId[0]));
        return builder
            .assertThat("VISIBLE CONTACT: cycle and phase boundaries stay inside their tick bounds",
                view -> hasBoundedCycle(frames))
            .assertThat("PHYSICAL MODES: selected contact exercises both Traverse and Tactical movement",
                view -> hasPhysicalModeCoverage(frames) && hasPhysicalModeAlternation(frames))
            .assertThat("PHYSICAL ADVANCE: the contact route produces forward displacement",
                view -> !frames.isEmpty() && frames.stream().mapToDouble(MovementFrame::x).max().orElse(0)
                    - frames.get(0).x() >= 5.0)
            .assertThat("LIVE FIRE: at least one accepted round is recorded",
                view -> hasAcceptedShotFrom(shooterId[0]))
            .build();
    }

    public static SimScenario orderShareContrast() {
        final double[] goToShare = {-1};
        final double[] attackShare = {-1};
        List<MovementFrame> goToFrames = new ArrayList<>();
        List<MovementFrame> attackFrames = new ArrayList<>();
        SimScenario.Builder builder = SimScenario.builder("movement_order_share_contrast").duration(380)
            .description("Real GO_TO and ATTACK orders under visible contact produce different Tactical phase durations.")
            .field(64, 48)
            .setup(ctx -> {
                goToShare[0] = -1;
                attackShare[0] = -1;
                goToFrames.clear();
                attackFrames.clear();
                ctx.spawnAnchor(8, 0, 22);
                SoldierEntity goTo = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "go_to", 8, 0, 18, SquadMode.HOLD);
                SoldierEntity attack = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "attack", 8, 0, 26, SquadMode.HOLD);
                var enemyA = ctx.spawnEnemyLabeled("enemy_go", 32, 0, 18);
                var enemyB = ctx.spawnEnemyLabeled("enemy_attack", 32, 0, 26);
                enemyA.setNoAi(true);
                enemyB.setNoAi(true);
                goTo.setTarget(enemyA);
                attack.setTarget(enemyB);
                ctx.issueGoTo(48, 18);
                attack.receivePing(com.stevesarmy.ping.PingType.ATTACK,
                    new Vec3(48.5, attack.getY(), 26.5));
            })
            .action(35, "capture contextual tactical shares", ctx -> {
                goToShare[0] = ctx.soldier("go_to").getMovementController().snapshot().tacticalShare();
                attackShare[0] = ctx.soldier("attack").getMovementController().snapshot().tacticalShare();
            });
        for (int tick = 1; tick <= 370; tick++) {
            builder.action(tick, "sample actual order phase durations", ctx -> {
                sample(ctx, "go_to", goToFrames);
                sample(ctx, "attack", attackFrames);
            });
        }
        builder.action(379, "order phase duration diagnostic", ctx -> {
            logDiagnostics("order_share_go_to", goToFrames, null);
            logDiagnostics("order_share_attack", attackFrames, null);
        });
        return builder
            .assertThat("ORDERS: GO_TO share is lower than ATTACK by at least 0.15",
                view -> goToShare[0] >= 0.0 && attackShare[0] - goToShare[0] >= 0.15)
            .assertThat("PLANNED PHASES: ATTACK allocates at least five percentage points more time Tactical",
                view -> averagePreferredTacticalCycleShare(attackFrames)
                    - averagePreferredTacticalCycleShare(goToFrames) >= 0.05)
            .assertThat("PHYSICAL MODE: both orders exercise Tactical and Traverse movement",
                view -> hasPhysicalModeCoverage(goToFrames) && hasPhysicalModeCoverage(attackFrames))
            .build();
    }

    public static SimScenario tacticalStrafeFacing() {
        List<MovementFrame> frames = new ArrayList<>();
        SimScenario.Builder builder = SimScenario.builder("movement_tactical_strafe_facing").duration(240)
            .description("The soldier advances laterally along a real route while keeping its body oriented toward a visible hostile.")
            .field(64, 48)
            .setup(ctx -> {
                frames.clear();
                ctx.spawnAnchor(14, 0, 24);
                SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "strafer", 14, 0, 24, SquadMode.HOLD);
                ctx.harden("strafer");
                var enemy = ctx.spawnEnemyLabeled("enemy", 14, 0, 8);
                makePassiveDurableTarget(enemy);
                soldier.setTarget(enemy);
                ctx.issueGoTo(46, 24);
            });
        for (int tick = 1; tick <= 230; tick++) {
            builder.action(tick, "sample lateral movement and target facing", ctx -> sample(ctx, "strafer", frames));
        }
        return builder
            .assertThat("LATERAL ADVANCE: soldier makes physical route progress",
                view -> movedForward(frames, 8.0))
            .assertThat("TARGET FACING: most Tactical samples stay oriented toward the visible hostile",
                view -> {
                    long tactical = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TACTICAL).count();
                    long facing = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TACTICAL
                        && f.facingError() >= 0 && f.facingError() <= 60).count();
                    return tactical >= 10 && facing >= tactical / 2;
                })
            .build();
    }

    public static SimScenario tacticalBackpedalProgress() {
        List<MovementFrame> frames = new ArrayList<>();
        SimScenario.Builder builder = SimScenario.builder("movement_tactical_backpedal_progress").duration(240)
            .description("The soldier walks away from a visible hostile while keeping its body facing the hostile.")
            .field(64, 48)
            .setup(ctx -> {
                frames.clear();
                ctx.spawnAnchor(22, 0, 24);
                SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "backpedal", 22, 0, 24, SquadMode.HOLD);
                ctx.harden("backpedal");
                var enemy = ctx.spawnEnemyLabeled("enemy", 16, 0, 18);
                enemy.setNoAi(true);
                soldier.setTarget(enemy);
                ctx.issueGoTo(48, 24);
            });
        for (int tick = 1; tick <= 230; tick++) {
            builder.action(tick, "sample backward movement and target facing", ctx -> sample(ctx, "backpedal", frames));
        }
        return builder
            .assertThat("BACKWARD ADVANCE: soldier moves away from the hostile along its route",
                view -> movedForward(frames, 8.0))
            .assertThat("TARGET FACING: Tactical movement continues facing the hostile",
                view -> {
                    long tactical = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TACTICAL).count();
                    long facing = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TACTICAL
                        && f.facingError() >= 0 && f.facingError() <= 60).count();
                    return tactical >= 10 && facing >= tactical / 2;
                })
            .build();
    }

    public static SimScenario hipSpreadBlocksLateralFriendly() {
        final boolean[] legacyAllowed = {false};
        final boolean[] spreadBlocked = {false};
        return SimScenario.builder("movement_hip_spread_blocks_lateral_friendly").duration(40)
            .description("A nearby lateral ally falls outside the old vertical rays but inside the live hip-fire spread envelope.")
            .field(48, 40)
            .setup(ctx -> {
                ctx.spawnAnchor(5, 0, 18);
                SoldierEntity shooter = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "shooter", 10, 0, 18, SquadMode.HOLD);
                SoldierEntity lateralAlly = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "ally", 17, 0, 19, SquadMode.HOLD);
                shooter.setNoAi(true);
                lateralAlly.setNoAi(true);
                Vec3 aimPoint = new Vec3(30.5, shooter.getEyeY(), 18.5);
                legacyAllowed[0] = FriendlyFireChecker.isSafeToShoot(shooter, aimPoint, 0.9f);
                spreadBlocked[0] = !FriendlyFireChecker.isSafeToShoot(shooter, aimPoint, 0.9f,
                    1.0f, 1.0f, 0.0f, 0.0f, false);
            })
            .assertThat("LATERAL FRIEND: legacy rays miss the ally and expanded hip spread blocks fire",
                view -> legacyAllowed[0] && spreadBlocked[0])
            .build();
    }

    public static SimScenario mandatoryCrawlFireGate() {
        final boolean[] gateHeld = {false};
        final java.util.UUID[] shooterId = {null};
        List<MovementFrame> frames = new ArrayList<>();
        SimScenario.Builder builder = SimScenario.builder("movement_mandatory_crawl_fire_gate").armed().duration(100)
            .description("A route's mandatory crawl constraint suppresses weapon fire until the constraint clears.")
            .setup(ctx -> {
                gateHeld[0] = false;
                frames.clear();
                ctx.spawnAnchor(10, 0, 22);
                SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "crawler", 10, 0, 22, SquadMode.HOLD);
                shooterId[0] = soldier.getUUID();
                ctx.harden("crawler");
                var enemy = ctx.spawnEnemyLabeled("enemy", 34, 0, 22);
                makePassiveDurableTarget(enemy);
                soldier.setTarget(enemy);
                ctx.issueGoTo(48, 22);
            })
            .action(20, "inspect crawl fire gate", ctx -> {
                SoldierEntity soldier = ctx.soldier("crawler");
                gateHeld[0] = soldier.getMovementController().isMandatoryCrawl()
                    && soldier.getMovementController().fireMode() == SoldierMovementController.FireMode.NONE
                    && frames.stream().filter(f -> f.tick() >= 12 && f.tick() < 20)
                        .allMatch(f -> f.controller().mandatoryCrawl()
                            && f.controller().fireMode() == SoldierMovementController.FireMode.NONE
                            && "SWIMMING".equals(f.posture()));
                soldier.getMovementController().setMandatoryCrawl(false, "none");
            })
            .assertThat("CRAWL GATE: mandatory crawl holds low posture and fire disabled",
                view -> gateHeld[0] && SimGunHandler.acceptedShots().stream()
                    .noneMatch(s -> s.shooterId().equals(shooterId[0]) && s.tick() >= 12 && s.tick() < 20))
            .assertThat("ROUTE RESUMES: after crawl clears, physical route progress continues",
                view -> movedAfterTick(frames, 20, 4.0))
            .assertThat("POSTURE RELEASE: the soldier returns to a non-swimming pose",
                view -> frames.stream().anyMatch(f -> f.tick() > 25 && !"SWIMMING".equals(f.posture())));
        for (int tick = 8; tick < 20; tick++) {
            builder.action(tick, "latch mandatory crawl after route starts", ctx ->
                ctx.soldier("crawler").getMovementController().setMandatoryCrawl(true, "sim route clearance"));
        }
        for (int tick = 1; tick <= 95; tick++) {
            builder.action(tick, "sample crawl and route resumption", ctx -> sample(ctx, "crawler", frames));
        }
        builder.action(99, "mandatory crawl diagnostic", ctx -> logDiagnostics("mandatory_crawl", frames, shooterId[0]));
        return builder.build();
    }

    public static SimScenario speedAndSpreadContract() {
        List<MovementFrame> frames = new ArrayList<>();
        final java.util.UUID[] shooterId = {null};
        SimScenario.Builder builder = SimScenario.builder("movement_speed_spread_contract").armed().duration(300)
            .description("Live M4 movement attributes resolve once through Tactical pace; accepted hip and ADS shots use distinct native spread envelopes.")
            .field(64, 48)
            .setup(ctx -> {
                frames.clear();
                ctx.spawnAnchor(12, 0, 22);
                SoldierEntity soldier = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 12, 0, 22, SquadMode.HOLD);
                shooterId[0] = soldier.getUUID();
                ctx.harden("rifle");
                // Keep the contact ahead of the whole route so Traverse has a
                // real forward hip-fire window, without steering into the target.
                var target = ctx.spawnEnemyLabeled("target", 60, 0, 22);
                makePassiveDurableTarget(target);
                soldier.setTarget(target);
                soldier.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.35);
                SimGunHandler.useM4A1MovementProfile(soldier);
                SimGunHandler.enableNativeScatterForScenario();
                ctx.issueGoTo(48, 22);
            })
            .assertThat("PROFILE: saturated and low Tactical requests apply 0.40 x 0.85 exactly once",
                view -> resolvesTacticalSpeed(frames))
            .assertThat("NATIVE SPREAD: accepted hip and ADS shots stay inside their live envelopes",
                view -> hasHipAndAdsWithinSpread(shooterId[0]))
            .assertThat("VISIBLE CONTACT: target remains selected and visible during the profile run",
                view -> visibleContactSamples(frames) >= 60)
            .assertThat("FIRE MODE: the soldier's accepted rounds include both hip and ADS",
                view -> hasAcceptedHipAndAds(shooterId[0]))
            .assertThat("CONTROLLER FIRE: accepted shots match the sampled HIP/ADS state",
                view -> shotsMatchFireModes(frames, shooterId[0]))
            .assertThat("PHYSICAL SPEED: Traverse stays at or below 0.28 and Tactical at or below 0.09521 blocks/tick",
                view -> hasPhysicalStepBounds(frames));
        for (int tick = 1; tick <= 290; tick++) {
            builder.action(tick, "sample live movement profile", ctx -> sample(ctx, "rifle", frames));
        }
        builder.action(299, "speed and fire diagnostic", ctx -> logDiagnostics("speed_spread", frames, shooterId[0]));
        return builder.build();
    }

    private static void sample(SimContext ctx, String label, List<MovementFrame> frames) {
        SoldierEntity soldier = ctx.soldier(label);
        if (soldier == null || !soldier.isAlive()) return;
        SoldierMovementController controller = soldier.getMovementController();
        var snapshot = controller.snapshot();
        double attr = soldier.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        double high = snapshot.mode() == SoldierMovementController.Mode.TACTICAL
            ? controller.resolveModeSpeed(1.2, attr) : Double.NaN;
        double low = snapshot.mode() == SoldierMovementController.Mode.TACTICAL
            ? controller.resolveModeSpeed(0.12, attr) : Double.NaN;
        float facingError = -1;
        LivingEntity target = soldier.getTarget();
        if (target != null) {
            double dx = target.getX() - soldier.getX();
            double dz = target.getZ() - soldier.getZ();
            double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double error = Math.abs(((soldier.getYRot() - desiredYaw + 540.0) % 360.0) - 180.0);
            facingError = (float) error;
        }
        frames.add(new MovementFrame(soldier.tickCount, snapshot,
            soldier.getX(), soldier.getZ(), soldier.getYRot(), SimGunHandler.acceptedShots().size(),
            high, low, facingError, soldier.getPose().name(),
            soldier.hasGoToNavigationOwnership(), soldier.hasValidAttackTarget()));
    }

    private static void makePassiveDurableTarget(SoldierEntity target) {
        target.setNoAi(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2048.0);
        target.setHealth(target.getMaxHealth());
    }

    private static boolean hasBoundedCycle(List<MovementFrame> frames) {
        java.util.Map<Integer, int[]> plannedDurations = new java.util.LinkedHashMap<>();
        boolean plannedTraverse = false;
        boolean plannedTactical = false;
        for (MovementFrame frame : frames) {
            var state = frame.controller();
            if (!state.selectedTargetVisible() || state.phaseCounter() <= 0) continue;
            if (state.preferredMode() == SoldierMovementController.Mode.TRAVERSE) plannedTraverse = true;
            if (state.preferredMode() == SoldierMovementController.Mode.TACTICAL) plannedTactical = true;
            if (state.phaseDurationTicks() < 20 || state.phaseDurationTicks() > 68) return false;
            int[] durations = plannedDurations.computeIfAbsent(state.phaseCounter(), ignored -> new int[2]);
            int modeIndex = state.preferredMode() == SoldierMovementController.Mode.TACTICAL ? 0 : 1;
            durations[modeIndex] = state.phaseDurationTicks();
        }
        if (!plannedTraverse || !plannedTactical) return false;
        int completeCycles = 0;
        for (int[] durations : plannedDurations.values()) {
            if (durations[0] == 0 || durations[1] == 0) continue;
            completeCycles++;
            int plannedCycleTicks = durations[0] + durations[1];
            if (plannedCycleTicks < 72 || plannedCycleTicks > 88) return false;
        }
        return completeCycles >= 2;
    }

    private static double averagePreferredTacticalCycleShare(List<MovementFrame> frames) {
        java.util.Map<Integer, int[]> cycles = new java.util.LinkedHashMap<>();
        for (MovementFrame frame : frames) {
            if (!frame.controller().selectedTargetVisible() || frame.controller().phaseCounter() <= 0) continue;
            int[] counts = cycles.computeIfAbsent(frame.controller().phaseCounter(), ignored -> new int[2]);
            counts[1]++;
            if (frame.controller().preferredMode() == SoldierMovementController.Mode.TACTICAL) counts[0]++;
        }
        return cycles.values().stream().filter(c -> c[1] >= 60)
            .mapToDouble(c -> (double) c[0] / c[1]).average().orElse(-1.0);
    }

    private static boolean hasPhysicalModeCoverage(List<MovementFrame> frames) {
        boolean sawTraverse = false;
        boolean sawTactical = false;
        for (MovementFrame frame : frames) {
            if (!frame.controller().selectedTargetVisible()) continue;
            if (frame.controller().mode() == SoldierMovementController.Mode.TRAVERSE) sawTraverse = true;
            if (frame.controller().mode() == SoldierMovementController.Mode.TACTICAL) sawTactical = true;
        }
        return sawTraverse && sawTactical;
    }

    private static boolean hasPhysicalModeAlternation(List<MovementFrame> frames) {
        SoldierMovementController.Mode previous = null;
        int transitions = 0;
        for (MovementFrame frame : frames) {
            if (!frame.controller().selectedTargetVisible()) continue;
            SoldierMovementController.Mode mode = frame.controller().mode();
            if (previous != null && mode != previous) transitions++;
            previous = mode;
        }
        return transitions >= 2;
    }

    private static long visibleContactSamples(List<MovementFrame> frames) {
        return frames.stream().filter(frame -> frame.controller().selectedTargetVisible()).count();
    }

    private static boolean hasPhysicalStepBounds(List<MovementFrame> frames) {
        int tacticalSteps = 0;
        for (int i = 1; i < frames.size(); i++) {
            MovementFrame previous = frames.get(i - 1);
            MovementFrame current = frames.get(i);
            if (current.tick() - previous.tick() != 1) continue;
            double step = Math.hypot(current.x() - previous.x(), current.z() - previous.z());
            if (step > 0.28001) return false;
            boolean tactical = previous.controller().mode() == SoldierMovementController.Mode.TACTICAL
                && current.controller().mode() == SoldierMovementController.Mode.TACTICAL;
            if (!tactical) continue;
            tacticalSteps++;
            if (step > 0.09521) return false;
        }
        return tacticalSteps >= 10;
    }

    private static boolean movedForward(List<MovementFrame> frames, double minimum) {
        if (frames.size() < 2) return false;
        return frames.get(frames.size() - 1).x() - frames.get(0).x() >= minimum;
    }

    private static boolean movedAfterTick(List<MovementFrame> frames, int afterTick, double minimum) {
        MovementFrame first = frames.stream().filter(f -> f.tick() >= afterTick).findFirst().orElse(null);
        MovementFrame last = frames.isEmpty() ? null : frames.get(frames.size() - 1);
        return first != null && last != null
            && Math.hypot(last.x() - first.x(), last.z() - first.z()) >= minimum;
    }

    private static void logDiagnostics(String name, List<MovementFrame> frames, java.util.UUID shooterId) {
        if (frames.isEmpty()) {
            com.stevesarmy.StevesArmyMod.LOGGER.info("[SimMovementContract] {} frames=0", name);
            return;
        }
        MovementFrame first = frames.get(0);
        MovementFrame last = frames.get(frames.size() - 1);
        double minX = frames.stream().mapToDouble(MovementFrame::x).min().orElse(last.x());
        double maxX = frames.stream().mapToDouble(MovementFrame::x).max().orElse(last.x());
        long tactical = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TACTICAL).count();
        long traverse = frames.stream().filter(f -> f.controller().mode() == SoldierMovementController.Mode.TRAVERSE).count();
        int firstMove = frames.stream().filter(f -> Math.hypot(f.x() - first.x(), f.z() - first.z()) >= 0.5)
            .mapToInt(MovementFrame::tick).findFirst().orElse(-1);
        List<Integer> cycleTicks = new ArrayList<>();
        int previousCounter = -1;
        int previousCycleTick = -1;
        for (MovementFrame frame : frames) {
            if (frame.controller().phaseCounter() == previousCounter) continue;
            if (previousCycleTick >= 0) cycleTicks.add(frame.tick() - previousCycleTick);
            previousCounter = frame.controller().phaseCounter();
            previousCycleTick = frame.tick();
        }
        var state = last.controller();
        List<SimGunHandler.ShotRecord> accepted = SimGunHandler.acceptedShots().stream()
            .filter(s -> shooterId != null && s.shooterId().equals(shooterId))
            .toList();
        int firstShot = accepted.stream().mapToInt(SimGunHandler.ShotRecord::tick).min().orElse(-1);
        List<String> shots = accepted.stream()
            .map(s -> s.tick() + ":" + (s.aiming() ? "ADS" : "HIP") + ":"
                + String.format(java.util.Locale.ROOT, "%.2f/%.2f", s.pitchDeviation(), s.yawDeviation()))
            .toList();
        com.stevesarmy.StevesArmyMod.LOGGER.info(
            "[SimMovementContract] {} elapsed={}t samples={} x={}->{} minX={} maxX={} firstMove={} phases={}T/{}V cycles={} phaseCounter={} phase={}/{} mode={} preferred={} fire={} owner(goTo={},attack={}) target={} visible={} distance={} route={} crawl={} posture={} profile={}/{} available={} firstShot={} shots={}",
            name, last.tick() - first.tick(), frames.size(),
            fmt(first.x()), fmt(last.x()), fmt(minX), fmt(maxX), firstMove, tactical, traverse, cycleTicks,
            state.phaseCounter(), state.phaseTicksRemaining(), state.phaseDurationTicks(), state.mode(),
            state.preferredMode(), state.fireMode(), last.goToOwned(), last.attackOwned(),
            state.selectedTargetVisible(),
            fmt(state.nearestVisibleHostile()), fmt(state.routeDistance()), state.mandatoryCrawl(), state.postureReason(),
            fmt(state.adsNeutralAttribute()), fmt(state.adsRatio()), state.movementProfileAvailable(), firstShot, shots);
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3f", value) : "inf";
    }

    private static boolean resolvesTacticalSpeed(List<MovementFrame> frames) {
        double expectedNeutral = 0.331625;
        boolean checked = false;
        for (MovementFrame frame : frames) {
            if (frame.controller().mode() != SoldierMovementController.Mode.TACTICAL) continue;
            double neutral = frame.controller().adsNeutralAttribute();
            double ratio = frame.controller().adsRatio();
            double highExpected = Math.min(1.2 * neutral, 0.28) * 0.40 * ratio;
            double lowExpected = Math.min(0.12 * neutral, 0.28) * 0.40 * ratio;
            if (Math.abs(frame.saturatedTacticalSpeed() - highExpected) > 1e-6
                || Math.abs(frame.lowTacticalSpeed() - lowExpected) > 1e-6
                || Math.abs(neutral - expectedNeutral) > 1e-5
                || Math.abs(ratio - 0.85) > 1e-6) return false;
            checked = true;
        }
        return checked;
    }

    private static boolean hasAcceptedShotFrom(java.util.UUID shooterId) {
        return shooterId != null && SimGunHandler.acceptedShots().stream()
            .anyMatch(s -> s.shooterId().equals(shooterId));
    }

    private static boolean hasAcceptedHipAndAds(java.util.UUID shooterId) {
        List<SimGunHandler.ShotRecord> shots = SimGunHandler.acceptedShots();
        return shooterId != null && shots.stream().filter(s -> s.shooterId().equals(shooterId))
            .anyMatch(s -> !s.aiming()) && shots.stream().filter(s -> s.shooterId().equals(shooterId))
            .anyMatch(SimGunHandler.ShotRecord::aiming);
    }

    private static boolean hasHipAndAdsWithinSpread(java.util.UUID shooterId) {
        boolean hip = false;
        boolean ads = false;
        for (SimGunHandler.ShotRecord shot : SimGunHandler.acceptedShots()) {
            if (!shot.shooterId().equals(shooterId)) continue;
            float nativeLimit = shot.aiming() ? 0.25f : 3.0f;
            float customLimit = shot.aiming() ? 7.5f : 15.0f;
            if (Math.abs(shot.nativePitchDeviation()) > nativeLimit
                || Math.abs(shot.nativeYawDeviation()) > nativeLimit
                || Math.abs(shot.pitchDeviation() - shot.nativePitchDeviation()) > customLimit
                || Math.abs(shot.yawDeviation() - shot.nativeYawDeviation()) > customLimit) return false;
            if (shot.aiming()) ads = true;
            else hip = true;
        }
        return hip && ads;
    }

    private static boolean shotsMatchFireModes(List<MovementFrame> frames, java.util.UUID shooterId) {
        if (shooterId == null) return false;
        boolean sawHip = false;
        boolean sawAds = false;
        for (SimGunHandler.ShotRecord shot : SimGunHandler.acceptedShots()) {
            if (!shot.shooterId().equals(shooterId)) continue;
            // Capture at shot acceptance: scenario sampling runs before entity
            // AI and cannot describe a route that ends during the same tick.
            if (!shot.movingIntent()) continue;
            if (shot.aiming()) {
                if (shot.controllerFireMode() != SoldierMovementController.FireMode.ADS) return false;
                sawAds = true;
            } else {
                if (shot.controllerFireMode() != SoldierMovementController.FireMode.HIP) return false;
                sawHip = true;
            }
        }
        return sawHip && sawAds;
    }
}

package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.PeekController;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Isolates the real peek/controller/physics loop from unrelated cover selection and combat roulette. */
public final class CornerPeekScenarios {
    private CornerPeekScenarios() {}

    public static SimScenario quickMemorySafety() {
        boolean[] passed = new boolean[3];
        return SimScenario.builder("corner_peek_quick_memory_safety").duration(5).armed()
            .description("Hidden updates preserve precise observed aim; unobserved contacts cannot invent a prefire point.")
            .terrain(t -> t.platform())
            .setup(ctx -> {
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "observer", 20, 0, 20, SquadMode.HOLD);
                var enemy = ctx.spawnEnemyLabeled("memory_target", 20, 0, 40);
                s.setNoAi(true);
                enemy.setNoAi(true);
                var awareness = s.getThreatAwareness();
                awareness.onEntityDetected(enemy, s.position(), false);
                passed[0] = awareness.getThreatInfos().stream().filter(info -> info.entity == enemy)
                    .allMatch(info -> info.lastObservedAim == null);
                awareness.onEntityDetected(enemy, s.position(), true);
                Vec3 observed = enemy.getEyePosition();
                enemy.setPos(enemy.position().add(5, 0, 0));
                awareness.onEntityDetected(enemy, s.position(), false);
                passed[1] = awareness.getThreatInfos().stream().filter(info -> info.entity == enemy)
                    .allMatch(info -> observed.equals(info.lastObservedAim));
                awareness.onEntityDetected(enemy, s.position(), true);
                passed[2] = awareness.getThreatInfos().stream().filter(info -> info.entity == enemy)
                    .allMatch(info -> enemy.getEyePosition().equals(info.lastObservedAim));
            })
            .assertThat("unobserved contact has no precise prefire point", v -> passed[0])
            .assertThat("hidden movement cannot move precise remembered aim", v -> passed[1])
            .assertThat("genuine observation refreshes precise remembered aim", v -> passed[2])
            .build();
    }

    public static SimScenario normalFire() { return normalFire(0); }

    public static SimScenario normalFire(int window) {
        int[] ticks = new int[6]; // peek, lane, acquisition, ADS, credit, shot
        java.util.Arrays.fill(ticks, -1);
        boolean[] pose = {false};
        int[] yawEvidence = {0};
        int[] quickTiming = {-1, -1, -1, -1}; // start, first shot, return, hidden
        int[] quickShotCount = {0};
        boolean[] recovery = {true};
        int[] movingQuickShots = {0};
        var b = SimScenario.builder(window == 0 ? "corner_peek_normal_fire" : window == 1 ? "corner_peek_crouched_window" : "corner_peek_standing_window").duration(360).core().armed()
            .description("Normal entity AI ticks hold a full-cover lane and fire repeatedly; logs approach and lane-to-shot latency.")
            // Short completed bursts intentionally exceed the generic careful-peek exposure count.
            .suppressDetectors("PeekThrash")
            .terrain(t -> {
                t.platform(); t.coverBlock(20, 21, 2);
                if (window > 0) {
                    // Narrow the bounded corridor to an opening whose vertical span selects posture.
                    for (int x : new int[]{19, 21}) {
                        var slab = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState();
                        t.block(x, 1, 21, slab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                            window == 1 ? net.minecraft.world.level.block.state.properties.SlabType.TOP
                                : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM));
                        if (window == 2) t.block(x, 0, 21, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
            })
            .setup(ctx -> {
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "peeker", 20, 0, 20, SquadMode.HOLD);
                var enemy = ctx.spawnEnemyLabeled("enemy", 20, 0, 40);
                enemy.setNoAi(true);
                s.setOwnerUUID(java.util.UUID.nameUUIDFromBytes("corner-peek-fixture-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                ctx.disarmSoldier("enemy");
                enemy.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(10000);
                enemy.setHealth(10000);
                s.setTarget(enemy);
                s.getCombatGoal().setTarget(enemy);
                s.getThreatAwareness().onEntityDetected(enemy, s.position());
                var dock = new CoverPoint(ctx.terrain.at(20, 0, 20));
                dock.setType(CoverType.FULL);
                dock.setCoverHeight(2);
                dock.setProtectedDirections(java.util.Set.of(Direction.SOUTH));
                s.getCoverBehaviorManager().setCurrentCover(dock);
                s.getCoverBehaviorManager().setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER);
                s.getPeekController().resetForNewCover(s, dock.getPosition());
                ctx.setDangerSweepEnabled(false);
            });
        for (int tick = 1; tick < 359; tick++) {
            final int at = tick;
            b.action(tick, "normal firing latency observation", ctx -> {
                var s = ctx.soldier("peeker");
                var p = s.getPeekController();
                var target = ctx.soldier("enemy");
                if (p.isQuickPeek() && p.isMovingToPeek() && quickTiming[0] < 0) quickTiming[0] = at;
                int accepted = (int)SimGunHandler.acceptedShots().stream().filter(shotRecord -> shotRecord.shooterId().equals(s.getUUID())).count();
                if (accepted > 0 && quickTiming[1] < 0) quickTiming[1] = at;
                if (p.isQuickPeek() && p.isReturning() && quickTiming[2] < 0) {
                    quickTiming[2] = at;
                    quickShotCount[0] = accepted;
                    movingQuickShots[0] = (int)SimGunHandler.acceptedShots().stream()
                        .filter(shotRecord -> shotRecord.shooterId().equals(s.getUUID()) && shotRecord.movingIntent()
                            && shotRecord.shooterVelocity().horizontalDistanceSqr() > 0.0001).count();
                }
                if (quickTiming[2] >= 0 && p.isHiding() && quickTiming[3] < 0) quickTiming[3] = at;
                if (quickTiming[3] >= 0 && at < quickTiming[3] + 39 && !p.isHiding()) recovery[0] = false;
                if (p.isMovingToPeek() && p.isCornerPreAiming()
                    && Math.abs(net.minecraft.util.Mth.wrapDegrees(s.getYHeadRot() - s.yBodyRot)) > 30) yawEvidence[0]++;
                if ((p.isMovingToPeek() || p.isExposed()) && ticks[0] < 0) ticks[0] = at;
                boolean lane = com.stevesarmy.combat.ExposureCalculator.hasFreshFiringLaneFrom(s.getEyePosition(), target);
                if (lane && ticks[1] < 0) ticks[1] = at;
                var combat = (com.stevesarmy.entity.ai.SoldierCombatGoal)s.getCombatGoal();
                if (combat.isCornerTargetAcquiredForDebug() && ticks[2] < 0) ticks[2] = at;
                if (com.stevesarmy.combat.GunIntegration.getAimProgress(s) >= 0.8f && ticks[3] < 0) ticks[3] = at;
                if (p.getCornerPreAimCredit(target) > 0 && ticks[4] < 0) ticks[4] = at;
                long shot = s.getEngagementTelemetry().getLastShotTick();
                if (shot > 0 && ticks[5] < 0) ticks[5] = at;
                if (p.isExposed() && s.getPose() == (window == 2 ? net.minecraft.world.entity.Pose.STANDING : net.minecraft.world.entity.Pose.CROUCHING)) pose[0] = true;
                if (at % 5 == 0 && ticks[0] >= 0 && (ticks[5] < 0 || at % 40 == 0))
                    StevesArmyMod.LOGGER.info("[CornerFire] t={} peek={} lane={} aq={} ads={} credit={} gate={} shot={} threshold={} acquired={}",
                        at, p.getState(), lane, combat.getAimQualityForDebug(),
                        com.stevesarmy.combat.GunIntegration.getAimProgress(s), p.getCornerPreAimCredit(target),
                        s.getEngagementTelemetry().getGateReason(), shot, combat.getCornerShotThresholdForDebug(), combat.isCornerTargetAcquiredForDebug());
                if (at == 358) StevesArmyMod.LOGGER.info("[QuickPeek] timing={} firstBurstShots={} recovery={} movingShots={}",
                    java.util.Arrays.toString(quickTiming), quickShotCount[0], recovery[0], movingQuickShots[0]);
                if (at == 358) StevesArmyMod.LOGGER.info("[CornerFire] milestones={} approach={} laneToShot={} crouch={}",
                    java.util.Arrays.toString(ticks), ticks[1] - ticks[0], ticks[5] - ticks[1], pose[0]);
            });
        }
        return b.assertThat("normal AI reaches usable lane and fires", v -> ticks[0] >= 0 && ticks[1] >= 0 && ticks[5] >= ticks[1])
            .assertThat("normal pose tick retains selected full-cover posture", v -> pose[0])
            .assertThat("normal movement body and head aim remain independent", v -> yawEvidence[0] >= 3)
            .assertThat("natural exposure permits multiple successful shots", v -> v.shots().size() >= 3)
            .assertThat("fresh precise contact completes a bounded three-shot quick cycle", v -> window != 0
                || (quickTiming[0] >= 0 && quickTiming[1] >= quickTiming[0]
                    && quickTiming[1] - quickTiming[0] <= 20 && quickTiming[2] > quickTiming[1]
                    && quickTiming[3] >= quickTiming[2] && quickTiming[3] - quickTiming[2] <= 20
                    && quickShotCount[0] == 3 && movingQuickShots[0] >= 1 && recovery[0]))
            .build();
    }

    public static SimScenario create(boolean crossfire) { return create(crossfire, false); }

    public static SimScenario create(boolean crossfire, boolean mirrored) { return create(crossfire, mirrored, false, false); }

    public static SimScenario create(boolean crossfire, boolean mirrored, boolean backoff, boolean blockedRear) {
        CoverPoint[] dock = new CoverPoint[1];
        boolean[] evidence = new boolean[8];
        int[] exposure = new int[2];
        double[] metrics = new double[4]; // first-clear displacement, exposed displacement, max, return offset
        int[] preAim = new int[3]; // before-lane ADS, credit ticks, reset
        double[] backMetrics = new double[3];
        var b = SimScenario.builder(backoff ? (blockedRear ? "corner_peek_backoff_blocked_rear" : crossfire ? "corner_peek_backoff_crossfire" : "corner_peek_backoff_pre_ads") : crossfire ? (mirrored ? "corner_peek_known_crossfire_mirror" : "corner_peek_known_crossfire") : "corner_peek_first_lane")
            .duration(180).core().armed()
            .description("Real full-cover peek controller and entity travel: first stable fractional opening, honest lane, bounded return"
                + (crossfire ? "; prefer west opening shielded from a known eastern threat" : ""))
            .terrain(t -> {
                t.platform();
                t.coverBlock(20, 21, 2);
                if (crossfire && !backoff) t.coverBlock(mirrored ? 18 : 22, 23, 2);
                if (backoff) {
                    // An oblique threat opens a view by stepping away from the face.
                    if (!crossfire) t.coverBlock(21, 21, 2);
                    else t.coverBlock(22, 23, 2);
                    if (blockedRear) t.coverBlock(20, 19, 2);
                }
            })
            .setup(ctx -> {
                java.util.Arrays.fill(evidence, false);
                java.util.Arrays.fill(metrics, 0);
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "peeker", 20, 0, 20, SquadMode.HOLD);
                s.setNoAi(true);
                s.setNoGravity(true);
                var enemy = ctx.spawnEnemy(backoff ? 35 : 20, 0, 40);
                enemy.setNoAi(true);
                s.setTarget(enemy);
                if (s.getCombatGoal() instanceof com.stevesarmy.entity.ai.SoldierCombatGoal combat) combat.setTarget(enemy);
                s.getThreatAwareness().onEntityDetected(enemy, s.position());
                if (crossfire) s.getThreatAwareness().onEnemyPing(ctx.terrain.at(mirrored ? 5 : 35, 0, 40));
                dock[0] = new CoverPoint(ctx.terrain.at(20, 0, 20));
                dock[0].setType(CoverType.FULL);
                dock[0].setCoverHeight(2);
                dock[0].setProtectedDirections(java.util.Set.of(Direction.SOUTH));
                s.getCoverBehaviorManager().setCurrentCover(dock[0]);
                s.getPeekController().resetForNewCover(s, dock[0].getPosition());
                ctx.setDangerSweepEnabled(false);
            });
        for (int tick = 2; tick < 175; tick++) {
            final int at = tick;
            b.action(tick, "per-tick physical peek oracle", ctx -> {
                var s = ctx.soldier("peeker");
                var peek = s.getPeekController();
                var mover = (CoverPositionController) s.getMoveControl();
                Vec3 center = Vec3.atBottomCenterOf(dock[0].getPosition());
                double distance = s.position().subtract(center).horizontalDistance();
                metrics[2] = Math.max(metrics[2], distance);
                // Independent vanilla block clip, no cover skipping or perception cache.
                boolean clear = ctx.level.clip(new net.minecraft.world.level.ClipContext(s.getEyePosition(),
                    s.getTarget().getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, s)).getType()
                    == net.minecraft.world.phys.HitResult.Type.MISS;
                if (clear && !evidence[0]) { evidence[0] = true; metrics[0] = distance; }
                if (!clear && peek.isMovingToPeek() && com.stevesarmy.combat.GunIntegration.getAimProgress(s) >= 0.8f) preAim[0]++;
                backMetrics[0] = Math.max(backMetrics[0], center.z - s.getZ());
                if (peek.isExposed() && !evidence[1]) {
                    preAim[1] = peek.getAlignedPreAimTicks();
                    backMetrics[1] = peek.getCornerPreAimCredit(s.getTarget());
                    evidence[7] = peek.getCornerPreAimCredit(ctx.enemies.stream().filter(e -> e != s.getTarget()).findFirst().orElse(null)) == 0;
                    if (s.getCombatGoal() instanceof com.stevesarmy.entity.ai.SoldierCombatGoal combat) {
                        float before = combat.getAimQualityForDebug();
                        float yaw = s.getYHeadRot();
                        s.setYHeadRot(yaw + 90);
                        combat.applyCornerPreAimQuality();
                        evidence[6] = combat.getAimQualityForDebug() == before;
                        s.setYHeadRot(yaw);
                        combat.applyCornerPreAimQuality();
                        backMetrics[2] = combat.getAimQualityForDebug() - before;
                    }
                    evidence[1] = true;
                    metrics[1] = distance;
                    evidence[2] = clear;
                    evidence[3] = mirrored ? s.getX() > center.x : s.getX() < center.x;
                    if (crossfire) {
                        Vec3 threatEye = Vec3.atBottomCenterOf(ctx.terrain.at(mirrored ? 5 : 35, 0, 40)).add(0, 1.62, 0);
                        exposure[0] = CoverFidelityProbe.probe(ctx.level, s, threatEye).exposed();
                        // Counterfactual uses the same independent oracle and live dimensions;
                        // restore immediately, without ticking controllers at the opposite opening.
                        Vec3 actual = s.position();
                        s.setPos(center.x - (actual.x - center.x), actual.y, actual.z);
                        exposure[1] = CoverFidelityProbe.probe(ctx.level, s, threatEye).exposed();
                        s.setPos(actual.x, actual.y, actual.z);
                        evidence[5] = exposure[0] < exposure[1];
                    }
                    StevesArmyMod.LOGGER.info("[CornerPeek] crossfire={} firstClear={} exposed={} x={} honest={} threatRays={}/{}",
                        crossfire, metrics[0], distance, s.getX() - center.x, clear, exposure[0], exposure[1]);
                    peek.forceReturnToCover(s, dock[0], mover);
                    evidence[7] &= peek.getCornerPreAimCredit(s.getTarget()) == 0;
                }
                if (evidence[1] && peek.isIdleInCover()) {
                    metrics[3] = distance;
                    evidence[4] = distance <= 0.08;
                    if (!peek.isCornerPreAiming() && peek.getAlignedPreAimTicks() == 0) preAim[2] = 1;
                }
                s.setNoAi(false);
                try {
                    peek.tick(s, dock[0], mover, !evidence[1]);
                    mover.tick();
                    s.travel(new Vec3(s.xxa, 0, s.zza));
                } finally { s.setNoAi(true); }
                if (at == 10) StevesArmyMod.LOGGER.info("[CornerPeek] startup state={} result={} target={} speed={} pos={}",
                    peek.getState(), mover.getLastResult(), mover.getDebugTargetPos(), s.getSpeed(), s.position());
                if (at == 174) StevesArmyMod.LOGGER.info("[CornerPeek] crossfire={} evidence={} metrics={} back={} preAim={}",
                    crossfire, java.util.Arrays.toString(evidence), java.util.Arrays.toString(metrics), java.util.Arrays.toString(backMetrics), java.util.Arrays.toString(preAim));
            });
        }
        return b.assertThat("physical opening reached and EXPOSED has an honest live lane", v -> blockedRear ? !evidence[1] && metrics[2] < 0.08 : evidence[0] && evidence[1] && evidence[2])
            .assertThat("stop at fractional first opening without full-block overshoot", v -> backoff ? metrics[2] < 1.5 : metrics[1] < 0.85 && metrics[1] <= metrics[0] + 0.16 && metrics[2] < 0.90)
            .assertThat("return physically docks within 0.08", v -> blockedRear ? metrics[2] < 0.08 : evidence[4])
            .assertThat("known crossfire selects shielded side with independently fewer body rays", v -> !crossfire || backoff || (evidence[3] && evidence[5]))
            .assertThat("backoff geometry uses bounded rear travel when requested", v -> !backoff || (blockedRear || crossfire ? backMetrics[0] < 0.15 : backMetrics[0] >= 0.05))
            .assertThat("full-cover pre-ADS is established before lane confirmation", v -> !backoff || blockedRear || preAim[0] > 0)
            .assertThat("return clears full-cover prealignment credit", v -> !evidence[1] || preAim[2] == 1)
            .assertThat("missing or changed target and return receive no credit", v -> !evidence[1] || evidence[7])
            .assertThat("turned-away gun receives no corner quality credit", v -> !evidence[1] || evidence[6])
            .assertThat("aligned corner contact earns bounded initial quality above a fresh aim", v -> !backoff || blockedRear || (backMetrics[2] > 0 && backMetrics[2] <= 0.30))
            .build();
    }
}

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

    public static SimScenario create(boolean crossfire) { return create(crossfire, false); }

    public static SimScenario create(boolean crossfire, boolean mirrored) {
        CoverPoint[] dock = new CoverPoint[1];
        boolean[] evidence = new boolean[6];
        int[] exposure = new int[2];
        double[] metrics = new double[4]; // first-clear displacement, exposed displacement, max, return offset
        var b = SimScenario.builder(crossfire ? (mirrored ? "corner_peek_known_crossfire_mirror" : "corner_peek_known_crossfire") : "corner_peek_first_lane")
            .duration(180).core()
            .description("Real full-cover peek controller and entity travel: first stable fractional opening, honest lane, bounded return"
                + (crossfire ? "; prefer west opening shielded from a known eastern threat" : ""))
            .terrain(t -> {
                t.platform();
                t.coverBlock(20, 21, 2);
                if (crossfire) t.coverBlock(mirrored ? 18 : 22, 23, 2);
            })
            .setup(ctx -> {
                java.util.Arrays.fill(evidence, false);
                java.util.Arrays.fill(metrics, 0);
                var s = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "peeker", 20, 0, 20, SquadMode.HOLD);
                s.setNoAi(true);
                s.setNoGravity(true);
                var enemy = ctx.spawnEnemy(20, 0, 40);
                enemy.setNoAi(true);
                s.setTarget(enemy);
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
                if (peek.isExposed() && !evidence[1]) {
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
                }
                if (evidence[1] && peek.isIdleInCover()) {
                    metrics[3] = distance;
                    evidence[4] = distance <= 0.08;
                }
                s.setNoAi(false);
                try {
                    peek.tick(s, dock[0], mover, !evidence[1]);
                    mover.tick();
                    s.travel(new Vec3(s.xxa, 0, s.zza));
                } finally { s.setNoAi(true); }
                if (at == 10) StevesArmyMod.LOGGER.info("[CornerPeek] startup state={} result={} target={} speed={} pos={}",
                    peek.getState(), mover.getLastResult(), mover.getDebugTargetPos(), s.getSpeed(), s.position());
                if (at == 174) StevesArmyMod.LOGGER.info("[CornerPeek] crossfire={} evidence={} metrics={}",
                    crossfire, java.util.Arrays.toString(evidence), java.util.Arrays.toString(metrics));
            });
        }
        return b.assertThat("physical opening reached and EXPOSED has an honest live lane", v -> evidence[0] && evidence[1] && evidence[2])
            .assertThat("stop at fractional first opening without full-block overshoot", v -> metrics[1] < 0.85 && metrics[1] <= metrics[0] + 0.16 && metrics[2] < 0.90)
            .assertThat("return physically docks within 0.08", v -> evidence[4])
            .assertThat("known crossfire selects shielded side with independently fewer body rays", v -> !crossfire || (evidence[3] && evidence[5]))
            .build();
    }
}

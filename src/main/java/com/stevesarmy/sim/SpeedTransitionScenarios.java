package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.SoldierGroundNavigation;
import com.stevesarmy.entity.ai.SoldierSpeedController;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Transition probes use real controllers with goal AI disabled to isolate competing speed owners. */
public final class SpeedTransitionScenarios {
    private SpeedTransitionScenarios() {}

    public static SimScenario transitions() {
        boolean[] ok = new boolean[28];
        var scenario = SimScenario.builder("movement_speed_transitions").core().duration(200)
            .description("ADS-release during crawl must not amplify a stored modifier; docking "
                + "must not add a diagonal burst; ordinary acceleration is bounded; external impulses survive.")
            .terrain(t -> t.platform())
            .setup(ctx -> {
                java.util.Arrays.fill(ok, false);
                ctx.spawnAnchor(5, 0, 5);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 20, 0, 20, SquadMode.HOLD).setNoAi(true);
                ctx.setDangerSweepEnabled(false);
            })
            .action(40, "recorded Stewart request cannot exceed normal player sprint", ctx -> {
                var s = ctx.soldier("probe");
                var attribute = s.getAttribute(Attributes.MOVEMENT_SPEED);
                double base = attribute.getBaseValue();
                s.setNoAi(false);
                try {
                    attribute.setBaseValue(0.331625);
                    s.setDeltaMovement(Vec3.ZERO);
                    var ctrl = (CoverPositionController) s.getMoveControl();
                    for (int n = 0; n < 12; n++) {
                        ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                        ctrl.tick();
                    }
                    Vec3 start = s.position();
                    s.setOnGround(true);
                    s.travel(new Vec3(0, 0, 1));
                    double moved = s.position().subtract(start).horizontalDistance();
                    com.stevesarmy.StevesArmyMod.LOGGER.info("[SprintCeilingProbe] recorded request intent={} physical={}",
                        ctrl.getSpeedController().appliedSpeed(), moved);
                    ok[13] = moved > 0.1 && moved <= 0.28001
                        && ctrl.getSpeedController().appliedSpeed() <= 0.28001;
                } finally {
                    attribute.setBaseValue(base);
                    s.setNoAi(true);
                    s.setDeltaMovement(Vec3.ZERO);
                }
            })
            .action(50, "crawl under weapon tax, then release ADS before the next movement tick", ctx -> {
                SoldierEntity s = ctx.soldier("probe");
                var speed = s.getAttribute(Attributes.MOVEMENT_SPEED);
                var tax = new AttributeModifier(java.util.UUID.randomUUID(), "transition probe tax",
                    -0.75, AttributeModifier.Operation.MULTIPLY_TOTAL);
                speed.addTransientModifier(tax);
                var nav = (SoldierGroundNavigation) s.getNavigation();
                nav.setSpeedModifier(1.2);
                try {
                    var crawl = s.getTravelCrawlController();
                    var active = crawl.getClass().getDeclaredField("crawlActive");
                    active.setAccessible(true);
                    active.setBoolean(crawl, true);
                    var timer = crawl.getClass().getDeclaredField("checkTimer");
                    timer.setAccessible(true);
                    timer.setInt(crawl, 1000);
                    crawl.tick();
                    double retained = nav.getNavSpeedModifier();
                    speed.removeModifier(tax);
                    var ctrl = (CoverPositionController) s.getMoveControl();
                    for (int n = 0; n < 12; n++) {
                        ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), retained);
                        ctrl.tick();
                    }
                    ok[0] = s.getSpeed() <= 0.65 * speed.getBaseValue() + 0.00001
                        && s.getSpeed() > 0.1;
                    ok[1] = Math.abs(retained - 1.2) < 0.00001;
                    active.setBoolean(crawl, false);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("crawl transition probe", e);
                } finally {
                    speed.removeModifier(tax);
                }
            })
            .action(60, "tangential motion plus docking correction cannot create a speed burst", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                s.setDeltaMovement(0, 0.07, 0.30);
                ctrl.maintainCoverAnchor(s.position().add(1, 0, 0));
                ctrl.tick();
                ok[2] = s.getDeltaMovement().horizontalDistance() <= 0.30001
                    && Math.abs(s.getDeltaMovement().y - 0.07) < 0.00001;
            })
            .action(70, "unanchored external impulse is not globally clamped", ctx -> {
                var s = ctx.soldier("probe");
                Vec3 impulse = new Vec3(1.2, 0.4, -0.8);
                s.setDeltaMovement(impulse);
                s.getMoveControl().tick();
                ok[3] = s.getDeltaMovement().distanceToSqr(impulse) < 0.00000001;
            })
            .action(80, "ordinary request increases smoothly, then reaches capped steady pace", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = s.getMoveControl();
                ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 0.1);
                ctrl.tick();
                double before = s.getSpeed();
                ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                ctrl.tick();
                ok[4] = s.getSpeed() - before <= 0.06001;
                for (int n = 0; n < 10; n++) {
                    ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                    ctrl.tick();
                }
                ok[5] = Math.abs(s.getSpeed() - Math.min(SoldierSpeedController.MAX_LOCOMOTION_SPEED,
                    1.2 * s.getAttributeValue(Attributes.MOVEMENT_SPEED))) < 0.00001;
            })
            .action(85, "idle movement stops speed intent immediately", ctx -> {
                var s = ctx.soldier("probe");
                s.getMoveControl().tick();
                ok[6] = s.getSpeed() == 0;
            })
            .action(90, "a pinned escape toward protection retains an immediate legal dash", ctx -> {
                var s = ctx.soldier("probe");
                s.getCoverBehaviorManager().setTargetCover(new com.stevesarmy.combat.cover.CoverPoint(
                    ctx.terrain.at(25, 0, 20)));
                ctx.injectSuppression("probe", 40);
                s.getMoveControl().setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                s.getMoveControl().tick();
                ok[7] = s.getCoverBehaviorManager().getSuppressionTracker().isPinned()
                    && Math.abs(s.getSpeed() - SoldierSpeedController.MAX_LOCOMOTION_SPEED) < 0.00001;
                s.getCoverBehaviorManager().setTargetCover(null);
                s.getCoverBehaviorManager().getSuppressionTracker().reset();
            })
            .action(95, "firing-prone owner supersedes an old travel-crawl state", ctx -> {
                var s = ctx.soldier("probe");
                try {
                    var crawl = s.getTravelCrawlController();
                    var active = crawl.getClass().getDeclaredField("crawlActive");
                    active.setAccessible(true);
                    active.setBoolean(crawl, true);
                    s.setFiringProne(true);
                    for (int n = 0; n < 10; n++) {
                        s.getMoveControl().setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                        s.getMoveControl().tick();
                    }
                    ok[8] = Math.abs(s.getSpeed() - SoldierSpeedController.MAX_LOCOMOTION_SPEED) < 0.00001;
                    active.setBoolean(crawl, false);
                    s.setFiringProne(false);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("firing-prone priority probe", e);
                }
            })
            .action(96, "walking acceleration cannot masquerade as an external impulse", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                s.setNoAi(false);
                try {
                    Vec3 start = s.position();
                    s.setDeltaMovement(0.40, 0, 0);
                    for (int n = 0; n < 3; n++) {
                        ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                        ctrl.tick();
                        s.setOnGround(true);
                        s.travel(new Vec3(0, 0, 1));
                    }
                    double moved = s.position().subtract(start).horizontalDistance();
                    ok[9] = moved > 0.1 && moved <= ctrl.getSpeedController().appliedSpeed() + 0.00001;
                } finally {
                    s.setNoAi(true);
                }
            })
            .action(97, "real travel preserves a pre-existing external impulse", ctx -> {
                var s = ctx.soldier("probe");
                s.setNoAi(false);
                try {
                    s.getMoveControl().setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                    s.getMoveControl().tick();
                    Vec3 start = s.position();
                    s.setDeltaMovement(1.2, 0.4, 0);
                    s.travel(Vec3.ZERO);
                    ok[10] = s.position().subtract(start).horizontalDistance() > 1.0
                        && s.getY() > start.y;
                } finally {
                    s.setNoAi(true);
                }
            })
            .action(98, "movement debug packets preserve telemetry and explicit disable across the wire", ctx -> {
                var s = ctx.soldier("probe");
                var entry = new com.stevesarmy.network.MovementSpeedDebugPacket.Entry(s.getId(), s.getUUID(),
                    "Speed probe", s.position(), new Vec3(0.2, 0.4, -0.1),
                    0.224, 0.123, 0.456, 1.2, 0.398, 0.338, 0.332,
                    0.25, 0.338, true, "crawl", "controller: cover handoff",
                    "SEEKING_COVER", "CROUCHING", true, false);
                var packet = new com.stevesarmy.network.MovementSpeedDebugPacket(true, java.util.List.of(entry));
                var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    com.stevesarmy.network.MovementSpeedDebugPacket.encode(packet, buf);
                    ok[11] = packet.equals(new com.stevesarmy.network.MovementSpeedDebugPacket(buf))
                        && !buf.isReadable();
                    buf.clear();
                    var disabled = new com.stevesarmy.network.MovementSpeedDebugPacket(false, java.util.List.of());
                    com.stevesarmy.network.MovementSpeedDebugPacket.encode(disabled, buf);
                    ok[12] = disabled.equals(new com.stevesarmy.network.MovementSpeedDebugPacket(buf))
                        && !buf.isReadable();
                } finally {
                    buf.release();
                }
            })
            .action(130, "slowing ordinary momentum is not an external impulse", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                s.setDeltaMovement(new Vec3(0.4, 0, 0));
                ctrl.getSpeedController().resolve(0.1, true, false);
                s.setSpeed((float) ctrl.getSpeedController().appliedSpeed());
                double moved = travel(s, Vec3.ZERO);
                ok[15] = moved <= ctrl.getSpeedController().appliedSpeed() + 0.00001
                    && !ctrl.getSpeedController().impulseBypass();
            })
            .action(135, "zero intent still bounds diagonal coasting and anchor movement", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                ctrl.clear();
                s.setDeltaMovement(new Vec3(0.28, 0.04, 0.28));
                ctrl.maintainCoverAnchor(s.position().add(1, 0, 0));
                ctrl.tick();
                double moved = travel(s, Vec3.ZERO);
                ok[16] = moved > 0.1 && moved <= 0.28001
                    && !ctrl.getSpeedController().impulseBypass();
            })
            .action(140, "sprint jumping cannot exceed the horizontal ceiling", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                for (int n = 0; n < 6; n++) ctrl.getSpeedController().resolve(1.2, true, false);
                s.setSpeed((float) ctrl.getSpeedController().appliedSpeed());
                s.setDeltaMovement(new Vec3(0.28, 0.42, 0));
                s.setOnGround(false);
                double y = s.getY();
                double moved = travel(s, new Vec3(0, 0, 1));
                com.stevesarmy.StevesArmyMod.LOGGER.info("[SprintCeilingProbe] jump moved={} dy={} intent={} impulse={}",
                    moved, s.getY() - y, ctrl.getSpeedController().appliedSpeed(), ctrl.getSpeedController().impulseBypass());
                ok[17] = moved > 0.1 && moved <= 0.28001 && s.getY() > y;
            })
            .action(145, "explicit weak knockback is preserved below the heuristic threshold", ctx -> {
                var s = ctx.soldier("probe");
                s.setDeltaMovement(Vec3.ZERO);
                s.setOnGround(true);
                s.knockback(0.4, 1, 0);
                s.setSpeed(0);
                double y = s.getY();
                double moved = travel(s, Vec3.ZERO);
                var speed = ((CoverPositionController) s.getMoveControl()).getSpeedController();
                ok[18] = moved > 0.35 && s.getY() > y && speed.impulseBypass();
            })
            .action(150, "transport movement stays outside the self-locomotion budget", ctx -> {
                var s = ctx.soldier("probe");
                Vec3 start = s.position();
                s.move(net.minecraft.world.entity.MoverType.SHULKER, new Vec3(0.6, 0, 0));
                ok[19] = s.position().subtract(start).horizontalDistance() > 0.55;
            })
            .action(155, "manual CQB and doorway policies remain slower than the ceiling", ctx -> {
                var s = ctx.soldier("probe");
                var speed = ((CoverPositionController) s.getMoveControl()).getSpeedController();
                s.setCQB(true);
                double manual = speed.resolve(1.2, true, false);
                s.setCQB(false);
                double doorway = speed.resolve(1.2, true, true);
                ok[20] = manual > 0 && manual <= 0.24 * s.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue()
                    && doorway > 0 && doorway <= 0.03 * s.getAttributeValue(Attributes.MOVEMENT_SPEED) + 0.00001;
            })
            .action(160, "automatic enemy-near-path caution respects the same ceiling", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                ctrl.clear();
                s.setDeltaMovement(Vec3.ZERO);
                var here = s.blockPosition().subtract(ctx.terrain.origin);
                ctx.injectThreat(here.getX() + 5, here.getZ() + 3);
                s.getNavigation().moveTo(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                s.getNavigation().tick();
                ctrl.tick();
                double moved = travel(s, new Vec3(0, 0, 1));
                ok[21] = "enemy near path".equals(ctrl.cautionReason())
                    && ctrl.getSpeedController().targetSpeed() <= 0.28001 && moved <= 0.28001;
            })
            .action(190, "knockback after spending a locomotion budget remains exempt", ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                ctrl.clear();
                s.setDeltaMovement(Vec3.ZERO);
                for (int n = 0; n < 6; n++) ctrl.getSpeedController().resolve(1.2, true, false);
                s.setSpeed((float) ctrl.getSpeedController().appliedSpeed());
                travel(s, new Vec3(0, 0, 1));
                s.setDeltaMovement(Vec3.ZERO);
                s.setOnGround(true);
                s.knockback(0.4, 1, 0);
                s.setSpeed(0);
                ok[23] = travel(s, Vec3.ZERO) > 0.35 && ctrl.getSpeedController().impulseBypass();
            })
            .assertThat("recorded request obeys physical player sprint ceiling", v -> ok[13])
            .assertThat("boosted attributes and cover handoffs cannot manufacture external momentum", v -> ok[14])
            .assertThat("slowing ordinary momentum stays within its budget", v -> ok[15])
            .assertThat("zero-intent diagonal coasting and anchor correction stay below sprint", v -> ok[16])
            .assertThat("jumping keeps vertical physics but obeys horizontal sprint ceiling", v -> ok[17])
            .assertThat("weak explicit knockback remains exempt", v -> ok[18])
            .assertThat("non-self transport remains exempt", v -> ok[19])
            .assertThat("manual CQB and doorway limits remain slower", v -> ok[20])
            .assertThat("automatic caution obeys sprint ceiling", v -> ok[21])
            .assertThat("movement input cannot indefinitely sustain external impulse exemption", v -> ok[22])
            .assertThat("knockback after ordinary movement remains exempt", v -> ok[23])
            .assertThat("vanilla WAIT handoff clears stale tactical lateral input", v -> ok[24])
            .assertThat("vanilla MOVE_TO handoff clears stale tactical lateral input", v -> ok[25])
            .assertThat("MOVE_TO physical displacement follows its requested route", v -> ok[26])
            .assertThat("standing vanilla Traverse reaches physical sprint pace", v -> ok[27])
            .assertThat("movement debug telemetry survives packet round trip without field misalignment", v -> ok[11])
            .assertThat("movement debug disable clears with an empty packet", v -> ok[12])
            .assertThat("walking acceleration and repeated controller ticks share one physical movement budget", v -> ok[9])
            .assertThat("physical external impulse survives travel and movement limiting", v -> ok[10])
            .assertThat("crawl pace survives ADS release", v -> ok[0])
            .assertThat("crawl never overwrites the goal's requested modifier", v -> ok[1])
            .assertThat("docking does not amplify tangential momentum", v -> ok[2])
            .assertThat("external horizontal and vertical impulse is preserved", v -> ok[3])
            .assertThat("ordinary speed increase is bounded", v -> ok[4])
            .assertThat("steady normal pace is capped at player sprint", v -> ok[5])
            .assertThat("idle movement intent stops immediately", v -> ok[6])
            .assertThat("pinned escape keeps an immediate dash", v -> ok[7])
            .assertThat("firing-prone state keeps priority over old travel crawl", v -> ok[8]);
        for (int tick = 110; tick < 125; tick++) {
            final int sample = tick;
            scenario.action(tick, "boosted physical travel and cover handoff " + tick, ctx -> {
                var s = ctx.soldier("probe");
                var attribute = s.getAttribute(Attributes.MOVEMENT_SPEED);
                double base = attribute.getBaseValue();
                var ctrl = (CoverPositionController) s.getMoveControl();
                try {
                    attribute.setBaseValue(4.0);
                    if (sample == 110) {
                        s.setDeltaMovement(Vec3.ZERO);
                        ok[14] = true;
                    }
                    ctrl.moveTo(s.position().add(5, 0, 5), 0.05, 1.2);
                    for (int n = 0; n < 8; n++) ctrl.tick();
                    double moved = travel(s, new Vec3(0, 0, 1));
                    ok[14] &= moved > 0.01 && moved <= 0.28001
                        && s.getDeltaMovement().horizontalDistance() <= 0.28001
                        && !ctrl.getSpeedController().impulseBypass()
                        && ctrl.getSpeedController().limitingReason().startsWith("sprint-ceiling");
                } finally {
                    attribute.setBaseValue(base);
                }
            });
        }
        for (int tick = 165; tick <= 184; tick++) {
            final int sample = tick;
            scenario.action(tick, "knockback settles despite sustained movement input " + tick, ctx -> {
                var s = ctx.soldier("probe");
                var ctrl = (CoverPositionController) s.getMoveControl();
                if (sample == 165) {
                    ctrl.clear();
                    s.setDeltaMovement(Vec3.ZERO);
                    s.setOnGround(true);
                    s.knockback(1.2, 1, 0);
                    ok[22] = true;
                }
                for (int n = 0; n < 6; n++) ctrl.getSpeedController().resolve(1.2, true, false);
                s.setSpeed((float) ctrl.getSpeedController().appliedSpeed());
                s.setOnGround(true);
                double incoming = s.getDeltaMovement().horizontalDistance();
                double moved = travel(s, new Vec3(0, 0, 1));
                ok[22] &= moved <= Math.max(0.28, incoming) + 0.00001;
                if (sample == 165) ok[22] &= moved > 0.28;
                if (sample == 184) ok[22] &= moved <= 0.28001 && !ctrl.getSpeedController().impulseBypass();
            });
        }
        scenario.action(100, "stale tactical strafe input is cleared on vanilla wait and route handoff", ctx -> {
            SoldierEntity s = ctx.soldier("probe");
            CoverPositionController ctrl = (CoverPositionController) s.getMoveControl();
            s.moveTo(20.5, s.getY(), 20.5, -90.0F, s.getXRot());
            s.setDeltaMovement(Vec3.ZERO);
            s.setOnGround(true);
            s.setYRot(-90.0F);

            ctrl.clear();
            s.setXxa(0.8F);
            ctrl.tick();
            ok[24] = s.xxa == 0.0F;

            double startX = s.getX();
            double startZ = s.getZ();
            ctrl.setWantedPosition(startX + 8.0, s.getY(), startZ, 1.0);
            s.setXxa(0.8F);
            ctrl.tick();
            ok[25] = s.xxa == 0.0F;
            travel(s, new Vec3(s.xxa, s.yya, s.zza));
            double dx = s.getX() - startX;
            double dz = s.getZ() - startZ;
            ok[26] = dx > 0.0001 && Math.abs(dz) <= dx * 0.20;
            com.stevesarmy.StevesArmyMod.LOGGER.info(
                "[MovementHandoff] waitCleared={} moveCleared={} dx={} dz={}",
                ok[24], ok[25], dx, dz);
        });
        scenario.action(105, "measure steady vanilla Traverse physical pace", ctx -> {
            var s = ctx.soldier("probe");
            var ctrl = (CoverPositionController) s.getMoveControl();
            ctrl.clear();
            s.setDeltaMovement(Vec3.ZERO);
            s.setYRot(-90);
            double last = 0;
            s.setNoAi(false);
            try {
                for (int n = 0; n < 20; n++) {
                    s.tickCount++;
                    ctrl.getSpeedController().beginTick();
                    s.setOnGround(true);
                    ctrl.setWantedPosition(s.getX() + 8, s.getY(), s.getZ(), 1.2);
                    ctrl.tick();
                    Vec3 start = s.position();
                    s.travel(new Vec3(s.xxa, s.yya, s.zza));
                    last = s.position().subtract(start).horizontalDistance();
                }
                ok[27] = last >= 0.275 && last <= 0.28001;
                com.stevesarmy.StevesArmyMod.LOGGER.info("[TraversePhysicalPace] actual={} intent={} forward={}",
                    last, ctrl.getSpeedController().appliedSpeed(), s.zza);
            } finally {
                s.setNoAi(true);
                ctrl.clear();
                s.setDeltaMovement(Vec3.ZERO);
            }
        });
        return scenario.build();
    }

    private static double travel(SoldierEntity soldier, Vec3 input) {
        Vec3 start = soldier.position();
        soldier.setNoAi(false);
        try {
            soldier.travel(input);
            return soldier.position().subtract(start).horizontalDistance();
        } finally {
            soldier.setNoAi(true);
        }
    }
}

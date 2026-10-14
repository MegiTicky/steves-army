package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.SoldierGroundNavigation;
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
        boolean[] ok = new boolean[9];
        return SimScenario.builder("movement_speed_transitions").core().duration(100)
            .description("ADS-release during crawl must not amplify a stored modifier; docking "
                + "must not add a diagonal burst; ordinary acceleration is bounded; external impulses survive.")
            .terrain(t -> t.platform())
            .setup(ctx -> {
                java.util.Arrays.fill(ok, false);
                ctx.spawnAnchor(5, 0, 5);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 20, 0, 20, SquadMode.HOLD).setNoAi(true);
                ctx.setDangerSweepEnabled(false);
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
            .action(80, "ordinary request increases smoothly, then reaches unchanged steady pace", ctx -> {
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
                ok[5] = Math.abs(s.getSpeed() - 1.2 * s.getAttributeValue(Attributes.MOVEMENT_SPEED)) < 0.00001;
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
                    && Math.abs(s.getSpeed() - 1.2 * s.getAttributeValue(Attributes.MOVEMENT_SPEED)) < 0.00001;
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
                    ok[8] = Math.abs(s.getSpeed() - 1.2 * s.getAttributeValue(Attributes.MOVEMENT_SPEED)) < 0.00001;
                    active.setBoolean(crawl, false);
                    s.setFiringProne(false);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("firing-prone priority probe", e);
                }
            })
            .assertThat("crawl pace survives ADS release", v -> ok[0])
            .assertThat("crawl never overwrites the goal's requested modifier", v -> ok[1])
            .assertThat("docking does not amplify tangential momentum", v -> ok[2])
            .assertThat("external horizontal and vertical impulse is preserved", v -> ok[3])
            .assertThat("ordinary speed increase is bounded", v -> ok[4])
            .assertThat("steady normal pace is unchanged", v -> ok[5])
            .assertThat("idle movement intent stops immediately", v -> ok[6])
            .assertThat("pinned escape keeps an immediate dash", v -> ok[7])
            .assertThat("firing-prone state keeps priority over old travel crawl", v -> ok[8])
            .build();
    }
}

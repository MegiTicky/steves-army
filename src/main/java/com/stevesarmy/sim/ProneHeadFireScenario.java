package com.stevesarmy.sim;

import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.Pose;

/** A low firing origin can see a head above cover while the preferred torso lane clips it. */
public final class ProneHeadFireScenario {
    private ProneHeadFireScenario() {}

    public static SimScenario create() {
        boolean[] geometryConfirmed = {false};
        boolean[] selectedClearHead = {false};
        boolean[] pinnedAndProne = {false};
        return SimScenario.builder("prone_head_over_cover_fire")
            .description("A pinned prone rifleman fires at a visible head when the torso ray clips low cover.")
            .armed()
            .duration(120)
            .field(64, 64)
            .terrain(t -> {
                t.floorOnly();
                t.coverRow(8, 7, 10, 10, 1);
            })
            .setup(ctx -> {
                var shooter = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "shooter", 50, 0, 50, SquadMode.HOLD);
                var target = ctx.spawnEnemyLabeled("target", 7, 0, 7);
                shooter.setInvulnerable(true);
                target.setInvulnerable(true);
                target.setNoAi(true);
                target.setPose(Pose.CROUCHING);
                shooter.setLowCrouching(true);
                shooter.getCombatGoal().setTarget(target);
            })
            .suppress("shooter", 2, 100, 10)
            .action(5, "probe the prone head and torso rays", ctx -> {
                var shooter = ctx.byLabel.get("shooter");
                var target = ctx.byLabel.get("target");
                shooter.setLowCrouching(true);
                target.setPose(Pose.CROUCHING);
                pinnedAndProne[0] = shooter.getCoverBehaviorManager().isPinned()
                    && shooter.isLowCrouching();
                var from = shooter.getEyePosition();
                var head = target.position().add(0, target.getBbHeight() * 0.92, 0);
                var torso = target.position().add(0, target.getBbHeight() * 0.68, 0);
                geometryConfirmed[0] = VisibilityRay.traceFresh(ctx.level, from, head, shooter).hasContact()
                    && !VisibilityRay.traceFresh(ctx.level, from, torso, shooter).hasContact();
                var selected = ExposureCalculator.getBestAimPoint(shooter, target);
                ctx.diagnostics.add("prone-head probe: from=" + from + " target=" + target.position()
                    + " height=" + target.getBbHeight() + " head=" + head + " torso=" + torso
                    + " selected=" + selected.type + " " + selected.position
                    + " selectedFresh=" + VisibilityRay.traceFresh(ctx.level, from, selected.position, shooter).hasContact());
                selectedClearHead[0] = selected.canShoot()
                    && selected.type == ExposureCalculator.AimPointType.HEAD
                    && VisibilityRay.traceFresh(ctx.level, from, selected.position, shooter).hasContact();
            })
            .assertThat("fixture has a clear head and blocked preferred torso", view -> geometryConfirmed[0])
            .assertThat("shooter is pinned and prone at the head-lane check", view -> pinnedAndProne[0])
            .assertThat("aim selection uses the clear head lane", view -> selectedClearHead[0])
            .assertThat("pinned prone shooter fires at the exposed target", view ->
                view.shotTicks().stream().anyMatch(tick -> tick >= 5 && tick <= 100))
            .build();
    }
}

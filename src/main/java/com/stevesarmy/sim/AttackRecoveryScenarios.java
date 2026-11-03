package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.FireTeamSuppressionTracker;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Personal suppression must pause an attack without permanently cancelling its next bound. */
public final class AttackRecoveryScenarios {
    private static final String RIFLE = "rifleman";

    private AttackRecoveryScenarios() {}

    /**
     * The pressure source stops, while its live enemy and the original ATTACK order remain.
     * No private AI state, recovery timers, or movement destinations are injected. The
     * existing band-edge rig supplies a real dock before pressure starts; a second row
     * gives the normal search/navigation pipeline somewhere useful to advance afterward.
     */
    public static SimScenario personalPinRecovery() {
        BlockPos[] dock = {null};
        Vec3[] dockedPosition = {null};
        boolean[] pinnedAtDock = {false};
        boolean[] recovered = {false};
        boolean[] teamBelowHeavy = {true};
        int[] recoveryTick = {-1};
        int[] progressTick = {-1};
        int lastPressureTick = 390;
        var b = SimScenario.builder("attack_personal_pin_recovery")
            .core()
            .duration(1200)
            .armed()
            .description("A rifleman pins at a physically occupied dock with team pressure below HEAVY. "
                + "After scripted fire stops, the same live enemy and ATTACK order persist: "
                + "hold through the quiet window, then make real forward progress without a new command.")
            .terrain(t -> {
                t.platform();
                t.coverRow(12, 10, 28, 10, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 14);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), RIFLE, 20, 0, 14, SquadMode.HOLD);
                ctx.harden(RIFLE);
                var target = ctx.spawnEnemyLabeled("target", 12, 0, 2);
                target.setNoAi(true);
                target.setSquadMode(SquadMode.HOLD);
                target.setHoldPosition(ctx.terrain.at(12, 0, 2));
                ctx.disarmSoldier("target");
                ctx.harden("target");
                ctx.injectThreat(20, 5, 1.0f);
                ctx.issueAttackOrder(20, 2);
            })
            // Synthetic origins have no matching shooting entity; the invulnerable
            // enemy cannot provide a damage-based FireStarvation signal. Sparse-row
            // dock search/pose quality is covered by the dedicated cover fixtures.
            .suppressDetectors("FireStarvation", "PeekThrash", "DangerOracleMismatch",
                "StuckSeek", "ExposedWhileHiding", "PositionDeadlock")
            .action(240, "record physically occupied origin dock", ctx -> {
                var s = ctx.soldier(RIFLE);
                var m = s.getCoverBehaviorManager();
                var cover = m.getCurrentCover();
                if (cover != null && m.isInCover()
                    && s.position().multiply(1, 0, 1).distanceTo(cover.getPosition().getCenter().multiply(1, 0, 1)) <= 0.25) {
                    dock[0] = cover.getPosition().immutable();
                    dockedPosition[0] = s.position();
                }
            })
            .action(410, "reveal successor cover after pressure ends", ctx ->
                ctx.terrain.coverRow(12, 5, 28, 5, 1));

        for (int tick = 250; tick <= lastPressureTick; tick += 10) {
            b.action(tick, "pin at the original dock", ctx ->
                ctx.injectSuppressionFromGround(RIFLE, 2, 20.5, 5.5, 1.5, 3.0f));
        }
        for (int tick = 60; tick < 1200; tick += 60) {
            b.action(tick, "retain live threat context without adding suppression", ctx ->
                ctx.refreshInjectedThreats());
        }
        for (int tick = 260; tick < 1200; tick += 10) {
            final int at = tick;
            b.action(tick, "observe personal recovery and actual next bound", ctx -> {
                var s = ctx.soldier(RIFLE);
                var m = s.getCoverBehaviorManager();
                teamBelowHeavy[0] &= FireTeamSuppressionTracker.getLevel(s)
                    < StevesArmyConfig.getFireteamHeavyThreshold();
                if (dock[0] != null && dock[0].equals(m.getCurrentCover() == null
                    ? null : m.getCurrentCover().getPosition()) && m.isInCover() && m.isPinned()) {
                    pinnedAtDock[0] = true;
                }
                if (at > lastPressureTick && pinnedAtDock[0] && m.getSuppressionTracker().isRecovered()) {
                    recovered[0] = true;
                    if (recoveryTick[0] < 0) recoveryTick[0] = at;
                }
                if (dockedPosition[0] != null && recovered[0] && s.hasValidAttackTarget()
                    && ctx.soldier("target").isAlive()
                    && s.getZ() <= dockedPosition[0].z - 2.5 && progressTick[0] < 0) {
                    progressTick[0] = at;
                }
                if (at == 1190) {
                    StevesArmyMod.LOGGER.info(
                        "[AttackRecovery] dock={} pinned={} teamBelowHeavy={} recoveryTick={} progressTick={} finalPos={} state={} attackTarget={}",
                        dock[0], pinnedAtDock[0], teamBelowHeavy[0], recoveryTick[0], progressTick[0],
                        s.position(), m.getState(), s.getAttackTargetPos());
                }
            });
        }
        return b
            .assertThat("fixture reached and pinned at a real occupied dock with team below HEAVY",
                view -> dockedPosition[0] != null && pinnedAtDock[0] && teamBelowHeavy[0])
            .assertThat("original dock is retained during personal suppression and the eight-second quiet window",
                view -> dock[0] != null && view.timeline().series(RIFLE).stream()
                    .filter(s -> s.tick >= 260 && s.tick < lastPressureTick + 160)
                    .allMatch(s -> dock[0].equals(s.state.currentCover) && s.state.position != null
                        && Math.hypot(s.state.position.x - dockedPosition[0].x,
                            s.state.position.z - dockedPosition[0].z) <= 1.0))
            .assertThat("personal suppression naturally recovers while the enemy and attack objective remain live",
                view -> recovered[0] && view.timeline().series(RIFLE).stream()
                    .anyMatch(s -> s.tick > lastPressureTick && s.hasAttackTarget
                        && s.state.suppressionLevel < 0.3f))
            .assertThat("recovered rifleman physically advances at least 2.5 blocks on the original order by t1190",
                view -> progressTick[0] >= lastPressureTick + 160 && progressTick[0] <= 1190)
            .build();
    }
}

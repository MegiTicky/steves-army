package com.stevesarmy.sim;

import com.stevesarmy.GameClock;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.MachineGunnerSupportGoal;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Set;

/** Reduced trace reconstruction: seed one occupied dock, then run the real MG AI. */
public final class MgAttackRowHoldScenarios {
    private static final int SEED_TICK = 10;
    private static final int END_TICK = 1200;

    private MgAttackRowHoldScenarios() {}

    public static SimScenario objectiveCrossed() { return create(true); }
    public static SimScenario alignedControl() { return create(false); }

    private static SimScenario create(boolean crossed) {
        String name = crossed ? "mg_attack_row_hold_objective_crossed" : "mg_attack_row_hold_aligned";
        // Same MG, owner, objective, enemy, terrain. Only the rifle line changes.
        int rifleX = crossed ? 38 : 20;
        int rifleZ = crossed ? 13 : 25;
        boolean[] seeded = {false};
        boolean[] newSearch = {false};
        double[] minimumGap = {Double.MAX_VALUE};
        long[] acceptedShots = {0};
        int[] seedEntityTick = {-1};
        int[] firstSearch = {-1};

        SimScenario.Builder scenario = SimScenario.builder(name)
            .description("Reduced trace reconstruction: occupied HALF dock with an obstructed lane; "
                + (crossed ? "crossed rifle line tests physical gap recovery."
                           : "aligned rifle line is the control for physical gap recovery."))
            .duration(END_TICK).field(80, 80).armed()
            // The invulnerable, disarmed actors establish geometry, not damage.
            .suppressDetectors("FireStarvation")
            .terrain(t -> {
                t.platform();
                t.coverRow(19, 59, 21, 59, 1);
                t.wall(12, 55, 28, 55, 2, Blocks.STONE_BRICKS.defaultBlockState());
                // Long low cover gives a protected crouch and standing lane;
                // endpoints do not expose the intended successor dock obliquely.
                t.coverRow(28, 40, 44, 40, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(23, 0, 63);
                SoldierEntity gunner = ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 20, 0, 60, SquadMode.HOLD);
                for (int i = 0; i < 2; i++) {
                    String label = "rifle_" + i;
                    SoldierEntity rifle = ctx.spawnFriendly(ModEntities.SOLDIER.get(), label, rifleX + (i == 0 ? -1 : 1), 0, rifleZ, SquadMode.HOLD);
                    rifle.setNoAi(true);
                    rifle.setInvulnerable(true);
                    ctx.disarmSoldier(label);
                }
                SoldierEntity enemy = ctx.spawnEnemyLabeled("defender", 19, 0, 10);
                enemy.setNoAi(true);
                enemy.setInvulnerable(true);
                ctx.disarmSoldier("defender");
                gunner.setInvulnerable(true);
                // No registered clearing job: isolate the hold order from clearing stages.
                BlockPos objective = ctx.terrain.at(19, 0, 20);
                gunner.setAttackTarget(objective);
                gunner.setHoldPosition(objective);
            });

        for (int tick = SEED_TICK; tick <= END_TICK; tick++) {
            int at = tick;
            scenario.action(at, "observe reconstructed occupied-dock state", ctx -> {
                SoldierEntity gunner = ctx.soldier("gunner");
                gunner.refreshAttackOrderClock();
                CoverTacticalGoal goal = ((MachineGunnerSupportGoal) gunner.getCoverTacticalGoal()).getCoverController();
                BlockPos dock = ctx.terrain.at(20, 0, 60);
                if (at == SEED_TICK) {
                    seedDock(gunner, goal, dock);
                    seeded[0] = true;
                    seedEntityTick[0] = gunner.tickCount;
                }
                SoldierEntity rifle0 = ctx.soldier("rifle_0");
                SoldierEntity rifle1 = ctx.soldier("rifle_1");
                Vec3 average = new Vec3(
                    (rifle0.getX() + rifle1.getX()) * 0.5,
                    (rifle0.getY() + rifle1.getY()) * 0.5,
                    (rifle0.getZ() + rifle1.getZ()) * 0.5);
                double gap = Math.hypot(gunner.getX() - average.x, gunner.getZ() - average.z);
                minimumGap[0] = Math.min(minimumGap[0], gap);
                acceptedShots[0] = SimGunHandler.acceptedShots().stream().filter(shot -> gunner.getUUID().equals(shot.shooterId())).count();
                var state = goal.captureStateSnapshot();
                if (at == SEED_TICK + 1) {
                    seeded[0] = seeded[0] && gunner.hasValidAttackTarget()
                        && gunner.getCoverBehaviorManager().getCurrentCover() != null
                        && goal.getAttackPhaseOrdinal() == CoverTacticalGoal.AttackPhase.OCCUPYING_COVER.ordinal()
                        && gap > 30 && !gunner.hasLineOfSight(ctx.soldier("defender"))
                        && gunner.getCoverBehaviorManager().getSuppressionLevel() == 0;
                }
                if (at > SEED_TICK && state.lastSearch != null
                    && "ATTACK_SELECTING".equals(state.lastSearch.mode)
                    && state.lastSearch.tick > seedEntityTick[0]) {
                    newSearch[0] = true;
                    if (firstSearch[0] < 0) firstSearch[0] = at;
                }
                if (at == SEED_TICK + 1 || at == 200 || at == 400 || at == END_TICK) {
                    long shots = SimGunHandler.acceptedShots().stream().filter(s -> gunner.getUUID().equals(s.shooterId())).count();
                    StevesArmyMod.LOGGER.info(
                        "[MgAttackRowHold] scenario={} tick={} validSetup={} gap={} phase={} current={} target={} search={} firstSearch={} actualLos={} suppression={} reload={} shots={}",
                        name, at, seeded[0], gap,
                        CoverTacticalGoal.AttackPhase.values()[goal.getAttackPhaseOrdinal()],
                        gunner.getCoverBehaviorManager().getCurrentCover() == null ? null : gunner.getCoverBehaviorManager().getCurrentCover().getPosition(),
                        gunner.getCoverBehaviorManager().getTargetCover() == null ? null : gunner.getCoverBehaviorManager().getTargetCover().getPosition(),
                        state.lastSearch == null ? null : state.lastSearch.mode + "/" + state.lastSearch.result + ":" + state.lastSearch.tick,
                        firstSearch[0], gunner.hasLineOfSight(ctx.soldier("defender")),
                        gunner.getCoverBehaviorManager().getSuppressionLevel(), gunner.isPreparingOrReloading(), shots);
                }
            });
        }
        return scenario
            .assertThat("reconstructed unsuppressed occupied HALF dock has a >30-block gap and blocked LOS", v -> seeded[0])
            .assertThat("real attack state machine starts a fresh catch-up cover search", v -> newSearch[0])
            .assertThat("MG physically closes the squad gap below 30 blocks", v -> minimumGap[0] < 30)
            .assertThat("MG restores a firing opportunity", v -> acceptedShots[0] > 0)
            .build();
    }

    private static void seedDock(SoldierEntity gunner, CoverTacticalGoal goal, BlockPos dock) {
        gunner.getNavigation().stop();
        if (gunner.getMoveControl() instanceof CoverPositionController control) control.clear();
        gunner.setPos(dock.getX() + .5, dock.getY(), dock.getZ() + .5);
        gunner.setDeltaMovement(Vec3.ZERO);
        CoverPoint cover = new CoverPoint(dock);
        cover.setType(CoverType.HALF);
        cover.setCoverHeight(1);
        cover.setProtectedDirections(Set.of(Direction.NORTH));
        cover.setQuality(1);
        cover.setCombatScore(1);
        gunner.getCoverBehaviorManager().clearCover();
        gunner.getCoverBehaviorManager().setCurrentCover(cover);
        gunner.getCoverBehaviorManager().setState(CoverBehaviorManager.CoverState.IN_COVER);
        CoverReservationManager.reserve(dock, gunner);
        gunner.getPeekController().resetForNewCover(gunner, dock);
        // One-time state reconstruction, not an override during observation.
        set(goal, "attackCommandGeneration", gunner.getAttackGeneration());
        set(goal, "attackPhase", CoverTacticalGoal.AttackPhase.OCCUPYING_COVER);
        set(goal, "attackPhaseStartTime", GameClock.nowMs());
        set(goal, "attackCoverArrivalTime", GameClock.nowMs());
        set(goal, "attackExpectedCover", null);
        set(goal, "fallbackAdvanceTarget", null);
    }

    private static void set(CoverTacticalGoal goal, String name, Object value) {
        try {
            Field field = CoverTacticalGoal.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(goal, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MG dock reconstruction field changed: " + name, e);
        }
    }
}

package com.stevesarmy.sim;

import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;

/** Two soldiers competing around one block: protected side versus wrong side. */
public final class LoneBlockMultiSoldierScenario {
    private LoneBlockMultiSoldierScenario() {}

    public static SimScenario create() {
        boolean[] sharedSquadRegistered = {false};
        boolean[] protectedOwnerSeeded = {false};
        return SimScenario.builder("lone_block_multi_soldier_direction")
            .description("A fixed north threat faces two soldiers on opposite sides of one block. "
                + "The south-side soldier owns the block; the north-side soldier rejects it and stays "
                + "prone in a separated open-ground lane.")
            .duration(500)
            .armed()
            // The fixed threat is invulnerable so posture can be observed for the full run.
            .suppressDetectors("FireStarvation")
            .terrain(t -> { t.platform(); t.coverBlock(20, 30, 1); })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                var covered = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "covered", 20, 0, 31, SquadMode.HOLD);
                var wrongSide = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "wrong_side", 20, 0, 27, SquadMode.HOLD);
                var threat = ctx.spawnEnemyLabeled("threat", 20, 0, 8);
                threat.setNoAi(true);
                threat.setInvulnerable(true);
                threat.setSquadMode(SquadMode.HOLD);
                threat.setHoldPosition(ctx.terrain.at(20, 0, 8));
                covered.getCombatGoal().setTarget(threat);
                wrongSide.getCombatGoal().setTarget(threat);
                covered.setInvulnerable(true);
                wrongSide.setInvulnerable(true);
                wrongSide.setNoAi(true);
                ctx.injectThreat(20, 8, 1.0f);
            })
            .action(1, "seed owner on finder-validated south-side cover", ctx ->
                protectedOwnerSeeded[0] = seedProtectedOwner(ctx))
            .action(1, "verify shared squad registration", ctx -> {
                var covered = ctx.soldier("covered");
                var wrongSide = ctx.soldier("wrong_side");
                var coveredSquadId = covered.getSquadId();
                var wrongSideSquadId = wrongSide.getSquadId();
                var members = coveredSquadId == null ? java.util.List.<net.minecraft.world.entity.LivingEntity>of()
                    : SquadManager.get(ctx.level).getSquadMembers(ctx.level, coveredSquadId, null);
                sharedSquadRegistered[0] = coveredSquadId != null
                    && coveredSquadId.equals(wrongSideSquadId)
                    && members.stream().anyMatch(member -> member.getUUID().equals(covered.getUUID()))
                    && members.stream().anyMatch(member -> member.getUUID().equals(wrongSide.getUUID()));
            })
            .action(40, "release wrong-side soldier after block reservation", ctx ->
                ctx.soldier("wrong_side").setNoAi(false))
            .action(20, "refresh fixed threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(200, "refresh fixed threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(380, "refresh fixed threat intel", ctx -> ctx.refreshInjectedThreats())
            .suppress("wrong_side", 20, 480, 40)
            .assertThat("both soldiers are registered in the same live squad",
                view -> sharedSquadRegistered[0])
            .assertThat("south-side owner starts on a finder-validated protected block slot",
                view -> protectedOwnerSeeded[0])
            .assertThat("south-side soldier claims the lone block as HALF cover by t350",
                view -> view.timeline().series("covered").stream()
                    .anyMatch(s -> s.state.currentCover != null
                        && isLoneBlockCover(s.state.currentCover)
                        && "HALF".equals(s.state.currentCoverType)
                        && s.state.position.z > 30.0D
                        && s.tick <= 350))
            .assertThat("block owner remains docked on the threat-protected side",
                view -> view.timeline().series("covered").stream()
                    .filter(s -> s.tick >= 100 && s.state.currentCover != null
                        && isLoneBlockCover(s.state.currentCover))
                    .filter(s -> s.state.position.z > 30.0D)
                    .count() >= 20)
            .assertThat("north-side soldier never claims the occupied lone block",
                view -> view.timeline().series("wrong_side").stream()
                    .filter(s -> s.tick >= 100)
                    .noneMatch(s -> s.state.currentCover != null
                        && isLoneBlockCover(s.state.currentCover)))
            .assertThat("wrong-side soldier never selects an unprotected-side slot at the lone block",
                view -> view.timeline().series("wrong_side").stream()
                    .noneMatch(s -> s.state.targetCover != null
                        && isLoneBlockCover(s.state.targetCover)
                        && s.state.targetCover.getZ() < 30))
            .assertThat("wrong-side soldier uses sustained open-ground prone posture",
                view -> view.timeline().series("wrong_side").stream()
                    .filter(s -> s.tick >= 100 && s.state.currentCover == null)
                    .filter(s -> s.state.suppressionLevel >= 0.5f)
                    .filter(s -> "SWIMMING".equals(s.posture) && s.state.lowCrouching)
                    .count() >= 20)
            .assertThat("wrong-side prone lane remains at least three blocks from the covered teammate",
                LoneBlockMultiSoldierScenario::wrongSideProneIsSpaced)
            .build();
    }

    private static boolean seedProtectedOwner(SimContext ctx) {
        var covered = ctx.soldier("covered");
        var threat = ctx.byLabel.get("threat");
        if (threat == null) return false;

        var protectedSlot = ctx.terrain.at(20, 0, 31).immutable();
        var threatDirection = threat.position().subtract(covered.position()).normalize();
        var finder = new com.stevesarmy.combat.cover.CoverFinder(ctx.level);
        var cover = finder
            .findTopCovers(covered, threatDirection, java.util.List.of(threat), 12, 50, false)
            .stream()
            .map(scored -> scored.cover)
            .filter(candidate -> candidate.getPosition().equals(protectedSlot))
            .filter(candidate -> candidate.getType() == com.stevesarmy.combat.cover.CoverType.HALF)
            .filter(candidate -> finder.isPrimaryThreatProtected(candidate, covered, threatDirection))
            .findFirst()
            .orElse(null);
        if (cover == null || !com.stevesarmy.combat.cover.CoverReservationManager.reserve(
            protectedSlot, covered)) {
            return false;
        }

        covered.getCoverBehaviorManager().setCurrentCover(cover);
        covered.getCoverBehaviorManager().setState(
            com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER);
        return true;
    }

    private static boolean wrongSideProneIsSpaced(SimScenario.SimRunResultView view) {
        var wrongSide = view.timeline().series("wrong_side");
        var covered = view.timeline().series("covered");
        com.stevesarmy.sim.SimSample lastProne = null;
        for (var sample : wrongSide) {
            if (sample.tick >= 100 && sample.state.currentCover == null
                && "SWIMMING".equals(sample.posture) && sample.state.lowCrouching) {
                lastProne = sample;
            }
        }
        com.stevesarmy.sim.SimSample lastBlockOwner = null;
        for (var sample : covered) {
            if (sample.state.currentCover != null
                && isLoneBlockCover(sample.state.currentCover)) {
                lastBlockOwner = sample;
            }
        }
        return lastProne != null && lastBlockOwner != null
            && horizontalDistance(lastProne, lastBlockOwner) >= 3.0D;
    }

    private static boolean isLoneBlockCover(net.minecraft.core.BlockPos position) {
        return Math.abs(position.getX() - 20) + Math.abs(position.getZ() - 30) == 1;
    }

    private static double horizontalDistance(com.stevesarmy.sim.SimSample a,
                                             com.stevesarmy.sim.SimSample b) {
        return Math.hypot(a.state.position.x - b.state.position.x,
            a.state.position.z - b.state.position.z);
    }
}

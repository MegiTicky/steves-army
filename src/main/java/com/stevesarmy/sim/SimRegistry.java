package com.stevesarmy.sim;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The v1 scenario set: the cover/suppression strand the live mod struggles
 * with, plus one clean baseline for detector calibration. Every generic
 * detector runs on every scenario; explicit assertions add scenario-specific
 * expectations.
 */
public final class SimRegistry {
    private SimRegistry() {}

    public static List<SimScenario> all() {
        return List.of(
            suppressedFollowEscape(),
            holdEnclosedPocket(),
            persistentPingRelocation(),
            mgAnchorFreshness(),
            pairSuppressionLanes(),
            openFieldBaseline());
    }

    public static SimScenario byName(String name) {
        for (SimScenario scenario : all()) {
            if (scenario.name.equals(name)) return scenario;
        }
        return null;
    }

    public static List<String> names() {
        return all().stream().map(s -> s.name).toList();
    }

    // 1. FOLLOW squad pinned by sustained fire — the follow-escape strand ------

    private static SimScenario suppressedFollowEscape() {
        return SimScenario.builder("suppressed_follow_escape")
            .description("FOLLOW squad under sustained MG-style fire: soldiers must reach cover and "
                + "the follow gate must reopen once pressure subsides (t400+). Reproduces the "
                + "suppression-gates-all-follow-escapes strand.")
            .duration(600)
            .terrain(t -> {
                t.platform();
                t.coverRow(20, 18, 22, 18, 1); // north waist-high segment
                t.coverRow(20, 30, 22, 30, 1); // south waist-high segment
                t.coverBlock(19, 24, 2);       // head-high hard cover mid-line
                t.coverRow(44, 20, 46, 20, 1); // enemy-side cover (east of the shooter)
                t.coverRow(44, 28, 46, 28, 1);
                t.coverBlock(43, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alice", 10, 0, 24, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bob", 12, 0, 27, SquadMode.FOLLOW);
                ctx.spawnEnemy(40, 0, 24);
            })
            .suppress(null, 40, 400, 15)
            .assertThat("injection pins soldiers (suppression reaches >= 0.9)",
                view -> maxSuppression(view, "alice") >= 0.9f)
            .assertThat("at least one soldier engages the cover machine (cover sought or relocation)",
                view -> view.timeline().soldierLabels().stream().anyMatch(label ->
                    view.timeline().series(label).stream().anyMatch(s ->
                        s.state.targetCover != null || s.state.currentCover != null
                            || !"NONE".equals(s.state.relocType))))
            .build();
    }

    // 2. HOLD soldier in a one-exit pocket — the seek-fail escalation strand ---

    private static SimScenario holdEnclosedPocket() {
        return SimScenario.builder("hold_enclosed_pocket")
            .description("HOLD soldier inside a three-walled pocket with the enemy outside the opening: "
                + "cover searches should fail-and-escalate (exhaustion, blacklist) but never strand "
                + "the soldier in SEEKING_COVER forever.")
            .duration(600)
            .terrain(t -> {
                t.platform();
                t.pocket(10, 24, 3, 3, "W");
                t.coverRow(44, 20, 46, 20, 1); // enemy-side cover
                t.coverRow(44, 28, 46, 28, 1);
                t.coverBlock(43, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(3, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "pocket1", 10, 0, 24, SquadMode.HOLD);
                ctx.spawnEnemy(40, 0, 24);
            })
            .suppress("pocket1", 40, 400, 15)
            .assertThat("pocket soldier engages the cover machine at least once",
                view -> view.timeline().series("pocket1").stream().anyMatch(s ->
                    s.state.searchPending || s.state.targetCover != null || s.state.failedSearches > 0))
            .build();
    }

    // 3. continuous suppression — relocation-away-from-origin strand -----------

    private static SimScenario persistentPingRelocation() {
        return SimScenario.builder("persistent_ping_relocation")
            .description("Rifleman pinned continuously for the whole scenario from one fixed origin: "
                + "the relocation chain must move the soldier between covers instead of dead-ending.")
            .duration(700)
            .terrain(t -> {
                t.platform();
                t.coverRow(22, 20, 24, 20, 1); // relocation option north of the line
                t.coverRow(22, 28, 24, 28, 1); // relocation option south of the line
                t.coverBlock(18, 24, 2);       // close hard cover near the spawn
                t.coverRow(40, 20, 42, 20, 1); // enemy-side cover
                t.coverRow(40, 28, 42, 28, 1);
                t.coverBlock(39, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "runner", 12, 0, 24, SquadMode.FOLLOW);
                ctx.spawnEnemy(36, 0, 24);
            })
            .suppress("runner", 40, 660, 15)
            .assertThat("runner claims or pursues more than one cover position across the run",
                view -> distinctCovers(view, "runner") >= 2)
            .build();
    }

    // 4. MG anchor + suppression centroid freshness after the shooter moves ----

    private static SimScenario mgAnchorFreshness() {
        return SimScenario.builder("mg_anchor_freshness")
            .description("Machine gunner pinned from position A; at t320 the shooter relocates to "
                + "position B. The MG suppression centroid must refresh from fresh squad intel "
                + "instead of latching to the stale anchor.")
            .duration(700)
            .terrain(t -> {
                t.platform();
                t.coverRow(16, 21, 18, 21, 1); // waist-high cover north of the gunner
                t.coverRow(16, 27, 18, 27, 1); // waist-high cover south of the gunner
                t.coverBlock(22, 24, 2);       // head-high hard cover mid-line
                t.coverRow(40, 20, 42, 20, 1); // enemy-side cover near position A
                t.coverRow(40, 28, 42, 28, 1);
                t.coverBlock(39, 24, 2);
                t.coverRow(40, 40, 42, 40, 1); // enemy-side cover near the moved position B
                t.coverRow(40, 46, 42, 46, 1);
                t.coverBlock(39, 44, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 12, 0, 24, SquadMode.FOLLOW);
                ctx.spawnEnemy(36, 0, 24);
            })
            .suppress("gunner", 40, 300, 15)
            .action(320, "enemy relocates 20 blocks south", ctx -> {
                EnemySoldierEntity enemy = ctx.enemies.isEmpty() ? null : ctx.enemies.get(0);
                if (enemy != null) ctx.moveEnemy(enemy, 36, 0, 44);
            })
            .suppress("gunner", 340, 660, 15)
            .assertThat("MG suppression centroid sees fresh intel after the shooter moves "
                + "(fresh ticks <= 120 by t520)",
                view -> view.timeline().series("gunner").stream()
                    .filter(s -> s.tick >= 520)
                    .anyMatch(s -> s.state.suppressionCenter != null && s.state.supCenterFreshTicks >= 0
                        && s.state.supCenterFreshTicks <= 120))
            .build();
    }

    // 5. two suppressors on one threat — dispersion, no duplicated anchor ------

    private static SimScenario pairSuppressionLanes() {
        return SimScenario.builder("pair_suppression_lanes")
            .description("Two riflemen pinned by one shooter: each should claim its own cover and "
                + "(when armed) the pair should disperse rather than stack on one anchor.")
            .duration(700)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverRow(18, 12, 20, 12, 1); // left soldier's options
                t.coverRow(18, 19, 20, 19, 1);
                t.coverRow(18, 29, 20, 29, 1); // right soldier's options
                t.coverRow(18, 36, 20, 36, 1);
                t.coverRow(40, 20, 42, 20, 1); // enemy-side cover
                t.coverRow(40, 28, 42, 28, 1);
                t.coverBlock(39, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "left", 12, 0, 16, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "right", 12, 0, 32, SquadMode.FOLLOW);
                ctx.spawnEnemy(36, 0, 24);
            })
            .suppress(null, 40, 660, 15)
            .assertThat("when both soldiers hold cover at the end, their covers differ",
                view -> {
                    Vec3 left = lastCover(view, "left");
                    Vec3 right = lastCover(view, "right");
                    if (left == null || right == null) return true; // stuck cases are the detectors' job
                    return left.distanceTo(right) >= 1.0;
                })
            .build();
    }

    // 6. clean baseline — detector false-positive calibration ------------------

    private static SimScenario openFieldBaseline() {
        return SimScenario.builder("open_field_baseline")
            .description("No suppression, one visible enemy directly in the soldier's facing: a healthy "
                + "run. Any detector violation here is a false positive to calibrate, not a bug.")
            .duration(500)
            .terrain(t -> {
                t.platform();
                t.coverRow(7, 44, 13, 44, 1); // enemy-side cover south of the shooter
                t.coverBlock(7, 40, 1);
                t.coverBlock(13, 40, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 8);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "scout", 10, 0, 8, SquadMode.FOLLOW);
                ctx.spawnEnemy(10, 0, 40); // due south = in front of yaw-0 soldiers
            })
            .assertThat("scout detects and targets the enemy by t400",
                view -> view.timeline().series("scout").stream()
                    .filter(s -> s.tick <= 400)
                    .anyMatch(s -> s.hasAttackTarget))
            .build();
    }

    // --- assertion helpers -----------------------------------------------------

    private static float maxSuppression(SimScenario.SimRunResultView view, String label) {
        float max = 0f;
        for (SimSample s : view.timeline().series(label)) {
            max = Math.max(max, s.state.suppressionLevel);
        }
        return max;
    }

    private static int distinctCovers(SimScenario.SimRunResultView view, String label) {
        java.util.Set<net.minecraft.core.BlockPos> covers = new java.util.HashSet<>();
        for (SimSample s : view.timeline().series(label)) {
            if (s.state.targetCover != null) covers.add(s.state.targetCover);
            if (s.state.currentCover != null) covers.add(s.state.currentCover);
        }
        return covers.size();
    }

    private static Vec3 lastCover(SimScenario.SimRunResultView view, String label) {
        List<SimSample> series = view.timeline().series(label);
        for (int i = series.size() - 1; i >= 0; i--) {
            if (series.get(i).state.currentCover != null) {
                return series.get(i).state.currentCover.getCenter();
            }
        }
        return null;
    }
}

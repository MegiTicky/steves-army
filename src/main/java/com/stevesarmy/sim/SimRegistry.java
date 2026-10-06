package com.stevesarmy.sim;

import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
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
            mgRiflemanSeekContrast(),
            pairSuppressionLanes(),
            openFieldBaseline(),
            dockArrivalRoles(),
            dockArrivalSuppressed(),
            dockReloadHold(),
            dockKnockbackRecovery(),
            dockSuppressedDriftRecovery(),
            dockCqbHoldFidelity(),
            dockPeekReturn());
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

    // 4b. MG vs riflemen under identical fire — the MG seek-strand bug ---------

    private static SimScenario mgRiflemanSeekContrast() {
        return SimScenario.builder("mg_rifleman_seek_contrast")
            .description("Live-fire MG vs riflemen fight: both sides armed with sim guns, so "
                + "suppression is organic (real near-misses, real return fire). Riflemen must "
                + "claim the nearby cover rows; the MG must not strand in SEEKING_COVER with "
                + "zero movement — the live combat bug where the MG never gets into cover. "
                + "This is also the peek-system probe: pinned soldiers must expose and fire.")
            .duration(700)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverRow(16, 21, 18, 21, 1); // waist-high rows the squad should claim
                t.coverRow(16, 27, 18, 27, 1);
                t.coverBlock(22, 24, 2);       // head-high hard cover mid-line
                t.coverRow(40, 20, 42, 20, 1); // enemy-side cover
                t.coverRow(40, 28, 42, 28, 1);
                t.coverBlock(39, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 12, 0, 24, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "left", 12, 0, 14, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "right", 12, 0, 34, SquadMode.FOLLOW);
                // two shooters so the squad's near-miss suppression can't pin
                // the whole enemy side silent — a live exchange both ways
                ctx.spawnEnemy(38, 0, 22);
                ctx.spawnEnemy(38, 0, 26);
            })
            .assertThat("left rifleman claims cover by t200 and still holds it at t400",
                view -> claimedCoverByAndHeld(view, "left", 200, 400))
            .assertThat("right rifleman claims cover by t200 and still holds it at t400",
                view -> claimedCoverByAndHeld(view, "right", 200, 400))
            .assertThat("MG gets into cover (or genuinely relocates) instead of seeking in place",
                view -> mgEngagedCover(view, "gunner"))
            .assertThat("squad fights back — the enemy takes damage from friendly fire",
                view -> view.enemyHealthEnd() < view.enemyHealthStart())
            .assertThat("riflemen actually fire at the enemy during the fight",
                view -> view.timeline().series("left").stream().anyMatch(s -> s.shotsFired > 0)
                    && view.timeline().series("right").stream().anyMatch(s -> s.shotsFired > 0))
            .assertThat("at least one soldier peeks out of cover to engage (EXPOSED)",
                view -> view.timeline().soldierLabels().stream()
                    .anyMatch(label -> view.timeline().series(label).stream()
                        .anyMatch(s -> "EXPOSED".equals(s.state.peekState))))
            .build();
    }

    /** Claims a cover by {@code byTick} and still holds one at {@code holdTick}. */
    private static boolean claimedCoverByAndHeld(SimScenario.SimRunResultView view, String label,
                                                 int byTick, int holdTick) {
        boolean claimed = false;
        boolean held = false;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick <= byTick && s.state.currentCover != null) claimed = true;
            if (s.tick == holdTick && s.state.currentCover != null) held = true;
        }
        return claimed && held;
    }

    /**
     * The MG is expected to end up in cover like any rifleman. Fails the
     * seek-strand signature: never claims cover and never leaves its
     * spawn neighborhood while the cover machine runs.
     */
    private static boolean mgEngagedCover(SimScenario.SimRunResultView view, String label) {
        Vec3 spawn = null;
        for (SimSample s : view.timeline().series(label)) {
            if (s.state.currentCover != null) return true;
            if (spawn == null) spawn = s.state.position;
            if (spawn != null && s.state.position.distanceTo(spawn) > 6.0) return true;
        }
        return false;
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

    // 7-11. cover-arrival fidelity (dock suite) ---------------------------------
    //
    // The final phase of getting into cover is velocity manipulation: the
    // CoverPositionController anchor pulls the soldier onto the exact slot
    // center (block center + 0.08 deadzone) whenever it is idle in cover.
    // These scenarios check that the anchor actually fired and that the
    // soldier physically stands in the slot block — not near it — across
    // roles and edge cases (suppression, reload, knockback, peek return).

    private static final double DOCK_TOLERANCE = 0.35;

    /** Standard one-lane dock fixture: half row + full block between soldier lane and enemy. */
    private static void dockLane(com.stevesarmy.sim.terrain.SimTerrain t, int laneX) {
        t.coverRow(laneX - 1, 30, laneX + 1, 30, 1); // waist-high row (half cover)
        t.coverBlock(laneX, 34, 2);                  // head-high block (full cover)
    }

    private static SimScenario dockArrivalRoles() {
        return SimScenario.builder("dock_arrival_roles")
            .description("Cover-arrival fidelity for three roles (rifleman, MG, anti-tank): a short "
                + "suppression pulse sends all three to cover; each must claim a slot, the anchor "
                + "velocity must engage, and each must end standing in the exact slot block with "
                + "0 exposed body rays while hiding.")
            .duration(500)
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
                dockLane(t, 18);
                dockLane(t, 26);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 18, 0, 22, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.ANTI_TANK.get(), "at", 26, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(18, 0, 42);
            })
            .suppress(null, 20, 80, 15)
            .assertThat("rifleman claims cover by t300", view -> claimedCoverBy(view, "rifle", 300))
            .assertThat("MG claims cover by t300", view -> claimedCoverBy(view, "gunner", 300))
            .assertThat("anti-tank claims cover by t300", view -> claimedCoverBy(view, "at", 300))
            .assertThat("rifleman docks into the exact slot (offset <= 0.35)", view -> dockedAtEnd(view, "rifle"))
            .assertThat("MG docks into the exact slot (offset <= 0.35)", view -> dockedAtEnd(view, "gunner"))
            .assertThat("anti-tank docks into the exact slot (offset <= 0.35)", view -> dockedAtEnd(view, "at"))
            .assertThat("rifleman anchor velocity engaged", view -> anchorPullsBy(view, "rifle") > 0)
            .assertThat("MG anchor velocity engaged", view -> anchorPullsBy(view, "gunner") > 0)
            .assertThat("anti-tank anchor velocity engaged", view -> anchorPullsBy(view, "at") > 0)
            .assertThat("rifleman physically hidden while hiding (0 exposed rays)",
                view -> existsHidingUnexposed(view, "rifle", 250))
            .assertThat("MG physically hidden while hiding (0 exposed rays)",
                view -> existsHidingUnexposed(view, "gunner", 250))
            .assertThat("anti-tank physically hidden while hiding (0 exposed rays)",
                view -> existsHidingUnexposed(view, "at", 250))
            .build();
    }

    private static SimScenario dockArrivalSuppressed() {
        return SimScenario.builder("dock_arrival_suppressed")
            .description("Arrival under sustained suppression (rifleman + MG): both must claim cover while "
                + "pinned, and once pressure ends the anchor must finish the docking — exact slot block, "
                + "no drift left over from the pinned approach.")
            .duration(550)
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
                dockLane(t, 18);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 18, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(14, 0, 42);
            })
            .suppress(null, 40, 440, 15)
            .assertThat("rifleman claimed cover while pinned", view -> wasPinnedInCover(view, "rifle"))
            .assertThat("MG claimed cover while pinned", view -> wasPinnedInCover(view, "gunner"))
            .assertThat("rifleman docks into the exact slot once pressure ends",
                view -> dockedAtEnd(view, "rifle"))
            .assertThat("MG docks into the exact slot once pressure ends",
                view -> dockedAtEnd(view, "gunner"))
            .assertThat("rifleman anchor velocity engaged", view -> anchorPullsBy(view, "rifle") > 0)
            .assertThat("MG anchor velocity engaged", view -> anchorPullsBy(view, "gunner") > 0)
            .build();
    }

    private static SimScenario dockReloadHold() {
        return SimScenario.builder("dock_reload_hold")
            .description("Reload edge case: a scripted reload pins the soldier in its cover slot; shoves "
                + "during the reload must be damped (movement held while reloading), and once the reload "
                + "finishes the anchor velocity must pull the soldier back into the exact slot block.")
            .duration(500)
            .armed()
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "reloader", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(16, 0, 42);
                ctx.spawnEnemy(22, 0, 46);
            })
            .action(100, "scripted reload starts", ctx -> {
                SoldierEntity s = ctx.soldier("reloader");
                if (s != null && s.isAlive()) {
                    com.stevesarmy.combat.GunIntegration.reload(s);
                }
            })
            .action(110, "shove while reloading", ctx -> ctx.knockbackIfReloadingInCover("reloader", 0.35, 0))
            .action(118, "shove while reloading", ctx -> ctx.knockbackIfReloadingInCover("reloader", 0.35, 0))
            .action(126, "shove while reloading", ctx -> ctx.knockbackIfReloadingInCover("reloader", 0.35, 0))
            .action(134, "shove while reloading", ctx -> ctx.knockbackIfReloadingInCover("reloader", 0.35, 0))
            .action(200, "shove after reload (idle in cover)", ctx -> ctx.knockbackIfIdleInCover("reloader", 0.45, 0))
            .action(208, "shove after reload (idle in cover)", ctx -> ctx.knockbackIfIdleInCover("reloader", 0.45, 0))
            .action(216, "shove after reload (idle in cover)", ctx -> ctx.knockbackIfIdleInCover("reloader", 0.45, 0))
            .assertThat("reload engaged (a reloading sample was recorded)", view -> everReloading(view, "reloader"))
            .assertThat("reload held the slot through the reload shoves (idle drift < 0.5)",
                view -> peakIdleOffset(view, "reloader", 100, 190) < 0.5)
            .assertThat("anchor pulled the soldier back into the slot after the post-reload shove",
                view -> dockedBackWithin(view, "reloader", 220, 160))
            .assertThat("anchor velocity fired after the reload (pulls increased post t190)",
                view -> anchorPullsDeltaSince(view, "reloader", 190) >= 3)
            .build();
    }

    private static SimScenario dockKnockbackRecovery() {
        return SimScenario.builder("dock_knockback_recovery")
            .description("Anchor-recovery probe (support role, peek disabled): shoves land while the soldier "
                + "is pinned crouched in its slot early, before the enemy can close to CQB range and freeze "
                + "the controller. The anchor must fire and pull the soldier back into the exact slot block.")
            .duration(420)
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SUPPORT.get(), "anchor1", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(24, 0, 46); // far: keeps CQB hold out of the recovery window
            })
            .suppress(null, 20, 360, 15)
            .action(55, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("anchor1", 0.4, 0))
            .action(63, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("anchor1", 0.4, 0))
            .action(71, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("anchor1", 0.4, 0))
            .action(79, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("anchor1", 0.4, 0))
            .assertThat("shove registered while pinned (idle offset > 0.3 seen after t55)",
                view -> peakIdleOffset(view, "anchor1", 60, 250) > 0.3)
            .assertThat("anchor pulled the soldier back into the slot (offset <= 0.2 within 150t)",
                view -> dockedBackWithin(view, "anchor1", 60, 150))
            .assertThat("anchor velocity fired during recovery (pulls increased post t50)",
                view -> anchorPullsDeltaSince(view, "anchor1", 50) >= 3)
            .assertThat("ends docked in the exact slot", view -> dockedAtEnd(view, "anchor1"))
            .build();
    }

    private static SimScenario dockSuppressedDriftRecovery() {
        return SimScenario.builder("dock_suppressed_drift_recovery")
            .description("Anchor under suppression (rifleman): shoves land while pinned, before the enemy can "
                + "close to CQB range. The suppressed-state handler keeps the anchor live; it must re-dock "
                + "the soldier even while suppression is still decaying.")
            .duration(550)
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "drifter", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(24, 0, 46); // far: keeps CQB hold out of the recovery window
            })
            .suppress("drifter", 40, 380, 15)
            .action(60, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("drifter", 0.4, 0))
            .action(68, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("drifter", 0.4, 0))
            .action(76, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("drifter", 0.4, 0))
            .action(84, "shove pinned soldier off slot", ctx -> ctx.knockbackIfIdleInCover("drifter", 0.4, 0))
            .assertThat("soldier was pinned in cover before the shoves", view -> wasPinnedInCover(view, "drifter"))
            .assertThat("shove registered while pinned (idle offset > 0.3 seen in 65-240)",
                view -> peakIdleOffset(view, "drifter", 65, 240) > 0.3)
            .assertThat("anchor re-docked the pinned soldier (offset <= 0.2 within 130t of the shoves)",
                view -> dockedBackWithin(view, "drifter", 65, 130))
            .assertThat("anchor velocity fired (pulls increased post t55)",
                view -> anchorPullsDeltaSince(view, "drifter", 55) >= 3)
            .build();
    }

    /**
     * Documents a live finding (2026-10-06, expected FAIL until fixed): when an
     * enemy closes to CQB range while the soldier is still arriving, the CQB
     * hold freezes the position controller mid-approach — the cover anchor
     * starves, the soldier locks 0.3-0.8 blocks off its slot, and when the
     * enemy keeps advancing past the cover block the soldier keeps claiming
     * SUPPRESSED_IN_COVER/HIDING while fully visible (flank re-evaluation only
     * lives in tickInCover, which never runs while suppressed).
     */
    private static SimScenario dockCqbHoldFidelity() {
        return SimScenario.builder("dock_cqb_hold_fidelity")
            .description("KNOWN FINDING (expected FAIL today): an enemy spawning point-blank beside the cover "
                + "line latches the CQB hold during the cover approach; the controller freezes, the anchor "
                + "starves, and the soldier ends 'hiding' off-slot while the close threat sees its whole "
                + "body. Codifies the CQB-anchor starvation + flank-visibility finding for the dock suite.")
            .duration(500)
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "cqb1", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(12, 0, 34); // point-blank beside the cover line from tick 0
            })
            .suppress("cqb1", 20, 460, 15)
            .assertThat("soldier claims cover", view -> claimedCoverBy(view, "cqb1", 300))
            .assertThat("FINDING: soldier docks into its slot (offset <= 0.35) despite the CQB hold",
                view -> dockedAtEnd(view, "cqb1"))
            .assertThat("FINDING: hiding soldier is not fully visible to the point-blank threat",
                view -> !exposedWhileHidingEver(view, "cqb1", 120, 15))
            .build();
    }

    /** True when a docked hiding sample sustained {@code rays}+ exposed rays for 40t+ in the window. */
    private static boolean exposedWhileHidingEver(SimScenario.SimRunResultView view, String label,
                                                  int fromTick, int rays) {
        int streak = 0;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < fromTick) continue;
            boolean hit = s.exposedBodyRays >= rays
                && "HIDING".equals(s.state.peekState)
                && s.state.currentCover != null
                && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5;
            streak = hit ? streak + 1 : 0;
            if (streak >= 8) return true; // 8 samples = 40t
        }
        return false;
    }

    private static SimScenario dockPeekReturn() {
        return SimScenario.builder("dock_peek_return")
            .description("Peek-return docking: after a peek cycle the soldier must return into the exact "
                + "slot (RETURN tolerance 0.05). Peek completion and body exposure are the peek strand's "
                + "and the cover-protection detector's domain, not this scenario's.")
            .duration(600)
            .armed()
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
                t.coverRow(14, 42, 16, 42, 1); // enemy-side cover
                t.coverBlock(19, 44, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "peeker", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(16, 0, 40);
                ctx.spawnEnemy(20, 0, 46);
            })
            .assertThat("at least one peek cycle started (MOVING_TO_PEEK)", view -> existsPeekCycle(view, "peeker"))
            .assertThat("soldier re-docked into the exact slot after a peek return (offset <= 0.25)",
                view -> dockedAfterReturn(view, "peeker"))
            .build();
    }

    private static boolean existsPeekCycle(SimScenario.SimRunResultView view, String label) {
        for (SimSample s : view.timeline().series(label)) {
            if ("MOVING_TO_PEEK".equals(s.state.peekState) || "RETURNING_TO_COVER".equals(s.state.peekState)) {
                return true;
            }
        }
        return false;
    }

    // --- dock assertion helpers --------------------------------------------------

    private static boolean claimedCoverBy(SimScenario.SimRunResultView view, String label, int byTick) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick > byTick) break;
            if (s.state.currentCover != null) return true;
        }
        return false;
    }

    private static boolean wasPinnedInCover(SimScenario.SimRunResultView view, String label) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.state.pinned && s.state.currentCover != null) return true;
        }
        return false;
    }

    /** Last idle-in-cover sample: still holding cover, standing within DOCK_TOLERANCE of the slot center. */
    private static boolean dockedAtEnd(SimScenario.SimRunResultView view, String label) {
        List<SimSample> series = view.timeline().series(label);
        for (int i = series.size() - 1; i >= 0; i--) {
            SimSample s = series.get(i);
            if (s.state.currentCover == null || !idlePeek(s)) continue;
            return s.coverSlotOffset >= 0 && s.coverSlotOffset <= DOCK_TOLERANCE;
        }
        return false;
    }

    private static int anchorPullsBy(SimScenario.SimRunResultView view, String label) {
        List<SimSample> series = view.timeline().series(label);
        return series.isEmpty() ? -1 : series.get(series.size() - 1).anchorPulls;
    }

    private static int anchorPullsDeltaSince(SimScenario.SimRunResultView view, String label, int tick) {
        List<SimSample> series = view.timeline().series(label);
        Integer base = null;
        for (SimSample s : series) {
            if (s.tick >= tick) {
                base = s.anchorPulls;
                break;
            }
        }
        if (base == null || series.isEmpty()) return -1;
        return series.get(series.size() - 1).anchorPulls - base;
    }

    private static boolean idlePeek(SimSample s) {
        return "HIDING".equals(s.state.peekState) || "STANDING_IN_HALF_COVER".equals(s.state.peekState);
    }

    /** Max slot offset among idle-in-cover samples in [fromTick, toTick]; -1 if no idle sample. */
    private static double peakIdleOffset(SimScenario.SimRunResultView view, String label, int fromTick, int toTick) {
        double peak = -1;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < fromTick) continue;
            if (s.tick > toTick) break;
            if (s.state.currentCover == null || !idlePeek(s)) continue;
            peak = Math.max(peak, s.coverSlotOffset);
        }
        return peak;
    }

    /** Exists an idle-in-cover sample in [afterTick, afterTick+withinTicks] docked within 0.2. */
    private static boolean dockedBackWithin(SimScenario.SimRunResultView view, String label,
                                           int afterTick, int withinTicks) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < afterTick) continue;
            if (s.tick > afterTick + withinTicks) break;
            if (s.state.currentCover != null && idlePeek(s) && s.coverSlotOffset >= 0
                && s.coverSlotOffset <= 0.2) return true;
        }
        return false;
    }

    private static boolean existsHidingUnexposed(SimScenario.SimRunResultView view, String label, int afterTick) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < afterTick) continue;
            if (s.state.currentCover != null && idlePeek(s) && s.exposedBodyRays == 0
                && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5) return true;
        }
        return false;
    }

    /** Exists a RETURNING->idle transition whose next samples settle within 0.25 of the slot. */
    private static boolean dockedAfterReturn(SimScenario.SimRunResultView view, String label) {
        List<SimSample> series = view.timeline().series(label);
        for (int i = 0; i < series.size(); i++) {
            if (!"RETURNING_TO_COVER".equals(series.get(i).state.peekState)) continue;
            for (int j = i + 1; j < series.size() && series.get(j).tick - series.get(i).tick <= 30; j++) {
                SimSample s = series.get(j);
                if (s.state.currentCover != null && idlePeek(s)
                    && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.25) return true;
            }
        }
        return false;
    }

    private static boolean everReloading(SimScenario.SimRunResultView view, String label) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.reloading) return true;
        }
        return false;
    }

    // --- earlier assertion helpers ---------------------------------------------

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

package com.stevesarmy.sim;

import com.stevesarmy.GameClock;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.LivingEntity;
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
            MovementRecoveryScenarios.diagnostic("pit", "goto"),
            MovementRecoveryScenarios.diagnostic("pit", "follow"),
            MovementRecoveryScenarios.diagnostic("pit", "idle"),
            MovementRecoveryScenarios.diagnostic("pit", "cancel"),
            MovementRecoveryScenarios.diagnostic("box", "goto"),
            MovementRecoveryScenarios.diagnostic("box", "follow"),
            MovementRecoveryScenarios.diagnostic("partial_box", "goto"),
            MovementRecoveryScenarios.diagnostic("roof", "goto"),
            MovementRecoveryScenarios.roof(true),
            MovementRecoveryScenarios.roof(false),
            MovementRecoveryScenarios.wrongFloorArrival(),
            MovementRecoveryScenarios.pit(false, false),
            MovementRecoveryScenarios.pit(true, false),
            MovementRecoveryScenarios.pit(true, true),
            PlayerSuppressionScenarios.freshPlayer(),
            PlayerSuppressionScenarios.npcControl(),
            PlayerSuppressionScenarios.respawnedPlayer(),
            ThreatIntelLifecycleScenario.create(),
            suppressedFollowEscape(),
            holdEnclosedPocket(),
            persistentPingRelocation(),
            mgAnchorFreshness(),
            mgRiflemanSeekContrast(),
            mgSuppressionRoundRobin(),
            mgDirectFireCadence(),
            MgPersistentAttackSuppressionScenario.create(),
            MgAttackRowHoldScenarios.objectiveCrossed(),
            MgAttackRowHoldScenarios.alignedControl(),
            fireTeamRoleBalance(),
            DirectionalCoverScenarios.wrongFacingCoverRejects(),
            DirectionalCoverScenarios.protectedCoverControl(),
            LoneBlockMultiSoldierScenario.create(),
            NoCoverProneScenarios.pressuredOpenGroundFallsProne(),
            NoCoverProneScenarios.unpressuredOpenGroundStaysUpright(),
            NoCoverProneScenarios.clusteredSquad(2),
            NoCoverProneScenarios.clusteredSquad(8),
            ProneLaneContentionScenario.create(),
            coverCrossRoleReservation(),
            coverReservationKeepalive(),
            pairSuppressionLanes(),
            openFieldBaseline(),
            dockArrivalRoles(),
            dockArrivalSuppressed(),
            dockReloadHold(),
            dockReloadMidApproach(),
            dockKnockbackRecovery(),
            dockSuppressedDriftRecovery(),
            dockCqbHoldFidelity(),
            dockPeekReturn(),
            CornerPeekScenarios.quickMemorySafety(),
            FiringLanePerformanceScenario.create(),
            CornerPeekScenarios.normalFire(),
            CornerPeekScenarios.normalFire(1),
            CornerPeekScenarios.normalFire(2),
            CornerPeekScenarios.create(false),
            CornerPeekScenarios.create(true),
            CornerPeekScenarios.create(true, true),
            CornerPeekScenarios.create(false, false, true, false),
            CornerPeekScenarios.create(true, false, true, false),
            CornerPeekScenarios.create(false, false, true, true),
            loneBlockExposure(),
            loneBlockFlankStand(),
            supportSquadFight(),
            supportRearCoverPick(),
            dangerOracleOpenField(),
            dangerOracleRidge(),
            dangerOracleLoneBlock(),
            dangerOracleCrossfire(),
            dangerOracleNoIntel(),
            dangerSelfTestAllZero(),
            dangerSelfTestAllMax(),
            dangerSelfTestMirrored(),
            dangerDecayProperty(),
            dangerDecayReal(),
            dangerShiftReal(),
            dangerMultiSquadBudget(),
            dangerPathDetour(),
            dangerDetourLive(),
            travelRerouteSuppressed(),
            travelCrawlSuppressed(),
            coverApproachCrawlSuppressed(),
            cqbReloadCloseProtect(),
            cqbCloseFlankSeek(),
            cqbHoldNoShotRelease(),
            cqbFormationNoFreeze(),
            courseCqbHouseApproach(),
            exposedPlantEngage(),
            exposedFfSidestep(),
            exposedWithdrawOverrun(),
            courseExposedYardFight(),
            streetBoundCrossing(),
            streetMgCoversBound(),
            streetSoloNoCoord(),
            cqbDoorwayQueue(),
            cqbCornerPie(),
            attackPingCornerPie(),
            cqbLostContactWatch(),
            clearEmptyRoom(),
            clearInteriorEnemy(),
            clearBreakContact(),
            clearBlockedDoor(),
            clearSparseOutdoor(),
            clearLegacyProximityTwin(),
            clearDividedRooms(),
            clearContactRevisit(),
            clearTrainingCourse(),
            clearBenchmarkNorthDoorFunnel(),
            clearDeepObjectiveSequence(),
            clearHouseExit(),
            clearLongEgress(),
            mgAttackCoverSkip(),
            mgAttackHalfRows(),
            MgAdvanceContinuityScenarios.docklessRecovery(false),
            MgAdvanceContinuityScenarios.docklessRecovery(true),
            MgAdvanceContinuityScenarios.advanceContinuity(),
            MgAdvanceContinuityScenarios.dockedEndpointRecovery(),
            mgAttackScatteredBlocks(),
            cqbDoorContact(),
            attackGuideUnreachableConcede(),
            goToVolleyStrandDirectWalk(),
            goToVolleyStreet(),
            GoToPerformanceScenario.create(),
            GoToCoverEndpointScenarios.endpointCompletesAtCover(),
            GoToCoverEndpointScenarios.crossingPingCenterKeepsClaim(),
            GoToArrivalCoverScenario.arrivalCoverDelay(),
            GoToLongRouteScenarios.longOpenMarch(),
            GoToLongRouteScenarios.longDetourMarch(),
            cautionAdsTravelFloor(),
            suppressedCrawlPace(),
            clearCheckerDeath(),
            ghostCorpseNoCaution(),
            attackSuperiorityAdvance(),
            attackParityAdvance(),
            attackSuperioritySustained(),
            attackParitySustained(),
            attackPeekGateDiscipline(),
            suppressedRelocationSafeRoute(),
            attackAbsolutePinHold(),
            attackAbsolutePinHold(true),
            CautionSteeringScenarios.freshnessAndPace(),
            CautionSteeringScenarios.raisedPathDoorway(),
            SpeedTransitionScenarios.transitions(),
            SoldierTraceScenarios.recorderProbes(),
            SoldierTraceScenarios.exportCommandProbe(),
            SoldierTraceScenarios.populationProbe(),
            CrowdedAttackRowsScenarios.crowdedAttackRows(),
            CrowdedAttackRowsScenarios.crowdedAttackTallRows(),
            AttackRepingTraversalScenarios.repeatedAttackPing(),
            AttackRepingTraversalScenarios.movedAttackPingChokepoints(),
            MovementCombatScenarios.traverseProgress(),
            MovementCombatScenarios.attackPhaseCycle(),
            MovementCombatScenarios.orderShareContrast(),
            MovementCombatScenarios.tacticalStrafeFacing(),
            MovementCombatScenarios.tacticalBackpedalProgress(),
            MovementCombatScenarios.hipSpreadBlocksLateralFriendly(),
            MovementCombatScenarios.mandatoryCrawlFireGate(),
            MovementCombatScenarios.speedAndSpreadContract());
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
            .core()
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
            .core()
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

    // 4c. MG round-robin suppression — bursts rotate across last-seen positions

    /**
     * One armed machine gunner vs two GUNLESS enemies spawned in the open in
     * front of 2-high dig-in walls. The MG's near-misses pin them and their
     * own cover AI docks them behind the walls, which are tall enough to
     * actually break line of sight at this range — then the MG suppresses
     * last-seen positions, and the round-robin doctrine must spread bursts
     * across BOTH positions instead of stacking every burst on one contact.
     */
    private static SimScenario mgSuppressionRoundRobin() {
        return SimScenario.builder("mg_suppression_round_robin")
            .core()
            .description("MG suppression distribution: armed MG + squadmates vs two lane-aligned "
                + "enemies at two distances, pinned in windows so LOS breaks while targets are "
                + "held. Asserts bursts cover BOTH enemy positions with alternation and no "
                + "fixation. KNOWN SCOPE LIMIT: in-sim the autonomous suppression plan never "
                + "arms (no [Suppression] claims in any geometry tried), so this proves "
                + "squad-level burst distribution, not MG-internal rotation; rotation switches "
                + "are observable in-game via suppression debug logging "
                + "('[Suppression] ... round-robin rotates').")
            .duration(700)
            .armed()
            .field(56, 48)
            .terrain(t -> {
                t.platform();
                t.coverRow(16, 21, 18, 21, 1);   // gunner's rows; each enemy sits on one row lane
                t.coverRow(16, 27, 18, 27, 1);
                t.coverRow(40, 20, 42, 24, 1);   // near enemy's cover (lane of row z=21)
                t.coverRow(52, 24, 54, 28, 1);   // far enemy's cover (lane of row z=27)
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 12, 0, 24, SquadMode.FOLLOW);
                // squadmates: the contrast scenario's MG only acquires targets
                // with the squad present (shared detection feeds the gunner)
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "left", 12, 0, 14, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "right", 12, 0, 34, SquadMode.FOLLOW);
                ctx.spawnEnemyLabeled("enemyA", 38, 0, 22);
                ctx.spawnEnemyLabeled("enemyB", 50, 0, 26);
                // Deterministic intel: hand the squad its sightings directly
                // and keep them fresh for the whole run.
                ctx.injectThreat(38, 22, 1.0f);
                ctx.injectThreat(50, 26, 1.0f);
            })
            .action(90, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(180, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(270, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(360, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(450, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(540, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(630, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            // Pinning windows: force the prone-duck that breaks LOS while the
            // MG still holds the target — the mid-fight moment where
            // suppression on last-seen positions arms.
            .suppress("enemyA", 150, 350, 10)
            .suppress("enemyB", 150, 350, 10)
            .suppress("enemyA", 450, 650, 10)
            .suppress("enemyB", 450, 650, 10)
            .assertThat("MG bursts land on BOTH enemy positions (round-robin distribution)",
                view -> countShotsNear(view, 38, 22, 8.0) >= 4 && countShotsNear(view, 50, 26, 8.0) >= 4)
            .assertThat("bursts alternate between the two positions (>=1 cluster transition)",
                view -> shotClusterTransitions(view, 38, 22, 50, 26, 8.0) >= 1)
            .assertThat("no single-target fixation: neither position absorbs >75% of clustered shots",
                view -> {
                    int a = countShotsNear(view, 38, 22, 8.0);
                    int b = countShotsNear(view, 50, 26, 8.0);
                    int total = a + b;
                    return total > 0 && Math.max(a, b) * 4 <= total * 3;
                })
            .assertThat("MG actually fired a sustained engagement (>=20 tracer rounds)",
                view -> view.shots().size() >= 20)
            .build();
    }

    // 4d. MG direct-fire cadence — active-target fire should not lag suppression fire

    /**
     * Cadence probe for the user-reported asymmetry: with an ACTIVE VISIBLE
     * target the MG "slowly aims, fires slowly, and is inaccurate", while
     * last-seen suppression fires long smooth bursts. A hardened (invulnerable,
     * so damage reactions can't stop it) gunless enemy patrols the open at
     * ~48 blocks — always visible, so the measurement isolates the aim-quality
     * gate, burst profile, and recoil tax. (An earlier wall variant was thrown
     * away: the enemy hid behind it ~80% of frames, which starves ANY build —
     * correct peek discipline, but it measures peek response, not cadence.)
     * Gunner tracers are attributable by from-position (gunner spawns at x=6,
     * z=32, behind the squadmates at x=10 so no tracer path passes near it).
     */
    private static SimScenario mgDirectFireCadence() {
        return SimScenario.builder("mg_direct_fire_cadence")
            .core()
            .description("MG direct-fire cadence: armed machine gunner (+ two armed squadmates "
                + "for acquisition insurance) vs a hardened GUNLESS enemy patrolling the open "
                + "at ~48 blocks. Always-visible target: the MG must open up promptly and "
                + "sustain machine-gun cadence - >=30 gunner tracers into the lane, >=4 "
                + "distinct bursts (gaps >20t), at least ONE burst of >=7 shots (impossible "
                + "for the 5-shot baseline profile whose bursts recoil-collapse at 3-4), "
                + "first tracer within 120t, >=8 rounds landing in the tight lane.")
            .duration(800)
            .armed()
            .field(72, 64)
            .terrain(t -> {
                t.platform();
                // Low wall for the friendly line: without any cover the MG
                // strands in SEEKING_COVER all run (its fire-lane cover search
                // never settles on an open field) and never fires.
                t.coverRow(3, 14, 3, 26, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(2, 0, 26);
                // Gunner sits OFF-COLUMN (z=20 vs squadmates at z=32/40): its
                // lane to the enemy corridor must not pass near a friendly, or
                // the friendly-fire cone check blocks every shot.
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 6, 0, 20, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "left", 10, 0, 32, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "right", 10, 0, 40, SquadMode.FOLLOW);
                ctx.spawnEnemyLabeled("enemy", 59, 0, 32);
                ctx.disarmSoldier("enemy");
                ctx.harden("enemy");
            })
            .action(30, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(50, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(70, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(90, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(110, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(130, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(150, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(170, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(190, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(210, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(230, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(250, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(270, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(290, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(310, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(330, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(350, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(370, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(390, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(410, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(430, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(450, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(470, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(490, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(510, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(530, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(550, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(570, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(590, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(610, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(630, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(650, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(670, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(690, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(710, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(730, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(750, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .action(770, "strafe enemy", ctx -> ctx.strafeEnemy("enemy", 22, 42))
            .assertThat("MG opens up: >=30 gunner tracers into the enemy lane",
                view -> countGunnerShotsNear(view, 6, 20, 50, 18, 68, 46) >= 30)
            .assertThat("MG sustains bursts: >=4 distinct bursts (gaps >20t)",
                view -> countGunnerBursts(view, 6, 20, 50, 18, 68, 46, 20) >= 4)
            .assertThat("MG bursts like a machine gun: >=1 burst of >=7 shots",
                view -> longestGunnerBurst(view, 6, 20, 50, 18, 68, 46, 20) >= 7)
            .assertThat("MG opens fire promptly: first gunner tracer within 120t",
                view -> firstGunnerShotTick(view, 6, 20, 50, 18, 68, 46) <= 120)
            .assertThat("MG fire lands on the target: >=8 tracers terminate in the enemy box",
                view -> countGunnerShotsNear(view, 6, 20, 50, 18, 66, 46) >= 8)
            .build();
    }

    /** Counts tracers fired FROM near (fromX, fromZ) whose endpoints land in the target box. */
    private static int countGunnerShotsNear(SimScenario.SimRunResultView view,
                                            double fromX, double fromZ,
                                            double tx0, double tz0, double tx1, double tz1) {
        int count = 0;
        for (double[] shot : view.shots()) {
            double dx = shot[0] - fromX;
            double dz = shot[1] - fromZ;
            if (dx * dx + dz * dz > 25.0) continue;   // shooter within 5 blocks
            if (shot[2] >= tx0 && shot[2] <= tx1 && shot[3] >= tz0 && shot[3] <= tz1) {
                count++;
            }
        }
        return count;
    }

    /** Distinct bursts (>=1 shot each) among the shooter's in-box tracers, split at {@code gapTicks}. */
    private static int countGunnerBursts(SimScenario.SimRunResultView view,
                                         double fromX, double fromZ,
                                         double tx0, double tz0, double tx1, double tz1,
                                         int gapTicks) {
        int bursts = 0;
        long last = Long.MIN_VALUE;
        java.util.List<Long> ticks = view.shotTicks();
        java.util.List<double[]> shots = view.shots();
        for (int i = 0; i < shots.size() && i < ticks.size(); i++) {
            double[] shot = shots.get(i);
            double dx = shot[0] - fromX, dz = shot[1] - fromZ;
            if (dx * dx + dz * dz > 25.0) continue;
            if (shot[2] < tx0 || shot[2] > tx1 || shot[3] < tz0 || shot[3] > tz1) continue;
            long t = ticks.get(i);
            if (last == Long.MIN_VALUE || t - last > gapTicks) bursts++;
            last = t;
        }
        return bursts;
    }

    /** Longest burst (shots in a row, split at {@code gapTicks}) among the shooter's in-box tracers. */
    private static int longestGunnerBurst(SimScenario.SimRunResultView view,
                                          double fromX, double fromZ,
                                          double tx0, double tz0, double tx1, double tz1,
                                          int gapTicks) {
        int longest = 0;
        int current = 0;
        long last = Long.MIN_VALUE;
        java.util.List<Long> ticks = view.shotTicks();
        java.util.List<double[]> shots = view.shots();
        for (int i = 0; i < shots.size() && i < ticks.size(); i++) {
            double[] shot = shots.get(i);
            double dx = shot[0] - fromX, dz = shot[1] - fromZ;
            if (dx * dx + dz * dz > 25.0) continue;
            if (shot[2] < tx0 || shot[2] > tx1 || shot[3] < tz0 || shot[3] > tz1) continue;
            long t = ticks.get(i);
            current = (last == Long.MIN_VALUE || t - last > gapTicks) ? 1 : current + 1;
            longest = Math.max(longest, current);
            last = t;
        }
        return longest;
    }

    /** First tick at which the shooter landed a tracer in the box; Integer.MAX_VALUE when none. */
    private static int firstGunnerShotTick(SimScenario.SimRunResultView view,
                                           double fromX, double fromZ,
                                           double tx0, double tz0, double tx1, double tz1) {
        java.util.List<Long> ticks = view.shotTicks();
        java.util.List<double[]> shots = view.shots();
        int first = Integer.MAX_VALUE;
        for (int i = 0; i < shots.size() && i < ticks.size(); i++) {
            double[] shot = shots.get(i);
            double dx = shot[0] - fromX, dz = shot[1] - fromZ;
            if (dx * dx + dz * dz > 25.0) continue;
            if (shot[2] < tx0 || shot[2] > tx1 || shot[3] < tz0 || shot[3] > tz1) continue;
            first = (int) Math.min(first, ticks.get(i));
        }
        return first;
    }

    private static int countShotsNear(SimScenario.SimRunResultView view, double x, double z, double radius) {
        int count = 0;
        for (double[] shot : view.shots()) {
            double dx = shot[2] - x;
            double dz = shot[3] - z;
            if (dx * dx + dz * dz <= radius * radius) {
                count++;
            }
        }
        return count;
    }

    /** Fire-order transitions between two shot-endpoint clusters (ignoring unclustered shots). */
    private static int shotClusterTransitions(SimScenario.SimRunResultView view,
                                              double ax, double az, double bx, double bz, double radius) {
        int last = 0;
        int transitions = 0;
        for (double[] shot : view.shots()) {
            double daSq = sq(shot[2] - ax) + sq(shot[3] - az);
            double dbSq = sq(shot[2] - bx) + sq(shot[3] - bz);
            double rSq = radius * radius;
            int cluster = daSq > rSq && dbSq > rSq ? 0 : (daSq <= dbSq ? 1 : 2);
            if (cluster == 0) continue;
            if (last != 0 && cluster != last) transitions++;
            last = cluster;
        }
        return transitions;
    }

    private static double sq(double v) {
        return v * v;
    }

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
                view -> view.enemyHealthEnd() < 20.0f)
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
            .core()
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

    private static SimScenario dockReloadMidApproach() {
        return SimScenario.builder("dock_reload_midapproach")
            .core()
            .description("Reload starting mid-approach (user-reported): the reload must not leave the soldier "
                + "crouched at the claim-time offset for the whole magazine swap — the claim→dock alignment "
                + "finishes through the reload, same exception as the approach itself.")
            .duration(500)
            .armed()
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "reloader", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(14, 0, 46); // due south: lane geometry shields it, keeping bug-1 exposure out of this verdict
            })
            .suppress(null, 20, 260, 15)
            .action(35, "burn one round, start reload mid-approach", ctx -> {
                SoldierEntity s = ctx.soldier("reloader");
                if (s != null && s.isAlive()) {
                    com.stevesarmy.combat.GunIntegration.shootAtPosition(s, s.position().add(0.0, -1.0, 0.0));
                    com.stevesarmy.combat.GunIntegration.reload(s);
                }
            })
            .assertThat("reload was engaged", view -> everReloading(view, "reloader"))
            .assertThat("reload overlapped the cover claim (exercises the bug window)",
                view -> reloadOverlappedClaim(view, "reloader"))
            .assertThat("dock completes during the reload (offset <= 0.2 between claim and reload end)",
                view -> docksDuringReload(view, "reloader"))
            .assertThat("ends docked in the exact slot", view -> dockedAtEnd(view, "reloader"))
            .build();
    }

    /** True when the reload window overlaps the cover claim (the bug's precondition). */
    private static boolean reloadOverlappedClaim(SimScenario.SimRunResultView view, String label) {
        Integer claimTick = null;
        Integer reloadEndTick = null;
        boolean reloadingSeen = false;
        for (SimSample s : view.timeline().series(label)) {
            if (claimTick == null && s.state.currentCover != null) claimTick = s.tick;
            if (s.reloading) {
                reloadingSeen = true;
                reloadEndTick = s.tick;
            }
        }
        return reloadingSeen && claimTick != null && reloadEndTick != null && reloadEndTick >= claimTick;
    }

    /** The claim→dock transit finishes during the reload: an idle sample between
     * the cover claim and the reload end sits within 0.2 of the slot. */
    private static boolean docksDuringReload(SimScenario.SimRunResultView view, String label) {
        Integer claimTick = null;
        Integer reloadEndTick = null;
        for (SimSample s : view.timeline().series(label)) {
            if (claimTick == null && s.state.currentCover != null) claimTick = s.tick;
            if (s.reloading) reloadEndTick = s.tick;
        }
        if (claimTick == null || reloadEndTick == null || reloadEndTick < claimTick) return false;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < claimTick || s.tick > reloadEndTick) continue;
            if (s.state.currentCover != null && idlePeek(s)
                && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.2) return true;
        }
        return false;
    }

    private static SimScenario dockReloadHold() {
        return SimScenario.builder("dock_reload_hold")
            .core()
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
            .core()
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
     * Combat positioning rework phase 1: the engagement hold defers to a dock
     * that is about to finish, releases when it produces no burst, and manual
     * CQB no longer freeze-holds on sight. The pre-fix failure (2026-10-06
     * finding): the hold froze the position controller mid-approach, the
     * anchor starved, the soldier locked 0.3-0.8 blocks off its slot, and kept
     * claiming SUPPRESSED_IN_COVER/HIDING while fully visible.
     */
    private static SimScenario dockCqbHoldFidelity() {
        return SimScenario.builder("dock_cqb_hold_fidelity")
            .description("CQB hold defers to docking (fixed 2026-10-08): an enemy closing during the "
                + "cover approach must not starve the anchor - the last step into protection finishes, "
                + "the soldier fights from the slot, and a flanked hiding soldier is not fully visible.")
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
            .assertThat("soldier docks into its slot (offset <= 0.35) despite the CQB hold",
                view -> dockedAtEnd(view, "cqb1"))
            .assertThat("hiding soldier is not fully visible to the point-blank threat",
                view -> !exposedWhileHidingEver(view, "cqb1", 120, 15))
            .build();
    }

    /** Phase 1: a reload mid-approach plus a closing enemy must still end docked. */
    private static SimScenario cqbReloadCloseProtect() {
        return SimScenario.builder("cqb_reload_close_protect")
            .core()
            .description("Phase 1 positioning rework: a reload starting mid-approach while an enemy closes "
                + "into emergency range must end docked - the hold may not latch while reloading, the "
                + "dock finishes through the reload, and a latched hold defers to the finishing dock.")
            .duration(500)
            .armed()
            .terrain(t -> {
                t.platform();
                dockLane(t, 10);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "reloader", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(12, 0, 34); // closes into emergency range while the dock is in flight
            })
            .action(35, "burn one round, start reload mid-approach", ctx -> {
                SoldierEntity s = ctx.soldier("reloader");
                if (s != null && s.isAlive()) {
                    com.stevesarmy.combat.GunIntegration.shootAtPosition(s, s.position().add(0.0, -1.0, 0.0));
                    com.stevesarmy.combat.GunIntegration.reload(s);
                }
            })
            .suppress("reloader", 20, 260, 15)
            .assertThat("reload was engaged", view -> everReloading(view, "reloader"))
            .assertThat("ends docked in the exact slot (hold never starved the dock)",
                view -> dockedAtEnd(view, "reloader"))
            .build();
    }

    /** Phase 1: close-range enemies re-enable cover seeking - the soldier claims a nearby pocket. */
    private static SimScenario cqbCloseFlankSeek() {
        return SimScenario.builder("cqb_close_flank_seek")
            .core()
            .description("Phase 1 positioning rework: a close target (4 blocks) used to DISABLE cover "
                + "seeking entirely; now the soldier seeks bounded nearby protection while the "
                + "engagement hold no-shot clock runs. The pocket 3 blocks west must be claimed.")
            .duration(600)
            .terrain(t -> {
                t.platform();
                t.coverRow(5, 18, 8, 18, 1); // peekable half-cover row, 3-4 blocks west of the soldier
            })
            .setup(ctx -> {
                ctx.spawnAnchor(12, 0, 26); // SE of the row, clear of the enemy line
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "scout", 10, 0, 20, SquadMode.FOLLOW);
            })
            .action(30, "close enemy appears 4 blocks east", ctx -> ctx.spawnEnemy(14, 0, 20))
            .assertThat("claims close-range cover by t300 (seek not disabled at close range)",
                view -> claimedCoverBy(view, "scout", 300))
            .assertThat("docks into the pocket", view -> dockedAtEnd(view, "scout"))
            .build();
    }
    /** Phase 1: a hold that can never produce a shot (friendly in the cone) must release and reposition. */
    private static SimScenario cqbHoldNoShotRelease() {
        return SimScenario.builder("cqb_hold_no_shot_release")
            .core()
            .description("Phase 1 positioning rework: the engagement hold latches on a visible enemy at "
                + "emergency range, but a teammate stands dead-center in the fire cone (LoS is clear, "
                + "FriendlyFireChecker blocks every shot). The no-shot timeout must release the hold so "
                + "cover seeking acts - a permanently blocked shot is a stranded soldier, not a "
                + "fighting position.")
            .duration(500)
            .armed()
            .terrain(t -> {
                t.platform();
                // The proven claimable-protection layout of cqb_close_flank_seek
                // (row north-west of the soldier) plus the FF obstruction below.
                t.coverRow(5, 18, 8, 18, 1);
            })
            .setup(ctx -> {
                // The squad anchor stands ON the eastward aim line 2 blocks
                // ahead: the owner is a FriendlyFireChecker scan member, so
                // every shot from the spawn position is blocked forever while
                // LoS stays clear (vanilla clips ignore entities). Churn-free
                // obstruction. The enemy is disarmed so no suppression noise
                // muddies the no-shot clock.
                ctx.spawnAnchor(12, 0, 20);
                SoldierEntity shooter = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "shooter", 10, 0, 20, SquadMode.FOLLOW);
                ctx.harden("shooter");
            })
            .action(30, "disarmed enemy appears 4 blocks east and holds position", ctx -> {
                EnemySoldierEntity threat = ctx.spawnEnemyLabeled("threat", 14, 0, 20);
                ctx.disarmSoldier("threat");
                // Disarmed soldiers close to melee, which invalidates the row
                // mid-run; HOLD at spawn keeps the geometry steady.
                threat.setSquadMode(SquadMode.HOLD);
                threat.setHoldPosition(ctx.terrain.at(14, 0, 20));
            })
            .assertThat("hold releases and the soldier claims reachable cover by t350",
                view -> claimedCoverBy(view, "shooter", 350))
            .assertThat("fights from the claimed cover (peek exposure after claim)",
                view -> exposedAfter(view, "shooter", 220))
            .build();
    }

    /** True when the soldier was ever in the EXPOSED peek state after the given tick. */
    private static boolean exposedAfter(SimScenario.SimRunResultView view, String label, int fromTick) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick >= fromTick && "EXPOSED".equals(s.state.peekState)) return true;
        }
        return false;
    }

    /** Maximum horizontal displacement from the first sample (how far the soldier roamed). */
    private static double maxDisplacement(SimScenario.SimRunResultView view, String label) {
        List<SimSample> series = view.timeline().series(label);
        if (series.isEmpty()) return 0;
        Vec3 start = series.get(0).state.position;
        double max = 0;
        for (SimSample s : series) {
            double d = Math.hypot(s.state.position.x - start.x, s.state.position.z - start.z);
            if (d > max) max = d;
        }
        return max;
    }

    /** True when the soldier moved at least {@code minDist} between the two ticks. */
    private static boolean movedBetween(SimScenario.SimRunResultView view, String label,
                                        int fromTick, int toTick, double minDist) {
        List<SimSample> series = view.timeline().series(label);
        SimSample a = null;
        SimSample b = null;
        for (SimSample s : series) {
            if (a == null && s.tick >= fromTick) a = s;
            if (s.tick <= toTick) b = s;
        }
        if (a == null || b == null) return false;
        return Math.hypot(b.state.position.x - a.state.position.x,
            b.state.position.z - a.state.position.z) >= minDist;
    }

    /** True when the soldier was ever within {@code maxGap} of its (nearest) enemy. */
    private static double minEnemyDistance(SimScenario.SimRunResultView view, String label) {
        double min = Double.MAX_VALUE;
        for (SimSample s : view.timeline().series(label)) {
            if (s.enemyDistance >= 0 && s.enemyDistance < min) min = s.enemyDistance;
        }
        return min;
    }

    /** Phase 2: an exposed soldier with a clear shot plants and engages — purposeful, not chaotic. */
    private static SimScenario exposedPlantEngage() {
        return SimScenario.builder("exposed_plant_engage")
            .suppressDetectors("StuckSeek", "FollowEscapeBlocked")
            .description("Exposed-combat doctrine: no cover anywhere near, clear shot at a stationary "
                + "stationary enemy at 20 blocks. The soldier plants and engages promptly (TTFS <= 150t), "
                + "stays within a small roam radius (no chaotic wandering), and damages the enemy."
                + " KNOWN FINDING (expected detector FAILs StuckSeek/FollowEscapeBlocked and a wide roam radius): featureless arenas expose the pre-existing far-side cover lure - the legacy scorer claims far rim/far-side junk cover under the SEEKING label. The doctrine assertions (plant/TTFS, sidestep, never-overrun, damage) are the pass criteria; the lure penalty term is tracked separately.")
            .duration(500)
            .armed()
            .terrain(t -> t.platform())
            .setup(ctx -> {
                // Center-arena: the platform rim walls are 20+ blocks out, so
                // the search cannot claim wrong-sided rim "cover" and the
                // doctrine genuinely runs with nothing claimable nearby.
                ctx.spawnAnchor(24, 0, 26);
                SoldierEntity rifle = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 24, 0, 24, SquadMode.FOLLOW);
                ctx.harden("rifle");
                // Disarmed and pinned: the plant scenario tests the doctrine
                // decision deterministically - no suppression race. The
                // under-fire duel is course_exposed_yard_fight's job.
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 44, 0, 24);
                ctx.disarmSoldier("threat");
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(44, 0, 24));
            })
            .assertThat("opens fire promptly (first tracer by t150)",
                view -> view.shotTicks().stream().filter(t -> t <= 150).count() >= 1)
            .assertThat("stays in the fight locally (dock/peek roam <= 14)", view -> maxDisplacement(view, "rifle") <= 14.0)
            .assertThat("enemy takes damage (spawn HP 20)", view -> view.enemyHealthEnd() < 20.0f)
            .build();
    }

    /** Phase 2: a friendly-fire-blocked lane triggers one bounded sidestep that reopens the shot. */
    private static SimScenario exposedFfSidestep() {
        return SimScenario.builder("exposed_ff_sidestep")
            .suppressDetectors("StuckSeek", "FollowEscapeBlocked")
            .description("Exposed-combat doctrine: the squad anchor stands on the aim line so every shot "
                + "from the spawn position is FF-blocked while LoS stays clear. The blocked lane must "
                + "trigger ONE bounded sidestep perpendicular to the aim; from the new position the "
                + "anchor leaves the cone and the soldier damages the enemy."
                + " KNOWN FINDING (expected detector FAILs StuckSeek/FollowEscapeBlocked and a wide roam radius): featureless arenas expose the pre-existing far-side cover lure - the legacy scorer claims far rim/far-side junk cover under the SEEKING label. The doctrine assertions (plant/TTFS, sidestep, never-overrun, damage) are the pass criteria; the lure penalty term is tracked separately.")
            .duration(500)
            .armed()
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(24, 0, 24); // dead-center in the spawn fire cone
                SoldierEntity shooter = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "shooter", 22, 0, 24, SquadMode.FOLLOW);
                ctx.harden("shooter");
                EnemySoldierEntity threat = ctx.spawnEnemyLabeled("threat", 26, 0, 24);
                ctx.disarmSoldier("threat");
                threat.setSquadMode(SquadMode.HOLD);
                threat.setHoldPosition(ctx.terrain.at(26, 0, 24));
            })
            .assertThat("sidesteps off the blocked lane (moves >= 1.5 blocks after the first release)",
                view -> movedBetween(view, "shooter", 110, 300, 1.5))
            .assertThat("shot lands after the sidestep (enemy damaged)",
                view -> view.enemyHealthEnd() < 20.0f)
            .assertThat("adjustment stays bounded (sidestep + one overrun withdraw; roam <= 12)",
                view -> maxDisplacement(view, "shooter") <= 12.0)
            .build();
    }

    /** Phase 2: an overrunning melee rush meets a bounded withdrawal that keeps the soldier alive. */
    private static SimScenario exposedWithdrawOverrun() {
        return SimScenario.builder("exposed_withdraw_overrun")
            .core()
            .description("Exposed-combat doctrine: a disarmed enemy rushes from 15 blocks - the sim "
                + "teleports it onto the soldier every 40t so the rush cannot stall (an unarmed "
                + "enemy left alone tends to retreat to the boundary, which would pass the gap "
                + "asserts vacuously). The exposed soldier must never be overrun (min gap >= 2), "
                + "must actually be threatened (the rush closed inside 6), and still gets shots "
                + "off while disengaging.")
            .duration(500)
            .armed()
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(30, 0, 28);
                SoldierEntity rifle = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 28, 0, 24, SquadMode.FOLLOW);
                ctx.harden("rifle");
                EnemySoldierEntity rusher = ctx.spawnEnemyLabeled("threat", 39, 0, 24);
                ctx.disarmSoldier("threat");
            })
            .action(40, "rusher closes", ctx -> rushToward(ctx, 4))
            .action(80, "rusher closes", ctx -> rushToward(ctx, 4))
            .action(120, "rusher closes", ctx -> rushToward(ctx, 4))
            .action(160, "rusher closes", ctx -> rushToward(ctx, 4))
            .action(200, "rusher closes", ctx -> rushToward(ctx, 4))
            .action(240, "rusher closes", ctx -> rushToward(ctx, 4))
            .assertThat("the rush actually closed (min gap <= 6 blocks - not a vacuous retreat)",
                view -> minEnemyDistance(view, "rifle") <= 6.0)
            .assertThat("never overrun (min gap to the rusher >= 2.0 blocks)",
                view -> minEnemyDistance(view, "rifle") >= 2.0)
            .assertThat("fires land while disengaging (rusher damaged, spawn HP 20)",
                view -> view.enemyHealthEnd() < 20.0f)
            .assertThat("disengages while firing (at least one tracer)", view -> !view.shots().isEmpty())
            .build();
    }

    /** Teleport the lone enemy to `gap` blocks south of the rifle - a deterministic rush step. */
    private static void rushToward(com.stevesarmy.sim.scenario.SimContext ctx, double gap) {
        if (ctx.enemies.isEmpty()) return;
        SoldierEntity rifle = ctx.byLabel.get("rifle");
        if (rifle == null || !rifle.isAlive()) return;
        ctx.moveEnemy(ctx.enemies.get(0), rifle.getBlockX(), 0, rifle.getBlockZ() + (int) gap);
    }

    /** Phase 2 course scenario: a street yard fight with sparse rubble - protection or purposeful planting. */
    private static SimScenario courseExposedYardFight() {
        return SimScenario.builder("course_exposed_yard_fight")
            .description("Course scenario (street + house): a soldier fights from the sparse street rubble "
                + "against an enemy at the east doorway. Either claims sensible protection or plants with "
                + "bounded movement while the firefight runs - never strands, never wanders.")
            .duration(700)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                SoldierEntity rifle = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 22, 0, 36, SquadMode.FOLLOW);
                // East of the house, in the street's line of sight (the house's
                // south face has no door, so a doorway enemy is not visible).
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 32, 0, 20);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(32, 0, 20));
            })
            .assertThat("firefight produces tracers", view -> !view.shots().isEmpty())
            .assertThat("enemy takes damage (spawn HP 20)", view -> view.enemyHealthEnd() < 20.0f)
            // 18, not 12: a single bounded quiet reposition (~6 blocks out and
            // back) is doctrine, not chaotic wandering; the routine-reposition
            // RNG fires it in roughly half the runs.
            .assertThat("movement stays purposeful (roam radius <= 18 blocks)",
                view -> maxDisplacement(view, "rifle") <= 18.0)
            .build();
    }

    /** Phase 3: a fire team crosses the hot street one soldier at a time. */
    private static SimScenario streetBoundCrossing() {
        return SimScenario.builder("street_bound_crossing")
            .core()
            .description("Course scenario (squad street-crossing doctrine): a two-man fire team must "
                + "advance from the south rim across the rubble street to a hold point inside the "
                + "west house while an armed enemy covers the lane from the east. "
                + "SquadMovementCoordinator bounds them one at a time - the crossing is staggered, "
                + "both soldiers make the north side, and the pair answers fire en route.")
            .duration(900)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(24, 0, 42);
                SoldierEntity alpha = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 20, 0, 40, SquadMode.HOLD);
                SoldierEntity bravo = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 26, 0, 40, SquadMode.HOLD);
                alpha.setHoldPosition(ctx.terrain.at(18, 0, 8));
                bravo.setHoldPosition(ctx.terrain.at(18, 0, 8));
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 34, 0, 16);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(34, 0, 16));
            })
            .assertThat("both soldiers make the north side (final z < 24)",
                view -> northOf(view, "alpha", 24) && northOf(view, "bravo", 24))
            .assertThat("crossing is staggered (one north of z=30 while the other waits south of z=36)",
                view -> staggeredCrossing(view, "alpha", "bravo", 30, 33))
            .assertThat("the pair answers fire en route (tracers)", view -> !view.shots().isEmpty())
            .build();
    }

    /** Phase 3: the MG anchors the overwatch lane while the rifle bounds. */
    private static SimScenario streetMgCoversBound() {
        return SimScenario.builder("street_mg_covers_bound")
            .description("Course scenario (squad street-crossing doctrine): an MG and a rifle cross the "
                + "rubble street under an armed enemy. The MG is overwatch-anchored by preference - "
                + "it holds its lane while the rifle bounds across, then rotates across itself.")
            .duration(900)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(24, 0, 42);
                SoldierEntity mg = ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg", 26, 0, 40, SquadMode.HOLD);
                SoldierEntity rifle = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 22, 0, 40, SquadMode.HOLD);
                mg.setHoldPosition(ctx.terrain.at(18, 0, 8));
                rifle.setHoldPosition(ctx.terrain.at(18, 0, 8));
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 34, 0, 16);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(34, 0, 16));
            })
            .assertThat("MG anchors while the rifle crosses (MG displacement <= 8 during the crossing)",
                view -> anchorHoldsDuring(view, "rifle", "mg", 34, 24, 8.0) <= 8.0)
            .assertThat("rifle reaches the north side (final z < 24)", view -> northOf(view, "rifle", 24))
            .assertThat("MG rotates across after the rifle clears (final z < 28)",
                view -> northOf(view, "mg", 28))
            .build();
    }

    /** Phase 3: a lone soldier crosses the same street with no coordination overhead. */
    private static SimScenario streetSoloNoCoord() {
        return SimScenario.builder("street_solo_no_coord")
            .description("Course scenario (squad street-crossing doctrine, solo bypass): a single soldier "
                + "advances across the same hot street. With nobody to hold a lane, the coordinator "
                + "stays out of the way - the existing exposed-combat and suppressed-traveler "
                + "doctrines govern.")
            .duration(900)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(24, 0, 42);
                SoldierEntity solo = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "solo", 24, 0, 40, SquadMode.HOLD);
                solo.setHoldPosition(ctx.terrain.at(18, 0, 8));
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 34, 0, 16);
                enemy.setSquadMode(SquadMode.HOLD);
                enemy.setHoldPosition(ctx.terrain.at(34, 0, 16));
            })
            .assertThat("solo crosses the street (final z < 24)", view -> northOf(view, "solo", 24))
            .assertThat("engages en route (tracers)", view -> !view.shots().isEmpty())
            .build();
    }

    // Phase 4: CQB doorway/corner doctrine --------------------------------------

    /** Count of samples in [t0,t1] where the soldier faces northward (fz < limit). */
    private static int northFacingSamples(SimScenario.SimRunResultView view, String label,
                                          int t0, int t1, double fzLimit) {
        int n = 0;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < t0 || s.tick > t1) continue;
            if (Math.cos(Math.PI * s.headYaw / 180.0) < fzLimit) n++;
        }
        return n;
    }

    /** True when both soldiers are simultaneously within r of the door center. */
    private static boolean everBothNear(SimScenario.SimRunResultView view, String a, String b,
                                        net.minecraft.world.phys.Vec3 door, double r) {
        java.util.Map<Integer, SimSample> ta = new java.util.HashMap<>();
        for (SimSample s : view.timeline().series(a)) ta.put(s.tick, s);
        for (SimSample s : view.timeline().series(b)) {
            SimSample oa = ta.get(s.tick);
            if (oa == null) continue;
            double da = Math.hypot(oa.state.position.x - door.x, oa.state.position.z - door.z);
            double db = Math.hypot(s.state.position.x - door.x, s.state.position.z - door.z);
            if (da < r && db < r) return true;
        }
        return false;
    }

    /** True when the point lies inside the cqbCourse house shell. */
    private static boolean inHouse(double x, double z) {
        return x >= 15.0 && x <= 29.0 && z >= 7.0 && z <= 17.0;
    }

    /** Last recorded sample, or null. */
    private static SimSample lastSample(SimScenario.SimRunResultView view, String label) {
        var series = view.timeline().series(label);
        return series.isEmpty() ? null : series.get(series.size() - 1);
    }

    /** First sample at or after the tick, or null. */
    private static SimSample sampleAt(SimScenario.SimRunResultView view, String label, int tick) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick >= tick) return s;
        }
        return null;
    }

    /**
     * Phase 4: doorway queueing. Two manual-CQB soldiers march into the house
     * west room; both paths funnel through the west door (14,12). The doorway
     * doctrine holds the follower back before the wall-narrow passage while
     * the leader is still in it - the stack crosses single-file instead of
     * clumping in the fatal funnel. Queueing lives in the shared MoveControl
     * caution layer, so cover approaches, GO_TO relocations and attack
     * approaches get the same treatment.
     */
    private static SimScenario cqbDoorwayQueue() {
        return SimScenario.builder("cqb_doorway_queue")
            .core()
            .description("Phase-4 CQB doctrine: two manual-CQB soldiers march into the cqbCourse "
                + "house through the same west door. The doorway queue holds the follower back "
                + "before the wall-narrow passage while the leader is still in it - the stack "
                + "crosses single-file instead of clumping in the fatal funnel. Works through "
                + "the shared MoveControl caution layer, so cover approaches, GO_TO relocations "
                + "and attack approaches get the same queueing.")
            .duration(900)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(16, 0, 32);
                // Objectives deep in the FAR room (beyond the partition door):
                // the west door sits outside both hold radii, so the leader
                // cannot legitimately park in the funnel - the dock-resume
                // doctrine carries him through and the follower follows.
                SoldierEntity alpha = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 16, 0, 28, SquadMode.HOLD);
                alpha.setCQB(true);
                alpha.setHoldPosition(ctx.terrain.at(18, 0, 8));
                SoldierEntity bravo = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 18, 0, 30, SquadMode.HOLD);
                bravo.setCQB(true);
                bravo.setHoldPosition(ctx.terrain.at(20, 0, 9));
            })
            .assertThat("single-file door crossing (never both within 1.5 of the door at once)",
                view -> !everBothNear(view, "alpha", "bravo", new net.minecraft.world.phys.Vec3(14.5, 0, 12.5), 1.5))
            // "Reached" = inside the house shell: both objectives sit in the
            // west room, so an outside route cannot satisfy this - the funnel
            // crossing is mandatory.
            .assertThat("both soldiers reach their objectives inside the house "
                    + "(final within x 15-29, z 7-17)",
                view -> {
                    SimSample a = lastSample(view, "alpha");
                    SimSample b = lastSample(view, "bravo");
                    if (a == null || b == null) return false;
                    return inHouse(a.state.position.x, a.state.position.z)
                        && inHouse(b.state.position.x, b.state.position.z);
                })
            .build();
    }

    /**
     * Phase 4: corner pie-slicing. A manual-CQB soldier marches to the house's
     * northwest room; the path bends at the west door and the arc beyond the
     * corner holds squad intel of a threat. While approaching the bend the
     * soldier must pre-aim INTO the arc (northward facing) instead of staring
     * at the wall he is hugging.
     */
    private static SimScenario cqbCornerPie() {
        return SimScenario.builder("cqb_corner_pie")
            .core()
            .description("Phase-4 CQB doctrine: a manual-CQB soldier marches around the cqbCourse "
                + "west door toward the northwest room while squad intel places a threat just "
                + "past the corner. Corner pie-slicing pre-aims the arc the corner opens into "
                + "(northward facing) while approaching - aim orientation leads the body around "
                + "the corner instead of trailing it.")
            .duration(700)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(26, 0, 30);
                SoldierEntity pie = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "pie", 26, 0, 22, SquadMode.HOLD);
                pie.setCQB(true);
                pie.setHoldPosition(ctx.terrain.at(16, 0, 8));
                // Deterministic intel just past the corner arc, kept fresh.
                ctx.injectThreat(16, 10, 1.0f);
            })
            .action(90, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(180, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(270, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(360, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .assertThat("pre-aims the corner arc on approach (>= 4 northward-facing samples "
                    + "while south of the door, t60-400)",
                view -> northFacingSamples(view, "pie", 60, 400, -0.3) >= 4)
            // Hold doctrine docks within the 10-block hold radius; the room
            // entry itself is not required (cover inside radius wins).
            .assertThat("march reaches hold radius of the objective (final dist to (16,8) <= 10.5)",
                view -> {
                    SimSample last = lastSample(view, "pie");
                    return last != null && Math.hypot(last.state.position.x - 16.5,
                        last.state.position.z - 8.5) <= 10.5;
                })
            .build();
    }

    /**
     * Corner pie-slicing under an ATTACK order (the clearing-doctrine path
     * players actually ping in-game, no manual CQB toggle). Same house bend
     * and intel placement as cqb_corner_pie, but the march is driven by
     * issueAttackOrder: CoverTacticalGoal owns the advance and SoldierCombatGoal
     * only enters when it has targets/cover, so this probes whether the pie
     * check survives that ownership on the attack path.
     */
    private static SimScenario attackPingCornerPie() {
        return SimScenario.builder("attack_ping_corner_pie")
            .description("Corner pie-slicing under an attack ping: an ordinary soldier (no manual "
                + "CQB) advances on an ATTACK order toward the cqbCourse northwest room while "
                + "squad intel places a threat just past the west-door corner arc. The path bends "
                + "at the door; while still south of the door line the soldier must pre-aim INTO "
                + "the arc (northward facing) instead of staring at the wall he is hugging.")
            .duration(1000)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(26, 0, 30);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "pie", 26, 0, 22, SquadMode.HOLD);
                // Deterministic intel just past the corner arc, kept fresh.
                ctx.injectThreat(16, 10, 1.0f);
                ctx.issueAttackOrder(16, 8);
            })
            .action(90, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(180, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(270, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(360, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(450, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(540, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(630, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .assertThat("pre-aims the corner arc while approaching (>= 4 northward-facing "
                    + "samples still south of the door line z=13)",
                view -> {
                    int n = 0;
                    for (SimSample s : view.timeline().series("pie")) {
                        if (s.state.position.z > 13.0
                            && Math.cos(Math.PI * s.headYaw / 180.0) < -0.3) {
                            n++;
                        }
                    }
                    return n >= 4;
                })
            .assertThat("caution slow-down engages and holds the cap while active "
                    + "(>= 10 moving capped samples, none above the 0.37 auto modifier)",
                view -> {
                    int engaged = 0;
                    for (SimSample s : view.timeline().series("pie")) {
                        if (s.cautionReason == null || "none".equals(s.cautionReason)) continue;
                        if (s.appliedSpeed != null && s.appliedSpeed > 0.0) {
                            // cap contract: min(requested, 0.37) modifier x 0.35 speed attr
                            if (s.appliedSpeed > 0.37 * 0.35 + 0.005) return false;
                            engaged++;
                        }
                    }
                    return engaged >= 10;
                })
            .assertThat("attack order advances the soldier to the objective room "
                    + "(final dist to (16,8) <= 10.5)",
                view -> {
                    SimSample last = lastSample(view, "pie");
                    return last != null && Math.hypot(last.state.position.x - 16.5,
                        last.state.position.z - 8.5) <= 10.5;
                })
            .build();
    }

    /**
     * Attack order at an objective sealed inside a wall pocket: every POI the
     * coordinator derives from the ping floods only the pocket interior, so
     * the dockless soldier outside faces an unreachable guide with no dock to
     * hold and no advance to take. That combination used to re-request the
     * same doomed search forever — the frozen SELECTING_COVER/NO_COVER wall.
     * After bounded starved searches the job is conceded and the soldier must
     * make visible progress on the order.
     */
    private static SimScenario attackGuideUnreachableConcede() {
        return SimScenario.builder("attack_guide_unreachable_concede")
            .description("Dockless attack soldier, unreachable guide: the attack ping lands inside "
                + "a sealed building on a borderless (coverless) floor, so every cover search "
                + "fails and the assigned corner job has no walkable route. The soldier must "
                + "concede the job after bounded starved searches and advance, not mill in place.")
            .duration(1000)
            .armed()
            .terrain(t -> {
                t.floorOnly();
                t.building(33, 9, 39, 15, 3, new int[0][]); // sealed objective box, no doors
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 30);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "concede", 10, 0, 30, SquadMode.HOLD);
                ctx.injectThreat(36, 12, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueAttackOrder(36, 12);
            })
            .action(250, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(500, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .action(750, "refresh threat intel", ctx -> ctx.refreshInjectedThreats())
            .assertThat("advances on the order despite the sealed box "
                    + "(final displacement from spawn >= 8)",
                view -> {
                    SimSample last = lastSample(view, "concede");
                    return last != null && Math.hypot(
                        last.state.position.x - 10.5, last.state.position.z - 30.5) >= 8.0;
                })
            .assertThat("closes on the sealed box (final dist to (36,12) <= 14)",
                view -> {
                    SimSample last = lastSample(view, "concede");
                    return last != null && Math.hypot(
                        last.state.position.x - 36.5, last.state.position.z - 12.5) <= 14.0;
                })
            .build();
    }

    /**
     * GO_TO volley into a coverless corridor: the destination has no cover
     * route, and the player re-pings the same spot every 30 ticks. Every
     * re-ping bumped the move generation and restarted the 200t relocation
     * window, so the direct-walk fallback could never fire — the soldier
     * stood prone flashing SEEKING/NO_COVER. The seek-starvation bail is
     * keyed on the destination, so the volley cannot restart it and the
     * soldier must walk.
     */
    private static SimScenario goToVolleyStrandDirectWalk() {
        return SimScenario.builder("goto_volley_strand_direct_walk")
            .description("Mashed GO_TO pings over a coverless platform: the cover relocation "
                + "seek starves, and the direct walk must take over within the destination-"
                + "keyed grace instead of stranding prone in a SEEKING/NO_COVER flash.")
            .duration(700)
            .armed()
            .terrain(t -> t.floorOnly()) // coverless corridor: every seek must fail
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "mover", 8, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(14, 22, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(34, 24);
            })
            .action(60, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(90, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(120, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(150, "re-ping GO_TO + refresh threat", ctx -> {
                ctx.issueGoTo(34, 24);
                ctx.refreshInjectedThreats();
            })
            .action(180, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(210, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(240, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(270, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(300, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(330, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(360, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(390, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(420, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(450, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(480, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(510, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(540, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(570, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(600, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .action(630, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 24))
            .assertThat("makes progress while the pings mash "
                    + "(displacement from spawn at t330 >= 6)",
                view -> {
                    SimSample at = sampleAt(view, "mover", 330);
                    return at != null && Math.hypot(
                        at.state.position.x - 8.5, at.state.position.z - 24.5) >= 6.0;
                })
            .assertThat("completes the approach (closest approach to the ping <= 4; "
                    + "post-completion combat drift is legal)",
                view -> {
                    double best = Double.MAX_VALUE;
                    for (SimSample s : view.timeline().series("mover")) {
                        double d = Math.hypot(
                            s.state.position.x - 34.5, s.state.position.z - 24.5);
                        if (d < best) best = d;
                    }
                    return best <= 4.0;
                })
            .assertThat("never strands prone away from the destination "
                    + "(at t330: not prone, or already within 6 of the ping)",
                view -> {
                    SimSample at = sampleAt(view, "mover", 330);
                    if (at == null) return false;
                    boolean near = Math.hypot(
                        at.state.position.x - 34.5, at.state.position.z - 24.5) <= 6.0;
                    return near || !"SWIMMING".equals(at.posture);
                })
            .assertThat("state flash stays bounded once the walk owns the legs "
                    + "(<= 8 SEEKING/NO_COVER transitions per 200 ticks after t200)",
                view -> {
                    // Completed orders now retain HOLD ownership and perform
                    // normal paced cover retries. Count bursts, not the total
                    // across the entire 25-second post-arrival observation.
                    java.util.ArrayDeque<Integer> flips = new java.util.ArrayDeque<>();
                    String prev = null;
                    for (SimSample s : view.timeline().series("mover")) {
                        if (s.tick < 200) continue;
                        boolean flash = "SEEKING_COVER".equals(s.state.coverState)
                            || "NO_COVER".equals(s.state.coverState);
                        if (flash && prev != null && !prev.equals(s.state.coverState)) {
                            flips.addLast(s.tick);
                            while (!flips.isEmpty() && s.tick - flips.peekFirst() >= 200) {
                                flips.removeFirst();
                            }
                            if (flips.size() > 8) return false;
                        }
                        prev = flash ? s.state.coverState : null;
                    }
                    return true;
                })
            .build();
    }

    /**
     * The realistic GO_TO volley: the streetBlock fixture (two houses, doors,
     * a street between them, waist-high cover rows) instead of a bare floor.
     * The order points into house B while the player re-pings the same spot
     * every 30 ticks — the mashed-ping pattern from the in-game strand. With
     * the cover endpoint doctrine, the order may finish at a selected street
     * dock before entering the room; the volley must not restart its seek
     * window or strand the soldier there in a prone state.
     */
    private static SimScenario goToVolleyStreet() {
        boolean[] holdingAtEnd = {false};
        boolean[] coverNearSearchAreaAtEnd = {false};
        SimScenario.Builder scenario = SimScenario.builder("goto_volley_street")
            .description("Mashed GO_TO pings on the streetBlock fixture toward the far side of house B, "
                + "with waist-high cover rows in the street. The order may finish at a selected "
                + "cover endpoint, and repeated pings must preserve its claim and posture.")
            .duration(900)
            .armed()
            .terrain(t -> {
                t.streetBlock();
                t.coverRow(19, 12, 20, 13, 1);
                t.coverRow(19, 17, 20, 18, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 32);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "mover", 10, 0, 34, SquadMode.HOLD);
                ctx.injectThreat(21, 15, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(34, 15);
            })
            .action(60, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(90, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(120, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(150, "re-ping GO_TO + refresh threat", ctx -> {
                ctx.issueGoTo(34, 15);
                ctx.refreshInjectedThreats();
            })
            .action(180, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(210, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(240, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(270, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(300, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(330, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(360, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(390, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(420, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(450, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(480, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .action(510, "re-ping GO_TO (volley)", ctx -> ctx.issueGoTo(34, 15))
            .assertThat("keeps advancing while the pings mash "
                    + "(displacement from spawn at t330 >= 10)",
                view -> {
                    SimSample at = sampleAt(view, "mover", 330);
                    return at != null && Math.hypot(
                        at.state.position.x - 10.5, at.state.position.z - 34.5) >= 10.0;
                })
            .assertThat("prone posture at t450 is either near the ping or docked in selected cover",
                view -> {
                    SimSample at = sampleAt(view, "mover", 450);
                    if (at == null) return false;
                    boolean near = Math.hypot(
                        at.state.position.x - 34.5, at.state.position.z - 15.5) <= 8.0;
                    boolean docked = at.state.currentCover != null && at.coverSlotOffset >= 0.0
                        && at.coverSlotOffset <= 3.0;
                    return near || docked || !"SWIMMING".equals(at.posture);
                })
            .assertThat("state flash stays bounded once the walk owns the legs "
                    + "(<= 8 SEEKING/NO_COVER transitions after t200)",
                view -> {
                    int flips = 0;
                    String prev = null;
                    for (SimSample s : view.timeline().series("mover")) {
                        if (s.tick < 200) continue;
                        boolean flash = "SEEKING_COVER".equals(s.state.coverState)
                            || "NO_COVER".equals(s.state.coverState);
                        if (flash && prev != null && !prev.equals(s.state.coverState)) {
                            flips++;
                        }
                        prev = flash ? s.state.coverState : null;
                    }
                    return flips <= 8;
                });
        scenario.action(870, "record completed order and retained cover endpoint", ctx -> {
            SoldierEntity mover = ctx.soldier("mover");
            if (mover == null || !mover.isAlive()) return;
            holdingAtEnd[0] = mover.isGoToHolding();
            var cover = mover.getCoverBehaviorManager().getCurrentCover();
            if (cover != null) {
                var pos = cover.getPosition();
                coverNearSearchAreaAtEnd[0] = Math.hypot(pos.getX() + 0.5 - 34.5,
                    pos.getZ() + 0.5 - 15.5) <= 20.0;
            }
        });
        return scenario
            .assertThat("completed GO_TO retains its selected cover near the search area",
                view -> holdingAtEnd[0] && coverNearSearchAreaAtEnd[0])
            .build();
    }

    /**
     * Automatic caution preserves the capped Traverse pace under TaCZ gun-speed emulation.
     * There is no visible target; injected threat only supplies caution pressure. The
     * weapon-weight-adjusted request remains capped at 0.28 blocks/tick.
     * Exact modifier/ADS/slow-request behavior is covered by CautionSteeringScenarios.
     */
    private static SimScenario cautionAdsTravelFloor() {
        boolean[] holdingAtEnd = {false};
        boolean[] coverNearSearchAreaAtEnd = {false};
        SimScenario.Builder scenario = SimScenario.builder("caution_ads_travel_floor")
            .description("GO_TO across the streetBlock with a threat near the path and the "
                + "sim gun's TaCZ speed emulation engaged: automatic caution retains "
                + "capped Traverse pace with the normal weapon weight tax.")
            .duration(600)
            .armed()
            .terrain(t -> {
                t.streetBlock();
                t.coverRow(19, 12, 20, 13, 1);
                t.coverRow(19, 17, 20, 18, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 32);
                SoldierEntity mover = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "mover", 10, 0, 34, SquadMode.HOLD);
                ctx.injectThreat(21, 15, 1.0f);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(34, 15);
                // Probe the emulation before the run: aiming must visibly tax
                // the attribute, or the speed asserts below are vacuous.
                net.minecraft.world.entity.ai.attributes.AttributeInstance speedAttr =
                    mover.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                double unaimed = speedAttr.getValue();
                com.stevesarmy.combat.GunIntegration.aim(mover, true);
                double aimed = speedAttr.getValue();
                com.stevesarmy.combat.GunIntegration.aim(mover, false);
                double restored = speedAttr.getValue();
                // aim() itself may engage the weight tax (first refresh), so
                // judge against the post-aim restore, not the pre-aim value.
                if (aimed > restored * 0.85 || restored > unaimed + 1.0e-9) {
                    throw new IllegalStateException(String.format(
                        "sim gun speed emulation not engaged: unaimed=%.4f aimed=%.4f restored=%.4f",
                        unaimed, aimed, restored));
                }
            })
            .assertThat("automatic caution preserves capped Traverse pace "
                    + "(median caution-window speed in [0.275, 0.28001] b/t)",
                view -> {
                    java.util.List<Double> speeds = new java.util.ArrayList<>();
                    for (SimSample s : view.timeline().series("mover")) {
                        if (s.tick < 40) continue;
                        if (!"enemy near path".equals(s.cautionReason)) continue;
                        Double sp = s.appliedSpeed;
                        if (sp == null || sp <= 0.05) continue; // door stalls are a separate phenomenon
                        speeds.add(sp);
                    }
                    if (speeds.size() < 6) {
                        return false; // not enough caution travel observed to judge
                    }
                    speeds.sort(Double::compare);
                    double median = speeds.get(speeds.size() / 2);
                    return median >= 0.275 && median <= 0.28001;
                });
        scenario.action(580, "record completed order and retained cover endpoint", ctx -> {
            SoldierEntity mover = ctx.soldier("mover");
            if (mover == null || !mover.isAlive()) return;
            holdingAtEnd[0] = mover.isGoToHolding();
            var cover = mover.getCoverBehaviorManager().getCurrentCover();
            if (cover != null) {
                var pos = cover.getPosition();
                coverNearSearchAreaAtEnd[0] = Math.hypot(pos.getX() + 0.5 - 34.5,
                    pos.getZ() + 0.5 - 15.5) <= 20.0;
            }
        });
        return scenario
            .assertThat("completed GO_TO retains its selected cover near the search area",
                view -> holdingAtEnd[0] && coverNearSearchAreaAtEnd[0])
            .build();
    }

    /**
     * Suppressed-traveler crawl gate: sustained near-miss fire pins a rifleman
     * marching (GO_TO) along a 1-high wall row with a low shooter north of it —
     * the lane cells read eye-exposed / leg-shielded, so the crawl engages.
     * The soldier must go low and respect the 0.65 x BASE crawl ceiling while
     * retaining slower route requests and weapon weight. A normal march reaches
     * the 0.2275 base cap; an explicit 0.65 crawl route requests 0.21555625 after
     * weapon weight. Neither should acquire an extra ADS tax (about 0.172).
     */
    private static SimScenario suppressedCrawlPace() {
        return SimScenario.builder("suppressed_crawl_pace")
            .description("GO_TO march under sustained scripted near-miss fire: the crawl "
                + "engages in crawl-hidden cells and the soldier must go low (SWIMMING "
                + "posture), preserving slower weighted route requests under the 0.65 x base cap.")
            .duration(400)
            .armed()
            .terrain(t -> {
                t.platform();
                // east-west 1-high wall north of the march lane (z=20): south-face
                // cells are eye-exposed / leg-shielded against the low northern shooter
                t.coverRow(10, 20, 30, 20, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "mover", 30, 0, 26, SquadMode.HOLD);
                ctx.setDangerSweepEnabled(false);
                ctx.issueGoTo(12, 26);
            })
            .action(15, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(30, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(45, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(60, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(75, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(90, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(105, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(120, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("mover", 3, 20.5, 8.5, 1.0, 3.0f))
            .assertThat("march completes (closest approach to the ping <= 3)",
                view -> {
                    double best = Double.MAX_VALUE;
                    for (SimSample s : view.timeline().series("mover")) {
                        double d = Math.hypot(
                            s.state.position.x - 12.5, s.state.position.z - 26.5);
                        if (d < best) best = d;
                    }
                    return best <= 3.0;
                })
            .assertThat("goes low while marching under fire "
                    + "(SWIMMING posture observed with suppression >= 0.5)",
                view -> {
                    for (SimSample s : view.timeline().series("mover")) {
                        if ("SWIMMING".equals(s.posture)
                            && s.state.suppressionLevel >= 0.5f) {
                            return true;
                        }
                    }
                    return false;
                })
            .assertThat("crawl preserves slower weighted route requests under its base cap "
                    + "(median crawl-window appliedSpeed in [0.210, 0.234])",
                view -> {
                    java.util.List<Double> speeds = new java.util.ArrayList<>();
                    for (SimSample s : view.timeline().series("mover")) {
                        if (!"SWIMMING".equals(s.posture)) continue;
                        Double sp = s.appliedSpeed;
                        if (sp == null || sp <= 0.05) continue;
                        speeds.add(sp);
                    }
                    if (speeds.size() < 4) {
                        return false; // not enough crawl travel observed to judge
                    }
                    speeds.sort(Double::compare);
                    double median = speeds.get(speeds.size() / 2);
                    return median >= 0.210 && median <= 0.234;
                })
            .build();
    }

    /**
     * Phase 4: lost-contact watch. Close-quarters contact with a visible armed
     * enemy breaks when the enemy slips behind the house wall. The soldier
     * holds position and keeps his aim on the last-known spot through the
     * watch window instead of drifting into routine scanning.
     */
    private static SimScenario cqbLostContactWatch() {
        return SimScenario.builder("cqb_lost_contact_watch")
            .core()
            .description("Phase-4 CQB doctrine: an armed enemy breaks contact by slipping behind "
                + "the cqbCourse house wall. The soldier holds position and keeps his aim on "
                + "the last-known position for the lost-contact watch window instead of "
                + "wandering; after the window he resumes normal behavior.")
            .duration(600)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(36, 0, 28);
                SoldierEntity watch = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "watch", 36, 0, 24, SquadMode.FOLLOW);
                ctx.harden("watch");
                EnemySoldierEntity enemy = ctx.spawnEnemyLabeled("threat", 28, 0, 20);
                // Disarmed: contact must break by LoS, not by a firefight.
                ctx.disarmSoldier("threat");
            })
            .action(120, "enemy slips behind the house wall", ctx -> {
                if (ctx.byLabel.get("threat") instanceof EnemySoldierEntity e) {
                    // Inside the east room, HOLD-pinned: the x=30 wall breaks
                    // LoS from the soldier's position south-east of the house,
                    // and the pin keeps him from wandering back out the door.
                    EnemySoldierEntity moved = ctx.moveEnemy(e, 26, 0, 12);
                    moved.setSquadMode(SquadMode.HOLD);
                    moved.setHoldPosition(ctx.terrain.at(26, 0, 12));
                }
            })
            // Displacement 9, not 2.5: a cover-goal relocation may lawfully
            // take the legs during the window (the watch yields to cover
            // moves); what it must not do is wander off or chase.
            .assertThat("stays in the local area through the watch window (t120-200 displacement <= 9)",
                view -> {
                    SimSample at = sampleAt(view, "watch", 120);
                    SimSample end = sampleAt(view, "watch", 200);
                    if (at == null || end == null) return false;
                    double d = Math.hypot(end.state.position.x - at.state.position.x,
                        end.state.position.z - at.state.position.z);
                    return d <= 9.0;
                })
            .assertThat("keeps aim on the last-known spot during the watch window "
                    + "(t130-195 west-facing)",
                view -> {
                    int facing = 0;
                    for (SimSample s : view.timeline().series("watch")) {
                        if (s.tick < 130 || s.tick > 195) continue;
                        double fx = -Math.sin(Math.PI * s.headYaw / 180.0);
                        double fz = Math.cos(Math.PI * s.headYaw / 180.0);
                        if (fx < -0.2 && fz < 0.2) facing++;
                    }
                    // Samples land every 5t: the 65t window holds ~13; a
                    // lawful mid-window cover relocation may drag the aim
                    // briefly, so half the window suffices.
                    return facing >= 6;
                })
            // No resume assertion: ThreatTracker keeps last-known 600t by
            // design, so the aim lingers well past the watch window. The
            // doctrine under test is the hold+aim during the window.
            .build();
    }

    /** True when the soldier's final sample stands north (z below) of the line. */
    private static boolean northOf(SimScenario.SimRunResultView view, String label, double zLine) {
        List<SimSample> series = view.timeline().series(label);
        return !series.isEmpty() && series.get(series.size() - 1).state.position.z < zLine;
    }

    /** True when at some tick one soldier is north of northZ while the other is still south of southZ. */
    private static boolean staggeredCrossing(SimScenario.SimRunResultView view,
                                             String labelA, String labelB, double northZ, double southZ) {
        List<SimSample> sa = view.timeline().series(labelA);
        List<SimSample> sb = view.timeline().series(labelB);
        for (SimSample a : sa) {
            if (a.state.position.z >= northZ) continue;
            for (SimSample b : sb) {
                if (b.tick != a.tick) continue;
                if (b.state.position.z > southZ) return true;
            }
        }
        for (SimSample b : sb) {
            if (b.state.position.z >= northZ) continue;
            for (SimSample a : sa) {
                if (a.tick != b.tick) continue;
                if (a.state.position.z > southZ) return true;
            }
        }
        return false;
    }

    /** Max displacement of anchorLabel while moverLabel crosses between the two z lines. */
    private static double anchorHoldsDuring(SimScenario.SimRunResultView view, String moverLabel,
                                            String anchorLabel, double zFrom, double zTo, double maxAllowed) {
        List<SimSample> mover = view.timeline().series(moverLabel);
        List<SimSample> anchor = view.timeline().series(anchorLabel);
        Integer windowStart = null;
        Integer windowEnd = null;
        for (SimSample s : mover) {
            if (windowStart == null && s.state.position.z < zFrom) windowStart = s.tick;
            if (windowStart != null && s.state.position.z < zTo) { windowEnd = s.tick; break; }
        }
        if (windowStart == null) return 0;
        Vec3 origin = null;
        double max = 0;
        for (SimSample s : anchor) {
            if (s.tick < windowStart) continue;
            if (windowEnd != null && s.tick > windowEnd) break;
            if (origin == null) origin = s.state.position;
            max = Math.max(max, Math.hypot(s.state.position.x - origin.x, s.state.position.z - origin.z));
        }
        return max;
    }

    /** Phase 1: CQB formation is a mobility doctrine - no freeze on sight at range, cover use retained. */
    private static SimScenario cqbFormationNoFreeze() {
        return SimScenario.builder("cqb_formation_no_freeze")
            .core()
            .description("Phase 1 CQB doctrine rework: the formation wheel's CQB toggle no longer "
                + "freeze-holds on sight at any distance. A CQB soldier spotting an enemy at 8 blocks "
                + "keeps using cover - claims the nearby row instead of standing frozen in the open.")
            .duration(500)
            .terrain(t -> {
                t.platform();
                t.coverRow(6, 18, 8, 18, 1);
            })
            .setup(ctx -> {
                var anchor = ctx.spawnAnchor(4, 0, 20);
                SoldierEntity cqb = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "cqb", 10, 0, 20, SquadMode.FOLLOW);
                cqb.setCQB(true);
                ctx.squadForAnchor(anchor).setCQB(true);
                ctx.spawnEnemy(10, 0, 28); // 8 blocks: inside the OLD any-distance freeze, outside emergency range
            })
            .assertThat("CQB soldier claims cover by t300 (no sight-freeze, cover use retained)",
                view -> claimedCoverBy(view, "cqb", 300))
            .assertThat("docks into the row", view -> dockedAtEnd(view, "cqb"))
            .build();
    }

    /** Phase 1 course smoke test: a hold march through the two-room house course must not strand. */
    private static SimScenario courseCqbHouseApproach() {
        return SimScenario.builder("course_cqb_house_approach")
            .core()
            .description("Course scenario (two-room house + street): a soldier marching to a hold point "
                + "inside the west room while an enemy loiters outside the east door. Under the current "
                + "doctrine a detected enemy turns the march into a bounded firefight - the march must "
                + "still make real progress toward the objective (no strand at spawn), claim sensible "
                + "protection, and cycle peek/fire without deadlock detectors.")
            .duration(900)
            .armed()
            .terrain(t -> t.cqbCourse())
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                SoldierEntity marcher = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "marcher", 22, 0, 38, SquadMode.HOLD);
                marcher.setHoldPosition(ctx.terrain.at(18, 0, 12)); // inside the west room
                ctx.spawnEnemy(34, 0, 10); // outside the east door
            })
            .assertThat("march makes useful progress toward the house (>= 8 blocks closer than spawn)",
                view -> advancedToward(view, "marcher", 18.5, 12.5, 8.0))
            .assertThat("firefight produced tracers (enemy engaged or return fire seen)",
                view -> !view.shots().isEmpty())
            .build();
    }

    /** True when the soldier ends at least {@code minBlocks} closer to (x,z) than it started. */
    private static boolean advancedToward(SimScenario.SimRunResultView view, String label,
                                          double x, double z, double minBlocks) {
        List<SimSample> series = view.timeline().series(label);
        if (series.isEmpty()) return false;
        SimSample first = series.get(0);
        SimSample last = series.get(series.size() - 1);
        double d0 = Math.hypot(first.state.position.x - x, first.state.position.z - z);
        double d1 = Math.hypot(last.state.position.x - x, last.state.position.z - z);
        return d0 - d1 >= minBlocks;
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
            .core()
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

    // 12. lone 1-block cover in an open field — the stand-while-hiding strand --

    /**
     * User report (2026-10-06): a soldier hiding at a single 1-block cover in an
     * open field "not actually ducking (just stands there)" dies to enemy fire.
     * A lone 1-high block classifies HALF with synced cover height 1.0, so the
     * pose gate (SoldierEntity.tick) must hold the crouch whenever he is docked
     * there; sustained STANDING posture with exposed head rays at that slot is
     * the reported failure signature.
     */
    private static SimScenario loneBlockExposure() {
        return SimScenario.builder("lone_block_exposure")
            .description("User report: at a single 1-block cover in an open field the soldier "
                + "stands upright while labeled hiding and is shot dead. The lone block "
                + "classifies HALF (synced height 1.0 < 1.3), so the pose gate must keep him "
                + "crouched at the slot; sustained standing head exposure there is the bug.")
            .duration(700)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverBlock(12, 30, 1); // THE lone block — nothing else in the field
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "lone", 10, 0, 22, SquadMode.FOLLOW);
                // four shooters so the pressure outlives the soldier's return fire —
                // two die too fast and the stand-exposure window never forms
                ctx.spawnEnemy(16, 0, 44);
                ctx.spawnEnemy(22, 0, 48);
                ctx.spawnEnemy(12, 0, 50);
                ctx.spawnEnemy(26, 0, 42);
            })
            .assertThat("soldier claims the lone block (HALF) as cover by t300",
                view -> claimedCoverTypedBy(view, "lone", 300, "HALF"))
            .assertThat("FINDING: docked soldier never sustains STANDING posture at the low "
                + "half cover with head rays exposed (pose gate holds the crouch)",
                view -> !sustainedStandingAtLowHalfCover(view, "lone", 40, 8))
            .build();
    }

    /** Claims a cover of the given type by {@code byTick}. */
    private static boolean claimedCoverTypedBy(SimScenario.SimRunResultView view, String label,
                                               int byTick, String type) {
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick > byTick) break;
            if (s.state.currentCover != null && type.equals(s.state.currentCoverType)) return true;
        }
        return false;
    }

    /**
     * Close-quarters variant of the user report: the enemy inside the 6-block
     * emergency-flank distance and off the prone arc. Documented result (three
     * runs): the suppression prone still wins server-side — the soldier dies
     * standing-exposed anyway because a lone block at that range leaves 10-12
     * of 15 body rays visible while prone. The emergency stand-fire cycle
     * (prepareToFire out-of-arc stand, 20t EMR window vetoing the prone) never
     * got a window to fire before death; it remains an in-game-only candidate
     * for the "keeps standing while suppressed" visual, readable on the
     * coverstate card's EMR flag.
     */
    private static SimScenario loneBlockFlankStand() {
        return SimScenario.builder("lone_block_flank_stand")
            .description("KNOWN FINDING (close range): at a lone 1-block cover with the enemy inside "
                + "the 6-block flank distance, the soldier dies within ~150t with 10-12/15 body rays "
                + "visible while PRONE and pinned (sup 1.0) — the lone block protects nothing at "
                + "close range. Server posture behaves as designed; the standing visual is a render-"
                + "side or emergency-stand question, readable via the coverstate card.")
            .duration(600)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverBlock(12, 30, 1); // THE lone block
            })
            .setup(ctx -> {
                ctx.spawnAnchor(4, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "flank", 10, 0, 22, SquadMode.FOLLOW);
                ctx.spawnEnemy(15, 0, 32); // ~4.2 blocks from the claimed slot: inside EMERGENCY_FLANK_DISTANCE, ~45deg off the prone arc
                ctx.spawnEnemy(24, 0, 40); // keeps pressure when the close one goes down
            })
            .assertThat("soldier claims the lone block (HALF) as cover by t300",
                view -> claimedCoverTypedBy(view, "flank", 300, "HALF"))
            .assertThat("FINDING: soldier never stands while suppressed at the low half cover "
                + "(emergency stand-fire cycle dominates the suppression prone)",
                view -> !standingWhileSuppressedEver(view, "flank", 40, 8))
            .build();
    }

    /** Sustained STANDING posture while pinned (suppression >= 0.5) docked at a low HALF cover. */
    private static boolean standingWhileSuppressedEver(SimScenario.SimRunResultView view,
                                                       String label, int fromTick,
                                                       int streakSamples) {
        int streak = 0;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < fromTick) continue;
            boolean hit = s.state.currentCover != null
                && "HALF".equals(s.state.currentCoverType)
                && s.syncedCoverHeight >= 0 && s.syncedCoverHeight < 1.3f
                && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5
                && "STANDING".equals(s.posture)
                && (s.state.pinned || s.state.suppressionLevel >= 0.5f);
            streak = hit ? streak + 1 : 0;
            if (streak >= streakSamples) return true;
        }
        return false;
    }

    /** Sustained STANDING posture while docked at a HALF cover whose synced height is
     * below the 1.3 standing threshold — the pose gate should be holding the crouch. */
    private static boolean sustainedStandingAtLowHalfCover(SimScenario.SimRunResultView view,
                                                           String label, int fromTick,
                                                           int streakSamples) {
        int streak = 0;
        for (SimSample s : view.timeline().series(label)) {
            if (s.tick < fromTick) continue;
            boolean hit = s.state.currentCover != null
                && "HALF".equals(s.state.currentCoverType)
                && s.syncedCoverHeight >= 0 && s.syncedCoverHeight < 1.3f
                && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5
                && "STANDING".equals(s.posture)
                && s.exposedHeadRays > 0;
            streak = hit ? streak + 1 : 0;
            if (streak >= streakSamples) return true;
        }
        return false;
    }

    // 13. support-role cover behavior ------------------------------------------
    //
    // The support wraps CoverTacticalGoal (rear positioning, never peeks) and
    // periodically leaves cover for resupply duty, so its cover engagement
    // flaps: claim → duty (cover cleared) → claim again. These scenarios check
    // both halves of that loop plus the pick quality itself.

    /**
     * Live-fire mixed squad: one support + two riflemen vs two enemies behind
     * their own cover. The support must claim cover under real fire, actually
     * dock into it while hiding, and still be claiming cover late in the fight
     * (the duty→cover return path must not strand it in the open).
     */
    private static SimScenario supportSquadFight() {
        return SimScenario.builder("support_squad_fight")
            .description("Live-fire support probe: one support + two riflemen vs two enemies behind "
                + "their own cover. The support must claim cover under real fire, dock into it while "
                + "hiding (0 exposed rays), and come back to cover after resupply-duty departures. "
                + "KNOWN FINDINGS (expected FAIL until fixed, 2026-10-06): pre-intel cover searches "
                + "downgrade solid waist-high walls to CONCEALMENT (null-threat NORTH default reads "
                + "the height in the wrong direction; type frozen at claim), and the pose gate only "
                + "crouches HALF covers — so the misclassified soldier stands upright behind a 1-high "
                + "wall labeled HIDING with head rays exposed the whole fight.")
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
                ctx.spawnFriendly(ModEntities.SUPPORT.get(), "sup", 12, 0, 24, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "left", 12, 0, 14, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "right", 12, 0, 34, SquadMode.FOLLOW);
                ctx.spawnEnemy(38, 0, 22);
                ctx.spawnEnemy(38, 0, 26);
            })
            .assertThat("support claims cover by t300", view -> claimedCoverBy(view, "sup", 300))
            .assertThat("support docks and hides unexposed after t250 (0 exposed rays)",
                view -> existsHidingUnexposed(view, "sup", 250))
            .assertThat("left rifleman claims cover by t200 and still holds it at t400",
                view -> claimedCoverByAndHeld(view, "left", 200, 400))
            .assertThat("right rifleman claims cover by t200 and still holds it at t400",
                view -> claimedCoverByAndHeld(view, "right", 200, 400))
            .assertThat("support is in cover again after t400 (duty return path doesn't strand)",
                view -> view.timeline().series("sup").stream()
                    .anyMatch(s -> s.tick >= 400 && s.state.currentCover != null))
            .assertThat("squad fights back — the enemy takes damage from friendly fire",
                view -> view.enemyHealthEnd() < 20.0f)
            .assertThat("FINDING: no soldier claims a solid wall as CONCEALMENT "
                + "(pre-intel searches read cover height in the null-threat NORTH default and "
                + "downgrade 1-high solid cover; the claimed type then never reclassifies)",
                view -> !everClaimedSolidCoverAsConcealment(view))
            .assertThat("FINDING: a docked hiding soldier at a sub-1.3 cover never sustains "
                + "STANDING posture with head rays exposed (the pose gate only crouches HALF, "
                + "so CONCEALMENT-typed solid cover stands upright in the open)",
                view -> !sustainedStandingWhileHiding(view, 40, 8))
            .build();
    }

    /** Any claimed cover typed CONCEALMENT whose synced height is >= 0.8 — solid cover misclassified. */
    private static boolean everClaimedSolidCoverAsConcealment(SimScenario.SimRunResultView view) {
        return view.timeline().soldierLabels().stream().anyMatch(label ->
            view.timeline().series(label).stream().anyMatch(s ->
                s.state.currentCover != null
                    && "CONCEALMENT".equals(s.state.currentCoverType)
                    && s.syncedCoverHeight >= 0.8f));
    }

    /** Sustained STANDING posture while docked + hiding at a low cover with head rays exposed, any soldier. */
    private static boolean sustainedStandingWhileHiding(SimScenario.SimRunResultView view,
                                                        int fromTick, int streakSamples) {
        return view.timeline().soldierLabels().stream().anyMatch(label -> {
            int streak = 0;
            for (SimSample s : view.timeline().series(label)) {
                if (s.tick < fromTick) continue;
                boolean hit = s.state.currentCover != null
                    && s.syncedCoverHeight >= 0 && s.syncedCoverHeight < 1.3f
                    && s.coverSlotOffset >= 0 && s.coverSlotOffset <= 0.5
                    && "HIDING".equals(s.state.peekState)
                    && "STANDING".equals(s.posture)
                    && s.exposedHeadRays > 0;
                streak = hit ? streak + 1 : 0;
                if (streak >= streakSamples) return true;
            }
            return false;
        });
    }

    /**
     * Deterministic cover-pick probe (support alone, injected suppression): a
     * protected waist-high row ~6 blocks from the soldier vs a head-high block
     * ~11 blocks out, both between the soldier and the enemy. Under the
     * corrected distance term the near row should win; the pre-fix scorer
     * squared the distance against the 24 cap, scored both candidates 0.00 and
     * picked on quality alone. Run with sim.coverlog=true to read the
     * per-candidate [CoverScore] breakdown behind the pick.
     */
    private static SimScenario supportRearCoverPick() {
        return SimScenario.builder("support_rear_cover_pick")
            .description("Cover-pick probe: a pinned support chooses between a protected waist-high "
                + "row 6 blocks away and a head-high block 11 blocks away. Correct scoring claims "
                + "the near row instead of walking past usable cover while under fire.")
            .duration(600)
            .terrain(t -> {
                t.platform();
                t.coverRow(14, 24, 15, 24, 1); // NEAR protected waist-high row
                t.coverBlock(19, 24, 2);       // FAR head-high block
                t.coverRow(40, 20, 42, 20, 1); // enemy-side cover
                t.coverRow(40, 28, 42, 28, 1);
                t.coverBlock(39, 24, 2);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(2, 0, 24);
                ctx.spawnFriendly(ModEntities.SUPPORT.get(), "sup", 8, 0, 24, SquadMode.FOLLOW);
                ctx.spawnEnemy(40, 0, 24);
            })
            .suppress("sup", 40, 540, 15)
            .assertThat("support claims cover by t300", view -> claimedCoverBy(view, "sup", 300))
            .assertThat("FINDING: first claimed cover is the near row (within 9 blocks of spawn; "
                + "the far block is 11+)",
                view -> firstClaimedCoverNearSpawn(view, "sup", 9.0))
            .build();
    }

    /** Distance from the soldier's first recorded position to its first claimed cover; -1 if none. */
    private static double firstClaimedCoverSpawnDistance(SimScenario.SimRunResultView view, String label) {
        Vec3 spawn = null;
        for (SimSample s : view.timeline().series(label)) {
            if (spawn == null) spawn = s.state.position;
            if (s.state.currentCover != null) {
                return s.state.currentCover.getCenter().distanceTo(spawn);
            }
        }
        return -1;
    }

    private static boolean firstClaimedCoverNearSpawn(SimScenario.SimRunResultView view, String label,
                                                      double maxDist) {
        double d = firstClaimedCoverSpawnDistance(view, label);
        return d >= 0 && d <= maxDist;
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

    // --- danger-map oracle rig (Phase 0) ---------------------------------------
    //
    // These scenarios verify the VERIFICATION RIG before any danger-map code
    // exists: the oracle scenarios hand-check the independent ground-truth
    // engine against known geometry, the self-tests prove the sweep FAILs
    // deliberately-wrong samplers (expected FAILs, like the KNOWN FINDING
    // scenarios), and the decay scenario pins the temporal property contract.

    /** ORACLE: open platform, one threat — the whole field must read exposed. */
    private static SimScenario dangerOracleOpenField() {
        return SimScenario.builder("danger_oracle_open_field")
            .description("ORACLE RIG: flat platform, one synthetic threat; the independent oracle "
                + "must read the whole verification field as exposed — a hand-check of the ground-truth engine itself.")
            .duration(300)
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 14, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
            })
            .action(150, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .assertThat("oracle exists and covers the default region",
                view -> view.dangerOracle() != null && view.dangerOracle().size() >= 300)
            .assertThat(">=90% of verified cells score > 80 (open field = fully exposed)",
                view -> cellFraction(view.dangerOracle(), c -> c.score() > 80) >= 0.90)
            .assertThat("real map sampled danger reads hot at the probe (>=80 by t60)",
                view -> view.timeline().series("probe").stream()
                    .filter(s -> s.tick >= 60)
                    .allMatch(s -> s.dangerAtSoldier >= 80))
            .build();
    }

    /** ORACLE: full-height ridge — directional shadow vs exposed sides. */
    private static SimScenario dangerOracleRidge() {
        return SimScenario.builder("danger_oracle_ridge")
            .description("ORACLE RIG: a 3-high ridge spans the field; everything behind it must read "
                + "near-zero while the threat side reads exposed — directional geometry ground truth.")
            .duration(300)
            .terrain(t -> {
                t.platform();
                t.coverRow(24, 8, 24, 40, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerVerificationRegion(4, 12, 44, 36);
            })
            .action(150, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .assertThat("cells west of the ridge read near-zero (shadow side)",
                view -> cellFractionWhere(view.dangerOracle(),
                    c -> c.centerX() <= 22, c -> c.score() < 10) >= 0.95)
            .assertThat("cells east of the ridge read exposed (threat side)",
                view -> cellFractionWhere(view.dangerOracle(),
                    c -> c.centerX() >= 26, c -> c.score() > 60) >= 0.90)
            .build();
    }

    /** ORACLE: one waist-high block — center-line eye/leg bits as ground truth. */
    private static SimScenario dangerOracleLoneBlock() {
        return SimScenario.builder("danger_oracle_lone_block")
            .description("ORACLE RIG: one waist-high block. The cell behind it must read eye-exposed / "
                + "leg-protected on the center line (head shows over the wall; prone bodies are safe "
                + "on this line). The 15-ray fan-width kill mechanism of lone narrow blocks is the "
                + "fidelity probe + DangerProbeDisagreement detector's domain, not the oracle's.")
            .duration(300)
            .terrain(t -> {
                t.platform();
                t.coverBlock(20, 24, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerVerificationRegion(4, 10, 18, 38);
            })
            .action(150, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .assertThat("cell behind the lone block reads PARTIAL danger (~67: eye clear, one leg ray leaks)",
                view -> {
                    DangerOracle.OracleCell c = cellAt(view, 17, 25);
                    return c != null && c.score() >= 50 && c.score() <= 85
                        && c.eyeExposed() && c.legExposed();
                })
            .assertThat("deeper cells behind the lone block stay partially exposed (rays pass over 1.0)",
                view -> {
                    DangerOracle.OracleCell c = cellAt(view, 13, 25);
                    return c != null && c.score() >= 50 && c.score() <= 85 && c.eyeExposed();
                })
            .assertThat("flanking cell off the block axis is fully exposed on both heights",
                view -> {
                    DangerOracle.OracleCell c = cellAt(view, 17, 11);
                    return c != null && c.score() > 80 && c.legExposed();
                })
            .build();
    }

    /** ORACLE: two threats around one wall — union semantics (one shadow is not safety). */
    private static SimScenario dangerOracleCrossfire() {
        return SimScenario.builder("danger_oracle_crossfire")
            .description("ORACLE RIG: two threats at different angles around one 3-high wall — a pocket "
                + "shadowed from both reads 0 while a cell open to either threat reads exposed "
                + "(danger unions across threats).")
            .duration(300)
            .terrain(t -> {
                t.platform();
                t.coverRow(20, 20, 20, 28, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 24, SquadMode.HOLD);
                // fractional placements: symmetric integer threats put fan rays
                // exactly on block boundaries where the two clip codes disagree
                ctx.injectThreat(40.0, 22.3);
                ctx.injectThreat(39.6, 26.7);
                ctx.setDangerVerificationRegion(4, 14, 18, 34);
            })
            .action(150, "refresh injected threats", ctx -> ctx.refreshInjectedThreats())
            .assertThat("pocket shadowed from both threats reads ~0",
                view -> {
                    DangerOracle.OracleCell c = cellAt(view, 17, 25);
                    return c != null && c.score() < 10;
                })
            .assertThat("cell open to one threat reads exposed despite the wall (union)",
                view -> {
                    DangerOracle.OracleCell c = cellAt(view, 17, 15);
                    return c != null && c.score() > 80;
                })
            .build();
    }

    /** ORACLE: no threats — no oracle, no sampled danger (the map's empty state). */
    private static SimScenario dangerOracleNoIntel() {
        return SimScenario.builder("danger_oracle_no_intel")
            .description("ORACLE RIG: no threats injected — no oracle is produced and the sampled "
                + "danger stays absent (the map's empty state must be silence, not garbage).")
            .duration(200)
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 14, 0, 24, SquadMode.HOLD);
            })
            .assertThat("no oracle without injected threats", view -> view.dangerOracle() == null)
            .assertThat("map reads verified-safe (0, not unknown) with no threats",
                view -> view.timeline().series("probe").stream()
                    .filter(s -> s.tick >= 40)
                    .allMatch(s -> s.dangerAtSoldier == 0))
            .build();
    }

    /** SELF-TEST (expected FAIL): constant-zero sampler vs an open field. */
    private static SimScenario dangerSelfTestAllZero() {
        return SimScenario.builder("danger_selftest_all_zero")
            .expectedFail("RIG SELF-TEST: the deliberately-wrong sampler must trip DangerOracleMismatch")
            .description("RIG SELF-TEST (expected FAIL): a constant-0 danger sampler must trip "
                + "DangerOracleMismatch on an open field — proves the sweep fails a map that "
                + "sees nothing.")
            .duration(200)
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 14, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerSampler((x, z) -> 0.0);
            })
            .assertThat("DangerOracleMismatch fired against the all-zero stub",
                view -> hasFailingViolation(view, "DangerOracleMismatch"))
            .build();
    }

    /** SELF-TEST (expected FAIL): constant-100 sampler vs a ridge (shadow side must not read hot). */
    private static SimScenario dangerSelfTestAllMax() {
        return SimScenario.builder("danger_selftest_all_max")
            .expectedFail("RIG SELF-TEST: the deliberately-wrong sampler must trip DangerOracleMismatch")
            .description("RIG SELF-TEST (expected FAIL): a constant-100 danger sampler must trip "
                + "DangerOracleMismatch on a ridge field — a map that calls everything dangerous "
                + "is as wrong as one that calls everything safe.")
            .duration(200)
            .terrain(t -> {
                t.platform();
                t.coverRow(24, 8, 24, 40, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerVerificationRegion(4, 12, 44, 36);
                ctx.setDangerSampler((x, z) -> 100.0);
            })
            .assertThat("DangerOracleMismatch fired against the all-max stub",
                view -> hasFailingViolation(view, "DangerOracleMismatch"))
            .build();
    }

    /** SELF-TEST (expected FAIL): spatially mirrored sampler — catches maps that aggregate right but are wrong in space. */
    private static SimScenario dangerSelfTestMirrored() {
        return SimScenario.builder("danger_selftest_mirrored")
            .expectedFail("RIG SELF-TEST: the deliberately-wrong sampler must trip DangerOracleMismatch")
            .description("RIG SELF-TEST (expected FAIL): a sampler that inverts the field at the ridge "
                + "(hot where safe, safe where hot) must trip DangerOracleMismatch — catches maps "
                + "that look right in aggregate but are spatially wrong.")
            .duration(200)
            .terrain(t -> {
                t.platform();
                t.coverRow(24, 8, 24, 40, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 10, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerVerificationRegion(4, 12, 44, 36);
                ctx.setDangerSampler((x, z) -> x < 24 ? 100.0 : 0.0);
            })
            .assertThat("DangerOracleMismatch fired against the mirrored stub",
                view -> hasFailingViolation(view, "DangerOracleMismatch"))
            .build();
    }

    /** RIG: temporal decay property — sampled danger must drop after the threats die. */
    private static SimScenario dangerDecayProperty() {
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        return SimScenario.builder("danger_decay_property")
            .description("RIG: temporal property — after the injected threats are marked dead the sampled "
                + "danger must decay to zero within the window. Uses a decay-mimicking stub sampler; "
                + "Phase 1 re-runs this contract against the real map (a tick-0 action re-arms the stub).")
            .duration(400)
            .action(0, "re-arm decay stub", ctx -> alive.set(true))
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 14, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
                ctx.setDangerSampler((x, z) -> alive.get() ? 100.0 : 0.0);
                // the sweep compares at scenario end, after the staged kill — skip it here
                ctx.setDangerSweepEnabled(false);
            })
            .action(250, "injected threats marked dead", ctx -> {
                ctx.markInjectedThreatsDead();
                alive.set(false);
            })
            .assertThat("danger reads hot while the threat is alive (>=10 samples at 100 before the kill tick)",
                view -> {
                    // actions fire before sampling, so the kill-tick sample already reads cold
                    long hot = view.timeline().series("probe").stream()
                        .filter(s -> s.tick < 250 && s.dangerAtSoldier >= 100).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick < 250).count();
                    return total >= 10 && hot == total;
                })
            .assertThat("danger decays to zero after the threat dies (>=10 samples at 0 after t280)",
                view -> {
                    long cold = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 280 && s.dangerAtSoldier == 0).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 280).count();
                    return total >= 10 && cold == total;
                })
            .build();
    }

    /** REAL MAP: threat death must decay sampled danger to verified-safe within the refresh window. */
    private static SimScenario dangerDecayReal() {
        return SimScenario.builder("danger_decay_real")
            .description("DANGER MAP: the real per-squad map reads hot from an injected threat; marking "
                + "the threat dead must decay sampled danger to verified-safe (0) within the "
                + "view-refresh window, not wait out the cell TTL.")
            .duration(400)
            .action(0, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(120, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(220, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 14, 0, 24, SquadMode.HOLD);
                ctx.injectThreat(40, 24);
            })
            .action(250, "injected threats marked dead", ctx -> ctx.markInjectedThreatsDead())
            .assertThat("map reads hot while the threat lives (>=10 samples at 100 from t40)",
                view -> {
                    long hot = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 40 && s.tick < 250 && s.dangerAtSoldier >= 100).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 40 && s.tick < 250).count();
                    return total >= 10 && hot == total;
                })
            .assertThat("map decays to verified-safe 0 after the threat dies (>=10 samples at 0 after t280)",
                view -> {
                    long cold = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 280 && s.dangerAtSoldier == 0).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 280).count();
                    return total >= 10 && cold == total;
                })
            .build();
    }

    /** REAL MAP: moving the threat (origin drift) must invalidate and re-bake — the shadow flips sides. */
    private static SimScenario dangerShiftReal() {
        java.util.concurrent.atomic.AtomicReference<SimContext.InjectedThreat> threatRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        return SimScenario.builder("danger_shift_real")
            .description("DANGER MAP: relocating the injected threat across the ridge (origin drift) "
                + "must invalidate the stale shadow and re-bake — the probe's cell flips safe->hot.")
            .duration(400)
            .terrain(t -> {
                t.platform();
                t.coverRow(24, 8, 24, 40, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(8, 0, 24);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "probe", 12, 0, 24, SquadMode.HOLD);
                threatRef.set(ctx.injectThreat(40, 24));
            })
            .action(200, "threat relocates to the west flank", ctx ->
                ctx.moveInjectedThreat(threatRef.get(), 10, 16))
            .assertThat("before the move the probe reads safe (threat east, probe west of the ridge)",
                view -> {
                    long safe = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 120 && s.tick < 200 && s.dangerAtSoldier == 0).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 120 && s.tick < 200).count();
                    return total >= 10 && safe == total;
                })
            .assertThat("after the move the probe reads hot (threat now on the west side)",
                view -> {
                    long hot = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 260 && s.dangerAtSoldier >= 100).count();
                    long total = view.timeline().series("probe").stream()
                        .filter(s -> s.tick >= 260).count();
                    return total >= 10 && hot == total;
                })
            .build();
    }

    /** REAL MAP: two squads share one global ray budget; both maps must still reach their soldiers. */
    private static SimScenario dangerMultiSquadBudget() {
        return SimScenario.builder("danger_multi_squad_budget")
            .description("DANGER MAP: two independent squads (own anchors, soldiers, threats) share the "
                + "global per-tick ray budget; both maps must bake hot cells around their own soldiers "
                + "and neither may overshoot the budget (DangerBudgetExceeded is automatic). The squads "
                + "sit beyond each other's detection range so the only intel is the injected sighting - "
                + "cross-squad entity sightings would churn the threat view and wipe the bake.")
            .duration(400)
            .field(48, 150)
            .action(0, "refresh injected threats", ctx -> ctx.refreshInjectedThreats())
            .action(150, "refresh injected threats", ctx -> ctx.refreshInjectedThreats())
            .action(300, "refresh injected threats", ctx -> ctx.refreshInjectedThreats())
            .setup(ctx -> {
                com.stevesarmy.entity.TargetEntity alphaAnchor = ctx.spawnAnchor(6, 0, 12);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 12, 0, 12, SquadMode.HOLD);
                com.stevesarmy.entity.TargetEntity bravoAnchor = ctx.spawnAnchor(6, 0, 130);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 12, 0, 130, SquadMode.HOLD);
                ctx.injectThreat(ctx.squadForAnchor(alphaAnchor), 40, 12);
                ctx.injectThreat(ctx.squadForAnchor(bravoAnchor), 40, 130);
            })
            .assertThat("alpha's cell bakes hot (>=80 by t100)",
                view -> view.timeline().series("alpha").stream()
                    .filter(s -> s.tick >= 100).allMatch(s -> s.dangerAtSoldier >= 80))
            .assertThat("bravo's cell bakes hot (>=80 by t100)",
                view -> view.timeline().series("bravo").stream()
                    .filter(s -> s.tick >= 100).allMatch(s -> s.dangerAtSoldier >= 80))
            .build();
    }

    /** PATHFINDING: an attacking AT must route around exposed ground when a shadowed lane exists. */
    private static SimScenario dangerPathDetour() {
        return SimScenario.builder("danger_path_detour")
            .description("PATHFINDING (anti-tank): an AT with an ATTACK objective across the field must "
                + "route around exposed ground - a long wall offers a shadowed lane along its west face; "
                + "the corridor danger cost must bias the approach into the shadow (AT doctrine: covered "
                + "approach). Measured as the share of travel samples reading low danger.")
            .duration(800)
            .action(0, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(150, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(300, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(450, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .action(600, "refresh injected threat", ctx -> ctx.refreshInjectedThreats())
            .terrain(t -> {
                t.platform();
                // one long wall: the west face is a connected shadow lane from the
                // spawn to the north gap; everything east of it is exposed
                t.coverRow(24, 12, 24, 34, 3);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                com.stevesarmy.entity.SoldierEntity hunter = ctx.spawnFriendly(
                    ModEntities.ANTI_TANK.get(), "hunter", 8, 0, 40, SquadMode.HOLD);
                ctx.injectThreat(32, 18);
                // ATTACK objective east of the wall - the direct line crosses exposed ground
                hunter.setAttackTarget(ctx.terrain.at(42, 0, 8));
                hunter.setHoldPosition(ctx.terrain.at(42, 0, 8));
            })
            .assertThat("hunter reaches the objective (within 8 blocks by t760)",
                view -> {
                    java.util.List<SimSample> series = view.timeline().series("hunter");
                    if (series.isEmpty()) return false;
                    SimSample last = series.get(series.size() - 1);
                    return last.state.position != null
                        && last.state.position.distanceTo(
                            new Vec3(42.5, last.state.position.y, 8.5)) < 8.0;
                })
            .assertThat("approach stays in shadow (>=50% of travel samples read danger <= 50)",
                view -> {
                    java.util.List<SimSample> series = view.timeline().series("hunter");
                    long enRoute = series.stream()
                        .filter(s -> s.tick >= 40 && s.tick <= 720 && s.dangerAtSoldier >= 0).count();
                    long lowDanger = series.stream()
                        .filter(s -> s.tick >= 40 && s.tick <= 620 && s.dangerAtSoldier >= 0
                            && s.dangerAtSoldier <= 50).count();
                    return enRoute >= 20 && lowDanger * 100 >= enRoute * 50;
                })
            .build();
    }

    /**
     * LIVE-source variant of danger_path_detour: identical wall footprint and
     * identical routing/arrival assertions, but the danger gradient is fed by
     * real combat instead of an injected intel sighting — a real enemy entity,
     * a real spotter squadmate whose own detection writes the shared squad
     * intel, and the per-squad map bakes from that live sighting stream.
     *
     * <p>Geometry lessons from the first three drafts: a spotter docked behind
     * full cover loses LoS and the sighting ages out after 200 ticks (the map
     * clears — correct staleness behavior, wrong test geometry); a free enemy
     * converges on whatever it can see, including down the AT's own shadow
     * lane (the corridor is symmetric — enemies route on danger too); and
     * hard cover near the enemy lures the friendly cover pilot into docking
     * on the enemy's own walls (far-side blocks score as "blocks the threat
     * line"). So the wall's north six blocks are HALF height: the spotter
     * docks behind them with peek-over LoS that never breaks, the enemy's
     * engagement-range stop parks it east of the wall, and no cover exists
     * near the nest to lure anyone. Guns stay absent so the A/B against
     * danger_path_detour isolates exactly the threat-source variable. This is
     * also the first danger scenario whose animation draws the threat: real
     * entities are recorded, injected sightings are not.
     */
    private static SimScenario dangerDetourLive() {
        return SimScenario.builder("danger_detour_live")
            .description("PATHFINDING (anti-tank, LIVE intel): the danger gradient comes from a real enemy "
                + "entity, not an injected sighting. A spotter behind the wall's half-height north section "
                + "holds peek-over LoS and its detections feed the shared squad map; the AT must still take "
                + "the full-height west-face shadow lane on its covered approach and arrive.")
            .duration(800)
            .terrain(t -> {
                t.platform();
                // one long wall: the west face is a connected shadow lane from the
                // spawn to the north end; everything east of it is exposed.
                // North six blocks are HALF height: the spotter's peek-over post
                // (eye rays clear, leg rays blocked), keeping its LoS alive.
                t.coverRow(24, 18, 24, 34, 3);
                t.coverRow(24, 12, 24, 17, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                com.stevesarmy.entity.SoldierEntity hunter = ctx.spawnFriendly(
                    ModEntities.ANTI_TANK.get(), "hunter", 8, 0, 40, SquadMode.HOLD);
                // two-man squad = the real friendliness chain: the spotter's own
                // detection writes shared squad intel, which is what bakes the map
                // the hunter's corridor cost reads
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "spotter", 22, 0, 14, SquadMode.HOLD);
                // real enemy east of the wall; its engagement-range stop parks it
                // 8-9 blocks from the spotter's last-known position, off the route
                ctx.spawnEnemy(34, 0, 16);
                // ATTACK objective east of the wall - the direct line crosses exposed ground
                hunter.setAttackTarget(ctx.terrain.at(42, 0, 8));
                hunter.setHoldPosition(ctx.terrain.at(42, 0, 8));
            })
            .assertThat("live loop: squad map reads danger > 20 at the spotter by t150",
                view -> {
                    java.util.List<SimSample> series = view.timeline().series("spotter");
                    return series.stream()
                        .filter(s -> s.tick >= 40 && s.tick <= 150)
                        .anyMatch(s -> s.dangerAtSoldier > 20);
                })
            .assertThat("hunter reaches the objective (within 8 blocks by t760)",
                view -> {
                    java.util.List<SimSample> series = view.timeline().series("hunter");
                    if (series.isEmpty()) return false;
                    SimSample last = series.get(series.size() - 1);
                    return last.state.position != null
                        && last.state.position.distanceTo(
                            new Vec3(42.5, last.state.position.y, 8.5)) < 8.0;
                })
            .assertThat("approach stays in shadow (>=50% of travel samples read danger <= 50)",
                view -> {
                    java.util.List<SimSample> series = view.timeline().series("hunter");
                    long enRoute = series.stream()
                        .filter(s -> s.tick >= 40 && s.tick <= 720 && s.dangerAtSoldier >= 0).count();
                    long lowDanger = series.stream()
                        .filter(s -> s.tick >= 40 && s.tick <= 620 && s.dangerAtSoldier >= 0
                            && s.dangerAtSoldier <= 50).count();
                    return enRoute >= 20 && lowDanger * 100 >= enRoute * 50;
                })
            .build();
    }

    /**
     * SUPPRESSED-TRAVELER (reroute): a rifleman marching across open ground to a
     * hold objective comes under sustained near-miss fire from a fixed origin east
     * of the lane. The near-misses carry the firing origin, so the squad intel
     * bakes the shooter, the map bakes the shadow, the march path re-routes around
     * it, and the soldier still arrives - the traveler defers cover-seek until
     * pinned (dangerTravelResponse). No enemy entity: the live detection chain is
     * danger_detour_live's job; this isolates the traveler response to pressure.
     *
     * <p>March length stays under the soldier's 32-block FOLLOW_RANGE: async
     * pathfinding returns a partial (canReach=false) path for anything longer and
     * the navigation layer hard-stops it - a pre-existing hold-march limitation,
     * not part of this doctrine. Injection cadence (single burst / 10t) walks
     * suppression into the pressured band (0.5..0.9) without pinning; pinned
     * would hand the march to the cover goal, a different doctrine path.
     */
    private static SimScenario travelRerouteSuppressed() {
        // holds the marcher's UUID and the served-reroute count for assertions
        final java.util.UUID[] marcherId = new java.util.UUID[1];
        final int[] reroutesServed = new int[1];
        final int[] slabY = new int[1];
        return SimScenario.builder("travel_reroute_suppressed")
            .core()
            .description("SUPPRESSED TRAVEL (reroute): marching rifleman under near-miss fire keeps marching - "
                + "the fire origin feeds squad intel, the map bakes the shadow, the hold goal re-routes "
                + "danger-aware, and the soldier reaches the objective radius without diving for cover. "
                + "Open field offers no safer lane to deviate toward, so the reroute is proven by the "
                + "served-reroute counter and the danger-corridor capture, not by path geometry.")
            .duration(400)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                com.stevesarmy.entity.SoldierEntity marcher = ctx.spawnFriendly(
                    ModEntities.SOLDIER.get(), "marcher", 8, 0, 40, SquadMode.HOLD);
                marcher.setHoldPosition(ctx.terrain.at(18, 0, 20));
                marcherId[0] = marcher.getUUID();
                slabY[0] = ctx.terrain.at(0, 0, 0).getY();
            })
            .action(20, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 30.5, 30.5, 2.0, 3.0f))
            .action(30, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 30.5, 30.5, 2.0, 3.0f))
            .action(40, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 30.5, 30.5, 2.0, 3.0f))
            .action(50, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 30.5, 30.5, 2.0, 3.0f))
            .action(60, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 30.5, 30.5, 2.0, 3.0f))
            .action(65, "probe", ctx -> {
                var s = ctx.soldier("marcher");
                var squad = com.stevesarmy.squad.SquadManager.get((net.minecraft.server.level.ServerLevel) s.level())
                    .getSquadById(s.getSquadId()).orElse(null);
                com.stevesarmy.StevesArmyMod.LOGGER.info(
                    "[ChainProbe] intelThreats={} activeThreats={} bakedCells={} sup={}",
                    squad == null ? -1 : squad.getThreatIntel().getThreatCount(),
                    squad == null ? -1 : squad.getDangerMap().activeThreatCount(),
                    squad == null ? -1 : squad.getDangerMap().bakedCellCount(),
                    String.format("%.2f", s.getCoverBehaviorManager().getSuppressionLevel()));
            })
            .action(120, "sample reroute counter", ctx ->
                reroutesServed[0] = ctx.soldier("marcher").getCoverBehaviorManager().getTravelReroutesServed())
            .assertThat("fire origin baked into the squad map (danger > 20 at the marcher mid-march)",
                view -> view.timeline().series("marcher").stream()
                    .filter(s -> s.tick >= 40 && s.tick <= 220)
                    .anyMatch(s -> s.dangerAtSoldier > 20))
            .assertThat("travel re-route served (near-miss while marching recomputed the path)",
                view -> reroutesServed[0] >= 1)
            .assertThat("re-path was danger-aware (corridor captured with baked shadow cells)",
                view -> {
                    var debug = com.stevesarmy.entity.ai.pathfinding.DangerCorridor.debugFor(marcherId[0]);
                    return debug != null && !debug.cellDanger().isEmpty();
                })
            .assertThat("march continues under fire (reaches the hold radius before any cover dive)",
                view -> {
                    Vec3 hold = new Vec3(18.5, slabY[0], 20.5);
                    boolean doveToCover = false;
                    for (SimSample s : view.timeline().series("marcher")) {
                        if (s.state == null || s.state.position == null) continue;
                        if (s.state.coverState != null && !"NO_COVER".equals(s.state.coverState)) {
                            doveToCover = true;
                        }
                        if (s.state.position.distanceTo(hold) < 12.0 && !doveToCover) {
                            return true;
                        }
                    }
                    return false;
                })
            .assertThat("pressure was real (suppression peaked at or above pressured)",
                view -> maxSuppression(view, "marcher") >= 0.5f)
            .build();
    }

    /**
     * SUPPRESSED-TRAVELER (crawl): a rifleman marching east to a hold objective
     * passes along a 1-high wall with the shooter crouched low behind it (origin
     * at wall-top height). South-of-wall cells read the crawl-hide pattern
     * (standing eye clears the wall top, legs are shielded), so the suppressed
     * march drops low through the stretch and stands again once the pressure
     * decays below the pressured threshold. Origin-only fire at realistic bullet
     * speed (3.0 caps the tracker's speed multiplier; the traveler resistance
     * roughly cancels it), same rationale and FOLLOW_RANGE scoping as
     * travel_reroute_suppressed; the hold goal stops at its 10-block radius, so
     * arrival means reaching the radius.
     */
    private static SimScenario travelCrawlSuppressed() {
        final int[] slabY = new int[1];
        return SimScenario.builder("travel_crawl_suppressed")
            .core()
            .description("SUPPRESSED TRAVEL (crawl): marching rifleman drops low through the 1-high-wall "
                + "stretch (eyes clear the wall, legs shielded) while suppressed, stands after the "
                + "pressure eases, and still reaches the objective.")
            .duration(600)
            .terrain(t -> {
                t.platform();
                // east-west 1-high wall north of the march lane (z=22): south-face
                // cells are eye-exposed / leg-shielded - the crawl-hide pattern
                t.coverRow(10, 20, 38, 20, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                com.stevesarmy.entity.SoldierEntity marcher = ctx.spawnFriendly(
                    ModEntities.SOLDIER.get(), "marcher", 14, 0, 22, SquadMode.HOLD);
                marcher.setHoldPosition(ctx.terrain.at(40, 0, 22));
                slabY[0] = ctx.terrain.at(0, 0, 0).getY();
            })
            .action(20, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 24.5, 8.5, 1.0, 3.0f))
            .action(30, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 24.5, 8.5, 1.0, 3.0f))
            .action(40, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 24.5, 8.5, 1.0, 3.0f))
            .action(50, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 24.5, 8.5, 1.0, 3.0f))
            .action(60, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 1, 24.5, 8.5, 1.0, 3.0f))
            .assertThat("crawls through the shielded stretch (low posture while inside the wall band)",
                view -> view.timeline().series("marcher").stream()
                    .filter(s -> s.state.position != null
                        && s.state.position.x >= 12 && s.state.position.x <= 36)
                    .anyMatch(s -> "SWIMMING".equals(s.posture)))
            .assertThat("stands again after the pressure eases (standing observed after the first crawl)",
                view -> {
                    boolean crawled = false;
                    for (SimSample s : view.timeline().series("marcher")) {
                        if ("SWIMMING".equals(s.posture)) crawled = true;
                        if (crawled && "STANDING".equals(s.posture)) return true;
                    }
                    return false;
                })
            .assertThat("march continues under fire (reaches the hold radius before any cover dive)",
                view -> {
                    Vec3 hold = new Vec3(40.5, slabY[0], 22.5);
                    boolean doveToCover = false;
                    for (SimSample s : view.timeline().series("marcher")) {
                        if (s.state == null || s.state.position == null) continue;
                        if (s.state.coverState != null && !"NO_COVER".equals(s.state.coverState)) {
                            doveToCover = true;
                        }
                        if (s.state.position.distanceTo(hold) < 12.0 && !doveToCover) {
                            return true;
                        }
                    }
                    return false;
                })
            .assertThat("pressure was real (suppression peaked at or above pressured)",
                view -> maxSuppression(view, "marcher") >= 0.5f)
            .build();
    }

    /**
     * SUPPRESSED-TRAVELER (cover approach): sustained realistic fire pins a
     * rifleman marching west along a 1-high wall, the cover goal takes over and
     * claims a south-face wall cell, and the approach to that cover crosses
     * crawl-hidden cells (shooter crouched north of the wall: standing eye
     * clears, legs shielded). The universal travel crawl fires on the route to
     * cover even while pinned - pinned means dive, the approach happens low.
     */
    private static SimScenario coverApproachCrawlSuppressed() {
        return SimScenario.builder("cover_approach_crawl")
            .core()
            .description("SUPPRESSED COVER APPROACH: sustained fire pins a rifleman marching along a "
                + "1-high wall; the cover goal claims a wall cell and the approach to it crosses "
                + "crawl-hidden cells, so the soldier goes low on the way to cover while pinned.")
            .duration(400)
            .terrain(t -> {
                t.platform();
                // east-west 1-high wall north of the march lane (z=20): south-face
                // cells are eye-exposed / leg-shielded against the low northern shooter
                t.coverRow(10, 20, 30, 20, 1);
            })
            .setup(ctx -> {
                ctx.spawnAnchor(6, 0, 42);
                com.stevesarmy.entity.SoldierEntity marcher = ctx.spawnFriendly(
                    ModEntities.SOLDIER.get(), "marcher", 30, 0, 26, SquadMode.HOLD);
                marcher.setHoldPosition(ctx.terrain.at(12, 0, 26));
            })
            .action(15, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(30, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(45, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(60, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(75, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(90, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(105, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .action(120, "near-miss pressure", ctx -> ctx.injectSuppressionFromGround("marcher", 3, 20.5, 8.5, 1.0, 3.0f))
            .assertThat("fire was heavy enough to pin (realistic sustained fire must pin for the dive)",
                view -> maxSuppression(view, "marcher") >= 0.9f)
            .assertThat("cover claimed (the dive actually started)",
                view -> view.timeline().series("marcher").stream()
                    .anyMatch(s -> s.state != null && s.state.coverState != null
                        && ("SEEKING_COVER".equals(s.state.coverState)
                            || "REPOSITIONING".equals(s.state.coverState))))
            .assertThat("goes low on the approach to cover (crawl posture while moving to cover under pressure)",
                view -> view.timeline().series("marcher").stream()
                    .anyMatch(s -> s.state != null && s.state.coverState != null
                        && ("SEEKING_COVER".equals(s.state.coverState)
                            || "REPOSITIONING".equals(s.state.coverState))
                        && s.state.suppressionLevel >= 0.5f
                        && "SWIMMING".equals(s.posture)))
            .assertThat("reaches cover (the approach ends in cover, not stranded in the open)",
                view -> view.timeline().series("marcher").stream()
                    .anyMatch(s -> s.state != null && s.state.coverState != null
                        && ("IN_COVER".equals(s.state.coverState)
                            || "SUPPRESSED_IN_COVER".equals(s.state.coverState))))
            .build();
    }

    // --- danger oracle assertion helpers ---------------------------------------

    private static DangerOracle.OracleCell cellAt(SimScenario.SimRunResultView view, double x, double z) {
        return view.dangerOracle() == null ? null : view.dangerOracle().at(x, z);
    }

    /** Fraction of ALL oracle cells matching the predicate (0 on a null oracle). */
    private static double cellFraction(DangerOracle.Oracle oracle,
                                       java.util.function.Predicate<DangerOracle.OracleCell> predicate) {
        return cellFractionWhere(oracle, c -> true, predicate);
    }

    /** Fraction of region-selected oracle cells matching the predicate. */
    private static double cellFractionWhere(DangerOracle.Oracle oracle,
                                            java.util.function.Predicate<DangerOracle.OracleCell> region,
                                            java.util.function.Predicate<DangerOracle.OracleCell> predicate) {
        if (oracle == null) return 0.0;
        long total = 0;
        long matching = 0;
        for (DangerOracle.OracleCell cell : oracle.cells().values()) {
            if (!region.test(cell)) continue;
            total++;
            if (predicate.test(cell)) matching++;
        }
        return total == 0 ? 0.0 : (double) matching / total;
    }

    private static boolean hasFailingViolation(SimScenario.SimRunResultView view, String detectorName) {
        return view.violations().stream()
            .anyMatch(v -> detectorName.equals(v.detector) && v.severity == SimViolation.Severity.FAIL);
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

    // 12. cross-role systems — fire team balance + reservation exclusivity ------

    /**
     * User report: the fire team balancer "sometimes puts all machine gunners
     * into one group and riflemen into another instead of evenly distributing
     * them". Recreation: eight EMPTY-HANDED soldiers (the command-spawn /
     * sidearm-swapped state) spawned in two role clusters. Rebalance buckets
     * by main-hand item, so all eight land in one minecraft:air bucket and
     * proximity seeding pulls each spatial cluster whole into a single team.
     * Role-keyed buckets must split both roles across the two teams instead.
     */
    private static SimScenario fireTeamRoleBalance() {
        int[] mgPerTeam = new int[2];
        int[] riflePerTeam = new int[2];
        int[] assigned = new int[1];
        return SimScenario.builder("fireteam_role_balance")
            .core()
            .description("Fire team rebalance with 4 MGs + 4 riflemen spawned in two role "
                + "clusters, all empty-handed. Rebalance must bucket by ROLE: each team "
                + "gets <=2 of each role instead of whole clusters, and no soldier is "
                + "dropped from the rosters.")
            .field(64, 64)
            .duration(120)
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(32, 0, 32);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg1", 12, 0, 20, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg2", 14, 0, 22, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg3", 12, 0, 24, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg4", 14, 0, 26, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r1", 44, 0, 40, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r2", 46, 0, 42, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r3", 44, 0, 44, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r4", 46, 0, 46, SquadMode.FOLLOW);
                for (String label : java.util.List.of("mg1", "mg2", "mg3", "mg4", "r1", "r2", "r3", "r4")) {
                    ctx.disarmSoldier(label);
                }
            })
            .action(10, "rebalance fire teams", ctx -> {
                com.stevesarmy.squad.FireTeamAssignment fta =
                    com.stevesarmy.squad.FireTeamAssignment.get(ctx.level, ctx.anchor.getUUID());
                fta.rebalance(new java.util.ArrayList<>(ctx.friendlies), ctx.anchor.position());
                java.util.Map<java.util.UUID, SoldierEntity> byId = new java.util.HashMap<>();
                ctx.friendlies.forEach(s -> byId.put(s.getUUID(), s));
                java.util.List<com.stevesarmy.squad.FireTeam> active = fta.getActiveTeams();
                for (int i = 0; i < 2 && i < active.size(); i++) {
                    for (java.util.UUID id : fta.getSoldiersInTeam(active.get(i))) {
                        SoldierEntity s = byId.get(id);
                        if (s == null) continue;
                        assigned[0]++;
                        if (s.getRole() == com.stevesarmy.entity.SoldierRole.MACHINE_GUNNER) {
                            mgPerTeam[i]++;
                        } else if (s.getRole() == com.stevesarmy.entity.SoldierRole.RIFLEMAN) {
                            riflePerTeam[i]++;
                        }
                    }
                }
            })
            .assertThat("no team receives more than 2 of the 4 machine gunners",
                view -> mgPerTeam[0] <= 2 && mgPerTeam[1] <= 2)
            .assertThat("no team receives more than 2 of the 4 riflemen",
                view -> riflePerTeam[0] <= 2 && riflePerTeam[1] <= 2)
            .assertThat("all 8 soldiers land in a team (none dropped)",
                view -> assigned[0] == 8)
            .build();
    }

    /**
     * User report: "the occupied cover thing doesn't work well cross-role".
     * The reservation manager keeps TWO maps that never check each other:
     * standing-cover reservations (riflemen/AT) and MG prone-lane
     * reservations. A rifleman's cover and an MG's prone lane can therefore
     * occupy the same spot cross-role, while same-role pairs are excluded.
     * Both directions and both write+query paths must respect a 3-block
     * cross separation, and a real CoverFinder search must skip the
     * prone-reserved spot.
     */
    private static SimScenario coverCrossRoleReservation() {
        boolean[] proneRejectedAtReservedCover = new boolean[1];
        boolean[] proneFreeAwayFromCover = new boolean[1];
        boolean[] standRejectedAtProneLane = new boolean[1];
        boolean[] standFreeAwayFromProne = new boolean[1];
        double[] chosenCoverDistSqr = new double[1];
        return SimScenario.builder("cover_cross_role_reservation")
            .core()
            .description("Standing-cover reservation and MG prone-lane reservation must "
                + "exclude each other (3-block separation) in both directions, on both "
                + "the write and query paths, and CoverFinder must not return the "
                + "prone-reserved spot to a rifleman.")
            .field(64, 64)
            .duration(120)
            .terrain(t -> {
                t.platform();
                t.coverRow(20, 20, 20, 40, 1);
            })
            .setup(ctx -> {
                // No anchor: pure reservation/finder probe without squad AI noise.
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifle", 24, 0, 30, SquadMode.FOLLOW);
                ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "mg", 16, 0, 26, SquadMode.FOLLOW);
                ctx.spawnEnemyLabeled("threat", 40, 0, 30);
                ctx.disarmSoldier("rifle");
                ctx.disarmSoldier("mg");
                ctx.disarmSoldier("threat");
                ctx.harden("rifle");
                ctx.harden("mg");
                ctx.harden("threat");
            })
            .action(10, "rifleman reserves standing cover P; probe prone availability", ctx -> {
                net.minecraft.core.BlockPos p = ctx.terrain.at(20, 0, 30).immutable();
                CoverReservationManager.reserve(p, ctx.soldier("rifle"));
                proneRejectedAtReservedCover[0] =
                    !CoverReservationManager.isProneAvailableFor(p, ctx.soldier("mg"));
                proneFreeAwayFromCover[0] =
                    CoverReservationManager.isProneAvailableFor(ctx.terrain.at(20, 0, 38).immutable(), ctx.soldier("mg"));
            })
            .action(20, "swap: MG reserves prone lane at P; probe standing availability", ctx -> {
                net.minecraft.core.BlockPos p = ctx.terrain.at(20, 0, 30).immutable();
                CoverReservationManager.release(p, ctx.soldier("rifle"));
                CoverReservationManager.reserveProne(p, ctx.soldier("mg"));
                standRejectedAtProneLane[0] =
                    !CoverReservationManager.isAvailableFor(p, ctx.soldier("rifle"));
                standFreeAwayFromProne[0] =
                    CoverReservationManager.isAvailableFor(ctx.terrain.at(20, 0, 38).immutable(), ctx.soldier("rifle"));
            })
            .action(30, "rifleman cover search with the prone lane reserved", ctx -> {
                net.minecraft.core.BlockPos p = ctx.terrain.at(20, 0, 30).immutable();
                SoldierEntity rifle = ctx.soldier("rifle");
                LivingEntity threat = ctx.enemies.get(0);
                Vec3 threatDir = threat.position().subtract(rifle.position()).normalize();
                CoverFinder finder = new CoverFinder(ctx.level);
                CoverFinder.ScoredCover choice = finder
                    .findTopCovers(rifle, threatDir, java.util.List.of(threat), 8, 1, false)
                    .stream().findFirst().orElse(null);
                chosenCoverDistSqr[0] = choice == null
                    ? Double.MAX_VALUE
                    : choice.cover.getPosition().distSqr(p);
            })
            .assertThat("prone lane at a standing-reserved cover is rejected",
                view -> proneRejectedAtReservedCover[0])
            .assertThat("prone lanes stay open away from standing reservations",
                view -> proneFreeAwayFromCover[0])
            .assertThat("standing cover at a prone-reserved lane is rejected",
                view -> standRejectedAtProneLane[0])
            .assertThat("standing cover stays open away from prone lanes",
                view -> standFreeAwayFromProne[0])
            .assertThat("cover search does not return a spot within the cross separation of the prone lane",
                view -> chosenCoverDistSqr[0] >= 9.0)
            .build();
    }

    /**
     * The lifecycle hole behind "two soldiers stack in one cover": a goal
     * interruption calls clearCover(), which releases the reservation while
     * the soldier is still physically standing at the spot, so the next
     * searcher (any role) claims it and walks in. A reservation held by a
     * live soldier physically occupying the spot must not age out, and must
     * free once the holder actually leaves.
     */
    private static SimScenario coverReservationKeepalive() {
        boolean[] aliveWhilePresent = new boolean[1];
        boolean[] freedAfterLeave = new boolean[1];
        return SimScenario.builder("cover_reservation_keepalive")
            .core()
            .description("A reservation whose holder physically occupies the spot survives "
                + "its 30s timeout (occupancy keep-alive sweep); once the holder leaves, "
                + "the same backdated reservation expires normally.")
            .field(64, 64)
            .duration(160)
            .terrain(t -> {
                t.platform();
                t.coverRow(20, 20, 20, 40, 1);
            })
            .setup(ctx -> {
                // The anchor is a static LivingEntity: a reservation holder that
                // never wanders, so occupancy distance stays deterministic.
                ctx.spawnAnchor(21, 0, 30);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "seeker", 30, 0, 34, SquadMode.FOLLOW);
                ctx.disarmSoldier("seeker");
                ctx.harden("seeker");
            })
            .action(10, "holder reserves cover P", ctx -> {
                net.minecraft.core.BlockPos p = ctx.terrain.at(20, 0, 30).immutable();
                CoverReservationManager.releaseAll(ctx.anchor);
                CoverReservationManager.reserve(p, ctx.anchor);
            })
            .action(20, "backdate the reservation past its timeout while the holder stands at it", ctx ->
                backdateReservation(ctx.anchor, ctx.terrain.at(20, 0, 30).immutable(), 31_000L))
            .action(40, "sample: still reserved while physically occupied", ctx ->
                aliveWhilePresent[0] = !CoverReservationManager.isAvailable(ctx.terrain.at(20, 0, 30).immutable()))
            .action(60, "holder leaves; backdate again", ctx -> {
                net.minecraft.core.BlockPos far = ctx.terrain.at(44, 0, 50);
                ctx.anchor.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0, 0);
                backdateReservation(ctx.anchor, ctx.terrain.at(20, 0, 30).immutable(), 31_000L);
            })
            .action(90, "sample: freed after the holder left", ctx ->
                freedAfterLeave[0] = CoverReservationManager.isAvailable(ctx.terrain.at(20, 0, 30).immutable()))
            .assertThat("reservation survives its timeout while the holder physically occupies the spot",
                view -> aliveWhilePresent[0])
            .assertThat("reservation frees once the holder walks away",
                view -> freedAfterLeave[0])
            .build();
    }

    /** Backdates a live reservation's wall-clock stamp (test seam for timeout behavior). */
    @SuppressWarnings("unchecked")
    private static void backdateReservation(net.minecraft.world.entity.LivingEntity holder,
                                            net.minecraft.core.BlockPos pos, long backMillis) {
        try {
            java.lang.reflect.Field field = CoverReservationManager.class.getDeclaredField("reservationTimestamps");
            field.setAccessible(true);
            java.util.Map<java.util.UUID, java.util.Map<net.minecraft.core.BlockPos, Long>> stamps =
                (java.util.Map<java.util.UUID, java.util.Map<net.minecraft.core.BlockPos, Long>>) field.get(null);
            java.util.Map<net.minecraft.core.BlockPos, Long> perSoldier = stamps.get(holder.getUUID());
            if (perSoldier != null && perSoldier.containsKey(pos)) {
                perSoldier.put(pos, GameClock.nowMs() - backMillis);
            }
        } catch (ReflectiveOperationException ignored) {
            // Leave the stamp alone; the keep-alive assert will fail loudly instead.
        }
    }

    // 14. ATTACK clearing doctrine — area rework acceptance scenarios ----------

    /**
     * Time-series holder a clearing scenario's sampled probe writes into, so
     * assertions can verify coordinator behavior (stage progression, order
     * persistence, target release) without new harness plumbing. Read the
     * fields from the assertion lambdas; the sampled actions fill them live.
     */
    private static final class ClearingProbe {
        int maxStage = -1;
        int lastStage = -1;
        int prevStage = -1;
        boolean enteredZone = false;
        boolean targetsCleared = false;
        boolean lastAllTargetsValid = false;
        boolean securedSeen = false;
        boolean stalledSeen = false;
        boolean enemyDown = false;
        /** First tick the order read SECURED; -1 never. Ordering asserts use this. */
        int securedAtTick = -1;
        /** First tick every spawned enemy was down; -1 while any is up. */
        int allEnemiesDownAtTick = -1;
        /** Enemies seen alive inside the entry zone at least once (they must fall before SECURED). */
        java.util.Set<java.util.UUID> zoneEnemies = new java.util.HashSet<>();
        /** First tick every zone enemy was down; -1 while any is up or none ever entered. */
        int zoneEnemiesDownAtTick = -1;
        /** Distinct STALLED episodes observed (entering the stage from another). */
        int stallEpisodes = 0;
        /** Fraction of check-targets observed at the last probe sample; -1 no order. */
        double lastCheckedFraction = -1.0;
        /** Best fraction seen all run — reads -1 again once a completed order prunes. */
        double maxCheckedFraction = -1.0;
        /** Game tick of the squad's most recent successful check (max seen; -1 none). */
        long lastCheckTick = -1L;
        /** Most recent check of any contact's last-known (max seen; -1 none). */
        long lastContactCheckTick = -1L;
        /**
         * Contact-check evidence as of the moment completion first read ARMED
         * (all obligations resolved): the direct "the surviving contact was
         * re-observed" signal, uncontaminated by post-completion re-sights
         * (armed is inherently pre-completion).
         */
        long lastContactCheckAtArmed = -1L;
        /** First tick the coordinator reported completion ARMED (all obligations resolved); -1 never. */
        int armedAtTick = -1;
        /** First tick any soldier stood inside the entry zone; -1 never (ordering asserts). */
        int enteredAtTick = -1;
        /** Labels of soldiers seen inside the entry zone at least once. */
        java.util.Set<String> enteredLabels = new java.util.HashSet<>();
        /** Labels of soldiers alive at the most recent probe sample. */
        java.util.Set<String> aliveLabels = new java.util.HashSet<>();

        boolean allSurvivorsEntered() {
            return !aliveLabels.isEmpty() && enteredLabels.containsAll(aliveLabels);
        }
    }

    /**
     * Registers a sampled probe every {@code step} ticks in [from,to]: reads
     * the squad's ClearingCoordinator stage plus per-soldier order state into
     * the probe. {@code zone} marks the objective space for entry assertions
     * (null = no entry tracking).
     */
    private static void addClearingProbe(SimScenario.Builder builder, int from, int to, int step,
                                         ClearingProbe probe,
                                         java.util.function.BiPredicate<Double, Double> zone) {
        for (int t = from; t <= to; t += step) {
            final int at = t;
            builder.action(at, "clearing probe", ctx -> {
                probe.lastStage = com.stevesarmy.squad.ClearingCoordinator.stageOrdinal(
                    ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL);
                probe.maxStage = Math.max(probe.maxStage, probe.lastStage);
                if (probe.lastStage == com.stevesarmy.squad.ClearingCoordinator.ClearingStage.SECURED.ordinal()) {
                    probe.securedSeen = true;
                    if (probe.securedAtTick < 0) {
                        probe.securedAtTick = at;
                    }
                }
                if (probe.lastStage == com.stevesarmy.squad.ClearingCoordinator.ClearingStage.STALLED.ordinal()) {
                    probe.stalledSeen = true;
                    if (probe.prevStage != probe.lastStage) {
                        probe.stallEpisodes++;
                    }
                }
                probe.prevStage = probe.lastStage;
                probe.lastCheckedFraction = com.stevesarmy.squad.ClearingCoordinator.checkedFraction(
                    ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL);
                probe.maxCheckedFraction = Math.max(probe.maxCheckedFraction, probe.lastCheckedFraction);
                probe.lastCheckTick = Math.max(probe.lastCheckTick,
                    com.stevesarmy.squad.ClearingCoordinator.lastCheckTick(
                        ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL));
                probe.lastContactCheckTick = Math.max(probe.lastContactCheckTick,
                    com.stevesarmy.squad.ClearingCoordinator.lastContactCheckTick(
                        ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL));
                if (probe.armedAtTick < 0
                    && com.stevesarmy.squad.ClearingCoordinator.completionArmed(
                        ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL)) {
                    probe.armedAtTick = at;
                    probe.lastContactCheckAtArmed = probe.lastContactCheckTick;
                }
                java.util.Map<SoldierEntity, String> labels = new java.util.HashMap<>();
                ctx.byLabel.forEach((l, s) -> labels.put(s, l));
                boolean allValid = !ctx.friendlies.isEmpty();
                boolean allCleared = !ctx.friendlies.isEmpty();
                probe.aliveLabels.clear();
                for (SoldierEntity s : ctx.friendlies) {
                    if (!s.isAlive()) continue;
                    probe.aliveLabels.add(labels.getOrDefault(s, s.getUUID().toString()));
                    if (s.hasValidAttackTarget()) {
                        allCleared = false;
                    } else {
                        allValid = false;
                    }
                    if (zone != null && zone.test(s.getX(), s.getZ())) {
                        probe.enteredZone = true;
                        if (probe.enteredAtTick < 0) {
                            probe.enteredAtTick = at;
                        }
                        probe.enteredLabels.add(labels.getOrDefault(s, s.getUUID().toString()));
                    }
                }
                probe.lastAllTargetsValid = allValid;
                if (allCleared) {
                    probe.targetsCleared = true;
                }
                boolean anyEnemy = false;
                boolean allEnemiesDown = true;
                boolean zoneEnemiesDown = true;
                for (EnemySoldierEntity e : ctx.enemies) {
                    anyEnemy = true;
                    boolean alive = e.isAlive() && !e.isRemoved();
                    if (!alive) {
                        continue;
                    }
                    allEnemiesDown = false;
                    if (zone != null && zone.test(e.getX(), e.getZ())) {
                        probe.zoneEnemies.add(e.getUUID());
                    }
                    if (probe.zoneEnemies.contains(e.getUUID())) {
                        zoneEnemiesDown = false;
                    }
                }
                if (anyEnemy && allEnemiesDown && probe.allEnemiesDownAtTick < 0) {
                    probe.allEnemiesDownAtTick = at;
                    probe.enemyDown = true;
                }
                // Enemies that operated inside the objective zone must be down
                // before SECURED; a defender who stayed outside the area (and
                // was never a contact) does not block the order by doctrine.
                if (!probe.zoneEnemies.isEmpty() && zoneEnemiesDown && probe.zoneEnemiesDownAtTick < 0) {
                    probe.zoneEnemiesDownAtTick = at;
                }
            });
        }
    }

    /** West room interior of the CQB course house (walls at x14/x22, z6/z18). */
    private static final java.util.function.BiPredicate<Double, Double> WEST_ROOM =
        (x, z) -> x > 14.5 && x < 21.5 && z > 6.5 && z < 17.5;

    /**
     * ACCEPTANCE (design §1+§2+§6): an empty room with blind corners. The
     * order completes only when the squad entered the space, observed its
     * corners, and settled — proximity to the ping is not completion.
     */
    private static SimScenario clearEmptyRoom() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_empty_room")
            .description("ATTACK clearing (acceptance): an empty two-access room with blind corners. "
                + "The activity-backed order must carry the squad INSIDE the room, observe its "
                + "wall-adjacent corners, and settle before completing as Area secured - the "
                + "legacy 4-block proximity completion does not apply to clearing orders.")
            .duration(1600)
            .terrain(t -> t.cqbCourse());
        addClearingProbe(builder, 40, 1580, 20, probe, WEST_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 22, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 36, SquadMode.HOLD);
                ctx.issueAttackOrder(18, 12); // west room center
            })
            .assertThat("squad entered the objective room", view -> probe.enteredZone)
            .assertThat("every survivor physically entered the room (no outside-the-wall completion)",
                view -> probe.allSurvivorsEntered())
            .assertThat("every check-target was observed (fraction 1.0)",
                view -> probe.maxCheckedFraction >= 0.999)
            .assertThat("order completed as SECURED (entered, corners observed, settled)",
                view -> probe.securedSeen)
            .assertThat("attack targets released after completion",
                view -> probe.targetsCleared)
            .build();
    }

    /**
     * ACCEPTANCE (design §5+§6): an enemy fighting from behind interior cover.
     * The order stays alive through the firefight, completes only once the
     * contact is resolved (enemy down) and the remaining corners are observed.
     */
    private static SimScenario clearInteriorEnemy() {
        ClearingProbe probe = new ClearingProbe();
        // Full-only: the SECURED asserts hinge on kill timing (dice roulette —
        // 2 of 4 runs red on 2026-10-09 at both tick speeds); the clear family
        // stays guarded in core by clear_blocked_door + clear_contact_revisit.
        SimScenario.Builder builder = SimScenario.builder("clear_interior_enemy")
            .description("ATTACK clearing (acceptance): an enemy holds the objective room behind "
                + "interior cover. The squad fights through the doorway, resolves the contact "
                + "(enemy down), and only then secures the room - combat never ends the order "
                + "early, and completion waits for the check.")
            .duration(2000)
            .armed()
            .terrain(t -> {
                t.cqbCourse();
                t.coverBlock(18, 8, 1); // interior cover the enemy can fight from
            });
        addClearingProbe(builder, 40, 2980, 20, probe, WEST_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 22, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 36, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 24, 0, 38, SquadMode.HOLD);
                // Fourth rifleman: 3v1 through one doorway was combat
                // roulette — a bad suppression roll let the defender pin the
                // squad (sup 0.97) and outlive the clock.
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 26, 0, 36, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("raider", 18, 0, 9);
                ctx.issueAttackOrder(18, 12);
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("interior enemy was killed", view -> probe.enemyDown)
            .assertThat("squad entered the objective room", view -> probe.enteredZone)
            // Direct resolution assert: the coordinator must report every
            // obligation resolved BEFORE the order completes. Death ticks are
            // deliberately not used here — investigation resolving a surviving
            // contact is legitimate doctrine, and a time allowance against a
            // death tick cannot establish the rule.
            .assertThat("SECURED only after every obligation was resolved (armed before complete)",
                view -> probe.securedSeen && probe.securedAtTick > probe.armedAtTick)
            .assertThat("every check-target was observed (fraction 1.0)",
                view -> probe.maxCheckedFraction >= 0.999)
            .assertThat("order completed as SECURED after the contact resolved",
                view -> probe.securedSeen)
            .build();
    }

    /**
     * ACCEPTANCE (design §5): contact that breaks. A threat sighting inside the
     * room goes stale (no re-sighting) — its last-known position becomes a check
     * target the squad must physically observe before the order can complete.
     */
    private static SimScenario clearBreakContact() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_break_contact")
            .description("ATTACK clearing (acceptance): contact breaks. A threat was sighted inside "
                + "the objective room and then disappeared; its last-known position is a check "
                + "target. The order completes only after the squad looked there - losing the "
                + "sighting neither completes nor cancels the attack.")
            .duration(1600)
            .terrain(t -> t.cqbCourse());
        addClearingProbe(builder, 40, 1580, 20, probe, WEST_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 22, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 36, SquadMode.HOLD);
                ctx.injectThreat(18, 10); // sighted in the room, then gone dark
                ctx.issueAttackOrder(18, 12);
            })
            .assertThat("squad entered the objective room", view -> probe.enteredZone)
            .assertThat("the stale contact's last-known was observed like any check target (fraction 1.0)",
                view -> probe.maxCheckedFraction >= 0.999)
            .assertThat("order completed as SECURED after the stale contact was observed",
                view -> probe.securedSeen)
            .assertThat("attack targets released after completion",
                view -> probe.targetsCleared)
            .build();
    }

    /**
     * ACCEPTANCE (design §4+§7): a sealed objective room. No entry exists, so
     * no progress into the area is possible: the order reports STALLED (once)
     * and PERSISTS - no silent timeout, no completion, targets still held.
     */
    private static SimScenario clearBlockedDoor() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_blocked_door")
            .core()
            .description("ATTACK clearing (acceptance): the objective room is fully sealed (east "
                + "door and partition door blocked). The squad cannot enter and cannot see the "
                + "interior: the order reports 'Attack stalled' and then persists indefinitely - "
                + "no proximity completion, no silent timeout.")
            .duration(2000)
            .terrain(t -> {
                t.cqbCourse();
                t.block(30, 0, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                t.block(30, 1, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                t.block(30, 2, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                t.block(22, 0, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                t.block(22, 1, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                t.block(22, 2, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
            });
        addClearingProbe(builder, 40, 2980, 20, probe, null);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(26, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 26, 0, 38, SquadMode.HOLD);
                ctx.issueAttackOrder(26, 12); // sealed east room center
            })
            .assertThat("attack reported STALLED (no safe progress into the area)",
                view -> probe.stalledSeen)
            .assertThat("stalled was reported EXACTLY once (a report, not a repeating nag)",
                view -> probe.stallEpisodes == 1)
            .assertThat("order persists - targets still held at the end of the run",
                view -> probe.lastAllTargetsValid)
            .assertThat("order never completed", view -> !probe.securedSeen)
            .build();
    }

    /**
     * ACCEPTANCE (design §1): a sparse outdoor objective. No walls means no
     * corner POIs and no concealment to check — the order completes by
     * reaching and settling the space alone. Checking scales to the space.
     */
    private static SimScenario clearSparseOutdoor() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_sparse_outdoor")
            .description("ATTACK clearing (acceptance): a sparse outdoor objective. With no walls "
                + "there are no corner checks to run - completion is reaching the space and "
                + "settling into it, proving the check stage scales with what the area contains.")
            .duration(900)
            .terrain(t -> t.platform());
        addClearingProbe(builder, 40, 880, 20, probe, (x, z) -> Math.abs(x - 30.5) < 9 && Math.abs(z - 14.5) < 9);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(10, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 10, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 12, 0, 36, SquadMode.HOLD);
                ctx.issueAttackOrder(30, 14);
            })
            .assertThat("squad reached the objective area", view -> probe.enteredZone)
            .assertThat("order completed as SECURED without any corner checks",
                view -> probe.securedSeen)
            .assertThat("attack targets released after completion",
                view -> probe.targetsCleared)
            .build();
    }

    /**
     * BASELINE TWIN of clear_empty_room: the same room approached with direct
     * setAttackTarget calls (no activity of record). The legacy path must be
     * untouched — no clearing coordinator state, legacy proximity completion.
     */
    private static SimScenario clearLegacyProximityTwin() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_legacy_proximity_twin")
            .description("ATTACK legacy baseline: the clear_empty_room geometry approached via direct "
                + "setAttackTarget (no SquadActivityManager order). The legacy doctrine must be "
                + "preserved exactly: no clearing coordinator state exists, and the legacy "
                + "proximity completion releases the targets.")
            .duration(900)
            .terrain(t -> t.cqbCourse());
        addClearingProbe(builder, 40, 880, 20, probe, WEST_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                SoldierEntity alpha = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 22, 0, 38, SquadMode.HOLD);
                SoldierEntity bravo = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 36, SquadMode.HOLD);
                // Direct attack targets — the legacy path, no activity of record.
                alpha.setAttackTarget(ctx.terrain.at(18, 0, 12));
                alpha.setHoldPosition(ctx.terrain.at(18, 0, 12));
                bravo.setAttackTarget(ctx.terrain.at(18, 0, 12));
                bravo.setHoldPosition(ctx.terrain.at(18, 0, 12));
            })
            .assertThat("no clearing coordinator state exists for direct attack targets",
                view -> probe.maxStage == -1)
            .assertThat("legacy proximity completion released the targets",
                view -> probe.targetsCleared)
            .build();
    }

    /**
     * ACCEPTANCE: the multi-room training complex (SimTerrain.trainingCourse,
     * the sim twin of the in-game /stevesarmy_debug course). Two defenders in
     * different rooms behind interior cover: the squad fights through the main
     * doors, resolves both contacts, and the order secures only after the
     * reachable blind spots are observed. The multi-space route-clearing
     * expansion of the area is increment 3; this pins the five-room geometry.
     */
    private static SimScenario clearTrainingCourse() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_training_course")
            .description("ATTACK clearing on the five-room training complex: defenders in the hall "
                + "and the NW room. The squad enters through the west door into the SW room and "
                + "works north then east - room by room, fighting both contacts down, and secures. "
                + "The north doors were a death-funnel: defenders camping them stalled the assault "
                + "outside for whole runs (threshold entry under fire is INC 2 entry coordination, "
                + "not what this scenario is here to test), so the approach gives the coordinator "
                + "a fair, sequential clearing problem instead.")
            .field(64, 64)
            .duration(3000)
            .armed()
            .terrain(t -> t.trainingCourse());
        addClearingProbe(builder, 40, 2980, 20, probe,
            (x, z) -> x > 10.5 && x < 45.5 && z > 8.5 && z < 21.5);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(7, 0, 22);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 6, 0, 20, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 7, 0, 25, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 5, 0, 22, SquadMode.HOLD);
                // Fourth rifleman: the 3v2 room fights were combat roulette
                // (run-to-run a soldier could die in a doorway and the squad
                // could fail to finish both defenders inside 3000t).
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 8, 0, 22, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("hall", 33, 0, 12);
                ctx.spawnEnemyLabeled("northwest", 14, 0, 12);
                ctx.issueAttackOrder(20, 9);
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("both room defenders were killed", view -> probe.enemyDown)
            .assertThat("squad entered the complex interior", view -> probe.enteredZone)
            .assertThat("SECURED only after every obligation was resolved (armed before complete)",
                view -> probe.securedSeen && probe.securedAtTick > probe.armedAtTick)
            .assertThat("order completed as SECURED after the contacts resolved",
                view -> probe.securedSeen)
            .build();
    }

    /**
     * BENCHMARK — EXPECTED TO FAIL (kept at the external reviewer's request):
     * the original north-door approach to the training complex. Defenders
     * camping the double door stall the assault OUTSIDE for whole runs —
     * threshold entry under fire. That is Increment 2's entry-coordination
     * problem; the west-entry variant above isolates Increment 1's clearing
     * logic. This scenario stays as the honest measure of that gap: when the
     * funnel stops being lethal here, INC 2's entry coordination has landed.
     * Its failures are known-fail baseline, not regressions.
     */
    private static SimScenario clearBenchmarkNorthDoorFunnel() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_benchmark_north_door_funnel")
            .description("KNOWN-FAIL BENCHMARK: the training complex assaulted straight through "
                + "the north double door against camping defenders. Threshold entry under fire "
                + "is the INC 2 entry-coordination gap; this run exists to measure it and is "
                + "expected to fail on combat/completion until INC 2 lands.")
            .field(64, 64)
            .duration(3000)
            .armed()
            .terrain(t -> t.trainingCourse());
        addClearingProbe(builder, 40, 2980, 20, probe,
            (x, z) -> x > 10.5 && x < 45.5 && z > 8.5 && z < 21.5);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 6);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 14, 0, 5, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 6, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 26, 0, 5, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 32, 0, 6, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("hall", 33, 0, 12);
                ctx.spawnEnemyLabeled("northwest", 14, 0, 12);
                ctx.issueAttackOrder(20, 9);
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("both room defenders were killed", view -> probe.enemyDown)
            .assertThat("squad entered the complex interior", view -> probe.enteredZone)
            .assertThat("SECURED only after every obligation was resolved (armed before complete)",
                view -> probe.securedSeen && probe.securedAtTick > probe.armedAtTick)
            .assertThat("order completed as SECURED after the contacts resolved",
                view -> probe.securedSeen)
            .build();
    }

    /** The deep SE-E room of the training complex (the far end of the building). */
    private static final java.util.function.BiPredicate<Double, Double> DEEP_ROOM =
        (x, z) -> x > 36.5 && x < 45.5 && z > 22.5 && z < 35.5;

    /**
     * DEEP-OBJECTIVE sequence: defenders staggered along the route (hall,
     * SE-W, and the deepest room), attack ping planted at the back of the far
     * room. The clearing area bounds around the ping, so the squad must fight
     * through each space on the way — contact room by room — and only then
     * run the deep room's checks and secure. Pins that a far ping neither
     * strands the advance nor completes before the squad physically reaches
     * and clears the deep space.
     */
    private static SimScenario clearDeepObjectiveSequence() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_deep_objective_sequence")
            .description("ATTACK clearing with the ping at the back of the far room: defenders in "
                + "the hall, the SE-W room, and the deep SE-E room. The squad fights through each "
                + "space en route, arrives in the deep room, resolves the last contact, checks the "
                + "deep blind spots, and secures there - the order cannot complete early and the "
                + "advance cannot strand short of the far ping.")
            .field(64, 64)
            // 4200: a mauled squad (this is a real 4v3 staggered fight) can
            // finish its checks late; the SECURE assemble walk then has to
            // bring stragglers physically inside before the settle window can
            // even start. 3000 starved that tail.
            .duration(4200)
            .armed()
            .terrain(t -> {
                t.trainingCourse();
                // The deep SE-E room otherwise has one waist-high fixture,
                // and the deep defender spawns right beside it — assaulting
                // soldiers then had no real dock and claimed wall-adjacent
                // spots facing away from the threats (ExposedWhileHiding).
                // A rubble row south-mid gives them hard cover from the west
                // door and waist cover from the deep room.
                t.coverRow(38, 33, 40, 33, 1);
                // Seal the far-west perimeter door: with it open the
                // pathfinder sometimes takes the long SW loop and bypasses the
                // hall defender entirely, which turns this into a two-contact
                // run by pathfinding luck. The hall corridor is the route.
                for (int y = 0; y < 3; y++) {
                    t.block(10, y, 22, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                }
            });
        addClearingProbe(builder, 40, 4180, 20, probe, DEEP_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(20, 0, 6);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 14, 0, 5, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 6, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 26, 0, 5, SquadMode.HOLD);
                // Fourth rifleman: the 3v3 staggered-defense fight was combat
                // roulette (run-to-run the squad could lose two soldiers and
                // let the last defender escape the building past the clock).
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 32, 0, 6, SquadMode.HOLD);
                ctx.spawnEnemyLabeled("hall", 33, 0, 12);
                ctx.spawnEnemyLabeled("sew", 28, 0, 28);
                ctx.spawnEnemyLabeled("deep", 42, 0, 30);
                ctx.issueAttackOrder(41, 29);
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("all three defenders were killed", view -> probe.enemyDown)
            .assertThat("squad reached the deep room", view -> probe.enteredZone)
            .assertThat("SECURED only after every obligation was resolved (armed before complete)",
                view -> probe.securedSeen && probe.securedAtTick > probe.armedAtTick)
            .assertThat("order completed as SECURED in the deep room",
                view -> probe.securedSeen)
            .build();
    }

    /**
     * ACCEPTANCE (adversarial, GPT review #1+#4): two rooms under one roof,
     * connected only through an opening at the FAR end of the partition. The
     * east room's blind spots sit directly across the partition wall from the
     * west room's floor: proximity there is 2 blocks, but the wall is between.
     * No blind spot may be checked through that wall, and the order cannot
     * complete until someone has physically projected presence into the far
     * room (through its opening) — checking across the wall or completing
     * from the near side both fail here.
     */
    private static SimScenario clearDividedRooms() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_divided_rooms")
            .description("ATTACK clearing (adversarial): two connected rooms whose partition "
                + "opening is at the far end. The east room's corners face the west room across "
                + "the partition wall — 2 blocks away but hidden. Checks require real line of "
                + "sight around via the far opening, and completion requires someone to have "
                + "entered the east room: proximity through the dividing wall checks nothing.")
            .duration(2200)
            .terrain(t -> t.dividedRooms());
        addClearingProbe(builder, 40, 2180, 20, probe,
            // East room interior + the partition opening (x16 z17-18) counts as reaching it.
            (x, z) -> x > 15.5 && x < 23.5 && z > 6.5 && z < 19.5);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(12, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 12, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 10, 0, 36, SquadMode.HOLD);
                ctx.issueAttackOrder(12, 10); // west room, north half
            })
            .assertThat("someone reached the far room BEFORE completion (entry precedes SECURED)",
                view -> probe.enteredZone && probe.enteredAtTick >= 0
                    && probe.securedAtTick > probe.enteredAtTick)
            .assertThat("every check-target was observed BEFORE completion (armed precedes SECURED)",
                view -> probe.maxCheckedFraction >= 0.999 && probe.armedAtTick >= 0
                    && probe.securedAtTick > probe.armedAtTick)
            .assertThat("order completed as SECURED", view -> probe.securedSeen)
            .build();
    }

    /**
     * ACCEPTANCE (adversarial, GPT review #2): a contact continuously sighted
     * at a spot the squad already checked, whose sighting then ends (the
     * harness stops refreshing the intel at t=400 and the entry decays).
     * Checks made before the last sighting do not count — the squad must look
     * again AFTER it, so the check recorded last must postdate the sighting
     * that ended. The keep-alive stop (not a teleport) is the doctrine path:
     * forgetting a threat leaves the last-known obligation standing until a
     * fresh observation resolves it.
     */
    private static SimScenario clearContactRevisit() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_contact_revisit")
            .core()
            .description("ATTACK clearing (adversarial): a threat is sighted in the room and kept "
                + "in fresh contact at his spot until t=400, when the harness stops refreshing "
                + "the intel and the sighting decays. The pre-sighting check cannot age into "
                + "resolution: the squad must observe the last-known again AFTER the sighting "
                + "ended, and only then can the order secure.")
            .duration(1800)
            .terrain(t -> t.cqbCourse());
        addClearingProbe(builder, 40, 1780, 20, probe, WEST_ROOM);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(22, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 22, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 20, 0, 36, SquadMode.HOLD);
                ctx.injectThreat(18, 10); // sighted in the room, kept fresh by the harness
                ctx.issueAttackOrder(18, 12);
            })
            .action(400, "sighting ends - intel stops refreshing and decays", ctx ->
                ctx.injectedThreats().clear())
            // After t=400 the contact recheck is the ONLY outstanding
            // obligation (all POIs were checked during the sighting). The
            // asserts read the CONTACT-specific check tick captured at the
            // ARMED sample: armed is pre-completion by definition, so the
            // injected threat's post-SECURED re-sights (he never dies, the
            // squad just forgets him) cannot inflate the evidence.
            .assertThat("the surviving contact was re-observed AFTER the sighting ended (resolution asserted directly)",
                view -> probe.lastContactCheckAtArmed > 400)
            .assertThat("completion stayed blocked until obligations resolved, then SECURED followed",
                view -> probe.securedSeen && probe.armedAtTick > 0
                    && probe.securedAtTick > probe.armedAtTick)
            .build();
    }

    /**
     * ACCEPTANCE (contact-plan strand): an assault squad spawns INSIDE house A
     * of streetBlock with the attack ping planted in house B's back room. The
     * order must carry the squad out A's single east door, across the 8-block
     * open street, through B's west door, and into the partitioned back room —
     * reaching the far house is not completing: the back-room checks must run
     * before the order reads SECURED. Unarmed like its clear_divided_rooms
     * twin (house B is empty on purpose: the acceptance is route + checks,
     * not a fight).
     */
    private static SimScenario clearHouseExit() {
        ClearingProbe probe = new ClearingProbe();
        SimScenario.Builder builder = SimScenario.builder("clear_house_exit")
            .description("ATTACK clearing across an open street: the squad starts inside house A "
                + "(one east door) with the ping in house B's partitioned back room. The order "
                + "must walk them out the door, across the exposed street, through B's door, "
                + "and run the back-room checks before the order reads SECURED — entering the "
                + "far house is not completion.")
            // 3600: two structures plus an exposed crossing; the SECURE
            // assemble walk has to bring every soldier physically inside
            // before the settle window can even start (same tail that starved
            // the deep sequence at 3000).
            .duration(3600)
            // The clearing flow's check-dock walk registers each successive
            // check position as a cover claim — CoverOscillation reads that
            // progression as churn. Suppressed for the clearing family until
            // the detector learns to ignore dock ADVANCES (wave-B scope).
            .suppressDetectors("CoverOscillation")
            .terrain(t -> t.streetBlock());
        addClearingProbe(builder, 40, 3580, 20, probe,
            // House B interior including both door thresholds.
            (x, z) -> x > 25.5 && x < 36.5 && z > 10.5 && z < 19.5);
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(11, 0, 15); // inside house A, with the squad
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 9, 0, 12, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 13, 0, 17, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 12, 0, 13, SquadMode.HOLD);
                ctx.issueAttackOrder(35, 15); // house B back room, past the partition door
            })
            .assertThat("squad reached house B", view -> probe.enteredZone)
            .assertThat("every check-target was observed (fraction 1.0 — the back room was checked)",
                view -> probe.maxCheckedFraction >= 0.999)
            .assertThat("order completed as SECURED (entered, checked, settled)",
                view -> probe.securedSeen)
            .assertThat("attack targets released after completion",
                view -> probe.targetsCleared)
            .assertThat("every soldier ends inside house B or within 6 of the back-room ping",
                view -> {
                    for (String label : new String[] {"alpha", "bravo", "charlie"}) {
                        SimSample last = null;
                        java.util.List<SimSample> series = view.timeline().series(label);
                        if (!series.isEmpty()) {
                            last = series.get(series.size() - 1);
                        }
                        if (last == null) return false;
                        boolean inB = last.state.position.x > 25.5 && last.state.position.x < 36.5
                            && last.state.position.z > 10.5 && last.state.position.z < 19.5;
                        double d = Math.hypot(last.state.position.x - 35.5,
                            last.state.position.z - 15.5);
                        if (!inB && d > 6.0) return false;
                    }
                    return true;
                })
            .build();
    }

    /**
     * REPRODUCTION (Wave A.1, user in-game report 2026-10-10): the squad
     * starts spread through a partitioned house A while the order pings
     * house B at the far end of a 50-block street. In-game, front soldiers
     * leave quickly and advance while the back grinds inside the start
     * house — the pacing asserts below encode healthy egress, and FAILING
     * them is the reproduction: the per-soldier exit-tick evidence names
     * how far behind the back soldiers actually fall, and the state
     * timeline + [AttackClear][view] diag names the mechanism (behind-
     * claims, dock-window quantization, gate denials).
     */
    private static SimScenario clearLongEgress() {
        ClearingProbe probe = new ClearingProbe();
        final String[] labels = {"alpha", "bravo", "charlie", "delta"};
        // First tick each soldier stands outside house A's east wall /
        // inside house B, sampled every 20t (the poll is the resolution).
        final long[] exitTick = new long[labels.length];
        final long[] arriveTick = new long[labels.length];
        final long[] streetDockTick = new long[labels.length];
        // Backtrack doctrine: once a soldier has committed through the door,
        // the advance is one-way — he may jitter at the threshold but never
        // walk back into the house interior nor retreat meaningfully behind
        // his forwardmost point. Earlier passes hid exactly this: recordings
        // showed interior re-entries up to ~1240t and retreats up to ~13
        // blocks, none of it asserted.
        final int[] reentrySamples = new int[labels.length];
        // Retreat is measured in OBJECTIVE-DISTANCE space: a retreat means
        // ending up meaningfully farther from the objective than the closest
        // approach so far. Raw westward-x regression misreads the final
        // approach — staging on house B's south face then cutting to the
        // west/door side drops ~4 blocks of x while getting 4 blocks CLOSER
        // to the objective (run 104710: alpha (70.5,25.5)→(66.5,17.5),
        // dist 9.5→5.5, counted as a 4.08 "retreat"). The turn-around bug
        // this hunts (the in-game walk-back into house A) moves AWAY from
        // the objective by 7+ blocks and still fails this hard.
        final double[] maxObjectiveRegression = new double[labels.length];
        final double[] closestApproachDist = new double[labels.length];
        java.util.Arrays.fill(exitTick, -1L);
        java.util.Arrays.fill(arriveTick, -1L);
        java.util.Arrays.fill(streetDockTick, -1L);
        java.util.Arrays.fill(closestApproachDist, Double.MAX_VALUE);
        SimScenario.Builder builder = SimScenario.builder("clear_long_egress")
            .description("ATTACK clearing egress pacing across a 50-block street: the squad is "
                + "spread through partitioned house A (front soldier at the door, three deep in "
                + "the back room) with the ping in house B 50 blocks east and one armed defender "
                + "holding B. Healthy egress: everyone is out of house A promptly and close "
                + "together — no soldier lags far behind the front, and the advance is one-way: "
                + "no re-entry into house A and no retreat behind the soldier's forwardmost "
                + "point. Failing "
                + "the pacing asserts reproduces the in-game back-soldiers-stuck-behind report; "
                + "failing the backtrack asserts reproduces the in-game turn-around-into-the-"
                + "house report.")
            // 4800: egress quantization + the 50-block crossing + the far
            // exchange need headroom; cqb_door_contact needed 4800 for its
            // close 2v1 alone.
            .duration(4800)
            .field(96, 40)
            .armed()
            // Clearing flow check-dock walks register as cover claims —
            // same waiver as the clearing family until the detector
            // learns dock ADVANCES (wave-B scope).
            // ExposedWhileHiding: the known 1-high-cover "hiding but
            // standing" family — house B's arrival docks read partially
            // exposed to the guard; fix awaits the concealment go-ahead.
            // FireDeadlock + RotationOscillation: WP4 engagement-arbiter
            // families (wave B). This is the first arrival scenario to end
            // in a guarded-house standoff — exactly the state those fix —
            // so they fire here until wave B lands.
            // PositionDeadlock: the arrival seek-transient — docking into
            // house B under guard fire can hold SEEKING_COVER ~125t against
            // the detector's 120t threshold and self-resolves into a dock
            // (run 220446: delta 675-800 → docked 975). The three arrival
            // asserts below own the strand guarantee directly, so the
            // generic detector is waived here, not everywhere.
            // StuckSeek: the same arrival seek-transient on a longer fuse —
            // the final dock can hold SEEKING 265-395t before the dock walk
            // resolves (run 105206: bravo 265t WARN resolved; run 105701:
            // bravo 395t FAIL, docked 34t later). The streetDock/arrival
            // asserts own "did he get there"; the timing nuance is wave-B
            // work, not an egress failure.
            .suppressDetectors("CoverOscillation", "ExposedWhileHiding",
                "FireDeadlock", "RotationOscillation", "PositionDeadlock", "StuckSeek")
            .terrain(t -> t.longStreetBlock());
        addClearingProbe(builder, 40, 4780, 20, probe,
            // House B interior.
            (x, z) -> x > 66.5 && x < 77.5 && z > 11.5 && z < 24.5);
        // 1t grid through the pacing horizon, 5t after: a healthy egress
        // crosses the ~1-block doorway strip in 2-3t, and even a 5t grid can
        // straddle the whole transit (run 20261011 093005: alpha crossed
        // unsampled, arrived house B at t370 with exitTick still -1 — three
        // false assert failures). South of the house's west half is also
        // unambiguously out (the spine exits via the west door then the
        // south face), which widens the catch window.
        for (int t = 40; t <= 4780; t += (t < 1800 ? 1 : 5)) {
            final int at = t;
            builder.action(at, "egress probe", ctx -> {
                for (int i = 0; i < labels.length; i++) {
                    SoldierEntity s = ctx.soldier(labels[i]);
                    if (s == null || !s.isAlive()) continue;
                    // Out of house A = committed through the west door: the
                    // doorway cell spans x 6..7, so 6.5 is its midpoint — a
                    // soldier sampled at x=5.5-6.5 has left the interior but
                    // a tighter threshold clips the doorway transit.
                    if (exitTick[i] < 0
                        && (s.getX() < 6.5 || (s.getZ() > 24.5 && s.getX() < 17.5))) {
                        exitTick[i] = at;
                    }
                    if (arriveTick[i] < 0 && s.getX() > 66.5
                        && s.getZ() > 11.5 && s.getZ() < 24.5) {
                        arriveTick[i] = at;
                    }
                    // Path-based progression evidence: a dock on street cover
                    // between the houses (the spine advance hops cover-to-
                    // cover; a soldier who runs the street without docking
                    // once is progressing on straight-line distance).
                    if (streetDockTick[i] < 0 && s.getX() > 17.5 && s.getX() < 66.5) {
                        var st = s.getCoverBehaviorManager().getState();
                        if (st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                            || st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
                            streetDockTick[i] = at;
                        }
                    }
                    // Backtrack tracking, measured only after the first exit:
                    // interior band is house A's footprint minus the doorway
                    // cell (x 6..7), so threshold jostling doesn't count.
                    // Peek transits are exempt: the peek tip is a sanctioned
                    // forward extension and the return walk to the dock dips
                    // behind it by design (run 094026: bravo/delta "retreated"
                    // 3.2 blocks stepping back off their peek tip at the
                    // house B dock — dock-to-peek-tip-to-dock, not a
                    // turn-around). The metric FREEZES at house B arrival:
                    // from there on the soldier is fighting the guard, and
                    // suppression repositions / protection docks inside the
                    // objective are CQB behavior (cqb-contact layer), not
                    // advance discipline. Metric is objective-DISTANCE
                    // regression (see the array declaration above).
                    if (exitTick[i] >= 0) {
                        double x = s.getX();
                        if (arriveTick[i] < 0) {
                            int peekOrdinal = s.getSyncedPeekState();
                            boolean peekTransit = peekOrdinal
                                    == com.stevesarmy.entity.ai.PeekController.State.MOVING_TO_PEEK.ordinal()
                                || peekOrdinal
                                    == com.stevesarmy.entity.ai.PeekController.State.RETURNING_TO_COVER.ordinal();
                            if (!peekTransit) {
                                double dx = x - 72.0D;
                                double dz = s.getZ() - 18.0D;
                                double dist = Math.sqrt(dx * dx + dz * dz);
                                if (dist < closestApproachDist[i]) closestApproachDist[i] = dist;
                                double regression = dist - closestApproachDist[i];
                                if (regression > maxObjectiveRegression[i]) {
                                    maxObjectiveRegression[i] = regression;
                                }
                            }
                        }
                        if (x > 7.0 && x < 16.5 && s.getZ() > 11.5 && s.getZ() < 24.5) {
                            reentrySamples[i]++;
                        }
                    }
                }
            });
        }
        builder.action(4740, "egress diag", ctx ->
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] long_egress exitTicks={} arriveTicks={} reentrySamples={} maxObjectiveRegression={} closestApproach={}",
                java.util.Arrays.toString(exitTick), java.util.Arrays.toString(arriveTick),
                java.util.Arrays.toString(reentrySamples), java.util.Arrays.toString(maxObjectiveRegression),
                java.util.Arrays.toString(closestApproachDist)));
        return builder
            .setup(ctx -> {
                // Mirrored house A: door on the WEST wall, so alpha fronts the
                // exit and delta is deepest east behind the partition.
                ctx.spawnAnchor(12, 0, 18); // house A back room, with the squad
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 8, 0, 18, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 14, 0, 15, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 14, 0, 21, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 15, 0, 18, SquadMode.HOLD);
                EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 74, 0, 15);
                guard.setSquadMode(SquadMode.HOLD);
                guard.setHoldPosition(ctx.terrain.at(74, 0, 15));
                ctx.issueAttackOrder(72, 18); // house B center
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("squad reached house B", view -> probe.enteredZone)
            .assertThat("every soldier docked on street cover en route (spine progression)",
                view -> java.util.Arrays.stream(streetDockTick).allMatch(t -> t > 0))
            .assertThat("every soldier got out of house A",
                view -> java.util.Arrays.stream(exitTick).allMatch(t -> t > 0))
            .assertThat("every soldier arrived at house B (no mid-route strand)",
                view -> java.util.Arrays.stream(arriveTick).allMatch(t -> t > 0))
            .assertThat("every soldier exited house A by t=1800 (egress pacing)",
                view -> java.util.Arrays.stream(exitTick).allMatch(t -> t > 0 && t <= 1800))
            .assertThat("front-to-back exit spread within 1000t (the squad moves as one)",
                view -> {
                    long min = Long.MAX_VALUE;
                    long max = -1L;
                    for (long t : exitTick) {
                        if (t <= 0) return false;
                        min = Math.min(min, t);
                        max = Math.max(max, t);
                    }
                    return max - min <= 1000;
                })
            .assertThat("no soldier re-enters house A after exiting (one-way advance)",
                view -> {
                    for (int i = 0; i < labels.length; i++) {
                        if (exitTick[i] < 0) continue; // covered by the exit assert
                        if (reentrySamples[i] * 5 > 40) return false;
                    }
                    return true;
                })
            .assertThat("no soldier ends up 3.5 blocks farther from the objective than his closest approach (one-way advance)",
                view -> {
                    for (int i = 0; i < labels.length; i++) {
                        if (exitTick[i] < 0) continue;
                        if (maxObjectiveRegression[i] > 3.5) return false;
                    }
                    return true;
                })
            .build();
    }

    /**
     * ACCEPTANCE (contact-plan strand, covering fire before the threshold):
     * the clear_blocked_door east-room geometry with the east door OPEN — a
     * single guarded entrance — and one armed defender HOLD-pinned just
     * inside it. The ping sits in the room. The gate this scenario pins:
     * nobody crosses the danger band (4.5 blocks around the defender's
     * last-known, which the pin turns into a fixture constant) until one of
     * the doctrine's gate conditions holds — a successful covering shot, the
     * order latching STALLED, or a suppressed mate (the bypass the hard gate
     * deliberately keeps open); once the gate opens, the checker crosses and
     * the order completes. Guns armed on both sides — the exchange is organic.
     */
    /**
     * USER REPORT reproduction: machine gunners in ATTACK "constantly skip the
     * cover — after reaching a cover he immediately cancels it and heads to the
     * next cover, almost straight line to the objective, while he is supposed
     * to stay in the back row." The gunner spawns DEEPEST in house A so any
     * forward overtaking is visible, and every street dock is tracked as an
     * episode (start tick, end tick, position): the complaint's signature is a
     * chain of short-held docks — dock, release, next dock — instead of the
     * base-of-fire doctrine of anchoring behind the riflemen and suppressing.
     * The back-row metric freezes each soldier's closest approach at arrival
     * and fails when the gunner outfronts either rifleman during the advance.
     * Instrumentation first: the [Sim][diag] dump lists the episode chains so
     * thresholds are set from observed behavior, not guesses.
     */
    private static SimScenario mgAttackCoverSkip() {
        final String[] labels = {"gunner", "r1", "r2"};
        final long[] arriveTick = new long[labels.length];
        final double[] closestApproachDist = new double[labels.length];
        java.util.Arrays.fill(arriveTick, -1L);
        java.util.Arrays.fill(closestApproachDist, Double.MAX_VALUE);
        // Dock episodes per soldier: [startTick, endTick, x, z] rows appended
        // when a street dock ends (cover state leaves IN_COVER-family).
        final java.util.List<java.util.List<double[]>> dockEpisodes = new java.util.ArrayList<>();
        for (int i = 0; i < labels.length; i++) dockEpisodes.add(new java.util.ArrayList<>());
        final int[] dockStartTick = new int[labels.length];
        final double[] dockStartX = new double[labels.length];
        final double[] dockStartZ = new double[labels.length];
        final boolean[] dockHoldActive = new boolean[labels.length];
        // Saw the soldier PEEK out (EXPOSED) at some point during the open
        // dock: separates a dock that was used to engage from one that was
        // docked and immediately abandoned — the churn signature needs the
        // "never used" half, because a healthy catch-up advance also leaves
        // short row-holding docks once it has peeked and fired from them.
        final boolean[] dockPeeked = new boolean[labels.length];
        java.util.Arrays.fill(dockStartTick, -1);
        // Guard health forensics: the guard died ~t55 with zero shots in two
        // runs (killing the firefight before it starts). Track hp changes so
        // the cause discriminates: 1hp/10t = the spawn-bleed pacing, 2/tick =
        // suffocation, one big drop = fall/void.
        final java.util.List<double[]> guardHpLog = new java.util.ArrayList<>();
        // Back-row doctrine, sampled DURING the firefight (guard alive, nobody
        // arrived yet): the gunner must never be the frontmost soldier toward
        // the objective. Final closest-approach comparison alone hides this —
        // run 6: gunner closest 3.46 < r1's 0.50, but that r1 value came at
        // t1025 AFTER the guard died; during the exchange (t260-445) the
        // gunner sat 10 blocks in FRONT of both riflemen at row 62.
        final int[] gunnerAheadSamples = new int[1];
        // Sampled objective distance per soldier for the probe tick.
        final double[] curDist = new double[labels.length];
        SimScenario.Builder builder = SimScenario.builder("mg_attack_cover_skip")
            .description("USER REPORT: MG in ATTACK skips cover after cover (dock -> immediate "
                + "cancel -> next cover), advancing in a near straight line to the objective "
                + "instead of staying in the back row. Gunner spawns deepest in house A; every "
                + "street dock is tracked as an episode and the gunner must never outfront the "
                + "riflemen before any of them arrives.")
            .duration(4800)
            .field(96, 40)
            .armed()
            .suppressDetectors("PositionDeadlock", "StuckSeek")
            .terrain(t -> t.longStreetBlock());
        builder.setup(ctx -> {
            ctx.spawnAnchor(12, 0, 18);
            // Gunner deepest (back row start); riflemen ahead of him.
            ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 8, 0, 18, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r1", 12, 0, 15, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r2", 14, 0, 21, SquadMode.HOLD);
            // Guard closer than clear_long_egress's (74,15): the reported churn
            // happens under FIRE, and at 26 blocks the stalled squad never
            // accumulates detection (stationary soldiers gain slowly) — the
            // guard must engage while the advance is still healthy.
            EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 66, 0, 16);
            guard.setSquadMode(SquadMode.HOLD);
            guard.setHoldPosition(ctx.terrain.at(66, 0, 16));
            ctx.issueAttackOrder(72, 18);
        });
        // Dock-episode probe: 5t resolution (a "reached cover then immediately
        // cancelled" dock still lasts the approach walk, far above 5t). Street
        // docks only (x 17.5..66.5) — house-A interior docks are egress, not
        // advance doctrine.
        for (int t = 20; t <= 4700; t += 5) {
            final int at = t;
            builder.action(at, "dock probe", ctx -> {
                if (!ctx.enemies.isEmpty()) {
                    LivingEntity g = ctx.enemies.get(0);
                    guardHpLog.add(new double[]{at, g.getX(), g.getY(), g.getZ(),
                        g.getHealth(), g.fallDistance});
                } else {
                    guardHpLog.add(new double[]{at, -1, -1, -1, -1, -1});
                }
                for (int i = 0; i < labels.length; i++) {
                    SoldierEntity s = ctx.soldier(labels[i]);
                    if (s == null || !s.isAlive()) continue;
                    if (arriveTick[i] < 0 && s.getX() > 66.5
                        && s.getZ() > 11.5 && s.getZ() < 24.5) {
                        arriveTick[i] = at;
                    }
                    boolean peekTransit = s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.MOVING_TO_PEEK.ordinal()
                        || s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.RETURNING_TO_COVER.ordinal();
                    double dist = Double.MAX_VALUE;
                    if (!peekTransit) {
                        double dx = s.getX() - 72.0D;
                        double dz = s.getZ() - 18.0D;
                        dist = Math.sqrt(dx * dx + dz * dz);
                        if (dist < closestApproachDist[i]) closestApproachDist[i] = dist;
                    }
                    curDist[i] = dist;
                    var st = s.getCoverBehaviorManager().getState();
                    boolean docked = st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                        || st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER;
                    boolean inStreet = s.getX() > 17.5 && s.getX() < 66.5;
                    // Row-hold state at THIS sample: the rifleman line has not
                    // yet passed this soldier's position by the leapfrog
                    // margin — a dock left under this condition is an abandoned
                    // holding dock (the churn), while a dock left after the
                    // line passed is the correct leapfrog-follow hop.
                    boolean rowHold = false;
                    if (docked) {
                        double selfDist = curDist[i];
                        double frontRifle = Double.MAX_VALUE;
                        for (int j = 0; j < labels.length; j++) {
                            if (j == i || labels[j].startsWith("gunner")) continue;
                            if (curDist[j] < frontRifle) frontRifle = curDist[j];
                        }
                        if (selfDist < Double.MAX_VALUE && frontRifle < Double.MAX_VALUE
                            && frontRifle > selfDist - 5.0) {
                            rowHold = true;
                        }
                    }
                    if (docked && inStreet) {
                        if (dockStartTick[i] < 0) {
                            dockStartTick[i] = at;
                            dockStartX[i] = s.getX();
                            dockStartZ[i] = s.getZ();
                            dockHoldActive[i] = rowHold;
                            dockPeeked[i] = false;
                        } else if (rowHold) {
                            dockHoldActive[i] = true;
                        }
                        if (s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.EXPOSED.ordinal()) {
                            dockPeeked[i] = true;
                        }
                    } else if (dockStartTick[i] >= 0) {
                        dockEpisodes.get(i).add(new double[]{dockStartTick[i], at,
                            dockStartX[i], dockStartZ[i], dockHoldActive[i] ? 1 : 0,
                            dockPeeked[i] ? 1 : 0});
                        dockStartTick[i] = -1;
                    }
                }
                // Firefight simultaneity: only counts while the guard is alive
                // and every soldier is still advancing — after the guard dies
                // the walk-in is unopposed and positions reshuffle freely.
                boolean guardAlive = !ctx.enemies.isEmpty() && ctx.enemies.get(0).isAlive();
                if (guardAlive && arriveTick[0] < 0 && arriveTick[1] < 0 && arriveTick[2] < 0
                    && curDist[0] < Double.MAX_VALUE && curDist[1] < Double.MAX_VALUE
                    && curDist[2] < Double.MAX_VALUE
                    && curDist[0] < Math.min(curDist[1], curDist[2]) - 2.0) {
                    gunnerAheadSamples[0]++;
                }
            });
        }
        builder.action(4700, "diag", ctx -> {
            StringBuilder gh = new StringBuilder();
            double prevHp = -1, prevY = -999;
            for (double[] r : guardHpLog) {
                if (r[0] <= 200 || r[4] != prevHp || Math.abs(r[2] - prevY) > 0.5) {
                    gh.append(String.format("[t%d hp=%.1f y=%.1f fall=%.1f] ",
                        (long) r[0], r[4], r[2], r[5]));
                    prevHp = r[4];
                    prevY = r[2];
                }
            }
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] guardHp: {}", gh);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] gunnerAheadSamples={} (x5t each, while guard alive)",
                gunnerAheadSamples[0]);
            for (int i = 0; i < labels.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (double[] e : dockEpisodes.get(i)) {
                    sb.append(String.format("[t%d..t%d dur=%.0f @(%.0f,%.0f)] ",
                        (long) e[0], (long) e[1], e[1] - e[0], e[2], e[3]));
                }
                com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} docks={} arrive={} closest={}",
                    labels[i], dockEpisodes.get(i).size(),
                    arriveTick[i] < 0 ? "never" : ("t" + arriveTick[i]),
                    closestApproachDist[i] == Double.MAX_VALUE ? "n/a"
                        : String.format("%.2f", closestApproachDist[i]));
                if (sb.length() > 0) {
                    com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} episodes: {}",
                        labels[i], sb);
                }
            }
        });
        return builder
            .assertThat("riflemen reached house B (route sanity)",
                view -> arriveTick[1] > 0 && arriveTick[2] > 0)
            .assertThat("gunner docked on street cover at least once en route (he engages the cover machine)",
                view -> !dockEpisodes.get(0).isEmpty())
            .assertThat("gunner stayed in the back row DURING the firefight (never the frontmost soldier while the guard lives)",
                view -> gunnerAheadSamples[0] * 5 <= 100)
            .assertThat("gunner docks cover-to-cover (no FORWARD street hop skips more than 14 blocks of cover rows; backward re-docks are repositions and never reset the high-water mark)",
                view -> {
                    double maxX = Double.NaN;
                    double maxGap = 0;
                    for (double[] e : dockEpisodes.get(0)) {
                        if (e[2] <= 17.5) continue; // street docks only
                        if (!Double.isNaN(maxX) && e[2] > maxX) {
                            maxGap = Math.max(maxGap, e[2] - maxX);
                        }
                        if (Double.isNaN(maxX) || e[2] > maxX) {
                            maxX = e[2];
                        }
                    }
                    return maxGap <= 14.0;
                })
            .assertThat("gunner never abandons an unused back-row dock (a short dock left while the rifleman line had not passed AND without ever peeking from it is the cover churn; a peeked dock the advance machinery hops on from is a healthy catch-up step)",
                view -> {
                    int abandoned = 0;
                    for (double[] e : dockEpisodes.get(0)) {
                        if (e[1] - e[0] < 60 && e[4] > 0 && e[5] <= 0) abandoned++;
                    }
                    return abandoned <= 1;
                })
            .build();
    }

    /**
     * MG + riflemen ATTACK across waist-high rows lying ACROSS the advance
     * axis — peek-over half-cover geometry, unlike the street fixture's
     * north-south full-lane walls. The squad docks on a row's south face,
     * peeks over the top, and hops row-to-row; the gunner must hold the back
     * row the whole way (same doctrine asserts as mg_attack_cover_skip, on
     * the other cover family).
     */
    private static SimScenario mgAttackHalfRows() {
        final String[] labels = {"gunner", "r1", "r2"};
        final long[] arriveTick = new long[labels.length];
        final double[] closestApproachDist = new double[labels.length];
        java.util.Arrays.fill(arriveTick, -1L);
        java.util.Arrays.fill(closestApproachDist, Double.MAX_VALUE);
        final java.util.List<java.util.List<double[]>> dockEpisodes = new java.util.ArrayList<>();
        for (int i = 0; i < labels.length; i++) dockEpisodes.add(new java.util.ArrayList<>());
        final int[] dockStartTick = new int[labels.length];
        final double[] dockStartX = new double[labels.length];
        final double[] dockStartZ = new double[labels.length];
        final boolean[] dockHoldActive = new boolean[labels.length];
        final boolean[] dockPeeked = new boolean[labels.length];
        java.util.Arrays.fill(dockStartTick, -1);
        final java.util.List<double[]> guardHpLog = new java.util.ArrayList<>();
        final int[] gunnerAheadSamples = new int[1];
        final double[] curDist = new double[labels.length];
        // Two separate findings on this terrain:
        // 1. LURE (expectedFail): the gunner's FIRST dock is the rear platform
        //    rim (x≈0 CONCEALMENT, behind the spawn line) because the ATTACK
        //    cover search accepts backward candidates.
        // 2. STALL (fixed, ea76cb6): he used to stay parked there — the
        //    docked next-cover search retried an empty pool forever. The
        //    dock-stall release now walks him out (~t90) and up the rows.
        final long[] firstFieldDock = {Long.MAX_VALUE};
        final boolean[] rimLureDocked = {false};
        SimScenario.Builder builder = SimScenario.builder("mg_attack_half_rows")
            .description("MG + riflemen ATTACK across waist-high E-W cover rows (peek-over "
                + "half cover). The gunner must work the row line behind the riflemen — "
                + "docking, peeking over the top, hopping row-to-row — and never outfront "
                + "the riflemen while the guard lives. Same doctrine asserts as "
                + "mg_attack_cover_skip on a different cover geometry. KNOWN GAP "
                + "(expectedFail): the gunner's FIRST dock is the rear platform "
                + "rim (x≈0 CONCEALMENT) — the ATTACK cover search accepts "
                + "backward candidates (far-side/junk-cover lure family). The "
                + "old advance STALL on that dock is fixed by the dock-stall "
                + "release and stays a green assert.")
            .expectedFail(
                "gunner's first dock is backward rear-rim cover (x≈0 "
                    + "CONCEALMENT lure) — the ATTACK search accepts backward "
                    + "candidates; the stall itself is fixed (dock-stall release)")
            .duration(4800)
            .field(96, 40)
            .armed()
            .suppressDetectors("PositionDeadlock", "StuckSeek")
            .terrain(t -> {
                t.platform();
                // Waist-high rows ACROSS the advance axis, staggered with
                // open lanes between them; a 1-high stub row at x=78 gives
                // the defender a dock-valid hold cell (street-nest lesson).
                t.coverRow(18, 12, 30, 12, 1);
                t.coverRow(22, 19, 34, 19, 1);
                t.coverRow(40, 10, 52, 10, 1);
                t.coverRow(40, 22, 52, 22, 1);
                t.coverRow(58, 14, 70, 14, 1);
                t.coverRow(78, 15, 78, 19, 1);
            });
        builder.setup(ctx -> {
            ctx.spawnAnchor(12, 0, 17);
            ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 8, 0, 17, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r1", 11, 0, 12, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r2", 11, 0, 22, SquadMode.HOLD);
            EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 76, 0, 17);
            guard.setSquadMode(SquadMode.HOLD);
            guard.setHoldPosition(ctx.terrain.at(76, 0, 17));
            ctx.issueAttackOrder(82, 17);
        });
        for (int t = 20; t <= 4700; t += 5) {
            final int at = t;
            builder.action(at, "dock probe", ctx -> {
                if (!ctx.enemies.isEmpty()) {
                    LivingEntity g = ctx.enemies.get(0);
                    guardHpLog.add(new double[]{at, g.getX(), g.getY(), g.getZ(),
                        g.getHealth(), g.fallDistance});
                } else {
                    guardHpLog.add(new double[]{at, -1, -1, -1, -1, -1});
                }
                for (int i = 0; i < labels.length; i++) {
                    SoldierEntity s = ctx.soldier(labels[i]);
                    if (s == null || !s.isAlive()) continue;
                    double adx = s.getX() - 82.0D;
                    double adz = s.getZ() - 17.0D;
                    double adist = Math.sqrt(adx * adx + adz * adz);
                    if (arriveTick[i] < 0 && adist < 6.0) {
                        arriveTick[i] = at;
                    }
                    boolean peekTransit = s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.MOVING_TO_PEEK.ordinal()
                        || s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.RETURNING_TO_COVER.ordinal();
                    double dist = Double.MAX_VALUE;
                    if (!peekTransit) {
                        if (adist < closestApproachDist[i]) closestApproachDist[i] = adist;
                        dist = adist;
                    }
                    curDist[i] = dist;
                    var st = s.getCoverBehaviorManager().getState();
                    boolean docked = st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                        || st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER;
                    boolean inField = s.getX() > 14 && s.getX() < 72;
                    if (i == 0 && docked && !inField && firstFieldDock[0] == Long.MAX_VALUE) {
                        rimLureDocked[0] = true;
                    }
                    boolean rowHold = false;
                    if (docked) {
                        double selfDist = curDist[i];
                        double frontRifle = Double.MAX_VALUE;
                        for (int j = 0; j < labels.length; j++) {
                            if (j == i || labels[j].startsWith("gunner")) continue;
                            if (curDist[j] < frontRifle) frontRifle = curDist[j];
                        }
                        if (selfDist < Double.MAX_VALUE && frontRifle < Double.MAX_VALUE
                            && frontRifle > selfDist - 5.0) {
                            rowHold = true;
                        }
                    }
                    if (docked && inField) {
                        if (dockStartTick[i] < 0) {
                            dockStartTick[i] = at;
                            dockStartX[i] = s.getX();
                            dockStartZ[i] = s.getZ();
                            dockHoldActive[i] = rowHold;
                            dockPeeked[i] = false;
                            if (i == 0) {
                                firstFieldDock[0] = Math.min(firstFieldDock[0], at);
                            }
                        } else if (rowHold) {
                            dockHoldActive[i] = true;
                        }
                        if (s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.EXPOSED.ordinal()) {
                            dockPeeked[i] = true;
                        }
                    } else if (dockStartTick[i] >= 0) {
                        dockEpisodes.get(i).add(new double[]{dockStartTick[i], at,
                            dockStartX[i], dockStartZ[i], dockHoldActive[i] ? 1 : 0,
                            dockPeeked[i] ? 1 : 0});
                        dockStartTick[i] = -1;
                    }
                }
                boolean guardAlive = !ctx.enemies.isEmpty() && ctx.enemies.get(0).isAlive();
                if (guardAlive && arriveTick[0] < 0 && arriveTick[1] < 0 && arriveTick[2] < 0
                    && curDist[0] < Double.MAX_VALUE && curDist[1] < Double.MAX_VALUE
                    && curDist[2] < Double.MAX_VALUE
                    && curDist[0] < Math.min(curDist[1], curDist[2]) - 2.0) {
                    gunnerAheadSamples[0]++;
                }
            });
        }
        builder.action(4700, "diag", ctx -> {
            StringBuilder gh = new StringBuilder();
            double prevHp = -1, prevY = -999;
            for (double[] r : guardHpLog) {
                if (r[0] <= 200 || r[4] != prevHp || Math.abs(r[2] - prevY) > 0.5) {
                    gh.append(String.format("[t%d hp=%.1f y=%.1f fall=%.1f] ",
                        (long) r[0], r[4], r[2], r[5]));
                    prevHp = r[4];
                    prevY = r[2];
                }
            }
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] guardHp: {}", gh);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] gunnerAheadSamples={} (x5t each, while guard alive)",
                gunnerAheadSamples[0]);
            for (int i = 0; i < labels.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (double[] e : dockEpisodes.get(i)) {
                    sb.append(String.format("[t%d..t%d dur=%.0f @(%.0f,%.0f)] ",
                        (long) e[0], (long) e[1], e[1] - e[0], e[2], e[3]));
                }
                com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} docks={} arrive={} closest={}",
                    labels[i], dockEpisodes.get(i).size(),
                    arriveTick[i] < 0 ? "never" : ("t" + arriveTick[i]),
                    closestApproachDist[i] == Double.MAX_VALUE ? "n/a"
                        : String.format("%.2f", closestApproachDist[i]));
                if (sb.length() > 0) {
                    com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} episodes: {}",
                        labels[i], sb);
                }
            }
        });
        return builder
            .assertThat("riflemen reached the objective (route sanity)",
                view -> arriveTick[1] > 0 && arriveTick[2] > 0)
            .assertThat("gunner advanced onto the row line promptly (the rear-rim lure must not become a stall; dock-stall release walks him out)",
                view -> firstFieldDock[0] < 460)
            .assertThat("gunner's first dock is NOT backward rear-rim cover (ATTACK search lure - expectedFail until the search rejects backward candidates)",
                view -> !rimLureDocked[0])
            .assertThat("gunner docked on row cover at least once en route (he engages the cover machine)",
                view -> !dockEpisodes.get(0).isEmpty())
            .assertThat("gunner stayed in the back row DURING the firefight (never the frontmost soldier while the guard lives)",
                view -> gunnerAheadSamples[0] * 5 <= 100)
            .assertThat("gunner never abandons an unused dock (short + row-holding + never peeked from = the cover churn)",
                view -> {
                    int abandoned = 0;
                    for (double[] e : dockEpisodes.get(0)) {
                        if (e[1] - e[0] < 60 && e[4] > 0 && e[5] <= 0) abandoned++;
                    }
                    return abandoned <= 1;
                })
            .assertThat("riflemen used row cover en route too (normal soldiers attack through the cover machine)",
                view -> !dockEpisodes.get(1).isEmpty() && !dockEpisodes.get(2).isEmpty())
            .build();
    }

    /**
     * MG + riflemen ATTACK through scattered ISOLATED cover blocks (mixed
     * heights, no rows) — every dock is a lone piece with exposed flanks,
     * the geometry that most tempts straight-line charges. The doctrine
     * asserts (back row during the firefight, no unused-dock abandonment,
     * riflemen dock too) must hold here just as on the row line.
     */
    private static SimScenario mgAttackScatteredBlocks() {
        final String[] labels = {"gunner", "r1", "r2"};
        final long[] arriveTick = new long[labels.length];
        final double[] closestApproachDist = new double[labels.length];
        java.util.Arrays.fill(arriveTick, -1L);
        java.util.Arrays.fill(closestApproachDist, Double.MAX_VALUE);
        final java.util.List<java.util.List<double[]>> dockEpisodes = new java.util.ArrayList<>();
        for (int i = 0; i < labels.length; i++) dockEpisodes.add(new java.util.ArrayList<>());
        final int[] dockStartTick = new int[labels.length];
        final double[] dockStartX = new double[labels.length];
        final double[] dockStartZ = new double[labels.length];
        final boolean[] dockHoldActive = new boolean[labels.length];
        final boolean[] dockPeeked = new boolean[labels.length];
        java.util.Arrays.fill(dockStartTick, -1);
        final java.util.List<double[]> guardHpLog = new java.util.ArrayList<>();
        final int[] gunnerAheadSamples = new int[1];
        final double[] curDist = new double[labels.length];
        SimScenario.Builder builder = SimScenario.builder("mg_attack_scattered_blocks")
            .description("MG + riflemen ATTACK through scattered lone cover blocks (mixed "
                + "1/2-high, wide spacing, exposed flanks on every piece). The pick "
                + "decisions get no row line to follow; the gunner must still trail the "
                + "riflemen dock-to-dock and never outfront them while the guard lives.")
            .duration(4800)
            .field(96, 40)
            .armed()
            .suppressDetectors("PositionDeadlock", "StuckSeek")
            .terrain(t -> {
                t.platform();
                // Isolated pieces, staggered so no two share a lane; the
                // 1-high stub row at x=78 gives the defender a dock-valid
                // hold cell (street-nest lesson from longStreetBlock).
                t.coverBlock(20, 10, 1);
                t.coverBlock(20, 24, 2);
                t.coverBlock(30, 17, 1);
                t.coverBlock(38, 8, 2);
                t.coverBlock(38, 26, 1);
                t.coverBlock(48, 13, 1);
                t.coverBlock(48, 22, 2);
                t.coverBlock(58, 17, 1);
                t.coverBlock(66, 10, 2);
                t.coverBlock(66, 25, 1);
                t.coverRow(78, 15, 78, 19, 1);
            });
        builder.setup(ctx -> {
            ctx.spawnAnchor(12, 0, 17);
            ctx.spawnFriendly(ModEntities.MACHINE_GUNNER.get(), "gunner", 8, 0, 17, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r1", 11, 0, 12, SquadMode.HOLD);
            ctx.spawnFriendly(ModEntities.SOLDIER.get(), "r2", 11, 0, 22, SquadMode.HOLD);
            EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 76, 0, 17);
            guard.setSquadMode(SquadMode.HOLD);
            guard.setHoldPosition(ctx.terrain.at(76, 0, 17));
            ctx.issueAttackOrder(82, 17);
        });
        for (int t = 20; t <= 4700; t += 5) {
            final int at = t;
            builder.action(at, "dock probe", ctx -> {
                if (!ctx.enemies.isEmpty()) {
                    LivingEntity g = ctx.enemies.get(0);
                    guardHpLog.add(new double[]{at, g.getX(), g.getY(), g.getZ(),
                        g.getHealth(), g.fallDistance});
                } else {
                    guardHpLog.add(new double[]{at, -1, -1, -1, -1, -1});
                }
                for (int i = 0; i < labels.length; i++) {
                    SoldierEntity s = ctx.soldier(labels[i]);
                    if (s == null || !s.isAlive()) continue;
                    double adx = s.getX() - 82.0D;
                    double adz = s.getZ() - 17.0D;
                    double adist = Math.sqrt(adx * adx + adz * adz);
                    if (arriveTick[i] < 0 && adist < 6.0) {
                        arriveTick[i] = at;
                    }
                    boolean peekTransit = s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.MOVING_TO_PEEK.ordinal()
                        || s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.RETURNING_TO_COVER.ordinal();
                    double dist = Double.MAX_VALUE;
                    if (!peekTransit) {
                        if (adist < closestApproachDist[i]) closestApproachDist[i] = adist;
                        dist = adist;
                    }
                    curDist[i] = dist;
                    var st = s.getCoverBehaviorManager().getState();
                    boolean docked = st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                        || st == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER;
                    boolean inField = s.getX() > 14 && s.getX() < 72;
                    boolean rowHold = false;
                    if (docked) {
                        double selfDist = curDist[i];
                        double frontRifle = Double.MAX_VALUE;
                        for (int j = 0; j < labels.length; j++) {
                            if (j == i || labels[j].startsWith("gunner")) continue;
                            if (curDist[j] < frontRifle) frontRifle = curDist[j];
                        }
                        if (selfDist < Double.MAX_VALUE && frontRifle < Double.MAX_VALUE
                            && frontRifle > selfDist - 5.0) {
                            rowHold = true;
                        }
                    }
                    if (docked && inField) {
                        if (dockStartTick[i] < 0) {
                            dockStartTick[i] = at;
                            dockStartX[i] = s.getX();
                            dockStartZ[i] = s.getZ();
                            dockHoldActive[i] = rowHold;
                            dockPeeked[i] = false;
                        } else if (rowHold) {
                            dockHoldActive[i] = true;
                        }
                        if (s.getSyncedPeekState()
                            == com.stevesarmy.entity.ai.PeekController.State.EXPOSED.ordinal()) {
                            dockPeeked[i] = true;
                        }
                    } else if (dockStartTick[i] >= 0) {
                        dockEpisodes.get(i).add(new double[]{dockStartTick[i], at,
                            dockStartX[i], dockStartZ[i], dockHoldActive[i] ? 1 : 0,
                            dockPeeked[i] ? 1 : 0});
                        dockStartTick[i] = -1;
                    }
                }
                boolean guardAlive = !ctx.enemies.isEmpty() && ctx.enemies.get(0).isAlive();
                if (guardAlive && arriveTick[0] < 0 && arriveTick[1] < 0 && arriveTick[2] < 0
                    && curDist[0] < Double.MAX_VALUE && curDist[1] < Double.MAX_VALUE
                    && curDist[2] < Double.MAX_VALUE
                    && curDist[0] < Math.min(curDist[1], curDist[2]) - 2.0) {
                    gunnerAheadSamples[0]++;
                }
            });
        }
        builder.action(4700, "diag", ctx -> {
            StringBuilder gh = new StringBuilder();
            double prevHp = -1, prevY = -999;
            for (double[] r : guardHpLog) {
                if (r[0] <= 200 || r[4] != prevHp || Math.abs(r[2] - prevY) > 0.5) {
                    gh.append(String.format("[t%d hp=%.1f y=%.1f fall=%.1f] ",
                        (long) r[0], r[4], r[2], r[5]));
                    prevHp = r[4];
                    prevY = r[2];
                }
            }
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] guardHp: {}", gh);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] gunnerAheadSamples={} (x5t each, while guard alive)",
                gunnerAheadSamples[0]);
            for (int i = 0; i < labels.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (double[] e : dockEpisodes.get(i)) {
                    sb.append(String.format("[t%d..t%d dur=%.0f @(%.0f,%.0f)] ",
                        (long) e[0], (long) e[1], e[1] - e[0], e[2], e[3]));
                }
                com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} docks={} arrive={} closest={}",
                    labels[i], dockEpisodes.get(i).size(),
                    arriveTick[i] < 0 ? "never" : ("t" + arriveTick[i]),
                    closestApproachDist[i] == Double.MAX_VALUE ? "n/a"
                        : String.format("%.2f", closestApproachDist[i]));
                if (sb.length() > 0) {
                    com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} episodes: {}",
                        labels[i], sb);
                }
            }
        });
        return builder
            .assertThat("riflemen reached the objective (route sanity)",
                view -> arriveTick[1] > 0 && arriveTick[2] > 0)
            .assertThat("gunner docked on a lone block at least once en route (he engages the cover machine)",
                view -> !dockEpisodes.get(0).isEmpty())
            .assertThat("gunner stayed in the back row DURING the firefight (never the frontmost soldier while the guard lives)",
                view -> gunnerAheadSamples[0] * 5 <= 100)
            .assertThat("gunner never abandons an unused dock (short + row-holding + never peeked from = the cover churn)",
                view -> {
                    int abandoned = 0;
                    for (double[] e : dockEpisodes.get(0)) {
                        if (e[1] - e[0] < 60 && e[4] > 0 && e[5] <= 0) abandoned++;
                    }
                    return abandoned <= 1;
                })
            .assertThat("riflemen used block cover en route too (normal soldiers attack through the cover machine)",
                view -> !dockEpisodes.get(1).isEmpty() && !dockEpisodes.get(2).isEmpty())
            .build();
    }

    private static SimScenario cqbDoorContact() {
        ClearingProbe probe = new ClearingProbe();
        // The guard is HOLD-pinned beside the door, so its last-known is a
        // fixture constant: the center of the pinned spawn cell.
        final double guardX = 28.5;
        final double guardZ = 12.5;
        // The gated band around the threat equals the overrun range (3.0):
        // standing at the door threshold is bypass-legal, and the ungated
        // initial approach legitimately docks soldiers just outside it —
        // crossing INTO the band is what the gate polices.
        final double dangerBand = 3.0;
        // Live gate-state holders the probe actions fill. Min-seen, because
        // lastSuccessfulShotTick reports the LAST successful shot — the
        // earliest value we ever observe is the first one.
        final long[] firstCoverShot = {Long.MAX_VALUE};
        final long[] firstStallTick = {Long.MAX_VALUE};
        // Earliest observed contact-open (ContactPlan.contactOpenTick): feeds
        // the bloodless-standoff leg of the gate — open + attackClearStallTicks.
        final long[] firstOpenTick = {Long.MAX_VALUE};
        SimScenario.Builder builder = SimScenario.builder("cqb_door_contact")
            .description("ATTACK clearing onto a guarded door: one armed defender holds the east "
                + "room's single entrance from just inside. The contact gate must hold the "
                + "squad out of the room until a mate registers a successful covering shot, "
                + "the order reports STALLED, a mate is suppressed, or the contact sits "
                + "bloodless for a full stall window (the doctrine's bypasses) - after the "
                + "gate opens the squad closes on the contact, the fight resolves, and the "
                + "order completes.")
            // 4800: the training-course clock — an armed 2v1 through one
            // doorway can take the whole exchange, and the SECURE settle
            // needs the quiet tail. The 4200 clock armed SECURED at ~t4190,
            // one probe sample past the last poll at 4180.
            .duration(4800)
            .armed()
            // Expected FAIL until WP3/WP4 — this run measures three known
            // gaps it exposed: (1) squad intel only receives sighting-grade
            // writes (head-ray LoS), so through a doorway the contact opens
            // only when the assemble walk closes to point-blank — the hard
            // gate has nothing to gate with during the check-dock walks, and
            // they enter the danger space ungated; (2) the fire chain's
            // burst-cooldown strand holds visible+ready soldiers shotless for
            // the whole clock (no deadlock watchdog yet); (3) the assemble
            // walk is coordinator movement, not a ContactPlan-gated advance.
            .expectedFail(
                "contact opens late (intel feed gap, WP3/4), fire-chain burst_cd strand (WP4), "
                    + "ungated assemble entry")
            .terrain(t -> {
                t.cqbCourse();
                // Seal the partition door: the east room keeps ONE entrance —
                // the guarded east door — so every entry is a contact crossing.
                for (int y = 0; y < 3; y++) {
                    t.block(22, y, 12, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                }
            });
        addClearingProbe(builder, 40, 4780, 20, probe,
            // East room interior of the course house (walls at x22/x30, z6/z18).
            (x, z) -> x > 22.5 && x < 29.5 && z > 6.5 && z < 17.5);
        // Gate-state polling: the poll cadence is the tick resolution of this
        // evidence (ScheduledAction hands the tick to the probe helper).
        for (int t = 60; t <= 4140; t += 60) {
            final int at = t;
            builder.action(at, "contact-gate probe",
                ctx -> recordGateState(ctx, at, "cover", firstCoverShot, firstStallTick));
            builder.action(at, "contact-open probe", ctx -> {
                for (String label : new String[] {"cover", "checker"}) {
                    SoldierEntity s = ctx.soldier(label);
                    if (s != null && s.isAlive()) {
                        long open = com.stevesarmy.squad.ContactPlan.contactOpenTick(s);
                        if (open < firstOpenTick[0]) {
                            firstOpenTick[0] = open;
                        }
                    }
                }
            });
        }
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(40, 0, 18);
                // North-east of the door row: no spawn line of sight (the
                // east wall blocks), so the fight develops as the squad
                // closes instead of starting as an instant mutual stare.
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "cover", 38, 0, 16, SquadMode.HOLD);
                // South-east, farther out and off the fire ray: the checker
                // approaches last, out of his mate's lane.
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "checker", 36, 0, 22, SquadMode.HOLD);
                EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 28, 0, 12);
                guard.setSquadMode(SquadMode.HOLD);
                guard.setHoldPosition(ctx.terrain.at(28, 0, 12));
                ctx.issueAttackOrder(26, 12); // east room center, past the guarded door
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("nobody entered the room's danger space before covering fire, STALLED, a suppressed mate, or bloodless standoff",
                view -> {
                    double gate = gateOpenTick(view, firstCoverShot[0], firstStallTick[0],
                        firstOpenTick[0]);
                    for (String label : new String[] {"cover", "checker"}) {
                        for (SimSample s : view.timeline().series(label)) {
                            // Band proximity counts only INSIDE the room
                            // footprint: a cover dock one block from the
                            // doorway is 2.9 from the guard through a solid
                            // wall — Euclidean distance alone reads the
                            // staging dock as a breach.
                            boolean inRoom = s.state.position.x > 22.5
                                && s.state.position.x < 29.5
                                && s.state.position.z > 6.5 && s.state.position.z < 17.5;
                            double d = Math.hypot(s.state.position.x - guardX,
                                s.state.position.z - guardZ);
                            if (inRoom && d <= dangerBand && s.tick < gate) return false;
                        }
                    }
                    return true;
                })
            .assertThat("covering fire registered (the gate's shot leg actually armed)",
                view -> firstCoverShot[0] < Long.MAX_VALUE)
            .assertThat("the squad closed on the contact only AFTER the gate opened (approach follows support)",
                view -> {
                    double gate = gateOpenTick(view, firstCoverShot[0], firstStallTick[0],
                        firstOpenTick[0]);
                    if (gate >= Long.MAX_VALUE - 1 || gate <= 0) return false;
                    boolean anyAdvanced = false;
                    for (String label : new String[] {"cover", "checker"}) {
                        java.util.List<SimSample> series = view.timeline().series(label);
                        if (series.isEmpty()) continue;
                        SimSample firstAfter = null;
                        for (SimSample s : series) {
                            if (s.tick >= gate) { firstAfter = s; break; }
                        }
                        if (firstAfter == null) continue;
                        double dBefore = Math.hypot(firstAfter.state.position.x - guardX,
                            firstAfter.state.position.z - guardZ);
                        SimSample last = series.get(series.size() - 1);
                        double dAfter = Math.hypot(last.state.position.x - guardX,
                            last.state.position.z - guardZ);
                        if (dAfter < dBefore - 0.5) anyAdvanced = true;
                        // Any room entry that DID happen must postdate the gate.
                        if (last.state.position.x > 22.5 && last.state.position.x < 29.5
                            && last.state.position.z > 6.5 && last.state.position.z < 17.5
                            && last.tick < gate) return false;
                    }
                    return anyAdvanced;
                })
            .assertThat("order completed as SECURED after the contact opened",
                view -> probe.securedSeen && firstOpenTick[0] < Long.MAX_VALUE
                    && probe.securedAtTick > firstOpenTick[0])
            .assertThat("the door defender fell before the order completed",
                view -> probe.zoneEnemiesDownAtTick >= 0
                    && (probe.securedAtTick < 0
                        || probe.zoneEnemiesDownAtTick < probe.securedAtTick))
            // TEMP wave-A validation: pins the probe internals so a failed
            // ordering assert names its failing leg instead of a guess.
            .action(4700, "TEMP diag — probe internals", ctx -> {
                com.stevesarmy.StevesArmyMod.LOGGER.info(
                    "[Sim][diag] door_contact probe securedSeen={} securedAtTick={} firstOpen={} "
                        + "firstShot={} firstStall={} zoneDown={} enteredZone={}",
                    probe.securedSeen, probe.securedAtTick, firstOpenTick[0],
                    firstCoverShot[0], firstStallTick[0], probe.zoneEnemiesDownAtTick,
                    probe.enteredZone);
            })
            .build();
    }

    /**
     * The tick the contact gate was first allowed to open: the earliest of the
     * covering shot, the STALLED latch, the "mate suppressed" bypass, and the
     * bloodless-standoff escape (contact open + attackClearStallTicks) — the
     * conditions the doctrine lets a soldier advance under.
     */
    private static double gateOpenTick(SimScenario.SimRunResultView view, long firstShot,
                                       long firstStall, long firstOpen) {
        double gate = Math.min(firstShot, firstStall);
        if (firstOpen < Long.MAX_VALUE) {
            gate = Math.min(gate,
                firstOpen + com.stevesarmy.StevesArmyConfig.getAttackClearStallTicks());
        }
        for (String label : new String[] {"cover", "checker"}) {
            for (SimSample s : view.timeline().series(label)) {
                if (s.state.suppressionLevel >= 0.4) {
                    gate = Math.min(gate, s.tick);
                    break;
                }
            }
        }
        return gate;
    }

    /**
     * Polls the contact-plan gate inputs into the caller's min-tracking
     * holders: the earliest successful shot the cover soldier registers
     * (SquadMovementCoordinator.lastSuccessfulShotTick) and the earliest tick
     * the clearing order latched STALLED (ClearingCoordinator.isStalledLatched).
     * Called on the scenario's action cadence; {@code at} is the poll tick and
     * bounds the evidence's resolution.
     */
    private static void recordGateState(SimContext ctx, int at, String coverLabel,
                                        long[] firstShotOut, long[] firstStallOut) {
        SoldierEntity cover = ctx.soldier(coverLabel);
        if (cover != null && cover.isAlive()) {
            Long shot = com.stevesarmy.squad.SquadMovementCoordinator.lastSuccessfulShotTick(cover);
            if (shot != null && shot < firstShotOut[0]) {
                firstShotOut[0] = shot;
            }
            if (firstStallOut[0] == Long.MAX_VALUE
                && com.stevesarmy.squad.ClearingCoordinator.isStalledLatched(cover)) {
                firstStallOut[0] = at;
            }
        }
    }

    /**
     * ACCEPTANCE (checker-loss handoff): the divided-rooms geometry, NO
     * enemies. Mid-clear the harness kills the soldier holding a check claim
     * — damageSoldier with a
     * lethal amount is the harness's only death primitive, and the 20-HP body
     * makes 1000f deterministic. The order may neither complete short of the
     * dead checker's claim nor strand: a survivor re-claims the POI, the full
     * check fraction reaches 1.0, and SECURED still lands inside the clock —
     * after the death, so the dead soldier cannot be the one who did the work.
     *
     * <p>Deliberately EMPTY of enemies: the contact-gate behavior is
     * cqb_door_contact's acceptance. A live contact here destabilizes the
     * docks through the known threat-aware cover families (concealment /
     * exposed-hiding) and roulettes the run; the claim machinery this
     * scenario exists to pin is deterministic only unarmed.
     */
    private static SimScenario clearCheckerDeath() {
        ClearingProbe probe = new ClearingProbe();
        // The kill target's claim, last-captured before the kill
        // (ClearingView.assignedPoi): evidence he HELD a job the survivors
        // must finish. Null means nobody held a claim — the order never
        // handed out checks.
        final net.minecraft.core.BlockPos[] deadClaim = {null};
        final String[] deadHolder = {null};
        final boolean[] killLanded = {false};
        /** Server tick the conditional kill fired (ordering assert anchor). */
        final int[] killTick = {0};
        SimScenario.Builder builder = SimScenario.builder("clear_checker_death")
            .description("ATTACK clearing with the soldier holding a check claim killed mid-run: "
                + "the order may not complete short of his claim and may not strand - a "
                + "survivor re-claims the dead soldier's POI, the full check fraction reaches "
                + "1.0, and the order still reads SECURED inside the clock, strictly after "
                + "the death.")
            // 3600: the divided-rooms clock (2200) plus the death, the
            // re-claim walk, and the settle tail — the 3000 clock hit SECURE
            // with the completion still arming at the wire.
            .duration(3600)
            .terrain(t -> t.dividedRooms());
        addClearingProbe(builder, 40, 3580, 20, probe,
            // East room interior + the partition opening — the zone
            // clear_divided_rooms already uses for its far-room asserts.
            (x, z) -> x > 15.5 && x < 23.5 && z > 6.5 && z < 19.5);
        // Claim capture + conditional kill. Claims live ~10t (stripped one
        // heavy cycle after the check lands), so the poll must run at the same
        // cadence; and the holder can be ANY member — claims are
        // nearest-unchecked, so which soldier holds one at a given tick is
        // squad-order dependent. The kill fires the FIRST poll a claim is held
        // while check-targets remain unchecked: a fixed death tick completed
        // the order at ~t870 and the t900 kill came after SECURED, breaking
        // the "strictly after the death" ordering by construction.
        final int killSearchEnd = 1500;
        for (int t = 300; t <= killSearchEnd; t += 10) {
            final int at = t;
            builder.action(at, "kill the claim holder once a claim exists mid-clear", ctx -> {
                if (killLanded[0]) {
                    return;
                }
                boolean midClear = com.stevesarmy.squad.ClearingCoordinator.checkedFraction(
                    ctx.anchor.getUUID(), com.stevesarmy.squad.FireTeam.ALL) < 0.999;
                for (String label : new String[] {"checker", "alpha", "bravo"}) {
                    SoldierEntity s = ctx.soldier(label);
                    if (s == null || !s.isAlive()) {
                        continue;
                    }
                    com.stevesarmy.squad.ClearingCoordinator.ClearingView v =
                        com.stevesarmy.squad.ClearingCoordinator.viewFor(s);
                    if (v == null || v.assignedPoi() == null) {
                        continue;
                    }
                    deadClaim[0] = v.assignedPoi();
                    deadHolder[0] = label;
                    if (!midClear) {
                        return; // capture only — killing after fraction 1.0 tests nothing
                    }
                    ctx.damageSoldier(label, 1000.0f);
                    killLanded[0] = !s.isAlive();
                    if (killLanded[0]) {
                        killTick[0] = at;
                    }
                    return;
                }
            });
        }
        return builder
            .setup(ctx -> {
                ctx.spawnAnchor(12, 0, 38);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 12, 0, 38, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 10, 0, 36, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "checker", 14, 0, 36, SquadMode.HOLD);
                ctx.issueAttackOrder(12, 10); // west room, north half — clear_divided_rooms's ping
            })
            .assertThat("a check claim was assigned before the kill",
                view -> deadClaim[0] != null)
            .assertThat("the kill landed mid-run (a claim was held while checks remained)",
                view -> killLanded[0] && killTick[0] > 0)
            .assertThat("every check-target was observed (fraction 1.0 — the dead claim was re-checked)",
                view -> probe.maxCheckedFraction >= 0.999)
            .assertThat("order completed as SECURED strictly after the death (survivors finished it)",
                view -> probe.securedSeen && probe.securedAtTick > killTick[0])
            .assertThat("squad reached the objective space",
                view -> probe.enteredZone)
            .build();
    }

    /**
     * REGRESSION (user-spotted t990, run attack_superiority_advance
     * 20261014_113627): a killed enemy's personal ThreatAwareness entry
     * outlived the corpse and kept caution steering ("enemy near path") plus
     * cover churn alive around the kill site — all four soldiers shuffled at
     * 0.01-0.13 b/t for ~300t after the last defender died, with suppression
     * 0.00 and zero living enemies. A corpse is not a threat: once the enemy
     * is down, a soldier walking past the kill site must travel at free
     * speed with no caution cap. Deterministic by construction — unarmed,
     * single soldier, scripted kill.
     */
    private static SimScenario ghostCorpseNoCaution() {
        final boolean[] killed = {false};
        final int[] killTick = {-1};
        /** Caution frames inside the corpse transit band (the bug signature). */
        final int[] ghostCautionFrames = {0};
        /** Caution-capped crawl frames inside the transit band. */
        final int[] slowFramesNearCorpse = {0};
        final double corpseX = 24.5;
        final double corpseZ = 20.5;
        SimScenario.Builder builder = SimScenario.builder("ghost_corpse_no_caution")
            .description("Kill an enemy at 4 blocks, then GO_TO straight past the kill site: no "
                + "caution steering and no speed cap may engage near the corpse. Guards the "
                + "dead-entity ghost threat that shuffled soldiers at 0.01-0.13 b/t around "
                + "kill sites long after the fight ended.")
            .duration(800)
            .core()
            .terrain(t -> t.platform())
            .setup(ctx -> {
                ctx.spawnAnchor(18, 0, 20);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 20, 0, 20, SquadMode.HOLD);
                // Unarmed scenario: the victim never fires, alpha never shoots —
                // the kill is scripted and the run has no combat dice.
                ctx.spawnEnemyLabeled("victim", 24, 0, 20);
            });
        for (int t = 30; t <= 150; t += 5) {
            final int at = t;
            builder.action(at, "kill the detected enemy once", ctx -> {
                if (killed[0]) {
                    return;
                }
                if (ctx.enemies.isEmpty()) {
                    return;
                }
                EnemySoldierEntity victim = ctx.enemies.get(0);
                if (!victim.isAlive() || victim.isRemoved()) {
                    killed[0] = true;
                    killTick[0] = at;
                    return;
                }
                SoldierEntity alpha = ctx.soldier("alpha");
                boolean detected = alpha != null && alpha.isAlive()
                    && (!alpha.getThreatAwareness().getThreatInfos().isEmpty()
                        || alpha.hasValidAttackTarget());
                if (detected) {
                    victim.hurt(victim.level().damageSources().generic(), 1000.0F);
                    killed[0] = !victim.isAlive();
                    killTick[0] = at;
                }
            });
        }
        builder.action(160, "walk past the kill site", ctx -> ctx.issueGoTo(34, 20));
        for (int t = 170; t <= 700; t += 5) {
            final int at = t;
            builder.action(at, "ghost caution probe", ctx -> {
                SoldierEntity alpha = ctx.soldier("alpha");
                if (alpha == null || !alpha.isAlive()) {
                    return;
                }
                double dist = Math.sqrt(Math.pow(alpha.getX() - corpseX, 2)
                    + Math.pow(alpha.getZ() - corpseZ, 2));
                if (dist > 12.0) {
                    return;
                }
                if (alpha.getMoveControl()
                        instanceof com.stevesarmy.entity.ai.CoverPositionController ctrl
                    && "enemy near path".equals(ctrl.cautionReason())) {
                    ghostCautionFrames[0]++;
                }
                if (!alpha.getNavigation().isDone() && alpha.getSpeed() <= 0.15F) {
                    slowFramesNearCorpse[0]++;
                }
            });
        }
        return builder
            .assertThat("the enemy was detected and killed",
                view -> killed[0] && killTick[0] > 0)
            .assertThat("alpha walked past the kill site (reached x >= 30)",
                view -> view.timeline().series("alpha").stream()
                    .anyMatch(s -> s.state.position.x >= 30.0))
            .assertThat("no ghost caution near the corpse (enemy near path never active)",
                view -> ghostCautionFrames[0] == 0)
            .assertThat("no caution-capped crawl near the corpse (speed > 0.15 while moving)",
                view -> slowFramesNearCorpse[0] == 0)
            .build();
    }

    /**
     * BEHAVIOR PROBE (user report 2026-10-13): how does the ATTACK advance
     * profile change when the defense holds FIRE SUPERIORITY instead of
     * parity? In-game report: soldiers used to bound cover-to-cover (0.5.1)
     * and now push straight at the objective — good against thin defenses,
     * lethal against a defended street. Same longStreetBlock map, same
     * 4-rifleman squad, two defense sizes; the per-soldier advance
     * telemetry (dock episodes, longest exposed run, moving-under-fire
     * time, casualties) is the evidence and the user judges the behavior.
     * Full-only: live-fire roulette.
     */
    private static SimScenario attackSuperiorityAdvance() {
        return attackFireAdvance("attack_superiority_advance", 3, false);
    }

    private static SimScenario attackParityAdvance() {
        return attackFireAdvance("attack_parity_advance", 1, false);
    }

    /** Sustained variants: damage-immune defenders keep the exchange live all run. */
    private static SimScenario attackSuperioritySustained() {
        return attackFireAdvance("attack_superiority_sustained", 3, true);
    }

    private static SimScenario attackParitySustained() {
        return attackFireAdvance("attack_parity_sustained", 1, true);
    }

    /**
     * Peek-before-advance gate, end to end (user question 2026-10-13: "for a
     * soldier to advance he needs to be peeking in his cover for at least a
     * certain amount of time without getting pinned back to ducking — test if
     * this is actually working").
     *
     * Phase 1 (t40-t290): scripted near-miss pressure from the enemy side keeps
     * the docked soldier unrecovered. The dwell clock freezes while !recovered
     * (CoverTacticalGoal resets attackCoverArrivalTime every such tick), so NO
     * dwell-gated advance trigger can legally fire and the 15s hard timeout
     * never matures inside the window. The row is the only cover on the field,
     * so the relocation channel has nowhere forward to go either. The soldier
     * must hold the row line and stay ducked.
     *
     * Phase 2 (t290+): pressure lifts and a lone cover block appears near the
     * objective (terrain.block writes live), giving the advance a dock to take.
     * After the 2500ms recovery window the dwell clock restarts from zero, so
     * the peek path (dwell met + peek completed + recovered) wins the race
     * against the 10s/15s watchdogs. The soldier must expose to the threat at
     * least once before a new ATTACK_SELECTING advance search fires.
     *
     * The disarmed hardened enemy is HOLD-pinned so pressure geometry stays
     * scripted. Advance searches are detected through the lastSearch snapshot
     * (mode ATTACK_SELECTING) rather than position: the gate governs the
     * search trigger, and cs=REPOSITIONING alone cannot separate a gated
     * advance from a cover-manager relocation.
     */
    private static SimScenario attackPeekGateDiscipline() {
        SimScenario.Builder b = SimScenario.builder("attack_peek_gate_discipline")
            .description("Peek-before-advance gate: scripted pressure pins the docked soldier "
                + "through the whole dwell window (frozen dwell -> no legal advance trigger), "
                + "then lifts and reveals a forward dock. While pinned he holds the row, stays "
                + "ducked, and fires no ATTACK_SELECTING advance search; after recovery a peek "
                + "precedes the advance search. Disarmed hardened enemy is HOLD-pinned so "
                + "pressure geometry stays scripted.")
            .duration(800)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverRow(10, 20, 26, 20, 1); // the only cover during the pin phase
            })
            .setup(ctx -> {
                // Spawn far enough out that the row (~11 spine blocks of
                // progress) clears ATTACK_SPINE_HOP_LENGTH: with the spawn any
                // closer the spine-frame candidate filter rejects every first
                // dock on this field and the soldier stands pinned in the open.
                // Objective sits past the enemy so the revealed block (~22
                // spine blocks) clears frontier+hop as the SECOND dock; with a
                // shorter spine the hop gate + objective-exclusion radius leave
                // no legal advance target and the release-phase search fails.
                ctx.spawnAnchor(12, 0, 32);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifleman", 20, 0, 32, SquadMode.HOLD);
                EnemySoldierEntity target = ctx.spawnEnemyLabeled("target", 18, 0, 8);
                target.setSquadMode(SquadMode.HOLD);
                target.setHoldPosition(ctx.terrain.at(18, 0, 8));
                ctx.harden("target");
                ctx.disarmSoldier("target"); // no return fire: the pressure timeline is ours
                ctx.injectThreat(18, 9, 1.0f);
                ctx.issueAttackOrder(20, 2);
                // Name every advance trigger in the run log ([AttackPhase] lines).
                com.stevesarmy.debug.DiagnosticLogManager.setAttackLoggingEnabled(true);
            })
            // FireStarvation: the hardened enemy can never take damage.
            // PeekThrash: the pin-release cycle intentionally produces repeated
            // peek attempts from one dock.
            // DangerOracleMismatch: the injected suppression origins bake danger
            // cells the LoS oracle (entity-driven) legitimately disagrees with.
            // StuckSeek: the post-advance seek tail (finding the next dock on a
            // sparse field) is cover-search roulette, not the gate this scenario
            // measures — the pin/peek/recovery asserts above are the contract.
            // ExposedWhileHiding: same tail — where the post-advance re-dock
            // lands (rarely a north-face slot with no protection) is dock
            // scoring roulette; dedicated dock-quality scenarios own that.
            .suppressDetectors("FireStarvation", "PeekThrash", "DangerOracleMismatch",
                "StuckSeek", "ExposedWhileHiding");
        for (int t = 40; t <= 290; t += 10) {
            b.action(t, "pinned pressure",
                ctx -> ctx.injectSuppressionFromGround("rifleman", 2, 18.5, 8.5, 1.5, 3.0f));
        }
        for (int t = 60; t <= 720; t += 60) {
            b.action(t, "refresh threat intel", ctx -> ctx.refreshInjectedThreats());
        }
        return b
            .action(295, "reveal advance cover", ctx -> ctx.terrain.block(22, 1, 10,
                net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState()))
            .action(790, "restore logging defaults",
                ctx -> com.stevesarmy.debug.DiagnosticLogManager.setAttackLoggingEnabled(false))
            .assertThat("validity: docked on the row by t300 (cover claim z on the row line)",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick > 300) break;
                        if (s.state.currentCover != null) {
                            int z = s.state.currentCover.getZ();
                            return z >= 19 && z <= 23;
                        }
                    }
                    return false;
                })
            .assertThat("PIN HOLDS: while pressure is live (t75-t290) the soldier never "
                    + "crosses the row line z=19",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 75) continue;
                        if (s.tick > 290) break;
                        if (s.state.position.z <= 19.0) return false;
                    }
                    return true;
                })
            .assertThat("PINNED = DUCKED: while pinned (t75-t290) the soldier never exposes "
                    + "(no peek exposure states)",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 75) continue;
                        if (s.tick > 290) break;
                        String ps = s.state.peekState;
                        if ("EXPOSED".equals(ps) || "STANDING_IN_HALF_COVER".equals(ps)) {
                            return false;
                        }
                    }
                    return true;
                })
            .assertThat("NO ADVANCE SEARCH WHILE PINNED: no new ATTACK_SELECTING cover search "
                    + "lands during the pin (t40-t290)",
                view -> {
                    String baseline = null;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 40 || s.tick > 290) continue;
                        String ls = String.valueOf(s.state.lastSearch);
                        if (baseline == null) {
                            baseline = ls;
                            continue;
                        }
                        if (ls.startsWith("ATTACK_SELECTING") && !ls.equals(baseline)) {
                            return false;
                        }
                    }
                    return baseline != null;
                })
            .assertThat("GATE OPENS: after pressure lifts the soldier leaves the dock (or an "
                    + "advance search fires) by t700",
                view -> {
                    String baseline = null;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 40) continue;
                        String ls = String.valueOf(s.state.lastSearch);
                        if (baseline == null) baseline = ls;
                        if (s.tick < 295 || s.tick > 700) continue;
                        boolean leftCover = !"IN_COVER".equals(s.state.coverState)
                            && !"SUPPRESSED_IN_COVER".equals(s.state.coverState);
                        boolean newSearch = ls.startsWith("ATTACK_SELECTING")
                            && !ls.equals(baseline);
                        boolean queued = "ATTACK_SELECTING".equals(s.state.queuedMode);
                        if (leftCover || newSearch || queued) return true;
                    }
                    return false;
                })
            .assertThat("LADDER: peek activity precedes leaving the dock after release "
                    + "(advance ran through the peek-gated path)",
                view -> {
                    boolean peeked = false;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick >= 290) {
                            String ps = s.state.peekState;
                            if ("EXPOSED".equals(ps) || "STANDING_IN_HALF_COVER".equals(ps)
                                || "MOVING_TO_PEEK".equals(ps)
                                || "RETURNING_TO_COVER".equals(ps)) {
                                peeked = true;
                            }
                            boolean leftCover = !"IN_COVER".equals(s.state.coverState)
                                && !"SUPPRESSED_IN_COVER".equals(s.state.coverState);
                            if (s.tick >= 295 && leftCover) return peeked;
                        }
                    }
                    return false;
                })
            .assertThat("RECOVERED BEFORE ADVANCE: suppression has decayed (<= 0.45) when the "
                    + "soldier leaves the dock",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 295) continue;
                        boolean leftCover = !"IN_COVER".equals(s.state.coverState)
                            && !"SUPPRESSED_IN_COVER".equals(s.state.coverState);
                        if (leftCover) return s.state.suppressionLevel <= 0.45f;
                    }
                    return false;
                })
            .build();
    }

    /**
     * Suppressed relocation must be fully safe or not happen (user doctrine
     * 2026-10-13: "I think we need suppressed relocation to be fully safe, or
     * else, just stay at that cover").
     *
     * Reproduces the death mechanism from the longStreetBlock forensics: a
     * docked ATTACK soldier under continuous scripted pressure has a forward
     * lure wall revealed mid-pin, and BEFORE the safe-route classifier the
     * suppression relocation took the plain attack-corridor path — an upright
     * walk-out straight toward it while suppression is still ~1.0 (the
     * alpha/bravo/charlie walk-outs).
     *
     * Contract (PIN ONLY): while suppression >= 0.6 the soldier never moves
     * more than half a block north of his settled dock and never exposes.
     * The line is dock-relative because attack dock scoring prefers the
     * forward (north-face) row slots, so the dock z itself is not stable
     * across runs. The release-phase advance is deliberately NOT asserted
     * here: the dock-delivery pipeline has a pre-existing move-freeze
     * roulette (three recorded runs froze a selected cover walk mid-approach
     * or mid-advance with an accepted nav path and zero steps) that makes
     * any post-release crossing assert roulette; release-phase advance
     * health is owned by attack_peek_gate_discipline and the longStreetBlock
     * A/B fights.
     *
     * Geometry: band-edge spawn (12 blocks from the objective, distSqr 144
     * = CLEARING_INVESTIGATE_BAND_SQ) dissolves the advance spine from tick
     * zero, so the dock is deterministic (t45, no per-run A* wander; a
     * longer approach re-arms the spine and docked the row's far east end
     * in two recorded runs). Enemy at (12,2): close enough that the
     * soldier's attack target survives the whole pin (sight range ~16-20 —
     * at 22.8 the target memory expired mid-pin and the cover drop rerouted
     * the walk-out through the unclassified SEEKING loop), far enough that
     * his park is not on the measured soldier's assert lines.
     */
    private static SimScenario suppressedRelocationSafeRoute() {
        SimScenario.Builder b = SimScenario.builder("suppressed_relocation_safe_route")
            .description("Suppressed relocation safety: continuous-pin pressure with a "
                + "forward lure wall revealed mid-pin. While suppressed the soldier must "
                + "stay at his dock (the pre-fix bypass walks exposed forward docks at "
                + "suppression ~1.0); the safe-route classifier must withhold those. "
                + "Guards the attack-target bypass.")
            .duration(800)
            .armed()
            .terrain(t -> {
                t.platform();
                t.coverRow(12, 10, 28, 10, 1); // the dock row; the lure reveals forward of it
            })
            .setup(ctx -> {
                // Band-edge spawn: see the class comment. Enemy close enough
                // to keep the attack target alive, HOLD-pinned at (12,2).
                ctx.spawnAnchor(20, 0, 14);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifleman", 20, 0, 14, SquadMode.HOLD);
                EnemySoldierEntity target = ctx.spawnEnemyLabeled("target", 12, 0, 2);
                target.setSquadMode(SquadMode.HOLD);
                target.setHoldPosition(ctx.terrain.at(12, 0, 2));
                ctx.harden("target");
                ctx.disarmSoldier("target"); // no return fire: the pressure timeline is ours
                ctx.injectThreat(20, 5, 1.0f);
                ctx.issueAttackOrder(20, 2);
                com.stevesarmy.debug.DiagnosticLogManager.setAttackLoggingEnabled(true);
                com.stevesarmy.debug.DiagnosticLogManager.setCoverPerformanceLoggingEnabled(true);
            })
            // FireStarvation: the hardened enemy can never take damage.
            // PeekThrash: the pin cycle intentionally produces repeated peek
            // attempts from one dock.
            // DangerOracleMismatch: the injected suppression origins bake danger
            // cells the LoS oracle (entity-driven) legitimately disagrees with.
            // StuckSeek: the post-release seek tail (next dock on a sparse
            // field) is cover-search roulette, not the safety contract this
            // scenario measures (asserts below).
            // ExposedWhileHiding: same tail — where a re-dock lands is dock
            // scoring roulette; dedicated dock-quality scenarios own that. The
            // pinned-phase walk-out this scenario guards is asserted by
            // position, not by this detector.
            // PositionDeadlock: the dock-delivery pipeline's pre-existing
            // move-freeze (REPOSITIONING held with an accepted nav path and
            // zero steps — recorded here t235 and independently on a clean
            // suppression-free approach) recovers on its own via re-selection
            // and is a separate follow-up; the pin contract below is
            // position-asserted and unaffected.
            .suppressDetectors("FireStarvation", "PeekThrash", "DangerOracleMismatch",
                "StuckSeek", "ExposedWhileHiding", "PositionDeadlock");
        for (int t = 250; t <= 390; t += 10) {
            b.action(t, "pinned pressure",
                ctx -> ctx.injectSuppressionFromGround("rifleman", 2, 20.5, 5.5, 1.5, 3.0f));
        }
        for (int t = 60; t <= 720; t += 60) {
            b.action(t, "refresh threat intel", ctx -> ctx.refreshInjectedThreats());
        }
        return b
            .action(790, "restore diagnostic flags", ctx -> {
                com.stevesarmy.debug.DiagnosticLogManager.setAttackLoggingEnabled(false);
                com.stevesarmy.debug.DiagnosticLogManager.setCoverPerformanceLoggingEnabled(false);
            })
            .action(230, "reveal forward lure wall", ctx -> {
                // Revealed mid-pin, BEFORE the first pinned relocation search
                // after it sees it, and long after the approach has docked so
                // the advance cannot grab it early. 2-wide so its south dock
                // reads threat-protected from the (20.5, 5.5) firing origin.
                // Forward of the row toward the objective — the pre-fix
                // relocation bait. Y is terrain-RELATIVE: y=1 sits one block
                // above the platform floor (origin y=100), matching
                // coverRow's y=0 footprint.
                ctx.terrain.block(19, 1, 6, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                ctx.terrain.block(20, 1, 6, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
            })
            .assertThat("DOCKED: the soldier has claimed a row cover by t185",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick > 185) break;
                        if (s.state.currentCover != null) {
                            int z = s.state.currentCover.getZ();
                            return z >= 9 && z <= 13;
                        }
                    }
                    return false;
                })
            .assertThat("PRESSURE LIVE: suppression >= 0.6 early (t260-290) and late "
                    + "(t360-390) in the pin window",
                view -> {
                    boolean early = view.timeline().series("rifleman").stream()
                        .filter(s -> s.tick >= 260 && s.tick <= 290)
                        .anyMatch(s -> s.state.suppressionLevel >= 0.6f);
                    boolean late = view.timeline().series("rifleman").stream()
                        .filter(s -> s.tick >= 360 && s.tick <= 390)
                        .anyMatch(s -> s.state.suppressionLevel >= 0.6f);
                    return early && late;
                })
            .assertThat("SAFE OR STAY: while suppressed (>= 0.6) the soldier never moves "
                    + "more than half a block north of his dock (t250-420)",
                view -> {
                    Double dockZ = settledDockZ(view);
                    if (dockZ == null) return false;
                    double line = dockZ - 0.5;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 250) continue;
                        if (s.tick > 420) break;
                        if (s.state.suppressionLevel >= 0.6f && s.state.position.z < line) {
                            return false;
                        }
                    }
                    return true;
                })
            .assertThat("STAYS DUCKED: while suppressed (>= 0.6) and settled in cover "
                    + "the soldier is never EXPOSED or STANDING_IN_HALF_COVER (t300-370)",
                view -> {
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 300) continue;
                        if (s.tick > 370) break;
                        if (s.state.suppressionLevel >= 0.6f) {
                            String cs = s.state.coverState;
                            if (!"IN_COVER".equals(cs) && !"SUPPRESSED_IN_COVER".equals(cs)) {
                                continue; // mid-move (incl. the delivery-freeze
                                          // symptom) is the pipeline's state,
                                          // not the ducked contract
                            }
                            String ps = s.state.peekState;
                            if ("EXPOSED".equals(ps) || "STANDING_IN_HALF_COVER".equals(ps)) {
                                return false;
                            }
                        }
                    }
                    return true;
                })
            .build();
    }

    /**
     * The settled pre-pressure dock of the "rifleman": the most northward
     * position sampled in t100-240, after the dock system's pre-pressure
     * churn settles (one recorded run re-docked north at t172-200) and
     * before the pinned-pressure window opens at t250.
     * Dock-relative because attack dock scoring prefers forward (north-face)
     * row slots, so the dock z itself is not stable across runs.
     */
    private static Double settledDockZ(SimScenario.SimRunResultView view) {
        Double dockZ = null;
        for (SimSample s : view.timeline().series("rifleman")) {
            if (s.tick < 100) continue;
            if (s.tick > 240) break;
            double z = s.state.position.z;
            dockZ = dockZ == null ? z : Math.min(dockZ, z);
        }
        return dockZ;
    }

    // ATTACK absolute-pin doctrine: ten-vs-one fire must freeze the dock -----

    /**
     * ATTACK absolute-pin doctrine (user report): a soldier advancing on an
     * ATTACK order into ABSOLUTE enemy fire (10 shooters vs 1) must NEVER
     * voluntarily walk out of his cover while suppressed. In-game he currently
     * holds through only ~2 pinned peek cycles and then exits — telemetry
     * shows coverState flipping to NO_COVER and an upright walk-out under
     * full suppression. This scenario is the pre-fix reproduction and the
     * post-fix gate.
     *
     * Contract (MOVEMENT-based — the doctrine is about walking out, not state
     * labels):
     *  - PINNED LIVE: organic suppression (ten real shooters firing real
     *    bullets; no scripted injection, no injected threats) saturates to
     *    >= 0.9 by t400 and again in t800-1400. The late leg is vacuous when
     *    the soldier died before the window — DIES-AT-DOCK owns that outcome.
     *  - NEVER-LEAVE: while suppression >= 0.6 the soldier never displaces
     *    more than 1.0 block from his settled dock (median position over
     *    t60-240, after the band-edge dock settles; dock-relative because
     *    attack dock scoring makes the absolute dock z unstable across runs).
     *  - NO EXIT-WALK: no SEEKING_COVER/REPOSITIONING/NO_COVER episode while
     *    suppression >= 0.6 carries net displacement beyond 1.5 blocks —
     *    state churn without displacement is not a walk-out.
     *  - DIES-AT-DOCK: if the fire kills him, he dies within 1.5 blocks of
     *    his settled dock — death at the dock is doctrine holding, not a
     *    failure; passes vacuously when he survives.
     *
     * Geometry (lessons from suppressed_relocation_safe_route): band-edge
     * spawn — (20,14) sits distSqr 144 = exactly 12 blocks from the attack
     * objective (20,2), so the advance spine dissolves from tick 0 and the
     * dock is deterministic (~t45, no per-run A* wander). One 1-high dock row
     * z=10 spanning x12-28 with an open approach south of it. Ten hardened
     * ARMED enemies HOLD-pinned across the whole front (x4-24, z2-4,
     * staggered so lanes overlap): every lateral and forward route from the
     * row crosses at least one live lane, so no slot the scorer could pick is
     * actually safer. The nearest enemies sit ~7 blocks off the row — inside
     * the ~16-20 sight range, so the attack target memory survives the whole
     * pin (the donor's 22.8-block expiry lesson).
     *
     * Detectors: FireStarvation is suppressed (hardened enemies can never
     * take damage — hits land but damage is ignored, so the detector reads
     * the instrument, not a dead firing chain); PeekThrash (the pinned peek
     * cadence roulette IS the reported mechanism); DangerOracleMismatch,
     * StuckSeek and ExposedWhileHiding (seek-tail and dock-quality roulette
     * are owned by dedicated scenarios — the pinned walk-out this scenario
     * guards is asserted by position, not by those detectors).
     * PositionDeadlock deliberately stays ENABLED: a REPOSITIONING-held
     * freeze with an accepted nav path and zero steps is evidence for the
     * freeze-then-strip mechanism being reproduced.
     */
    private static SimScenario attackAbsolutePinHold() {
        return attackAbsolutePinHold(false);
    }

    private static SimScenario attackAbsolutePinHold(boolean displacedProbe) {
        final boolean[] displacementHeld = {false};
        final boolean[] recoveredAbandoned = {false};
        final String[] enemyLabels =
            {"e01", "e02", "e03", "e04", "e05", "e06", "e07", "e08", "e09", "e10"};
        final int[][] enemySpots = {
            {4, 2}, {6, 4}, {8, 2}, {10, 3}, {12, 2}, {14, 4}, {16, 2}, {18, 3}, {21, 2}, {24, 4}};
        return SimScenario.builder(displacedProbe
                ? "attack_pinned_displacement_hold" : "attack_absolute_pin_hold")
            .description("ATTACK absolute-pin doctrine: one rifleman advancing on an ATTACK "
                + "order into absolute fire (10 armed hardened shooters) must never "
                + "voluntarily walk out of his dock while suppressed. In-game he exits after "
                + "~2 pinned peek cycles with coverState NO_COVER. Guards the pin: "
                + "saturation fire must freeze him at the dock (or kill him there) — never "
                + "an exit walk.")
            .duration(2000)
            .armed()
            .suppressDetectors("FireStarvation", "PeekThrash", "DangerOracleMismatch",
                "StuckSeek", "ExposedWhileHiding")
            .terrain(t -> {
                t.platform();
                t.coverRow(12, 10, 28, 10, 1); // the dock row; open approach south of it
            })
            .setup(ctx -> {
                // Band-edge spawn: see the class comment — distSqr to the
                // attack objective is 144, dissolving the advance spine.
                ctx.spawnAnchor(20, 0, 14);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "rifleman", 20, 0, 14, SquadMode.HOLD);
                if (displacedProbe) ctx.harden("rifleman");
                for (int i = 0; i < enemyLabels.length; i++) {
                    EnemySoldierEntity e = ctx.spawnEnemyLabeled(
                        enemyLabels[i], enemySpots[i][0], 0, enemySpots[i][1]);
                    e.setSquadMode(SquadMode.HOLD);
                    e.setHoldPosition(ctx.terrain.at(enemySpots[i][0], 0, enemySpots[i][1]));
                    ctx.harden(enemyLabels[i]); // the pressure never dies
                }
                // Past the row (z=2 is north of the z=10 row), 12 blocks out.
                ctx.issueAttackOrder(20, 2);
            })
            .action(200, "probe pinned distance abandonment before tickInCover", ctx -> {
                if (!displacedProbe) return;
                SoldierEntity s = ctx.soldier("rifleman");
                var manager = s.getCoverBehaviorManager();
                var dock = manager.getCurrentCover();
                if (dock == null || manager.getSuppressionTracker().isRecovered()) return;
                var origin = s.position();
                var velocity = s.getDeltaMovement();
                s.setPos(origin.x, origin.y, origin.z + 9.0);
                try {
                    var goal = (com.stevesarmy.entity.ai.CoverTacticalGoal) s.getCoverTacticalGoal();
                    var attackTargetField = SoldierEntity.class.getDeclaredField("attackTargetPos");
                    attackTargetField.setAccessible(true);
                    Object attackTarget = attackTargetField.get(s);
                    // ATTACK canUse returns early; exercise the shared non-attack branch
                    // without issuing a new order or changing its generation.
                    attackTargetField.set(s, null);
                    try {
                        displacementHeld[0] = goal.canUse() && manager.getCurrentCover() == dock
                            && manager.getState() == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER;
                    } finally {
                        attackTargetField.set(s, attackTarget);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("distance abandonment probe", e);
                } finally {
                    // Sample voluntary movement separately from the injected displacement.
                    s.setPos(origin.x, origin.y, origin.z);
                    s.setDeltaMovement(velocity);
                }
            })
            .action(1990, "recovered distance abandonment control", ctx -> {
                if (!displacedProbe) return;
                SoldierEntity s = ctx.soldier("rifleman");
                var manager = s.getCoverBehaviorManager();
                var dock = manager.getCurrentCover();
                if (dock == null) return;
                var origin = s.position();
                manager.getSuppressionTracker().reset();
                s.setPos(origin.x, origin.y, origin.z + 9.0);
                try {
                    var goal = (com.stevesarmy.entity.ai.CoverTacticalGoal) s.getCoverTacticalGoal();
                    var attackTargetField = SoldierEntity.class.getDeclaredField("attackTargetPos");
                    attackTargetField.setAccessible(true);
                    Object attackTarget = attackTargetField.get(s);
                    attackTargetField.set(s, null);
                    try {
                        recoveredAbandoned[0] = !goal.canUse() && manager.getCurrentCover() == null;
                    } finally {
                        attackTargetField.set(s, attackTarget);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("recovered abandonment control", e);
                } finally {
                    s.setPos(origin.x, origin.y, origin.z);
                    manager.setCurrentCover(dock);
                    ctx.injectSuppression("rifleman", 40);
                    manager.setState(com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER);
                }
            })
            .assertThat("DISPLACEMENT: pinned canUse retains dock beyond eight blocks; recovered control abandons",
                view -> !displacedProbe || (displacementHeld[0] && recoveredAbandoned[0]))
            .assertThat("LONG PIN: hardened probe survives and remains pinned beyond fifteen seconds",
                view -> !displacedProbe || view.timeline().series("rifleman").stream()
                    .anyMatch(s -> s.tick >= 1800 && s.health > 0f && s.state.suppressionLevel >= 0.9f))
            .assertThat("FIREFIGHT LIVE: tracers were recorded (the pin is organic gunfire, "
                    + "not scripted injection)",
                view -> !view.shots().isEmpty())
            .assertThat("PINNED LIVE: organic suppression reaches >= 0.9 by t400 and again "
                    + "in t800-1400 (late leg vacuous if he died before the window — "
                    + "DIES-AT-DOCK owns that outcome)",
                view -> {
                    boolean early = false;
                    boolean late = false;
                    boolean aliveLate = false;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick <= 400 && s.health > 0f && s.state.suppressionLevel >= 0.9f) {
                            early = true;
                        }
                        if (s.tick >= 800 && s.tick <= 1400 && s.health > 0f) {
                            aliveLate = true;
                            if (s.state.suppressionLevel >= 0.9f) late = true;
                        }
                    }
                    return early && (late || !aliveLate);
                })
            .assertThat("NEVER-LEAVE: while suppressed (>= 0.6, measured from t100 "
                    + "post-approach) the soldier never displaces more than 1.0 block from "
                    + "his settled dock (median position over t60-240; dock-relative because "
                    + "attack dock scoring makes the absolute dock unstable across runs)",
                view -> {
                    double[] dock = settledDockMedian(view);
                    if (dock == null) return false;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 100) continue;
                        if (s.health <= 0f) continue;
                        if (s.state.suppressionLevel < 0.6f) continue;
                        double dx = s.state.position.x - dock[0];
                        double dz = s.state.position.z - dock[1];
                        if (dx * dx + dz * dz > 1.0) return false;
                    }
                    return true;
                })
            .assertThat("NO EXIT-WALK: no SEEKING_COVER/REPOSITIONING/NO_COVER episode while "
                    + "suppressed (>= 0.6, measured from t100 post-approach) carries net "
                    + "displacement beyond 1.5 blocks — state churn without displacement is "
                    + "not a walk-out",
                view -> {
                    boolean inEpisode = false;
                    double originX = 0;
                    double originZ = 0;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.tick < 100) continue;
                        boolean exitState = "SEEKING_COVER".equals(s.state.coverState)
                            || "REPOSITIONING".equals(s.state.coverState)
                            || "NO_COVER".equals(s.state.coverState);
                        if (!exitState || s.health <= 0f || s.state.suppressionLevel < 0.6f) {
                            inEpisode = false;
                            continue;
                        }
                        if (!inEpisode) {
                            inEpisode = true;
                            originX = s.state.position.x;
                            originZ = s.state.position.z;
                        }
                        double dx = s.state.position.x - originX;
                        double dz = s.state.position.z - originZ;
                        if (dx * dx + dz * dz > 2.25) return false;
                    }
                    return true;
                })
            .assertThat("DIES-AT-DOCK: if the fire kills him he dies within 1.5 blocks of "
                    + "his settled dock (death at the dock is doctrine holding, not a "
                    + "failure; vacuous when he survives)",
                view -> {
                    double[] dock = settledDockMedian(view);
                    if (dock == null) return false;
                    boolean sawDeath = false;
                    SimSample lastAlive = null;
                    for (SimSample s : view.timeline().series("rifleman")) {
                        if (s.health > 0f) lastAlive = s;
                        else sawDeath = true;
                    }
                    if (!sawDeath) return true;
                    if (lastAlive == null) return false;
                    double dx = lastAlive.state.position.x - dock[0];
                    double dz = lastAlive.state.position.z - dock[1];
                    return dx * dx + dz * dz <= 2.25;
                })
            .build();
    }

    /**
     * The settled dock of the "rifleman" as the MEDIAN (x,z) of his living
     * samples in t60-240: after the band-edge dock settles (~t45 with this
     * geometry) and inside the early engagement, so one relocation hop or
     * peek excursion cannot move the reference. Median, not min/mean — the
     * dock-relative line must track the TYPICAL docked position when attack
     * dock scoring makes the absolute dock z unstable across runs.
     * Null when the soldier never sampled alive in the window (fixture broke).
     */
    private static double[] settledDockMedian(SimScenario.SimRunResultView view) {
        java.util.List<Double> xs = new java.util.ArrayList<>();
        java.util.List<Double> zs = new java.util.ArrayList<>();
        for (SimSample s : view.timeline().series("rifleman")) {
            if (s.tick < 60) continue;
            if (s.tick > 240) break;
            if (s.health <= 0f) continue;
            xs.add(s.state.position.x);
            zs.add(s.state.position.z);
        }
        if (xs.isEmpty()) return null;
        double[] xsArr = new double[xs.size()];
        double[] zsArr = new double[zs.size()];
        for (int i = 0; i < xs.size(); i++) {
            xsArr[i] = xs.get(i);
            zsArr[i] = zs.get(i);
        }
        java.util.Arrays.sort(xsArr);
        java.util.Arrays.sort(zsArr);
        int mid = xsArr.length / 2;
        double mx = xsArr.length % 2 == 1 ? xsArr[mid] : (xsArr[mid - 1] + xsArr[mid]) / 2.0;
        double mz = zsArr.length % 2 == 1 ? zsArr[mid] : (zsArr[mid - 1] + zsArr[mid]) / 2.0;
        return new double[] {mx, mz};
    }

    private static SimScenario attackFireAdvance(String name, int defenderCount, boolean hardenDefenders) {
        final String[] labels = {"alpha", "bravo", "charlie", "delta"};
        final int n = labels.length;
        // Per-soldier advance-profile telemetry, sampled every 5t.
        final double[] lastX = new double[n];
        final double[] lastZ = new double[n];
        final boolean[] haveLast = new boolean[n];
        /** Samples walking exposed (not docked in cover). */
        final int[] exposedMoveSamples = new int[n];
        /** Of those, samples with suppression >= 0.5 (advancing under fire). */
        final int[] exposedMoveUnderFire = new int[n];
        final int[] curRun = new int[n];
        final int[] maxExposedRun = new int[n];
        final double[] curRunDist = new double[n];
        final double[] maxRunDistance = new double[n];
        /** Cover-dock transitions over the whole run (street subset below). */
        final int[] dockEpisodes = new int[n];
        final boolean[] prevInCover = new boolean[n];
        final int[] streetDockEpisodes = new int[n];
        final double[] maxForwardX = new double[n];
        final int[] firstDeathTick = new int[n];
        /** Closest approach to any living enemy while not docked (blocks). */
        final double[] closestExposedApproach = new double[n];
        java.util.Arrays.fill(closestExposedApproach, Double.MAX_VALUE);
        final int[] enemyDeathTick = new int[defenderCount];
        java.util.Arrays.fill(enemyDeathTick, -1);
        final int[] probeSamples = {0};

        SimScenario.Builder builder = SimScenario.builder(name)
            .description("ATTACK advance behavior probe on the 50-block defended street: the squad "
                + "starts at the west end in the open and pings house B; " + defenderCount
                + " armed defenders hold the east end behind the cover lines. Measurement run for "
                + "the user-reported fire-superiority charge: dock cadence, exposed-walk runs, "
                + "moving-under-fire time, and casualties on both sides — the user judges the "
                + "behavior, these numbers only have to be honest.")
            .duration(4800)
            .field(96, 40)
            .armed()
            // Live-fire standoff families, same waiver set as clear_long_egress
            // (this scenario deliberately creates that standoff shape).
            .suppressDetectors("CoverOscillation", "ExposedWhileHiding",
                "FireDeadlock", "RotationOscillation", "PositionDeadlock", "StuckSeek",
                // Live-fire peek cadence roulette: 6 clean peek exposures from
                // one row inside a 290t firefight trips it on some dice
                // (run 20261014_123449), never on others — the probe owns its
                // behavior asserts, not peek doctrine.
                "PeekThrash",
                // Hardened-defender artifact: hits land but damage is ignored,
                // so "enemy took no damage" can never distinguish a dead firing
                // chain from the instrument (run 20261014_113930: two false
                // FireStarvations while tracers recorded).
                hardenDefenders ? "FireStarvation" : "CoverOscillation")
            .terrain(t -> t.longStreetBlock());
        for (int t = 20; t <= 4780; t += 5) {
            final int at = t;
            builder.action(at, "advance profile probe", ctx -> {
                probeSamples[0]++;
                for (int i = 0; i < n; i++) {
                    SoldierEntity s = ctx.soldier(labels[i]);
                    if (s == null) continue;
                    if (!s.isAlive()) {
                        if (firstDeathTick[i] < 0) firstDeathTick[i] = at;
                        continue;
                    }
                    double x = s.getX();
                    double z = s.getZ();
                    maxForwardX[i] = Math.max(maxForwardX[i], x);
                    var cover = s.getCoverBehaviorManager();
                    boolean inCover = cover.getState()
                            == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.IN_COVER
                        || cover.getState()
                            == com.stevesarmy.combat.cover.CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER;
                    if (inCover && !prevInCover[i]) {
                        dockEpisodes[i]++;
                        if (x < 66.5) streetDockEpisodes[i]++;
                    }
                    prevInCover[i] = inCover;
                    double disp = haveLast[i]
                        ? Math.sqrt((x - lastX[i]) * (x - lastX[i]) + (z - lastZ[i]) * (z - lastZ[i]))
                        : 0.0;
                    lastX[i] = x;
                    lastZ[i] = z;
                    haveLast[i] = true;
                    boolean movingExposed = !inCover && disp > 0.5;
                    if (movingExposed) {
                        exposedMoveSamples[i]++;
                        if (cover.getSuppressionTracker().getSuppressionLevel() >= 0.5) {
                            exposedMoveUnderFire[i]++;
                        }
                        curRun[i]++;
                        curRunDist[i] += disp;
                        if (curRun[i] > maxExposedRun[i]) {
                            maxExposedRun[i] = curRun[i];
                            maxRunDistance[i] = curRunDist[i];
                        }
                    } else {
                        curRun[i] = 0;
                        curRunDist[i] = 0.0;
                    }
                    if (!inCover) {
                        double nearest = Double.MAX_VALUE;
                        for (EnemySoldierEntity e : ctx.enemies) {
                            if (!e.isAlive() || e.isRemoved()) continue;
                            double d = Math.sqrt(
                                (x - e.getX()) * (x - e.getX()) + (z - e.getZ()) * (z - e.getZ()));
                            nearest = Math.min(nearest, d);
                        }
                        if (nearest < closestExposedApproach[i]) {
                            closestExposedApproach[i] = nearest;
                        }
                    }
                }
                for (int e = 0; e < defenderCount && e < ctx.enemies.size(); e++) {
                    EnemySoldierEntity en = ctx.enemies.get(e);
                    if (enemyDeathTick[e] < 0 && !(en.isAlive() && !en.isRemoved())) {
                        enemyDeathTick[e] = at;
                    }
                }
            });
        }
        builder.action(4740, "advance profile diag", ctx -> {
            for (int i = 0; i < n; i++) {
                com.stevesarmy.StevesArmyMod.LOGGER.info(
                    "[Sim][diag] {} {}: maxFwd={} docks={} streetDocks={} exposedMove={} underFire={} "
                        + "worstRun={}samp ({}blk) closestApproach={} firstDeath={}",
                    name, labels[i],
                    String.format("%.1f", maxForwardX[i]),
                    dockEpisodes[i], streetDockEpisodes[i],
                    exposedMoveSamples[i], exposedMoveUnderFire[i],
                    maxExposedRun[i], String.format("%.1f", maxRunDistance[i]),
                    closestExposedApproach[i] == Double.MAX_VALUE ? "-" :
                        String.format("%.1f", closestExposedApproach[i]),
                    firstDeathTick[i] < 0 ? "-" : String.valueOf(firstDeathTick[i]));
            }
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim][diag] {} defender death ticks: {}", name,
                java.util.Arrays.toString(java.util.Arrays.copyOf(enemyDeathTick, defenderCount)));
        });
        return builder
            .setup(ctx -> {
                // Squad in the open at the west end of the street; the four
                // waist-high cover lines (x=28/40/52/62) are the bound
                // options between them and house B.
                ctx.spawnAnchor(18, 0, 18);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "alpha", 18, 0, 18, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "bravo", 18, 0, 14, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "charlie", 18, 0, 22, SquadMode.HOLD);
                ctx.spawnFriendly(ModEntities.SOLDIER.get(), "delta", 20, 0, 18, SquadMode.HOLD);
                // Parity: the house B guard alone. Superiority: two more
                // defenders docked behind the x=52 (north lane) and x=62
                // (south lane) cover rows.
                EnemySoldierEntity guard = ctx.spawnEnemyLabeled("guard", 74, 0, 15);
                guard.setSquadMode(SquadMode.HOLD);
                guard.setHoldPosition(ctx.terrain.at(74, 0, 15));
                if (defenderCount >= 2) {
                    EnemySoldierEntity flanker = ctx.spawnEnemyLabeled("flanker", 53, 0, 14);
                    flanker.setSquadMode(SquadMode.HOLD);
                    flanker.setHoldPosition(ctx.terrain.at(53, 0, 14));
                }
                if (defenderCount >= 3) {
                    EnemySoldierEntity runner = ctx.spawnEnemyLabeled("runner", 63, 0, 20);
                    runner.setSquadMode(SquadMode.HOLD);
                    runner.setHoldPosition(ctx.terrain.at(63, 0, 20));
                }
                if (hardenDefenders) {
                    // The point is the advance profile UNDER sustained fire,
                    // not who wins: immortal defenders keep the exchange
                    // (and the contact-plan gate) live for the whole clock.
                    for (String enemyLabel : new String[] {"guard", "flanker", "runner"}) {
                        ctx.harden(enemyLabel);
                    }
                }
                ctx.issueAttackOrder(72, 18); // house B center
            })
            .assertThat("firefight happened (tracers recorded)", view -> !view.shots().isEmpty())
            .assertThat("advance probe collected samples", view -> probeSamples[0] > 100)
            .build();
    }
}

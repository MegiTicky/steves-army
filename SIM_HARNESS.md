# Simulation Harness (`/stevesarmy_sim`)

A headless scenario harness that runs the **real** soldier state machine — cover
seek, suppression, peek, relocation, follow gating — against programmatic
terrain, samples the same per-soldier telemetry the `/stevesarmy_debug
coverstate` card shows, and **flags bug signatures automatically** so diagnosis
does not depend on eyeballing in-game behavior.

## Why in-game and not a standalone simulator

The decision logic (`CoverTacticalGoal` ~6.2k lines, `SoldierCombatGoal` ~4.3k)
is entangled with real pathfinding, entity physics, and `VisibilityRay`
raycasts. A re-implemented copy drifts and stops proving anything about the
live code (see the abandoned workspace-root `cover_simulator/`). The harness
has perfect fidelity: real blocks, real DDA, real navigation, real failure
signals.

## Running

```powershell
# headless: run the CORE suite (27 stable per-family guards; the default), halt
.\gradlew runServer -Psim.autorun=true

# headless: run EVERY registered scenario (~67, incl. roulette probes and open-bug reproducers)
.\gradlew runServer -Psim.autorun=true -Psim.suite=full

# headless: run a single scenario (fast iteration)
.\gradlew runServer -Psim.autorun=true -Psim.scenario=mg_rifleman_seek_contrast

# headless at real-time 20 TPS (default is unthrottled — see Fast ticks below)
.\gradlew runServer -Psim.autorun=true -Psim.fastticks=false

# interactive: from the dev client or a server console
/stevesarmy_sim run all
/stevesarmy_sim run hold_enclosed_pocket
/stevesarmy_sim list
```

**Suite tiers:** scenarios marked `.core()` in `SimRegistry` run by default
(one-or-more stable guards per behavior family); everything else is full-only
(roulette-prone combat probes, open-bug reproducers, danger-map rigs, long
benchmark clears). `-Psim.suite=full` overrides; an explicit
`-Psim.scenario=<name>` list overrides both. Deleted-by-design content: the
old A/B archive baselines (`cqb_freeze_baseline`,
`cqb_formation_freeze_baseline`, `cqb_corner_frozen_aim_baseline`,
`exposed_ff_frozen_baseline`, `street_swarm_baseline`) were removed
2026-10-09 — their evidence lives in git history.

**XFAIL (expected failures):** scenarios marked `.expectedFail(<reason>)` are
negative tests whose FAIL *is* the pass condition — currently the three
`danger_selftest_*` rigs that inject deliberately-wrong samplers and require
`DangerOracleMismatch` to trip. Their reports show
`outcome : XFAIL (expected: ...)` and the final `SIM COMPLETE` tally counts
them separately (`N pass, M xfail, K fail`). If an expected-fail scenario ever
**passes**, it renders `XPASS — expected-fail scenario passed; investigate`
and counts as a real failure — that alarm means the thing it guards against
stopped happening.

**Detector suppression:** scenarios may skip specific generic detectors with
`.suppressDetectors("StuckSeek", ...)` (exact detector names) when the
detector is known-noisy for that geometry while the scenario's own doctrine
assertions stay live. Used by `exposed_plant_engage`/`exposed_ff_sidestep`
whose featureless arenas trip StuckSeek/FollowEscapeBlocked by design (the
far-side cover lure finding).

Reports land in `run/sim_reports/<scenario>_<timestamp>.txt` (+ `.json`).
The txt report contains the outcome, every detector violation with its full
counter-chain evidence, a per-soldier segmented state timeline, and a
**cover-dock fidelity** summary line per soldier (final slot offset, anchor
velocity pulls, worst body rays seen while hiding/standing). The final
autorun log line `SIM COMPLETE` is the CI-style grep anchor; per-scenario
`PASS`/`FAIL` is in the report and the `[Sim]` log lines.

### Fast ticks

By default, autorun suites are **unthrottled**: `SimManager.onFastTickClock`
pins `MinecraftServer.nextTickTime` (the vanilla 50 ms/tick pacing anchor)
~100 ms into the past every tick, so the server ticks at CPU speed instead of
real time. A full 67-scenario suite drops from ~38 minutes to ~90 seconds
(the core 27 run in well under a minute).
Two pieces keep behavior identical to production 20 TPS:

- Gameplay millisecond timers (peek cadence, cover entry, suppression memory,
  reservation timeouts — everything reading `GameClock.nowMs()`) resolve to
  game-time-derived milliseconds (50 ms per tick) while fast ticks are on, so
  they tick-for-tick match the wall-clock behavior at 20 TPS.
- World saving is disabled for the sim world during autorun (`noSave`), since
  the throwaway world is rebuilt per scenario anyway.

`-Psim.fastticks=false` runs the old real-time 20 TPS regime (A/B comparisons,
timing-debugging); `-Psim.fastticks=true` forces unthrottling for interactive
`/stevesarmy_sim` runs. Production and normal dev runs never touch any of
this (`auto` = on iff `sim.autorun`).

Known fidelity limit: **async worker latency is wall-clock, not tick-clock.**
`asyncPathfindingEnabled`/`asyncCoverPilotEnabled` (both default true) run A*
and cover-pilot searches on worker threads; a result that takes ~3 ms of real
worker time arrives within the same or next tick at 20 TPS (50 ms/tick) but
1–3 ticks later when unthrottled (~1–3 ms/tick). `GameClock` cannot absorb
this — it is thread scheduling, not a timer read. In practice this only
matters for latency-sensitive detectors in already-unstable scenarios (one
known case: `clear_deep_objective_sequence` hit `PositionDeadlock` at a fixed
tick in 2 of 3 unthrottled runs, 0 of 4 at 20 TPS — that scenario also FAILs
on the 20 TPS baseline with varying reasons). Verdict-level A/B on stable
scenarios matches exactly. If a fast-run-only signal looks async-related,
rerun that scenario with `-Psim.fastticks=false` before chasing it.

### GO_TO arrival timing

`goto_cover_endpoint_completion` and `goto_cross_cover_center_keeps_claim` put
static cover in the destination search area before issuing GO_TO. The selected
dock completes the command even outside the ping centre radius. The probes
check that a route crossing the centre keeps its cover approach, that arrival
clears relocation ownership, and that the completed order retains the dock
through the ordinary HOLD march dwell. Run both with fast ticks and
`-Psim.fastticks=false`.

`goto_arrival_cover_delay` forces an initial cover relocation failure, then adds
reachable half cover when the direct walk reaches its objective, with a known
enemy 30 blocks beyond it. It checks search initiation within 20 ticks, selection
within 60 ticks, physical docking within 100 ticks, and preservation of the
completed GO_TO holding state. `[SimGoToArrival]` logs the measured ticks and the
arrival cooldown. Confirm timing with `-Psim.fastticks=false` as well as fast ticks.

### Crowded ATTACK traversal probes

The full-only scenarios `crowded_attack_rows`, `crowded_attack_tall_rows`,
`attack_reping_low_cover_12v12`, and `attack_reping_moved_chokepoints_12v12`
exercise 12 friendly riflemen and 12 defenders with successive cover rows,
reissued objectives, or moved objectives. Run them together with:

```powershell
.\gradlew runServer -Psim.autorun=true -Psim.scenario=crowded_attack_rows,crowded_attack_tall_rows,attack_reping_low_cover_12v12,attack_reping_moved_chokepoints_12v12
```

`TraversalSpeedProbe` independently measures physical displacement every server
tick, including collisions and rescue teleports; its first tick establishes the
spawn baseline. `[SimTraversalSpeed]` log lines summarize the distribution and
largest movements with height changes, velocity, movement owner, cover/peek
state, movement attributes, and controller intent. Intent is not measured
blocks per tick. Investigation thresholds are >=1 horizontal block in one tick,
or >=0.60 blocks per tick for three consecutive ticks. These are candidate
signatures, not proof of a controller fault; jumps, shoves, and rescue teleports
remain in the evidence. Progress/combat assertions guard against vacant probes.

The gun handler uses the Test instance's default-pack M4A1 movement profile
(weight 3.5, aim -0.15, reload -0.10). Firing still uses the harness's hitscan gun
model, not the actual M4A1 implementation or its 810 RPM cadence. Confirm any
fast-run signal with `-Psim.fastticks=false` before drawing timing conclusions.

### Machine-gunner ATTACK continuity

`mg_advance_continuity` advances an MG and rifleman through half-cover rows
five blocks apart with distant stationary enemies. It checks physical docking,
successor-cover ownership when leaving a dock, and unexplained exposed pauses;
peek excursions and completed orders do not count as departures.

`mg_stale_claim_endpoint` first waits for a real MG dock, then reconstructs the
trace's endpoint state: navigation stopped away from that dock, `NO_COVER`, a
retained origin claim, and recent sub-threshold suppression. It checks recovery
within 20 ticks and release of the remote claim. The relocation is an explicit
fault-state injection, not a claim of deterministic replay of the original trace.
Run both with fast ticks and `-Psim.fastticks=false`.

### Watching the simulation

Every scenario also produces `<scenario>_<timestamp>_sim.html` — a
self-contained top-down animation of the run (double-click to open in any
browser; no server or codec needed). Soldiers are colored by cover state with a
white ring when pinned and a red arc showing suppression level; dashed white
lines are live line-of-sight, cyan lines point at the pursued cover (marker
colored by cover type), terrain is painted with vanilla map colors plus the
vanilla +z relief shading (so walls read as elevation edges, not color hints),
yellow lines are shots fired with an impact dot at the aim point,
red squares are enemies. The event feed below the scrubber is append-only.
Play at 0.5–10x, scrub, and click a soldier for its full state snapshot at
that tick (including shots fired, magazine, and the `last search` line —
mode/phase/result plus where the search looked and its no-cover streak). The
same data ships machine-readable as
`<scenario>_<timestamp>_recording.json`. You can also
watch a run live: `/stevesarmy_sim run <name>` from the dev client and fly up
to the fixture (platform at Y=160+, west edge of the world origin).

### Guns & ammo in the sim

**Armed scenarios fight with real sim guns** (`sim/SimGunHandler`, installed
per scenario when the builder calls `.armed()`): a hitscan rifle (30-round
mag, ~600 RPM, 4 dmg, 64-block range; the MG gets a 100-round belt, `mg` gun
tab and `isMachineGun=true` so the base-of-fire doctrine engages). Every shot
clips against real blocks, damages the first living thing it crosses, and
feeds the same near-miss segment check TaCZ bullets use
(`IncomingFireHandler.checkNearMissLineSegment`) — so suppression, weapon
threat intel and fire-superiority tracking run on both sides organically.
The handler is wired through a dedicated facade override
(`GunIntegration.installSimGunHandler`) and makes `isAnyGunLoaded()` true
while armed, which opens the combat goal's whole firing chain; un-armed
scenarios uninstall it and keep the deterministic scripted-pressure model
(`FallbackGunHandler` stays a stub).

Unarmed scenarios still suppress via scripted injection
(`.suppress(label, from, until, interval)` → repeated `SuppressionTracker`
near-misses) and reports print `guns: ABSENT` with firing-chain detectors
skipped.

Every successful round fired through `GunIntegration.shoot*` (any shooter,
sim gun, TaCZ or a future real fallback) is tapped by
`GunIntegration.shotListener`, which the harness installs per scenario:
shots appear as yellow tracers in the frame they were fired in, per-soldier
`shots fired` counters, `ammo / magazine` and `hp` show in the inspect panel
and per-frame recording, and `SimSample.shotsFired` is available to detectors.
Sim samples report the live combat-entity target (`soldier.getTarget()`), not
`hasValidAttackTarget()` (that flag is the player-issued ATTACK objective and
is never set in scenarios).

### Cover-arrival fidelity probes (dock suite)

The final phase of getting into cover is velocity manipulation:
`CoverPositionController` anchors an idle soldier onto the exact slot center
(block center, 0.08-block deadzone) by adding/removing radial velocity every
tick, and peek returns dock at a 0.05 tolerance. The dock suite verifies this
physically instead of trusting the state machine:

- **slot offset** — horizontal distance from the soldier to its claimed
  cover slot center, sampled every 5t (`cd` in the recording/inspect panel).
- **anchor pulls** — `CoverPositionController` debug counter: ticks where the
  anchor actually nudged velocity toward the slot (`ap`).
- **body-exposure rays** — `CoverFidelityProbe` casts 15 block rays from the
  nearest enemy's eye to sample points across the soldier's real bounding box
  (feet/mid/head bands x corners+center); counts rays with a clear line
  (`xr`, head band `xh`). A healthy low-crouch (0.6-high box) behind any
  cover probes 0/15; standing in half cover tolerates head rays (you look
  over the wall) but torso rays mean the slot does not shield the body.
- **scripted knockback** — `SimContext.knockbackIfIdleInCover` /
  `knockbackIfReloadingInCover` push the soldier off its slot state-gated
  (idle-in-cover / mid-reload), standing in for explosions and melee.

## Architecture

- `sim/SimManager` — tick-loop runner: build fixture → spawn actors → scripted
  action timeline → sample every 5 ticks → detectors + assertions → report →
  cleanup/next. Auto-runs from `ServerStartedEvent` when `-Dsim.autorun=true`.
- `sim/SimRecorder` + `sim/SimRecordingWriter` — per-sample animation frames
  (states, LoS, cover targets, enemies, event markers) serialized into the
  self-contained HTML player per scenario run.
- `sim/sim/scenario/SimScenario` — declarative DSL: terrain builder, actor
  setup, timed actions (`suppress(...)`, `moveEnemy`, `damageSoldier`),
  explicit assertions.
- `sim/terrain/SimTerrain` — real `setBlock` fixtures (platform with rim walls,
  pockets, cover rows) at a per-scenario Y level; every placed block is
  tracked and removed at cleanup. Fixtures stack 8Y apart so the full
  14-scenario registry stays under the y=319 build limit (placement above it
  is rejected, so `SimTerrain.block()` fails loudly if a fixture would cross
  it).
- `sim/CoverFidelityProbe` — the physical cover check behind the dock suite:
  15 block rays from the nearest enemy's eye across the soldier's bounding
  box, plus per-sample slot offset and anchor telemetry wiring.
- `sim/detector/StandardDetectors` — twelve pure-over-timeline checks. All
  thresholds live in `sim/SimTuning`.
- `sim/SimIsolation` — static-state reset inventory between scenarios
  (reservations, fire-team suppression memory, pending searches, squads,
  raytrace caches). `resetForSim()` methods added to the owning classes.
- `combat/CombatStateSnapshot` — the shared per-soldier snapshot extracted
  from `CoverTacticalGoal`; feeds both the debug packet and the harness.

## Danger-map verification rig (2026-10-07)

The harness is also the ground-truth rig for the per-squad danger map
(`squad/SquadDangerMap`). Three layers:

- **Oracle** (`sim/DangerOracle`): recomputes per-cell exposure with
  independent vanilla clips — never through VisibilityRay or map code. Grid
  convention (the map mirrors it exactly): 2x2 cells keyed
  `(floorDiv(x,2), floorDiv(z,2))`, probes at leg 0.4 / eye 1.6 with a
  6-ray body-span fan (center ±0.45 perpendicular to the threat axis);
  per-height bits boolean, score fractional (weight × 100 × exposedRays/6).
- **Threat injection**: `ctx.injectThreat(x, z)` writes a synthetic
  sighting straight into `SquadThreatIntel` (no entity, fully
  deterministic); `refreshInjectedThreats` renews the 200t freshness
  window, `moveInjectedThreat` teleports one (origin-drift invalidation),
  `markInjectedThreatsDead` kills them. The oracle is computed from the
  injection record at scenario end, so killed threats leave it.
- **Judgment**: SimManager sweeps the live sampler (the soldier's real map,
  or a scenario-registered stub) over the verification region at finish;
  `DangerOracleMismatch` FAILs beyond a per-cell tolerance (one fan-ray
  of slack absorbs clip-code grazing) and a 10% fraction (knife-edge cells
  vs systematic errors). `DangerProbeDisagreement` cross-checks sampled
  danger against CoverFidelityProbe body rays (WARN; the standing-vs-prone
  gap is doctrine, so the hot-side rule requires the leg bit).
  `DangerBudgetExceeded` enforces the global per-tick ray budget.

The **red/green self-tests** (`danger_selftest_*`, expected FAILs) prove
the rig bites: all-zero / all-max / mirrored stub samplers must trip the
mismatch detector. Real-map scenarios: `danger_oracle_*` (open field,
ridge shadow, lone-block bits, crossfire union), `danger_oracle_no_intel`
(empty state = verified-safe zero), `danger_decay_real` (threat death
decays within the view-refresh window), `danger_shift_real` (origin drift
re-bakes the shadow), `danger_multi_squad_budget` (two squads share the
budget), `danger_path_detour` (an attacking AT routes along the wall's
shadow lane instead of the exposed diagonal).

Consumers (stage 2b, pathfinding only): pathfinding adds
`roleWeight × danger/100` to corridor cells — graded per role (rifleman
1.5, machine gunner 3.0, garrison 2.0, AT 4.0, support 6.0) — sync paths
included (SupportDutyController's direct createPath calls run the same
corridor pipeline). The stage-2a cover-scoring danger term + claim gate
were REVERTED (2d803ba): cover selection reads nothing from the map
(doctrine: the danger map is "prefer safer path", never "safer cover");
the `dangerCoverWeight`/`dangerClaimMaxScore` config keys no longer exist.

`sweepLeftoverFixtureBlocks` removes fixture-palette blocks left above
Y_BASE by crashed sessions at startup — strays poison every heightmap
query downstream. Fixtures now stack 5Y apart (32 scenarios must fit
under the build limit).
## Scenarios (v1 — cover/suppression strand)

| Scenario | What it reproduces |
|---|---|
| `suppressed_follow_escape` | FOLLOW squad pinned under fire; escape + gate reopening |
| `hold_enclosed_pocket` | HOLD in a one-exit pocket; fail-and-escalate without stranding |
| `persistent_ping_relocation` | Continuous suppression from one origin; relocation dead-ends |
| `mg_anchor_freshness` | MG centroid/anchor staleness after the shooter moves |
| `mg_rifleman_seek_contrast` | Same fire on an MG and two riflemen: both must reach cover (the live MG seek-strand bug) |
| `mg_suppression_round_robin` | Armed MG vs two dug-in enemies: suppression bursts must rotate across BOTH last-seen positions (round-robin, no fixation); enemy A pinned via injection so the unsuppressed-first rank applies. Asserts on recorded tracer clusters (`view.shots()`) |
| `mg_direct_fire_cadence` | Armed MG (off-column, behind its own cover row) vs a hardened gunless patroller in the open at ~48 blocks: the MG must open fire promptly (TTFS <=120t), sustain >=30 tracers, and land >=1 burst of >=7 shots (the 5-shot baseline profile cannot). A/B probe for the direct-fire cadence fix: baseline 44-66 sparse shots / TTFS 55 vs fixed 155-187 / TTFS 10 |
| `fireteam_role_balance` | 4 MGs + 4 riflemen spawned in two role clusters, all empty-handed (the command-spawn/sidearm state): fire team rebalance must bucket by ROLE — each team gets <=2 of each role instead of whole clusters, and no soldier is dropped. A/B probe for role-keyed buckets: baseline clumps 4/0 per role |
| `cover_cross_role_reservation` | Standing-cover reservation and MG prone-lane reservation must exclude each other (3-block separation) in both directions on write+query paths, and CoverFinder must not return a spot within the separation of a prone lane. A/B probe for the cross-map checks: baseline accepts both overlaps |
| `cover_reservation_keepalive` | A reservation whose holder physically occupies the spot must survive its 30s timeout (occupancy keep-alive sweep) and free once the holder leaves. A/B probe for the stop()/clearCover stacking hole: baseline expires the spot while still occupied |
| `pair_suppression_lanes` | Two suppressors; dispersion vs stacking |
| `open_field_baseline` | No suppression; detector false-positive calibration |
| `dock_arrival_roles` | Rifleman + MG + AT pulse into cover; each must dock into the exact slot block with the anchor velocity engaged |
| `dock_arrival_suppressed` | Rifleman + MG arriving under sustained suppression; anchor finishes the docking once pressure ends |
| `dock_reload_hold` | Armed: scripted reload pins the slot through shoves; anchor re-docks after the reload |
| `dock_knockback_recovery` | Support role (peek disabled) shoved off its slot while pinned; anchor must recover |
| `dock_suppressed_drift_recovery` | Rifleman shoved off its slot mid-pin; the suppressed-state anchor re-docks |
| `dock_cqb_hold_fidelity` | KNOWN FINDING (expected FAIL): a point-blank enemy latches the CQB hold during the approach; anchor starves, soldier locks off-slot and stays fully visible when the enemy flanks past the block |
| `dock_peek_return` | Armed peek cycles; the return move must land back in the exact slot |
| `lone_block_exposure` | KNOWN FINDING (expected FAIL): ranged reproduction of the lone-1-block death mechanism (see findings below) |
| `lone_block_flank_stand` | KNOWN FINDING (close range): lone block + enemy inside the 6-block flank gate; documents 10-12/15 rays exposed prone |
| `support_squad_fight` | Live-fire support + 2 riflemen vs 2 enemies: support must claim cover, dock unexposed, return after duty. KNOWN FINDINGS (expected FAIL): pre-intel searches type solid waist-high walls CONCEALMENT; the pose gate then leaves the misclassified soldier STANDING at a 1-high wall with head rays exposed the whole fight |
| `support_rear_cover_pick` | Deterministic pick probe: pinned support picks between a protected waist-high row 6 blocks out and a 2-high block 11 blocks out; corrected distance term must claim the near row |

### Combat positioning rework (2026-10-08, phase 1)

| Scenario | What it verifies |
|---|---|
| `dock_cqb_hold_fidelity` | FLIPPED to expected PASS: the emergency hold defers to a finishing dock, the no-shot clock releases a hold that produces nothing, and suppressed flank checks escape compromised cover |
| `cqb_freeze_baseline` | A/B twin (expected FAIL post-fix): asserts the OLD starvation — approach frozen mid-dock, soldier locked off-slot |
| `cqb_reload_close_protect` | Reload mid-approach while an enemy closes: ends docked, hold never starves the dock |
| `cqb_close_flank_seek` | A 4-block enemy re-enables cover seeking: the nearby pocket is claimed and docked |
| `cqb_hold_no_shot_release` | LoS clear but every shot FF-blocked by a docked teammate: no-shot timeout releases the hold, soldier claims reachable cover |
| `cqb_formation_no_freeze` | CQB formation = mobility doctrine: enemy at 8 blocks does NOT freeze the soldier; the nearby row is claimed |
| `cqb_formation_freeze_baseline` | A/B twin (expected FAIL post-fix): asserts the old freeze-any-distance doctrine |
| `course_cqb_house_approach` | Course scenario (two-room house + street, `SimTerrain.cqbCourse`): a hold march through the house completes while an enemy loiters outside the east door |

Config: the `close_combat` section (closeCombatRange/exitRange hysteresis 16/20, emergency
range 5, overrun range 3, dock finish 2.5, hold no-shot timeout 60t, release cooldown 40t,
seek radius 14, re-eval 10t). Fixture spacing is 3Y (53 scenarios fit under the build limit).

### Combat positioning rework (2026-10-08, phase 2 — exposed combat)

`ExposedCombatController` gives a soldier with NO usable cover a small dependable response
set instead of a frozen seek state: PLANT_AND_ENGAGE (stability window), BOUNDED_ADJUST
(one sidestep off an FF-blocked lane), WITHDRAW (checked distance, ends once not overrun +
reaction window — no infinite kite), DELIBERATE_HOLD. The controller decides at the seek
failure tails (`runExposedCombatFallback`, both the no-candidate tail and the
reservation-exhausted tail); the plant window re-arms after every failed round and vetoes
seek re-entry (attack-driven seeks included) so a coverless fight stops churning. During
doctrine ownership `evaluateCoverState` keeps the cover state truthful (no false
SEEKING_COVER). Fixtures: `SimManager.beginScenario` settles gun-handler armed mode BEFORE
setup runs (a transitioning handler used to wipe setup-time disarms).

| Scenario | What it verifies |
|---|---|
| `exposed_plant_engage` | Bare arena, stationary enemy 20 blocks: plants and fires promptly (TTFS ≤ 150t), damages the enemy |
| `exposed_ff_sidestep` | Anchor on the aim line (every shot FF-blocked, LoS clear): ONE bounded sidestep reopens the lane; shots land after it |
| `exposed_ff_frozen_baseline` | A/B twin (expected FAIL post-fix): asserts the old frozen-blocked-lane behavior |
| `exposed_withdraw_overrun` | Disarmed rusher from 11 blocks: never overrun (min gap ≥ 2), withdraw ends once clear (no kite), shots land while disengaging |
| `course_exposed_yard_fight` | Course scenario (street + house): fights from sparse rubble under real fire — tracers, damage, bounded roam |

KNOWN FINDING on the three bare-arena scenarios (expected StuckSeek/FollowEscapeBlocked
detector FAILs + wide roam): featureless platforms expose the pre-existing **far-side cover
lure** — the legacy scorer claims far rim/far-side junk cover and the walk out reads as a
seek. The doctrine assertions are the pass criteria; the lure penalty term is tracked
separately (see cover-scoring findings, 2026-10-07).

Config: the `exposed_combat` section (exposedCombatEnabled, plant min 40t, adjust window
40t / max attempts 1 / timeout 30t / sidestep 2.5 blocks, withdraw distance 8 / timeout 60t).

Every scenario except `open_field_baseline` (intentionally bare) places real
cover: waist-high stone-brick rows (half cover, peekable) and 2-high stacks
(full cover) between the actors and the shooter, spaced so each soldier has
reachable options and the pair/relocation scenarios have distinct candidates.

### Combat positioning rework (2026-10-08, phase 3 — squad street crossing)

`SquadMovementCoordinator` (squad package, server-ticked from SquadSyncHandler)
picks ONE bounder per fire team when a teammate is advancing on a hold objective
under threat (`squad_movement` config); the overwatcher anchors his lane (hold
goal stops, MG scores +10000 as preferred anchor). Covering fire = a teammate's
real successful shot inside `squadBoundCoverWindowTicks`; the bounder waits up
to `squadBoundCoverWaitTicks` for it. Rotation via LAST_BOUNDERS; bounds expire
on death/timeout (200t)/arrival. Solo soldiers bypass (no teammates, no bound).

**Dock-resume doctrine** (the missing half): a dock is a pause under fire, not a
terminus. `SoldierHoldPositionGoal.DOCK_SINCE` clocks each docked soldier;
`CoverTacticalGoal.canContinueToUse` yields the legs once the quiet dwell
(`holdMarchDwellTicks`, 80t) has sat out — but only when there is NO attack
target (a live fight keeps the dock), not suppressed, not the bounder (crosses
through), not the overwatch anchor, and the hold objective is beyond the
RESUME MARGIN (`MARCH_RESUME_DIST_SQ` = hold radius + 50, i.e. the hold sits
> ~12 blocks out). The margin matters: a dock a block outside the plain hold
radius (distSq 112 vs 100) used to yield, march ~1.5 blocks across the radius
line, arrive, and walk straight back to the same cover — a perpetual
dock/re-dock pendulum (caught by `danger_multi_squad_budget`'s bake assert,
whose churn also starved the second squad's shared ray budget). A dock inside
the margin is the terminus and never yields. The cover goal's stop releases
the cover; the HOLD-mode seek veto (`isTravelingToObjective`) keeps quiet seek
rounds from re-docking mid-march; arrival (within hold radius) stops the clock
and the soldier takes a final cover normally. The close-combat context latch
(16/20 hysteresis) deliberately does NOT reset the dwell — a live threat
merely within 20 blocks must not re-freeze the march (that was the stall this
phase removed).

Detector note: `CoverOscillation` ignores samples with suppression >= 0.3
(under-fire pin/dive/re-dive is the suppressed-traveler doctrine working) and
samples where the soldier is traveling to an objective (en-route claims are
transient dives by design). The post-suppression refractory previously used an
`Integer.MIN_VALUE` sentinel whose subtraction overflowed int and silently
skipped every quiet sample before the first suppressed sample — quiet-state
churn was invisible until a suppressed sample happened to occur first; the
sentinel is now `Long.MIN_VALUE` behind an explicit `sawSuppression` flag.
`SimManager.Y_BASE` lowered 160 -> 100 (3Y spacing now fits 73 scenarios;
assertions made Y-agnostic).

| Scenario | What it verifies |
|---|---|
| `street_bound_crossing` | Course street: pair crosses under fire STAGGERED — alpha bounds while bravo overwatches at the rubble row, bravo follows after alpha clears; both reach the north side |
| `street_mg_covers_bound` | MG overwatch-anchors (holds within 8 blocks of its anchor) while the rifle bounds across, then rotates across itself |
| `street_solo_no_coord` | Solo (coordinator bypass): quiet-dwell resume carries him across the same hot street; engages en route |
| `street_swarm_baseline` | A/B twin (expected FAIL post-fix): documents the old simultaneous two-man swarm |

Timing-sensitive: the crossing stagger depends on enemy fire timing (which
soldier eats the first bursts) — the staggered/anchor assertions pass on the
green runs; detector noise under suppression is exempted (see above). The
`ExposedWhileHiding` flags on rubble rows are the known half-cover exposure
family (see lone-block findings), not a phase-3 regression.

### Combat positioning rework (2026-10-08, phase 4 — CQB doorway/corner doctrine)

Three aim/flow behaviors for close-quarters movement, all riding the existing
CQB caution activation (manual CQB toggle or enemy near path):

- **Doorway queueing** (`CqbSteeringAdvisor.isDoorwayBlocked` +
  `CoverPositionController.tickDoorwayQueueHold`): a 1-wide gap in a wall —
  signature is DIRECTION-INVARIANT: exactly one opposing neighbour pair
  around the node is solid (the wall the gap sits in), the other pair the
  passage — held by a squadmate within `cqbDoorwaySpacing` (3.5) ahead stops
  the follower to a creep facing the passage — single-file stack, no clumping
  in the fatal funnel. Two hard-won lessons: (1) the signature must NOT be
  keyed to the follower's travel direction — A* approaches a wall door by
  hugging the wall and TURNING into the opening, so at the doorway node the
  instantaneous travel direction still runs ALONG the wall and every
  travel-relative frame test (ahead/behind solids, lateral-solids-vs-travel)
  misreads the door as open ground; both were tried and failed silently (the
  scenario asserts passed on pathing luck — validate with the
  `[DoorwayQueue]` hold logs behind `-Psim.coverlog`, not just asserts).
  (2) The lookahead needs ~8 nodes: the wall-hug approach puts the doorway
  node far out on the path while the follower is still several blocks back.
  Lives in an UNCONDITIONAL branch of MoveControl.tick BEFORE the
  movement-mode split, so it holds every movement owner alike: cover
  approaches (controller moves bypass `tickCautionSteering` — that placement
  was tried and failed), GO_TO relocations, hold marches, and attack
  approaches.
- **Corner pie-slicing** (`CqbSteeringAdvisor.getCornerWatchPos`, consumed in
  SoldierCombatGoal.tickScanning): while approaching a path bend with a known
  threat within 12 blocks of the apex, pre-aim 4 blocks past the corner into
  the arc the corner opens into (eye height). Aim leads the body around the
  corner instead of trailing it.
- **Lost-contact watch** (`SoldierCombatGoal.shouldStandLostContactWatch`):
  close-quarters contact that just broke (freshest threat memory younger than
  `cqbLostContactWatchTicks`, 80t) holds the soldier in place, aim on
  last-known — yields to SEEKING/REPOSITIONING cover states, exposed-combat
  moves/plants, and only stops navigation in the open (docked soldiers keep
  the dock; the existing last-known aim chain already watches).

Recording: frames now carry `yh` (head yaw) for aim-orientation assertions.

| Scenario | What it verifies |
|---|---|
| `cqb_doorway_queue` | Two manual-CQB soldiers funnel into the house through one door: never both within 1.5 of the door at once (single-file), both get inside |
| `cqb_corner_pie` | Manual-CQB march around the west door with squad intel past the corner: >= 4 northward-facing (pre-aim) samples on approach, march reaches hold radius |
| `cqb_corner_frozen_aim_baseline` | A/B twin (expected FAIL post-fix): asserts the old wall-facing aim |
| `cqb_lost_contact_watch` | Armed enemy slips behind the house wall: soldier holds position and keeps west-facing aim through the watch window |

## Detectors (all run in every scenario)

`movement_speed_transitions` is a core real-controller probe for stale crawl
compensation after ADS release, preservation of raw navigation requests, bounded
docking with sideways momentum, preservation of unanchored external impulses,
ordinary acceleration/steady pace, immediate idle stop, pinned escape dash and
firing-prone priority. Baseline reproduces the first two speed-burst mechanisms.
Recording inspect panels expose speed request, target/applied intent, limiting
reason and measured last-tick displacement; these values are also in recording JSON.

`caution_freshness_and_pace` is a core server-level input/controller probe with AI
disabled so observations cannot renew accidentally. It checks fresh and expired
position pings, squad intel and retained living references; corner-watch freshness;
automatic steering and fallback at requested pace under an attribute tax; requested
slow movement; manual CQB's ramp/cap; and restoration after a fallback clamp.
`caution_ads_travel_floor` now guards normal GO_TO pace (about 0.398 b/t with
weapon weight), replacing its historical assertion for the retired automatic cap.
Use `suppressed_crawl_pace` and `cover_approach_crawl` for end-to-end crawl checks,
and `cqb_doorway_queue` / `cqb_corner_pie` for manual CQB flow.

StuckSeek, SeekFailLoop, CoverOscillation, PeekThrash (incl. aborted
peek cycles), SuppressionStarve, FollowEscapeBlocked, PathFailStorm,
PositionDeadlock, FireStarvation (armed runs only), ReservationDrift
(+ post-run ReservationLeak check), CoverDockDrift (idle soldier sitting
off its slot — FAIL unsuppressed, WARN when suppressed drift exceeds the
cover block), ExposedWhileHiding (docked soldier's body rays visible to the
threat while HIDING — FAIL; torso rays while STANDING_IN_HALF_COVER — WARN).

## Known findings (2026-10-06, armed sim + MG seek-strand fix)

Status after the MG seek-strand fix, the fixture build-limit repair, and the
armed-sim work (SimGunHandler + telemetry fixes). Suite state: 6/7 PASS; the
one FAIL is a pre-existing intermittent.

**FIXED — reload mid-approach defers the final dock (user in-game report,
2026-10-06).** Symptom: "soldier reaches the cover but not exactly [in it]
until it waits a few seconds (reloading?)". Mechanism: the approach claims
the cover (`currentCover` set, state IN_COVER) while still 0.3-1.0 blocks
from the slot center — that claim→dock transit is normally closed by the
recenter + anchor, but a reload active at that moment suspended both:
`maintainCoverAnchor` refused to arm while `isPreparingOrReloading()`, and
once `currentCover != null` the `isMovingToUnoccupiedCoverDuringReload`
exception died, so recenter `moveTo`s came back RELOAD_INTERRUPTED and the
per-tick `holdMovementForReload` cleared the in-flight alignment. Fix: the
claim→dock transit now finishes through the reload under the same exception
as the approach — `CoverPositionController.isFinishingCoverDockDuringReload()`
(reload + claimed cover + outside the anchor deadzone) is honored by the
controller's reload gates (moveTo / tick clear / anchor arming) and by
`SoldierEntity.holdMovementForReload`, and `holdForReload` arms the anchor
for a pending dock. A docked reloading soldier is unaffected (the predicate
is false inside the deadzone), so the reload movement hold still damps
knockback. Verified in-sim: claim at 0.488 mid-reload → docked to 0.018
before the reload ended (`dock_reload_midapproach`, whose
`docksDuringReload` assertion binds the dock to the reload window; the
docked-reload damp regression `dock_reload_hold` still passes).

**FIXED — cover scoring: closeness never mattered, FULL outranked HALF regardless of distance (2026-10-06 evening).** Follow-up to the bug-1 diagnosis ("cover picks that don't block the threat line"): traced the live per-candidate scores with the new `-Psim.coverlog` flag and found four scorers deciding cover picks with different math. (1) `CoverPoint.distanceTo()` returns a SQUARED distance and `CoverFinder.calculateDistanceScore` normalized it linearly against a 24-block span, so every candidate ≥4.9 blocks scored dist=0.00 — closeness contributed nothing; `PureCoverEvaluator.distanceScore` (the async cover pilot's scorer) replicated the bug verbatim. (2) The pilot's discovery ran `discoverCoverPoints(center, radius)` with threat=null, so `evaluatePosition` classified every cell next to a ≥2-high wall as FULL from height alone — the platform rim (~40 cells, 10 blocks off-axis) all typed FULL and crowded the pilot's coarse top-16, evicting the nearby HALF row from full scoring entirely. (3) The legacy `calculateScore` fallback (quality×10 + protection×10 vs distance×0.05/block) let any FULL classification beat any HALF no matter the distance. Fix: sqrt the distance in both modern scorers; pass the primary threat into the pilot's discovery so classification is threat-aware; retire the legacy scorer (the CoverTacticalGoal full-search fallback now uses `evaluateAndScoreAllFromCenter` + the protection filter, and the debug `/stevesarmy_debug` best-cover probe scores through the modern path too). Added a `[PureScore]` trace (same coverlog flag) so the pilot's ranking is visible. Verified: `dock_reload_hold` now claims the closer HALF row at t25 (was: walked past it to a FULL block 11 blocks out) with the pilot ranking the row #1 at the predicted 0.946, 0/15 exposure rays while hiding; suite went 10/15 PASS with the remaining FAILs all in the known bug-1/bug-2/follow-strand families (one dock_reload_hold suite FAIL was the soldier dying to organic fire mid-assertion — correct cover claimed).

**REPRODUCED (partially) — lone 1-block cover: "hiding" soldier looks standing and dies under fire (user report, 2026-10-06 late).** Two scenarios: `lone_block_exposure` (ranged, four shooters) and `lone_block_flank_stand` (enemy inside the 6-block flank gate). What the sim DID reproduce: the death mechanism — peek-state HIDING at the lone block while **6/15 rays (ranged) and 10-12/15 rays (close) stay visible to the threat**, pinned at sup 0.83-1.00 for the whole fight, periodic hits until death (close range kills in ~150t even while correctly prone). A lone waist-high block physically cannot protect a ducked body; no posture fixes that. What the sim did NOT reproduce: the user's "keeps standing, even when suppressed, not proning" — in all three runs the server proned correctly under suppression, so that state is either **(a)** the render bug below (crouch looks standing — the dominant visual), **(b)** the emergency stand-fire cycle (`prepareToFire` stands a prone soldier whose direct target is within 6 blocks and outside the ±30° prone arc; the 20t EMR window vetoes the suppression-prone enforcer — needs a very close, out-of-arc, alive target, which the sim couldn't sustain because death precedes it), or **(c)** cover not typed HALF in that world (the enforcer early-returns for non-HALF covers). The upgraded `coverstate` card (protocol 15) now discriminates all three live: state line shows `[HALF h=1.0 CROUCH(low)] EMR PINNED` (cover type + synced height + server pose + low-crouch flag + emergency flag), and the counters line gained `sup=` (suppression level, previously never rendered).

**Render bug (the "just stands there" visual, code-traced — invisible to the headless sim by construction).** The pose gate correctly applies `Pose.CROUCHING` at low half cover (synced height 1.0 < 1.3), but `HumanoidModel.setupAnim` keys its crouch pose off `entity.isCrouching()`, which reads the shared *sneaking flag* that soldiers never set, and it overwrites `SoldierRenderer`'s `model.crouching` assignment (SoldierRenderer.java:48) on every frame — so a server-side-crouched soldier renders standing. `EnemySoldierRenderer.java:49` same. The prone/low-crouch pose renders fine (different code path, `isLowCrouching` in SoldierModel). Telemetry now records **posture** (STANDING/CROUCHING/SWIMMING + rise flag) and **synced cover height** per frame (JSON keys `po`/`ri`/`ch`; player glyphs: big circle standing, small circle crouched, flat ellipse prone, dashed ring rising), so pose failures are directly observable in the animation and assertions.

**REPRODUCED (server-side) — "hiding but standing at half-height cover", support-role run (2026-10-06 night).** User follow-up: "simulate runs with support and see if they are finding cover correctly (add enemy and riflemen too)". Two scenarios: `support_squad_fight` (live-fire: support + 2 riflemen vs 2 enemies behind their own cover) and `support_rear_cover_pick` (deterministic near-vs-far pick under injected suppression). Cover **picking** is healthy: the support claimed a waist-high row ~4 blocks away within 20t, docked to 0.04, held IN_COVER all 700t, helped kill both enemies (40 → 0 HP); the pick probe claims the near HALF row over the far block (score traces: near slot 0.84-0.94 vs far block 0.67). The defects are in **classification and posture**: (1) the support's claimed cover typed **CONCEALMENT** while the rifleman at the mirrored, identical wall typed HALF — `[CoverScore]` traces caught the same slot flipping between `type=CONCEALMENT` and `type=HALF` across search passes. Mechanism: `CoverQualityEvaluator.evaluateWithCone` (CoverQualityEvaluator.java:95) overrides the cone verdict with `coverHeight < 0.8 → CONCEALMENT`, reading the height via `getDirectionFromVector(threatDir)` — and with no threat intel yet `ThreatAwareness.getPrimaryDirection` returns `Vec3.ZERO`, which `getDirectionFromVector` cardinalizes to **NORTH** (CoverFinder.java:933), so a search that runs before any threat is registered reads the height of the *empty north neighbor* of the slot instead of the wall the cone rays actually hit. The claimed type is frozen at claim (no reclassification while IN_COVER), so the wrong type persists the whole fight. (2) The pose gate (SoldierEntity.tick) crouches only for HALF covers, so the CONCEALMENT-typed soldier stands upright behind a 1-high wall, peekState HIDING, **5 head rays exposed, for the entire fight** — the user's exact reported visual, now with a server-side mechanism that is *not* the render bug. Both encoded as expected-FAIL FINDING assertions on `support_squad_fight` (`everClaimedSolidCoverAsConcealment`, `sustainedStandingWhileHiding`). Fix candidates: don't let the height override contradict the cone rays (use the cone-blocked direction, or skip the override when threatDir is null/stale), and/or reclassify the claimed cover when live threat intel arrives, and/or extend the pose gate's crouch to any claimed cover whose synced height < 1.3.

**Earlier strands (pre-existing, unchanged):**

1. **Post-suppression relocation churn** (`suppressed_follow_escape`,
   intermittent, fails suite / passes solo) — under sustained pressure a
   soldier may hop through 3-4 relocation targets inside a 200t window
   (CoverOscillation fires); ends safely in cover.
2. **Peek stalls under sustained suppression** (organically visible in the
   armed contrast run and `dock_peek_return`) — a suppressed soldier sits in
   MOVING_TO_PEEK for ~110t instead of either committing to the peek or
   folding back to HIDING; the peek-start decision and the suppression gate
   disagree.
3. **Reservation drift (WARN)** — occupied cover unreserved 60-80t after the
   fight ends; claims outlive released reservations until the goal cycle
   catches up.

**Earlier same day — the "MG stuck in SEEKING_COVER, never pathing" bug.**
Four stacked causes, all visible once searches were instrumented (`ls`
telemetry: mode/phase/result/center/streak per search): (1) reservation spin —
only the single best candidate was ever reserved, so a squadmate-held cover
was re-selected forever (reservation failures never blacklist); fixed with
ordered reserve pools bounded to 4 movement attempts per search. (2) the MG
rear anchor pointed away from every cover; fixed with seek escalation (120t
wall-clock without a claim widens the radius and re-centers the search).
(3) `seekingTicks` freezes during prone-walk detours, so escalation now arms
on ticks-since-last-cover-claim. (4) prone deployment received an empty cover
list for target-less soldiers, making its physical-cover gate vacuous — the
soldier prone-walked into the open while suppressed (the visible westward
wander); fixed by always passing the scored covers and refusing prone while
suppressed. Also repaired: fixtures at Y≥320 were silently rejected by the
build limit (empty-air scenarios) — Y spacing is 20Y and placement throws.

**Armed-sim pass — sim soldiers now fight real firefights.**
`mg_rifleman_seek_contrast` runs with sim guns both ways: the squad claims
cover by t20-35 (MG included — the seek bug stays fixed under live fire),
takes organic suppression up to 1.0 from enemy near-misses, peeks and fires
back (EXPOSED/RETURNING cycles visible per soldier), and kills both enemies
by ~t300 while taking real damage. Enemy return fire suppressed the gunner
and one rifleman for ~150t. Zero detector violations. This is the peek-system
probe the harness was missing: pinned soldiers must expose and fire, and now
they demonstrably do.

**Also resolved: the "detection gap" was a telemetry bug, not an AI bug.**
Sim samples used `soldier.hasValidAttackTarget()`, which is the player-issued
ATTACK *objective* (a BlockPos from the command stick — never set in
scenarios), so `open_field_baseline`'s targeting assertion could never pass.
Sampling `soldier.getTarget()` instead made the scout's acquisition visible;
`open_field_baseline` now passes. The tracer buffer had the same class of bug
(`beginFrame` wiped shots before `endFrame` wrote them, so no tracer ever
survived into the animation) — both fixed.

## Known findings — dock suite pass (2026-10-06, cover-arrival fidelity)

Status after adding the dock suite (7 scenarios), the CoverDockDrift +
ExposedWhileHiding detectors, the slot-offset / anchor-pull / body-ray
telemetry, and state-gated knockback actions. Full suite: 14 scenarios,
8 PASS, 6 FAIL — every FAIL is evidence below, none is a harness artifact.

**VERIFIED WORKING — the final-phase velocity manipulation.** Across roles
(rifleman, MG, anti-tank, support) and edge cases, the `CoverPositionController`
anchor does exactly its job: soldiers dock to 0.045-0.08 blocks from the slot
center (the 0.08 deadzone), a 0.4-impulse shove off the slot is recovered in
~10 ticks (also mid-suppression, via the suppressed-state handler at
`CoverTacticalGoal`'s suppressed tick), reload holds damp knockback to ~0,
and peek returns land back within 0.06 of the slot. Anchor-pull counters and
per-frame offsets are in every recording.

**BUG — cover picks that do not block the threat line (`ExposedWhileHiding`,
5 of 14 scenarios).** The cover scorer accepts slots whose blocker misses the
actual threat geometry: a crouched soldier dead-center in its slot (offset
0.045!) still probes 12-15/15 exposed body rays when the threat sits at a
diagonal, because the ray to the soldier passes *beside* the 1-wide block.
Half-cover rows leak corner rays the same way (AT: 6/15 in
`dock_arrival_roles`). The protection validation and the physical blocking
disagree systematically — this is the "they look in cover but are not"
signature, now measurable. Fix candidates live in the cover scorer's
protection check (posture-aware rays from the live threat origin), not in
the movement code.

**BUG — CQB hold starves the cover anchor and outlives its purpose
(`dock_cqb_hold_fidelity`, expected FAIL).** When an enemy closes to
CQB_RANGE (5 blocks) while the soldier is still approaching its slot, the
CQB-engage branch of `CoverPositionController.tick()` zeroes velocity every
tick and returns *before* the anchor branch — the approach freezes wherever
it was (0.3-0.8 blocks off-slot). The soldier then claims
SUPPRESSED_IN_COVER/HIDING indefinitely while the enemy keeps advancing
*past* the cover block to point-blank on the wrong side: 15/15 body rays
visible for 345t with zero flank re-evaluation (flank detection lives in
`tickInCover`, which never runs while suppressed; the same run also trips
StuckSeek + SuppressionStarve). Two stacked defects: (1) the CQB hold should
not cancel docking/anchor of an already-claimed cover, and (2) suppressed
soldiers need a compromised-cover check when the threat crosses to the
blocker's own side.

**New detector signals on the older scenarios.** `hold_enclosed_pocket`,
`mg_anchor_freshness`, and `suppressed_follow_escape` now FAIL through
`ExposedWhileHiding` — the same cover-protection bug firing in their
fixtures. `persistent_ping_relocation` tripped PeekThrash once under 620t of
continuous scripted suppression (pressured peek cycles inside the thrash
window) — treat as calibration-vs-doctrine, not yet a bug claim.

**Earlier same day — the "MG stuck in SEEKING_COVER, never pathing" bug.**

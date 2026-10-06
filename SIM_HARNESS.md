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
# headless: run every scenario, write reports, halt the server
.\gradlew runServer -Psim.autorun=true

# headless: run a single scenario (fast iteration)
.\gradlew runServer -Psim.autorun=true -Psim.scenario=mg_rifleman_seek_contrast

# interactive: from the dev client or a server console
/stevesarmy_sim run all
/stevesarmy_sim run hold_enclosed_pocket
/stevesarmy_sim list
```

Reports land in `run/sim_reports/<scenario>_<timestamp>.txt` (+ `.json`).
The txt report contains the outcome, every detector violation with its full
counter-chain evidence, a per-soldier segmented state timeline, and a
**cover-dock fidelity** summary line per soldier (final slot offset, anchor
velocity pulls, worst body rays seen while hiding/standing). The final
autorun log line `SIM COMPLETE` is the CI-style grep anchor; per-scenario
`PASS`/`FAIL` is in the report and the `[Sim]` log lines.

### Watching the simulation

Every scenario also produces `<scenario>_<timestamp>_sim.html` — a
self-contained top-down animation of the run (double-click to open in any
browser; no server or codec needed). Soldiers are colored by cover state with a
white ring when pinned and a red arc showing suppression level; dashed white
lines are live line-of-sight, cyan lines point at the pursued cover (marker
colored by cover type), tan blocks are half cover and pale steel blocks are
full cover, yellow lines are shots fired with an impact dot at the aim point,
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

## Scenarios (v1 — cover/suppression strand)

| Scenario | What it reproduces |
|---|---|
| `suppressed_follow_escape` | FOLLOW squad pinned under fire; escape + gate reopening |
| `hold_enclosed_pocket` | HOLD in a one-exit pocket; fail-and-escalate without stranding |
| `persistent_ping_relocation` | Continuous suppression from one origin; relocation dead-ends |
| `mg_anchor_freshness` | MG centroid/anchor staleness after the shooter moves |
| `mg_rifleman_seek_contrast` | Same fire on an MG and two riflemen: both must reach cover (the live MG seek-strand bug) |
| `pair_suppression_lanes` | Two suppressors; dispersion vs stacking |
| `open_field_baseline` | No suppression; detector false-positive calibration |
| `dock_arrival_roles` | Rifleman + MG + AT pulse into cover; each must dock into the exact slot block with the anchor velocity engaged |
| `dock_arrival_suppressed` | Rifleman + MG arriving under sustained suppression; anchor finishes the docking once pressure ends |
| `dock_reload_hold` | Armed: scripted reload pins the slot through shoves; anchor re-docks after the reload |
| `dock_knockback_recovery` | Support role (peek disabled) shoved off its slot while pinned; anchor must recover |
| `dock_suppressed_drift_recovery` | Rifleman shoved off its slot mid-pin; the suppressed-state anchor re-docks |
| `dock_cqb_hold_fidelity` | KNOWN FINDING (expected FAIL): a point-blank enemy latches the CQB hold during the approach; anchor starves, soldier locks off-slot and stays fully visible when the enemy flanks past the block |
| `dock_peek_return` | Armed peek cycles; the return move must land back in the exact slot |

Every scenario except `open_field_baseline` (intentionally bare) places real
cover: waist-high stone-brick rows (half cover, peekable) and 2-high stacks
(full cover) between the actors and the shooter, spaced so each soldier has
reachable options and the pair/relocation scenarios have distinct candidates.

## Detectors (all run in every scenario)

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

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
counter-chain evidence, and a per-soldier segmented state timeline. The final
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
  tracked and removed at cleanup. Fixtures stack 20Y apart (build limit
  y=319; placement above it is rejected, so `SimTerrain.block()` fails loudly
  if a fixture would cross it).
- `sim/detector/StandardDetectors` — ten pure-over-timeline checks. All
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
| `mg_rifleman_seek_contrast` | Live-fire MG vs riflemen fight (`.armed()`, two enemies): both sides reach cover, organic suppression both ways, peek-and-fire cycles |
| `pair_suppression_lanes` | Two suppressors; dispersion vs stacking |
| `open_field_baseline` | No suppression; detector false-positive calibration |

Every scenario except `open_field_baseline` (intentionally bare) places real
cover: waist-high stone-brick rows (half cover, peekable) and 2-high stacks
(full cover) between the actors and the shooter, spaced so each soldier has
reachable options and the pair/relocation scenarios have distinct candidates.

## Detectors (all run in every scenario)

StuckSeek, SeekFailLoop, CoverOscillation, PeekThrash (incl. aborted
peek cycles), SuppressionStarve, FollowEscapeBlocked, PathFailStorm,
PositionDeadlock, FireStarvation (armed runs only), ReservationDrift
(+ post-run ReservationLeak check).

## Known findings (2026-10-06, armed sim + MG seek-strand fix)

Status after the MG seek-strand fix, the fixture build-limit repair, and the
armed-sim work (SimGunHandler + telemetry fixes). Suite state: 6/7 PASS; the
one FAIL is a pre-existing intermittent.

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

Remaining strands (all pre-existing):

1. **Post-suppression relocation churn** (`suppressed_follow_escape`,
   intermittent, fails suite / passes solo) — under sustained pressure a
   soldier may hop through 3-4 relocation targets inside a 200t window
   (CoverOscillation fires); ends safely in cover.
2. **Peek stalls under sustained suppression** (now organically visible in the
   armed contrast run) — a suppressed soldier sits in MOVING_TO_PEEK for
   ~110t (left, t85-195) instead of either committing to the peek or folding
   back to HIDING; the peek-start decision and the suppression gate disagree.
3. **Reservation drift (WARN)** — occupied cover unreserved 60-80t after the
   fight ends; claims outlive released reservations until the goal cycle
   catches up.

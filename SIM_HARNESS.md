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
that tick (including shots fired and magazine). The same data ships
machine-readable as `<scenario>_<timestamp>_recording.json`. You can also
watch a run live: `/stevesarmy_sim run <name>` from the dev client and fly up
to the fixture (platform at Y=160+, west edge of the world origin).

### Guns & ammo in the sim

**Sim friendlies have no gun and no ammo today.** Two reasons stack: TaCZ is
compile-only in dev (its SRG-refmapped mixins FML-hard-fail against userdev
classes), and the mod's no-TaCZ fallback (`GunIntegration.FallbackGunHandler`)
is a deliberate stub — `hasGun()=false`, every `shoot()` returns `NOT_GUN` —
so nobody fires even though soldiers exist and target. Suppression pressure is
therefore deterministic `SuppressionTracker` injection, reports print
`guns: ABSENT`, and firing-chain detectors/assertions skip themselves.

Every successful round fired through `GunIntegration.shoot*` (any shooter,
TaCZ or a future real fallback) is tapped by `GunIntegration.shotListener`,
which the harness installs per scenario: shots appear as yellow tracers in
the animation frame they were fired, per-soldier `shots fired` counters and
`ammo / magazine` show in the inspect panel, and `SimSample.shotsFired` is
available to detectors. Armed runs (TaCZ on the runtime classpath, e.g. the
deployed game) get all of this automatically.

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
  tracked and removed at cleanup.
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

## Known findings (2026-10-06, post cover-fixture pass)

Findings after the scenarios gained real cover blocks (the first pass ran four
scenarios on a bare slab, which conflated "no cover found" with "cover machine
dead"):

1. **Escape chain dead while pinned** — in `suppressed_follow_escape` both
   soldiers hold cover through a 370t pin with `queuedModesSeen` empty and
   `relocType=NONE`; reorganization (REPOSITIONING/SEEKING, bob) only starts
   ~70t after the injection stops at t400. The escape chain does engage from
   the open (`persistent_ping_relocation` PASSes: cover at t35, reposition at
   t40-55 under fire) — it is reaching-cover-then-staying that never escalates.
2. **Suppression gates cover acquisition** (`pair_suppression_lanes`) — both
   soldiers spend 640t pinned in the open 7 blocks from valid cover. The
   suppression gate blocks starting a search; the brief decay dips (5-25t)
   where `SEEKING_COVER` blips through are too short for the async search to
   return before the next burst re-pins and cancels it.
3. **MG seek dead-wait** (`mg_anchor_freshness`) — MG held SEEKING_COVER 695t
   with `searchPending=false`, `targetCover=null`, `failedSearches=0` — the
   seek state is held with no pending search, no result, and no failure
   counted; nothing re-issues or escalates. (Pre-fixture pass the search
   stayed `pending=true` forever; now it resolves to nothing and the state
   still doesn't move.)
4. **Cover churn under fire** — bob claimed 4 distinct covers within 200t
   (`CoverOscillation`) once suppression dipped; alice/bob also show
   MOVING_TO_PEEK → RETURNING cycles that never reach EXPOSED at sup ~1.0.
5. **Reservation drift/leak (WARN)** — occupied cover unreserved 60t+; one
   orphaned reservation at cleanup.
6. **Detection candidate** — baseline scout does not acquire an enemy 30
   blocks due south (its facing) within 20s; zero detector violations (the
   calibration goal holds), but the targeting assertion fails. Needs a
   TargetAcquisition/FoF look before recalibrating.

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
| `mg_rifleman_seek_contrast` | Same fire on an MG and two riflemen: both must reach cover (the live MG seek-strand bug) |
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

## Known findings (2026-10-06, MG seek-strand fix)

Status after the MG seek-strand fix (reserve fallback + seek escalation) and
the fixture build-limit repair. Five full-suite runs; the two remaining FAILs
are separate, pre-existing strands.

**Fixed this pass — the "MG stuck in SEEKING_COVER, never pathing" bug.**
Four stacked causes, all visible once searches were instrumented (`ls`
telemetry: mode/phase/result/center/streak per search):

1. **Reservation spin** — the base search reserved only the single best
   candidate; if a squadmate held it, the whole search returned
   `NO_COVER_FOUND("reservation")` and reservation failures never blacklist,
   so the same taken cover was re-selected forever (streak 108+). Fix: ordered
   reserve pools (selected best → soldier-centered list → protection-filtered
   list from the intended search center), bounded to 4 movement attempts per
   search — the same pattern the relocation branch always had.
2. **Anchor pointed away from cover** — the MG rear anchor (behind the squad
   line) sat 14+ blocks from every cover, and radius 12 from it saw nothing.
   Fix: seek escalation — after 120t without a cover claim (wall clock, so
   prone-walk detours can't hide the stall), the search widens to the FOLLOW
   radius and re-centers on the owner when no anchor exists.
3. **`seekingTicks` is a blind clock** — it freezes while the goal walks to a
   prone spot (`proneFiringDestination` branch returns early), so any
   seekingTicks-based stall detector under-reports by 3-4x. Escalation now
   arms on ticks-since-last-cover-claim instead.
4. **Prone deployment bypassed the cover gate** — `findAndMoveToCover` passed
   an empty cover list to the prone selector whenever the soldier had no
   attack target, making its "use physical cover instead" check vacuous; the
   soldier then walked to open-ground prone firing positions while under
   fire (the visible westward wander, exposed, ~200t). Fix: the scored cover
   list is always passed to the gate, and the selector refuses prone
   deployment while suppressed. Post-fix the MG claims cover on its first
   search (IN_COVER by t20, same as the riflemen).

Also repaired along the way: fixtures for scenarios 5+ ran at Y≥320 — the
world silently rejected every block above the build limit (y=319), so those
scenarios fought on empty air (this fabricated the old "pinned in the open
7 blocks from cover" pair finding). Y spacing is now 20Y and out-of-range
placement throws.

Remaining strands (all pre-existing, not the MG seek bug):

1. **Detection gap** (`open_field_baseline`) — the scout sees the enemy
   (peeks EXPOSED with clear LoS) but never acquires the attack target within
   400t; targeting assertion fails. Needs a TargetAcquisition/FoF look.
2. **Post-suppression relocation churn** (`suppressed_follow_escape`,
   intermittent) — when sustained pressure ends, a soldier may hop through
   3-4 relocation targets in ~50t before settling. Ends safely in cover.
3. **Peek aborts under sustained suppression** (intermittent) — a soldier in
   full cover starts a peek, suppression re-pins mid-cycle, and it folds back
   without exposing (4+ aborted cycles tripped PeekThrash once). The
   peek-start decision and the suppression gate disagree.
4. **Reservation drift (WARN)** — occupied cover unreserved 60-80t; one
   orphaned reservation at cleanup in some runs.

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
lines are live line-of-sight, cyan lines point at the pursued cover, red
squares are enemies. Play at 0.5–10x, scrub, and click a soldier for its full
state snapshot at that tick. The same data ships machine-readable as
`<scenario>_<timestamp>_recording.json`. You can also watch a run live:
`/stevesarmy_sim run <name>` from the dev client and fly up to the fixture
(platform at Y=160+, west edge of the world origin).

Dev runs are gun-less by design (TaCZ is compile-only in dev — its SRG-refmapped
mixins cannot load against userdev classes), so suppression pressure comes from
deterministic `SuppressionTracker` injection and firing-chain assertions skip
themselves when no gun mod is present. Armed runs via the command on a TaCZ
instance exercise the real firing chain.

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

## Detectors (all run in every scenario)

StuckSeek, SeekFailLoop, CoverOscillation, PeekThrash (incl. aborted
peek cycles), SuppressionStarve, FollowEscapeBlocked, PathFailStorm,
PositionDeadlock, FireStarvation (armed runs only), ReservationDrift
(+ post-run ReservationLeak check).

## Known first-run findings (2026-10-06)

The harness's first pass reproduced live strands programmatically:

1. **Dead suppression-escape chain** — soldiers pinned 370–640t never queue
   `SUPPRESSION_REPOSITION` / `CONTINUOUS_SUPPRESSION` / `SUPPRESSION_POSITION`
   (`relocType=NONE`, `queuedModesSeen` empty for the whole pin). The
   continuous-suppression reposition trigger and the relocation chain do not
   engage from `SUPPRESSED_IN_COVER` in these conditions.
2. **MG async-search dead-wait** (`mg_anchor_freshness`) — MG held
   SEEKING_COVER 695t: `searchPending=true`, `targetCover=null`,
   `failedSearches=0`, `blacklistSize=0`. Because the search never *fails*,
   every escalation counter stays at zero and no watchdog re-decides.
3. **Open-field pinned paralysis** (`pair_suppression_lanes`) — no cover within
   search radius → NO_COVER + PINNED for the whole run with only periodic
   failed seeks; soldiers stand exposed under fire without relocating away.
4. **Peek-while-pinned churn** — repeated `MOVING_TO_PEEK → RETURNING_TO_COVER`
   cycles that never reach EXPOSED while suppression is ~1.0.
5. **Reservation drift/leak (WARN)** — occupied cover unreserved 65t+; one
   orphaned reservation at cleanup.
6. **Detection candidate** — baseline scout did not acquire an enemy 24 blocks
   away within 20s (facing-dependent?); needs a look before recalibrating.

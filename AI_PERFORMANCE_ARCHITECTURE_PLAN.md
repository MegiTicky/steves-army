# AI Performance Architecture Plan

## Purpose

This document defines the optimization target for Steve's Army server AI and a
phased path toward it. It is an architecture and implementation plan only. It
does not authorize moving existing Minecraft world access onto worker threads.

The primary objective is to reduce server-thread MSPT and worst-tick spikes as
soldier count increases while preserving tactical behavior. Reducing total CPU
time is desirable, but reducing work on the server thread and preventing bursty
work are the critical outcomes.

## Executive Decision

The project should aim for a **bounded, snapshot-based tactical planning
pipeline**, but multithreading should not be the first optimization step.

The recommended order is:

1. Establish repeatable profiles and stage-level metrics.
2. Eliminate duplicate work and unbounded retry patterns on the server thread.
3. Centralize same-tick perception and tactical request scheduling.
4. Separate pure tactical evaluation from live Minecraft access while still
   running it synchronously.
5. Validate snapshot equivalence in shadow mode.
6. Offload only immutable geometric evaluation and ranking to a bounded worker
   pool.
7. Keep live validation, reservation, pathfinding, navigation, and state changes
   on the server thread.

The first production multithreading target should be **ray-heavy cover and
firing-position evaluation over immutable terrain and threat snapshots**. It
should not be detection, pathfinding, navigation, or the current `CoverFinder`
method bodies.

## Evidence And Confidence

### Profile Evidence Available

The latest reported profiler priorities were:

| Path | Approximate sampled cost | Confidence |
|---|---:|---|
| `DetectionSystem.tick()` | 11.9% | Measured in the reported profile |
| `CoverTacticalGoal.tickInCover()` | 9.65% | Measured in the reported profile |
| `shouldRepositionForFlank()` | 7.04% | Measured in the reported profile |

The cover-search burst problem was independently observed and addressed by the
bounded `CoverSearchScheduler` in commit `e729104`. That scheduler limits
routine cover searches to two per server tick and staggers routine requests over
six ticks.

Exact call counts, scenario parameters, and the complete Spark call tree are not
stored with this document. Percentage values therefore identify priorities, not
absolute acceptance baselines. Phase 0 must create reproducible baselines before
further behavior or threading changes.

### Code-Derived Findings

The following findings come from the current source and should be verified with
metrics before their expected benefit is treated as measured:

| Finding | Scaling or spike risk | Source |
|---|---|---|
| Detection refreshes every combat-goal tick | Approximately soldiers x candidates x LOS/exposure work | `SoldierCombatGoal.shouldRefreshDetection()` always returns `true` |
| Potential-target lists are recomputed because target caching is disabled | Multiple entity queries per soldier and possible repeated calls in one tick | `SoldierCombatGoal.getPotentialTargets()`, `StevesArmyConfig` |
| A potential-target refresh performs separate queries for monsters, target entities, players, and soldiers | Repeated broad-phase world queries | `SoldierCombatGoal.computePotentialTargets()` |
| Detection performs an entity-pair visibility trace for each candidate, then up to eight exposure rays for candidates in a detection arc | Ray count grows with soldiers x candidates | `DetectionSystem.tick()`, `ExposureCalculator.calculateExposureUncached()` |
| Every smoke-aware visibility trace may query smoke entities along the ray | Potential entity-query multiplication when CBC smoke is available | `VisibilityRay.findSmokeIntersection()` |
| Failed flank evaluation can run a full cover search again on the next in-cover tick | Persistent cost when exposed but no better cover exists | `CoverTacticalGoal.tickInCover()`, `shouldRepositionForFlank()` |
| The five-second flank cooldown starts only after a successful reposition | Failed searches are not rate-limited by the existing cooldown | `CoverTacticalGoal.lastFlankRepositionTime` |
| Some cover-selection branches repeat candidate discovery or scoring | More block scans and rays than one tactical request requires | `CoverTacticalGoal.findAndMoveToCover()` |
| Attack selection can run a forward search and then a full local search | Two intentionally different contexts, but both are expensive | `CoverTacticalGoal.findAndMoveToCover()` |
| Non-attack fallback can call `findBestCover()` after an already-scored pass | Duplicate search when the first pass produces no usable choice | `CoverTacticalGoal.findAndMoveToCover()` |
| Path reachability can be recomputed for the same candidate during selection and movement | Duplicate path requests in one decision | `isExactCoverPathReachable()`, `moveToCover()` |
| Cover scoring repeatedly performs physical protection and firing-lane rays per candidate | Ray-heavy candidate evaluation | `CoverFinder`, `CoverQualityEvaluator` |
| Incoming projectile suppression queries nearby soldiers per projectile segment | Projectile count x local soldier query cost | `IncomingFireHandler.tick()` |

## Current Architecture Assessment

### Existing Strengths

- `CoverSearchScheduler` already provides bounded, coalesced server-thread cover
  work and stale command-generation rejection.
- `PerformanceMetrics` has counters for perception, cover search, path requests,
  scheduler pressure, and planned asynchronous cover work.
- Entity-pair LOS is reused within the same game tick when exact endpoints are
  unchanged in `TargetAcquisition`.
- `CoverTacticalGoal` already tracks attack and relocation generations needed to
  reject stale planning requests.
- `FiringPositionFinder` contains an immutable snapshot model, a pure snapshot
  evaluator, and a server-thread finalizer. This is useful design precedent.
- Cover reservation, path creation, and movement are already centralized enough
  to remain authoritative on the server thread.

### Current Limitations

- `CoverFinder` mixes world discovery, voxel geometry, live entity access,
  tactical scoring, mutable `CoverPoint` updates, reservation checks, and cache
  access. It is not a worker-safe component.
- `SquadCoverContext` is not a worker DTO. Its lists are not guaranteed to be
  deeply copied and it contains the mutable `SquadCoverPeekabilityCache`.
- `ThreatAwareness.ThreatInfo` still contains a `LivingEntity` reference.
- `DetectionSystem` owns mutable per-target progression state and consumes the
  level RNG while reading live pose, movement, brightness, and visibility.
- `VisibilityRay` reads live block states, collision shapes, block tags, and
  smoke entities.
- `PerformanceMetrics` contains asynchronous cover counters, but no executor or
  runtime caller currently uses `FiringPositionFinder.captureAsyncSnapshot()`,
  `evaluateAsyncSnapshot()`, or `finalizeAsyncEvaluation()`.
- The existing machine-gunner terrain snapshot does not include dynamic smoke.
  It is not yet an exact replacement for normal `VisibilityRay` semantics.
- Capturing the current machine-gunner snapshot still performs candidate
  discovery, protection evaluation, and voxel extraction on the server thread.
  Its net MSPT benefit is unproven.

### Documentation Drift

Source code must remain authoritative during implementation:

- `PERFORMANCE_OPTIMIZATION_PLAN.md` says Stage 1 caches are implemented, but
  current configuration disables target-query, positional visibility,
  aim-point, and exposure caching. Entity-pair LOS still has a one-tick cache.
- `ARCHITECTURE.md` describes an older `SquadCoverContext` shape and older cover
  evaluator details.
- Existing async metrics and snapshot records are scaffolding, not evidence that
  runtime cover evaluation is asynchronous.

Updating general architecture documentation belongs after implementation, when
the new behavior is stable.

## Performance Model

The important costs have different scaling behavior and should not be optimized
as one undifferentiated AI tick.

| Work class | Approximate scaling | Main cost type | Preferred control |
|---|---|---|---|
| Potential-target discovery | soldiers x nearby entity queries | Entity broad phase and allocation | Same-tick spatial reuse |
| Detection LOS | soldiers x candidates | Block traversal and smoke queries | Cull, reuse, and budget |
| Exposure | visible candidates x target sample points | Multiple visibility rays | Same-tick memoization and selective evaluation |
| Cover discovery | requests x search volume | Block and voxel queries | Coalescing, reuse, geometry snapshots |
| Cover scoring | candidates x threats x rays | Ray traversal and scoring | Staged evaluation and worker ranking |
| Flank replacement | exposed soldiers x retry frequency | Full cover searches | Event/cooldown gating and scheduler |
| Path validation | selected candidates x retries | Vanilla pathfinding | Top-N only and request-local reuse |
| Projectile suppression | active projectiles x nearby soldiers | Entity queries and segment math | Shared spatial index if measured |

Optimization should reduce each multiplier before introducing more CPU cores.

## Target Architecture

### Overview

```text
Server tick
    |
    +-- Build or lazily populate same-tick perception data
    |
    +-- AI goals make lightweight decisions and submit tactical requests
    |
    +-- Main-thread snapshot capture and request coalescing
    |
    +-- Bounded worker pool evaluates immutable snapshots
    |
    +-- Completed results enter a server-thread result inbox
    |
    +-- Validate live state, exact top candidates, reservations, and paths
    |
    +-- Apply navigation and AI state changes
```

The architecture should have three explicit boundaries.

### 1. Perception And Snapshot Capture

This boundary runs on the server thread. It may read Minecraft objects but must
copy only immutable values into a request.

Snapshot data may include:

- Server instance and dimension identity.
- Source game tick and request sequence.
- Soldier UUID, block position, eye position, dimensions, mode, role, and
  tactical revision.
- Attack, relocation, cover-state, threat, and squad generation values.
- Search center, radius, blacklist positions, and current cover.
- Threat UUIDs, copied positions, target sample points, weights, and freshness.
- Copied occupied/reserved positions and squad firing contacts.
- Terrain cells represented by primitive flags and immutable collision/outline
  boxes.
- Copied smoke AABBs when smoke participates in the requested visibility rules.
- A deterministic random seed or pre-sampled random values when equivalence
  requires randomness.

Snapshot capture must be timed independently. Offloading is a failure if copying
geometry costs nearly as much server-thread time as the original calculation.

### 2. Pure Tactical Evaluation

This boundary may run on a worker. It must not receive `Level`, `Entity`,
`LivingEntity`, `PathNavigation`, mutable squad objects, mutable `CoverPoint`, or
Minecraft RNG references.

Worker-safe operations include:

- Candidate discovery against copied terrain cells.
- DDA ray traversal against copied collision and outline boxes.
- Concealment and firing-lane calculations against copied geometry.
- Distance, arc, objective-progress, dispersion, flank, and protection math.
- Deterministic scoring, ranking, and bounded top-K selection.
- Producing immutable candidate records with diagnostics.

Worker results are advisory. They do not reserve cover, change AI state, or
start movement.

### 3. Validation And Application

This boundary runs on the server thread. It resolves the soldier by UUID and
rejects stale or invalid results before applying anything.

Validation must cover:

- Same server instance and dimension.
- Soldier alive, loaded, and still eligible for the request.
- Matching request sequence and tactical generation.
- Matching attack command or relocation generation.
- Compatible cover state and search mode.
- Soldier movement within the request's allowed tolerance.
- Threat and squad revisions within the request's validity policy.
- Geometry revision or exact live geometry validation for shortlisted results.
- Current reservation availability.
- Fresh exact LOS where gameplay correctness requires it.
- Fresh path creation and endpoint validation.

Only this phase may reserve a cover, mutate `CoverBehaviorManager`, update a
goal state, or call navigation.

## Thread-Safety Classification

| Operation | Main-thread capture | Worker-safe | Main-thread apply |
|---|:---:|:---:|:---:|
| Read `Level`, chunks, block states, tags, or voxel shapes | Yes | No | Yes |
| Query or resolve entities | Yes | No | Yes |
| Read entity pose, movement, light, team, or alive state | Yes | No | Yes |
| Copy primitive positions, dimensions, weights, and AABBs | Yes | N/A | N/A |
| Trace rays over copied AABBs | No | Yes | Optional exact check |
| Score and sort immutable candidates | No | Yes | No |
| Read or update `DetectionSystem.DetectionState` | Yes | No | Yes |
| Read or update `ThreatAwareness` | Yes | No | Yes |
| Read mutable squad or peekability caches | Yes | No | Yes |
| Check or mutate reservations | Yes | No | Yes |
| Create or inspect vanilla `Path` objects | Yes | No | Yes |
| Start navigation or change entity state | No | No | Yes |
| Write debug entity data or send packets | No | No | Yes |

Thread-safe collections such as `ConcurrentHashMap` do not make the surrounding
Minecraft operation semantically safe off-thread. In particular,
`CoverReservationManager` being backed by concurrent collections is not a reason
to reserve or select cover from a worker.

## Primary Optimization Goals

### Goal A: Stable Server-Thread Budgets

No command burst or persistent failure state should execute unbounded expensive
work in one tick.

Required properties:

- Expensive requests have a global per-tick admission budget.
- Requests are coalesced per soldier and request type.
- Priority has bounded aging so routine requests cannot starve.
- Failed evaluations receive a retry policy rather than immediately repeating.
- Queue depth, wait age, coalescing, cancellation, and starvation are observable.

`CoverSearchScheduler` is the initial implementation of this policy, not the
final generalized coordinator.

### Goal B: One Expensive Answer Per Tactical Question

A single decision should not independently rediscover the same blocks, retrace
the same ray, rebuild the same squad context, or recreate the same path unless
its inputs differ.

Required optimizations:

- Request-local candidate and ray memoization.
- Reuse one scored candidate set through selection, blacklist fallback, debug
  output, defensive-prone comparison, and final choice.
- Preserve separate searches when center, radius, reservation policy, or scoring
  context is genuinely different.
- Carry a validated path from top-candidate selection into movement when it is
  still current, instead of creating it twice.
- Keep debugging work out of normal runtime paths unless its feature is enabled.

### Goal C: Same-Tick Perception Reuse

Nearby soldiers often ask related world questions in the same tick. Reuse must
be exact for that tick before any multi-tick staleness is introduced.

The desired perception frame should provide:

- A per-level, per-tick broad-phase entity view or spatial cells populated on
  demand.
- Observer-specific filtering after broad-phase reuse.
- Exact query-bound checks so cell reuse cannot omit targets near boundaries.
- Entity-pair visibility reuse for identical endpoints.
- Optional ray-result reuse keyed by endpoints, smoke policy, ignored blocks,
  dimension, and geometry revision.
- A per-tick smoke AABB view so each ray does not independently query entities.

The disabled `CombatTargetQueryCache` should be treated as a prototype to audit,
not simply enabled. Its broad query geometry and TTL behavior must be validated
against exact target acquisition semantics.

### Goal D: Pure, Versioned Tactical Planning

Cover and firing-position selection should become an input-to-output function
over immutable values. This separation is valuable even before workers are used:

- It enables deterministic tests.
- It exposes snapshot cost separately from evaluation cost.
- It prevents accidental world access in worker code.
- It makes stale-result handling explicit.
- It supports synchronous fallback using the same evaluator.

### Goal E: Bounded Parallelism, Not Maximum Parallelism

The worker pool exists to move suitable CPU work off the server thread, not to
consume all available cores.

Initial production policy:

- Dedicated executor, never the common fork-join pool.
- One worker by default for the pilot; test two only after measurements.
- Bounded queue with latest-request-wins coalescing per soldier.
- No blocking wait from the server thread.
- Explicit shutdown and cancellation on server stop.
- Exceptions recorded and contained; synchronous behavior remains available.
- Config flag off by default until shadow and gameplay validation pass.

## Optimization Priority

### Priority 1: Flank Search Retry Control

`tickInCover()` can call `shouldRepositionForFlank()` every tick. If the soldier
is exposed and recovered but no better cover is found, the full search can repeat
without the existing successful-reposition cooldown ever starting.

Target behavior:

- Separate cheap flank exposure detection from expensive replacement search.
- Route replacement search through the bounded scheduler.
- Add a failed-search retry time or an input fingerprint that suppresses repeats
  until threat direction, threat set, current cover, or geometry changes.
- Preserve an immediate path for a newly detected close emergency flank only if
  profiling and gameplay requirements justify it.

This is the highest-value near-term optimization because it directly corresponds
to a measured hot path and removes repeat work rather than merely relocating it.

### Priority 2: Cover Search Pass Reuse

Refactor one tactical request to produce one reusable evaluation result containing
ranked candidates and request-local diagnostics.

The result should be consumed by:

- Base cover selection.
- Role-specific selection.
- Blacklist and reservation fallback.
- Prone defensive-position comparison.
- Debug top-cover output.
- Top-N main-thread path validation.

Attack forward and local fallback searches may remain separate because their
centers and selection semantics differ. They should share immutable geometry
where their regions overlap, not silently merge their scoring behavior.

### Priority 3: Same-Tick Target Query Consolidation

At minimum, repeated calls by one `SoldierCombatGoal` in the same game tick
should not rerun potential-target queries. The broader target is one safe
same-tick spatial view shared by nearby observers.

This phase must preserve:

- Target category ordering.
- Team and owner filtering.
- Creative and spectator exclusions.
- Exact configured range checks.
- Newly joined or removed entity semantics at the chosen tick boundary.

Do not introduce a multi-tick target TTL in this phase.

### Priority 4: Visibility And Exposure Work Reduction

Measure block traversal and smoke lookup separately, then optimize the dominant
part.

Candidate changes, in order of risk:

1. Reuse identical same-tick rays.
2. Reuse a same-tick smoke entity snapshot.
3. Avoid duplicate target-point generation and allocations.
4. Perform cheap distance and arc classification before expensive exposure rays.
5. Add a fast path for ordinary full-block geometry while retaining the custom
   path for partial shapes, concealment, transparent blocks, ignored cover, and
   smoke.
6. Consider multi-tick caching only as an opt-in behavior change.

Detection currently updates `wasInLOSLastCheck` even for candidates outside an
active detection arc. Any early LOS skip can alter the first-contact bonus later.
That state transition must be preserved or deliberately redesigned and tested.

### Priority 5: Path Request Reuse And Top-N Validation

Pathfinding stays on the server thread. Reduce its call count by validating only
the highest-ranked candidates and retaining a still-valid path through immediate
movement application.

Do not cache paths across meaningful entity movement, geometry changes, pose
changes, or navigation capability changes.

### Priority 6: Snapshot-Based Worker Evaluation

After priorities 1 through 5, offload only the remaining measured ray and scoring
cost. If synchronous work reduction removes the bottleneck, do not add worker
complexity merely because snapshot code exists.

### Deferred: Detection Multithreading

Detection should remain server-thread-based during the first worker rollout.

Reasons:

- Positive progression and decay are per-tick state transitions.
- First-contact behavior depends on previous LOS state.
- Detection consumes random values in update order.
- Brightness, pose, movement, and visibility are live world inputs.
- Results immediately feed threat awareness, squad reports, and player-facing
  contact events.

Detection can later use workers only after it is split into main-thread input
capture, pure per-target contribution calculation, and ordered main-thread state
application. Synchronous culling and same-tick reuse are likely lower-risk wins.

## Implementation Phases

### Phase 0: Reproducible Baseline And Contracts

Deliverables:

- Define controlled scenarios at 16, 32, and 64 soldiers where practical.
- Record idle, visible-target, partial-cover, failed-flank, mass ATTACK, GO_TO,
  suppression, smoke, and projectile-heavy scenarios.
- Capture Spark profiles, average MSPT, p95/p99 MSPT, worst tick, and TPS.
- Record `PerformanceMetrics` for the same timed window.
- Add stage timers needed to distinguish entity query, LOS, exposure, smoke
  lookup, cover discovery, cover rays/scoring, snapshot, path, and apply cost.
- Write behavior contracts for detection timing, cover choice, attack progress,
  suppression recovery, flank response, and reservation ownership.

Baseline records:

- Capture 1 (uninstrumented, 50 riflemen vs 50 enemies, flat world with cover):
  Spark profile `https://spark.lucko.me/aJFVfeQvND`.
- Capture 2 (instrumented replacement, 50 riflemen vs 50 enemies, flat world with
  cover): Spark profile `https://spark.lucko.me/82P7avB4qE`. The Test instance log
  shows the profiler running from `13:21:50` through `13:23:51`, uploading at
  `13:23:54`, with the final metrics report printed at `13:23:52`.
- Capture 2 log evidence: `C:\Users\lauya\curseforge\minecraft\Instances\Test\logs\latest.log`.
  Final cumulative report line: `13:23:52`, log line 19537. The report recorded:
  visibility cache `3,193,114` hits / `4,471,291` misses; `20,251,421` visibility
  ray requests and actual traces; `269,695` exposure-cache misses and calculations;
  `121,079` detection ticks with `4,380,763` candidates; `385,625` target refreshes
  with `14,070,596` candidates; `5,588` cover searches; `261,907` candidates
  discovered, `237,461` evaluated, and `237,053` scored; `115,878` cover ticks;
  `1,825` cover path requests, `7` retries, and `1,032` failures; `355` cooldown
  skips; and `20,174,856` smoke lookups with zero entities tested and zero hits.
- Capture 2 scheduler counters: `2,595` cover requests queued, `2,624` executed,
  `29,768` deferred, `17,118` coalesced, `13` cancelled, and `2` stale. Role
  counters were rifleman-only: `121,123` combat ticks, `121,079` detection ticks,
  `115,878` cover ticks, `3,229` cover searches, `1,825` path requests, `7` path
  retries, and `1,032` path failures. Async cover counters remained zero.
- Capture 2 caveats: the run produced repeated `Can't keep up!` warnings and
  repeated oversized collision-box errors (`AABB[-Infinity, -Infinity, -Infinity]`
  to `[Infinity, Infinity, Infinity]`). These remain test-environment caveats and
  are not attributed to Steve's Army without further isolation. The log line is
  escaped into one long chat line, so stage timing and per-tick percentile fields
  should be archived separately from the raw in-game report if those values are
  needed for the Phase 0 exit gate.
- Spark access: the short URL is the interactive viewer. The raw binary
  profile (protobuf `spark.SamplerData`) is downloadable from
  `https://bytebin.lucko.me/<code>` (same code as the viewer URL). Use that
  endpoint to archive profiles without relying on the interactive page.

Exit gate:

- Baselines are repeatable enough to compare two builds.
- Every reported hotspot can be tied to call counts and elapsed time.
- Debug logging is disabled during performance captures.

### Phase 1: Behavior-Preserving Server-Thread Work Reduction

Implementation status:

- Implemented same-tick potential-target reuse in `SoldierCombatGoal`; the
  cross-tick target cache remains disabled.
- Implemented request-local cover discovery reuse in `CoverFinder` for identical
  center, radius, and threat inputs within one finder request.
- Implemented request-local exact-path reuse in `CoverTacticalGoal`, keyed by the
  soldier's current block and tick. Only paths already validated as reachable are
  reused for movement.
- Added Phase 1 reuse counters to `PerformanceMetrics`: same-tick target hits,
  cover discovery hits, and cover path reuse hits.
- Built and deployed the instrumented Phase 1 jar to the Test instance. The
  deployed artifact matched the build SHA-256:
  `AE08BD67BAA4EA33258A5FDB245B709EC60B76F9EBAB35B61DBAF90824E02C14`.
- Latest diagnostic capture used Spark profile
  `https://spark.lucko.me/a4HHVT8Ny9`. The metrics report was printed at
  `13:42:06`, before Spark started at `13:42:14`; Spark stopped at `13:44:17`
  and uploaded at `13:44:20`. The report is therefore a reset-window diagnostic,
  not a strictly Spark-aligned comparison.
- Latest report recorded `11,696,000` visibility ray requests and actual traces,
  `127,192` exposure calculations, `61,149` detection ticks with `2,206,303`
  candidates, `88,191` target refreshes with `3,117,872` candidates, `3,438`
  cover searches, `168,571` candidates discovered, `160,958` evaluated, and
  `160,747` scored. It recorded `802` cover path requests, `2` retries, and
  `402` failures.
- Phase 1 reuse counters in the same report were `117,289` same-tick target
  hits, `268` cover discovery hits, and `231` path hits. Cover scheduling recorded
  `1,433` queued, `1,419` executed, `11,352` deferred, `8,061` coalesced,
  `50` cancelled, and `4` stale requests. Async cover work remained zero.
- The unrelated Valkyrien Skies collision-box errors and server-overload warnings
  in the shared log are excluded from this analysis. Spark and
  `PerformanceMetrics` are the authoritative sources for this capture. The
  metrics report still predates the Spark start by eight seconds, so its values
  are recorded as reset-window totals rather than strictly Spark-window totals.

Deliverables:

- Reuse potential-target results within one soldier tick.
- Remove duplicate candidate/scoring passes with identical inputs.
- Add request-local ray and candidate memoization.
- Reuse an immediate path between candidate validation and movement where safe.
- Audit unconditional attack-mode logging and debug data generation.
- Break visibility timing into block traversal and smoke entity lookup.

Constraints:

- No multi-tick cadence changes.
- No scoring weight or candidate-limit changes.
- No workers.
- No stale world answers across ticks.

Exit gate:

- Gameplay decisions match the baseline in deterministic scenarios.
- Total ray, target query, cover pass, and path request counts decrease.
- No regression in p95 or worst-tick MSPT.

### Phase 2: Retry Policies And Global Work Admission

Deliverables:

- Add a failed flank-search retry policy or input fingerprint.
- Integrate flank replacement work with bounded scheduling.
- Add priority aging and queue-age metrics if scheduler contention appears.
- Integrate emergency suppression searches only with an explicit pending state
  and measured emergency budget.
- Add cooldown/fingerprint handling for other repeated failed tactical searches.

This phase can change reaction timing and therefore requires explicit gameplay
testing. It should be opt-in until validated.

Exit gate:

- A failed flank scenario no longer performs a full search every tick.
- A newly changed flank input still triggers a timely reevaluation.
- No soldier remains indefinitely in selecting, seeking, or repositioning.
- Routine requests cannot starve under sustained emergency traffic.

### Phase 3: Same-Tick Perception Frame

Deliverables:

- Introduce or redesign a per-level same-tick target broad-phase view.
- Consolidate target categories without changing their final ordering.
- Add a same-tick smoke AABB view used by visibility traces.
- Centralize exact same-tick visibility memoization with explicit keys.
- Define tick-boundary invalidation and level-unload cleanup.

Constraints:

- No multi-tick target or visibility TTL.
- No worker access to the perception frame if it contains live entities.
- No whole-dimension entity scan; populate bounded spatial regions on demand.

Exit gate:

- Entity broad-phase query count scales by occupied spatial regions rather than
  directly by soldiers.
- Target sets and ordering match the baseline scenarios.
- Smoke behavior remains correct.

### Phase 4: Pure Tactical Evaluator, Still Synchronous

Deliverables:

- Define immutable `CoverSearchInput` and `CoverSearchResult`-style DTOs.
- Replace worker-ineligible fields with copied primitives and immutable records.
- Split geometry discovery, tactical scoring, and live application.
- Make candidate ordering deterministic, including tie-breakers.
- Run the pure evaluator synchronously through the existing scheduler.
- Compare its result with the legacy path in shadow mode.

The existing `FiringPositionFinder` snapshot evaluator should be audited and
reused where semantics match. It should not become a second independent executor
or snapshot format without a concrete need.

Exit gate:

- Pure evaluation has no reachable live-world references.
- Automated or diagnostic comparison shows equivalent candidate ordering within
  defined tolerances.
- Synchronous snapshot capture plus evaluation does not regress MSPT materially.

### Phase 5: Read-Only Async Shadow Pilot

Use the existing machine-gunner snapshot path as a plumbing and equivalence pilot
only after auditing its missing smoke semantics and capture cost.

Deliverables:

- Dedicated one-thread bounded executor.
- Server lifecycle startup, shutdown, cancellation, and exception handling.
- Request coalescing and result inbox processed on the server thread.
- Shadow results that never affect gameplay.
- Comparison between worker-ranked and live synchronous results.
- Metrics for queue wait, snapshot time, worker time, result age, stale results,
  validation rejects, and equivalence mismatches.

Exit gate:

- No off-thread Minecraft access is detected.
- Server stop and world unload leave no tasks or retained level references.
- Snapshot cost is substantially less than the synchronous work intended for
  removal.
- Result age and stale rate are low enough to justify a production pilot.
- Shadow ordering is acceptably equivalent, including concealment and smoke
  scenarios.

### Phase 6: Production Async Cover Pilot

Scope the first gameplay-affecting pilot to routine, non-emergency cover
selection. Keep attack fallback, suppression emergency routes, and detection on
their existing paths initially.

Deliverables:

- Opt-in server configuration, disabled by default.
- Latest-request-wins coalescing per soldier.
- Generation checks for command, cover, threat, squad, and geometry state.
- Main-thread exact validation of a bounded top-K worker shortlist.
- Main-thread reservation and path application.
- Synchronous fallback for queue saturation, worker failure, and unsupported
  snapshot semantics.
- A kill switch that does not require changing persisted data.

Exit gate:

- Controlled tests show lower p95/p99 MSPT and fewer cover-search spikes.
- Main-thread snapshot plus apply cost is materially below the previous
  synchronous evaluation cost.
- Stale and validation-reject rates remain within the limit established from
  Phase 5 data.
- Cover choice, movement completion, reservation ownership, and suppression
  behavior pass gameplay validation.

### Phase 7: Expand Only From New Profiles

Possible expansions:

- Attack forward and fallback cover evaluation.
- Flank replacement evaluation.
- Machine-gunner firing-position selection.
- Suppression route geometry classification, while pathfinding remains main
  thread.
- Pure detection contribution calculation.
- Projectile segment math over copied soldier positions if projectile
  suppression becomes measured as a major cost.

Each expansion needs its own snapshot inputs, validity policy, and behavior
tests. Do not treat the generic executor as permission to submit arbitrary AI
methods.

## Request And Result Model

Every asynchronous request should contain at least:

| Field | Purpose |
|---|---|
| Server epoch | Prevent a result from a stopped server applying to a new instance |
| Dimension key | Prevent cross-level application |
| Soldier UUID | Resolve the authoritative entity on apply |
| Request sequence | Latest request wins |
| Source tick | Measure age and reject expired work |
| Tactical revision | Reject goal/state changes |
| Command generation | Reject replaced ATTACK or GO_TO commands |
| Search mode | Preserve normal, reposition, attack, and emergency semantics |
| Geometry revision | Detect changed terrain snapshots |
| Threat revision | Detect materially changed threat inputs |
| Squad revision | Detect changed reservations or spacing context |
| Immutable snapshot | Worker input with no live references |

Results should contain only immutable candidate values, scores, and diagnostics.
They should not contain `Path`, `Entity`, `Level`, mutable `CoverPoint`, or a
reservation handle.

## Snapshot Strategy

Two strategies should be benchmarked during Phase 4 rather than selected by
assumption.

### Request-Local Snapshot

Copy only cells traversed by known candidate rays.

Advantages:

- Small worker input for narrow evaluations.
- Straightforward lifetime and no global invalidation cache.

Risks:

- Candidate discovery may remain on the server thread.
- Building ray-cell sets can itself be expensive.
- Overlapping soldiers duplicate snapshot work.

This resembles the current `FiringPositionFinder.TerrainSnapshot` approach.

### Versioned Regional Geometry Snapshot

Maintain immutable, section-sized geometry data that requests can reference.
Replace a section snapshot on relevant block changes.

Advantages:

- Candidate discovery can move to workers.
- Nearby soldiers share geometry.
- Repeated cover requests avoid voxel extraction.

Risks:

- More memory and lifecycle complexity.
- Modded block shapes and dynamic shape dependencies require careful capture.
- Block invalidation events must be complete.
- Initial population can create a new server-thread spike unless incremental.

Recommendation:

- Use request-local snapshots for the shadow pilot.
- Move to section snapshots only if metrics show request-local capture dominates
  and cover searches frequently overlap spatially.

## Determinism And Behavior Preservation

Parallel completion order must not change tactical behavior accidentally.

Requirements:

- Candidate tie-breaks include a stable final key such as packed block position.
- Request priority ordering does not depend on worker completion order.
- Worker code does not consume shared RNG.
- Random values required by a decision are seeded or sampled on the main thread.
- Result application is idempotent for a request sequence.
- A late result cannot clear or overwrite newer cover, attack, suppression, or
  relocation state.
- Detection results, if ever parallelized, apply in source-tick order or are
  discarded.

Snapshot latency is a behavior change. The allowed age must be explicit per
request type rather than hidden in a generic future callback.

## Metrics And Acceptance Criteria

### Required Metrics

Server-thread work:

- Target broad-phase queries and entities returned.
- Detection candidates, LOS rays, exposure calculations, and exposure rays.
- Smoke entity queries and smoke AABBs tested.
- Cover requests, discovery cells, candidates, quality rays, firing-lane rays,
  and scoring time.
- Flank checks, replacement searches, failed results, and retry suppressions.
- Path creations, candidate ranks path-tested, retries, and failures.
- Snapshot capture time and apply time.

Worker system:

- Queue depth, oldest request age, queue saturation, and coalescing.
- Worker count and utilization.
- Worker elapsed time and completed candidates.
- Cancelled, failed, stale, and validation-rejected results.
- Result age at apply.
- Shadow ranking mismatches and final-choice mismatches.

### Success Criteria

Numerical thresholds should be fixed after Phase 0, but every production phase
must satisfy these directional requirements:

- Lower p95 and p99 MSPT in the target scenario.
- Lower worst-tick cover spike during mass commands.
- No reduction in stable TPS under CPU saturation.
- Main-thread snapshot plus apply time lower than removed main-thread work.
- No growth without bound in queue depth, cache size, or retained snapshots.
- No indefinite soldier planning state.
- No off-thread world, entity, reservation, path, navigation, or packet access.
- No gameplay regression in detection, cover protection, smoke, suppression,
  attack progress, FOLLOW/GO_TO relocation, or reservation exclusivity.

Total CPU may increase slightly due to snapshots and validation, but that is
acceptable only when server-thread tail latency improves and spare cores exist.

## Test Matrix

| Scenario | Primary assertion |
|---|---|
| Idle soldiers, no targets | Minimal target and tactical work |
| Many soldiers, one visible target | Same-tick perception and LOS reuse scale |
| Many soldiers, many targets | Detection candidate growth remains bounded and behavior is preserved |
| Partial blocks and foliage | Snapshot rays match custom visibility semantics |
| CBC smoke between observer and target | Smoke blocks or conceals exactly as before |
| Mass ATTACK command | Search queue bounds peak MSPT and soldiers keep advancing |
| GO_TO and FOLLOW relocation | Command generations reject stale results |
| Exposed flank with no better cover | Failed search does not repeat every tick |
| Threat direction changes after failed flank | Reevaluation happens promptly |
| Suppression while in cover | Soldier remains protected and emergency policy wins |
| Terrain changes during worker search | Result is rejected or exactly revalidated |
| Reservation collision | Only one soldier applies the contested cover |
| Soldier death, unload, or dimension change | Pending result is discarded |
| Server shutdown with queued work | Executor stops and retains no world references |
| Worker exception and queue saturation | Synchronous fallback remains correct |

## Explicit Non-Goals

- Do not run the current `CoverFinder` directly in `CompletableFuture` or an
  executor.
- Do not run vanilla pathfinding or navigation off-thread.
- Do not read live entities, block states, voxel shapes, light, or squad data
  from workers.
- Do not enable multi-tick perception caches as part of the threading change.
- Do not increase worker count until one-worker profiling demonstrates a queue
  bottleneck and spare CPU capacity.
- Do not combine detection, cover, projectile suppression, and pathfinding into
  one large asynchronous AI task.
- Do not remove the synchronous fallback until the snapshot path has extensive
  production evidence.

## Recommended Near-Term Sequence

The next implementation work should follow this exact sequence:

1. Complete Phase 0 instrumentation and controlled profiles.
2. Fix failed flank-search repetition through scheduling and input-aware retry.
3. Reuse identical cover passes and immediate path results.
4. Consolidate same-tick target queries and smoke lookups.
5. Extract a pure cover evaluator and run it synchronously.
6. Audit and run the existing machine-gunner snapshot evaluator in async shadow
   mode to validate executor lifecycle and snapshot equivalence.
7. Enable an opt-in routine-cover worker pilot only if snapshot measurements
   demonstrate a material server-thread benefit.

This sequence attacks the measured bottlenecks first, minimizes behavioral risk,
and makes multithreading an evidence-based optimization rather than a structural
rewrite.

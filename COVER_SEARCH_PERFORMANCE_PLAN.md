# Cover Search Performance Plan

## Problem

Mass ATTACK and GO_TO commands can cause many soldiers to run the complete
cover search in the same server tick. The search performs block scanning,
cover-quality raycasts, threat scoring, squad scoring, and sometimes path
validation. The profiler shows this work dominating the server thread through
`CoverTacticalGoal.findAndMoveToCover()` and `CoverFinder`.

## Safety Invariants

- All Minecraft `Level`, entity, reservation, navigation, and pathfinding
  access remains on the server thread.
- A queued search never clears an occupied cover while it waits.
- A stale search cannot select movement for a different attack or GO_TO
  command generation.
- A selected cover is still checked for availability before movement starts.
- Emergency searches take priority over routine attack, follow, and debug
  searches.
- A pending request is coalesced instead of being submitted repeatedly.

## Phases

### Phase 1: Bounded Server-Thread Scheduling

Implement a global cover-search queue processed at the end of the server tick.
Run at most a small fixed number of searches per tick, ordered by urgency.
Use deterministic per-soldier staggering for routine requests so a command
burst does not produce a burst of searches in one tick.

This phase changes when the existing search runs, not how it evaluates cover.
It should reduce peak MSPT while preserving the existing selection and
movement code.

### Phase 2: Goal Integration and Request Coalescing

Convert initial cover selection, attack advancement, GO_TO/FOLLOW relocation,
stuck recovery, and suppression reposition searches into queued requests.
Represent a queued search explicitly in the attack state machine so a delayed
search is not interpreted as `NO_COVER_FOUND` and does not trigger premature
uncovered fallback movement.

Cancel requests when a goal stops or its command generation changes. Reject
requests whose soldier, attack target, relocation target, or cover state is no
longer valid.

### Phase 3: Work Reduction

- Add cooldowns for failed and recently completed searches.
- Avoid repeating the same full scoring pass for attack fallback and tactical
  selection when the candidate list can be reused.
- Cache immutable candidate geometry for stable block regions and invalidate it
  from block changes or a bounded time-to-live.
- Keep debug-only top-cover searches outside the runtime search budget where
  possible.

### Phase 4: Optional Snapshot-Based Worker Evaluation

Only consider worker-thread evaluation after profiling Phase 1 through Phase 3.
Capture immutable block, entity, threat, reservation, and squad snapshots on
the server thread. Run only pure candidate discovery/scoring against those
snapshots. Apply results on the server thread after validating the world,
reservation, command generation, and path.

The current `CoverFinder` must not be called directly from a worker because it
reads live Minecraft world state and mutates runtime objects.

## Initial Configuration

Phase 1 uses a deliberately conservative fixed budget of two full cover
searches per server tick and a six-tick deterministic routine stagger. These
values should be moved to server configuration after profiling.

The first implementation slice leaves emergency suppression replacement
searches synchronous so their existing hide-and-retry behavior is unchanged.
They are intentionally excluded from the routine queue until Phase 2 adds an
explicit emergency pending state. Once queued, emergency priority must not
exceed the global budget without a separate emergency budget and benchmark.

## Verification

Compare controlled Spark profiles before and after with the same world,
soldier count, command, and threat setup. Verify:

- Peak MSPT and cover-searches-per-tick decrease.
- Queue coalescing and deferred requests are visible in performance metrics.
- Attack soldiers still advance toward and reach the objective.
- GO_TO and FOLLOW soldiers still select and reach valid cover near their
  destination.
- Suppression and shot-in-cover recovery still select replacement cover.
- No soldier remains indefinitely in a selecting, seeking, or repositioning
  state.
- No cover search or path operation runs off the server thread.

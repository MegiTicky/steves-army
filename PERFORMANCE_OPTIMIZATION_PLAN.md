# Performance Optimization Plan

## Goal

Reduce server-thread cost from soldier combat and cover AI while preserving the
current detection, targeting, cover-selection, and reaction behavior.

## Staged Approach

### Stage 1: Low-risk optimizations

These changes must preserve the current gameplay decisions and timing:

- Reuse entity-to-entity visibility results for the same level game tick.
- Keep visibility caches isolated per level so entity IDs from different
  dimensions cannot collide.
- Reuse exposure results for the same observer, target, level, and game tick.
- Reuse the detection system's temporary UUID set instead of allocating one on
  every detection tick.
- Do not change cover-search scoring or selection until its duplicate evaluation
  can be removed without changing squad-context scoring.

Stage 1 does not change:

- Detection ranges or arcs.
- Detection accumulation or decay rates.
- Target refresh intervals.
- Cover search radius or candidate limits.
- LOS, exposure, or cover behavior rules.

### Stage 2: Measure before changing behavior

Collect counters and profiler data for:

- Potential targets per soldier.
- Visibility cache hits and misses.
- Exposure calculations and exposure rays.
- Cover searches and candidates evaluated.
- Navigation path requests and retries.

Use controlled tests with idle soldiers, visible targets, partial cover,
suppression, and simultaneous cover searches.

### Stage 3: Controlled behavior/timing changes

Only after Stage 1 is tested in gameplay, consider:

- Short-lived exposure caching across multiple ticks.
- Staggered target refreshes.
- A short cooldown after failed cover searches.
- Shared or spatially cached cover search results.
- Narrower or cheaper candidate queries.

These can affect reaction time around peeks, cover transitions, and newly
visible targets and require explicit gameplay testing.

### Stage 4: Ray and allocation optimization

Investigate a fast native raycast path for ordinary solid-block LOS while
retaining the custom path for concealment, smoke, ignored cover blocks, and
partial voxel behavior. Optimize ray allocations only after verifying the
custom traversal's edge cases.

## Current Implementation Status

Stage 1 is implemented first. The visibility and allocation changes are ready
for gameplay testing. Stage 2 opt-in metrics are available through
`/stevesarmy_debug metrics on|off|reset` and `/stevesarmy_debug metrics`.
Cover-search deduplication remains deferred because the
current alternative-cover path uses a different scoring context and needs a
separate behavior-preserving design. No Stage 2 or later behavior changes
should be added until the current build has been tested with representative
combat scenarios.

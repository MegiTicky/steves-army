# Grenade Arc Attempts

## Decision

The analytical drag-aware estimator was not reliable enough to select a throw: it could produce a mathematically plausible arc that collided with terrain or cover before reaching the target. A distributed multi-tick candidate search was also tried, but it added delay and state invalidation while a grenade plan was waiting for later slices.

The controller now uses the bounded exact world simulation from commit `86d6aec`. One scheduler request is queued and the complete candidate set is simulated synchronously during one server tick. The selected arc is the lowest-error valid result from that bounded set.

## Current Parameters

- `MAX_ARC_CANDIDATES = 16`.
- Candidate ordering retains the historical low/high analytical seeds for bounce-capable grenades and expands the soldier-like tactical pitch set to fill the 16-candidate bound.
- Non-bounce candidates use analytical roots as seeds, with +/- 3 degree correction candidates, then exact collision validation.
- Simulation uses the native ballistic profile's launch speed, gravity, drag, lifetime, bounce flag, and bounce factor.
- Each tick performs block clipping, up to three native-style contacts, bounce response, drag, gravity, friendly-path checks, and thrower-side cover checks.
- Non-bounce impacts are rejected when they are premature along the target direction and terminate at the collision point.
- Valid arcs must be within the 4.5 block target zone, outside the configured friendly blast safety area, and at least 2 blocks from the thrower endpoint.
- Protected and defensive targets retain the current overthrow preference; target-plane clearance is required for the selected protected-target validation.
- First block collision, bounce count, candidate list, landing error, and correlated native throw diagnostics remain available when grenade logging is enabled.

## Test Status

`compileJava` passed from `steves_army/` with Java 17 and the local TaCZ library present. No automated grenade test source set exists. The full `build` and in-game validation of M67/RGN throws, cover launches, friendly-path rejection, premature impacts, and correlated explosion diagnostics remain pending.

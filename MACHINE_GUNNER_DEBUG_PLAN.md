# Machine Gunner Debug Plan

## Goal

Debug the machine gunner as a staged pipeline. Each stage must expose its inputs,
result, and failure reason before the next stage is investigated. The diagnostics
must not issue movement, alter suppression, or change firing behavior unless a
future command explicitly says it is a control command.

## Stage Order

### 1. Objective and Context

Verify that the entity is a `MachineGunnerEntity`, identify the active suppression
center, and report the source of that center: attack objective, tracked threat, or
ping threat. Also report healing, reload, recall, target, squad, owner, and current
cover state because these can prevent the support goal or combat goal from running.

Primary code:

- `MachineGunnerEntity.getSuppressionCenter()`
- `MachineGunnerSupportGoal.hasSupportObjective()`
- `SoldierEntity.registerGoals()`

Diagnostic command planned:

```text
/stevesarmy_debug mg objective [entity]
```

### 2. Support Anchor

Verify that the rear support anchor can be calculated from the squad rifleman line
or owner fallback. Report the engagement center, line anchor, away direction, and
final ground-snapped anchor. A missing anchor stops firing-position evaluation.

Primary code:

- `SupportPositionFinder.findSupportPosition()`

Diagnostic command planned:

```text
/stevesarmy_debug mg anchor [entity]
```

### 3. Suppression Exposure Targets

Verify that the suppression center produces exposure samples. Report whether the
samples came from nearby cover or the grid fallback and the total sample count.
An empty sample set makes every candidate fail firing-access evaluation.

Primary code:

- `FiringPositionFinder.generateSuppressionTargets()`

Diagnostic command planned:

```text
/stevesarmy_debug mg targets [entity]
```

### 4. Firing Candidates

Evaluate cover-peek and open-prone terrain candidates. Report counts before and
after the minimum firing-access threshold, plus the highest-scoring candidates.
This isolates raycast/access problems from navigation problems.

Primary code:

- `FiringPositionFinder.collectCoverCandidates()`
- `FiringPositionFinder.collectOpenProneCandidates()`
- `FiringPositionFinder.computeFiringAccess()`

Diagnostic command planned:

```text
/stevesarmy_debug mg candidates [entity]
```

### 5. Path Selection

Run the same bounded path checks used by production selection. Report candidate
rank, destination, posture, access, score, path existence, and `canReach()`. This
separates good firing lanes that cannot be reached from candidate-generation
failures.

Diagnostic command implemented first:

```text
/stevesarmy_debug mg evaluate [entity]
```

This command reports stages 1 through 5 in one read-only operation.

### 6. Movement Handoff

After evaluation succeeds, verify that the selected position is issued to
`CoverTacticalGoal`, that the cover state changes, and that navigation receives the
expected destination. This stage should report pending lane, active lane, target
cover, navigation path, and movement result.

Diagnostic command planned:

```text
/stevesarmy_debug mg movement [entity]
```

### 7. Arrival and Posture

Verify physical arrival at the exact destination, cover occupancy, prone-plan
acceptance, prone movement completion, and posture transitions. Open-prone movement
must not clear valid existing cover before navigation accepts the handoff.

Diagnostic command planned:

```text
/stevesarmy_debug mg posture [entity]
```

### 8. Active-Lane Validation

Once occupied, recompute firing access and report whether the lane remains valid.
This stage catches lanes that were valid during selection but become blocked or
misaligned after arrival.

Diagnostic command planned:

```text
/stevesarmy_debug mg validate [entity]
```

### 9. Suppression and Firing

Verify TaCZ availability, gun presence, reload state, suppression position,
combat-goal ownership, aim alignment, firing authorization, and actual shot
attempts. This stage is intentionally after movement and lane validation.

Diagnostic command planned:

```text
/stevesarmy_debug mg firing [entity]
```

## Implementation Rules

- Evaluation commands are read-only and must not select, reserve, move, or clear a
  firing position.
- Production selection continues to use `FiringPositionFinder.findBest()`.
- Debug output must identify the first failed stage instead of only reporting
  `best=null`.
- Keep expensive full scans command-driven; do not add per-tick logging for every
  candidate.
- Use the exact production thresholds and path-check limit in diagnostics.

## Current Deliverable

Implement `mg evaluate` first. It exposes objective context, support anchor,
suppression target count, candidate counts, top candidates, and the production
top-ranked path selection result. The command sends a one-shot world overlay to the
commanding player: orange crosses are suppression targets, cyan is the support
anchor, purple boxes are candidates not path-checked, red boxes are unreachable,
green boxes are reachable alternatives, and yellow is the selected candidate.
The output is intended to determine whether the next investigation should focus on
target generation, firing raycasts, pathfinding, or movement handoff.

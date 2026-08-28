# Grenade Arc Planning

## Goal

Make autonomous grenade throws simple and dependable. A grenade does not need to
hit an exact coordinate. It only needs to detonate in a useful area around the
hostile target while avoiding an obviously dangerous endpoint for friendlies.

## Design

### Deterministic simulator-first search

- Use a fixed elevation sweep, approximately 10 to 80 degrees in 2.5 degree
  steps.
- Try low-to-high for ordinary throws and high-to-low for protected targets.
- Use the native LesRaisins discrete movement order as the authority:
  collision and bounce, drag, then gravity.
- Do not depend on an analytical ballistic root. Analytical math may be used
  only as an ordering hint, never as a reason to reject the search.
- Keep the existing scheduler so arc searches remain bounded across server
  ticks.

### Practical target zone

- Select the trajectory with the smallest predicted distance to the target.
- Accept a predicted detonation within the effective grenade area rather than
  requiring an exact landing point.
- Keep the preferred overthrow point as a hint for clearing cover, but judge the
  result against the actual hostile target point.
- Use a simple bounce simulation for bouncing grenades. Non-bouncing grenades
  detonate at their first block impact; bouncing grenades continue through
  native block bounces until their predicted endpoint.

### Safety policy

- Friendly entities along the projectile path do not reject a throw. The
  server-side friendly-fire event handler remains the authoritative protection
  if an unexpected collision or movement occurs.
- Hard planner checks are limited to the predicted detonation endpoint:
  - It must be in the useful target zone.
  - It must not be inside the configured friendly blast-safety radius.
  - It must not be dangerously close to the thrower.
- Use closest-point distance from friendly bounding boxes, not feet positions.
- Do not separately reject the abstract aim point and candidate landing point.
- Do not classify ordinary early terrain contacts as thrower cover failures.

### Aim and preparation

- Plan one deterministic nominal trajectory. A random aim sample must not turn a
  safe nominal throw into a hard failure.
- Keep the native throw integration and apply the selected velocity after the
  native entity is created.
- During preparation, validate only state that can genuinely cancel the throw:
  soldier state, reservation, inventory, target validity, material target
  movement, and ballistic profile.
- At the final gate, run one fresh simulation and endpoint safety check.
- If the world invalidates the selected trajectory, perform one deterministic
  replan rather than repeatedly sampling random aim deviations.

### Diagnostics

Diagnostics should describe practical search outcomes, including tested
candidates, candidates reaching the target zone, unsafe endpoints, endpoints
too close to the thrower, and the best failed target error. Avoid ambiguous
shared flags that combine unrelated rejection causes.

## Required runtime safeguards

- Execute throws on the server.
- Invoke LesRaisins' native throw path so item consumption, ownership, and
  grenade setup remain native.
- Verify that the item was consumed and the spawned projectile belongs to the
  soldier.
- Keep server motion and position synchronization after velocity/origin
  correction.
- Keep `GrenadeFriendlyFireHandler` as the final friendly-fire guarantee.
- Restore the soldier's rotation after a throw attempt.

## Verification

- Build with Java 17 using `.\gradlew build` from the Forge project.
- Run `git diff --check`.
- Deploy with `build-deploy-test.bat`.
- Manually test open ground, crouched cover, high cover, nearby friendlies,
  M67 floor and wall bounces, RGN impact detonation, maximum range, and target
  movement during preparation.

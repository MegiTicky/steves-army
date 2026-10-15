# Soldier movement speed overlay

Enable with `/stevesarmy_debug speed on` (operator permission required).
Disable with `/stevesarmy_debug speed off`, or `/stevesarmy_debug none`.
`/stevesarmy_debug all` also enables it. It does not change soldier speed.

Nearby friendly and enemy soldiers show compact world labels. Aim at a soldier
for a detailed HUD and recent speed graph. The overlay covers up to 32 nearest
soldiers within 64 blocks of the observer. To limit clutter, world labels show
the nearest 16 within 32 blocks; the focused HUD also uses a 32-block range.

The server samples actual horizontal position changes every tick and sends an
update every five ticks. The displayed blocks/second values assume 20 TPS;
blocks/tick is the underlying measurement. It is independent of client movement
interpolation, animation playback, and `Mob.getSpeed()` movement intent.

Soldier locomotion has a fixed horizontal ceiling of **0.28 blocks/tick**
(**5.6 blocks/second at 20 TPS**, approximately normal player ground sprint).
It also applies to jumping, speed buffs, automatic enemy-near-path caution,
and pinned escapes. Crawl, manual CQB, doorways, and final approaches can be
slower. Pinned escapes can accelerate immediately but cannot exceed the ceiling.
The limit reason is `sprint-ceiling` when this policy constrains the request.

Movement alternates between **Traverse** (route facing, ADS off, optional forward
hip bursts) and **Tactical** (target facing, moving ADS fire). A cycle lasts
72–88 ticks, about four seconds, with at least 20 ticks per phase. GO_TO,
long routes, distant enemies, and short protective-cover approaches favor
Traverse; ATTACK and nearby visible enemies favor Tactical. No visible combat
target means continuous Traverse. Terrain, docking, crawl and stationary
combat still take priority; overrides can extend the elapsed cycle.

Tactical steering translates the world-space route into local forward/sideways
input while facing the enemy. Returning to vanilla route steering clears the
old sideways input, including terrain, docking and stopped-controller handoffs.
Vanilla forward navigation does not clear that input itself; retaining it can
bend the route as the soldier turns. This reset does not clear physical velocity
or external impulses.

Vanilla route movement uses unit forward input after resolving pace. Vanilla's
`Mob.setSpeed` also writes forward input; retaining that fractional input would
multiply pace twice. Physical ground Traverse now reaches the resolved ceiling
on an unobstructed route. Recorded velocity is after drag, while displacement
measures actual movement; compare horizontal vector lengths, not one axis.

Tactical pace is the resolved Traverse pace times 0.40 times the weapon ADS
ratio, applied once. For a saturated M4 with a 15% ADS penalty this is
0.0952 blocks/tick (1.904 blocks/second). The speed limit reads `tactical-ads`.
The owner label includes the effective movement and fire modes. Flight-recorder
frames additionally include `movement.policy`: preferred/effective mode,
phase timing, contextual shares, target visibility, crawl and facing ownership,
weapon normalization availability, and transition reason. See `SOLDIER_TRACE.md`.

The physical limit is shared across self-movement calls within each entity tick,
including anchor correction and coasting with zero movement intent. Ordinary
walking velocity carried into the next tick is bounded too, so acceleration
cannot be mistaken for an external impulse after a controller handoff.
Explicit knockback and its excess momentum remain exempt, as do large incoming
impulses detected before walking acceleration. Other mods' direct velocity
changes have no provenance; the fallback detects incoming speeds above 0.50
blocks/tick. Vehicle/platform displacement outside self-locomotion, teleports,
and vertical physics are not capped. Actual total displacement can therefore
exceed the ceiling when external movement is involved.

- **Actual:** physical horizontal displacement during the latest server tick.
- **Average:** horizontal speed over the most recent ten sampled ticks.
- **Peak:** largest per-tick speed over the last 100 sampled ticks (five seconds
  at 20 TPS), including brief bursts between network updates.
- **Request / attribute:** the goal's raw speed multiplier and effective movement
  attribute, including weapon movement penalties.
- **Target / applied:** resolved controller intent and current locomotion budget.
  A stopped controller can still allow anchor correction or external movement;
  an above-budget reading is diagnostic evidence, not proof of a bug.
- **Incoming / spent / impulse:** velocity before walking acceleration, the
  self-movement budget consumed, and whether the impulse exemption was active.
- **Owner / limit / posture:** the movement source, active speed constraint, cover
  state, pose, grounded status, and collision status.

Green world labels indicate speed within the displayed budget; red indicates
an above-budget sample, and magenta marks the impulse exemption. The HUD chart
shows received actual speeds in green and their corresponding caps in yellow
over up to nine seconds. Its points arrive every five ticks; the server's
five-second peak is the value that preserves bursts between updates.

To investigate a burst, enable the overlay, issue the ATTACK ping, and aim at
the affected soldier. Record its name, actual/peak speed, target/applied speed,
owner, limit reason, and impulse status. A high **applied** speed points toward
pace policy; physical movement above that budget calls for investigating
impulses, non-locomotion movement, or controller handoffs.

The diagnostic is opt-in. Sampling stops when nobody subscribes, stale client
snapshots expire, and leaving the world clears the overlay. Both client and
server need the updated mod because this adds a network packet (protocol 18).

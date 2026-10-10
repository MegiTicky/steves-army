# Changelog

All notable changes to Steve's Army will be documented in this file.

## [0.5.2] - Unreleased

### Changed

- Combat positioning rework.
  - Close-range fights no longer freeze soldiers on sight: the engagement hold only
    latches inside an emergency range (5 blocks; 3 in CQB formation) with a loaded gun,
    never while suppressed or reloading, and releases when it stops producing fire.
  - Soldiers caught in the open without cover follow an **exposed-combat doctrine**
    instead of endlessly re-failing cover searches: plant and engage, one bounded
    sidestep off a blocked firing lane, or a checked withdrawal that ends once clear.
- **Danger-map-aware routing.** Each squad bakes a transient line-of-sight danger grid
  from its known threats, and pathfinding adds exposure cost through dangerous ground
  weighted by role (support most cautious, then anti-tank, machine gunner, garrison,
  rifleman) — squads prefer covered routes but still cross open ground when the
  objective demands it. Danger is a cost, never a wall. Enemy soldiers use the same
  routing and detour around kill zones too.
- **Suppressed-traveler doctrine.** A soldier marching under near-miss fire keeps
  moving: the fire origin feeds squad intel so the danger map bakes the shadow, the
  path is recomputed danger-aware, and the soldier drops to a low crawl through
  stretches where crawling hides them (universal travel crawl + traveler suppression
  resistance).
- **Squad street-crossing doctrine.** Fire teams cross open ground in bounds: one
  bounder per team, an overwatch anchor (the machine gunner), covering fire from a
  teammate's real shots, and a quiet-dwell rule that releases docked soldiers so the
  march resumes instead of stalling.
- **CQB doorway/corner doctrine.** Squadmates queue behind a soldier at a narrow
  doorway instead of stacking into it; soldiers pre-aim past the first corner into the
  arc a known threat opens (pie-slicing); and after close contact breaks, a soldier on
  open ground holds a short lost-contact watch on the last-known position.
- **Assault-and-secure clearing.** Attack orders no longer complete a few blocks from
  the ping: a squad-level coordinator bounds a walkable clearing area, turns
  wall-adjacent blind spots and broken-contact last-knowns into check targets, and
  assigns them as claims. The order completes as **"Area secured"** only when the squad
  is inside the space, its blind spots are observed, contact is resolved, and everyone
  has settled. Cover churn after the fight resolves is fixed.
- **Machine-gun direct-fire cadence.** With an active visible target, machine guns now
  fire real machine-gun bursts (longer bursts, short recovery, tuned per-shot recoil)
  instead of the marksman pacing that made them aim slowly and starve at the aim gate.
- **Machine-gun round-robin suppression.** When an MG suppresses multiple known
  threats, each completed burst rotates to the next candidate position, and two MGs
  interleave instead of stacking on the same target (`mgSuppressionRoundRobin`,
  `mgSuppressionRotationSize`).
- Body swap carries health: swapping into a soldier sets your health to theirs, and
  the body you leave keeps the health you had — damage round-trips through bodies and
  swapping back is never a free heal (`armorDoctrine.swapSyncsHealth`, default true).

### Fixed

- Soldier gun magazine split-brain between hand and inventory: the held stack and the
  inventory slot were separate copies, so reloads could strand, loop, and drain ammo.
  The main-hand slot now shares one stack instance end to end. Death pouches and
  pick-block eggs also stop carrying the enemy infinite-reserve ammo NBT.
- Machine gunners no longer strand in SEEKING_COVER without pathing while riflemen
  claim cover normally (fallback reservation pools + seek escalation).
- Prone deployment no longer bypasses the physical-cover gate for target-less
  soldiers under fire, and the selector refuses prone positions while suppressed.
- A reload starting during the final cover dock no longer leaves the soldier crouched
  short of the slot for the whole magazine swap.
- Cover scoring: the distance term is awake again, pilot classification is
  threat-aware, and the legacy x10 scorer is retired.
- Fire-team rebalance buckets soldiers by role instead of exact main-hand item (so
  empty-handed or sidearm-swapped soldiers no longer collapse into one role-blind
  bucket), and soldiers in unloaded chunks keep their roster slot.
- Cover churn after a clearing order's contact resolves: free-picked checks are sticky
  and claims reassign promptly, so soldiers work the remaining checks instead of
  flip-flopping covers.

### For developers

- `/stevesarmy_sim` headless state-machine simulation harness: scenario fixtures,
  hitscan sim guns, HTML animation recording, a danger-map verification rig
  (oracle engine + mismatch detectors + red/green self-tests), fast ticks, and
  core/full scenario tiers.
- Debug renders: `/stevesarmy_debug coverstate` (per-soldier cover/peek/mode
  diagnostic card), `/stevesarmy_debug dangermap` (danger cells, threats, and
  corridors — own squads by default, `all` restores everything), `/stevesarmy_debug
  course` (five-room CQB training building with enemy defenders) and the soldier FOV
  cone overlay.
- Fire-pipeline telemetry: every silent fire-chain exit is recorded with a reason and
  surfaced in the coverstate card and sim JSON.

## [0.5.1] - 2026-09-28

### Added

- **Body swap.** Tap **V** to swap bodies with the looked-at soldier (aim assist uses
  the command-stick cone: most-centered soldier wins within 32 blocks, half the
  stick's angle, leaves and fences don't block); tap on empty air does nothing. Hold
  **V** to open the role wheel with per-role sectors and a **Previous Body**
  swap-back. The body you leave behind becomes a soldier of its stored role carrying
  your items; overflow rides in a per-soldier stash that returns on swap-back and is
  lost where the soldier is lost.
- **Friend-or-foe system.** Three-state stances (friendly / neutral / hostile) per
  owner, layered on top of the built-in rules and resolved player > team > category:
  - **FoF tab** in the squad command screen with players, teams, and entity-category
    rows plus a stance dropdown.
  - **Look-at mark keybind** (unbound by default; category "Steve's Army") opens the
    stance picker for the entity you're looking at.
  - `/stevesarmy fof set player|team|category <key> <friendly|neutral|hostile|default>`
    and `/stevesarmy fof clear|list`, optionally `for <players>`.
  - HOSTILE marks beat team alliance; NEUTRAL players aren't proactively targeted
    until they attack; category stances replace the monsters/target-dummy config
    toggles per owner; grenade safety honors HOSTILE marks.
- **Monsters proactively hunt soldiers** (Recruits-style): vanilla monsters get a
  target goal on all soldier types on world join (creepers and endermen excluded).
  Configurable via `targeting.monstersAttackSoldiers` (default true); off restores
  retaliate-only behavior.
- **Supply pouch rework.** The supply pouch is now a 27-slot container item:
  shift + use opens it, use throws it like a snowball. A direct entity hit hands the
  contents to a player or restores them slot-by-slot into a soldier (armor and gun
  layout included); a miss lands a ground pouch anyone can pick up. Contents live in
  the item's NBT so they survive relogs, throws, and hand-offs. The support medic now
  throws the same pouch, homing to the staged ally, replacing the old resupply pouch
  entity and item (both removed).
- **AT pickup conversion:** a soldier who picks up a rocket launcher converts to the
  anti-tank role with the launcher stowed.
- **Gun slotting:** soldiers sort guns into a standard layout (main gun in hand,
  pistol in the sidearm slot), check their inventory, and equip a gun from the
  backpack when the main hand is empty.
- **Superb Warfare compat** (optional, `superbwarfare.enabled`): anti-tank soldiers
  treat enemy-crewed Superb Warfare vehicles as hard targets, and vehicle crew can
  board and man their guns (gunnery only — SBW vehicles are client-input driven, so
  AI cannot drive them).

### Changed

- Soldiers killed drop their whole kit inside a single supply pouch instead of a
  shower of item entities (`armorDoctrine.soldierDeathDrops`, default true).

### Fixed

- Anti-Tank soldiers no longer fire on vehicles that carry friendlies
  (friendly-occupant check).
- Valkyrien Skies compatibility performance issue (cover and pathfinding tick cost
  on VS2-heavy worlds).
- FoF tab buttons rendering past the right screen edge.
- Recall placement no longer drops soldiers into tree canopies (leaf grounds are
  skipped in the safe-placement scan).

## [0.5.0] - 2026-09-19

### Added

- **Sidearm slot:** a second gun rendered slung on the soldier's back (TaCZ-style).
  A soldier whose primary runs dry while fighting in the open emergency-draws the
  sidearm instead of reloading, then swaps back once in cover or the fight ends.
- **Anti-tank role split:** dedicated `steves_army:anti_tank` and
  `steves_army:enemy_anti_tank` soldiers with their own spawn eggs and
  `/stevesarmy spawn` branches. AT hunters fire from cover, keep a launcher-ammo
  reserve, and a **heavy-fire suppress ping** orders the hunter to shell the area
  (limited to one rocket, then sidearm suppression).
- **Suppression-ping reliability rework:** pinged soldiers relocate to better firing
  positions, autonomous suppression tracks live intel and preempts at range, paired
  fire spreads across gunners, and blind soldiers are never relocated.

### Fixed

- Ghost soldiers cleaned out of the owned-soldier registry; dismissal is sealed.
- Armor hunters no longer target their own side's vehicles.
- TaCZ event handlers are registered behind per-event presence guards.

## [0.4.0] - 2026-09-12

### Added

- **Vehicle crew role** (`steves_army:vehicle_crew`, enemy variant included): soldiers
  seat themselves in Create seats on Valkyrien Skies ships and man Tallyho hull MG /
  periscope stations with limited turret traverse, sustained-fire bloom, and
  last-known-position suppression. Crew spawn eggs, seat persistence across restarts,
  and re-seat after a seat vanishes on reload.
- **Armor doctrine:** soldiers detect enemy-crewed vehicles as hard targets via
  crewed turrets or crewed occupancy; role-based infantry reaction (riflemen suppress
  the vehicle, avoid-and-displace without AT support); hull firing solutions hit hull
  surfaces instead of bounding-box air; a dynamic firing gate gives each engagement a
  firing personality (variable bursts, suppression aim, miss ratchet).
- **Vehicle wheel page** on the ping wheel: MOUNT / DISMOUNT / MOUNT_CREW orders with
  VS Analog Warfare mount-handle integration.

### Fixed

- Hull MG ghost-fire, fire-arc violations, and covered-target aim points.
- Ship pathing: soldiers avoid the transformed hull, refuse under-hull extraction
  spots, and exit at the nearest vehicle handle.
- Crew seats protected from Create's cross-ship occupant swap.

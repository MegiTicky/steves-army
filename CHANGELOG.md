# Changelog

All notable changes to Steve's Army will be documented in this file.

## [0.6.0] - Release preparation

### Soldier Swap
- Swapping bodies carries health with it: take the soldier's current health and leave your own behind. Swapping back no longer provides a free heal.

### Superb Warfare Support
- SBW firearms use a dedicated gun handler. Dedicated vehicle crew can drive compatible SBW vehicles from the Vehicle page, while ordinary infantry ride in passenger seats.
- Drivers maintain space from friendly vehicles, stop close to GO_TO pings, and brake for friendly soldiers and vehicles. Anti-tank soldiers can target hostile-crewed vehicles.

### Machine Gunner Overhaul
- Machine gunners retain their existing support-role cover and movement safeguards while providing sustained ATTACK suppression.
- Suppression bursts rotate across every known enemy position, with multiple gunners interleaving their fire instead of stacking on one target.
- Visible targets receive longer bursts with faster recovery and better tracking on moving enemies, while low-in-cover firing keeps the gun's crawl recoil.
- Cover seeking now completes the move more reliably instead of stranding a gunner mid-seek or skipping from cover straight to the objective.

### Danger Map & Moving Under Fire
- Squads build a line-of-sight danger map from known threats. Friendly and enemy pathfinding favors safer routes around kill zones, while danger remains a cost rather than an impassable wall.
- Suppressed soldiers keep moving when a safer route exists, and crawl through stretches where going low reduces exposure while incoming fire is fresh.
- Relocation under suppression only happens when the route is fully safe; fully pinned soldiers hold their cover instead of walking out.

### Prone Positions & Squad Spacing
- Soldiers prioritize physical cover that protects them from the threat. When suitable cover is unavailable, they can spread into prone firing positions with usable sightlines.
- Squad spacing applies while moving and selecting defensive positions, including prone firing lanes. The spacing distance is configurable, with a default of 3 blocks.

### CQB & Room Clearing
- Close-quarters behavior no longer freezes when an enemy is spotted nearby, and close-range cover seeking works more consistently.
- Squads queue at doorways, pre-aim around corners and search last-known enemy positions after contact breaks.
- Soldiers make smaller corner peeks and can briefly expose themselves to fire a short burst at a recently spotted enemy before withdrawing.
- ATTACK combines an assault with room-by-room and area clearing, advancing cover-to-cover and checking blind spots before reporting "Area secured. All clear."
- Improved clearing objectives and regrouping on slabs and other partial-height ground. New orders take over from earlier regrouping tasks.

### Movement & Recovery
- Traverse lets soldiers relocate quickly while Tactical movement runs at 40% pace so they can fight while advancing.
- Fire teams cross open ground in bounds with an overwatching gunner; soldiers caught in the open return fire instead of endlessly re-seeking cover.
- Normal travel speed is capped around player sprint speed, with fixes for movement bursts and sudden skips.
- GO_TO orders arrive properly and hand off to cover, with improved long-distance travel and obstacle detours.
- Fixed soldiers becoming stranded outside cover during reloads, suppression or interrupted movement, and fixed doorway detection on raised paths.
- Added recovery from persistent movement failures. Soldiers trapped in enclosed terrain can relocate a short distance as a last resort while retaining their movement order.

### Soldier Animation Overhaul
- Rebuilt soldier presentation around a Simple Enemy-derived full-body rig and animation set, with updated idle, hit reactions, death animations and head tracking.
- Improved TaCZ weapon handling and ready stances for rifles, pistols and miniguns, alongside updated armor and stowed-weapon rendering.
- Added refreshed standing, crouching, kneeling, cover and crawl transitions. Half-cover firing uses a planted one-knee stance, with an updated walking and running stride.

### Command Wheels & Fireteam HUD
- Added optional click-to-open controls for command, vehicle, formation and fire-team wheels. Hold-to-open remains the default.
- In click mode, left-click confirms, Escape or the opener button cancels, and right-click switches between command and vehicle pages.
- Added a configurable compact fireteam HUD showing squad strength, orders and suppression, with estimated travel progress and arrival times.

### Guns, Equipment & Squad Management
- Fixed the empty-gun reload loop: soldiers handed an empty gun now reload and fire instead of looping forever.
- Aim holds on moving targets more patiently, including imperfect bursts against fast movers. Reload timing is role-aware, and gun weight no longer pushes caution movement below sneak pace.
- Soldiers interrupt combat less readily for tactical reloads, with longer firing windows for machine gunners. Suppression survives player respawn.
- When death drops are enabled, guns and one carried ammunition stack per ammunition type used by those guns drop separately. Remaining equipment stays in the supply pouch, preserving its inventory slots.
- Fixed soldiers losing their owner's team affiliation while the owner is offline, including across chunk reloads.
- Enemy anti-tank soldiers can spawn into callsign squads.

### Configuration & Performance
- Added optional server-side soldier chunk loading, disabled by default. It keeps qualifying nearby friendly and enemy soldiers active beyond normal ticking distance, with matching tracking distance.
- Chunk-loading distance defaults to 256 blocks and is configurable from 16 to 1024 blocks.
- Reduced processing costs for cover searches and squad danger updates.

### Developer Tools
- Added headless simulation scenarios, a soldier flight recorder with trace viewing, and debug overlays for cover state, danger maps, movement courses and field of view.
- Added a recording command to capture soldier behavior for debugging.

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
  treat hostile-crewed vehicles as hard targets, dedicated crew can drive and man
  SBW vehicles, and infantry use them as passenger transports.

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

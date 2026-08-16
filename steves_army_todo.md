# Steve's Army - Todo

## Valkyrien Skies Support
- [ ] Soldiers can dismount VS ship with player sitting on it, and follow GO_TO / attack commands
- [ ] Soldiers that are not on the valkyrien skies ship can still follow the ship (Dont get too close though, act as infantry support)
- [ ] Squad command screen: do not mount button to black list this vehicle for soldier mounting

## Squad Commands
- [x] Follow/Hold toggle
- [x] Hold position with location
- [x] Attack mode
    - [x] Formation with attack mode
    - [x] Attack ping should not change threat direction
    - [x] Cover-to-cover attack movement
- [x] Ping system (look-at, move-to, enemy-spotted)
- [x] Ping wheel UI
- [x] Squad HUD (debug overlays)
- [ ] Dodge artillery ping (find cover with roof, switch to crawl)
- [x] Suppressive fire ping
    - [x] Machine gun suppressive fire more intensive
- [x] Spotted target info: When soldiers in squad spots enemy, ping it/set glow effect to notify the player
- [x] Make GO_TO/Follow more similar to how attack mode work. Instead of reaching the location->wait like 3s->start seeking cover. Do like relocation needed->search cover near destination->pathfind to cover directly. And only fall back to location based (current behavior) if no cover is found
- [ ] <idk what name> flag: A item, something that the player can place on key defensive location (etc. a bunker, high ground). And soldier will go there, prioritizing machine gunner first. The soldier should stay at that exact block (and maybe only crawl to take cover if needed). A new soldier will automatically come to replace the old one if the old one is dead. Give player some sort of option to just look there and press a button to cancel it
- [ ] Squad activity display. Persistance ping? that tells the player each fire team what they are doing (GO_TO?Attack?Suppress?)
- [ ] Improve suppressive fire interaction. The current one is a bit too short for like one fire team suppressing and one attacking. But making a seperate start and end suppress seems a bit excessive. Need to figure a better way to do this

## Fire Teams
- [x] Fire team system (create, select, cycle)
- [x] Assign recruits to selected fire team
- [x] View and manage fire teams from squad command screen
- [x] Smart fire team allocation (weapon-balanced, history-aware)
- [x] Dismiss individual soldiers
- [x] Optional fire-team controls (config toggle)

## Combat AI
- [x] Target acquisition (line of sight, threat awareness)
- [x] Basic shooting AI
- [x] TaCZ weapon integration (reflection-based)
- [x] Aim accuracy system
- [x] Suppression system (decay, pinned threshold)
- [x] Suppressive fire at enemies behind cover
- [x] Tactical and emergency reloads
- [x] Healing item usage
- [x] Transparent block / smoke check
    - [ ] Add support to smoke from more different mod (TaCZ smoke grenade, mianbo armory etc) [low_priority]
- [x] See/shoot through glass-like blocks
- [x] Friendly fire prevention (raycast check before shooting)
- [Canceled] Recoil simulation (aim drift per weapon, recovery time scales with recoil)
- [x] Stabilized prone firing for high-recoil weapons (terrain/LOS checked, delayed and cooldown-limited)
- [ ] Enemy fire awareness->alert(increase detection) when hearing/seeing enemy fire
- [ ] Fireteam suppression level. A fireteam level
  - [ ] Stop peek if fireteam pinned (When peeking just lead to loses and no result, just hide and ask for further instruction [notice player])
- [ ] Recoverable Fire-Superiority System
- [ ] Fix random rotation in cover problem
- [ ] Variable aim quality threshold. More variety. And 2 different mode, normal and suppressive fire (suppressive fire threshold would be very low, for suppressive fire in direct engagement senario)
- [x] Is the soldier not canceling suppressive fire when active target is visible?
- [ ] Soldier not finishing downed target, should be able to finish downed player just like enemy soldier
- [ ] They are picking up things on the ground and replace their main hand gun. And in the middle of battle, somehow everyone decide to throw their gun away and pick up knife

## Cover System
- [x] Cover detection (HALF / FULL classification)
- [x] Cover scoring (protection, distance, firing quality, peek angle)
- [x] Cover reservation system
- [x] Cover state machine (seek, hide, peek, suppress, reposition)
- [x] Peek system (half-cover stance pop-up, full-cover side-step)
    - [x] Variable timing (less robotic peeking)
    - [x] Self-suppression fix (bullets near self)
    - [x] 1.5-block cover height fix (stand to see over)
- [x] Prone/crawl animations
- [x] Suppression integration (duck-back on fire)
- [x] Non-peekable cover → reposition
- [x] Height-aware cover posture
- [ ] Fix Copycat partial-layer cover height: include layers below 1/2 block when stacked above solid cover, and use the same crouch/standing posture for firing-lane LOS and the live peek. A solid block plus a 3/8 Copycat layer must not test from a blocked crouch eye while the soldier stands to fire.
- [x] Fix suppression near-miss detection
- [x] Fix non-peekable slide into wall
- [x] Reconsider cover when non-peekable
- [x] Tactical reload in cover (no reload while exposed)
- [x] Cover pathfinding straight-line obstacle fix
- [x] Fix weird head aim in cover — soldiers stare at odd angles while hiding
- [-] Cover path exposure — penalty if approach route is exposed to threats [Major_work]
- [-] Prevent slide when all peek positions invalid
- [x] Reposition if heavily suppressed. This need to work with cover path exposure or else the soldier is just going to reposition and run into the open and die
    - **Suppression-driven reposition now use cover path exposure
- [x] Fix enemy crawl pose. Currently just use vanilla crawl. Should use the same as soldier
- [-] Hurt when in cover reposition (if you are hurt when hiding in cover, that mean your cover is not good, re-evaluate cover now)
- [-] Cover angle, sometime they are taking cover that doesnt protect them
- [-] Soldier got pushed out of cover problem. Velocity manupulation back when in cover?
- [x] Some time soldier still tries to velocity manupulate their way through walls
- [-] A few soldier in the squad peeking repeatly but see no target. But part of soldier is actively engaging with enemy-->the soldier in the front "tell" the soldier not engaging the firing position of enemy, and use that to raycast, and reloate to appropriate firing position

## Respawn System
- [x] Death event handler
- [x] Squadmate selection UI
- [x] Transfer player to squadmate
- [x] Squad persistence on player swap
- [x] PlayerRevive Compat

## Performance And Settings
- [x] Distant soldier gun rendering culling
- [x] Configurable ping display size
- [x] Configurable diagnostic logging
- [x] Cover and combat diagnostics
- [x] Avoid walking into barbed wire & hazards
- [ ] Balance testing
- [-] Performance optimization
- [-] Multiplayer testing (is tested continously)

## Polish
- [ ] UI improvements
- [ ] Sound effects
- [ ] Weird look angle: soldier not looking at target when shooting
- [ ] Prone walking: soldiers remain proned when moving
- [ ] Make skin data-driven/configurable (player can use their own skin for the soldiers)

## Future (Post-MVP)
- Detailed planning UI (engagement rules: scout/battle/stealth, trigger discipline, formation, movement speed)
- Human-like behavior (hesitation, imperfect aim, reaction delay, target switching delay)
- Possible worldmap based planning (player can plan out sequence of attack before engaging. Might be too tactical and too complicated for normal player though)
- Mortar/crew-served machine gun

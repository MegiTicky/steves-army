# Steve's Army

Lead your own squad of AI soldiers through combat in Minecraft.

Recruit soldiers, equip them with modern firearms, organize them into fire teams, and command them using a tactical ping system. Your soldiers are persistent companions who take cover, suppress enemies, reload, and fight alongside you.

Example video: [Steve's Army gameplay](https://youtu.be/sHR4XjqFlCI)

## Features

### Squad Command

- Recruit and command a squad of AI soldiers.
- Switch between **Follow** and **Hold** modes.
- Order soldiers to attack positions, move to locations, or provide suppressive fire.
- Open soldier inventories to equip weapons, armor, and ammunition.
- Recall or dismiss individual soldiers.
- **Body swap:** tap `V` to swap bodies with the soldier you are looking at; hold `V`
  for the role wheel with a Previous Body swap-back. Damage round-trips through
  bodies — swapping back is never a free heal.

### Friend or Foe

- Mark players, scoreboard teams, or entity categories as **friendly**, **neutral**,
  or **hostile**; stances override the built-in rules (player > team > category).
- Manage stances in the squad command screen's **FoF tab**, with an optional look-at
  mark keybind, or with the `/stevesarmy fof` commands.
- Hostile-marked players are engaged like enemies, neutral players are ignored until
  they attack, and grenade safety honors hostile marks.
- Vanilla monsters proactively hunt your soldiers (Recruits-style) and can be marked
  per owner; both directions are configurable.

### Fire Teams

- Divide your squad into up to four fire teams.
- Assign recruits directly to a team.
- View and reorganize teams through the squad command screen.
- Soldiers are distributed intelligently by role and position.
- Use the optional fire-team control wheel for quick selection.

### Smart Combat AI

- Soldiers detect enemies by line of sight only, with no x-ray vision.
- Aim quality governs accuracy and fire rate based on target exposure, movement, weapon accuracy, and recoil.
- Target acquisition speed responds to visibility, exposure, movement, and brightness.
- Soldiers reload from their own inventories, preferably from behind cover.
- Soldiers use healing items when injured.
- Machine guns fire real machine-gun bursts at active targets and rotate their suppression across last-seen positions.
- Pathfinding weighs line-of-sight danger: squads prefer covered routes and low-crawl through exposed stretches when suppressed, without ever refusing the objective.
- Close-quarters doctrine: soldiers queue at doorways, pre-aim around corners, and clear rooms as a team.

### Tactical Cover System

- Soldiers automatically seek cover when under fire.
- They crouch behind low walls and side-step around full-height cover to peek and shoot.
- Behavior timing is randomized so soldiers do not act like robots.
- Soldiers abandon positions with no viable firing angle and search for better cover.
- Each cover position is reserved for one soldier to prevent crowding.
- Soldiers caught without cover plant and engage, sidestep off a blocked firing lane, or make a checked withdrawal instead of freezing.

### Suppression

- Incoming fire suppresses soldiers and reduces their combat effectiveness.
- Heavily suppressed soldiers stay pinned behind cover.
- Order your squad to suppress a last-seen enemy position, or use area suppression to deny an entire zone.

### Attack Movement

- When ordered to attack, soldiers advance from cover to cover.
- Fire teams cross open ground in bounds with overwatch and covering fire.
- Attack orders now **clear and secure an area**: soldiers work through the space's blind spots as assigned claims and complete the order as "Area secured" only when the ground is observed and contact is resolved.

### Respawn as a Squadmate

When you die, you automatically respawn as your nearest living squadmate.

### Supply Pouches

- Dead soldiers drop their whole kit inside a single **supply pouch** instead of a shower of item entities.
- The pouch is a 27-slot container item: shift + use opens it, use throws it.
- A direct hit hands the contents to a player or restocks a soldier slot-by-slot; a miss lands a ground pouch anyone can pick up.
- The support medic throws homing pouches to resupply squadmates mid-fight.

### Valkyrien Skies Support

- Soldiers automatically board ships when you do.
- They stay seated while the ship moves and disembark safely.
- They avoid Valkyrien Skies ships while pathfinding and teleport away if they become entangled.

### Gun Compatibility

Steve's Army supports TaCZ (Timeless and Classic Guns), Point Blank (Vic's Point Blank), and Superb Warfare (SBW) firearms. Soldiers use supported weapons with aiming, recoil, magazine management, and manual bolting, while consuming ammunition from their own inventories. Superb Warfare vehicles are also supported as hard targets for anti-tank soldiers. Dedicated vehicle crew can drive SBW vehicles and operate their weapon stations; ordinary infantry ride as passengers.

### Ping Wheel System

Coordinate your squad with eight ping types:

- **GO_TO**: Move to a location.
- **ATTACK**: Assault a position.
- **THREAT_DIRECTION**: Mark an enemy bearing.
- **SUPPRESS_AREA**: Pin down an area.
- **FOLLOW / HOLD**: Change squad mode.
- **SEND**: Send one soldier per ping to a location.
- **LOCATION**: Mark a point of interest.

Pings appear as diamond markers with distances and labels. They are shared with players on your scoreboard team.

### SBW vehicle controls

Vehicle crew stay out of regular squad pings. Hold the Ping Wheel key (`MMB` by default), then cycle between **Orders** and the existing **Vehicle** page with the configured mouse button (`RMB` by default). On the Vehicle page, mounting and dismounting actions are on the left; driving orders are on the right. **GO_TO** lines up with **GO_TO** on the Orders page, and **ATTACK** is next to it. Driving orders go only to vehicle crew in the selected fireteam who occupy an SBW driver's seat. Aim at terrain for GO_TO or at a target for ATTACK. FOLLOW keeps the vehicle near its owner; HOLD brakes at its current position.

Use the Fire Team Wheel (`P` by default) to select the crew's A/B/C/D group. You can assign existing crew to a group from the `[team:duty]` badge in the Crew tab, or choose a group before spawning/converting crew. This lets you control vehicle groups without sending infantry orders to crew. **Mount** and **Dismount** apply to ordinary soldiers. **Mount Crew** and **Dismount Crew** apply to vehicle crew in the selected group.

## Dependencies

### Required

- **Minecraft** 1.20.1
- **Forge** 47.4.0 or newer in the 47.x range

### Optional

| Mod | Supported range | Tested version |
| --- | --- | --- |
| TaCZ (Timeless and Classic Guns) | 1.1.0 or newer | 1.1.8 |
| Point Blank (Vic's Point Blank) | 1.7.0 or newer | — |
| Valkyrien Skies 2 | 2.3.0-beta.5 or newer | 2.3.0-beta.5 |
| PlayerRevive | 2.0 or newer | 2.0.31 |
| Superb Warfare | 0.8.0 or newer | — |
| Tallyho | 3.2 or newer | — |
| VS Analog Warfare | 0.4 or newer | — |
| Yes Steve Model | 2.6.5 or newer | — |
| LesRaisins Tactical Equipments | 0.3.0 or newer | — |

## Development

This is a Minecraft Forge 1.20.1 project. Java 17 is required. From the project directory:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew build
```

See `AGENTS.md` in the parent workspace for development, run configuration, and deployment details.

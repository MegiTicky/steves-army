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

### Fire Teams

- Divide your squad into up to four fire teams.
- Assign recruits directly to a team.
- View and reorganize teams through the squad command screen.
- Soldiers are distributed intelligently by weapon type and position.
- Use the optional fire-team control wheel for quick selection.

### Smart Combat AI

- Soldiers detect enemies by line of sight only, with no x-ray vision.
- Aim quality governs accuracy and fire rate based on target exposure, movement, weapon accuracy, and recoil.
- Target acquisition speed responds to visibility, exposure, movement, and brightness.
- Soldiers reload from their own inventories, preferably from behind cover.
- Soldiers use healing items when injured.
- Machine guns are especially effective at pinning enemies down.

### Tactical Cover System

- Soldiers automatically seek cover when under fire.
- They crouch behind low walls and side-step around full-height cover to peek and shoot.
- Behavior timing is randomized so soldiers do not act like robots.
- Soldiers abandon positions with no viable firing angle and search for better cover.
- Each cover position is reserved for one soldier to prevent crowding.

### Suppression

- Incoming fire suppresses soldiers and reduces their combat effectiveness.
- Heavily suppressed soldiers stay pinned behind cover.
- Order your squad to suppress a last-seen enemy position, or use area suppression to deny an entire zone.

### Attack Movement

- When ordered to attack, soldiers advance from cover to cover.
- They spread out to avoid clustering and continue moving toward the objective.

### Respawn as a Squadmate

When you die, you automatically respawn as your nearest living squadmate.

### Valkyrien Skies Support

- Soldiers automatically board ships when you do.
- They stay seated while the ship moves and disembark safely.
- They avoid Valkyrien Skies ships while pathfinding and teleport away if they become entangled.

### TaCZ Gun Compatibility

Steve's Army supports Timeless and Classic Guns firearms. Soldiers use TaCZ weapons with aiming, recoil, magazine management, and manual bolting, while consuming ammunition from their own inventories.

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

## Dependencies

### Required

- **Minecraft** 1.20.1
- **Forge** 47.4.0 or newer in the 47.x range

### Optional

| Mod | Supported range | Tested version |
| --- | --- | --- |
| TaCZ (Timeless and Classic Guns) | 1.1.0 or newer | 1.1.8 |
| Valkyrien Skies 2 | 2.3.0 or newer | 2.3.0-beta.5 |
| PlayerRevive | 2.0 or newer | 2.0.31 |

## Development

This is a Minecraft Forge 1.20.1 project. Java 17 is required. From the project directory:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew build
```

See `AGENTS.md` in the parent workspace for development, run configuration, and deployment details.

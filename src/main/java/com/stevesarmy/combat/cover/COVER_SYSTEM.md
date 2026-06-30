# Cover System Implementation Plan

## Overview

Dynamic cover detection system for Steve's Army soldiers. Evaluates positions in the world for cover quality using raycast-based analysis from threat positions.

---

## Core Design Principles

### Cover Height Classification

| Height | Type | Soldier Behavior |
|--------|------|------------------|
| < 0.5 | NONE/CONCEALMENT | No protection |
| 0.5 - 1.0 | HALF | Crouch/prone required |
| 1.0 - 1.5 | SHOOTABLE | Can shoot over while crouched |
| ≥ 1.5 | FULL | Complete protection |

### Block Solidity

Uses `BlockState.isCollisionShapeFullBlock()` to determine if a block provides solid cover:
- **Full blocks** (stone, cobblestone, iron): Solid cover
- **Partial blocks** (fences, trapdoors, slabs): Not solid - concealment only
- **Non-solid** (leaves, glass, tall grass): No cover

### Raycast-Based Quality Evaluation

Quality is determined by raycasting from threat eye position to soldier body points:
- Tests standing height (eye: 1.62m) and crouching height (eye: 1.27m)
- Tests multiple body points: feet, waist, chest, head, edges
- Only solid blocks (full collision shape) block rays

---

## Implementation Phases

### Phase 1: Core Data Structures ✓

- [x] `CoverPoint.java` - Data class for cover position, quality, type, protected directions
- [x] `CoverType.java` - Enum: NONE, CONCEALMENT, HALF, SHOOTABLE, FULL
- [x] `CoverDebugManager.java` - Client-side debug state

### Phase 2: Cover Detection ✓

- [x] `CoverFinder.java` - Scan for valid cover positions
- [x] `CoverQualityEvaluator.java` - Raycast-based scoring

### Phase 3: Debug Tools ✓

- [x] `CoverDebugCommand.java` - `/stevesarmy_cover` command
- [x] `CoverDebugRenderer.java` - Visual debug rendering

### Phase 4: Fix Core Issues (Current)

#### Issue 1: 2-Block Wall Incorrectly Rated
**Problem:** Two stacked solid blocks should be FULL (2.0 height), but system shows SHOOTABLE.

**Solution:** 
- Iterate upward when calculating cover height
- Sum heights of stacked solid blocks
- Stop when encountering non-solid or air blocks

#### Issue 2: Non-Solid Blocks Count as Cover
**Problem:** Fences, trapdoors, leaves treated as valid cover.

**Solution:**
- Use `isCollisionShapeFullBlock()` to determine if solid
- Non-full-block shapes = CONCEALMENT or NONE
- Only full collision blocks provide real protection

#### Issue 3: Houses Treated as Individual Walls
**Problem:** Corner positions in houses don't get composite quality bonus.

**Solution:**
- Raycast from actual threat position
- Test multiple body points simultaneously
- All blocking geometry is considered, not just nearest wall

#### Issue 4: L-Shape Cover Incorrectly Rated
**Problem:** Staircase/L-shape cover rated based on nearest wall, not actual LOS blocking.

**Solution:**
- Raycast tests actual bullet trajectory
- All solid blocks along ray path are considered
- Quality reflects true protection from threat angle

---

## Class Structure

```
com.stevesarmy.combat.cover/
├── CoverPoint.java           # Data: position, quality, type, directions
├── CoverType.java            # Enum: NONE, CONCEALMENT, HALF, SHOOTABLE, FULL
├── CoverFinder.java          # Detection: scan positions, evaluate cover height
├── CoverQualityEvaluator.java # Raycast: test actual protection from threat
└── CoverDebugManager.java    # State: client-side debug visualization
```

---

## Key Methods

### CoverFinder

```java
// Scan for cover points within radius
List<CoverPoint> findCoverPoints(BlockPos center, int radius, LivingEntity threat)

// Find best cover point for soldier
Optional<CoverPoint> findBestCover(BlockPos center, int radius, LivingEntity threat)

// Calculate height of cover in a direction (iterates upward)
float calculateCoverHeight(BlockPos soldierPos, Direction direction)

// Check if position is valid for standing
boolean isValidCoverPosition(BlockPos pos)
```

### CoverQualityEvaluator

```java
// Evaluate cover quality using raycasts from threat
CoverPoint evaluateWithRaycast(CoverPoint coverPoint, LivingEntity threat)

// Test if ray from threat to body point is blocked by solid block
boolean isRayBlocked(Vec3 from, Vec3 to)

// Generate test points on soldier body
List<Vec3> generateTestPoints(BlockPos pos, double eyeHeight, double totalHeight)

// Determine cover type from raycast results
CoverType determineTypeFromRaycast(float standingProtection, float crouchingProtection)
```

---

## Debug Commands

```
/stevesarmy_cover scan [radius]     - Scan for cover points
/stevesarmy_cover target <entity>   - Scan with threat entity
/stevesarmy_cover best [radius]     - Find best cover point
/stevesarmy_cover clear             - Clear visualization
/stevesarmy_cover toggle            - Toggle visualization
```

---

## Debug Visualization

| Element | Color | Meaning |
|---------|-------|---------|
| Box outline | Green | FULL cover |
| Box outline | Cyan | SHOOTABLE cover |
| Box outline | Yellow | HALF cover |
| Box outline | Gray | CONCEALMENT |
| Arrows | Green | Protected directions |
| Thick outline | Orange | Best cover point |
| Labels | White | Quality score + type letter |

---

## Performance Considerations

- Cache cover evaluations per chunk
- Re-evaluate every 40 ticks (2 seconds) or on block changes
- Limit raycasts per evaluation (8 body points × 2 stances = 16 rays max)
- Max cover points per scan: 50

---

## Testing Scenarios

1. **Flat wall (2 blocks high)** → FULL cover
2. **Single block wall** → SHOOTABLE cover
3. **Slab (0.5 height)** → HALF cover
4. **Fence** → CONCEALMENT (not solid)
5. **House corner (L-shape)** → FULL cover from multiple angles
6. **Open field** → NONE
7. **Behind glass/leaves** → CONCEALMENT (bullets pass through)

---

## Future Enhancements

- [ ] Stance system (soldier crouches in SHOOTABLE cover)
- [ ] Peeking behavior (pop-out to shoot, return to cover)
- [ ] Cover-seeking AI goal
- [ ] Integration with combat goal
- [ ] Config file for block cover values

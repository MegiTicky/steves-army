# Simple Enemy animation port

The default friendly and enemy soldier renderers now use Simple Enemy's standing
rig, six native keyframe clips, head tracking, combat/relaxed arms, rifle/pistol/
minigun poses and right-hand weapon attachment transform. No GeckoLib dependency
is needed. Attribution and GPL text are packaged under META-INF in the jar.

Custom YSM models keep their own renderer and animations. Player skins, skin
overlays, armor and the stowed-weapon layer use the adapted rig. Point Blank guns
retain the ordinary held-item transform; the copied attachment calibration is
for TaCZ.

New additions beyond the reference are standing/sneak/kneel blending, a low moving
sneak gait, two six-pixel leg segments, crawl entry/exit and a crawling gait. Arms
are single twelve-pixel segments with no extra hand cubes. Per-entity transition
weights prevent renderer sharing from leaking one soldier's pose into another.
The existing synced crawl amount and half-cover rise state drive the poses.
Locomotion uses a distance-driven, opposed full-length leg stride rather than
feeding limb swing into the upstream timed walk clip. It adds no root bob.
Exposed half-cover aiming raises both holding arms to keep the hand attachment
at least 1.125 blocks above the feet (a full block plus two model pixels).
This checks the grip anchor; actual barrel clearance still depends on the gun pack.
The server syncs a weapon-ready render hint, held for twenty ticks after combat
signals disappear; this does not change AI, targeting, collision or firing rules.

Run checks with Java 17:

```powershell
.\gradlew.bat -I tools/animation-checks.gradle animationChecks
.\gradlew.bat build
```

Checks sample all six clips, verify finite transforms, kneel/crawl vertical core
bounds, armor root/knee copying, same-frame stability and transition reversal.
These are geometry checks, not visual acceptance. The standing rig retains the
upstream proportions (about 2.04 blocks tall before headwear), while the gameplay
standing hitbox remains 1.8 blocks. Kneeling core height is about 1.50 blocks;
prone core height is about 0.59 blocks. Weapons and outstretched arms are not
required to fit inside the body collision box.

Human review should check idle/walk, weapon raise/lower, pistol and rifle holding,
hurt/death, cover enter/peek/return, stationary kneel versus moving sneak,
stand-to-crawl and crawl-to-stand, interrupted transitions, equipped armor and
several gun packs. Perfect arbitrary-pack grip/optic alignment is not guaranteed.

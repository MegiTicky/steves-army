# Third-Party Notices

## Simple Enemy Mod

Portions of the soldier animation and rendering implementation are derived from or adapted from Simple Enemy Mod by nekoyuni, licensed under the GNU General Public License, version 3.0.

- Upstream repository: https://github.com/NekoYuni/Simple-Enemy-Mod-Public
- License: GNU GPL-3.0; the complete license text is in [GPL-3.0.txt](GPL-3.0.txt).
- Ported/adapted portions: the six keyframe animation definitions in `src/main/java/com/stevesarmy/client/animation/SoldierAnimations.java`; soldier model geometry and standing pivots, with split legs and kneeling additions, in `src/main/java/com/stevesarmy/client/model/SoldierModel.java`; procedural crouch/crawl and weapon pose elements in that model; and the held-gun display transform in `src/main/java/com/stevesarmy/client/renderer/SoldierHeldItemLayer.java`.
- Changes: adapted to Steve's Army's model hierarchy and entities, with custom kneeling/crawling behavior and weapon-ready blending.
- Textures: no Simple Enemy Mod textures are included; the soldier model uses the existing Minecraft player skins.

The existing `LICENSE.txt` is retained as the Forge template license notice.

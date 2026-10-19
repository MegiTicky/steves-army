// Static half-cover posture review. The game still uses the vanilla humanoid rig.
import { mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const output = fileURLToPath(new URL('./review/half_cover_peek_animation.bbmodel', import.meta.url));
const elements = [];
function box(name, from, to, color, origin = [0, 0, 0], rotation = [0, 0, 0]) {
  const uuid = randomUUID();
  elements.push({ name, uuid, type: 'cube', from, to, origin, rotation, color,
    faces: Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down']
      .map(side => [side, { uv: [0, 0, 16, 16], texture: false }])) });
  return uuid;
}
function bone(name, origin, children, rotation = [0, 0, 0]) {
  return { name, uuid: randomUUID(), origin, rotation, visibility: true, export: true, children };
}

// Facing -Z. The cover is one full block high (top at y=16). Rifle top is y=18.
// Separate thigh and shin boxes make the tactical squat leg shape readable.
const soldier = bone('soldier', [0, 0, 0], [
  bone('body', [0, 16, 0], [box('torso', [-4, 13, -2], [4, 25, 2], 3)], [8, 0, 0]),
  bone('head', [0, 25, 0], [
    box('head', [-4, 25, -4], [4, 33, 4], 2),
    box('face forward', [-2, 28, -4.1], [2, 30, -4], 7)
  ]),
  // Hands are separate visible grip blocks at the rifle stock and fore-end.
  // Straight weapon-ready arms: both forearms meet the rifle along -Z.
  bone('right_arm', [-5, 22, -1], [box('right upper arm', [-7, 19, -8], [-3, 22, -2], 3)]),
  bone('left_arm', [5, 22, -1], [box('left upper arm', [3, 19, -8], [7, 22, -2], 3)]),
  bone('right_hand', [-2.5, 19, -9], [box('right hand grip', [-3.5, 17, -10], [-1, 20, -8], 2)]),
  bone('left_hand', [2.5, 19, -13], [box('left hand grip', [1, 17, -14], [3.5, 20, -12], 2)]),
  // One-knee kneel: one shin drops under the hip, the other thigh rises forward.
  bone('right_thigh', [-2, 13, 0], [box('right upper leg', [-4, 6, -2], [0, 14, 2], 5)]),
  bone('right_shin', [-2, 6, 0], [box('right lower leg kneeling back', [-4, 1, 0], [0, 5, 9], 5)]),
  bone('left_thigh', [2, 13, 0], [box('left upper leg raised forward', [0, 9, -9], [4, 14, 1], 5)]),
  bone('left_shin', [2, 9, -9], [box('left lower leg planted', [0, 0, -11], [4, 10, -7], 5)]),
  bone('rifle', [0, 18, -2], [box('rifle stock', [-1, 16, -7], [1, 18, -3], 7),
    box('rifle resting on cover top', [-1, 16, -20], [1, 18, -6], 7),
    box('rifle grip', [-1.5, 16, -10], [1.5, 18, -8], 7)])
]);
// Keep the reference as a true full block. In Blockbench, toggle this group off
// while reviewing the hidden knee and boot placement, then turn it back on.
const cover = bone('full half cover block', [0, 0, 0], [box('full cover block', [-8, 0, -8], [8, 16, 8], 6)]);
const floor = bone('floor', [0, 0, 0], [box('floor', [-20, -0.4, -24], [20, 0, 12], 0)]);
const model = {
  meta: { format_version: '4.10', model_format: 'free', box_uv: false },
  name: 'Half cover peek posture review', resolution: { width: 64, height: 64 }, elements,
  outliner: [soldier, cover, floor], textures: [],
  animations: [{ uuid: randomUUID(), name: 'half_cover_peek_posture', loop: 'once', length: 0.1,
    snapping: 40, animators: {} }], animation_variable_placeholders: ''
};
mkdirSync(new URL('./review/', import.meta.url), { recursive: true });
writeFileSync(output, JSON.stringify(model, null, 2) + '\n');
console.log(output);

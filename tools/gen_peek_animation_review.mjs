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
  bone('right_arm', [-5, 22, -1], [box('right upper arm', [-8, 17, -4], [-4, 24, 0], 3, [-6, 22, -1], [-30, 0, -12])]),
  bone('left_arm', [5, 22, -1], [box('left upper arm', [4, 17, -4], [8, 24, 0], 3, [6, 22, -1], [-30, 0, 12])]),
  bone('right_hand', [-3.7, 19, -7], [box('right hand grip', [-4.8, 17, -8], [-2.6, 20, -6], 2, [-3.7, 19, -7])]),
  bone('left_hand', [3.7, 19, -10], [box('left hand grip', [2.6, 17, -11], [4.8, 20, -9], 2, [3.7, 19, -10])]),
  bone('right_thigh', [-2, 13, 0], [box('right upper leg', [-4, 8, -2], [0, 16, 2], 5, [-2, 14, 0], [35, 0, 0])]),
  bone('right_shin', [-2, 8, -5], [box('right lower leg', [-4, 1, -7], [0, 9, -3], 5, [-2, 8, -5], [-35, 0, 0]), box('right boot', [-4.5, 0, -9], [0, 2.5, -4], 5)]),
  bone('left_thigh', [2, 13, 0], [box('left upper leg', [0, 8, -2], [4, 16, 2], 5, [2, 14, 0], [35, 0, 0])]),
  bone('left_shin', [2, 8, -5], [box('left lower leg', [0, 1, -7], [4, 9, -3], 5, [2, 8, -5], [-35, 0, 0]), box('left boot', [0, 0, -9], [4.5, 2.5, -4], 5)]),
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

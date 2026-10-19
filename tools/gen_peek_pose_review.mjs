#!/usr/bin/env node
// Generate a Blockbench review scene for the half-cover peek silhouette.
// Development aid only; the shipped renderer remains the Java humanoid model.

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, 'review', 'half_cover_peek_poses.bbmodel');
const elements = [];

function cube(name, from, to, color, origin = [0, 0, 0], rotation = [0, 0, 0]) {
  const id = randomUUID();
  elements.push({
    name, box_uv: false, rescale: false, locked: false, light_emission: 0,
    render_order: 'default', allow_mirror_modeling: true, from, to, autouv: 0,
    color, origin, rotation, uuid: id, type: 'cube',
    faces: Object.fromEntries(['north', 'east', 'south', 'west', 'up', 'down'].map(side =>
      [side, { uv: [0, 0, 0, 0], texture: null }])),
  });
  return id;
}

function group(name, origin, children, color = 0, rotation = [0, 0, 0]) {
  return { name, origin, color, uuid: randomUUID(), export: true, mirror_uv: false,
    isOpen: true, locked: false, visibility: true, autouv: 0, rotation, children };
}

function pose(offsetX, label, progress, exposed = false) {
  const skin = 0xD8B08C, fatigue = 0x60704E, vest = 0x4C5940;
  const webbing = 0x7A6548, boots = 0x353A35, weapon = 0x3C4148;
  const lead = Math.sin(progress * Math.PI);
  const smooth = progress * progress * (3 - 2 * progress);
  const crouch = 1 - smooth;
  const bodyY = 11 + 13 * smooth;
  const lean = 8 + 12 * lead;
  const twist = 5 * lead;
  const headTilt = -3.2 * lead;
  const sway = exposed ? 0.9 * Math.sin((progress + 0.25) * Math.PI * 2) : 0;
  const guard = label === 'RETURN GUARD' ? 1 : 0;

  const body = group('Body', [0, bodyY, 0], [
    cube('Torso', [-4, 0, -2], [4, 12, 2], fatigue, [0, 0, 0], [lean, twist, sway]),
    cube('Chest plate', [-3.6, 3.5, 2.01], [3.6, 9.5, 2.55], vest, [0, 0, 0], [lean, twist, sway]),
    cube('Belt', [-4.1, 2, -2.1], [4.1, 3, 2.1], webbing, [0, 0, 0], [lean, twist, sway]),
    group('Head', [0, 12, 0], [
      cube('Head block', [-4, 0, -4], [4, 8, 4], skin, [0, 0, 0], [headTilt, -twist * 0.45, -headTilt]),
      cube('Face marker', [-1.2, 3, 4.01], [1.2, 4.3, 4.35], 0x2A2420, [0, 0, 0], [headTilt, -twist * 0.45, -headTilt]),
    ]),
    group('Arms', [0, 10, 0], [
      cube('Right arm', [-7, -8, -1.5], [-4, 0, 1.5], fatigue, [-5.5, 0, 0], [-80, 0, -6]),
      cube('Left arm', [4, -8, -1.5], [7, 0, 1.5], fatigue, [5.5, 0, 0], [-75, 0, 8]),
      cube('Rifle', [-1.4, -2.3, -10], [1.4, -0.6, 10], weapon, [0, -1.5, 0]),
    ]),
  ]);

  const legs = group('Legs', [0, 0, 0], [
    cube('Right leg', [-4, 0, -1.5], [-0.5, bodyY, 1.5], fatigue, [-2.25, bodyY, 0], [guard * 12, 0, 8 * lead + 5 * crouch]),
    cube('Left leg', [0.5, 0, -1.5], [4, bodyY, 1.5], fatigue, [2.25, bodyY, 0], [-guard * 10, 0, -8 * lead - 5 * crouch]),
    cube('Right boot', [-4.5, 0, -2.5], [-0.5, 2.5, 2.5], boots),
    cube('Left boot', [0.5, 0, -2.5], [4.5, 2.5, 2.5], boots),
  ]);

  const wall = cube('HALF COVER TOP y=12', [-10, 11.8, -12], [10, 12, 2], 0x77706B);
  const result = group('Pose ' + label, [offsetX, 0, 0], [body, legs, wall], 0x7090A0);
  result.rotation = [0, guard ? -14 : 0, 0];
  return result;
}

const model = {
  meta: { format_version: '4.10', model_format: 'java_block', box_uv: false },
  name: "Steve's Army - half-cover peek review",
  model_identifier: 'steves_army_half_cover_peek_review',
  visible_box: [80, 30, 30], variable_placeholders: '', variable_placeholder_buttons: [],
  unhandled_root_fields: {}, resolution: { width: 64, height: 64 }, elements,
  outliner: [
    pose(-24, 'CROUCHED', 0.0),
    pose(0, 'MID PEEK', 0.5),
    pose(24, 'EXPOSED', 1.0, true),
    pose(48, 'RETURN GUARD', 0.5),
  ],
  textures: [],
};

mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify(model, null, 2) + '\n');
console.log(out);

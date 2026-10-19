// Two-segment legs and rigid arms. Preview and runtime JSON share tracks.
import { mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';

const dir = new URL('./review/', import.meta.url);
mkdirSync(dir, { recursive: true });
const elements = [], groups = [], geoBones = [];
// World-space review convention: 16 pixels = 1 block, with no legacy renderer
// scale or crouch translation applied a second time.
const sneak = { width: 9.6, height: 24, eye: 1.27 * 16 };
const lean = 12, leanRad = lean * Math.PI / 180;
const torsoYaw = 24*Math.PI/180, cy=Math.cos(torsoYaw), sy=Math.sin(torsoYaw);
const torsoShiftZ = -0.65;
// Apply forward lean after yaw so the torso still leans toward the target.
const cl=Math.cos(leanRad), sl=Math.sin(leanRad);
const torsoMatrix=[[cy,0,sy],[-sl*sy,cl,sl*cy],[-cl*sy,-sl,cl*cy]];
const mul=(a,b)=>a.map(row=>b[0].map((_,j)=>row.reduce((s,v,k)=>s+v*b[k][j],0)));
const transpose=a=>a[0].map((_,i)=>a.map(row=>row[i]));
const apply=(m,p)=>m.map(row=>row.reduce((s,v,i)=>s+v*p[i],0));
function animationEuler(m) {
  return [-Math.atan2(m[2][1],m[2][2]),-Math.asin(-m[2][0]),Math.atan2(m[1][0],m[0][0])].map(v=>v*180/Math.PI);
}
const bodyRotation=animationEuler(torsoMatrix);
const headCant=6*Math.PI/180;
const headRotation=animationEuler(mul(transpose(torsoMatrix),[[Math.cos(headCant),-Math.sin(headCant),0],[Math.sin(headCant),Math.cos(headCant),0],[0,0,1]]));
const legLength = 6, legHalfDepth = 1.5, rearThighAngle = 30;
const rearGroundOverlap = 0.35;
const frontHipInset = 1.2;
const kneeHipY = legLength*Math.cos(rearThighAngle*Math.PI/180)+legHalfDepth-rearGroundOverlap;
const hipOffsetZ = sneak.width/2-legLength+legLength*Math.sin(rearThighAngle*Math.PI/180);
const restLift = 12-kneeHipY;
const weaponShiftY = restLift-4;
// Tuck the head into the collar and rearward rather than scaling its geometry.
const neckY = 12 + 9.6, neckZ = 0.4;
const neckAtKneel = kneeHipY + (neckY-12)*Math.cos(leanRad)+neckZ*Math.sin(leanRad);
const eyeOffset = sneak.eye - neckAtKneel;
const weaponShiftZ = -2.65;
const weaponShiftX = -2.8;
const rifleCant = -8;
function cantedGrip(p) {
  const pivot = [-0.8+weaponShiftX,22+weaponShiftY,-5+weaponShiftZ];
  const a=rifleCant*Math.PI/180, x=p[0]-pivot[0], y=p[1]-pivot[1];
  return [pivot[0]+x*Math.cos(a)-y*Math.sin(a),pivot[1]+x*Math.sin(a)+y*Math.cos(a),p[2]];
}
const palette = ['#435343', '#b99b75', '#263228', '#293c50', '#24292d', '#8b8c83', '#b8b9ae', '#13191c'];
function crc32(bytes) {
  let c = 0xffffffff;
  for (const b of bytes) {
    c ^= b;
    for (let i = 0; i < 8; i++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0);
  }
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const tag = Buffer.from(type), size = Buffer.alloc(4), crc = Buffer.alloc(4);
  size.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(Buffer.concat([tag, data])));
  return Buffer.concat([size, tag, data, crc]);
}
const raw = Buffer.alloc(64 * (64 * 4 + 1));
for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) {
  const hex = palette[Math.floor(x / 8)].slice(1);
  const n = parseInt(hex, 16), offset = y * 257 + 1 + x * 4;
  raw.set([(n >> 16) & 255, (n >> 8) & 255, n & 255, 255], offset);
}
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(64); ihdr.writeUInt32BE(64, 4); ihdr[8] = 8; ihdr[9] = 6;
const texture = Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
writeFileSync(new URL('gecko_kneel_palette.png', dir), texture);

function bone(name, pivot, parent) {
  const b = {name, uuid: randomUUID(), origin: pivot, rotation: [0,0,0], children: [], visibility: true, export: true};
  groups.push(b);
  if (parent) parent.children.push(b);
  const geo = {name, ...(parent ? {parent: parent.name} : {}), pivot: [-pivot[0],pivot[1],pivot[2]], cubes: []};
  geoBones.push(geo);
  b.geo = geo;
  return b;
}
function box(b, name, from, to, color, origin = b.origin, rotation = [0,0,0]) {
  const uv = [color * 8 + 1, 1, color * 8 + 6, 6];
  const faces = Object.fromEntries(['north','south','east','west','up','down'].map(s => [s, {uv, texture: 0}]));
  const c = {name, uuid: randomUUID(), type: 'cube', from, to, origin, rotation, box_uv: false, faces};
  elements.push(c); b.children.push(c.uuid);
  b.geo.cubes.push({origin: [-to[0],from[1],from[2]], size: to.map((v,i) => v-from[i]),
    pivot: [-origin[0],origin[1],origin[2]], rotation: [-rotation[0],-rotation[1],rotation[2]],
    uv: Object.fromEntries(['north','south','east','west','up','down'].map(s => [s, {uv: [uv[0],uv[1]], uv_size: [5,5]}]))});
}
function segment(b, name, start, end, width, color) {
  const d = end.map((v,i) => v-start[i]);
  const length = Math.hypot(...d);
  // Editor Euler ZYX maps the downward local Y axis onto the requested segment.
  const x = Math.asin(-d[2]/length) * 180 / Math.PI;
  const z = Math.atan2(d[0],-d[1]) * 180 / Math.PI;
  box(b, name, [start[0]-width/2,start[1]-length,start[2]-width/2],
    [start[0]+width/2,start[1],start[2]+width/2], color, start, [x,0,z]);
}
const root = bone('soldier', [0,12,0]);
const body = bone('body', [0,12,0], root);
box(body, 'jacket', [-4,12,-2], [4,23,2], 0);
box(body, 'vest', [-4.1,13.2,-2.4], [4.1,22,2.4], 2);
const head = bone('head', [0,neckY,neckZ], body);
box(head, 'head', [-3.5,neckY,neckZ-3.5], [3.5,neckY+7,neckZ+3.5], 1);
box(head, 'helmet', [-3.7,neckY+5,neckZ-3.7], [3.7,neckY+7.1,neckZ+3.7], 0);
for (const x of [-0.8,1.8]) box(head, x<0?'dominant aiming eye':'other eye',
  [x-0.35,neckY+eyeOffset-0.2,neckZ-3.55], [x+0.35,neckY+eyeOffset+0.2,neckZ-3.5],7);
for (const [side,x] of [['right',-2],['left',2]]) {
  // The raised hip sits slightly inside the lower torso. Segment lengths stay
  // six pixels, and the shin remains a true child of its knee pivot.
  const z = side==='left' ? frontHipInset : 0;
  const thigh = bone(`${side}_thigh`, [x,12,z], root);
  box(thigh, `${side} upper leg`, [x-2,12-legLength,z-legHalfDepth], [x+2,12,z+legHalfDepth], 3);
  const shin = bone(`${side}_shin`, [x,12-legLength,z], thigh);
  box(shin, `${side} lower leg (no foot)`, [x-2,12-2*legLength,z-legHalfDepth], [x+2,12-legLength,z+legHalfDepth], 3);
}
// Adapt Simple Enemy's fixed 12-pixel rigid arms, independent head/torso
// directions and staggered shoulders. Two pixels extend above each shoulder
// pivot, with the hand at ten pixels below it, as on a vanilla-style arm.
for (const [side,localShoulder,hand] of [
  ['right',[-4.8,9.6,0],[-0.8+weaponShiftX,17+restLift,-8+weaponShiftZ]],
  ['left',[4.8,9.6,-0.6],[weaponShiftX,17.7+restLift,-10.8+weaponShiftZ]]
]) {
  // Arms share the translated root with the weapon: shoulder positions already
  // include the constant torso lean, so wrists never inherit an unwanted tilt.
  const shoulder=apply(torsoMatrix,localShoulder); shoulder[1]+=12; shoulder[2]+=torsoShiftZ;
  const target = cantedGrip(hand);
  const delta=target.map((v,i)=>v-shoulder[i]), length=Math.hypot(...delta);
  const axis=delta.map(v=>v/length);
  const wrist=shoulder.map((v,i)=>v+10*axis[i]);
  const top=shoulder.map((v,i)=>v-2*axis[i]);
  const upper = bone(`${side}_arm`, shoulder, root);
  segment(upper, `${side} rigid arm`, top, wrist, 3.4, 0);
  const grip = bone(`${side}_hand`, wrist, upper);
  // Keep the attachment pivot, but the rigid arm already includes the hand.
}
const rifle = bone('rifle', [-0.8,22,-5], root);
rifle.rotation = [0,0,rifleCant];
rifle.geo.rotation = [0,0,rifleCant];
// Ordinary inline stock: placement changes apply to the entire weapon.
box(rifle, 'inline stock', [-1.3,20.7,-6.5], [-0.3,22.3,-2.9], 4);
box(rifle, 'shoulder butt pad', [-1.4,20.4,-3.1], [-0.2,22.5,-2.7], 4);
box(rifle, 'receiver', [-1.6,22,-13], [0,23.5,-6], 4);
box(rifle, 'barrel', [-1.2,22.4,-30], [-0.4,23.2,-12], 7);
box(rifle, 'pistol grip', [-1.45,19.8,-8.8], [-0.15,22,-7.5], 4);
box(rifle, 'magazine', [-1.45,19.4,-10.5], [-0.15,22,-9], 4);
const opticY = sneak.eye + 4;
box(rifle, 'optic mount', [-1.2,23.5,-8.8], [-0.4,opticY,-6.3], 4);
box(rifle, 'optic sight body', [-1.45,opticY-0.55,-9.5], [-0.15,opticY+0.55,-6], 4);
box(rifle, 'ocular lens', [-1.25,opticY-0.35,-6.01], [-0.35,opticY+0.35,-5.97], 7);
// Lift the weapon assembly by the changed root drop; barrel remains at y=18.4.
rifle.origin[1] += weaponShiftY;
rifle.origin[0] += weaponShiftX;
rifle.origin[2] += weaponShiftZ;
rifle.geo.pivot[1] += weaponShiftY;
rifle.geo.pivot[0] -= weaponShiftX;
rifle.geo.pivot[2] += weaponShiftZ;
for (const c of elements.filter(c=>rifle.children.includes(c.uuid))) {
  c.from[1]+=weaponShiftY; c.to[1]+=weaponShiftY;
  c.from[0]+=weaponShiftX; c.to[0]+=weaponShiftX;
  c.from[2]+=weaponShiftZ; c.to[2]+=weaponShiftZ;
}
for (const c of rifle.geo.cubes) {c.origin[0]-=weaponShiftX;c.origin[1]+=weaponShiftY;c.origin[2]+=weaponShiftZ;}
const cover = bone('reference_cover', [0,0,-16]);
box(cover, '16 pixel full block', [-8,0,-28], [8,16,-12], 5);
const floor = bone('reference_floor', [0,0,0]);
box(floor, 'ground', [-18,-0.3,-30], [18,0,14], 6);
const guides = bone('reference_sneak_hitbox_and_eye', [0,0,0]);
const h=sneak.width/2, t=0.035;
for (const x of [-h,h]) for (const z of [-h,h]) box(guides,'sneak hitbox vertical', [x-t,0,z-t],[x+t,sneak.height,z+t],5);
for (const y of [0,sneak.height]) {
  for (const x of [-h,h]) box(guides,'sneak hitbox depth', [x-t,y-t,-h],[x+t,y+t,h],5);
  for (const z of [-h,h]) box(guides,'sneak hitbox width', [-h,y-t,z-t],[h,y+t,z+t],5);
}
box(guides,'1.27 block eye and optic axis',[-0.825,sneak.eye-0.025,-29],[-0.775,sneak.eye+0.025,-4.5],1);

const frontAngle = Math.acos((kneeHipY-legLength)/legLength) * 180/Math.PI;
const frontDistance = legLength*Math.sin(frontAngle*Math.PI/180);
// Bedrock animation X rotations have the opposite sign to editor cube rotations.
const kneel = {soldier: {position: [0,-restLift,hipOffsetZ]}, body:{rotation:bodyRotation}, head:{rotation:headRotation}, left_thigh: {rotation: [-frontAngle,0,0]}, left_shin: {rotation: [frontAngle,0,0]}, right_thigh: {rotation: [-rearThighAngle,0,0]}, right_shin: {rotation: [90+rearThighAngle,0,0]}};
const stand = {soldier: {position: [0,2*legLength-12,-frontDistance+hipOffsetZ]}, body:{rotation:bodyRotation}, head:{rotation:headRotation}, left_thigh: {rotation: [0,0,0]}, left_shin: {rotation: [0,0,0]}, right_thigh: {rotation: [0,0,0]}, right_shin: {rotation: [0,0,0]}};
kneel.body.position = stand.body.position = [0,0,torsoShiftZ];
function transition(start, end) {
  const bones = {};
  for (const [name,channels] of Object.entries(start)) {
    bones[name] = {};
    for (const [channel,values] of Object.entries(channels)) {
      // Smoothstep sampling prevents spline overshoot at the floor/cover.
      bones[name][channel] = {};
      for (let i=0;i<=20;i++) {
        const u=i/20, p=u*u*(3-2*u);
        const lowering = start===stand;
        const q = lowering ? p : 1-p;
        const theta = q*Math.PI/2;
        // Allow the small authored overlap at the rear knee/shin, while the
        // forward shin remains vertical and its bottom stays planted.
        const hipY = legLength*Math.cos(q*rearThighAngle*Math.PI/180)+legLength*Math.cos(theta)+legHalfDepth*Math.sin(theta)-rearGroundOverlap*q;
        const a = Math.acos(Math.min(1,(hipY-legLength)/legLength));
        const constrained = name==='soldier' ? [0,hipY-12,-frontDistance+legLength*Math.sin(a)+hipOffsetZ]
          : name==='left_thigh' ? [-a*180/Math.PI,0,0]
          : name==='left_shin' ? [a*180/Math.PI,0,0]
          : values.map((v,j)=>v+(end[name][channel][j]-v)*p);
        bones[name][channel][(u*1.2).toFixed(3)] = constrained;
      }
    }
  }
  return {loop: 'hold_on_last_frame', animation_length: 1.2, bones};
}
const tracks = {
  'animation.steves_army.cover_kneel_lower': transition(stand,kneel),
  'animation.steves_army.cover_kneel_hold': {loop: true, animation_length: 2, bones: kneel},
  'animation.steves_army.cover_kneel_rise': transition(kneel,stand)
};
const animations = Object.entries(tracks).map(([name,clip]) => {
  const animators = {};
  for (const [boneName,channels] of Object.entries(clip.bones)) {
    const b = groups.find(g=>g.name===boneName), keyframes = [];
    for (const [channel,track] of Object.entries(channels)) {
      const keys = Array.isArray(track) ? {'0.0': track} : track;
      for (const [time,vector] of Object.entries(keys)) keyframes.push({uuid: randomUUID(),channel,time:Number(time),interpolation:'linear',data_points:[{x:vector[0],y:vector[1],z:vector[2]}]});
    }
    animators[b.uuid] = {name:boneName,type:'bone',keyframes};
  }
  return {uuid:randomUUID(),name,loop:clip.loop===true?'loop':'hold',length:clip.animation_length,snapping:100,animators};
});
function clean(b) {return Object.fromEntries(Object.entries(b).filter(([k])=>k!=='geo').map(([k,v])=>[k,k==='children'?v.map(c=>typeof c==='object'?clean(c):c):v]));}
const model = {meta:{format_version:'4.10',model_format:'bedrock',box_uv:false},name:'Gecko kneel joint study',resolution:{width:64,height:64},elements,
  outliner:[root,cover,floor,guides].map(clean),textures:[{name:'gecko_kneel_palette.png',uuid:randomUUID(),source:`data:image/png;base64,${texture.toString('base64')}`,mode:'bitmap',saved:true}],animations};
writeFileSync(new URL('gecko_half_cover_kneel.bbmodel',dir),JSON.stringify(model,null,2)+'\n');
writeFileSync(new URL('gecko_half_cover_kneel.animation.json',dir),JSON.stringify({format_version:'1.8.0',animations:tracks},null,2)+'\n');
writeFileSync(new URL('gecko_half_cover_kneel.geo.json',dir),JSON.stringify({format_version:'1.12.0','minecraft:geometry':[{description:{identifier:'geometry.steves_army.kneel_study',texture_width:64,texture_height:64,visible_bounds_width:5,visible_bounds_height:4,visible_bounds_offset:[0,1,0]},bones:geoBones.filter(b=>!b.name.startsWith('reference_'))}]},null,2)+'\n');
console.log('Wrote connected Blockbench rig, GeckoLib geometry, shared animation tracks, and palette.');

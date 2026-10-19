// Authoring preview: vanilla-sized rigid limbs; proposed motion, not game playback.
import {mkdirSync, writeFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const output = fileURLToPath(new URL('./review/half_cover_peek_animation.bbmodel', import.meta.url));
const elements = [], bones = {};
function box(name, from, to, color) {
  const uuid = randomUUID();
  elements.push({name, uuid, type:'cube', from, to, origin:[0,0,0], rotation:[0,0,0], color,
    faces:Object.fromEntries(['north','south','east','west','up','down'].map(s=>[s,{uv:[0,0,16,16],texture:false}]))});
  return uuid;
}
function bone(name, origin, children) {
  const uuid = randomUUID(); bones[name] = uuid;
  return {name, uuid, origin, rotation:[0,0,0], visibility:true, export:true, children};
}
// World Y up, face towards -Z; each limb keeps the vanilla 4x12x4 dimensions.
const soldier = bone('soldier',[0,0,0],[
  bone('body',[0,12,0],[box('torso',[-4,12,-2],[4,24,2],3)]),
  bone('head',[0,24,0],[box('head',[-4,24,-4],[4,32,4],2),box('face',[-2,27,-4.1],[2,29,-4],7)]),
  bone('right_arm',[-5,22,0],[box('right arm',[-8,12,-2],[-4,24,2],3)]),
  bone('left_arm',[5,22,0],[box('left arm',[4,12,-2],[8,24,2],3)]),
  bone('right_leg',[-2,12,0],[box('right leg',[-4,0,-2],[0,12,2],5)]),
  bone('left_leg',[2,12,0],[box('left leg',[0,0,-2],[4,12,2],5)]),
  bone('weapon',[0,20,-7],[box('aim reference',[-1,19,-17],[1,21,-3],7)])
]);
const animators = {};
// Concealed hold -> 0.4s rise -> aim hold -> 0.3s return -> concealed hold.
const samples = [[0,0],[0.65,0],[0.75,0.16],[0.85,0.5],[0.95,0.84],[1.05,1],
  [1.25,1],[1.65,1],[2.05,1],[2.35,1],[2.45,0.74],[2.55,0.26],[2.65,0],[3.2,0]];
function keys(name, channel, fn) {
  const a = animators[bones[name]] ??= {name,type:'bone',keyframes:[]};
  samples.forEach(([time,p])=>a.keyframes.push({uuid:randomUUID(),channel,time,
    interpolation:'linear',data_points:[Object.fromEntries(fn(p,time).map((v,i)=>[['x','y','z'][i],String(v)]))]}));
}
keys('soldier','position',p=>[0,-7*(1-p),0]);
keys('body','rotation',p=>[24*(1-p)+8*Math.sin(p*Math.PI),0,2*Math.sin(p*Math.PI)]);
keys('head','position',p=>[0,-1.5*(1-p),-3*(1-p)]);
keys('head','rotation',p=>[-8*(1-p),0,-2*Math.sin(p*Math.PI)]);
// Rigid legs rotate about the hips; vertical compensation keeps feet at floor height.
keys('right_leg','rotation',p=>[-55*(1-p),0,0]);
keys('left_leg','rotation',p=>[55*(1-p),0,0]);
for (const leg of ['right_leg','left_leg']) keys(leg,'position',p=>[0,7*(1-p)-12*(1-Math.cos(55*(1-p)*Math.PI/180)),0]);
for (const arm of ['right_arm','left_arm']) {
  keys(arm,'rotation',()=>[-85,arm==='right_arm'?-12:18,arm==='right_arm'?-5:8]);
  keys(arm,'position',p=>[0,-0.5*(1-p),-2*(1-p)]);
}
keys('weapon','rotation',p=>[-8*(1-p),0,0]);
keys('weapon','position',p=>[0,-1*(1-p),-1.5*(1-p)]);
const model = {meta:{format_version:'4.10',model_format:'free',box_uv:false},
  name:'Half cover peek - motion proposal',resolution:{width:64,height:64},elements,
  outliner:[soldier,bone('cover',[0,0,0],[box('one block cover',[-13,0,-24],[13,16,-20],6)]),
    bone('floor',[0,0,0],[box('floor',[-20,-0.4,-27],[20,0,12],0)])],textures:[],
  animations:[{uuid:randomUUID(),name:'half_cover_peek_review',loop:'loop',length:3.2,
    snapping:40,animators}],
  animation_variable_placeholders:''};
mkdirSync(new URL('./review/',import.meta.url),{recursive:true});
writeFileSync(output,JSON.stringify(model,null,2)+'\n');
console.log(output);

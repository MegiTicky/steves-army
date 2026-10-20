// Export the reviewed Blockbench endpoints to the native SoldierModel rig.
// Usage: node tools/export_reviewed_runtime.mjs path/to/review.bbmodel
import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
const input=process.argv[2];
if(!input)throw Error('Pass the approved transition bbmodel');
const raw=readFileSync(input),model=JSON.parse(raw),rad=Math.PI/180;
const add=(a,b)=>a.map((v,i)=>v+b[i]),sub=(a,b)=>a.map((v,i)=>v-b[i]);
const mul=(a,b)=>a.map(row=>b[0].map((_,j)=>row.reduce((s,v,k)=>s+v*b[k][j],0)));
const vec=(a,b)=>a.map(row=>row.reduce((s,v,i)=>s+v*b[i],0));
const tr=a=>a[0].map((_,i)=>a.map(row=>row[i]));
const I=[[1,0,0],[0,1,0],[0,0,1]],C=[[1,0,0],[0,-1,0],[0,0,1]];
function rot([x,y,z]){const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);return mul(mul([[cz,-sz,0],[sz,cz,0],[0,0,1]],[[cy,0,sy],[0,1,0],[-sy,0,cy]]),[[1,0,0],[0,cx,-sx],[0,sx,cx]]);}
function quaternion(m){const x=Math.atan2(m[2][1],m[2][2])/2,y=Math.asin(Math.max(-1,Math.min(1,-m[2][0])))/2,z=Math.atan2(m[1][0],m[0][0])/2;const sx=Math.sin(x),cx=Math.cos(x),sy=Math.sin(y),cy=Math.cos(y),sz=Math.sin(z),cz=Math.cos(z);return [sx*cy*cz-cx*sy*sz,cx*sy*cz+sx*cy*sz,cx*cy*sz-sx*sy*cz,cx*cy*cz+sx*sy*sz];}
const boxes={head:[-4,-8,-4,8,8,8],body:[-4,0,-2,8,12,4],right_arm:[-3.0123,-1.9895,-1.7505,4,12,4],left_arm:[-1,-2,-2,4,12,4],right_leg:[-2,0,-2,4,6,4],left_leg:[-2,0,-2,4,6,4],right_leg_shin:[-2,0,-2,4,6,4],left_leg_shin:[-2,0,-2,4,6,4]};
const nativeP=p=>[p[0],24-p[1],p[2]],nativeR=r=>mul(mul(C,r),C);
function evaluate(prefix,time){
 const clip=model.animations.find(a=>a.name.startsWith(prefix));if(!clip)throw Error(prefix);
 const world=new Map();
 function group(g,parent={p:[0,0,0],r:I,origin:[0,0,0]}){
   const keys=clip.animators[g.uuid]?.keyframes??[];
   const channel=c=>{const list=keys.filter(k=>k.channel===c);const key=list.find(k=>Math.abs(k.time-time)<1e-6)??list.filter(k=>k.time<=time).at(-1);return key?['x','y','z'].map(a=>Number(key.data_points[0][a])):[0,0,0];};
   const p=channel('position').map((v,i)=>i===0?-v:v),r=channel('rotation').map((v,i)=>v*rad*(i<2?-1:1));
   const state={p:add(parent.p,vec(parent.r,add(sub(g.origin,parent.origin),p))),r:mul(parent.r,rot(add((g.rotation??[0,0,0]).map(v=>v*rad),r))),origin:g.origin};
   world.set(g.name,state);for(const child of g.children??[])if(typeof child==='object')group(child,state);
 }
 model.outliner.forEach(g=>group(g));
 const parts={},expected={};
 for(const [name,box]of Object.entries(boxes)){
   const state=world.get(name),g=findGroup(name),cube=model.elements.find(c=>c.uuid===g.children.find(x=>typeof x==='string'));
   const centre=cube.from.map((v,i)=>(v+cube.to[i])/2);
   const wc=add(state.p,vec(state.r,sub(centre,g.origin)));
   const r=nativeR(state.r),localCentre=box.slice(0,3).map((v,i)=>v+box[i+3]/2);
   const origin=sub(nativeP(wc),vec(r,localCentre));
   parts[name]={position:origin,rotation:quaternion(r),centre:localCentre};
   expected[name]=[];for(let mask=0;mask<8;mask++){const p=cube.from.map((v,i)=>mask&(1<<i)?cube.to[i]:v);expected[name].push(add(state.p,vec(state.r,sub(p,g.origin))).map(v=>v/16));}
 }
 for(const name of ['right_leg_shin','left_leg_shin']){
   const parent=world.get(name.replace('_shin','')),child=world.get(name),r=mul(tr(nativeR(parent.r)),nativeR(child.r));
   const parentOrigin=parts[name.replace('_shin','')].position,childOrigin=parts[name].position;
   parts[name].position=vec(tr(nativeR(parent.r)),sub(childOrigin,parentOrigin));parts[name].rotation=quaternion(r);
 }
 const rifle=world.get('reference_rifle_standin');
 return {parts,weaponRotation:quaternion(nativeR(rifle.r)),expected};
}
function findGroup(name){let result;function visit(g){if(g.name===name)result=g;for(const c of g.children??[])if(typeof c==='object')visit(c);}model.outliner.forEach(visit);if(!result)throw Error(name);return result;}
const states={standing_ready:evaluate('01 ',0),standing_lowered:evaluate('08 ',0),crouch:evaluate('09 ',1.2),cover:evaluate('03 ',0)};
// The author adjusted the legs in the FULL cycle's cover hold, rather than
// the original endpoint clips. Use that exact reviewed sample for both planted
// kneeling states, which share their leg placement; retain each upper body.
const reviewedLegs=evaluate('12 ',2.28);
for(const state of [states.crouch,states.cover])for(const name of ['right_leg','left_leg','right_leg_shin','left_leg_shin']){
 state.parts[name]=reviewedLegs.parts[name];state.expected[name]=reviewedLegs.expected[name];
}
const out={sourceSha256:createHash('sha256').update(raw).digest('hex'),states};
const destination=new URL('../src/main/resources/assets/steves_army/pose_data/reviewed_poses.json',import.meta.url);
mkdirSync(new URL('.',destination),{recursive:true});writeFileSync(destination,JSON.stringify(out,null,2)+'\n');
console.log('Exported four reviewed native poses:',destination.pathname);

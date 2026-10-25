import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const source=new URL('./review/reviewed_unarmed_crouch.bbmodel',import.meta.url);
const bytes=readFileSync(source),model=JSON.parse(bytes);
const pages=await(await fetch('http://127.0.0.1:9222/json/list')).json();
const ws=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise(r=>ws.onopen=r);
let id=0;const pending=new Map();ws.onmessage=e=>{let m=JSON.parse(e.data);pending.get(m.id)?.(m);pending.delete(m.id)};
const send=(method,params)=>new Promise(r=>{pending.set(++id,r);ws.send(JSON.stringify({id,method,params}))});
const extract=function(model){
 Codecs.project.load(model,{path:'',no_file:true});Modes.options.animate.select();Timeline.pause();
 Animation.all.forEach(a=>a.playing=false);Animation.all[1].select();Animation.all.forEach((a,i)=>a.playing=i===1);
 const frames=[];
 for(let i=0;i<=40;i++){
  Timeline.setTime(i/40*1.2);Animator.preview();const world={},parts={},expected={};
  for(let g of Group.all){g.mesh.updateMatrixWorld(true);let p=g.mesh.getWorldPosition(new THREE.Vector3()),r=g.mesh.getWorldQuaternion(new THREE.Quaternion());
   // Reflect Y into Java's coordinate system. Keep actual runtime cube pivots.
   p.set(p.x,24-p.y,p.z);r.set(-r.x,r.y,-r.z,r.w);
   let centre=g.name==='right_arm'?[-1.0123,4.0105,.2495]:g.name==='left_arm'?[1,4,0]:g.name==='head'?[0,-4,0]:g.name==='body'?[0,6,0]:[0,3,0];
   if(g.name.endsWith('_arm'))p.add(new THREE.Vector3(0,4,0).sub(new THREE.Vector3(...centre)).applyQuaternion(r));
   world[g.name]={p,r,centre};
   const c=Cube.all.find(c=>c.name===g.name),pos=c.mesh.geometry.attributes.position;let corners=[];c.mesh.updateMatrixWorld(true);
   for(let j=0;j<pos.count;j++){let v=new THREE.Vector3().fromBufferAttribute(pos,j).applyMatrix4(c.mesh.matrixWorld);if(g.name==='right_arm')v.add(new THREE.Vector3(0,0,-.2495).applyQuaternion(g.mesh.getWorldQuaternion(new THREE.Quaternion())));v.divideScalar(16);if(!corners.some(a=>v.distanceTo(new THREE.Vector3(...a))<1e-7))corners.push(v.toArray())}expected[g.name]=corners;
  }
  for(let [n,s] of Object.entries(world)){let p=s.p.clone(),r=s.r.clone();if(n.endsWith('_shin')){let parent=world[n.replace('_shin','')];p.sub(parent.p).applyQuaternion(parent.r.clone().invert());r.premultiply(parent.r.clone().invert())}parts[n]={position:p.toArray(),rotation:r.toArray(),centre:s.centre}}
  frames.push({progress:i/40,parts,expected});
 }
 Animation.all.forEach(a=>a.playing=false);Animation.all[4].select();Animation.all[4].playing=true;Timeline.setTime(2.2);Animator.preview();
 return frames;
};
const response=await send('Runtime.evaluate',{expression:`(${extract.toString()})(${JSON.stringify(model)})`,returnByValue:true});
if(response.result.exceptionDetails)throw Error(JSON.stringify(response.result.exceptionDetails));
const frames=response.result.result.value;
writeFileSync(new URL('../src/main/resources/assets/steves_army/pose_data/unarmed_crouch.json',import.meta.url),JSON.stringify({sourceSha256:createHash('sha256').update(bytes).digest('hex'),duration:1.2,frames},null,2));
console.log(`Exported ${frames.length} actual animated Blockbench samples.`);ws.close();

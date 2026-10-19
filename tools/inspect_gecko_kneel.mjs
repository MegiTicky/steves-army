// Inspect the live Blockbench scene, including exported coordinate conventions.
import { readFileSync, writeFileSync } from 'node:fs';
const targets = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const socket = new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
let id=0;
async function call(method, params) {
  const request=++id;
  return new Promise((resolve,reject)=>{
    const handler=event=>{
      const data=JSON.parse(event.data);
      if(data.id!==request)return;
      socket.removeEventListener('message',handler);
      if(data.error||data.result?.exceptionDetails)reject(new Error(JSON.stringify(data)));
      else resolve(data.result);
    };
    socket.addEventListener('message',handler);
    socket.send(JSON.stringify({id:request,method,params}));
  });
}
const expectedGeo=JSON.parse(readFileSync(new URL('./review/gecko_half_cover_kneel.geo.json',import.meta.url),'utf8'));
const expectedAnim=JSON.parse(readFileSync(new URL('./review/gecko_half_cover_kneel.animation.json',import.meta.url),'utf8'));
try {
  const result=await call('Runtime.evaluate',{returnByValue:true,expression:`(() => {
    Timeline.pause(); Modes.options.animate.select();
    Animation.all.forEach(a=>a.playing=false);
    Animation.all[0].select(); Animation.selected.playing=true;
    const scenePoint=(mesh,p)=>mesh.localToWorld(new THREE.Vector3(...p));
    let minGround=Infinity,maxArmError=0;
    for(let i=0;i<=100;i++) {
      Timeline.setTime(1.2*i/100); Animator.preview();
      Canvas.scene.updateMatrixWorld(true);
      for(const c of Cube.all.filter(c=>/leg/.test(c.name))) minGround=Math.min(minGround,new THREE.Box3().setFromObject(c.mesh).min.y);
      for(const side of ['right','left']) {
        const c=Cube.all.find(c=>c.name===side+' rigid arm');
        c.mesh.geometry.computeBoundingBox();
        const bb=c.mesh.geometry.boundingBox;
        const endpoint=scenePoint(c.mesh,[(bb.min.x+bb.max.x)/2,bb.min.y,(bb.min.z+bb.max.z)/2]);
        const joint=Group.all.find(g=>g.name===side+'_hand').mesh.getWorldPosition(new THREE.Vector3());
        maxArmError=Math.max(maxArmError,endpoint.distanceTo(joint));
      }
    }
    Timeline.setTime(1.2); Animator.preview(); Canvas.scene.updateMatrixWorld(true);
    const receiver=new THREE.Box3().setFromObject(Cube.all.find(c=>c.name==='receiver').mesh);
    const cover=new THREE.Box3().setFromObject(Cube.all.find(c=>c.name==='16 pixel full block').mesh);
    const coreBounds=new THREE.Box3();
    for(const c of Cube.all.filter(c=>/leg|jacket|vest|^head$|helmet/.test(c.name))) coreBounds.union(new THREE.Box3().setFromObject(c.mesh));
    const center=name=>new THREE.Box3().setFromObject(Cube.all.find(c=>c.name===name).mesh).getCenter(new THREE.Vector3());
    const eye=center('dominant aiming eye'),lens=center('ocular lens');
    const legLengths=Object.fromEntries(Cube.all.filter(c=>/upper leg|lower leg/.test(c.name))
      .map(c=>[c.name,c.to[1]-c.from[1]]));
    const armLengths=Object.fromEntries(Cube.all.filter(c=>/rigid arm/.test(c.name))
      .map(c=>[c.name,c.to[1]-c.from[1]]));
    // The authored rear-shin tuck permits 0.35 pixels of ground overlap.
    // Keep reporting the measured overlap instead of treating it as zero.
    const coreFitsSneak=coreBounds.min.y>=-0.351 && coreBounds.max.y<=24.001
      && coreBounds.min.x>=-4.801 && coreBounds.max.x<=4.801 && coreBounds.min.z>=-4.801 && coreBounds.max.z<=4.801;
    return {minGround,maxArmError,rifleClearance:receiver.min.y-cover.max.y,coreFitsSneak,legLengths,armLengths,
      coreBounds:{min:coreBounds.min.toArray(),max:coreBounds.max.toArray()},eye:eye.toArray(),lens:lens.toArray(),
      sightAxisError:Math.hypot(eye.x-lens.x,eye.y-lens.y),
      geo:Codecs.bedrock.compile({raw:true}),animations:AnimationCodec.codecs.bedrock.compileFile(Animation.all)};
  })()`});
  const r=result.result.value;
  if(Object.keys(r.armLengths).length!==2 || Object.values(r.armLengths).some(n=>Math.abs(n-12)>1e-6)) {
    throw new Error('Arm segment length changed: '+JSON.stringify(r.armLengths));
  }
  if(Object.keys(r.legLengths).length!==4 || Object.values(r.legLengths).some(length=>Math.abs(length-6)>1e-6)) {
    throw new Error('Leg segment length changed: '+JSON.stringify(r.legLengths));
  }
  if(r.minGround < -0.4 || r.maxArmError > 0.001 || Math.abs(r.rifleClearance-2)>0.25 || !r.coreFitsSneak
    || r.sightAxisError>4 || Math.abs(r.eye[1]-1.27*16)>1 || r.lens[2]>=r.eye[2]) {
    throw new Error(JSON.stringify({...r,geo:undefined,animations:undefined}));
  }
  const actualBones=r.geo['minecraft:geometry'][0].bones;
  for(const bone of expectedGeo['minecraft:geometry'][0].bones) {
    const actual=actualBones.find(b=>b.name===bone.name);
    if(!actual || JSON.stringify(actual.pivot)!==JSON.stringify(bone.pivot)) throw new Error('Export pivot mismatch: '+bone.name);
    for(let i=0;i<bone.cubes.length;i++) for(const key of ['origin','size','pivot','rotation']) {
      const expected=bone.cubes[i][key],got=actual.cubes[i][key]??(key==='rotation'?[0,0,0]:undefined);
      if(key==='pivot'&&!actual.cubes[i].rotation)continue;
      if(!got || expected.some((v,j)=>Math.abs(v-got[j])>1e-5))throw new Error('Export cube mismatch: '+bone.name+' '+key);
    }
  }
  for(const [name,clip] of Object.entries(expectedAnim.animations)) for(const [bone,channels] of Object.entries(clip.bones)) for(const [channel,keys] of Object.entries(channels)) {
    const got=r.animations.animations[name].bones[bone][channel];
    const normalized=Array.isArray(keys)?{'0':keys}:keys;
    const actual=Array.isArray(got)?{'0':got}:got;
    for(const [time,vector] of Object.entries(normalized)) {
      const key=Object.keys(actual).find(t=>Math.abs(Number(t)-Number(time))<1e-5);
      if(!key || vector.some((v,j)=>Math.abs(v-actual[key][j])>0.001))throw new Error('Animation export mismatch: '+name+' '+bone+' '+time);
    }
  }
  console.log(JSON.stringify({minGround:r.minGround,maxArmJointError:r.maxArmError,rifleClearance:r.rifleClearance,
    coreFitsSneakWithRearOverlap:r.coreFitsSneak,legLengths:r.legLengths,armLengths:r.armLengths,coreBounds:r.coreBounds,eye:r.eye,opticAxis:r.lens,sightAxisError:r.sightAxisError,exportParity:'PASS'},null,2));
  const views={side:[55,16,-6],front:[0,20,-65],threequarter:[44,22,30]};
  for(const [name,position] of Object.entries(views)) {
    await call('Runtime.evaluate',{expression:`(() => { main_preview.controls.target.set(0,13,-6);main_preview.camera.position.set(${position});main_preview.camera.lookAt(0,13,-6);main_preview.render(); })()`});
    const shot=await call('Page.captureScreenshot',{format:'png'});
    writeFileSync(new URL('./review/gecko_kneel_'+name+'.png',import.meta.url),Buffer.from(shot.data,'base64'));
  }
} finally { socket.close(); }

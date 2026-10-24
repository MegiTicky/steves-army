// Adjust only unarmed shoulder positions; keep the approved rotations and legs.
import {readFileSync,writeFileSync} from 'node:fs';
const file=new URL('./review/reviewed_unarmed_crouch.bbmodel',import.meta.url);
const model=JSON.parse(readFileSync(file,'utf8'));
const drop=2-(model.unarmedShoulderDropPixels||0);
const smooth=t=>{t=Math.max(0,Math.min(1,t));return t*t*(3-2*t)};
function weight(name,time){
 if(name.startsWith('01'))return 0;
 if(name.startsWith('02'))return smooth(time/1.2);
 if(name.startsWith('03'))return 1;
 if(name.startsWith('04'))return smooth(1-time/1.2);
 if(time<.6)return 0;if(time<1.8)return smooth((time-.6)/1.2);
 if(time<=2.8)return 1;if(time<4)return smooth((4-time)/1.2);return 0;
}
for(const animation of model.animations){
 const body=Object.values(animation.animators).find(a=>a.name==='body');
 for(const arm of Object.values(animation.animators).filter(a=>a.name.endsWith('_arm'))){
  for(const key of arm.keyframes.filter(k=>k.channel==='position')){
   const rotation=body.keyframes.find(k=>k.channel==='rotation'&&Math.abs(k.time-key.time)<1e-6).data_points[0];
   const x=Number(rotation.x)*Math.PI/180,y=Number(rotation.y)*Math.PI/180,z=Number(rotation.z)*Math.PI/180;
   // ZYX rotation applied to the torso-local downward vector (0,-drop,0).
   const w=weight(animation.name,key.time),d=drop*w;
   const offset=[d*(Math.sin(z)*Math.cos(x)-Math.cos(z)*Math.sin(y)*Math.sin(x)),
    -d*(Math.cos(z)*Math.cos(x)+Math.sin(z)*Math.sin(y)*Math.sin(x)),
    -d*Math.cos(y)*Math.sin(x)];
   for(let i=0;i<3;i++){let axis=['x','y','z'][i];key.data_points[0][axis]=Number(key.data_points[0][axis])+offset[i]}
  }
 }
}
model.unarmedShoulderDropPixels=2;
writeFileSync(file,JSON.stringify(model,null,2));
console.log('Unarmed crouch shoulders lowered 2px along the torso; standing unchanged.');

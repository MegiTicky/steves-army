package com.stevesarmy.debug.trace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/** Self-contained HTML and text presentations for exported flight recorder bundles. */
public final class TracePresentation {
    private TracePresentation() {}

    public static String html(JsonObject bundle) {
        String json = bundle.toString()
                .replace("</", "<\\/")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
        return """
                <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Soldier flight trace</title><style>
                *{box-sizing:border-box}body{margin:0;background:#171a1d;color:#e6e9eb;font:13px system-ui, sans-serif;display:grid;grid-template-rows:auto 1fr auto;height:100vh}header,footer{display:flex;align-items:center;gap:10px;padding:8px 12px;background:#24282c;border-bottom:1px solid #3b4146}header{flex-wrap:wrap}select,button,input{font:inherit;color:inherit;background:#30363b;border:1px solid #505960;border-radius:3px;padding:5px}button{cursor:pointer}button:hover{background:#414950}.layout{min-height:0;display:grid;grid-template-columns:minmax(0,1fr) 360px}#map{width:100%;height:100%;background:#121517;display:block;touch-action:none}.side{overflow:auto;border-left:1px solid #3b4146;padding:10px}.row{display:flex;gap:8px;align-items:center}.row input[type=range]{flex:1}.muted{color:#a8b0b6}.event{border-top:1px solid #3b4146;padding:6px 0;cursor:pointer}.event:hover{background:#272d31}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#101315;padding:8px;border-radius:3px}.badge{padding:2px 5px;border-radius:2px;background:#46535a;color:#fff}.warn{color:#ffd078}h3{font-size:14px;margin:12px 0 5px}footer{border-top:1px solid #3b4146;border-bottom:0}.legend{display:flex;gap:12px;flex-wrap:wrap}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:4px}@media(max-width:800px){body{height:100dvh}.layout{grid-template-columns:1fr;grid-template-rows:minmax(45vh,1fr) 42vh}.side{border-left:0;border-top:1px solid #3b4146}}
                </style></head><body><header><strong>Soldier flight trace</strong><select id="actor"></select><button id="play" title="Play or pause">▶</button><input id="scrub" type="range" min="0" max="0" value="0"><span id="clock" class="muted"></span><label><input id="terrain" type="checkbox" checked> terrain</label><select id="tmode" title="Terrain display mode"><option value="surface" selected>surface map</option><option value="layer">Y layer</option></select><label>Y <input id="level" type="number" step="1" style="width:62px"></label><span id="state" class="badge"></span></header>
                <div class="layout"><canvas id="map"></canvas><aside class="side"><h3>Frame snapshot</h3><pre id="snapshot"></pre><h3>Events</h3><div id="events"></div><h3>Heuristic notes</h3><div id="notes"></div></aside></div><footer><span class="legend"><span><i class="dot" style="background:#65c6ff"></i>position</span><span><i class="dot" style="background:#ffbd59"></i>target</span><span><i class="dot" style="background:#ce83ff"></i>cover</span><span><i class="dot" style="background:#6bd59a"></i>path</span></span><span class="muted">Drag to pan; wheel to zoom. Terrain: vanilla-map surface, or one Y layer.</span></footer>
                <script id="bundle" type="application/json">TRACE_BUNDLE_JSON</script><script>
                const B=JSON.parse(document.getElementById('bundle').textContent), frames=B.frames||[], events=B.events||[], actors=B.actors||[];
                const $=s=>document.querySelector(s), actor=$('#actor'), scrub=$('#scrub'), canvas=$('#map'), ctx=canvas.getContext('2d');
                const byId=new Map(actors.map(a=>[a.id,a])), groups=new Map(); for(const f of frames){if(!groups.has(f.soldierId))groups.set(f.soldierId,[]);groups.get(f.soldierId).push(f)}
                for(const [id,fs] of groups){const a=byId.get(id)||{};const o=document.createElement('option');o.value=id;o.textContent=(a.name||id)+' ('+(a.type||'soldier')+')';actor.append(o)}
                if(B.selectedId&&groups.has(B.selectedId))actor.value=B.selectedId;
                function configureTimeline(){const fs=selected();scrub.min=fs.length?Number(fs[0].timeMs||0):0;scrub.max=fs.length?Number(fs[fs.length-1].timeMs||0):0;scrub.step='any';if(fs.length){let minX=Infinity,maxX=-Infinity,minZ=Infinity,maxZ=-Infinity;for(const f of fs){const p=V(f.position);minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);minZ=Math.min(minZ,p[2]);maxZ=Math.max(maxZ,p[2])}const dx=Math.max(8,maxX-minX),dz=Math.max(8,maxZ-minZ);pan.x=(minX+maxX)/2;pan.z=(minZ+maxZ)/2;scale=Math.max(1,Math.min(20,(canvas.clientWidth-48)/dx,(canvas.clientHeight-48)/dz))}}
                let idx=0,playing=false, timer=null, scale=5, pan={x:0,z:0}, drag=null, current=null, playToken=0;
                const V=v=>Array.isArray(v)?v.map(Number):[0,0,0], fmt=o=>JSON.stringify(o,null,2);
                function selected(){return groups.get(actor.value)||[]}
                function resize(){const r=canvas.getBoundingClientRect(),d=devicePixelRatio||1;canvas.width=Math.max(1,r.width*d);canvas.height=Math.max(1,r.height*d);ctx.setTransform(d,0,0,d,0,0);draw()}
                function frame(){const fs=selected();return fs[Math.max(0,Math.min(idx,fs.length-1))]||null}
                function setFrame(n){const fs=selected();idx=Math.max(0,Math.min(n,fs.length-1));current=frame();if(!current)return;scrub.value=Number(current.timeMs||0);$('#clock').textContent='t='+(current.timeMs||0).toFixed(0)+' ms · tick '+current.tick;$('#state').textContent=[current.state?.coverState||current.state?.full||'—',current.movement?.policy?.mode,current.movement?.policy?.fireMode].filter(Boolean).join(' · ');$('#level').value=Math.floor(V(current.position)[1]);$('#snapshot').textContent=fmt(current);showEvents();draw()}
                function setTime(time){const fs=selected();let lo=0,hi=fs.length;while(lo<hi){const mid=(lo+hi)>>1;if(Number(fs[mid].timeMs||0)<=time)lo=mid+1;else hi=mid}setFrame(Math.max(0,lo-1))}
                function atOrBeforeEvent(e,f){const es=Number(e.sequence),fs=Number(f.sequence);return Number.isFinite(es)&&Number.isFinite(fs)?es<=fs:Number(e.tick||0)<=Number(f.tick||0)}
                function nearestPath(f){const e=events.filter(x=>x.soldierId===f.soldierId&&x.category==='navigation-path'&&atOrBeforeEvent(x,f)&&Number(x.data?.pathId)===Number(f.navigation?.pathId)).at(-1);return e?.data?.path||[]}
                function terrain(){return B.terrain||{}}
                let surfaceCache=null;
                // Topmost non-air block per column, pre-shaded with the vanilla +z
                // scanline relief rule (LOW/NORMAL/HIGH), for the filled-map view.
                function surfaceMap(t){
                  if(surfaceCache)return surfaceCache;
                  const m=new Map(),pal=t.palette||[],pc=t.paletteColors||[];
                  const air=new Set();for(let i=0;i<pal.length;i++)if(/minecraft:(air|cave_air|void_air)(\\[|$)/.test(pal[i]))air.add(i);
                  for(const sec of (t.sections||[])){const bx=sec.x*16,by=sec.y*16,bz=sec.z*16,arr=sec.indices||[];
                    for(let i=0;i<arr.length;i++){const pid=Number(arr[i]);
                      if(air.has(pid))continue;
                      const x=bx+(i&15),z=bz+((i>>4)&15),y=by+((i>>8)&15);
                      const k=x+'|'+z,prev=m.get(k);
                      if(!prev||y>prev[0])m.set(k,[y,pid,null])}}
                  for(const [k,c] of m){const sep=k.indexOf('|'),x=+k.slice(0,sep),z=+k.slice(sep+1);
                    const below=m.get(x+'|'+(z-1));
                    const d=(c[0]-(below?below[0]:0))*0.8+((x+z)&1?0.2:-0.2);
                    const rgb=pc[c[1]];
                    c[2]=rgb?shadeCss(rgb,d>0.6?2:d<-0.6?0:1):null}
                  surfaceCache=m;return m;
                }
                function shadeCss(rgb,shade){const m=[180,220,255,135][shade];const c=s=>Math.round((rgb>>s&255)*m/255);return 'rgb('+c(16)+','+c(8)+','+c(0)+')'}
                function blockCss(t,pid,name){const rgb=(t.paletteColors||[])[pid];return rgb?shadeCss(rgb,1):paletteColor(name)}
                function draw(){if(!ctx||!canvas.width)return;const w=canvas.clientWidth,h=canvas.clientHeight;ctx.clearRect(0,0,w,h);ctx.fillStyle='#121517';ctx.fillRect(0,0,w,h);const fs=selected();if(!fs.length)return;const f=current||fs[0],p=V(f.position),cy=Number($('#level').value||p[1]);const sx=x=>w/2+(x-pan.x)*scale, sz=z=>h/2+(z-pan.z)*scale;
                  ctx.strokeStyle='#272d31';ctx.lineWidth=1;for(let x=Math.floor(pan.x/16)*16-128;x<pan.x+128;x+=16){ctx.beginPath();ctx.moveTo(sx(x),0);ctx.lineTo(sx(x),h);ctx.stroke()}for(let z=Math.floor(pan.z/16)*16-128;z<pan.z+128;z+=16){ctx.beginPath();ctx.moveTo(0,sz(z));ctx.lineTo(w,sz(z));ctx.stroke()}
                  const t=terrain();if($('#terrain').checked&&t.sections){
                    if($('#tmode').value!=='layer'&&(t.paletteColors||[]).length){
                      const surf=surfaceMap(t),vw=canvas.clientWidth,vh=canvas.clientHeight;
                      for(const [k,c] of surf){
                        if(!c[2])continue;
                        const sep=k.indexOf('|'),x=+k.slice(0,sep),z=+k.slice(sep+1);
                        const X=sx(x),Z=sz(z);
                        if(X<-scale||Z<-scale||X>vw||Z>vh)continue;
                        ctx.fillStyle=c[2];ctx.fillRect(X,Z,Math.max(1,scale),Math.max(1,scale));
                      }
                    } else for(const sec of t.sections){const base=[Number(sec.x)*16,Number(sec.y)*16,Number(sec.z)*16];if(cy<base[1]||cy>=base[1]+16)continue;const arr=sec.indices||[];for(let i=0;i<arr.length;i++){const lx=i&15,lz=(i>>4)&15,ly=(i>>8)&15,block=(t.palette||[])[Number(arr[i])]||'';if(base[1]+ly!==cy||/minecraft:(air|cave_air|void_air)(\\[|$)/.test(block))continue;ctx.fillStyle=blockCss(t,Number(arr[i]),block);ctx.fillRect(sx(base[0]+lx),sz(base[2]+lz),Math.max(1,scale),Math.max(1,scale))}}}
                  ctx.strokeStyle='#6bd59a';ctx.lineWidth=2;const path=nearestPath(f);if(path.length){ctx.beginPath();path.forEach((v,i)=>i?ctx.lineTo(sx(v[0]),sz(v[2])):ctx.moveTo(sx(v[0]),sz(v[2])));ctx.stroke();ctx.fillStyle='#6bd59a';path.forEach((v,i)=>{if(i===Number(f.navigation?.nextNodeIndex)){ctx.beginPath();ctx.arc(sx(v[0]),sz(v[2]),4,0,Math.PI*2);ctx.fill()}})}
                  ctx.strokeStyle='#75909f';ctx.lineWidth=1;const tail=fs.slice(0,fs.indexOf(f)+1);ctx.beginPath();tail.forEach((q,i)=>i?ctx.lineTo(sx(V(q.position)[0]),sz(V(q.position)[2])):ctx.moveTo(sx(V(q.position)[0]),sz(V(q.position)[2])));ctx.stroke();
                  const selectedTime=Number(f.timeMs||0);for(const [id,otherFrames] of groups){if(id===f.soldierId)continue;let lo=0,hi=otherFrames.length;while(lo<hi){const mid=(lo+hi)>>1;if(Number(otherFrames[mid].timeMs||0)<=selectedTime)lo=mid+1;else hi=mid}const other=otherFrames[lo-1];if(!other||selectedTime-Number(other.timeMs||0)>2000||!Array.isArray(other.position))continue;const q=V(other.position),alpha=Math.max(.16,.48-Math.abs(q[1]-cy)*.035);ctx.globalAlpha=alpha;ctx.fillStyle='#c0c8cc';ctx.beginPath();ctx.arc(sx(q[0]),sz(q[2]),4,0,Math.PI*2);ctx.fill()}ctx.globalAlpha=1;
                  const target=f.movement?.target||f.navigation?.target;if(Array.isArray(target)){ctx.fillStyle='#ffbd59';ctx.beginPath();ctx.arc(sx(target[0]),sz(target[2]),5,0,Math.PI*2);ctx.fill()}const cover=f.state?.currentCover||f.state?.targetCover;if(Array.isArray(cover)){ctx.fillStyle='#ce83ff';ctx.fillRect(sx(cover[0])-4,sz(cover[2])-4,8,8)}ctx.fillStyle='#65c6ff';ctx.beginPath();ctx.arc(sx(p[0]),sz(p[2]),6,0,Math.PI*2);ctx.fill();ctx.strokeStyle='#fff';ctx.stroke();ctx.fillStyle='#dce4e8';ctx.fillText('Y '+p[1].toFixed(1),sx(p[0])+9,sz(p[2])-8);
                }
                function paletteColor(s){let h=2166136261;for(let i=0;i<s.length;i++)h=Math.imul(h^s.charCodeAt(i),16777619);return 'hsl('+(h>>>0)%360+' 25% 40%)'}
                function showEvents(){const f=current;if(!f)return;const es=events.filter(e=>e.soldierId===f.soldierId&&atOrBeforeEvent(e,f)).slice(-80).reverse(),root=$('#events');root.replaceChildren();for(const e of es){const d=document.createElement('div');d.className='event';d.onclick=()=>setTime(Number(e.timeMs||0));const ts=document.createElement('span');ts.className='muted';ts.textContent=(e.timeMs||0).toFixed(0)+'ms · ';const title=document.createElement('b');title.textContent=(e.category||'event')+' / '+(e.reason||'');const body=document.createElement('pre');body.textContent=fmt(e.data||{});d.append(ts,title,body);root.append(d)}}
                function analyze(){const fs=selected(),notes=[];let start=0;for(let i=1;i<=fs.length;i++){const a=fs[start],b=fs[i];if(i===fs.length||b?.state?.coverState!==a?.state?.coverState||b?.state?.peekState!==a?.state?.peekState){if(a&&i-start>=20)notes.push({state:a.state?.coverState||'unknown',duration:(fs[i-1].timeMs-a.timeMs),frames:i-start});start=i}}const fails=events.filter(e=>e.soldierId===actor.value&&/fail|blocked|unreachable|stuck/i.test((e.reason||'')+' '+(e.category||'')));if(fails.length>3)notes.push({warning:'Repeated movement or navigation failure signals; inspect event evidence',count:fails.length});const root=$('#notes');root.replaceChildren();for(const finding of (Array.isArray(B.findings)?B.findings:[])){const d=document.createElement('div');d.className='event warn';d.textContent=typeof finding==='string'?finding:fmt(finding);root.append(d)}for(const n of notes.slice(0,100)){const d=document.createElement('div');d.className='event '+(n.warning?'warn':'');d.textContent=n.warning?n.warning+' ('+n.count+')':`State hold ${n.state}: ${(n.duration/1000).toFixed(1)}s, ${n.frames} frames`;root.append(d)}if(!root.childElementCount)root.textContent='No heuristic flags. Inspect the complete frame and event timeline.'}
                function scheduleNext(token){if(!playing||token!==playToken)return;const fs=selected();if(idx>=fs.length-1){playing=false;$('#play').textContent='▶';return}const a=Number(fs[idx].timeMs||0),b=Number(fs[idx+1].timeMs||a);timer=setTimeout(()=>{if(token!==playToken)return;setFrame(idx+1);scheduleNext(token)},Math.max(1,b-a))}
                $('#play').onclick=()=>{playing=!playing;$('#play').textContent=playing?'❚❚':'▶';playToken++;clearTimeout(timer);if(playing)scheduleNext(playToken)};
                scrub.oninput=()=>setTime(Number(scrub.value));actor.onchange=()=>{idx=0;$('#notes').replaceChildren();configureTimeline();analyze();setFrame(0)};$('#terrain').onchange=draw;$('#tmode').onchange=draw;$('#level').oninput=draw;
                canvas.onpointerdown=e=>{drag={x:e.clientX,z:e.clientY,px:pan.x,pz:pan.z};canvas.setPointerCapture(e.pointerId)};canvas.onpointermove=e=>{if(drag){pan.x=drag.px-(e.clientX-drag.x)/scale;pan.z=drag.pz-(e.clientY-drag.z)/scale;draw()}};canvas.onpointerup=()=>drag=null;canvas.onwheel=e=>{e.preventDefault();scale=Math.max(1,Math.min(40,scale*(e.deltaY<0?1.15:.87)));draw()};
                addEventListener('resize',resize);configureTimeline();analyze();resize();setFrame(0);
                </script></body></html>
                """.replace("TRACE_BUNDLE_JSON", json);
    }

    public static String summary(JsonObject bundle) {
        JsonObject meta = object(bundle, "metadata");
        JsonArray frames = array(bundle, "frames");
        JsonArray events = array(bundle, "events");
        String selected = string(bundle, "selectedId", "unknown");
        StringBuilder out = new StringBuilder("Soldier flight recorder summary\n")
                .append("Selected actor: ").append(selected).append('\n')
                .append("Version: ").append(string(meta, "modVersion", "unknown"))
                .append(" | window: ").append(number(meta, "coverageSeconds", 0)).append(" s")
                .append(" | history cap: ").append(number(meta, "historySeconds", 0)).append(" s")
                .append(" | sample interval: ").append(number(meta, "sampleTicks", 0)).append(" ticks\n")
                .append("Frames: ").append(frames.size()).append(" | events: ").append(events.size())
                .append(" | truncated: ").append(bool(meta, "truncated")).append('\n');
        out.append("\nActor registry\n");
        for (JsonElement e : array(bundle, "actors")) if (e.isJsonObject()) {
            JsonObject a = e.getAsJsonObject();
            out.append("- ").append(string(a, "name", "unknown")).append(" [")
                    .append(string(a, "id", "?")).append("] type=").append(string(a, "type", "?"))
                    .append(" dimension=").append(string(a, "dimension", "?"))
                    .append(" owner=").append(string(a, "ownerId", "none"))
                    .append(" squad=").append(string(a, "squadId", "none")).append('\n');
        }
        out.append("\nHeuristic review flags (signals only; not root-cause diagnoses)\n");
        JsonArray findings = array(bundle, "findings");
        if (!findings.isEmpty()) {
            out.append("Analyzer findings\n");
            for (JsonElement finding : findings) out.append("- ").append(safeText(finding)).append('\n');
        }
        int selectedFrames = 0;
        int repeats = 0;
        int failures = 0;
        double maxStationary = 0;
        double maxStateHold = 0;
        double stateStart = 0;
        String stateKey = null;
        JsonObject prior = null;
        double stationaryStart = 0;
        for (JsonElement e : frames) if (e.isJsonObject()) {
            JsonObject f = e.getAsJsonObject();
            if (!string(f, "soldierId", "").equals(selected)) continue;
            selectedFrames++;
            double tm = number(f, "timeMs", 0);
            JsonObject state = object(f, "state");
            String nextStateKey = string(state, "full", "") + "/" + string(state, "coverState", "") + "/" + string(state, "peekState", "");
            if (stateKey != null && stateKey.equals(nextStateKey)) {
                maxStateHold = Math.max(maxStateHold, tm - stateStart);
            } else {
                stateKey = nextStateKey;
                stateStart = tm;
            }
            JsonObject move = object(f, "movement");
            String result = string(move, "result", "");
            String reason = string(move, "failure", "") + " " + string(move, "reason", "");
            if (containsFailure(result + " " + reason)) failures++;
            if (prior != null && sameTarget(object(prior, "movement").get("target"), move.get("target"))) {
                if (stationaryStart == 0) stationaryStart = number(prior, "timeMs", tm);
                if (tm - stationaryStart > maxStationary) maxStationary = tm - stationaryStart;
            } else stationaryStart = tm;
            prior = f;
        }
        for (JsonElement e : events) if (e.isJsonObject()) {
            JsonObject event = e.getAsJsonObject();
            if (!string(event, "soldierId", "").equals(selected)) continue;
            repeats += (int) number(event, "repeat", 1);
            if (containsFailure(string(event, "category", "") + " " + string(event, "reason", ""))) failures++;
        }
        out.append("- Selected actor frames: ").append(selectedFrames).append('\n')
                .append("- Repeated movement/navigation failure signals: ").append(failures).append('\n')
                .append("- Event repeat count (including coalesced repeats): ").append(repeats).append('\n')
                .append("- Longest state segment observed: ").append(String.format(Locale.ROOT, "%.2f", maxStateHold / 1000)).append(" s.\n")
                .append("- Longest same-target interval observed: ").append(String.format(Locale.ROOT, "%.2f", maxStationary / 1000)).append(" s; this does not establish that the actor was stationary.\n")
                .append("Review state transitions, position/velocity, target changes, path indices, cover fields, and nearby events together. A long hold or repeated failure can be expected in combat, unloaded terrain, or a blocked scenario.\n");
        out.append("\nManual simulator reconstruction checklist\n")
                .append("1. Record Minecraft version, mod version, dimension, difficulty, gamerules, loaded mods, and server/client environment.\n")
                .append("2. Recreate the terrain and structures visible in the viewer (vanilla-map surface view or a captured Y layer); note that only loaded terrain within the export radius was sampled and unloaded chunks are listed separately.\n")
                .append("3. Match actor equipment, health, ownership, squad membership, and the event sequence immediately before the interval of interest.\n")
                .append("4. Replay the same command or ping and observe each relevant tick: goal/state, movement source and result, navigation target/path/index, cover claim/peek, position, and velocity.\n")
                .append("5. Repeat with one controlled change at a time and compare the same timestamp-relative interval. Flight recorder heuristics are leads, not proof of cause.\n");
        JsonArray limitations = array(meta, "limitations");
        if (!limitations.isEmpty()) {
            out.append("\nExport limitations\n");
            for (JsonElement limitation : limitations) out.append("- ").append(safeText(limitation)).append('\n');
        }
        return out.toString();
    }

    private static boolean sameTarget(JsonElement a, JsonElement b) { return a == null ? b == null : a.equals(b); }
    private static boolean containsFailure(String s) { return s.toLowerCase(Locale.ROOT).matches(".*(fail|blocked|unreachable|stuck|timeout).*"); }
    private static String safeText(JsonElement e) { return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString(); }
    private static JsonObject object(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject o, String key, String fallback) { return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback; }
    private static double number(JsonObject o, String key, double fallback) { try { return o != null && o.has(key) ? o.get(key).getAsDouble() : fallback; } catch (RuntimeException ignored) { return fallback; } }
    private static boolean bool(JsonObject o, String key) { return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean(); }
}

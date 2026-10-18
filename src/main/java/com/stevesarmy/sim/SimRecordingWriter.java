package com.stevesarmy.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Emits a self-contained HTML animation player per scenario run: top-down
 * terrain, soldiers colored by cover state, suppression arcs, LoS lines,
 * enemy positions, event markers, and a click-to-inspect panel. Playable and
 * scrubable in any browser — no codecs, no server.
 */
public final class SimRecordingWriter {
    private SimRecordingWriter() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void write(SimRunResult result, SimRecorder recorder) {
        String stamp = LocalDateTime.now().format(STAMP);
        String base = result.scenario.name + "_" + stamp;
        String json = recorder.toJson(result.scenario.name, result.failed() ? "FAIL" : "PASS", result.violations);
        try {
            Path dir = SimReportWriter.reportDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(base + "_sim.html"),
                render(result.scenario.name, result.failed() ? "FAIL" : "PASS", json), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(base + "_recording.json"), json, StandardCharsets.UTF_8);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim] recording: {}", dir.resolve(base + "_sim.html").toAbsolutePath());
        } catch (IOException e) {
            com.stevesarmy.StevesArmyMod.LOGGER.error("[Sim] failed to write recording: {}", e.toString());
        }
    }

    private static String render(String scenario, String outcome, String dataJson) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><title>stevesarmy sim: __TITLE__</title>
            <style>
             body{background:#141414;color:#ddd;font-family:'Segoe UI',Consolas,monospace;margin:14px}
             h3{margin:0 0 8px 0}
             #wrap{display:flex;gap:18px;align-items:flex-start}
             canvas{background:#000;border:1px solid #444;cursor:crosshair}
             .side{max-width:420px;font-size:13px;line-height:1.5}
             button,select{background:#2c2c2c;color:#eee;border:1px solid #555;padding:3px 10px;margin-right:6px}
             #scrub{width:420px;margin-top:8px}
             table.legend{border-collapse:collapse;margin-top:6px}
             table.legend td{padding:2px 10px 2px 0;font-size:12px}
             .dot{display:inline-block;width:11px;height:11px;border-radius:50%;vertical-align:middle}
             .sq{display:inline-block;width:11px;height:11px;background:#ff3030;vertical-align:middle}
             #detail{white-space:pre;margin-top:12px;color:#b8d8b8;font-size:12px;background:#1c1c1c;
                     border:1px solid #333;padding:8px;min-height:170px}
             #evlog{color:#ffcc66;font-size:12px;margin-top:4px;height:54px;overflow-y:auto;
                    background:#1a1a1a;border:1px solid #333;padding:4px 6px}
             #vline{color:#ff7b7b;font-size:12px;margin-top:4px}
             .outcome-FAIL{color:#ff7b7b} .outcome-PASS{color:#7bd97b}
            </style></head><body>
            <h3><span id="title"></span> &mdash; outcome <span id="outcome" class="outcome"></span></h3>
            <div id="wrap">
             <div>
              <canvas id="cv"></canvas>
              <div style="margin-top:6px">
               <button id="play">&#9654;</button>
               <select id="speed"><option>0.5x</option><option selected>1x</option><option>4x</option><option>10x</option></select>
               tick <span id="tickLabel">0</span> <span style="color:#666">(1x = real time)</span>
              </div>
              <input type="range" id="scrub" min="0" max="0" value="0">
              <div id="evlog" title="scripted events (append-only)"></div>
              <div id="vline"></div>
             </div>
             <div class="side">
              <b>legend</b>
              <table class="legend">
               <tr><td><span class="dot" style="background:#ff4d4d"></span></td><td>NO_COVER</td>
                   <td><span class="dot" style="background:#ffaa33"></span></td><td>SEEKING_COVER</td></tr>
               <tr><td><span class="dot" style="background:#4dff88"></span></td><td>IN_COVER</td>
                   <td><span class="dot" style="background:#4db8ff"></span></td><td>SUPPRESSED_IN_COVER</td></tr>
               <tr><td><span class="dot" style="background:#ffff4d"></span></td><td>REPOSITIONING</td>
                   <td><span class="sq"></span></td><td>enemy</td></tr>
               <tr><td><span class="dot" style="background:#000;border:2px solid #fff;width:7px;height:7px"></span></td><td>pinned</td>
                   <td><span class="dot" style="background:transparent;border:2px solid rgba(255,60,60,.9)"></span></td><td>suppression arc</td></tr>
               <tr><td><span style="color:#0ff">&mdash;</span></td><td>cover target</td>
                   <td><span style="color:#fff">&mdash;</span></td><td>line of sight</td></tr>
               <tr><td><span class="sq" style="background:#9c8a4d"></span></td><td>target: half cover</td>
                   <td><span class="sq" style="background:#cfd8e6"></span></td><td>target: full cover</td></tr>
               <tr><td colspan="4" style="color:#888">terrain = vanilla map colors, +z relief shading</td></tr>
               <tr><td><span style="color:#ffe066">&mdash;</span></td><td>shot (dot = impact)</td>
                   <td><span style="display:inline-block;width:14px;height:14px;background:rgba(255,60,40,.35)"></span></td><td>danger (baked map)</td></tr>
               <tr><td><span style="display:inline-block;width:14px;height:14px;background:#4dff88;border-radius:50%"></span></td><td>standing</td>
                   <td><span style="display:inline-block;width:9px;height:9px;background:#4dff88;border-radius:50%"></span></td><td>crouched</td></tr>
               <tr><td><span style="display:inline-block;width:16px;height:7px;background:#4dff88;border-radius:50%"></span></td><td>prone</td>
                   <td><span style="display:inline-block;width:12px;height:12px;border:1px dashed #fff;border-radius:50%"></span></td><td>rising</td></tr>
              </table>
              <div id="detail">click a soldier to inspect</div>
             </div>
            </div>
            <script>
            const D = __DATA__;
            const CS = 10;
            const cv = document.getElementById('cv');
            const W = D.terrain.w + 3, H = D.terrain.d + 3;
            cv.width = W * CS; cv.height = H * CS;
            const ctx = cv.getContext('2d');
            document.getElementById('title').textContent = 'stevesarmy sim: ' + D.scenario;
            const outcomeEl = document.getElementById('outcome');
            outcomeEl.textContent = D.outcome;
            outcomeEl.className = 'outcome outcome-' + D.outcome;
            const px = v => (v + 1) * CS;

            // --- terrain layer -----------------------------------------------------
            const terr = document.createElement('canvas');
            terr.width = cv.width; terr.height = cv.height;
            {
              const tc = terr.getContext('2d');
              // Legacy height-coded terrain keeps cover blocks visually distinct in
              // the simulation animation. Recording/export viewers retain their
              // separate vanilla-map renderer.
              const rimShades = ['#242424', '#808080', '#a2a2a2', '#c6c6c6'];
              const HALF = '#9c8a4d', FULL = '#cfd8e6';
              for (let ix = 0; ix < W; ix++) for (let iz = 0; iz < H; iz++) {
                const h = D.terrain.cells[ix * H + iz] || 0;
                const rim = ix === 0 || ix === W - 1 || iz === 0 || iz === H - 1;
                let fill;
                if (h === 0) fill = '#242424';
                else if (rim) fill = rimShades[Math.min(h, 3)];
                else fill = h === 1 ? HALF : FULL;
                tc.fillStyle = fill;
                tc.fillRect(ix * CS, iz * CS, CS, CS);
                if (h > 0) { tc.strokeStyle = 'rgba(0,0,0,.55)'; tc.strokeRect(ix*CS+.5, iz*CS+.5, CS-1, CS-1); }
              }
            }

            // --- danger overlay layer (static, from the LoS oracle) ---------------
            const dangerCv = document.createElement('canvas');
            dangerCv.width = cv.width; dangerCv.height = cv.height;
            if (D.danger && D.danger.length) {
              const dc = dangerCv.getContext('2d');
              for (const c of D.danger) {
                if (c[2] <= 0) continue;
                dc.fillStyle = 'rgba(255,60,40,' + (0.06 + 0.30 * c[2] / 100).toFixed(3) + ')';
                dc.fillRect(px(c[0]) - CS, px(c[1]) - CS, 2 * CS, 2 * CS);
              }
            }

            const stateColors = { NO_COVER:'#ff4d4d', SEEKING_COVER:'#ffaa33', IN_COVER:'#4dff88',
                                  SUPPRESSED_IN_COVER:'#4db8ff', REPOSITIONING:'#ffff4d' };
            const scrub = document.getElementById('scrub');
            scrub.max = D.frames.length - 1;
            let idx = 0, playing = false, timer = null, selected = null;

            function line(x1, z1, x2, z2, color, dash) {
              ctx.save();
              ctx.strokeStyle = color; ctx.lineWidth = 1;
              if (dash) ctx.setLineDash([3, 3]);
              ctx.beginPath(); ctx.moveTo(px(x1), px(z1)); ctx.lineTo(px(x2), px(z2)); ctx.stroke();
              ctx.restore();
            }

            // --- event log: append-only, rebuilt only when scrubbing backwards ------
            const evlog = document.getElementById('evlog');
            let evShown = 0;          // events[0..evShown) are in the log
            let lastRenderedTick = Infinity;
            function syncEvents(t, rebuild) {
              if (rebuild) { evlog.textContent = ''; evShown = 0; }
              let added = false;
              while (evShown < D.events.length && D.events[evShown][0] <= t) {
                const e = D.events[evShown++];
                const row = document.createElement('div');
                row.textContent = 't' + e[0] + ': ' + e[1];
                evlog.appendChild(row);
                added = true;
              }
              while (evlog.childNodes.length > 200) evlog.removeChild(evlog.firstChild);
              if (added) evlog.scrollTop = evlog.scrollHeight;
            }

            function render() {
              const f = D.frames[idx];
              ctx.drawImage(terr, 0, 0);
              ctx.drawImage(dangerCv, 0, 0);
              document.getElementById('tickLabel').textContent = f.t;
              scrub.value = idx;
              syncEvents(f.t, f.t < lastRenderedTick);
              lastRenderedTick = f.t;

              // enemies
              ctx.fillStyle = '#ff3030';
              for (const e of f.e) ctx.fillRect(px(e[0]) - 5, px(e[1]) - 5, 10, 10);

              for (const s of f.s) {
                if (s.tx !== undefined) {
                  line(s.x, s.z, s.tx, s.tz, 'rgba(0,255,255,.45)');
                  ctx.fillStyle = s.tk === 'FULL' ? '#cfd8e6' : s.tk === 'HALF' ? '#9c8a4d' : '#0ff';
                  ctx.strokeStyle = 'rgba(0,255,255,.9)'; ctx.lineWidth = 1;
                  ctx.fillRect(px(s.tx) - 4, px(s.tz) - 4, 8, 8);
                  ctx.strokeRect(px(s.tx) - 4.5, px(s.tz) - 4.5, 9, 9);
                }
                if (s.rx !== undefined) line(s.x, s.z, s.rx, s.rz, 'rgba(255,170,60,.55)', true);
                if (s.los === 'y' && s.nx !== undefined) line(s.x, s.z, s.nx, s.nz, 'rgba(255,255,255,.4)', true);
              }

              // tracers fired during this sample interval
              for (const sh of (f.shots || [])) {
                line(sh[0], sh[1], sh[2], sh[3], 'rgba(255,224,102,.85)');
                ctx.fillStyle = 'rgba(255,224,102,.9)';
                ctx.beginPath(); ctx.arc(px(sh[2]), px(sh[3]), 2.5, 0, 2 * Math.PI); ctx.fill();
              }

              for (const s of f.s) {
                const X = px(s.x), Z = px(s.z);
                // posture: standing = full circle, crouching = small circle,
                // prone (SWIMMING pose) = flattened ellipse
                ctx.fillStyle = stateColors[s.cs] || '#ffffff';
                ctx.strokeStyle = 'rgba(0,0,0,.7)'; ctx.lineWidth = 1;
                if (s.po === 'SWIMMING') {
                  ctx.beginPath(); ctx.ellipse(X, Z, 9, 3.5, 0, 0, 2 * Math.PI);
                  ctx.fill(); ctx.stroke();
                } else {
                  const r = s.po === 'CROUCHING' ? 4.5 : 7;
                  ctx.beginPath(); ctx.arc(X, Z, r, 0, 2 * Math.PI); ctx.fill(); ctx.stroke();
                }
                if (s.ri) { ctx.beginPath(); ctx.arc(X, Z, 9.5, 0, 2 * Math.PI);
                             ctx.strokeStyle = 'rgba(255,255,255,.8)'; ctx.setLineDash([2, 2]);
                             ctx.lineWidth = 1; ctx.stroke(); ctx.setLineDash([]); }
                if (s.pin) { ctx.beginPath(); ctx.arc(X, Z, 9, 0, 2 * Math.PI);
                             ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.stroke(); }
                if (s.sup > 0.02) { ctx.beginPath();
                             ctx.arc(X, Z, 11, -Math.PI / 2, -Math.PI / 2 + s.sup * 2 * Math.PI);
                             ctx.strokeStyle = 'rgba(255,60,60,.9)'; ctx.lineWidth = 3; ctx.stroke(); }
                if (selected === s.l) { ctx.beginPath(); ctx.arc(X, Z, 14, 0, 2 * Math.PI);
                             ctx.strokeStyle = '#0ff'; ctx.setLineDash([2, 2]); ctx.lineWidth = 1.5;
                             ctx.stroke(); ctx.setLineDash([]); }
                ctx.fillStyle = '#e8e8e8'; ctx.font = '10px Consolas,monospace';
                ctx.fillText(s.l, X + 13, Z - 9);
              }

              document.getElementById('detail').textContent = detail(f);
            }

            function detail(f) {
              if (!selected) return 'click a soldier to inspect';
              let s = null;
              for (const x of f.s) if (x.l === selected) s = x;
              if (!s) return selected + ' (not sampled this frame)';
              const p = v => v === undefined ? '-' : v.toFixed(1);
              return 'label            : ' + s.l +
                   '\\ncoverState       : ' + s.cs +
                   '\\npeekState        : ' + s.ps +
                   '\\nADS / aim quality: ' + (s.ads === undefined ? '-' : Math.round(s.ads * 100) + '% / ' + s.aq.toFixed(3)) +
                   '\\naligned pre-ADS  : ' + (s.pat || 0) + ' ticks' +
                   (s.po !== undefined || s.ch !== undefined
                     ? '\\nposture          : ' + (s.po || 'STANDING') + (s.ri ? '  [RISING]' : '')
                       + (s.ch !== undefined ? '   cover height: ' + s.ch : '')
                     : '') +
                   (s.hp !== undefined ? '\\nhealth           : ' + s.hp.toFixed(1) : '') +
                   '\\nsuppression      : ' + s.sup.toFixed(2) + (s.pin ? '   [PINNED]' : '') +
                   '\\nfollowBlockedBy  : ' + (s.fb || '-') +
                   '\\nwantCover        : ' + (s.wc || '-') +
                   '\\nqueued / reloc   : ' + (s.qm || '-') + ' / ' + s.rt +
                   '\\nseeking / failed / stuck : ' + s.sk + ' / ' + s.fs + ' / ' + s.st +
                   '\\ncurrentCover     : ' + (s.cx !== undefined ? p(s.cx) + ', ' + p(s.cz) + (s.ck ? '  [' + s.ck + ']' : '') : '-') +
                   '\\ntargetCover      : ' + (s.tx !== undefined ? p(s.tx) + ', ' + p(s.tz) + (s.tk ? '  [' + s.tk + ']' : '') : '-') +
                   '\\nrelocCenter      : ' + (s.rx !== undefined ? p(s.rx) + ', ' + p(s.rz) : '-') +
                   '\\nlast search      : ' + (s.ls || '-') +
                   (s.speedRequest !== undefined ? '\\nspeed request    : ' + s.speedRequest.toFixed(3)
                     + '\\ntarget / applied : ' + s.speedTarget.toFixed(3) + ' / ' + s.speedApplied.toFixed(3)
                     + '\\nmoved last tick  : ' + s.movedPerTick.toFixed(4)
                     + '\\nspeed limit      : ' + s.speedLimit : '') +
                   '\\nshots fired      : ' + (s.sf || 0) +
                   (s.am !== undefined ? '\\nammo             : ' + s.am + ' / ' + s.mg : '') +
                   '\\nslot offset      : ' + (s.cd !== undefined ? s.cd.toFixed(2) + (s.cd > 0.35 ? '  [DRIFT]' : '') : '-') +
                   '\\nanchor pulls     : ' + (s.ap !== undefined ? s.ap : '-') +
                   '\\nbody rays exposed: ' + (s.xr !== undefined ? s.xr + ' / ' + s.pr + (s.xh ? '  (head: ' + s.xh + ')' : '') : '-') +
                   (s.dm !== undefined ? '\\ndanger@cell     : ' + s.dm : '') +
                   (s.rl ? '\\nRELOADING' : '') +
                   '\\nLoS to enemy     : ' + s.los;
            }

            // --- playback ----------------------------------------------------------
            const speeds = { '0.5x': 500, '1x': 250, '4x': 62, '10x': 25 };
            const playBtn = document.getElementById('play');
            function stop() { playing = false; playBtn.innerHTML = '&#9654;'; if (timer) clearInterval(timer); timer = null; }
            function tickPlay() {
              if (idx >= D.frames.length - 1) { stop(); return; }
              idx++; render();
            }
            playBtn.onclick = () => {
              if (playing) { stop(); return; }
              if (idx >= D.frames.length - 1) idx = 0;
              playing = true; playBtn.innerHTML = '&#10074;&#10074;';
              timer = setInterval(tickPlay, speeds[document.getElementById('speed').value]);
            };
            document.getElementById('speed').onchange = () => {
              if (playing) { stop(); playBtn.onclick(); }
            };
            scrub.oninput = e => { stop(); idx = +e.target.value; render(); };
            cv.onclick = e => {
              const r = cv.getBoundingClientRect();
              const mx = e.clientX - r.left, my = e.clientY - r.top;
              const f = D.frames[idx];
              let best = null, bestD = 1e9;
              for (const s of f.s) {
                const d = Math.hypot(px(s.x) - mx, px(s.z) - my);
                if (d < bestD) { bestD = d; best = s.l; }
              }
              selected = bestD <= 14 ? best : null;
              render();
            };

            const vl = D.violations.map(v => '[' + v[3] + '] ' + v[1] + ' @t' + v[0] + ' (' + v[2] + ')');
            document.getElementById('vline').textContent = vl.length ? 'violations: ' + vl.join(', ') : 'no detector violations';

            render();
            </script></body></html>
            """
            .replace("__TITLE__", scenario)
            .replace("__DATA__", dataJson);
    }
}

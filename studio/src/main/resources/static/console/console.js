/* The workloads console. Vanilla JS over /api/console/*; the shell, tokens and drawing helpers
   are the approved prototype's (consoles-approved-v1), the data is the studio's. */
(() => {
const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
const con = $('#con');
const RM = matchMedia('(prefers-reduced-motion: reduce)').matches;
const css = n => getComputedStyle(con).getPropertyValue(n).trim();
const fmt = n => Math.round(n).toLocaleString('en-US');
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const ms = v => v == null ? '—' : v >= 1000 ? (v / 1000).toFixed(2) + ' s' : v >= 10 ? v.toFixed(0) + ' ms' : v.toFixed(v >= 1 ? 1 : 2) + ' ms';
const ago = s => s < 60 ? s + ' s ago' : s < 3600 ? Math.round(s / 60) + ' min ago' : s < 172800 ? Math.round(s / 3600) + ' h ago' : Math.round(s / 86400) + ' days ago';

async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  let data = null; try { data = await r.json(); } catch (e) { /* empty body */ }
  if (!r.ok) { const err = new Error((data && (data.error || data.message)) || r.status + ' ' + r.statusText); err.data = data; throw err; }
  return data;
}

/* ---------- rail, crumb, views ---------- */
const ICON = {
 loads: '<path d="M2 12c2.5 0 2.5-6 5-6s2.5 12 5 12 2.5-12 5-12 2.5 6 5 6"/>',
 soak: '<path d="M12 3v18M5 8h14M7 14h10"/><circle cx="12" cy="3" r="1"/>',
 evidence: '<path d="M9 3h6l1 3H8z"/><rect x="5" y="6" width="14" height="15" rx="1"/><path d="m9 13 2 2 4-4"/>',
 studio: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13 7 4 4"/>',
 set: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>'};
const VIEWS = [['loads', 'Standing loads'], ['soak', 'Endurance'], ['evidence', 'Delivery evidence'], ['studio', 'Load designer']];
/* Views added by other scripts (scenarios.js): {id, label, icon, show, redraw, live, running, cmds, home}. */
const EXT = {};
const MARK = '<path d="M2 16c3 0 3-9 6-9s3 16 6 16 3-16 6-16 3 9 6 9" fill="none" stroke="var(--c-acc)" stroke-width="3.4" stroke-linecap="round" transform="scale(.95) translate(1 -3)"/>';
let view = 'loads';
let overview = {drillWorkspace: null};

function buildRail() {
  $('#rail').innerHTML = `<div class="mark" aria-hidden="true"><svg viewBox="0 0 32 32">${MARK}</svg></div>` +
    VIEWS.map(([v, l]) => `<button role="tab" id="tab-${v}" data-v="${v}" aria-selected="${v === view}" tabindex="${v === view ? 0 : -1}" aria-label="${l}"><svg viewBox="0 0 24 24" aria-hidden="true">${ICON[v]}</svg><span class="tip">${l}</span></button>`).join('') +
    `<div class="sp"></div><button id="setBtn" aria-label="Theme and mode" aria-haspopup="dialog" aria-expanded="false"><svg viewBox="0 0 24 24" aria-hidden="true">${ICON.set}</svg><span class="tip">Theme and mode</span></button>`;
  $$('#rail [data-v]').forEach(b => {
    b.onclick = () => show(b.dataset.v);
    b.onkeydown = e => {
      const i = VIEWS.findIndex(v => v[0] === b.dataset.v);
      const d = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 0;
      if (d) { e.preventDefault(); show(VIEWS[(i + d + VIEWS.length) % VIEWS.length][0]); $('#tab-' + view).focus(); }
    };
  });
  $('#setBtn').onclick = openSettings;
}
function crumb(clients) {
  const live = (clients || []).filter(c => c.live).length;
  const ws = overview.drillWorkspace ? 'drill workspace' : 'no drill workspace';
  $('#crumb').innerHTML = `<b>acemq-java-amqp-workloads</b><i>/</i>${esc(ws)}${clients ? '<i>/</i>' + live + ' running' : ''}`;
}
function show(v) {
  view = v;
  try { history.replaceState(null, '', '#' + v); } catch (e) { /* file or sandbox */ }
  buildRail();
  $$('.view').forEach(s => s.classList.toggle('on', s.dataset.view === v));
  setLive();
  if (v === 'loads') loadLoads();
  if (v === 'soak') loadSoak();
  if (v === 'evidence') loadEvidence();
  if (v === 'studio') designerShown();
  if (EXT[v]) EXT[v].show();
  requestAnimationFrame(redrawAll);
}
let liveText = {loads: 'reading', soak: 'reading', evidence: 'evidence', studio: 'designer'};
function setLive() {
  const el = $('#live');
  el.textContent = EXT[view] ? EXT[view].live() : liveText[view];
  el.className = 'live' + ((view === 'loads' && loadsData && loadsData.clients.some(c => c.source === 'studio' && c.live)) || (EXT[view] && EXT[view].running && EXT[view].running()) ? ' run' : '');
}
document.addEventListener('click', e => { const b = e.target.closest && e.target.closest('[data-jump]'); if (b) { e.preventDefault(); show(b.dataset.jump); } });

/* ---------- themes ---------- */
const THEMES = [
 ['obsidian', 'Obsidian', 'linear-gradient(90deg,#0f1113 55%,#ff6600 55% 80%,#8fd5cc 80%)', 'Monochrome chrome, one orange accent, and colour kept for state.'],
 ['blueprint', 'Blueprint', 'linear-gradient(90deg,#0b2545 60%,#fff 60% 80%,#7fd3ff 80%)', 'Cyanotype drawing: white ink on prussian blue, square corners.'],
 ['gallery', 'Gallery', 'linear-gradient(90deg,#f1f2f4 60%,#111316 60% 80%,#ff6600 80%)', 'Swiss and quiet, for long reading sessions over run reports.'],
 ['signal', 'Signal', 'linear-gradient(90deg,#070707 60%,#ffb000 60% 80%,#ff6600 80%)', 'Mission control: amber on black, for the screen on the wall.'],
 ['aurora', 'Aurora', 'linear-gradient(90deg,#0c0a1e 40%,#6d5dfc 40% 70%,#5eead4 70%)', 'Glass panels over a violet and teal field.'],
 ['access', 'Access', 'linear-gradient(90deg,#000 60%,#ffd400 60%)', 'WCAG AAA: large hyperlegible type, 2 px rules, state by shape too.']];
const sysMode = matchMedia('(prefers-color-scheme: light)');
let theme = 'obsidian', mode = sysMode.matches ? 'light' : 'dark', modeChosen = false;
try {
  const s = JSON.parse(localStorage.getItem('acemq-con') || 'null');
  if (s) { theme = THEMES.some(t => t[0] === s.theme) ? s.theme : theme; if (s.mode === 'light' || s.mode === 'dark') { mode = s.mode; modeChosen = true; } }
} catch (e) { /* storage blocked */ }
sysMode.addEventListener('change', e => { if (!modeChosen) { mode = e.matches ? 'light' : 'dark'; applyTheme(false); } });
function applyTheme(persist) {
  con.dataset.theme = theme; con.dataset.mode = mode;
  document.documentElement.style.colorScheme = mode;
  document.body.style.background = css('--c-bg');
  $$('#themes button').forEach(b => b.setAttribute('aria-pressed', b.dataset.t === theme));
  $('#modeBtn').textContent = 'Mode: ' + mode;
  $('#themeNote').textContent = THEMES.find(t => t[0] === theme)[3];
  if (persist) try { localStorage.setItem('acemq-con', JSON.stringify(modeChosen ? {theme, mode} : {theme})); } catch (e) { /* storage blocked */ }
  requestAnimationFrame(redrawAll);
}
$('#themes').innerHTML = THEMES.map(t => `<button data-t="${t[0]}" aria-pressed="false"><span class="sw" style="background:${t[2]}"></span>${t[1]}</button>`).join('');
$$('#themes button').forEach(b => b.onclick = () => { theme = b.dataset.t; applyTheme(true); });
$('#modeBtn').onclick = () => { mode = mode === 'dark' ? 'light' : 'dark'; modeChosen = true; applyTheme(true); };
let opener = null;
function openSettings() { opener = document.activeElement; $('#settings').hidden = false; $('#setBtn').setAttribute('aria-expanded', 'true'); ($('#themes [aria-pressed="true"]') || $('#themes button')).focus(); }
function closeSettings() { if ($('#settings').hidden) return; $('#settings').hidden = true; const b = $('#setBtn'); if (b) b.setAttribute('aria-expanded', 'false'); opener && opener.focus && opener.focus(); }
$('#settings').onclick = e => { if (e.target.id === 'settings') closeSettings(); };

/* ---------- canvas helpers (the prototype's) ---------- */
/* The CSS height is read once, from the markup, and kept: writing the device-pixel height back into
   the attribute it was read from doubled the canvas on every redraw of a 2x screen. */
function fit(c) { const r = c.getBoundingClientRect(), d = devicePixelRatio || 1; if (!c.dataset.h) c.dataset.h = c.getAttribute('height') || r.height || 30; const w = Math.max(10, r.width), h = +c.dataset.h; c.style.height = h + 'px'; c.width = w * d; c.height = h * d; const x = c.getContext('2d'); x.setTransform(d, 0, 0, d, 0, 0); return [x, w, h]; }
function spark(c, data, col, opt = {}) {
  if (!c || !c.offsetParent) return;
  const [x, w, h] = fit(c); if (!data || data.length < 2) return;
  const mn = opt.min ?? Math.min(...data), mx = opt.max ?? Math.max(...data), rg = (mx - mn) || 1;
  const px = i => i * (w - 4) / (data.length - 1) + 2, py = v => h - 3 - (v - mn) / rg * (h - 7);
  x.beginPath(); data.forEach((v, i) => i ? x.lineTo(px(i), py(v)) : x.moveTo(px(i), py(v)));
  x.strokeStyle = col; x.lineWidth = 1.5; x.stroke();
  x.lineTo(px(data.length - 1), h); x.lineTo(px(0), h); x.closePath(); x.fillStyle = col; x.globalAlpha = .12; x.fill(); x.globalAlpha = 1;
  x.beginPath(); x.arc(px(data.length - 1), py(data[data.length - 1]), 2.4, 0, 7); x.fillStyle = col; x.fill();
}
function emptyChart(c, text) {
  if (!c || !c.offsetParent) return; const [x, w, h] = fit(c);
  x.fillStyle = css('--c-dim'); x.font = '12px ' + css('--c-mono'); x.textAlign = 'center'; x.fillText(text, w / 2, h / 2); x.textAlign = 'start';
}
function logLine(el, lv, tag, msg, at) {
  const li = document.createElement('li'); const d = at ? new Date(at) : null;
  li.innerHTML = `<time>${d && !isNaN(d) ? d.toTimeString().slice(0, 8) : '—'}</time><span class="lv ${esc(lv)}">${esc(tag)}</span><p>${esc(msg)}</p>`;
  el.append(li);
}
function empty(el, title, text, tag) {
  el.innerHTML = `<div class="empty">${tag ? `<span class="tag coming">${esc(tag)}</span>` : ''}<h4>${esc(title)}</h4><p>${text}</p></div>`;
  el.hidden = false;
}

/* ---------- STANDING LOADS ---------- */
let loadsData = null, lsel = null;
async function loadLoads() {
  try { loadsData = await api('/api/console/loads'); } catch (e) { liveText.loads = 'studio unreachable'; setLive(); return; }
  const cs = loadsData.clients;
  const live = cs.filter(c => c.live);
  liveText.loads = live.length ? 'live · 1 s' : 'no load running';
  setLive(); crumb(cs);
  if (!cs.some(c => c.id === lsel)) lsel = (live[0] || cs.find(c => c.latency) || cs[0] || {}).id || null;
  renderLoads();
}
const last = (c, k) => c.history.length ? c.history[c.history.length - 1][k] || 0 : 0;
function renderLoads() {
  const cs = loadsData.clients, live = cs.filter(c => c.live);
  const stopBtn = $('#stopRun'); const mine = cs.find(c => c.source === 'studio' && c.live);
  stopBtn.hidden = !mine; stopBtn.dataset.run = mine ? mine.runId : '';
  $('#loadsSub').textContent = cs.length
    ? `${cs.length} load${cs.length > 1 ? 's' : ''} · ${live.length} running · open loop · readings every second` + (loadsData.drillWorkspace ? ' · drill workspace' : '')
    : 'nothing to show yet';
  if (!cs.length) {
    empty($('#loadsEmpty'), 'No standing load to show',
      'Start one from the <b>Load designer</b>, or point the studio at the drill workspace with <code>ACEMQ_STUDIO_DRILL_WORKSPACE</code> so the five standing loads that <code>./scripts/chaos-drill.sh workload up</code> starts appear here, one card each.');
  } else $('#loadsEmpty').hidden = true;
  const sum = k => live.reduce((a, c) => a + last(c, k), 0);
  const tot = k => cs.reduce((a, c) => a + ((c.last || {})[k] || 0), 0);
  const unit = v => live.length ? fmt(v) + '<small>/s</small>' : '—';
  $('#wOff').innerHTML = unit(sum('offered')); $('#wConf').innerHTML = unit(sum('confirmed')); $('#wCons').innerHTML = unit(sum('consumed'));
  const depth = live.reduce((a, c) => a + ((c.last || {}).queueDepth || 0), 0);
  $('#wLag').textContent = live.some(c => c.last && c.last.queueDepth != null) ? 'queue depth ' + fmt(depth) : live.length ? 'depth not reported' : 'no load running';
  const ref = tot('refused'), fail = tot('failed');
  $('#wRef').textContent = cs.length ? fmt(ref) : '—';
  $('#wFail').textContent = cs.length ? fmt(fail) : '—';
  $('#wFail').className = 'v' + (fail ? ' cr' : '');
  $('#wFailD').className = 'd' + (fail ? ' cr' : cs.length ? ' up' : '');
  $('#wFailD').textContent = !cs.length ? ' ' : fail ? 'possibly lost · since each load began' : 'nothing possibly lost';
  const sel = cs.find(c => c.id === lsel);
  const p99 = sel && sel.latency ? sel.latency.p99Ms : null;
  $('#wP99').innerHTML = p99 == null ? '—' : esc(ms(p99)).replace(/ (ms|s)$/, '<small>$1</small>');
  $('#wP99D').textContent = sel ? (p99 == null ? sel.name + ' reports no latency' : sel.name) : ' ';

  $('#lcards').hidden = !cs.length;
  const known = $$('.lcard').map(b => b.dataset.l).join('|');
  if (known !== cs.map(c => c.id).join('|')) {
    $('#lcards').innerHTML = cs.map(c => `<button class="lcard" data-l="${esc(c.id)}" aria-pressed="${c.id === lsel}"><div class="lh"><b>${esc(c.name)}</b><span class="chip" data-chip></span></div>
      <span class="where" data-where></span>
      <canvas height="38" aria-hidden="true"></canvas>
      <div class="lstats"><div>confirmed<b data-k="confirmed"></b></div><div>refused<b data-k="refused"></b></div><div>p99<b data-k="p99"></b></div></div>
      <div class="lstats"><div>consumed<b data-k="consumed"></b></div><div>failed<b data-k="failed"></b></div><div>state<b data-k="state"></b></div></div></button>`).join('');
    $$('.lcard').forEach(b => b.onclick = () => { lsel = b.dataset.l; $$('.lcard').forEach(x => x.setAttribute('aria-pressed', x === b)); renderLoads(); });
  }
  cs.forEach(c => {
    const b = $$('.lcard').find(x => x.dataset.l === c.id); if (!b) return;
    b.setAttribute('aria-pressed', c.id === lsel);
    const chip = b.querySelector('[data-chip]');
    chip.textContent = c.source === 'studio' ? 'console' : 'drill';
    chip.className = 'chip' + (c.source === 'studio' ? ' acc' : '');
    b.querySelector('[data-where]').textContent = c.live ? (c.source === 'studio' ? c.broker : c.file) : c.source === 'studio' ? c.state : 'last reading ' + ago(c.ageSeconds);
    const L = c.last || {};
    const set = (k, v, cls) => { const e = b.querySelector(`[data-k="${k}"]`); e.textContent = v; e.className = cls || ''; };
    set('confirmed', c.live ? fmt(last(c, 'confirmed')) + '/s' : '—', c.live ? '' : 'z');
    set('consumed', c.live ? fmt(last(c, 'consumed')) + '/s' : '—', c.live ? '' : 'z');
    set('refused', fmt(L.refused || 0), L.refused ? 'wn' : 'z');
    set('failed', fmt(L.failed || 0), L.failed ? 'wn' : 'z');
    set('p99', c.latency ? ms(c.latency.p99Ms) : '—', c.latency ? '' : 'z');
    const st = b.querySelector('[data-k="state"]'); st.textContent = c.state;
    st.style.color = c.state === 'moving' ? 'var(--c-ok)' : c.state === 'blocked' || c.state === 'failed' ? 'var(--c-crit)' : 'var(--c-dim)';
  });
  const evs = loadsData.events.slice(-40).reverse();
  const evKey = JSON.stringify(evs);
  if (evKey !== renderLoads.evKey) { renderLoads.evKey = evKey;
  $('#loadLog').innerHTML = '';
  if (!evs.length) $('#loadLog').innerHTML = '<li><p style="color:var(--c-dim)">Nothing but readings: no load has written anything else.</p></li>';
  evs.forEach(e => logLine($('#loadLog'), e.level, e.who, e.text, e.at)); }
  $('#histLang').textContent = sel ? sel.name : '—';
  drawLoads();
}
function drawLoads() {
  if (!loadsData) return;
  const live = loadsData.clients.filter(c => c.live);
  const series = k => { const n = Math.max(0, ...live.map(c => c.history.length)); return Array.from({length: n}, (_, i) => live.reduce((a, c) => { const h = c.history, j = h.length - n + i; return a + (j >= 0 ? h[j][k] || 0 : 0); }, 0)); };
  spark($('#spOff'), series('offered'), css('--c-acc')); spark($('#spConf'), series('confirmed'), css('--c-acc2'));
  loadsData.clients.forEach(c => { const b = $$('.lcard').find(x => x.dataset.l === c.id); if (b) spark(b.querySelector('canvas'), c.history.map(p => p.confirmed), css('--c-acc2'), {min: 0}); });
  drawLat();
}
/* The selected load's latency. A load run from this console carries the HdrHistogram summary, drawn
   as the share of messages between each pair of percentiles on a log scale. The drill files carry
   only p99, drawn over the last minute. Nothing is drawn that was not measured. */
function drawLat() {
  const c = $('#latChart'); if (!c.offsetParent) return;
  const sel = loadsData && loadsData.clients.find(x => x.id === lsel);
  const L = sel && sel.latency;
  if (!L) { emptyChart(c, sel ? 'no latency in ' + sel.name + "'s readings" : 'no load selected'); $('#latLegend').innerHTML = ''; $('#latCnt').textContent = 'HdrHistogram · log scale'; return; }
  const [x, w, h] = fit(c); const pad = {l: 44, r: 10, t: 14, b: 26};
  x.font = '10px ' + css('--c-mono'); x.lineWidth = 1;
  if (L.p50Ms == null) {
    $('#latCnt').textContent = 'p99 per reading · last minute';
    const pts = sel.history.map(p => p.p99Ms).filter(v => v != null);
    if (pts.length < 2) { emptyChart(c, 'waiting for readings'); return; }
    const mx = Math.max(...pts) * 1.15 || 1;
    const px = i => pad.l + i * (w - pad.l - pad.r) / (pts.length - 1), py = v => pad.t + (1 - v / mx) * (h - pad.t - pad.b);
    [0, .5, 1].forEach(f => { const y = py(mx * f); x.strokeStyle = css('--c-line'); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(w - pad.r, y); x.stroke(); x.fillStyle = css('--c-dim'); x.fillText(ms(mx * f), 2, y + 3); });
    x.beginPath(); pts.forEach((v, i) => i ? x.lineTo(px(i), py(v)) : x.moveTo(px(i), py(v))); x.strokeStyle = css('--c-acc'); x.lineWidth = 2; x.stroke();
    x.fillStyle = css('--c-dim'); x.fillText('a minute ago', pad.l, h - 8); x.fillText('now', w - pad.r - 22, h - 8);
    $('#latLegend').innerHTML = `<span><i style="background:var(--c-acc)"></i>end-to-end p99</span><span>latest ${esc(ms(L.p99Ms))} · this load's sample lines carry p99 only</span>`;
    return;
  }
  $('#latCnt').textContent = 'HdrHistogram · log scale · ' + fmt(L.count) + ' messages';
  const bands = [[L.minMs, L.p50Ms, 50], [L.p50Ms, L.p90Ms, 40], [L.p90Ms, L.p99Ms, 9], [L.p99Ms, L.p999Ms, .9], [L.p999Ms, L.p9999Ms, .09], [L.p9999Ms, L.maxMs, .01]];
  const lo = Math.max(0.001, L.minMs || 0.001), hi = Math.max(lo * 10, L.maxMs);
  const lx = v => pad.l + (Math.log(Math.max(v, lo)) - Math.log(lo)) / (Math.log(hi) - Math.log(lo)) * (w - pad.l - pad.r);
  const dens = bands.map(b => b[2] / Math.max(0.05, Math.log10(Math.max(b[1], b[0] * 1.01) / Math.max(b[0], lo))));
  const mxd = Math.max(...dens);
  x.strokeStyle = css('--c-line'); [0, .5, 1].forEach(f => { const y = pad.t + (h - pad.t - pad.b) * (1 - f); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(w - pad.r, y); x.stroke(); });
  bands.forEach((b, i) => {
    const x0 = lx(b[0]), x1 = Math.max(x0 + 3, lx(b[1])), bh = (h - pad.t - pad.b) * dens[i] / mxd;
    x.fillStyle = i >= 3 ? css('--c-warn') : css('--c-acc2'); x.globalAlpha = .85; x.fillRect(x0 + 1, h - pad.b - bh, x1 - x0 - 2, bh); x.globalAlpha = 1;
    if (x1 - x0 > 34) { x.fillStyle = css('--c-dim'); x.fillText(b[2] + '%', x0 + 2, h - pad.b - bh - 3); }
  });
  x.fillStyle = css('--c-dim');
  for (let d = Math.pow(10, Math.floor(Math.log10(lo))); d <= hi; d *= 10) { const X = lx(d); if (X >= pad.l - 1) x.fillText(ms(d), X - 8, h - 8); }
  const mark = (v, lab, col) => { const X = lx(v); x.strokeStyle = col; x.setLineDash([4, 3]); x.beginPath(); x.moveTo(X, pad.t); x.lineTo(X, h - pad.b); x.stroke(); x.setLineDash([]); x.fillStyle = col; x.fillText(lab, X + 4, pad.t + 8); };
  mark(L.p50Ms, 'p50 ' + ms(L.p50Ms), css('--c-fg')); mark(L.p99Ms, 'p99 ' + ms(L.p99Ms), css('--c-acc'));
  $('#latLegend').innerHTML = `<span><i style="background:var(--c-acc2)"></i>up to p99</span><span><i style="background:var(--c-warn)"></i>beyond p99</span><span>p50 ${esc(ms(L.p50Ms))} · p90 ${esc(ms(L.p90Ms))} · p99 ${esc(ms(L.p99Ms))} · p99.9 ${esc(ms(L.p999Ms))} · max ${esc(ms(L.maxMs))}</span>`;
}
$('#stopRun').onclick = async () => {
  const id = $('#stopRun').dataset.run; if (!id) return;
  try { await api('/api/console/runs/' + encodeURIComponent(id) + '/stop', {}); toast('Stopping · it reports on the window it measured'); } catch (e) { toast(e.message); }
};

/* ---------- ENDURANCE ---------- */
let soak = null, smet = 'rss';
const SC = ['--c-info', '--c-acc2', '--c-acc', '--c-warn', '--c-crit', '--c-ok'];
async function loadSoak() {
  try { soak = await api('/api/console/endurance'); } catch (e) { soak = {available: false, reason: e.message}; }
  if (!soak.available) {
    liveText.soak = 'no soak report'; setLive();
    $('#soakSub').textContent = 'resource probes over repeated recovery';
    $('#soakBody').hidden = true;
    empty($('#soakEmpty'), 'No soak to show', esc(soak.reason) + ' The soak itself is planned to move into this repository; until then this view reads the workspace\'s report as it is.', 'coming');
    return;
  }
  $('#soakEmpty').hidden = true; $('#soakBody').hidden = false;
  const d = soak.id.match(/^(\d{4})(\d{2})(\d{2})/);
  liveText.soak = 'last soak · ' + (d ? new Date(+d[1], +d[2] - 1, +d[3]).toLocaleDateString('en-GB', {day: 'numeric', month: 'short'}) : soak.id); setLive();
  $('#soakSub').textContent = 'soak ' + soak.id + (soak.summary ? ' · ' + soak.summary.replace(/\.$/, '') : '');
  const passed = /^PASS/.test(soak.verdict);
  $('#sVerdict').textContent = soak.verdict; $('#sVerdict').className = 'v ' + (passed ? 'ok' : 'cr');
  const clean = soak.clients.filter(c => c.verdict === 'clean').length;
  $('#sVerdictD').textContent = `${clean} of ${soak.clients.length} clean`;
  $('#sCycles').innerHTML = soak.cycles != null ? fmt(soak.cycles) : '—';
  const mins = Math.max(0, ...Object.values(soak.series).map(s => s.length ? s[s.length - 1][0] : 0));
  $('#sCyclesD').textContent = mins ? Math.round(mins) + ' minutes of readings' : ' ';
  const st = (soak.settled || '').match(/low (\d+), high (\d+)/);
  $('#sSettled').innerHTML = st ? `${st[1]}<small>/ ${st[2]}</small>` : '—';
  $('#sSettledD').textContent = soak.afterClose ? 'right after a close: ' + soak.afterClose : ' ';
  const leaks = soak.clients.length - clean;
  $('#sLeaks').textContent = leaks; $('#sLeaks').className = 'v' + (leaks ? ' cr' : '');
  $('#sLeaksD').textContent = soak.allowances ? 'allowance: ' + soak.allowances : ' ';
  $('#soakT').innerHTML = soak.clients.map(c => `<tr><td class="mono"><span class="dot${c.verdict === 'clean' ? '' : ' c'}"></span>${esc(c.library)}</td><td class="num">${esc(c.rssBefore)} → ${esc(c.rssAfter)}</td><td class="num">${esc(c.fdsBefore)} → ${esc(c.fdsAfter)}</td><td class="num">${esc(c.threadsBefore)} → ${esc(c.threadsAfter)}</td><td><span class="tag ${c.verdict === 'clean' ? 'ok' : 'cr'}">${esc(c.verdict)}</span></td></tr>`).join('');
  $('#soakSource').innerHTML = `This is <code>${esc(soak.report)}</code>, written by <code>scripts/soak.sh</code> in <code>${esc(overview.drillWorkspace || '')}</code>, with its readings from <code>${esc(soak.readings || 'no readings file')}</code>. An interim source: the soak moves into this repository later, and until then the console shows what the script measured and nothing it did not.`;
  drawSoak();
}
function drawSoak() {
  const c = $('#soakChart'); if (!c.offsetParent || !soak || !soak.available) return;
  const libs = Object.keys(soak.series);
  if (!libs.length) { emptyChart(c, 'the readings file named in the report is not there'); $('#soakLegend').innerHTML = ''; return; }
  const [x, w, h] = fit(c); const pad = {l: 44, r: 12, t: 12, b: 26};
  const idx = {rss: 1, fds: 2, thr: 3}[smet];
  const val = (s, r) => smet === 'rss' ? r[1] / s[0][1] * 100 : r[idx] - s[0][idx];
  const all = libs.flatMap(l => soak.series[l].map(r => val(soak.series[l], r)));
  const lim = smet === 'rss' ? (soak.rssFactor || 2) * 100 : smet === 'fds' ? soak.fdSlack : soak.threadSlack;
  const cfg = {rss: {lab: v => Math.round(v) + '%', t: 'Resident memory, % of its own start'}, fds: {lab: v => (v >= 0 ? '+' : '') + Math.round(v), t: 'Open file handles, change from start'}, thr: {lab: v => (v >= 0 ? '+' : '') + Math.round(v), t: 'Threads, change from start'}}[smet];
  const mn = Math.min(smet === 'rss' ? 90 : -2, ...all), mx = Math.max(lim != null ? lim * 1.1 : 0, ...all) || 1;
  $('#soakTitle').textContent = cfg.t;
  const tmax = Math.max(...libs.map(l => { const s = soak.series[l]; return s[s.length - 1][0]; })) || 1;
  const px = t => pad.l + t / tmax * (w - pad.l - pad.r), py = v => pad.t + (1 - (v - mn) / (mx - mn)) * (h - pad.t - pad.b);
  x.font = '10px ' + css('--c-mono'); x.lineWidth = 1;
  for (let k = 0; k <= 4; k++) { const v = mn + (mx - mn) * k / 4; const y = py(v); x.strokeStyle = css('--c-line'); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(w - pad.r, y); x.stroke(); x.fillStyle = css('--c-dim'); x.fillText(cfg.lab(v), 4, y + 3); }
  [0, .25, .5, .75, 1].forEach(f => { x.fillStyle = css('--c-dim'); x.fillText(f === 0 ? 'minute 0' : String(Math.round(tmax * f)), px(tmax * f) - (f ? 8 : 0), h - 8); });
  if (lim != null) { x.strokeStyle = css('--c-crit'); x.setLineDash([6, 4]); x.beginPath(); x.moveTo(pad.l, py(lim)); x.lineTo(w - pad.r, py(lim)); x.stroke(); x.setLineDash([]); x.fillStyle = css('--c-crit'); x.fillText('leak threshold', w - pad.r - 92, py(lim) - 5); }
  libs.forEach((l, i) => { const s = soak.series[l], col = css(SC[i % SC.length]); x.strokeStyle = col; x.lineWidth = 1.8; x.beginPath(); s.forEach((r, j) => j ? x.lineTo(px(r[0]), py(val(s, r))) : x.moveTo(px(r[0]), py(val(s, r)))); x.stroke(); const e = s[s.length - 1]; x.beginPath(); x.arc(px(e[0]), py(val(s, e)), 3, 0, 7); x.fillStyle = col; x.fill(); });
  $('#soakLegend').innerHTML = libs.map((l, i) => `<span><i style="background:var(${SC[i % SC.length]})"></i>${esc(l)}</span>`).join('');
}
$$('#soakMetric .btn').forEach(b => b.onclick = () => { smet = b.dataset.m; $$('#soakMetric .btn').forEach(x => x.setAttribute('aria-pressed', x === b)); drawSoak(); });
$('#soakRun').onclick = () => toast('Run ./scripts/soak.sh in the workspace · this view reads the report it writes');

/* ---------- DELIVERY EVIDENCE ---------- */
let evidence = [];
const BADGE = {passed: ['ok', '✓'], warning: ['wn', '!'], failed: ['cr', '✕'], invalid: ['cr', '✕'], 'n/a': ['na', '–']};
async function loadEvidence() {
  try { evidence = await api('/api/console/evidence'); } catch (e) { evidence = []; }
  liveText.evidence = evidence.length ? evidence.length + ' run' + (evidence.length > 1 ? 's' : '') : 'no runs yet'; setLive();
  const sel = $('#evRun'), keep = sel.value;
  sel.innerHTML = evidence.map(r => `<option value="${esc(r.id)}">${esc(r.name)} · ${esc(new Date(r.startedAt).toLocaleString('en-GB', {dateStyle: 'medium', timeStyle: 'short'}))} · ${esc(r.verdict)}</option>`).join('');
  if (evidence.some(r => r.id === keep)) sel.value = keep;
  sel.hidden = $('#evExport').hidden = !evidence.length;
  if (!evidence.length) {
    $('#evBody').hidden = true;
    empty($('#evEmpty'), 'No run to show evidence for', 'Evidence comes from loads started in this console: when one ends, the library\'s rules and the file\'s objectives are checked against what it measured, and kept under <code>' + esc(overview.workloadsDir || 'the workloads directory') + '/runs</code>. Start one from the <b>Load designer</b>.');
    return;
  }
  $('#evEmpty').hidden = true; $('#evBody').hidden = false;
  renderEvidence();
}
function renderEvidence() {
  const r = evidence.find(x => x.id === $('#evRun').value) || evidence[0]; if (!r) return;
  $('#rules').innerHTML = r.rules.map(e => { const b = BADGE[e.status] || BADGE['n/a']; return `<div class="rule"><span class="badge ${b[0]}" aria-label="${esc(e.status)}">${b[1]}</span><div><h4>${esc(e.title)}</h4><p>${esc(e.status === 'passed' || e.status === 'n/a' ? e.plain : e.implication || e.plain)}</p></div><span class="ev">${esc(e.evidence)}</span></div>`; }).join('');
  $('#evName').textContent = r.id;
  const kv = (k, v) => `<div class="kv"><span>${esc(k)}</span><span>${esc(v)}</span></div>`;
  const L = r.endToEnd;
  $('#evRunKv').innerHTML = kv('verdict', r.verdict) + kv('broker', r.broker) + kv('measured', (r.durationMs / 1000).toFixed(1) + ' s') +
    kv('offered', r.offeredRate ? fmt(r.offeredRate) + '/s' : 'unthrottled') + kv('published · confirmed', fmt(r.published) + ' · ' + fmt(r.confirmed)) +
    kv('consumed', fmt(r.consumed)) + kv('refused · failed', fmt(r.refused) + ' · ' + fmt(r.failed)) +
    kv('end-to-end', L ? `p50 ${ms(L.p50Ms)} · p99 ${ms(L.p99Ms)} · max ${ms(L.maxMs)}` : 'not recorded');
}
$('#evRun').onchange = renderEvidence;
$('#stFile').oninput = () => { $('#stDir').textContent = $('#stFile').value; };
$('#evExport').onclick = () => {
  const r = evidence.find(x => x.id === $('#evRun').value) || evidence[0]; if (!r) return;
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([JSON.stringify(r, null, 2)], {type: 'application/json'}));
  a.download = 'evidence-' + r.id + '.json'; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  toast('Evidence exported · evidence-' + r.id + '.json');
};

/* ---------- LOAD DESIGNER ---------- */
let F = null, yaml = '', check = null, designerReady = false;
const RUNFOR = ['30s', '1m', '5m', '10m', '1h', '4h', 'until-stopped'];
const WARMUP = ['0s', '3s', '5s', '10s', '30s'];
const P99 = ['', '5ms', '10ms', '25ms', '100ms', '1s'];
const opt = (list, v, lab) => list.map(o => `<option value="${esc(o)}"${o === v ? ' selected' : ''}>${esc(lab ? lab(o) : o)}</option>`).join('');
async function designerShown() {
  if (designerReady) { drawStudio(); return; }
  try { F = await api('/api/console/designer/defaults'); } catch (e) { $('#stLint').innerHTML = `<div class="lint"><b>offline</b><span>${esc(e.message)}</span></div>`; return; }
  designerReady = true;
  $('#stForm').innerHTML = `
   <div class="field"><label for="stName">Name</label><input id="stName" type="text" value="${esc(F.name)}" spellcheck="false"></div>
   <div class="field"><label for="stQueue">Queue</label><input id="stQueue" type="text" value="${esc(F.queue || '')}" spellcheck="false"></div>
   <div class="field"><label for="stRate">Offered rate <span class="hv" id="stRateV"></span></label><input type="range" id="stRate" min="10" max="20000" step="10" value="${F.rate}"></div>
   <div class="field"><label for="stQType">Queue type</label><select id="stQType">${opt(['quorum', 'classic'], F.queueType)}</select></div>
   <div class="field"><label for="stSize">Message size <span class="hv" id="stSizeV"></span></label><input type="range" id="stSize" min="64" max="65536" step="64" value="${F.messageSize}"></div>
   <div class="field"><label for="stThreads">Publisher threads <span class="hv" id="stThreadsV"></span></label><input type="range" id="stThreads" min="1" max="16" value="${F.threads}"></div>
   <div class="field"><label for="stConc">Consumers <span class="hv" id="stConcV"></span></label><input type="range" id="stConc" min="1" max="32" value="${F.concurrency}"></div>
   <div class="field"><label for="stPrefetch">Prefetch <span class="hv" id="stPrefetchV"></span></label><input type="range" id="stPrefetch" min="1" max="1000" value="${F.prefetch}"></div>
   <div class="field"><label for="stWarm">Warm-up</label><select id="stWarm">${opt(WARMUP, F.warmup)}</select></div>
   <div class="field"><label for="stRun">Run for</label><select id="stRun">${opt(RUNFOR, F.runFor, o => o === 'until-stopped' ? 'until stopped' : o)}</select></div>
   <div class="field"><label for="stP99">Objective: p99 below</label><select id="stP99">${opt(P99, F.p99Below || '', o => o || 'none')}</select></div>
   <div class="field"><label for="stTput">Objective: at least, msg/s</label><input id="stTput" type="number" min="0" step="10" value="${F.throughputAtLeast || ''}" placeholder="none"></div>
   <div class="field wide"><label for="stBroker">Broker</label><input id="stBroker" type="text" value="${esc(F.broker)}" spellcheck="false"></div>`;
  const chk = (id, label, on) => `<label><input type="checkbox" id="${id}"${on ? ' checked' : ''}>${label}</label>`;
  $('#stChecks').innerHTML = chk('stConfirms', 'Publisher confirms', F.confirms) + chk('stUnthrottled', 'Unthrottled (find the ceiling)', F.unthrottled) +
    chk('stRandom', 'Incompressible payload', F.randomPayload) + chk('stDeclare', 'Declare the queue', F.declare !== false) + chk('stNoLost', 'Objective: nothing confirmed is lost', F.noMessagesLost);
  $$('#stForm input,#stForm select,#stChecks input').forEach(i => i.oninput = readStudio);
  refreshFiles();
  $('#stDir').textContent = $('#stFile').value; $('#stDir').title = overview.workloadsDir || '';
  readStudio();
}
async function refreshFiles() {
  try { const files = await api('/api/console/designer/files'); $('#stFiles').textContent = (files.length ? files.length + ' saved: ' + files.map(f => f.name).join(', ') : 'nothing saved yet') + ' · in ' + (overview.workloadsDir || 'the workloads directory'); } catch (e) { /* listing is a nicety */ }
}
let yamlTimer = 0, yamlSeq = 0;
function readStudio() {
  const v = id => $('#' + id).value, c = id => $('#' + id).checked;
  F = Object.assign(F, {name: v('stName'), queue: v('stQueue'), rate: +v('stRate'), queueType: v('stQType'), messageSize: +v('stSize'), threads: +v('stThreads'),
    concurrency: +v('stConc'), prefetch: +v('stPrefetch'), warmup: v('stWarm'), runFor: v('stRun'), p99Below: v('stP99') || null,
    throughputAtLeast: +v('stTput') > 0 ? +v('stTput') : null, broker: v('stBroker'),
    confirms: c('stConfirms'), unthrottled: c('stUnthrottled'), randomPayload: c('stRandom'), declare: c('stDeclare'), noMessagesLost: c('stNoLost')});
  $('#stRate').disabled = F.unthrottled;
  $('#stRateV').textContent = F.unthrottled ? 'unthrottled' : fmt(F.rate) + '/s';
  $('#stSizeV').textContent = F.messageSize >= 1024 ? (F.messageSize / 1024).toFixed(1) + ' KB' : F.messageSize + ' B';
  $('#stThreadsV').textContent = F.threads; $('#stConcV').textContent = F.concurrency; $('#stPrefetchV').textContent = F.prefetch;
  $('#stSummary').textContent = F.unthrottled ? 'unthrottled · as fast as the client can' : `${fmt(F.rate)}/s offered · ${(F.rate * F.messageSize / 1048576).toFixed(1)} MB/s · ${F.threads} thread${F.threads > 1 ? 's' : ''}`;
  drawStudio();
  clearTimeout(yamlTimer);
  yamlTimer = setTimeout(async () => {
    const seq = ++yamlSeq;
    try { const r = await api('/api/console/designer/yaml', F); if (seq !== yamlSeq) return; yaml = r.yaml; check = r.check; renderYaml(); lint(); }
    catch (e) { $('#stLint').innerHTML = `<div class="lint"><b>offline</b><span>${esc(e.message)}</span></div>`; }
  }, RM ? 0 : 120);
}
function renderYaml() {
  $('#stYaml').innerHTML = yaml.split('\n').map(l => {
    const m = l.match(/^(\s*)([A-Za-z0-9_]+:)(.*)$/);
    if (!m) return esc(l);
    const val = m[3].trim();
    return esc(m[1]) + `<span class="k">${esc(m[2])}</span>` + (val ? ' ' + (/^-?[0-9.]+$|^(true|false)$/.test(val) ? esc(val) : `<span class="s">${esc(val)}</span>`) : '');
  }).join('\n');
}
function lint() {
  const L = [];
  if (check && !check.valid) check.problems.forEach(p => L.push(`<div class="lint"><b>invalid</b><span>${esc(p)}</span></div>`));
  if (!F.confirms) L.push('<div class="lint"><b>no confirms</b><span>Without confirms a publish is a message handed to the socket; the evidence rules cannot tell a lost message from a slow one.</span></div>');
  if (F.unthrottled) L.push('<div class="lint"><b>unthrottled</b><span>No schedule, so latency is measured from the actual send and a stall flatters it. Use it to find the ceiling, then set a rate below it.</span></div>');
  if (!F.unthrottled && F.rate > 20000) L.push('<div class="lint"><b>heavy</b><span>' + fmt(F.rate) + '/s is more than one laptop sustains next to a broker; the measurement will show the machine, not the library.</span></div>');
  if (check && check.valid) L.unshift(`<div class="lint ok"><b>valid</b><span>The library parses this file. ${esc((check.description || '').split('\n').slice(2, 4).map(s => s.trim()).join(' · '))}</span></div>`);
  $('#stLint').innerHTML = L.join('');
}
const dur = s => { const m = String(s || '').match(/^(\d+)(ms|s|m|h)$/); return m ? +m[1] * {ms: .001, s: 1, m: 60, h: 3600}[m[2]] : 0; };
function drawStudio() {
  const c = $('#stChart'); if (!c || !c.offsetParent || !F) return;
  if (F.unthrottled) { emptyChart(c, 'no schedule: unthrottled publishes as fast as it can'); return; }
  const [x, w, h] = fit(c); const pad = {l: 52, r: 10, t: 10, b: 20};
  const warm = dur(F.warmup), total = F.runFor === 'until-stopped' ? 0 : dur(F.runFor), span = warm + (total || Math.max(60, warm * 4));
  const mx = F.rate * 1.15 || 1;
  x.font = '10px ' + css('--c-mono'); x.strokeStyle = css('--c-line'); x.fillStyle = css('--c-dim');
  [0, .5, 1].forEach(f => { const y = pad.t + (h - pad.t - pad.b) * (1 - f); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(w - pad.r, y); x.stroke(); x.fillText(fmt(mx * f) + '/s', 2, y + 3); });
  const px = t => pad.l + t / span * (w - pad.l - pad.r), py = v => pad.t + (1 - v / mx) * (h - pad.t - pad.b);
  if (warm) { x.fillStyle = css('--c-acc'); x.globalAlpha = .08; x.fillRect(px(0), pad.t, px(warm) - px(0), h - pad.t - pad.b); x.globalAlpha = 1; x.fillStyle = css('--c-dim'); x.fillText('warm-up, not measured', px(0) + 4, pad.t + 12); }
  x.fillStyle = css('--c-dim'); x.fillText('start', pad.l, h - 5); x.fillText(total ? F.runFor : 'until stopped', w - pad.r - 70, h - 5);
  x.beginPath(); x.moveTo(px(0), py(F.rate)); x.lineTo(px(span), py(F.rate)); x.strokeStyle = css('--c-acc'); x.lineWidth = 2; x.stroke();
  x.lineTo(px(span), py(0)); x.lineTo(px(0), py(0)); x.closePath(); x.fillStyle = css('--c-acc'); x.globalAlpha = .12; x.fill(); x.globalAlpha = 1;
}
$('#stValidate').onclick = async () => {
  if (!yaml) return;
  try { check = await api('/api/console/designer/validate', {yaml}); lint(); toast(check.valid ? 'Workload valid · the library parses it as written' : 'Not valid · ' + check.problems[0]); } catch (e) { toast(e.message); }
};
$('#stSave').onclick = async () => {
  try { const r = await api('/api/console/designer/files', {fileName: $('#stFile').value.trim(), yaml}); toast('Saved · ' + r.saved); refreshFiles(); }
  catch (e) { toast(e.message + (e.data && e.data.problems ? ' · ' + e.data.problems[0] : '')); }
};
$('#stStart').onclick = async () => {
  if (!yaml) return;
  const b = $('#stStart'); b.disabled = true;
  try { await api('/api/console/runs', {yaml}); toast('Load started · ' + F.name); show('loads'); }
  catch (e) { toast(e.message + (e.data && e.data.explanation ? ' · ' + e.data.explanation : '')); }
  finally { b.disabled = false; }
};

/* ---------- command palette ---------- */
let CMDS = [];
const baseCmds = () => [...VIEWS.map(v => [v[1], 'view', () => show(v[0])]),
 ['Start this load', 'command', () => { show('studio'); setTimeout(() => $('#stStart').click(), 300); }],
 ['Validate the workload file', 'command', () => { show('studio'); setTimeout(() => $('#stValidate').click(), 300); }],
 ['Stop the console\'s load', 'command', () => { show('loads'); $('#stopRun').click(); }],
 ...THEMES.map(t => ['Theme: ' + t[1], 'theme', () => { theme = t[0]; applyTheme(true); }]),
 ['Switch to light mode', 'theme', () => { mode = 'light'; modeChosen = true; applyTheme(true); }],
 ['Switch to dark mode', 'theme', () => { mode = 'dark'; modeChosen = true; applyTheme(true); }],
 ...Object.values(EXT).flatMap(x => x.cmds ? x.cmds() : [])];
function openPal() { opener = document.activeElement; $('#palette').hidden = false; $('#palIn').value = ''; listPal(''); $('#palIn').focus(); }
function closePal() { if ($('#palette').hidden) return; $('#palette').hidden = true; opener && opener.focus && opener.focus(); }
function listPal(q) {
  CMDS = baseCmds();
  const m = CMDS.filter(c => c[0].toLowerCase().includes(q.toLowerCase()));
  $('#palList').innerHTML = m.map(c => `<li><button data-i="${CMDS.indexOf(c)}">${esc(c[0])}<span>${c[1]}</span></button></li>`).join('') || '<li style="padding:10px;color:var(--c-dim)">Nothing matches. Try "loads", "evidence" or "designer".</li>';
  $$('#palList button').forEach(b => { b.onclick = () => { closePal(); CMDS[+b.dataset.i][2](); }; b.onkeydown = palNav; });
}
function palNav(e) {
  const items = $$('#palList button'), i = items.indexOf(document.activeElement);
  if (e.key === 'ArrowDown') { e.preventDefault(); (items[i + 1] || items[0]).focus(); }
  if (e.key === 'ArrowUp') { e.preventDefault(); i <= 0 ? $('#palIn').focus() : items[i - 1].focus(); }
}
$('#askBox').onclick = openPal; $('#askBox').onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPal(); } };
$('#palIn').oninput = e => listPal(e.target.value);
$('#palIn').onkeydown = e => { if (e.key === 'Escape') closePal(); if (e.key === 'ArrowDown') { e.preventDefault(); const b = $('#palList button'); b && b.focus(); } if (e.key === 'Enter') { const b = $('#palList button'); b && b.click(); } };
$('#palette').onclick = e => { if (e.target.id === 'palette') closePal(); };
addEventListener('keydown', e => {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); closeSettings(); openPal(); }
  if (e.key === 'Escape') { closePal(); closeSettings(); }
});

function toast(t) { const e = $('#toast'); e.textContent = t; e.classList.add('on'); clearTimeout(e._t); e._t = setTimeout(() => e.classList.remove('on'), 3200); }

/* ---------- boot + live loop ---------- */
function redrawAll() { drawLoads(); drawSoak(); drawStudio(); Object.values(EXT).forEach(x => x.redraw && x.redraw()); }

/* Charts follow their container, not only the window: a panel narrows when the grid changes. */
let rafPending = false;
const ro = new ResizeObserver(() => { if (!rafPending) { rafPending = true; requestAnimationFrame(() => { rafPending = false; redrawAll(); }); } });

/* What the other scripts on the page build on: the same helpers, the same palette, the same rail. */
window.AceConsole = {
  $, $$, esc, fmt, ms, api, css, fit, emptyChart, empty, toast, RM,
  show: v => show(v), view: () => view, setLive: () => setLive(),
  addView(d) { EXT[d.id] = d; VIEWS.push([d.id, d.label]); ICON[d.id] = d.icon; }
};

/* Booted once every script on the page has added its views, so the rail and the hash know them all. */
document.addEventListener('DOMContentLoaded', () => {
  const asked = (location.hash || '').slice(1);
  view = VIEWS.some(v => v[0] === asked) ? asked : (Object.values(EXT).map(x => x.home && x.home()).find(Boolean) || 'loads');
  applyTheme(false);
  buildRail();
  crumb(null);
  api('/api/console/overview').then(o => { overview = o; crumb(loadsData && loadsData.clients); }).catch(() => {}).finally(() => show(view));
  setInterval(() => { if (view === 'loads' && !document.hidden) loadLoads(); }, 1000);
  addEventListener('hashchange', () => { const v = location.hash.slice(1); if (v !== view && VIEWS.some(x => x[0] === v)) show(v); });
  $$('.main canvas, .main .pn, .stage').forEach(e => ro.observe(e));
});
})();

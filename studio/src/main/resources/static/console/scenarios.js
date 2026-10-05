/* Scenarios, scenario runs, reports and the broker: everything the studio's first interface did,
   in the console's design. Vanilla JS over the studio's own /api/scenarios, /api/runs (live over
   server-sent events), /api/presets and /api/broker; the shell and helpers are console.js's. */
(() => {
const C = window.AceConsole;
const {$, $$, esc, fmt, css, fit, emptyChart, empty, toast} = C;

/* ---------- talking to the studio ---------- */
async function call(path, method = 'GET', body, type = 'application/json') {
  const init = {method, headers: {}};
  if (body !== undefined) { init.headers['Content-Type'] = type; init.body = type === 'application/json' ? JSON.stringify(body) : body; }
  const r = await fetch(path, init);
  const text = await r.text();
  let data = null; try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
  if (!r.ok) {
    // The back end says what is wrong in a sentence; that is better than a status code.
    const said = data && (data.error || data.explanation);
    throw new Error(said ? said + (data.error && data.explanation ? ' · ' + data.explanation : '') : r.status + ' ' + r.statusText);
  }
  return data;
}
/* Fetches something and hands it to the browser as a file, named by the server when it says. */
async function download(path, fallback, body) {
  const r = await fetch(path, body === undefined ? {} : {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  if (!r.ok) throw new Error(r.status + ' ' + r.statusText);
  const blob = await r.blob(), m = /filename="([^"]+)"/.exec(r.headers.get('Content-Disposition') || '');
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = m ? m[1] : fallback;
  document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
/* The management API wants a user, and the AMQP URL usually carries the same one. Nothing is
   invented: when the URL has no user, none is sent. */
function withCredentials(c) {
  if (c.username) return c;
  try { const u = new URL(c.broker); return u.username ? {...c, username: decodeURIComponent(u.username), password: decodeURIComponent(u.password)} : c; }
  catch (e) { return c; }
}
const plural = (c, one) => c + ' ' + one + (c === 1 ? '' : 's');
const hostOf = u => { try { return new URL(u).host; } catch (e) { return u; } };
const clone = o => JSON.parse(JSON.stringify(o));
const compact = v => Intl.NumberFormat('en', {notation: 'compact'}).format(v);
const msF = v => v.toFixed(1) + 'ms';
const secs = iso => { const m = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(iso || ''); return m ? +(m[1] || 0) * 3600 + +(m[2] || 0) * 60 + +(m[3] || 0) : 0; };
const when = s => new Date(s).toLocaleString('en-GB', {dateStyle: 'medium', timeStyle: 'short'});

/* ---------- state ---------- */
const EMPTY = () => ({
  name: 'new scenario', description: '',
  exchanges: [{name: 'bench', type: 'topic'}],
  queues: [{name: 'bench.queue', type: 'classic', bindings: [{exchange: 'bench', routingKey: '#'}], consumers: {concurrency: 2, prefetch: 100}}],
  producers: [{name: 'load', exchange: 'bench', routingKeys: ['k'], rate: 5000, messageSize: 1024}],
  warmup: '5s', runFor: '30s'});
// Every queue type, assumed supported until a broker says otherwise.
const ALL_TYPES = [
  {id: 'classic', label: 'Classic', description: 'One node, no replication. The fastest, and the least safe.', supported: true},
  {id: 'classic-mirrored', label: 'Classic, mirrored', description: 'A classic queue with an HA policy. Removed in RabbitMQ 4.0.', supported: true},
  {id: 'quorum', label: 'Quorum', description: 'Replicated and durable. The safety costs throughput; measure it.', supported: true},
  {id: 'stream', label: 'Stream', description: 'An append-only log. Consumers read at their own offset.', supported: true}];
const EX_TYPES = [['topic', 'Topic', 'Routes on a pattern. The usual choice for events.'], ['fanout', 'Fanout', 'Every bound queue gets a copy; the key is ignored.'],
  ['direct', 'Direct', 'Routes on an exact key.'], ['headers', 'Headers', 'Routes on headers rather than on the key.']];

let scenario = EMPTY(), scenarioId = null, sel = null, designError = null;
let broker = 'amqp://guest:guest@localhost:5672', management = 'http://localhost:15672', tls = {enabled: false};
let probe = null, probing = false, probeFailure = null, probedOnce = false;
let check = {problems: [], warnings: []};
let presets = [], saved = [], history = [], picked = [], comparison = null;
let run = {id: null, reportId: null, samples: [], phase: 'STARTING', report: null, error: null, name: null, broker: null};
let source = null;

const secure = () => broker.trim().startsWith('amqps://');
const reachable = () => !!probe && probe.amqp.reachable && (!secure() || !!(probe.tls && probe.tls.completed));
const queueTypes = () => (probe && probe.capabilities.queueTypes) || ALL_TYPES;

/* ---------- BROKER ---------- */
async function look() {
  probing = true; probeFailure = null; renderBroker();
  try {
    probe = await call('/api/broker/probe', 'POST', withCredentials({broker, management, tls}));
    // Whatever answered is what a run will use, so the resolved URL replaces the typed one.
    if (reachable() && probe.amqp.url) { broker = probe.amqp.url; $('#bkAmqp').value = broker; }
  } catch (e) { probe = null; probeFailure = e.message; }
  probing = false; probedOnce = true;
  renderBroker(); renderChip(); renderLint(); renderInspector(); C.setLive();
}
const shortName = dn => { const m = /CN=([^,]+)/.exec(dn || ''); return m ? m[1] : dn; };
function renderBroker() {
  $('#bkTls').hidden = !secure();
  $('#bkWhen').textContent = probing ? 'looking' : probedOnce ? 'checked ' + new Date().toTimeString().slice(0, 5) : '';
  const out = $('#bkStatus');
  if (probing) { out.innerHTML = '<div class="gstat look"><span class="dot i"></span><b>Looking for a broker…</b></div>'; return; }
  if (!probedOnce) { out.innerHTML = '<div class="gstat"><span class="dot i"></span><b>Not checked yet</b></div>'; return; }
  const hs = probe && probe.tls, caps = probe && probe.capabilities, chain = hs && hs.chain && hs.chain[0];
  const note = t => `<p class="note">${t}</p>`;
  if (reachable()) {
    const sup = caps ? caps.queueTypes.filter(t => t.supported).map(t => t.label).join(', ') : '';
    out.innerHTML = `<div class="gstat ok"><span class="dot"></span><div><b>Found a broker at ${esc(hostOf(probe.amqp.url))}</b>` +
      (probe.amqp.rewritten ? note(esc(probe.amqp.explanation)) : '') +
      (hs && hs.completed ? note(esc(hs.protocol) + ' · ' + (hs.trusted ? 'certificate verified' : 'certificate NOT verified: encrypted, and proving nothing') +
        (hs.clientCertificateProvided ? ' · client certificate accepted' : '') + (tls.enabled && tls.clientCertificatePath && !hs.clientCertificateProvided ? ' · the broker did not ask for a client certificate' : '')) : '') +
      (chain ? note(`presented ${esc(shortName(chain.subject))}, signed by ${esc(shortName(chain.issuer))}, expires ${esc((chain.notAfter || '').slice(0, 10))}${chain.development ? ' · marked development-only' : ''}`) : '') +
      (caps && caps.version ? note(`RabbitMQ ${esc(caps.version)}: ${esc(sup)}`) : '') +
      (caps && !caps.known ? note('The management API did not answer, so only classic and quorum queues are offered. The run itself is unaffected.') : '') +
      `</div></div><div class="acts"><button class="btn pri" data-jump="scenarios">Design a scenario</button><button class="btn" data-look>Check again</button></div>`;
  } else {
    const hsFailed = hs && !hs.completed;
    out.innerHTML = `<div class="gstat bad"><span class="dot c"></span><div><b>${hsFailed ? 'The TLS handshake failed' : 'Nothing answered'}</b>` +
      note(esc(hsFailed ? hs.problem : (probeFailure || (probe && probe.amqp.explanation) || ''))) +
      (chain ? note(`it presented ${esc(shortName(chain.subject))}, signed by ${esc(shortName(chain.issuer))}`) : '') + '</div></div>' +
      (probe && probe.where !== 'host' ? `<div class="lint in"><b>where</b><span>The studio is ${esc(probe.whereDescription)}. ` +
        (probe.hostCandidates.length ? `It also tried ${esc(probe.hostCandidates.join(', '))}. If your broker is a container on the same network, use its container name instead of localhost.` : 'Use the broker\'s service name, such as rabbitmq.default.svc.cluster.local.') + '</span></div>' : '') +
      (probe && probe.amqp.attempts.length ? `<div class="tw"><table><caption class="sr">What was tried</caption><thead><tr><th>What was tried</th><th>Answer</th></tr></thead><tbody>${probe.amqp.attempts.map(a => `<tr><td class="mono"><code>${esc(a.url)}</code></td><td>${esc(a.reachable ? 'answered' : a.detail)}</td></tr>`).join('')}</tbody></table></div>` : '') +
      `<div class="acts"><button class="btn pri" data-look>Try again</button></div>` +
      `<p class="hint">No broker to hand? <code>docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:4-management</code></p>`;
  }
}
document.addEventListener('click', e => { if (e.target.closest && e.target.closest('[data-look]')) look(); });
$('#bkCheck').onclick = look;
$('#bkAmqp').value = broker; $('#bkMgmt').value = management;
let brokerEdited = false;
const brokerInput = () => { broker = $('#bkAmqp').value.trim(); management = $('#bkMgmt').value.trim(); brokerEdited = true; probe = null; renderBroker(); renderChip(); };
['#bkAmqp', '#bkMgmt'].forEach(s => {
  $(s).oninput = brokerInput;
  $(s).onkeydown = e => { if (e.key === 'Enter') { brokerEdited = false; look(); } };
  // Leaving the box checks it, as the studio's broker box always did.
  $(s).onblur = () => { if (brokerEdited) { brokerEdited = false; look(); } };
});
const tlsInput = () => {
  tls = {...tls, enabled: true, caPath: $('#bkCa').value || undefined, clientCertificatePath: $('#bkCert').value || undefined, clientKeyPath: $('#bkKey').value || undefined,
    allowDevelopmentCertificates: $('#bkDev').checked, trustAnyCertificate: $('#bkAny').checked};
};
['#bkCa', '#bkCert', '#bkKey', '#bkDev', '#bkAny'].forEach(s => { $(s).oninput = tlsInput; $(s).onchange = tlsInput; });
function renderChip() {
  const b = $('#scBroker'), st = run.id ? ['live', 'running'] : probing ? ['look', 'looking'] : reachable() ? [probe.amqp.rewritten ? 'rw' : 'ok', hostOf(probe.amqp.url)] : [probedOnce ? 'bad' : 'look', probedOnce ? (probe ? 'broker unreachable' : 'no broker') : 'not checked'];
  b.dataset.state = st[0]; b.lastChild.textContent = st[1];
  b.firstChild.className = 'dot' + (st[0] === 'bad' ? ' c' : st[0] === 'look' ? ' i' : st[0] === 'rw' ? ' w' : '');
}

/* ---------- SCENARIOS: the designer ---------- */
const svg = $('#scSvg');
const NW = 172, NH = 64, COLX = {producer: 0, exchange: 250, queue: 500}, ROW = 92;
function layoutNodes() {
  const layout = (scenario.ui && scenario.ui.layout) || {}, out = [];
  const add = (kind, list) => (list || []).forEach((n, i) => { const id = kind + ':' + n.name, p = layout[id] || {x: COLX[kind], y: i * ROW}; out.push({id, kind, n, x: +p.x || 0, y: +p.y || 0}); });
  add('producer', scenario.producers); add('exchange', scenario.exchanges); add('queue', scenario.queues);
  return out;
}
const cut = (s, n) => s.length > n ? s.slice(0, n - 1) + '…' : s;
function renderCanvas() {
  const ns = layoutNodes(), at = Object.fromEntries(ns.map(n => [n.id, n]));
  const sample = run.samples[run.samples.length - 1] || null;
  if (!ns.length) {
    svg.setAttribute('viewBox', '0 0 600 140'); svg.style.minWidth = '';
    svg.innerHTML = '<text class="sm" x="300" y="74" text-anchor="middle">Nothing yet: add an exchange, a queue and a producer.</text>';
    return;
  }
  const x0 = Math.min(...ns.map(n => n.x)) - 24, y0 = Math.min(...ns.map(n => n.y)) - 24;
  const w = Math.max(...ns.map(n => n.x + NW)) + 24 - x0, h = Math.max(...ns.map(n => n.y + NH)) + 24 - y0;
  svg.setAttribute('viewBox', `${x0} ${y0} ${w} ${h}`);
  // Never drawn smaller than legible: on a phone the topology scrolls inside its own box instead.
  svg.style.minWidth = Math.round(w * 0.8) + 'px';
  const edges = [];
  const edge = (a, b, live, label) => {
    if (!a || !b) return;
    const x1 = a.x + NW, y1 = a.y + NH / 2, x2 = b.x, y2 = b.y + NH / 2, mx = (x1 + x2) / 2;
    edges.push(`<path class="lk${live ? ' on' : ''}" d="M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2} ${y2}"/>` +
      (label ? `<text class="el" x="${mx}" y="${(y1 + y2) / 2 - 5}" text-anchor="middle">${esc(cut(label, 18))}</text>` : ''));
  };
  (scenario.producers || []).forEach(p => {
    if (!p.exchange) return;
    const live = sample && sample.producers.find(s => s.name === p.name);
    edge(at['producer:' + p.name], at['exchange:' + p.exchange], live && live.publishRate > 0, (p.routingKeys || [])[0]);
  });
  (scenario.queues || []).forEach(q => (q.bindings || []).forEach(b => {
    const live = sample && sample.queues.find(s => s.name === q.name);
    edge(at['exchange:' + b.exchange], at['queue:' + q.name], live && live.consumeRate > 0, b.routingKey);
  }));
  const nodes = ns.map(({id, kind, n, x, y}) => {
    let pills = [], rate, rateLabel;
    if (kind === 'producer') {
      const live = sample && sample.producers.find(s => s.name === n.name);
      pills = [[n.rate ? fmt(n.rate) + '/s' : 'unthrottled', 'flow'], [(n.messageSize || 1024) + ' B']];
      if (n.routingKeys && n.routingKeys.length && n.routingKeys[0]) pills.push([n.routingKeys.join(', ')]);
      if (live) { rate = live.publishRate; rateLabel = 'published/s'; }
    } else if (kind === 'exchange') {
      pills = [[n.type, 'in']];
    } else {
      const live = sample && sample.queues.find(s => s.name === n.name), c = n.consumers || {};
      const consuming = c.enabled !== false && (c.concurrency ?? 1) > 0;
      pills = [[n.type || 'classic'], consuming ? [(c.concurrency ?? 1) + ' consumers', 'flow'] : ['no consumers', 'warn']];
      if (live && live.depth) pills.push([fmt(live.depth) + ' waiting', 'warn']);
      if (live) { rate = live.consumeRate; rateLabel = 'consumed/s'; }
    }
    let budget = 28;
    const spans = pills.map(([t, tone]) => { if (budget <= 0) return ''; const s = cut(t, budget); budget -= s.length + 3; return `<tspan class="${tone || ''}">${esc(s)}</tspan>`; }).filter(Boolean).join('<tspan> · </tspan>');
    const on = sel && sel.kind === kind && sel.name === n.name;
    return `<g class="nd${n.enabled === false ? ' off' : ''}" data-id="${esc(id)}" data-kind="${kind}" transform="translate(${x} ${y})" tabindex="0" role="button" aria-pressed="${!!on}" aria-label="${kind} ${esc(n.name)}">
      <title>${esc(kind + ' ' + n.name + ' · ' + pills.map(p => p[0]).join(' · '))}</title>
      <rect class="nodeShape${on ? ' hit' : ''}" width="${NW}" height="${NH}" rx="6"/>
      ${kind !== 'producer' ? `<circle class="port" cx="0" cy="${NH / 2}" r="4"/>` : ''}${kind !== 'queue' ? `<circle class="port" cx="${NW}" cy="${NH / 2}" r="4"/>` : ''}
      <text class="sk" x="10" y="16">${kind}</text>
      ${rate !== undefined ? `<text class="rt" x="${NW - 10}" y="16" text-anchor="end">${fmt(rate)} ${rateLabel}</text>` : ''}
      <text class="sn" x="10" y="35">${esc(cut(n.name, 21))}</text>
      <text class="sm" x="10" y="53">${spans}</text></g>`;
  });
  svg.innerHTML = edges.join('') + nodes.join('') + '<path id="scDrag" class="lk on" d="" hidden/>';
}
/* Selecting is a click; binding is a drag from an exchange to a queue. Anything else dragged is
   ignored rather than creating a binding the broker would refuse. */
let drag = null, suppressClick = false;
const svgPoint = e => { const p = svg.createSVGPoint(); p.x = e.clientX; p.y = e.clientY; return p.matrixTransform(svg.getScreenCTM().inverse()); };
svg.addEventListener('pointerdown', e => {
  const g = e.target.closest('.nd[data-kind="exchange"]'); if (!g || e.button) return;
  const n = layoutNodes().find(x => x.id === g.dataset.id);
  drag = {from: g.dataset.id.slice(9), x: n.x + NW, y: n.y + NH / 2, sx: e.clientX, sy: e.clientY, moved: false};
});
addEventListener('pointermove', e => {
  if (!drag) return;
  if (!drag.moved && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) < 6) return;
  drag.moved = true; e.preventDefault();
  const p = svgPoint(e), d = $('#scDrag'); d.removeAttribute('hidden');
  d.setAttribute('d', `M${drag.x} ${drag.y} C${(drag.x + p.x) / 2} ${drag.y} ${(drag.x + p.x) / 2} ${p.y} ${p.x} ${p.y}`);
});
addEventListener('pointercancel', () => { if (drag) { drag = null; renderCanvas(); } });
addEventListener('pointerup', e => {
  if (!drag) return;
  const d = drag; drag = null;
  if (!d.moved) return;
  suppressClick = true; setTimeout(() => { suppressClick = false; }, 0);
  const hit = document.elementFromPoint(e.clientX, e.clientY), q = hit && hit.closest && hit.closest('.nd[data-kind="queue"]');
  if (q) bind(d.from, q.dataset.id.slice(6)); else renderCanvas();
});
function bind(exchange, queue) {
  const q = scenario.queues.find(x => x.name === queue); if (!q) return;
  q.bindings = [...(q.bindings || []), {exchange, routingKey: '#'}];
  changed(sel && sel.kind === 'queue' && sel.name === queue);
  toast('Bound ' + queue + ' to ' + exchange);
}
svg.addEventListener('click', e => {
  if (suppressClick) return;
  const g = e.target.closest('.nd');
  select(g ? {kind: g.dataset.kind, name: g.dataset.id.slice(g.dataset.kind.length + 1)} : null);
});
svg.addEventListener('keydown', e => {
  const g = e.target.closest('.nd'); if (!g || (e.key !== 'Enter' && e.key !== ' ')) return;
  e.preventDefault(); select({kind: g.dataset.kind, name: g.dataset.id.slice(g.dataset.kind.length + 1)});
  const back = $(`#scSvg .nd[data-id="${CSS.escape(g.dataset.id)}"]`); back && back.focus();
});
function select(s) { sel = s; renderCanvas(); renderInspector(); }

/* The inspector: everything about the selected thing, and nothing about anything else. */
const ins = $('#scInspector');
const field = (id, label, input, cls = '') => `<div class="field${cls}"><label for="${id}">${label}</label>${input}</div>`;
const text = (id, v, ph = '') => `<input id="${id}" type="text" value="${esc(v ?? '')}" placeholder="${esc(ph)}" spellcheck="false">`;
const number = (id, v, attrs = '') => `<input id="${id}" type="number" value="${esc(v ?? '')}" ${attrs}>`;
const check1 = (id, label, on) => `<div class="checks"><label><input type="checkbox" id="${id}"${on ? ' checked' : ''}>${label}</label></div>`;
const hint = t => `<p class="hint">${t}</p>`;
const row = (...f) => `<div class="frow">${f.join('')}</div>`;
const h5 = t => `<h5>${t}</h5>`;
const opts = (list, v) => list.map(([val, lab]) => `<option value="${esc(val)}"${val === v ? ' selected' : ''}>${esc(lab)}</option>`).join('');
const on = (id, fn, ev = 'input') => { const el = $('#' + id); if (el) el.addEventListener(ev, () => fn(el)); };
const numOrNone = v => v === '' ? undefined : Number(v);
/* An objective with one field changed, or none at all: an object of nothing is dropped, so a
   scenario that asks for nothing exports without an empty `expect` block. */
function withExpect(cur, changes) {
  const m = {...cur, ...changes};
  Object.keys(m).forEach(k => { if (m[k] === undefined || m[k] === '') delete m[k]; });
  return Object.keys(m).length ? m : undefined;
}
const setOrDrop = (o, k, v) => { if (v === undefined) delete o[k]; else o[k] = v; };
function choices(name, list, current, onPick) {
  return `<div class="choices" role="radiogroup">${list.map(t => `<label class="choice${t.supported === false ? ' dis' : ''}"${t.whyNot ? ` title="${esc(t.whyNot)}"` : ''}>
    <input type="radio" name="${name}" value="${esc(t.id)}"${t.id === current ? ' checked' : ''}${t.supported === false ? ' disabled' : ''}>
    <span><strong>${esc(t.label)}</strong><span>${esc(t.supported === false ? t.whyNot : t.description)}</span></span></label>`).join('')}</div>`;
}
function renderInspector() {
  const kind = sel ? sel.kind : null;
  $('#scInsTitle').textContent = kind ? kind[0].toUpperCase() + kind.slice(1) : 'The scenario';
  $('#scInsCnt').textContent = kind ? sel.name : 'select something on the canvas to configure it';
  if (!kind) return scenarioForm();
  const list = scenario[kind === 'queue' ? 'queues' : kind + 's'] || [];
  const node = list.find(x => x.name === sel.name);
  if (!node) { ins.innerHTML = ''; return; }
  ({producer: producerForm, exchange: exchangeForm, queue: queueForm})[kind](node);
  const remove = $('#scRemove');
  remove.onclick = () => {
    const key = kind === 'queue' ? 'queues' : kind + 's';
    scenario[key] = scenario[key].filter(x => x !== node); sel = null; changed(true);
  };
}
const rename = (node, kind) => el => { node.name = el.value; sel = {kind, name: el.value}; changed(); $('#scInsCnt').textContent = el.value; };
function scenarioForm() {
  ins.innerHTML = field('fName', 'Name', text('fName', scenario.name)) +
    field('fDesc', 'What it is for', `<textarea id="fDesc" rows="3" placeholder="The question this run answers">${esc(scenario.description || '')}</textarea>`) +
    row(field('fWarm', 'Warm-up', text('fWarm', scenario.warmup ?? '5s')), field('fFor', 'Measure for', text('fFor', scenario.runFor ?? '30s'))) +
    hint('The warm-up runs the whole scenario and throws the numbers away, so class loading, JIT and the first collection land there rather than in the p99.') +
    check1('fDeclare', 'Declare the topology before running', scenario.declare !== false) +
    hint('Turn this off against a real environment. Declaring there is either refused for mismatched arguments or, worse, quietly creates something subtly different from what production runs and measures that instead.') +
    hint('Select something on the canvas to configure it.');
  on('fName', el => { scenario.name = el.value; changed(); });
  on('fDesc', el => { scenario.description = el.value; changed(); });
  on('fWarm', el => { scenario.warmup = el.value; changed(); });
  on('fFor', el => { scenario.runFor = el.value; changed(); });
  on('fDeclare', el => { setOrDrop(scenario, 'declare', el.checked ? undefined : false); changed(); }, 'change');
}
function producerForm(p) {
  const exOpts = [['', '(the default exchange, by queue name)'], ...(scenario.exchanges || []).map(x => [x.name, x.name])];
  ins.innerHTML = field('fName', 'Name', text('fName', p.name)) +
    field('fEx', 'Publishes to', `<select id="fEx">${opts(exOpts, p.exchange)}</select>`) +
    field('fKeys', 'Routing keys, comma separated', text('fKeys', (p.routingKeys || []).join(', '), 'order.placed, order.cancelled')) +
    hint('Several keys are used in turn, which is what makes a topic exchange behave like one. A producer on a single key measures one binding however many the exchange has.') +
    row(field('fRate', 'Rate, messages a second', number('fRate', p.rate ?? 1000, 'min="0"')), field('fSize', 'Message size, bytes', number('fSize', p.messageSize ?? 1024, 'min="1"'))) +
    hint('Say the rate and let the studio work out the threads. A rate of 0 is unthrottled: it finds the ceiling, and makes the latency meaningless while doing it.') +
    check1('fConfirms', 'Wait for publisher confirms', p.confirms !== false) +
    check1('fOn', 'Include in the run', p.enabled !== false) +
    h5('What it must prove') +
    row(field('fAtLeast', 'At least, a second', number('fAtLeast', p.expect && p.expect.achievedRateAtLeast, 'min="0" placeholder="—"')),
      field('fWithin', 'Within % of the rate', number('fWithin', p.expect && p.expect.withinPercentOfOffered, 'min="0" max="100" placeholder="—"'))) +
    check1('fNoFail', 'Every publish must succeed', !!(p.expect && p.expect.noFailures)) +
    hint('What is left blank is not checked. Anything set here decides the exit code when this scenario is run from a pipeline, so a build can fail on a number rather than on somebody reading a chart.') +
    '<button class="btn danger" id="scRemove">Remove this producer</button>';
  on('fName', rename(p, 'producer'));
  on('fEx', el => { p.exchange = el.value; changed(); }, 'change');
  on('fKeys', el => { p.routingKeys = el.value.split(',').map(k => k.trim()).filter(Boolean); changed(); });
  on('fRate', el => { p.rate = Number(el.value); changed(); });
  on('fSize', el => { p.messageSize = Number(el.value); changed(); });
  on('fConfirms', el => { setOrDrop(p, 'confirms', el.checked ? undefined : false); changed(); }, 'change');
  on('fOn', el => { setOrDrop(p, 'enabled', el.checked ? undefined : false); changed(); }, 'change');
  const ex = changes => { setOrDrop(p, 'expect', withExpect(p.expect, changes)); changed(); };
  on('fAtLeast', el => ex({achievedRateAtLeast: numOrNone(el.value)}));
  on('fWithin', el => ex({withinPercentOfOffered: numOrNone(el.value)}));
  on('fNoFail', el => ex({noFailures: el.checked ? true : undefined}), 'change');
}
function exchangeForm(x) {
  ins.innerHTML = field('fName', 'Name', text('fName', x.name)) +
    `<div class="field"><span class="lab" id="fTypeL">Type</span>${choices('exchange-type', EX_TYPES.map(([id, label, description]) => ({id, label, description})), x.type)}</div>` +
    check1('fOn', 'Include in the run', x.enabled !== false) +
    '<button class="btn danger" id="scRemove">Remove this exchange</button>';
  on('fName', rename(x, 'exchange'));
  $$('#scInspector input[name="exchange-type"]').forEach(r => r.onchange = () => { x.type = r.value; changed(); });
  on('fOn', el => { setOrDrop(x, 'enabled', el.checked ? undefined : false); changed(); }, 'change');
}
function queueForm(q) {
  const exNames = (scenario.exchanges || []).map(x => x.name), c = q.consumers || {};
  const binds = (q.bindings || []).map((b, i) => {
    const o = exNames.map(n => [n, n]);
    // A binding left pointing at a renamed exchange stays visible, because hiding it is how it survives to the run.
    if (!exNames.includes(b.exchange)) o.push([b.exchange, b.exchange + ' (no such exchange)']);
    return `<div class="frow brow" data-i="${i}">${field('fBx' + i, 'Exchange', `<select id="fBx${i}" data-bx="${i}">${opts(o, b.exchange)}</select>`)}${field('fBk' + i, 'Routing key', `<input id="fBk${i}" data-bk="${i}" type="text" value="${esc(b.routingKey)}" placeholder="order.*" spellcheck="false">`)}<button class="btn ghost" data-unbind="${i}" title="Remove this binding">Unbind</button></div>`;
  }).join('');
  const args = Object.entries(q.arguments || {}).map(([k, v], i) =>
    `<div class="frow brow" data-key="${esc(k)}">${field('fAk' + i, 'Name', `<input id="fAk${i}" type="text" data-ak value="${esc(k)}" spellcheck="false">`)}${field('fAv' + i, 'Value', `<input id="fAv${i}" type="text" data-av value="${esc(String(v ?? ''))}" spellcheck="false">`)}<button class="btn ghost" data-unarg title="Remove">Remove</button></div>`).join('');
  const dlx = [['', '(none: rejected messages are dropped)'], ...exNames.map(n => [n, n])];
  ins.innerHTML = field('fName', 'Name', text('fName', q.name)) +
    `<div class="field"><span class="lab">Type</span>${choices('queue-type', queueTypes(), q.type || 'classic')}</div>` +
    field('fDlx', 'Dead-letter exchange', `<select id="fDlx">${opts(dlx, q.deadLetterExchange || '')}</select>`) +
    h5('Bound to') + ((q.bindings || []).length ? '' : hint('Nothing. Drag from an exchange to this queue on the canvas, or add one below.')) + binds +
    `<button class="btn ghost" id="fBind"${exNames.length ? '' : ' disabled'}>+ binding</button>` +
    h5('Arguments') + hint('What the broker is told when this queue is declared, beyond its type: <code>x-max-length</code>, <code>x-message-ttl</code>, <code>x-max-age</code> on a stream. Numbers are sent as numbers; anything else as text.') + args +
    '<button class="btn ghost" id="fArg">+ argument</button>' +
    h5('Consumers') +
    row(field('fConc', 'How many', number('fConc', c.concurrency ?? 1, 'min="0"')), field('fPre', 'Prefetch', number('fPre', c.prefetch ?? 100, 'min="0"'))) +
    row(field('fHt', 'Handler takes', text('fHt', c.handlerTime || '', '1ms')), field('fFail', 'Fails this often', number('fFail', c.failureRate ?? 0, 'min="0" max="1" step="0.01"'))) +
    check1('fConsOn', 'Consumers running', c.enabled !== false) +
    hint('Turning these off is not the same as removing them: the queue keeps filling and the run measures what the backlog costs, which is usually the question. The settings stay where they are, ready to be switched back on.') +
    check1('fOn', 'Include this queue in the run', q.enabled !== false) +
    h5('What it must prove') +
    row(field('fP99', 'p99 under', text('fP99', q.expect && q.expect.p99Below, '50ms')), field('fP999', 'p99.9 under', text('fP999', q.expect && q.expect.p999Below, '—'))) +
    field('fRateMin', 'Handles at least, a second', number('fRateMin', q.expect && q.expect.consumeRateAtLeast, 'min="0" placeholder="—"')) +
    check1('fNoBack', 'Must not be deeper at the end than at the start', !!(q.expect && q.expect.noBacklog)) +
    hint((q.type || 'classic') === 'stream'
      ? 'A stream keeps what it has served, so its depth is the length of the log rather than a backlog: this one is not checked for a stream.'
      : 'What is left blank is not checked. Anything set here decides the exit code when this scenario is run from a pipeline.') +
    '<button class="btn danger" id="scRemove">Remove this queue</button>';
  on('fName', rename(q, 'queue'));
  $$('#scInspector input[name="queue-type"]').forEach(r => r.onchange = () => { q.type = r.value; changed(true); });
  on('fDlx', el => { setOrDrop(q, 'deadLetterExchange', el.value || undefined); changed(); }, 'change');
  $$('#scInspector [data-bx]').forEach(s => s.onchange = () => { q.bindings[+s.dataset.bx].exchange = s.value; changed(); });
  $$('#scInspector [data-bk]').forEach(s => s.oninput = () => { q.bindings[+s.dataset.bk].routingKey = s.value; changed(); });
  $$('#scInspector [data-unbind]').forEach(b => b.onclick = () => { q.bindings = q.bindings.filter((_, i) => i !== +b.dataset.unbind); changed(true); });
  $('#fBind').onclick = () => { q.bindings = [...(q.bindings || []), {exchange: exNames[0] || '', routingKey: '#'}]; changed(true); };
  /* `x-max-length: "1000"` and `x-max-length: 1000` are different declarations, and the first is
     refused, so a value that reads as a number is kept as one. Renaming keeps the position. */
  const setArg = (k, v) => { const n = String(v).trim() !== '' && !Number.isNaN(Number(v)); q.arguments = {...(q.arguments || {}), [k]: n ? Number(v) : v}; };
  $$('#scInspector [data-key]').forEach(r => {
    r.querySelector('[data-ak]').oninput = e => { const from = r.dataset.key, to = e.target.value; q.arguments = Object.fromEntries(Object.entries(q.arguments || {}).map(([k, v]) => [k === from ? to : k, v])); r.dataset.key = to; changed(); };
    r.querySelector('[data-av]').oninput = e => { setArg(r.dataset.key, e.target.value); changed(); };
    r.querySelector('[data-unarg]').onclick = () => { const rest = {...q.arguments}; delete rest[r.dataset.key]; setOrDrop(q, 'arguments', Object.keys(rest).length ? rest : undefined); changed(true); };
  });
  $('#fArg').onclick = () => { setArg('x-max-length', ''); changed(true); };
  const cons = ch => { q.consumers = {...(q.consumers || {}), ...ch}; Object.keys(q.consumers).forEach(k => q.consumers[k] === undefined && delete q.consumers[k]); changed(); };
  on('fConc', el => cons({concurrency: Number(el.value)}));
  on('fPre', el => cons({prefetch: Number(el.value)}));
  on('fHt', el => cons({handlerTime: el.value || undefined}));
  on('fFail', el => cons({failureRate: Number(el.value) || undefined}));
  on('fConsOn', el => cons({enabled: el.checked ? undefined : false}), 'change');
  on('fOn', el => { setOrDrop(q, 'enabled', el.checked ? undefined : false); changed(); }, 'change');
  const ex = changes => { setOrDrop(q, 'expect', withExpect(q.expect, changes)); changed(); };
  on('fP99', el => ex({p99Below: el.value || undefined}));
  on('fP999', el => ex({p999Below: el.value || undefined}));
  on('fRateMin', el => ex({consumeRateAtLeast: numOrNone(el.value)}));
  on('fNoBack', el => ex({noBacklog: el.checked ? true : undefined}), 'change');
}

/* Every change redraws the canvas and is checked by the back end a moment later. A structural one
   (something added or removed) also rebuilds the inspector; typing never does, so focus stays. */
let checkTimer = 0, checkSeq = 0;
function changed(structural) {
  renderCanvas(); renderTitle();
  if (structural) renderInspector();
  clearTimeout(checkTimer);
  checkTimer = setTimeout(async () => {
    const seq = ++checkSeq;
    try { const r = await call('/api/scenarios/check', 'POST', scenario); if (seq === checkSeq) { check = {problems: r.problems, warnings: r.warnings}; renderLint(); } }
    catch (e) { /* the check is advice; a failed one changes nothing */ }
  }, 350);
}
function renderTitle() {
  $('#scName').textContent = scenario.name || 'Topology';
  const n = (k, one) => { const c = (scenario[k] || []).length; return c + ' ' + one + (c === 1 ? '' : 's'); };
  $('#scSub').textContent = (scenarioId ? 'saved' : 'not saved') + ' · ' + n('producers', 'producer') + ' · ' + n('exchanges', 'exchange') + ' · ' + n('queues', 'queue') + ' · warm-up ' + (scenario.warmup || '5s') + ' · measure ' + (scenario.runFor || '30s');
}
function renderLint() {
  const L = [];
  if (designError) L.push(`<div class="lint cr" data-dismiss><b>could not</b><span>${esc(designError)}</span></div>`);
  if (probe && probe.amqp.rewritten && reachable()) L.push(`<div class="lint in"><b>broker</b><span>${esc(probe.amqp.explanation)}. The run will use <code>${esc(probe.amqp.url)}</code>.</span></div>`);
  if (probedOnce && !probing && !reachable()) L.push(`<div class="lint cr"><b>no broker</b><span>${esc((probe && probe.amqp.explanation) || probeFailure || 'Nothing answered at ' + broker)} <a href="#broker" data-jump="broker">Connect to one</a> before running.</span></div>`);
  check.problems.forEach(p => L.push(`<div class="lint cr"><b>will not run</b><span>${esc(p)}</span></div>`));
  if (!check.problems.length) check.warnings.forEach(w => L.push(`<div class="lint"><b>warning</b><span>${esc(w)}</span></div>`));
  $('#scLint').innerHTML = L.join('');
  const busy = !!run.id;
  $('#scRun').disabled = check.problems.length > 0 || busy;
  $('#scRun').title = check.problems.length ? 'Fix what the designer says first' : busy ? 'A run is going' : 'Run this scenario';
  $('#scStop').hidden = !busy;
}
$('#scLint').addEventListener('click', e => { if (e.target.closest('[data-dismiss]')) { designError = null; renderLint(); } });

function openScenario(s, id, note) {
  scenario = clone(s); scenarioId = id; sel = null; designError = null;
  renderCanvas(); renderInspector(); changed(); renderLint();
  if (note) toast(note);
}
function newScenario() { openScenario(EMPTY(), null, 'A new scenario'); }
function add(kind) {
  const key = kind === 'queue' ? 'queues' : kind + 's', taken = (scenario[key] || []).map(x => x.name);
  let i = taken.length + 1; while (taken.includes(`${kind}-${i}`)) i++;
  const name = `${kind}-${i}`;
  const made = {exchange: {name, type: 'topic'}, queue: {name, type: 'classic', consumers: {concurrency: 1, prefetch: 100}},
    producer: {name, exchange: (scenario.exchanges && scenario.exchanges[0] && scenario.exchanges[0].name) || '', routingKeys: ['k'], rate: 1000, messageSize: 1024}}[kind];
  scenario[key] = [...(scenario[key] || []), made];
  sel = {kind, name}; changed(true);
}
async function saveScenario() {
  try { const r = await call(scenarioId ? '/api/scenarios/' + encodeURIComponent(scenarioId) : '/api/scenarios', scenarioId ? 'PUT' : 'POST', scenario); scenarioId = r.id; renderTitle(); loadSaved(); toast('Saved · ' + scenario.name); }
  catch (e) { designError = e.message; renderLint(); }
}
const exportAs = f => download(f === 'json' ? '/api/scenarios/export' : '/api/scenarios/export.yaml', 'acemq-workload.' + f, scenario).catch(e => { designError = e.message; renderLint(); });
async function importTopology() {
  try { openScenario(await call('/api/broker/import', 'POST', withCredentials({broker, management})), null, 'Imported the topology from ' + hostOf(broker)); }
  catch (e) { designError = e.message; renderLint(); }
}
$('#scAddX').onclick = () => add('exchange'); $('#scAddQ').onclick = () => add('queue'); $('#scAddP').onclick = () => add('producer');
$('#scNew').onclick = newScenario; $('#scSave').onclick = saveScenario;
$('#scJson').onclick = () => exportAs('json'); $('#scYaml').onclick = () => exportAs('yaml');
$('#scImport').onclick = importTopology;
// The other half of Export: a scenario that failed in a pipeline is opened here, changed, and run again.
$('#scOpen').onclick = () => $('#scFile').click();
$('#scFile').onchange = async e => {
  const f = e.target.files[0]; e.target.value = ''; if (!f) return;
  try { const r = await call('/api/scenarios/import?fileName=' + encodeURIComponent(f.name), 'POST', await f.text(), 'text/plain'); openScenario(r.scenario, null, 'Opened ' + f.name); }
  catch (err) { designError = err.message; renderLint(); }
};
$('#scRun').onclick = start; $('#scStop').onclick = stop;

async function loadPresets() {
  try { presets = await call('/api/presets'); } catch (e) { presets = []; }
  $('#scPresets').innerHTML = presets.length ? presets.map((p, i) => `<div class="rule preset"><span class="badge na">${i + 1}</span><div><h4>${esc(p.title)}</h4><p>${esc(p.question)}</p>
    <div class="pills"><span class="tag">${plural((p.scenario.queues || []).length, 'queue')}</span><span class="tag">${plural((p.scenario.producers || []).length, 'producer')}</span><span class="tag">${esc(p.scenario.runFor || '')}</span></div></div>
    <span class="ev"><button class="btn" data-preset="${esc(p.id)}" aria-label="Open the preset ${esc(p.title)}">Open</button></span></div>`).join('') : '<p class="hint">No presets came back from the studio.</p>';
  $$('#scPresets [data-preset]').forEach(b => b.onclick = () => openPreset(b.dataset.preset));
}
function openPreset(id) {
  const p = presets.find(x => x.id === id); if (!p) return;
  openScenario(p.scenario, null, 'Opened the preset · ' + p.title);
  C.show('scenarios'); scrollTo({top: 0, behavior: C.RM ? 'auto' : 'smooth'});
}
async function loadSaved() {
  try { saved = await call('/api/scenarios'); } catch (e) { saved = []; }
  $('#scSavedCnt').textContent = saved.length ? saved.length + ' saved' : 'nothing saved yet';
  $('#scSaved').innerHTML = saved.length ? saved.map(s => `<tr><td class="mono">${esc(s.name)}</td><td class="wrap">${esc(s.description || '')}</td>
    <td class="acts"><button class="btn" data-open-saved="${esc(s.id)}">Open</button><button class="btn ghost" data-del-saved="${esc(s.id)}" aria-label="Delete ${esc(s.name)}">Delete</button></td></tr>`).join('')
    : '<tr><td colspan="3" class="wrap" style="color:var(--c-dim)">Save a scenario and it is kept here, in the studio\'s database.</td></tr>';
  $$('[data-open-saved]').forEach(b => b.onclick = async () => {
    try { openScenario(await call('/api/scenarios/' + encodeURIComponent(b.dataset.openSaved)), b.dataset.openSaved, 'Opened ' + (saved.find(s => s.id === b.dataset.openSaved) || {}).name); scrollTo({top: 0}); }
    catch (e) { designError = e.message; renderLint(); }
  });
  $$('[data-del-saved]').forEach(b => b.onclick = async () => {
    try { await call('/api/scenarios/' + encodeURIComponent(b.dataset.delSaved), 'DELETE'); if (scenarioId === b.dataset.delSaved) { scenarioId = null; renderTitle(); } loadSaved(); }
    catch (e) { toast(e.message); }
  });
}

/* ---------- SCENARIO RUN ---------- */
async function start() {
  if (check.problems.length || run.id) return;
  if (!reachable()) { toast('Connect to a broker first'); C.show('broker'); if (!probing) look(); return; }
  if (source) { source.close(); source = null; }
  run = {id: null, reportId: null, samples: [], phase: 'STARTING', report: null, error: null, name: scenario.name, broker};
  C.show('runs');
  try {
    const s = await call('/api/runs', 'POST', {scenarioId, scenario, broker, tls: tls.enabled ? tls : undefined});
    run.id = s.id; run.reportId = s.id; run.broker = s.broker;
    attach(s.id);
  } catch (e) { run.error = e.message; }
  renderRun(); renderLint(); renderChip(); C.setLive();
}
async function stop() {
  if (!run.id) return;
  try { await call('/api/runs/' + encodeURIComponent(run.id) + '/stop', 'POST', {}); toast('Stopping · it reports on the window it measured'); } catch (e) { toast(e.message); }
}
/* Server-sent events: one-way, the browser reconnects on its own, and what has already happened is
   replayed on connect, so a reload picks the run back up. A replayed reading is not counted twice. */
function attach(id) {
  if (source) source.close();
  run.samples = [];
  source = new EventSource('/api/runs/' + encodeURIComponent(id) + '/stream');
  source.addEventListener('sample', e => {
    const s = JSON.parse(e.data), last = run.samples[run.samples.length - 1];
    if (!last || secs(s.elapsed) > secs(last.elapsed)) { run.samples.push(s); soon(); }
  });
  source.addEventListener('phase', e => { run.phase = JSON.parse(e.data).phase; soon(); });
  const end = () => { source && source.close(); source = null; run.id = null; renderRun(); renderLint(); renderChip(); renderCanvas(); loadHistory(); C.setLive(); };
  source.addEventListener('finished', e => { run.report = JSON.parse(e.data); end(); toast('Finished · ' + run.report.verdict); });
  source.addEventListener('failed', e => { run.error = JSON.parse(e.data).error; end(); });
}
let pending = false;
const soon = () => { if (!pending) { pending = true; requestAnimationFrame(() => { pending = false; renderRun(); if (C.view() === 'scenarios') renderCanvas(); C.setLive(); }); } };
const PHASES = [['STARTING', 'Starting', 'connecting and declaring'], ['WARMUP', 'Warm-up', 'these numbers are thrown away'], ['MEASURING', 'Measuring', 'what the report is made of'], ['DRAINING', 'Draining', 'what is in flight'], ['DONE', 'Report', 'the verdict and the file']];
function phaseLabel(p) { return {WARMUP: 'warming up: these numbers are thrown away', MEASURING: 'measuring', DRAINING: 'draining what is in flight'}[p] || 'starting'; }
const VERDICT = {passed: ['ok', 'Passed'], failed: ['cr', 'Failed'], invalid: ['cr', 'Invalid: this run did not measure what it was asked to']};
const SEV = {INVALID: ['cr', '✕'], FAILED: ['cr', '✕'], WARNING: ['wn', '!'], INFO: ['na', 'i']};
function renderRun() {
  const live = !!run.id, r = run.report, latest = run.samples[run.samples.length - 1];
  const nothing = !live && !r && !run.error && !run.samples.length;
  $('#rnBody').hidden = nothing;
  if (nothing) empty($('#rnEmpty'), 'Nothing is running', 'Design a scenario, or take one of the presets, and press <b>Run</b>. A finished run is opened again from <a href="#reports" data-jump="reports">Reports</a>.');
  else $('#rnEmpty').hidden = true;
  $('#rnSub').textContent = nothing ? 'nothing running' : [run.name, run.reportId, run.broker && hostOf(run.broker)].filter(Boolean).join(' · ');
  $('#rnStop').hidden = !live;
  const savable = r && run.reportId && !live;
  $('#rnSave').hidden = !savable;
  if (savable) ['html', 'md', 'json'].forEach(f => { $('#rn' + {html: 'Html', md: 'Md', json: 'Json'}[f]).href = `/api/runs/${encodeURIComponent(run.reportId)}/report.${f}`; });
  const idx = r ? 5 : Math.max(0, PHASES.findIndex(p => p[0] === run.phase));
  $('#rnSteps').innerHTML = PHASES.map((p, i) => `<div class="step${i < idx ? ' done' : ''}${i === idx && live ? ' now' : ''}"><span class="n">${i + 1} · ${p[1]}</span><span class="t">${p[2]}</span><div class="bar"><span style="width:${i < idx ? 100 : i === idx && live ? 50 : 0}%"></span></div></div>`).join('');
  $('#rnPhase').textContent = live ? ({STARTING: 'Starting', WARMUP: 'Warm-up', MEASURING: 'Measuring', DRAINING: 'Draining'}[run.phase] || 'Starting') : r ? 'Finished' : run.error ? 'Failed' : '—';
  $('#rnPhaseD').textContent = live ? phaseLabel(run.phase) : r ? (r.stoppedEarly ? 'stopped early' : 'ran its course') : ' ';
  $('#rnElapsed').innerHTML = latest ? Math.round(secs(latest.elapsed)) + '<small>s</small>' : '—';
  $('#rnBlocked').textContent = latest && latest.blocked ? 'the broker is blocking publishers' : latest ? 'publishers not blocked' : ' ';
  $('#rnBlocked').className = 'd' + (latest && latest.blocked ? ' cr' : '');
  const pub = latest ? latest.producers.reduce((a, p) => a + p.publishRate, 0) : null, cons = latest ? latest.queues.reduce((a, q) => a + q.consumeRate, 0) : null;
  $('#rnPub').innerHTML = pub == null ? '—' : fmt(pub) + '<small>/s</small>';
  $('#rnCons').innerHTML = cons == null ? '—' : fmt(cons) + '<small>/s</small>';
  $('#rnPubD').textContent = latest ? fmt(latest.producers.reduce((a, p) => a + p.published, 0)) + ' sent' : ' ';
  $('#rnConsD').textContent = latest ? fmt(latest.queues.reduce((a, q) => a + q.consumed, 0)) + ' handled' : ' ';
  $('#rnWait').textContent = latest ? fmt(latest.queues.reduce((a, q) => a + (q.depth || 0), 0)) : '—';
  $('#rnErr').innerHTML = run.error ? `<div class="lint cr"><b>run</b><span>${esc(run.error)}</span></div>` : '';
  $('#rnVerdict').innerHTML = r ? verdictHtml(r) : '';
  $('#rnDepthPn').hidden = !(latest && latest.queues.length);
  $('#rnNodes').innerHTML = latest ? latest.producers.map(p => `<div class="lcard static"><div class="lh"><b>${esc(p.name)}</b><span class="chip">producer</span></div>
      <div class="big">${fmt(p.publishRate)}<small>published/s</small></div><span class="where">${fmt(p.published)} sent${p.failed ? ` · <em class="cr">${fmt(p.failed)} failed</em>` : ''}</span></div>`).join('') +
    latest.queues.map(q => `<div class="lcard static"><div class="lh"><b>${esc(q.name)}</b><span class="chip">queue</span></div>
      <div class="big${q.consuming ? '' : ' wn'}">${fmt(q.consumeRate)}<small>consumed/s</small></div><span class="where">${q.consuming ? fmt(q.consumed) + ' handled' : 'nothing consuming'}${q.depth != null ? ' · ' + fmt(q.depth) + ' waiting' : ''}</span></div>`).join('') : '';
  $('#rnNodes').hidden = !latest;
  $('#rnTables').hidden = !r;
  if (r) {
    const lat = (l, k) => l.count ? msF(l[k]) : '—';
    $('#rnQT').innerHTML = r.queues.map(q => `<tr><td class="mono">${esc(q.name)}</td><td>${esc(q.type)}</td><td class="num">${q.consumers}</td><td class="num">${fmt(q.consumeRate)}</td><td class="num">${lat(q.endToEnd, 'p50')}</td><td class="num">${lat(q.endToEnd, 'p99')}</td><td class="num">${lat(q.endToEnd, 'p999')}</td><td class="num"${q.grew ? ' style="color:var(--c-warn)"' : ''}>${q.depthAtEnd != null ? fmt(q.depthAtEnd) : '—'}${q.grew ? ' ↑' : ''}</td></tr>`).join('');
    $('#rnPT').innerHTML = r.producers.map(p => `<tr><td class="mono">${esc(p.name)}</td><td class="num">${p.offeredRate ? fmt(p.offeredRate) : 'unthrottled'}</td><td class="num">${fmt(p.achievedRate)}</td><td class="num"${p.failed ? ' style="color:var(--c-crit)"' : ''}>${fmt(p.failed)}</td><td class="num">${lat(p.sendLag, 'p99')}</td><td class="num">${lat(p.publishLatency, 'p99')}</td></tr>`).join('');
  }
  drawRun();
}
function verdictHtml(r) {
  const v = VERDICT[r.verdict] || ['cr', r.verdict];
  return `<div class="pn verdict" data-verdict="${esc(r.verdict)}"><div class="ph"><h3>Verdict</h3><span class="cnt">${esc(r.scenario)} · started ${esc(when(r.startedAt))}</span></div><div class="pb">
    <div class="vt ${v[0]}">${esc(v[1])}</div>
    <p class="vs">${Math.round(r.durationMs / 1000)}s measured · ${fmt(r.totalPublished)} published · ${fmt(r.totalConsumed)} consumed${r.stoppedEarly ? ' · stopped early' : ''}${r.blockedMs ? ` · publishers blocked ${fmt(r.blockedMs)} ms${r.blockedReason ? ' (' + esc(r.blockedReason) + ')' : ''}` : ''}</p>
    ${r.findings.map(f => { const b = SEV[f.severity] || SEV.INFO; return `<div class="rule finding" data-severity="${esc(f.severity)}"><span class="badge ${b[0]}" aria-label="${esc(f.severity)}">${b[1]}</span><div><h4>${esc(f.observation)}</h4><p>${esc(f.implication)}</p></div><span class="ev">[${esc(f.severity)}] ${esc(f.rule)}</span></div>`; }).join('')}
  </div></div>`;
}
const DEPTH = ['--c-acc2', '--c-info', '--c-warn', '--c-acc', '--c-crit', '--c-ok'];
function drawRun() {
  const xs = run.samples.map(s => secs(s.elapsed));
  lines($('#rnRate'), xs, [
    {name: 'published/s', col: css('--c-acc'), v: run.samples.map(s => s.producers.reduce((a, p) => a + p.publishRate, 0)), fill: true},
    {name: 'consumed/s', col: css('--c-acc2'), v: run.samples.map(s => s.queues.reduce((a, q) => a + q.consumeRate, 0)), fill: true}], $('#rnRateLegend'));
  const latest = run.samples[run.samples.length - 1], names = latest ? latest.queues.map(q => q.name) : [];
  lines($('#rnDepth'), xs, names.map((n, i) => ({name: n, col: css(DEPTH[i % DEPTH.length]), v: run.samples.map(s => { const q = s.queues.find(x => x.name === n); return q && q.depth != null ? q.depth : 0; })})), $('#rnDepthLegend'));
}
/* A line chart on the console's canvas helpers: time across in seconds, compact values up the side. */
function lines(c, xs, series, legend) {
  if (!c || !c.offsetParent) return;
  legend.innerHTML = series.map(s => `<span><i style="background:${s.col}"></i>${esc(s.name)}</span>`).join('');
  if (xs.length < 2) { emptyChart(c, run.id ? 'waiting for readings' : 'no readings'); return; }
  const [x, w, h] = fit(c), pad = {l: 46, r: 12, t: 12, b: 24};
  const mx = Math.max(1, ...series.flatMap(s => s.v)) * 1.12, a = xs[0], span = (xs[xs.length - 1] - a) || 1;
  const px = t => pad.l + (t - a) / span * (w - pad.l - pad.r), py = v => pad.t + (1 - v / mx) * (h - pad.t - pad.b);
  x.font = '10px ' + css('--c-mono'); x.lineWidth = 1;
  [0, .25, .5, .75, 1].forEach(f => { const y = py(mx * f); x.strokeStyle = css('--c-line'); x.beginPath(); x.moveTo(pad.l, y); x.lineTo(w - pad.r, y); x.stroke(); x.fillStyle = css('--c-dim'); x.fillText(compact(mx * f), 4, y + 3); });
  const ticks = Math.max(2, Math.min(6, Math.floor((w - pad.l) / 80)));
  for (let i = 0; i <= ticks; i++) { const t = a + span * i / ticks; x.fillStyle = css('--c-dim'); x.fillText((span < ticks * 2 ? t.toFixed(1) : Math.round(t)) + 's', Math.min(px(t) - 6, w - pad.r - 20), h - 7); }
  series.forEach(s => {
    x.beginPath(); s.v.forEach((v, i) => i ? x.lineTo(px(xs[i]), py(v)) : x.moveTo(px(xs[i]), py(v)));
    x.strokeStyle = s.col; x.lineWidth = 2; x.stroke();
    if (s.fill) { x.lineTo(px(xs[xs.length - 1]), py(0)); x.lineTo(px(xs[0]), py(0)); x.closePath(); x.fillStyle = s.col; x.globalAlpha = .1; x.fill(); x.globalAlpha = 1; }
  });
}
$('#rnStop').onclick = stop;

/* ---------- REPORTS ---------- */
async function loadHistory() {
  try { history = await call('/api/runs'); } catch (e) { history = []; }
  picked = picked.filter(id => history.some(r => r.id === id && r.status === 'finished'));
  const mine = run.reportId && history.find(r => r.id === run.reportId);
  if (mine && run.name === 'the run in progress') { run.name = mine.scenarioName; run.broker = mine.broker; renderRun(); }
  renderHistory(); C.setLive();
}
const RESULT = {passed: 'ok', failed: 'cr', invalid: 'wn', running: 'in'};
function renderHistory() {
  $('#rpRuns').hidden = !history.length;
  if (!history.length) empty($('#rpEmpty'), 'No scenario run yet', 'Every run started from <a href="#scenarios" data-jump="scenarios">Scenarios</a> is kept here with its report, in HTML, Markdown and JSON, and every reading it took, so two can be compared.');
  else $('#rpEmpty').hidden = true;
  $('#rpCnt').textContent = history.length + ' kept';
  $('#rpT').innerHTML = history.map(r => {
    const done = r.status === 'finished', res = r.verdict || r.status, id = encodeURIComponent(r.id);
    return `<tr class="${run.reportId === r.id ? 'sel' : ''}"><td><label class="tick"><input type="checkbox" data-pick="${esc(r.id)}"${done ? '' : ' disabled'}${picked.includes(r.id) ? ' checked' : ''} aria-label="Compare ${esc(r.scenarioName)}, ${esc(when(r.startedAt))}" title="${done ? 'compare this run' : 'a run that did not finish has nothing to compare'}"></label></td>
      <td class="mono">${esc(r.scenarioName)}<i>${esc(r.id)}</i></td><td class="mono" style="color:var(--c-dim)">${esc(r.broker)}</td><td>${esc(when(r.startedAt))}</td>
      <td><span class="tag ${RESULT[res] || ''}"${r.error ? ` title="${esc(r.error)}"` : ''}>${esc(res)}</span></td>
      <td class="links">${done ? `<a href="/api/runs/${id}/report.html" download>HTML</a><a href="/api/runs/${id}/report.md" download>Markdown</a><a href="/api/runs/${id}/report.json" download>JSON</a>` : '—'}</td>
      <td class="acts">${done ? `<button class="btn" data-open-run="${esc(r.id)}">Open</button>` : ''}<button class="btn ghost" data-del-run="${esc(r.id)}" title="Forget this run and its readings" aria-label="Delete the run ${esc(r.id)}">Delete</button></td></tr>`;
  }).join('');
  $('#rpHint').textContent = ['Tick two runs to compare them.', 'One more.', 'Ready.'][picked.length];
  $('#rpCompare').disabled = picked.length !== 2;
  $$('[data-pick]').forEach(b => b.onchange = () => {
    // The oldest tick drops off rather than refusing the third.
    picked = b.checked ? [...picked, b.dataset.pick].slice(-2) : picked.filter(id => id !== b.dataset.pick);
    renderHistory();
  });
  $$('[data-open-run]').forEach(b => b.onclick = () => openRun(b.dataset.openRun));
  $$('[data-del-run]').forEach(b => b.onclick = async () => {
    const id = b.dataset.delRun;
    try { await call('/api/runs/' + encodeURIComponent(id), 'DELETE'); } catch (e) { /* already gone */ }
    picked = picked.filter(x => x !== id);
    if (run.reportId === id && !run.id) { run = {id: null, reportId: null, samples: [], phase: 'STARTING', report: null, error: null, name: null, broker: null}; renderRun(); }
    loadHistory();
  });
}
async function openRun(id) {
  try {
    const [report, samples] = await Promise.all([call('/api/runs/' + encodeURIComponent(id) + '/report'), call('/api/runs/' + encodeURIComponent(id) + '/samples')]);
    const s = history.find(r => r.id === id) || {};
    if (run.id) { toast('A run is going; it stays where it is'); return; }
    run = {id: null, reportId: id, samples, phase: 'DONE', report, error: null, name: s.scenarioName || report.scenario, broker: s.broker};
    C.show('runs');
  } catch (e) { toast(e.message); }
}
/* Direction is carried by the colour and the word, never by the sign: a latency that went up is
   worse and a rate that went up is better. Past ten times, a multiplier reads where a percentage does not. */
const cmpFmt = (v, unit) => v == null ? '—' : unit === 'ms' ? v.toFixed(1) + 'ms' : fmt(v);
const size = ch => Math.abs(ch) >= 10 ? '×' + (1 + ch).toFixed(ch > 100 ? 0 : 1) : (ch > 0 ? '+' : '') + (ch * 100).toFixed(1) + '%';
const cmpLabel = r => r.verdict === 'missing' ? (r.a == null ? 'only after' : 'only before') : r.change == null ? (r.verdict === 'same' ? 'same' : r.verdict) : size(r.change) + ' · ' + r.verdict;
const CMP = {better: '--c-ok', worse: '--c-crit', missing: '--c-warn'};
async function compare() {
  if (picked.length !== 2) return;
  try { comparison = await call(`/api/runs/compare?a=${encodeURIComponent(picked[0])}&b=${encodeURIComponent(picked[1])}`); } catch (e) { toast(e.message); return; }
  renderComparison();
}
function renderComparison() {
  $('#rpCmp').hidden = !comparison; $('#rpClose').hidden = !comparison;
  if (!comparison) return;
  const rows = comparison.rows, moved = rows.filter(r => r.verdict === 'better' || r.verdict === 'worse');
  $('#rpCmpTitle').textContent = `${(comparison.a && comparison.a.scenarioName) || 'run A'} → ${(comparison.b && comparison.b.scenarioName) || 'run B'}`;
  $('#rpCmpHint').textContent = (comparison.a && comparison.b ? `${when(comparison.a.startedAt)} against ${when(comparison.b.startedAt)}. ` : '') +
    (moved.length ? `${moved.length} of ${rows.length} measurements moved by more than 5%.` : 'Nothing moved by more than 5%, which on ordinary hardware means these are the same run.');
  $('#rpCmpT').innerHTML = rows.map(r => `<tr><td class="mono">${esc(r.node)}</td><td>${esc(r.metric)}</td><td class="num">${cmpFmt(r.a, r.unit)}</td><td class="num">${cmpFmt(r.b, r.unit)}</td><td class="num" data-verdict="${esc(r.verdict)}" style="color:var(${CMP[r.verdict] || '--c-dim'})">${esc(cmpLabel(r))}</td></tr>`).join('');
}
$('#rpCompare').onclick = compare;
$('#rpClose').onclick = () => { comparison = null; renderComparison(); };

/* ---------- registration with the shell ---------- */
const brokerLive = () => run.id ? 'running' : probing ? 'looking for a broker' : reachable() ? 'broker · ' + hostOf(probe.amqp.url) : probedOnce ? 'no broker' : 'broker not checked';
const firstLook = () => { if (!probedOnce && !probing) look(); };
C.addView({id: 'scenarios', label: 'Scenarios', icon: '<circle cx="5" cy="6" r="2"/><circle cx="5" cy="18" r="2"/><path d="M10 9.5 13 12l-3 2.5z"/><rect x="16" y="9" width="5" height="6" rx="1"/><path d="M7 6.5 10 10M7 17.5l3-3.5M13 12h3"/>',
  show() { firstLook(); renderCanvas(); renderInspector(); renderTitle(); renderLint(); renderChip(); }, redraw() {}, live: brokerLive, running: () => !!run.id,
  cmds: () => [['New scenario', 'command', () => { C.show('scenarios'); newScenario(); }], ['Save the scenario', 'command', () => { C.show('scenarios'); saveScenario(); }],
    ['Run the scenario', 'command', () => { C.show('scenarios'); start(); }], ['Stop the scenario run', 'command', stop],
    ['Open a scenario file', 'command', () => { C.show('scenarios'); $('#scFile').click(); }],
    ['Export the scenario as JSON', 'command', () => exportAs('json')], ['Export the scenario as YAML', 'command', () => exportAs('yaml')],
    ['Import the topology from the broker', 'command', () => { C.show('scenarios'); importTopology(); }],
    ['Add an exchange', 'command', () => { C.show('scenarios'); add('exchange'); }], ['Add a queue', 'command', () => { C.show('scenarios'); add('queue'); }], ['Add a producer', 'command', () => { C.show('scenarios'); add('producer'); }],
    ...presets.map(p => ['Preset: ' + p.title, 'preset', () => openPreset(p.id)])]});
C.addView({id: 'runs', label: 'Scenario run', icon: '<circle cx="12" cy="12" r="9"/><path d="m10 8 6 4-6 4z"/>',
  show() { firstLook(); renderRun(); }, redraw: drawRun, running: () => !!run.id,
  live: () => run.id ? phaseLabel(run.phase) : run.report ? 'finished · ' + run.report.verdict : run.error ? 'failed' : 'no run'});
C.addView({id: 'reports', label: 'Reports', icon: '<path d="M6 3h9l3 3v15H6z"/><path d="M9 10h6M9 14h6M9 18h4"/>',
  show() { loadHistory(); renderComparison(); }, live: () => history.length + ' run' + (history.length === 1 ? '' : 's') + ' kept',
  cmds: () => [['Compare the two ticked runs', 'command', () => { C.show('reports'); compare(); }]]});
C.addView({id: 'broker', label: 'Broker', icon: '<rect x="4" y="4" width="16" height="6" rx="1"/><rect x="4" y="14" width="16" height="6" rx="1"/><path d="M8 7h.01M8 17h.01"/>',
  show() { firstLook(); renderBroker(); }, live: brokerLive, running: () => !!run.id,
  cmds: () => [['Check the broker again', 'command', () => { C.show('broker'); look(); }]]});

/* ---------- boot ---------- */
loadPresets(); loadSaved(); loadHistory();
renderCanvas(); renderInspector(); renderTitle(); renderBroker(); renderChip(); renderRun(); changed();
// A run started before this page was opened is still going; attach to it rather than show nothing.
call('/api/runs/current').then(c => { if (c && c.running && c.id) { run = {...run, id: c.id, reportId: c.id, name: 'the run in progress'}; attach(c.id); renderRun(); renderLint(); renderChip(); } }).catch(() => {});
})();

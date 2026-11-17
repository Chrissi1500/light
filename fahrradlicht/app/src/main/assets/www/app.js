'use strict';
// =================================================================== Bruecke zur App
const $ = (id) => document.getElementById(id);
const pending = {};
let seq = 0;
const DEMO = !window.Native;
if (DEMO) window.Native = demoNative();

function esp(cmd, timeout = 8000) {
  return new Promise((res) => {
    const id = 'r' + (++seq);
    pending[id] = res;
    Native.send(id, JSON.stringify(cmd), timeout);
  });
}
window.onReply = (id, json) => { const r = JSON.parse(json); if (pending[id]) { pending[id](r); delete pending[id]; } };
window.onNative = (type, json) => { const d = JSON.parse(json); (handlers[type] || (() => {}))(d); };
window.onBack = () => { if (!$('sheet').classList.contains('hidden')) { closeSheet(); return true; } return false; };

const store = {
  get(k, d = '') { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} },
};
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('show'), 2600); }
function esc(s) { return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function buzz() { if (store.get('vibrate', '1') === '1') Native.vibrate(25); }

// =================================================================== Ansichten
function show(view) {
  for (const v of ['Connect', 'Install', 'Lights']) $('view' + v).classList.toggle('hidden', v !== view);
}
function setStatus(kind, text) {
  const s = $('status');
  s.className = 'pill ' + kind;
  s.textContent = text;
}

// =================================================================== Verbindung
let connected = false;
let manualDisconnect = false;
let retryTimer = null;
let retryDelay = 2000;
let lastAddr = store.get('lastAddr');
let lastName = store.get('lastName');

const handlers = {
  bleDevice(d) {
    const li = document.createElement('li');
    li.innerHTML = `<span><b>${esc(d.name)}</b><br><small>${esc(d.address)}</small></span><small>${d.rssi} dBm</small>`;
    li.onclick = () => connectTo(d.address, d.name);
    $('devList').appendChild(li);
  },
  bleState(d) {
    switch (d.state) {
      case 'scanning': $('scanState').textContent = 'Suche läuft …'; break;
      case 'idle': $('scanState').textContent = $('devList').children.length ? 'Gerät antippen zum Verbinden.' : 'Nichts gefunden. ESP32 an? Bluetooth an?'; break;
      case 'connecting': setStatus('busy', 'verbinde …'); break;
      case 'connected':
        connected = true; retryDelay = 2000;
        setStatus('on', lastName || 'verbunden');
        onConnected();
        break;
      case 'disconnected':
        connected = false;
        setStatus('off', 'getrennt');
        scheduleReconnect();
        break;
      case 'error':
        $('scanState').textContent = d.detail;
        if (!connected) { setStatus('off', 'nicht verbunden'); scheduleReconnect(); }
        toast(d.detail);
        break;
    }
  },
};

function connectTo(addr, name) {
  clearTimeout(retryTimer);
  manualDisconnect = false;
  lastAddr = addr; lastName = name || addr;
  store.set('lastAddr', addr); store.set('lastName', lastName);
  Native.bleConnect(addr);
}

function scheduleReconnect() {
  clearTimeout(retryTimer);
  if (manualDisconnect || !lastAddr) { show('Connect'); return; }
  // Im Hintergrund weiter versuchen, z. B. wenn der ESP32 neu startet
  setStatus('busy', 'suche ' + (lastName || 'ESP32') + ' …');
  retryTimer = setTimeout(() => { if (!connected && !manualDisconnect) Native.bleConnect(lastAddr); }, retryDelay);
  retryDelay = Math.min(retryDelay * 1.5, 10000);
  if (!lights.length) show('Connect');
}

$('btnScan').onclick = () => { $('devList').innerHTML = ''; Native.bleScan(); };
$('status').onclick = () => { if (!connected && lastAddr) { retryDelay = 2000; connectTo(lastAddr, lastName); } };

// =================================================================== Licht-Befehle
let lights = [];
let cfg = { invert: false, blink_ms: 400 };
let busy = 0;

async function licht(req) {
  const code = 'import licht\nlicht.cmd(' + JSON.stringify(JSON.stringify(req)) + ')';
  busy++;
  const r = await esp({ cmd: 'run', code, timeout: 5 }, 9000);
  busy--;
  if (!r.ok) {
    const e = String(r.err || '');
    if (/no module named|ImportError/i.test(e)) return { ok: false, missing: true, err: 'Licht-Programm fehlt' };
    return { ok: false, err: e.split('\n').filter(Boolean).pop() || 'Fehler' };
  }
  const line = String(r.out || '').split('\n').reverse().find((l) => l.startsWith('LICHT:'));
  if (!line) return { ok: false, err: 'Keine Antwort vom Licht-Programm' };
  return JSON.parse(line.slice(6));
}

async function send(req) {
  const r = await licht(req);
  if (r.missing) { show('Install'); return r; }
  if (!r.ok) { toast(r.err); $('lightErr').textContent = r.err; refresh(); return r; }
  apply(r);
  return r;
}

function apply(r) {
  lights = r.lights || [];
  cfg = { invert: !!r.invert, blink_ms: r.blink_ms || 400 };
  $('lightErr').textContent = lights.filter((l) => l.err).map((l) => l.name + ': ' + l.err).join(' · ');
  render();
  show('Lights');
}

async function refresh() {
  if (!connected) return;
  const r = await licht({ a: 'get' });
  if (r.missing) show('Install');
  else if (r.ok) apply(r);
}

async function onConnected() {
  const r = await licht({ a: 'get' });
  if (r.missing) { show('Install'); return; }
  if (r.ok) apply(r);
  else { toast(r.err); show('Lights'); }
}

// Zustand regelmaessig abgleichen (z. B. falls der ESP32 neu gestartet ist)
setInterval(() => { if (connected && !busy && !document.hidden && $('sheet').classList.contains('hidden')) refresh(); }, 15000);

// =================================================================== Darstellung
const COLORS = { extra: '#ffd24a' };
const ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"/></svg>';
const MODE_TXT = { off: 'aus', on: 'an', blink: 'blinkt' };

function render() {
  const normal = lights;
  const grid = $('lightGrid');
  grid.innerHTML = '';
  const single = normal.length === 1;
  grid.classList.toggle('single', single);
  normal.forEach((l, i) => {
    const t = document.createElement('div');
    const wide = !single && normal.length % 2 === 1 && i === normal.length - 1;
    t.className = 'tile m-' + l.mode + (single ? ' hero' : wide ? ' wide' : '') + (l.err ? ' error' : '');
    t.style.setProperty('--c', COLORS[l.id] || '#ffd24a');
    t.innerHTML = `
      <button class="main" type="button">
        <span class="bulb">${ICON}</span>
        <span style="min-width:0"><div class="name">${esc(l.name)}</div>
        <div class="state">${l.err ? 'Fehler' : MODE_TXT[l.mode] + (l.mode !== 'off' && l.bright < 100 ? ' · ' + l.bright + ' %' : '')}</div></span>
      </button>
      <div class="seg">
        <button data-m="off" class="${l.mode === 'off' ? 'on' : ''}" type="button">Aus</button>
        <button data-m="on" class="${l.mode === 'on' ? 'on' : ''}" type="button">An</button>
        <button data-m="blink" class="${l.mode === 'blink' ? 'on' : ''}" type="button">Blink</button>
      </div>
      <input type="range" min="5" max="100" step="5" value="${l.bright}" aria-label="Helligkeit ${esc(l.name)}">`;
    t.querySelector('.main').onclick = () => { buzz(); optimistic(l.id, { mode: l.mode === 'off' ? 'on' : 'off' }); send({ a: 'toggle', id: l.id }); };
    t.querySelectorAll('.seg button').forEach((b) => b.onclick = () => {
      buzz(); optimistic(l.id, { mode: b.dataset.m }); send({ a: 'set', id: l.id, mode: b.dataset.m });
    });
    t.querySelector('input').onchange = (e) => {
      const v = +e.target.value; optimistic(l.id, { bright: v, mode: l.mode === 'off' ? 'on' : l.mode });
      send({ a: 'set', id: l.id, bright: v, mode: l.mode === 'off' ? 'on' : l.mode });
    };
    grid.appendChild(t);
  });

  $('allRow').classList.toggle('hidden', normal.length < 2);
}

function optimistic(id, patch) {
  const l = lights.find((x) => x.id === id);
  if (l) Object.assign(l, patch);
  render();
}

$('btnAllOn').onclick = () => { buzz(); lights.forEach((l) => { l.mode = 'on'; }); render(); send({ a: 'all', mode: 'on' }); };
$('btnAllOff').onclick = () => { buzz(); lights.forEach((l) => { l.mode = 'off'; }); render(); send({ a: 'all', mode: 'off' }); };

// =================================================================== Installation
async function install() {
  const btns = [$('btnInstall'), $('btnReinstall')];
  const say = (t) => { $('installState').textContent = t; toast(t); };
  btns.forEach((b) => b.disabled = true);
  try {
    const code = Native.asset('esp32/licht.py');
    if (!code) throw new Error('licht.py fehlt in der App');
    const info = await esp({ cmd: 'info' }, 8000);
    if (!info.ok) throw new Error(info.err || 'ESP32 antwortet nicht');
    if (info.has_app) {
      const cur = await esp({ cmd: 'read', path: 'app.py' }, 15000);
      if (cur.ok && !/import licht/.test(cur.data || '') &&
          !confirm('Auf dem ESP32 läuft schon ein anderes Programm (app.py). Durch das Licht-Programm ersetzen?')) {
        return;
      }
    }
    say('Übertrage licht.py …');
    const w = await esp({ cmd: 'write', path: 'licht.py', data: code }, 40000);
    if (!w.ok) throw new Error(w.err);
    const ls = await esp({ cmd: 'ls', path: '/' }, 8000);
    const f = (ls.files || []).find((x) => x.name === 'licht.py');
    const want = new TextEncoder().encode(code).length;
    if (!f || (f.size && f.size !== want)) throw new Error('Übertragung unvollständig – bitte nochmal versuchen');
    say('Richte Autostart ein …');
    const d = await esp({ cmd: 'deploy', code: 'import licht\nlicht.main()\n' }, 8000);
    if (!d.ok) throw new Error(d.err);
    say('Fertig! ESP32 startet neu und verbindet sich gleich wieder …');
    closeSheet();
  } catch (e) {
    say('Fehler: ' + e.message);
  } finally {
    btns.forEach((b) => b.disabled = false);
  }
}
$('btnInstall').onclick = install;
$('btnReinstall').onclick = () => { if (connected) install(); else toast('Erst verbinden'); };

// =================================================================== Einstellungen
let cfgDraft = [];
function openSheet() {
  $('devName').textContent = lastAddr ? `${lastName} (${lastAddr})` + (connected ? ' – verbunden' : ' – nicht verbunden') : 'Noch kein Gerät gewählt';
  cfgDraft = lights.map((l) => ({ id: l.id, name: l.name, pin: l.pin, kind: l.kind }));
  $('cfgInvert').checked = cfg.invert;
  $('cfgBlink').value = String([500, 400, 250].reduce((a, b) => Math.abs(b - cfg.blink_ms) < Math.abs(a - cfg.blink_ms) ? b : a));
  renderCfg();
  $('sheet').classList.remove('hidden');
}
function closeSheet() { $('sheet').classList.add('hidden'); }
function renderCfg() {
  const box = $('cfgLights');
  box.innerHTML = cfgDraft.length ? '' : '<p class="hint">Verbinde dich mit dem ESP32, um die Lichter einzustellen.</p>';
  cfgDraft.forEach((l, i) => {
    const d = document.createElement('div');
    d.className = 'cfg-light';
    d.innerHTML = `<div class="kind">Licht ${i + 1}</div>
      <input value="${esc(l.name)}" aria-label="Name" placeholder="Name">
      <input type="number" min="0" max="48" inputmode="numeric" value="${esc(l.pin)}" aria-label="GPIO" placeholder="GPIO">
      <button type="button" aria-label="Entfernen">✕</button>`;
    const [nameIn, pinIn] = d.querySelectorAll('input');
    nameIn.oninput = () => { l.name = nameIn.value; };
    pinIn.oninput = () => { l.pin = pinIn.value === '' ? '' : +pinIn.value; };
    d.querySelector('button').onclick = () => { cfgDraft.splice(i, 1); renderCfg(); };
    box.appendChild(d);
  });
  $('btnAddLight').disabled = !connected;
  $('btnSaveCfg').disabled = !connected;
}
$('btnAddLight').onclick = () => {
  cfgDraft.push({ id: 'l' + Date.now().toString(36), name: 'Licht ' + (cfgDraft.length + 1), pin: '', kind: 'light' });
  renderCfg();
};
$('btnSaveCfg').onclick = async () => {
  const bad = cfgDraft.find((l) => l.pin === '' || isNaN(l.pin) || !String(l.name).trim());
  if (bad) { toast('Bitte jedem Licht einen Namen und einen GPIO geben'); return; }
  const pins = cfgDraft.map((l) => l.pin);
  if (new Set(pins).size !== pins.length) { toast('Jeder GPIO darf nur einmal vorkommen'); return; }
  const r = await send({ a: 'config', invert: $('cfgInvert').checked, blink_ms: +$('cfgBlink').value,
    lights: cfgDraft.map((l) => ({ id: l.id, name: String(l.name).trim(), pin: +l.pin, kind: l.kind })) });
  if (r.ok) { toast('Gespeichert'); closeSheet(); }
};
$('btnSettings').onclick = openSheet;
$('btnClose').onclick = closeSheet;
$('btnDisconnect').onclick = () => {
  manualDisconnect = true; clearTimeout(retryTimer); Native.bleDisconnect();
  connected = false; lights = []; setStatus('off', 'nicht verbunden'); show('Connect'); closeSheet();
};
$('btnOther').onclick = () => {
  manualDisconnect = true; clearTimeout(retryTimer); Native.bleDisconnect();
  connected = false; lights = []; setStatus('off', 'nicht verbunden'); show('Connect'); closeSheet();
  $('devList').innerHTML = ''; Native.bleScan();
};

$('optScreen').checked = store.get('screen', '1') === '1';
$('optVibrate').checked = store.get('vibrate', '1') === '1';
Native.keepScreenOn($('optScreen').checked);
$('optScreen').onchange = (e) => { store.set('screen', e.target.checked ? '1' : '0'); Native.keepScreenOn(e.target.checked); };
$('optVibrate').onchange = (e) => store.set('vibrate', e.target.checked ? '1' : '0');

// =================================================================== Start
show('Connect');
if (lastAddr) connectTo(lastAddr, lastName);

// =================================================================== Demo im Browser (ohne App)
function demoNative() {
  const st = {
    invert: false, blink_ms: 400,
    lights: [
      { id: 'extra', name: 'Zusatzlicht', pin: 25, kind: 'light', mode: 'on', bright: 80 },
    ],
  };
  const fire = (t, d) => setTimeout(() => window.onNative(t, JSON.stringify(d)), 150);
  const handle = (q) => {
    const L = (id) => st.lights.find((l) => l.id === id);
    if (q.a === 'set') Object.assign(L(q.id), q.mode ? { mode: q.mode } : {}, q.bright ? { bright: q.bright } : {});
    if (q.a === 'toggle') L(q.id).mode = L(q.id).mode === 'off' ? 'on' : 'off';
    if (q.a === 'all') st.lights.forEach((l) => { l.mode = q.mode; });
    if (q.a === 'config') { st.invert = q.invert; st.blink_ms = q.blink_ms; st.lights = q.lights.map((l) => ({ mode: 'off', bright: 100, ...l })); }
    return { ok: true, ...JSON.parse(JSON.stringify(st)) };
  };
  return {
    send(id, json) {
      const c = JSON.parse(json);
      let r = { ok: true };
      if (c.cmd === 'run') {
        const q = JSON.parse(JSON.parse(c.code.match(/licht\.cmd\((.*)\)$/s)[1]));
        r = { ok: true, out: 'LICHT:' + JSON.stringify(handle(q)) + '\n' };
      }
      setTimeout(() => window.onReply(id, JSON.stringify(r)), 60);
    },
    bleScan() { fire('bleState', { state: 'scanning' }); fire('bleDevice', { name: 'ESP32-Claude', address: 'AA:BB:CC:DD:EE:FF', rssi: -58 }); setTimeout(() => window.onNative('bleState', '{"state":"idle"}'), 600); },
    bleConnect() { fire('bleState', { state: 'connected', detail: 'Demo' }); },
    bleDisconnect() {},
    asset() { return ''; },
    keepScreenOn() {},
    vibrate() {},
  };
}

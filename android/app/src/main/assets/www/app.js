'use strict';
// ---------------------------------------------------------------- Bruecke zur nativen App
const $ = (id) => document.getElementById(id);
const pending = {};
let seq = 0;
if (!window.Native) {
  // Vorschau im Browser ohne App: Dummy-Bruecke
  window.Native = new Proxy({}, { get: () => (...a) => {
    const id = a[0]; if (typeof id === 'string' && id.startsWith('r')) setTimeout(() => onReply(id, JSON.stringify({ ok: false, err: 'Nur in der Android-App verfügbar' })), 100);
  } });
}
function esp(cmd, timeout = 15000) {
  return new Promise((res) => { const id = 'r' + (++seq); pending[id] = res; Native.send(id, JSON.stringify(cmd), timeout); });
}
window.onReply = (id, json) => { const r = JSON.parse(json); if (pending[id]) { pending[id](r); delete pending[id]; } };
window.onNative = (type, json) => { const d = JSON.parse(json); (handlers[type] || (() => {}))(d); };
window.onBack = () => { if (currentTab !== 'connect') { showTab('connect'); return true; } return false; };

const store = {
  get(k, d = '') { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} },
};
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('show'), 2600); }
function esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

// ---------------------------------------------------------------- Tabs
let currentTab = 'connect';
function showTab(t) {
  currentTab = t;
  document.querySelectorAll('.tab').forEach((s) => s.classList.toggle('active', s.id === 'tab-' + t));
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === t));
  if (t === 'files' && connected) listFiles();
}
document.querySelectorAll('nav button').forEach((b) => b.onclick = () => showTab(b.dataset.tab));

// ---------------------------------------------------------------- Verbindung
let transport = store.get('transport', 'wifi');
let connected = false;
function setTransport(t) {
  transport = t; store.set('transport', t);
  document.querySelectorAll('#transportSeg button').forEach((b) => b.classList.toggle('on', b.dataset.t === t));
  document.querySelectorAll('[data-pane]').forEach((p) => p.classList.toggle('hidden', p.dataset.pane !== t));
  Native.setTarget(t, $('wifiHost').value.trim());
  setStatus(false);
}
document.querySelectorAll('#transportSeg button').forEach((b) => b.onclick = () => setTransport(b.dataset.t));

function setStatus(on, text) {
  connected = on;
  const s = $('status');
  s.className = 'pill ' + (on ? 'on' : 'off');
  s.textContent = on ? (text || 'verbunden') : (text || 'nicht verbunden');
}

function renderInfo(r) {
  if (!r.ok) { $('devInfo').innerHTML = '<span style="color:var(--err)">' + esc(r.err || 'Fehler') + '</span>'; return; }
  const rows = [
    ['Name', r.name], ['Chip', r.machine], ['MicroPython', (r.micropython || '').split(';').pop().trim()],
    ['IP', (r.ip || '–') + (r.mode ? ' (' + r.mode + ')' : '')], ['app.py', r.has_app ? r.app : 'keine'],
    ['Freier RAM', Math.round(r.mem_free / 1024) + ' kB'], ['Freier Flash', Math.round(r.fs_free / 1024) + ' kB'],
  ];
  $('devInfo').classList.remove('muted');
  $('devInfo').innerHTML = rows.map(([k, v]) => `<div><span>${esc(k)}</span><span>${esc(v ?? '')}</span></div>`).join('')
    + (r.app_error ? `<pre class="out">${esc(r.app_error)}</pre>` : '');
}

async function refreshInfo() {
  const r = await esp({ cmd: 'info' }, 8000);
  renderInfo(r);
  if (r.ok) {
    setStatus(true, (transport === 'wifi' ? 'WLAN' : transport === 'ble' ? 'BT' : 'USB') + ' · ' + r.name);
    if (r.ip && transport !== 'wifi' && r.mode === 'wlan') { $('wifiHost').value = r.ip; store.set('wifiHost', r.ip); }
  } else if (transport === 'wifi') setStatus(false);
  return r;
}

async function connectWifi() {
  const host = $('wifiHost').value.trim();
  if (!host) { toast('Bitte IP eintragen oder „Suchen“'); return; }
  store.set('wifiHost', host);
  Native.setTarget('wifi', host);
  $('devInfo').textContent = 'Verbinde …';
  const r = await refreshInfo();
  toast(r.ok ? 'Verbunden mit ' + r.name : 'Keine Verbindung: ' + r.err);
}
$('wifiHost').onchange = () => Native.setTarget(transport, $('wifiHost').value.trim());

function discover() {
  $('wifiList').innerHTML = '<div class="muted">Suche im WLAN …</div>';
  const id = 'r' + (++seq);
  pending[id] = (r) => {
    const list = r.devices || [];
    if (!r.ok || !list.length) { $('wifiList').innerHTML = '<div class="muted">Nichts gefunden. Gleiches WLAN? IP steht auch im Log des ESP32 (USB).</div>'; return; }
    $('wifiList').innerHTML = '';
    list.forEach((d) => {
      const el = document.createElement('div');
      el.innerHTML = `<div><b>${esc(d.name)}</b><div class="meta">${esc(d.ip)} · v${esc(d.version || '?')}</div></div><div class="acts"><button class="primary">Wählen</button></div>`;
      el.querySelector('button').onclick = () => { $('wifiHost').value = d.ip; connectWifi(); };
      $('wifiList').appendChild(el);
    });
  };
  Native.discover(id);
}

// BLE
const bleSeen = {};
const handlers = {
  bleDevice(d) {
    if (bleSeen[d.address]) return;
    bleSeen[d.address] = true;
    const el = document.createElement('div');
    const likely = /esp|claude/i.test(d.name);
    el.innerHTML = `<div><b>${esc(d.name)}</b>${likely ? ' ✓' : ''}<div class="meta">${esc(d.address)} · ${d.rssi} dBm</div></div><div class="acts"><button class="${likely ? 'primary' : ''}">Verbinden</button></div>`;
    el.querySelector('button').onclick = () => Native.bleConnect(d.address);
    if (likely) $('bleList').prepend(el); else $('bleList').appendChild(el);
  },
  bleState(d) {
    if (d.state === 'scanning') { $('bleList').innerHTML = ''; Object.keys(bleSeen).forEach((k) => delete bleSeen[k]); toast('Suche Bluetooth-Geräte …'); }
    else if (d.state === 'connecting') toast('Verbinde mit ' + d.detail + ' …');
    else if (d.state === 'connected') { toast('Bluetooth verbunden'); if (transport === 'ble') refreshInfo(); }
    else if (d.state === 'disconnected') { if (transport === 'ble') setStatus(false); toast('Bluetooth getrennt'); }
    else if (d.state === 'error') toast(d.detail);
  },
  usbState(d) {
    if (d.state === 'connected') { toast('USB verbunden: ' + d.detail); if (transport === 'usb') refreshInfo(); }
    else if (d.state === 'disconnected') { if (transport === 'usb') setStatus(false); }
    else toast(d.detail);
  },
  usbAttached() { setTransport('usb'); showTab('connect'); toast('USB-Gerät erkannt – „USB verbinden“ tippen'); },
  progress(d) { $('instProgress').textContent = d.text; },
  claude: onClaude,
};

function usbInstall() {
  $('instProgress').textContent = 'Starte Installation …';
  const id = 'r' + (++seq);
  pending[id] = (r) => {
    $('instProgress').textContent = r.ok ? '✓ Fertig! Der ESP32 startet neu. Danach kannst du per WLAN oder Bluetooth verbinden.' : '✗ ' + r.err;
    if (r.ok) setTimeout(refreshInfo, 4000);
  };
  Native.usbInstall(id, $('instSsid').value, $('instPw').value, $('instName').value);
}

async function cmdNote(cmd) {
  const r = await esp(cmd);
  toast(r.ok ? (r.note || 'OK') : 'Fehler: ' + r.err);
  if (r.ok && /reset|deploy|stop|wifi/.test(cmd.cmd) && transport === 'wifi') setTimeout(refreshInfo, 6000);
  return r;
}
function setWifi() {
  if (!$('newSsid').value) { toast('WLAN-Name fehlt'); return; }
  cmdNote({ cmd: 'wifi', ssid: $('newSsid').value, password: $('newPw').value });
}

// ---------------------------------------------------------------- Code-Editor
const EXAMPLES = {
  'LED blinken': `from machine import Pin
import time

led = Pin(2, Pin.OUT)   # Onboard-LED (ESP32 DevKit: GPIO 2)
while True:
    led.value(not led.value())
    print("LED", led.value())
    time.sleep(0.5)
`,
  'Taster lesen': `from machine import Pin
import time

taster = Pin(0, Pin.IN, Pin.PULL_UP)   # BOOT-Taster
while True:
    if taster.value() == 0:
        print("Taster gedrückt!")
    time.sleep(0.1)
`,
  'Chip-Temperatur': `import esp32, time
for i in range(5):
    try:
        print("Chip-Temperatur:", esp32.mcu_temperature(), "°C")
    except AttributeError:
        print("Rohwert:", (esp32.raw_temperature() - 32) / 1.8, "°C")
    time.sleep(1)
`,
  'WLAN-Netze scannen': `import network
sta = network.WLAN(network.STA_IF)
sta.active(True)
for ssid, bssid, ch, rssi, auth, hidden in sta.scan():
    print(ssid.decode(), "Kanal", ch, "Signal", rssi, "dBm")
`,
  'NeoPixel Regenbogen': `from machine import Pin
import neopixel, time

np = neopixel.NeoPixel(Pin(48), 1)   # ESP32-S3 DevKit: GPIO 48, C3: GPIO 8
def rad(p):
    p %= 255
    if p < 85: return (255 - p * 3, p * 3, 0)
    if p < 170: p -= 85; return (0, 255 - p * 3, p * 3)
    p -= 170; return (p * 3, 0, 255 - p * 3)
i = 0
while True:
    np[0] = tuple(c // 8 for c in rad(i)); np.write()
    i += 3; time.sleep_ms(30)
`,
};
Object.keys(EXAMPLES).forEach((k) => { const o = document.createElement('option'); o.value = k; o.textContent = k; $('examples').appendChild(o); });
function loadExample(k) { if (EXAMPLES[k]) { $('editor').value = EXAMPLES[k]; saveDraft(); } }
function saveDraft() { store.set('editor', $('editor').value); store.set('codePath', $('codePath').value); }
$('editor').value = store.get('editor', EXAMPLES['LED blinken']);
$('codePath').value = store.get('codePath', 'app.py');
$('editor').oninput = saveDraft; $('codePath').oninput = saveDraft;
$('editor').addEventListener('keydown', (e) => {
  if (e.key === 'Tab') { e.preventDefault(); document.execCommand('insertText', false, '    '); }
});

function showOut(r) {
  let t = '';
  if (r.out) t += r.out;
  if (r.err) t += (t ? '\n' : '') + r.err;
  if (r.note) t += (t ? '\n' : '') + '— ' + r.note;
  if (!t) t = r.ok ? '(keine Ausgabe – fertig)' : 'Fehler';
  $('runOut').textContent = t;
}
async function runEditor() {
  $('runOut').textContent = 'Läuft …';
  showOut(await esp({ cmd: 'run', code: $('editor').value, timeout: 8 }, 20000));
}
async function deployEditor() {
  if (!confirm('Code als app.py installieren? Der ESP32 startet neu und führt ihn danach bei jedem Start aus.')) return;
  const r = await cmdNote({ cmd: 'deploy', code: $('editor').value });
  if (r.ok) { $('runOut').textContent = 'Installiert – Ausgaben siehst du im Tab „Log“.'; }
}
async function saveEditor() {
  const p = $('codePath').value.trim();
  if (/^\/?(main|agent)\.py$|^\/?config\.json$/.test(p) && !confirm(p + ' ist eine Systemdatei des Agents. Wirklich überschreiben?')) return;
  const r = await esp({ cmd: 'write', path: p, data: $('editor').value });
  toast(r.ok ? 'Gespeichert: ' + p : 'Fehler: ' + r.err);
}
function askClaudeAboutCode() {
  showTab('claude');
  $('prompt').value = 'Schau dir diesen Code an und verbessere/erkläre ihn:\n```python\n' + $('editor').value + '\n```';
  $('prompt').focus();
}

// ---------------------------------------------------------------- Dateien
async function listFiles() {
  $('fileList').innerHTML = '<div class="muted">Lade …</div>';
  const r = await esp({ cmd: 'ls', path: '/' });
  if (!r.ok) { $('fileList').innerHTML = `<div style="color:var(--err)">${esc(r.err)}</div>`; return; }
  $('fileList').innerHTML = '';
  r.files.sort((a, b) => a.name.localeCompare(b.name)).forEach((f) => {
    const el = document.createElement('div');
    el.innerHTML = `<div><b>${esc(f.name)}</b>${f.dir ? '/' : ''}<div class="meta">${f.dir ? 'Ordner' : f.size + ' Bytes'}</div></div>`
      + (f.dir ? '' : '<div class="acts"><button>Öffnen</button><button>Löschen</button></div>');
    if (!f.dir) {
      const [o, d] = el.querySelectorAll('button');
      o.onclick = async () => {
        const x = await esp({ cmd: 'read', path: f.name });
        if (!x.ok) { toast(x.err); return; }
        $('editor').value = x.data; $('codePath').value = f.name; saveDraft(); showTab('code');
      };
      d.onclick = async () => {
        if (!confirm(f.name + ' löschen?')) return;
        const x = await esp({ cmd: 'rm', path: f.name }); toast(x.ok ? 'Gelöscht' : x.err); listFiles();
      };
    }
    $('fileList').appendChild(el);
  });
}

// ---------------------------------------------------------------- Log
let logSince = 0, logBusy = false;
setInterval(async () => {
  if (!connected || !$('live').checked || logBusy || currentTab !== 'log' || busyClaude) return;
  logBusy = true;
  const r = await esp({ cmd: 'logs', since: logSince }, 6000);
  logBusy = false;
  if (!r.ok) return;
  if (r.last < logSince) logSince = 0; // Neustart erkannt
  if (r.lines.length) {
    const out = $('logOut');
    out.textContent += r.lines.map((l) => l[1]).join('\n') + '\n';
    if (out.textContent.length > 60000) out.textContent = out.textContent.slice(-40000);
    out.scrollTop = out.scrollHeight;
    $('tab-log').scrollTop = $('tab-log').scrollHeight;
  }
  logSince = r.last;
}, 1500);

// ---------------------------------------------------------------- Claude
['apiKey', 'model', 'effort', 'boardNote'].forEach((k) => {
  const el = $(k); const v = store.get(k, ''); if (v) el.value = v;
  el.onchange = el.oninput = () => store.set(k, el.value);
});
if (!store.get('apiKey')) $('claudeSettings').open = true;

let busyClaude = false, typingEl = null;
function chatAdd(cls, html) {
  const el = document.createElement('div'); el.className = cls; el.innerHTML = html;
  $('chat').appendChild(el); $('chat').scrollTop = $('chat').scrollHeight; return el;
}
function md(text) {
  // Minimales Markdown: Codebloecke, inline-Code, fett, Absaetze
  const parts = text.split(/```(\w*)\n?([\s\S]*?)```/g);
  let html = '';
  for (let i = 0; i < parts.length; i++) {
    if (i % 3 === 0) {
      html += esc(parts[i]).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
        .split(/\n{2,}/).map((p) => p.trim() ? '<p>' + p.replace(/\n/g, '<br>') + '</p>' : '').join('');
    } else if (i % 3 === 2) {
      html += `<pre>${esc(parts[i])}</pre><button onclick="toEditor(this)">→ In Editor</button>`;
    }
  }
  return html;
}
function toEditor(btn) {
  $('editor').value = btn.previousElementSibling.textContent; $('codePath').value = 'app.py'; saveDraft();
  showTab('code'); toast('Code im Editor');
}
const TOOL_NAMES = {
  esp_info: 'Geräteinfo', run_python: 'Code ausführen', deploy_app: 'App installieren', get_logs: 'Logs lesen',
  list_files: 'Dateien auflisten', read_file: 'Datei lesen', write_file: 'Datei schreiben', stop_app: 'App stoppen', reset_device: 'Neustart',
};
let lastToolEl = null;
function onClaude(ev) {
  if (typingEl) { typingEl.remove(); typingEl = null; }
  switch (ev.type) {
    case 'thinking': typingEl = chatAdd('typing', 'Claude denkt nach …'); break;
    case 'text': if (ev.text.trim()) chatAdd('msg bot', md(ev.text)); break;
    case 'tool': {
      const inp = ev.input || {};
      const detail = inp.code || inp.content || inp.path || '';
      lastToolEl = chatAdd('tool', `<details><summary>⚙ ${esc(TOOL_NAMES[ev.name] || ev.name)}${inp.path ? ' · ' + esc(inp.path) : ''} …</summary>${detail ? '<pre>' + esc(detail) + '</pre>' : ''}</details>`);
      typingEl = chatAdd('typing', 'ESP32 arbeitet …');
      break;
    }
    case 'tool_result': {
      const r = ev.result || { ok: false, err: ev.error };
      const ok = r.ok !== false && !ev.error;
      const txt = ev.error || r.err || r.out || r.note || (r.lines ? r.lines.map((l) => l[1]).join('\n') : '') || JSON.stringify(r);
      if (lastToolEl) {
        lastToolEl.classList.add(ok ? 'ok' : 'bad');
        const s = lastToolEl.querySelector('summary'); s.textContent = s.textContent.replace(' …', ok ? ' ✓' : ' ✗');
        lastToolEl.querySelector('details').insertAdjacentHTML('beforeend', '<pre>' + esc(String(txt).slice(0, 3000)) + '</pre>');
      }
      if (ev.name === 'esp_info' && ok) { renderInfo(r); setStatus(true, transport.toUpperCase() + ' · ' + r.name); }
      break;
    }
    case 'error': chatAdd('msg err', esc(ev.text)); break;
    case 'done':
      if (ev.text) chatAdd('msg sys', esc(ev.text));
      busyClaude = false; $('sendBtn').classList.remove('hidden'); $('stopBtn').classList.add('hidden');
      break;
  }
}
function sendPrompt() {
  const text = $('prompt').value.trim();
  if (!text || busyClaude) return;
  const key = $('apiKey').value.trim();
  if (!key) { $('claudeSettings').open = true; toast('Bitte zuerst den API-Key eintragen'); return; }
  if (!connected) chatAdd('msg sys', 'Hinweis: Noch kein ESP32 verbunden – Claude kann dann nur Code schreiben, aber nicht testen.');
  chatAdd('msg user', esc(text).replace(/\n/g, '<br>'));
  $('prompt').value = '';
  busyClaude = true; $('sendBtn').classList.add('hidden'); $('stopBtn').classList.remove('hidden');
  $('claudeSettings').open = false;
  Native.claudeChat(key, $('model').value, $('effort').value, $('boardNote').value, text);
}
function newChat() {
  if (busyClaude) return;
  Native.claudeReset();
  $('chat').innerHTML = '';
  chatAdd('msg sys', 'Neues Gespräch. Was soll der ESP32 machen?');
}
$('prompt').addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) sendPrompt(); });

// ---------------------------------------------------------------- Start
$('wifiHost').value = store.get('wifiHost', '');
setTransport(transport);
if (transport === 'wifi' && $('wifiHost').value) refreshInfo();

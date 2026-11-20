'use strict';
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

const TOKEN = new URLSearchParams(location.search).get('t') || sessionStorage.getItem('pc_t') || '';
sessionStorage.setItem('pc_t', TOKEN);

async function api(path, body, raw) {
  const opt = { headers: { 'X-Token': TOKEN } };
  if (body !== undefined) {
    opt.method = 'POST';
    if (raw) opt.body = body;
    else { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  }
  const r = await fetch('/api/' + path, opt);
  let j = {};
  try { j = await r.json(); } catch (e) { /* empty */ }
  if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
  return j;
}

function toast(msg, kind = '') {
  const t = document.createElement('div');
  t.className = 'toast ' + kind;
  t.textContent = msg;
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), 3800);
}

async function act(path, body, okMsg) {
  try {
    const r = await api(path, body === undefined ? {} : body);
    if (okMsg) toast(okMsg, 'ok');
    return r;
  } catch (e) { toast(e.message, 'err'); return null; }
}

/* ------------------------------------------------------------------ tabs */
const TITLES = { dash: 'Dashboard', console: 'Console', keys: 'Key mapping', settings: 'Settings', help: 'Guide' };
$$('.nav').forEach(b => b.addEventListener('click', () => {
  $$('.nav').forEach(x => x.classList.toggle('active', x === b));
  $$('.tab').forEach(t => t.classList.toggle('active', t.id === 'tab-' + b.dataset.tab));
  $('#pageTitle').textContent = TITLES[b.dataset.tab];
  if (b.dataset.tab === 'console') scrollLog(true);
}));

/* ------------------------------------------------------------------ state */
const MODE_COLORS = { pad: '#00e5ff', minecraft: '#55e06a', roblox: '#ff5252', touchpad: '#b388ff', racing: '#ff9100' };
const MODE_NAMES = { pad: 'GAMEPAD', minecraft: 'MINECRAFT', roblox: 'ROBLOX', touchpad: 'TOUCHPAD', racing: 'RACING' };
let S = null, CFG = null, devSig = '', online = true, accent = '#00e5ff';

function pill(id, cls, text) {
  const el = $(id);
  el.className = 'pill ' + cls;
  el.lastChild.textContent = text;
}

async function pollState() {
  try { S = await api('state'); render(S); online = true; }
  catch (e) { online = false; pill('#pSrv', 'bad', 'UI offline'); }
}

function render(s) {
  $('#ver').textContent = 'v' + s.version;
  pill('#pAdb', s.adb.ok ? 'ok' : 'bad', s.adb.ok ? 'ADB ready' : 'ADB missing');
  const d = s.devices.find(x => x.serial === s.selected);
  pill('#pPhone', d ? (d.state === 'device' ? 'ok' : 'warn') : 'bad',
       d ? ((d.model || d.serial) + (d.state === 'device' ? '' : ' · ' + d.state)) : 'No phone');
  pill('#pSrv', s.server.on ? 'ok' : 'bad', s.server.on ? 'Server :' + s.server.port : 'Server off');
  pill('#pApp', s.server.app ? 'ok' : (s.server.on ? 'warn' : ''), s.server.app ? 'App connected' : 'App waiting');
  const rtt = s.live.rtt;
  pill('#pPing', s.server.app && rtt >= 0 ? (rtt > 120 ? 'warn' : 'ok') : '', s.server.app && rtt >= 0 ? rtt + ' ms' : '— ms');

  // device list (rebuild only when it changes so an open dropdown is not disturbed)
  const sig = JSON.stringify(s.devices.map(x => [x.serial, x.state, x.model, x.battery])) + s.selected;
  if (sig !== devSig) {
    devSig = sig;
    const sel = $('#devSel');
    sel.innerHTML = '';
    if (!s.devices.length) sel.innerHTML = '<option value="">No phone found</option>';
    s.devices.forEach(x => {
      const o = document.createElement('option');
      o.value = x.serial;
      o.textContent = ((x.brand + ' ' + x.model).trim() || 'Android device') + '  [' + x.state + ']';
      sel.appendChild(o);
    });
    if (s.selected) sel.value = s.selected;
  }
  $('#devName').textContent = d ? ((d.brand + ' ' + d.model).trim() || '—') : '—';
  $('#devAndroid').textContent = d && d.android ? 'Android ' + d.android : '—';
  $('#devSerial').textContent = d ? d.serial : '—';
  $('#devState').textContent = d ? d.state : '—';
  const bat = d && d.battery ? parseInt(d.battery, 10) : null;
  $('#battFill').style.width = (bat === null || isNaN(bat) ? 0 : bat) + '%';
  $('#battFill').style.background = bat !== null && bat < 20 ? 'var(--bad)' : 'var(--good)';
  $('#battTxt').textContent = bat === null || isNaN(bat) ? '—' : bat + '%';

  const btn = $('#btnConnect');
  btn.textContent = s.server.on ? 'Disconnect' : 'Connect';
  btn.classList.toggle('primary', !s.server.on);
  btn.classList.toggle('danger', s.server.on);
  btn.disabled = !s.server.on && !(d && d.state === 'device');
  if (document.activeElement !== $('#port')) $('#port').value = s.server.port;
  if (document.activeElement !== $('#adbPath') && !$('#adbPath').dataset.dirty) $('#adbPath').value = s.adb.path || '';
  if (document.activeElement !== $('#autoDetect')) $('#autoDetect').checked = s.auto;
  $('#connHint').textContent = !s.adb.ok ? 'adb not found - paste the path to adb.exe above, or place it in the "adb" folder.'
    : s.server.on ? (s.server.app ? 'Everything is connected. Have fun! 🎮' : 'Server is on. Open the app on your phone.')
    : 'Plug the phone with a USB cable, enable USB debugging, then press Connect.';

  const L = s.live;
  const mode = L.mode || '';
  accent = MODE_COLORS[mode] || '#00e5ff';
  document.documentElement.style.setProperty('--accent', accent);
  $('#liveMode').textContent = MODE_NAMES[mode] || 'IDLE';
  $('#modeBadge').textContent = MODE_NAMES[mode] || 'IDLE';
  $('#liveMouse').textContent = mode === 'pad' ? 'mouse: ' + (L.ms ? 'ON' : 'OFF')
    : mode === 'touchpad' ? 'touchpad' : mode === 'racing' ? 'tilt = steering' : mode ? 'R-stick = look' : 'mouse: —';
  $('#stRate').textContent = L.rate;
  $('#stRtt').textContent = rtt >= 0 && s.server.app ? rtt : '—';
  $('#rumL').style.width = Math.round(L.rumble[0] * 100) + '%';
  $('#rumR').style.width = Math.round(L.rumble[1] * 100) + '%';

  const eb = $('#errBadge');
  eb.textContent = s.errors;
  eb.classList.toggle('hidden', !s.errors);
}

/* ------------------------------------------------------------------ dashboard actions */
$('#btnRefresh').onclick = () => act('check_adb', {}, 'Scanning…');
$('#devSel').onchange = e => act('select', { serial: e.target.value });
$('#adbPath').oninput = e => { e.target.dataset.dirty = '1'; };
$('#btnAdbSet').onclick = async () => { await act('check_adb', { custom: $('#adbPath').value }); delete $('#adbPath').dataset.dirty; };
$('#autoDetect').onchange = e => act('auto', { on: e.target.checked });
$('#btnConnect').onclick = async () => {
  if (S && S.server.on) await act('disconnect', {}, 'Disconnected');
  else await act('connect', { port: parseInt($('#port').value, 10) || 5555 }, 'Server started');
  pollState();
};
$('#btnOpen').onclick = () => act('open_app', {}, 'App opened on phone');
$('#btnInstall').onclick = () => $('#apkFile').click();
$('#apkFile').onchange = async e => {
  const f = e.target.files[0];
  if (!f) return;
  toast('Installing ' + f.name + '…');
  try {
    const r = await api('install?name=' + encodeURIComponent(f.name), await f.arrayBuffer(), true);
    toast('Install: ' + (r.msg || 'OK'), 'ok');
  } catch (err) { toast(err.message, 'err'); }
  e.target.value = '';
};
$('#btnRumble').onclick = () => act('rumble_test', {}, 'Rumble sent');
$$('[data-act]').forEach(b => b.onclick = async () => { await act(b.dataset.act, {}, b.textContent + ' ✓'); pollState(); });
$('#btnQuit').onclick = async () => { await act('quit', {}); document.body.innerHTML = '<p style="padding:40px;color:#8b94b4">You can close this window.</p>'; try { window.close(); } catch (e) { /* empty */ } };

/* ------------------------------------------------------------------ console */
let lastLog = 0, allLogs = [];
function lineClass(m) { return { '[!]': 'err', '[+]': 'ok', '[-]': 'warn', '[~]': 'info' }[m.slice(0, 3)] || ''; }
function addLine(x) {
  const f = $('#logFilter').value, c = lineClass(x.m);
  if (f === 'err' && c !== 'err') return;
  if (f === 'ok' && c !== 'ok') return;
  const d = document.createElement('div');
  d.innerHTML = '<span class="t"></span><span class="m ' + c + '"></span>';
  d.firstChild.textContent = x.t;
  d.lastChild.textContent = x.m;
  $('#logBox').appendChild(d);
}
function scrollLog(force) { const b = $('#logBox'); if (force || $('#autoScroll').checked) b.scrollTop = b.scrollHeight; }
async function pollLogs() {
  try {
    const r = await api('logs?since=' + lastLog);
    if (r.logs.length) {
      r.logs.forEach(x => { allLogs.push(x); addLine(x); lastLog = x.id; });
      if (allLogs.length > 1500) allLogs = allLogs.slice(-1500);
      scrollLog();
    }
  } catch (e) { /* offline */ }
}
function rebuildLog() { $('#logBox').innerHTML = ''; allLogs.forEach(addLine); scrollLog(true); }
$('#logFilter').onchange = rebuildLog;
$('#btnClear').onclick = async () => { await act('clear_logs', {}); allLogs = []; $('#logBox').innerHTML = ''; };
$('#btnCopy').onclick = async () => {
  const text = allLogs.map(x => x.t + ' ' + x.m).join('\n');
  try { await navigator.clipboard.writeText(text); toast('Console copied', 'ok'); }
  catch (e) {
    const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand('copy'); ta.remove(); toast('Console copied', 'ok');
  }
};
$('#btnSaveLog').onclick = async () => { const r = await act('save_log', {}); if (r) toast('Saved: ' + r.path, 'ok'); };

/* ------------------------------------------------------------------ config: key map + sliders */
const PAD_KEYS = ['A', 'B', 'X', 'Y', 'LB', 'RB', 'LT', 'RT', 'BACK', 'START', 'UP', 'DOWN', 'LEFT', 'RIGHT', 'LS', 'RS'];
const KEY_LABEL = { LS: 'L3 (stick click)', RS: 'R3 (stick click)', LT: 'LT (trigger)', RT: 'RT (trigger)' };
let saveTimer = null, pending = {};
function patchCfg(p) {
  const merge = (a, b) => { for (const k in b) { if (b[k] && typeof b[k] === 'object') { a[k] = a[k] || {}; merge(a[k], b[k]); } else a[k] = b[k]; } };
  merge(pending, p); merge(CFG, p);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => { const body = pending; pending = {}; try { await api('config', body); } catch (e) { toast(e.message, 'err'); } }, 300);
}

function buildKeyGrid() {
  const prof = $('#profSel').value, keys = (CFG.profiles[prof] || {}).keys || {};
  const g = $('#keyGrid');
  g.innerHTML = '';
  PAD_KEYS.forEach(k => {
    const c = document.createElement('div');
    c.className = 'kcell';
    c.innerHTML = '<label></label><input class="input mono" list="keyList" spellcheck="false" placeholder="—">';
    c.firstChild.textContent = KEY_LABEL[k] || k;
    const inp = c.lastChild;
    inp.value = keys[k] || '';
    inp.onchange = () => patchCfg({ profiles: { [prof]: { keys: { [k]: inp.value.trim().toLowerCase() } } } });
    g.appendChild(c);
  });
}

function bindRange(id, get, set, fmt = v => v) {
  const el = $('#' + id), out = $('#' + id + 'V');
  el.value = get(); out.textContent = fmt(+el.value);
  el.oninput = () => { out.textContent = fmt(+el.value); set(+el.value); };
}

async function loadCfg() {
  const r = await api('cfg');
  CFG = r.cfg;
  $('#keyList').innerHTML = r.keys.map(k => '<option value="' + k + '">').join('');
  buildKeyGrid();
  $('#profSel').onchange = buildKeyGrid;
  bindRange('sensPad', () => CFG.mouse_overlay.mouse_sens, v => patchCfg({ mouse_overlay: { mouse_sens: v } }));
  bindRange('sensMc', () => CFG.profiles.minecraft.mouse_sens, v => patchCfg({ profiles: { minecraft: { mouse_sens: v } } }));
  bindRange('sensRb', () => CFG.profiles.roblox.mouse_sens, v => patchCfg({ profiles: { roblox: { mouse_sens: v } } }));
  bindRange('kthr', () => CFG.stick_key_threshold, v => patchCfg({ stick_key_threshold: v }), v => v.toFixed(2));
  bindRange('dzone', () => CFG.mouse_overlay.deadzone, v => patchCfg({ mouse_overlay: { deadzone: v }, profiles: { minecraft: { deadzone: v }, roblox: { deadzone: v } } }), v => v.toFixed(2));
}

/* ------------------------------------------------------------------ live gamepad canvas */
const cv = $('#pad'), g = cv.getContext('2d');
function rr(x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
function drawPad() {
  const L = (S && S.live) || { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, b: [], mode: '' };
  const on = n => L.b.includes(n);
  const W = cv.width, H = cv.height;
  g.clearRect(0, 0, W, H);
  const dim = 'rgba(255,255,255,.16)', line = 'rgba(255,255,255,.28)';
  // body
  g.beginPath();
  g.moveTo(150, 70); g.bezierCurveTo(250, 40, 510, 40, 610, 70);
  g.bezierCurveTo(700, 100, 740, 270, 690, 330); g.bezierCurveTo(655, 370, 610, 350, 590, 310);
  g.bezierCurveTo(570, 270, 540, 260, 500, 260); g.lineTo(260, 260);
  g.bezierCurveTo(220, 260, 190, 270, 170, 310); g.bezierCurveTo(150, 350, 105, 370, 70, 330);
  g.bezierCurveTo(20, 270, 60, 100, 150, 70); g.closePath();
  g.fillStyle = 'rgba(255,255,255,.04)'; g.fill(); g.strokeStyle = line; g.lineWidth = 2; g.stroke();
  // triggers / bumpers
  const bar = (x, y, w, h, v, label, pressed) => {
    rr(x, y, w, h, 8); g.fillStyle = 'rgba(255,255,255,.06)'; g.fill();
    if (v > 0) { rr(x, y, Math.max(8, w * v), h, 8); g.fillStyle = accent; g.fill(); }
    rr(x, y, w, h, 8); g.strokeStyle = pressed ? accent : line; g.stroke();
    g.fillStyle = '#e9edfb'; g.font = '700 13px Segoe UI'; g.textAlign = 'center'; g.fillText(label, x + w / 2, y + h / 2 + 5);
  };
  bar(120, 14, 130, 22, L.lt, 'LT', L.lt > 0.05); bar(120, 42, 130, 18, on('LB') ? 1 : 0, 'LB', on('LB'));
  bar(510, 14, 130, 22, L.rt, 'RT', L.rt > 0.05); bar(510, 42, 130, 18, on('RB') ? 1 : 0, 'RB', on('RB'));
  // sticks
  const stick = (cx, cy, x, y, click) => {
    g.beginPath(); g.arc(cx, cy, 50, 0, 7); g.fillStyle = 'rgba(0,0,0,.35)'; g.fill(); g.strokeStyle = click ? accent : line; g.stroke();
    g.beginPath(); g.arc(cx + x * 26, cy - y * 26, 24, 0, 7); g.fillStyle = (x || y) ? accent : dim; g.fill();
  };
  stick(215, 180, L.lx, L.ly, on('LS')); stick(470, 245, L.rx, L.ry, on('RS'));
  // dpad
  const dp = (x, y, n) => { rr(x, y, 26, 26, 6); g.fillStyle = on(n) ? accent : dim; g.fill(); };
  dp(330, 150, 'UP'); dp(330, 202, 'DOWN'); dp(304, 176, 'LEFT'); dp(356, 176, 'RIGHT');
  // face buttons
  const face = (x, y, n, col) => { g.beginPath(); g.arc(x, y, 20, 0, 7); g.fillStyle = on(n) ? col : 'rgba(255,255,255,.07)'; g.fill(); g.strokeStyle = col; g.lineWidth = 2; g.stroke();
    g.fillStyle = on(n) ? '#000' : col; g.font = '700 16px Segoe UI'; g.textAlign = 'center'; g.fillText(n, x, y + 6); };
  face(590, 150, 'A', '#3ddc84'); face(630, 112, 'B', '#ff5252'); face(550, 112, 'X', '#448aff'); face(590, 74, 'Y', '#ffd740');
  // center
  const pill = (x, label, n) => { rr(x, 100, 54, 22, 11); g.fillStyle = on(n) ? accent : dim; g.fill(); g.fillStyle = on(n) ? '#000' : '#e9edfb'; g.font = '700 10px Segoe UI'; g.textAlign = 'center'; g.fillText(label, x + 27, 115); };
  pill(325, 'BACK', 'BACK'); pill(385, 'START', 'START');
  if (!L.mode) { g.fillStyle = 'rgba(255,255,255,.35)'; g.font = '600 15px Segoe UI'; g.textAlign = 'center'; g.fillText('Waiting for the phone app…', W / 2, 330); }
  requestAnimationFrame(drawPad);
}

/* ------------------------------------------------------------------ boot */
(async function boot() {
  try { await loadCfg(); } catch (e) { toast('Cannot load config: ' + e.message, 'err'); }
  pollState(); pollLogs();
  setInterval(pollState, 250);
  setInterval(pollLogs, 500);
  drawPad();
})();

import { initPlanner } from './planner.js';
import './planner.css';

const $ = (id) => document.getElementById(id);
const formatAP = (value) => (Number(value || 0) / 100).toFixed(2);
const THEME_KEY = 'aura_theme_v1';
function applyTheme(theme) {
  const dark = theme === 'dark';
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  const button = $('themeToggle');
  if (button) { button.textContent = dark ? 'Light mode' : 'Dark mode'; button.setAttribute('aria-label', `Switch to ${dark ? 'light' : 'dark'} mode`); button.setAttribute('aria-pressed', String(dark)); }
  const meta = document.querySelector('meta[name="theme-color"]'); if (meta) meta.content = dark ? '#101116' : '#f4f5f8';
}
applyTheme(localStorage.getItem(THEME_KEY) || 'dark');
fetch('/aura-update.json', { cache: 'no-store' }).then((response) => response.ok ? response.json() : null).then((release) => {
  if (release?.versionName && $('androidAppVersionLabel')) $('androidAppVersionLabel').textContent = `Native app · version ${release.versionName}`;
}).catch(() => {});
$('themeToggle').addEventListener('click', () => { const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'; localStorage.setItem(THEME_KEY, next); applyTheme(next); });
const checklistShortcut = new URLSearchParams(location.search).get('widget') === 'checklist';
if (checklistShortcut) {
  document.body.classList.add('checklist-shortcut-mode');
  document.title = 'AURA — Today’s checklist';
  const hint = $('checklistInstallHint');
  const apple = /iPhone|iPad|iPod/i.test(navigator.userAgent);
  hint.textContent = apple
    ? 'In Safari, tap Share → Add to Home Screen to save this checklist page. Your missions sync when you open AURA.'
    : 'On Android, press and hold the AURA icon and drag Checklist to your home screen. If AURA is not installed yet, use your browser menu to add AURA first.';
}
let account = null;
let pending = 0;
let verifiedHistory = [];
let periodMode = 'month';
const localPeriodNow = new Date();
$('periodDate').value = `${localPeriodNow.getFullYear()}-${String(localPeriodNow.getMonth() + 1).padStart(2, '0')}`;
$('periodYear').value = localPeriodNow.getFullYear();
function viewFromHash() {
  const hash = location.hash.slice(1);
  const aliases = { tasks: 'deadlines', study: 'subjects', life: 'home', library: 'home' };
  const view = aliases[hash] || hash;
  return ['focus', 'subjects', 'schedule', 'deadlines', 'devices', 'leaderboard', 'info'].includes(view) ? view : 'home';
}
function setView(view) {
  const topLevel = ['leaderboard', 'info'].includes(view);
  $('homeView').hidden = topLevel;
  $('leaderboardView').hidden = view !== 'leaderboard';
  $('infoView').hidden = view !== 'info';
  document.querySelectorAll('[data-page]').forEach(section => { section.hidden = !section.dataset.page.split(/\s+/).includes(view); });
  document.querySelectorAll('.view-tab').forEach((button) => {
    const active = button.dataset.view === view;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  if (view === 'leaderboard') refreshLeaderboard();
}
document.querySelectorAll('.view-tab').forEach((button) => {
  button.addEventListener('click', () => { location.hash = button.dataset.view; });
});
window.addEventListener('hashchange', () => setView(viewFromHash()));
setView(viewFromHash());
const api = async (url, body) => {
  const response = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers: { 'x-aura-time-zone': Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store'
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) { const error = new Error(data.error || 'That request could not be completed.'); error.status = response.status; throw error; }
  return data;
};
function fallbackDeviceHash(value) {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i++) { hash ^= value.charCodeAt(i); hash = Math.imul(hash, 16777619); }
  return Array.from({ length: 8 }, (_, i) => ((hash + i * 0x9e3779b9) >>> 0).toString(16).padStart(8, '0')).join('');
}
async function getDeviceId() {
  const dimensions = [screen.width, screen.height].sort((a, b) => a - b).join('x');
  const profile = [navigator.platform || 'unknown', dimensions, screen.colorDepth || 0, navigator.maxTouchPoints || 0, navigator.hardwareConcurrency || 0].join('|');
  try {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(profile));
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  } catch { return fallbackDeviceHash(profile); }
}
const deviceId = await getDeviceId();
let deviceBattery = null;
let deviceHeartbeatTimer;
let deviceRefreshTimer;
let deviceReportingPaused = false;
function deviceName() {
  const ua = navigator.userAgent;
  if (/Android/i.test(ua)) return 'Android device';
  if (/iPhone/i.test(ua)) return 'iPhone';
  if (/iPad/i.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) return 'iPad';
  if (/Windows/i.test(ua)) return 'Windows PC';
  if (/Macintosh|Mac OS/i.test(ua)) return 'Mac';
  if (/Linux/i.test(ua)) return 'Linux device';
  return 'Device';
}
async function reportDevice() {
  if (!account?.user?.id || !navigator.onLine || deviceReportingPaused) return;
  let batteryPercent = null, charging = null;
  try {
    if (!deviceBattery && typeof navigator.getBattery === 'function') deviceBattery = await navigator.getBattery();
    if (deviceBattery) { batteryPercent = Math.round(deviceBattery.level * 100); charging = Boolean(deviceBattery.charging); }
  } catch { deviceBattery = null; }
  try {
    await api('/api/planner', { action: 'device-heartbeat', deviceId, name: deviceName(), batteryPercent, charging, batterySupported: Boolean(deviceBattery) });
  } catch { /* Device sync resumes on the next heartbeat or app open. */ }
}
function formatDeviceSeen(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Last seen time unavailable';
  const minutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
  if (minutes < 1) return 'Last seen just now';
  if (minutes < 60) return `Last seen ${minutes} min${minutes === 1 ? '' : 's'} ago`;
  if (minutes < 1440) return `Last seen ${Math.floor(minutes / 60)} hr${Math.floor(minutes / 60) === 1 ? '' : 's'} ago`;
  return `Last seen ${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`;
}
async function refreshDevices() {
  const list = $('deviceList');
  if (!list) return;
  if (!account?.user?.id) { list.innerHTML = '<div class="device-empty">Sign in to see your devices.</div>'; return; }
  try {
    const { devices = [] } = await api('/api/planner', { action: 'devices-list' });
    list.innerHTML = devices.length ? devices.map((device) => {
      const battery = device.battery_percent === null || device.battery_percent === undefined ? 'Charge unavailable' : `${Number(device.battery_percent)}%${device.charging ? ' · charging' : ''}`;
      const isCurrent = device.device_id === deviceId;
      return `<article class="device-card"><span class="device-icon" aria-hidden="true">▣</span><div class="device-copy"><strong>${escapeHtml(device.name || 'Device')}${isCurrent ? ' <span class="device-current">This device</span>' : ''}</strong><span>${escapeHtml(formatDeviceSeen(device.last_seen))}</span></div><span class="device-charge">${escapeHtml(battery)}</span><button class="device-remove" type="button" data-device-id="${escapeHtml(device.device_id)}" aria-label="Remove ${escapeHtml(device.name || 'device')}">Remove</button></article>`;
    }).join('') : '<div class="device-empty">Your devices will appear here when you open AURA on them.</div>';
    list.querySelectorAll('.device-remove').forEach(button => button.addEventListener('click', async () => {
      if (!confirm('Remove this device from your AURA list?')) return;
      try {
        await api('/api/planner', { action: 'device-remove', deviceId: button.dataset.deviceId });
        if (button.dataset.deviceId === deviceId) { deviceReportingPaused = true; clearInterval(deviceHeartbeatTimer); }
        await refreshDevices();
      } catch { toast('Could not remove that device. Try again.'); }
    }));
  } catch (error) {
    if (error.status === 401) list.innerHTML = '<div class="device-empty">Sign in to see your devices.</div>';
    else list.innerHTML = '<div class="device-empty">Device status is temporarily unavailable.</div>';
  }
}
function startDeviceSync() {
  clearInterval(deviceHeartbeatTimer);
  if (account?.user?.id) {
    reportDevice();
    deviceHeartbeatTimer = setInterval(reportDevice, 120000);
  }
}
window.addEventListener('online', reportDevice);
window.addEventListener('hashchange', () => { if (viewFromHash() === 'devices') refreshDevices(); });
window.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') reportDevice(); });
if (typeof navigator.getBattery === 'function') navigator.getBattery().then((battery) => {
  deviceBattery = battery;
  battery.addEventListener('levelchange', reportDevice);
  battery.addEventListener('chargingchange', reportDevice);
}).catch(() => {});
deviceRefreshTimer = setInterval(() => { if (viewFromHash() === 'devices') refreshDevices(); }, 60000);
const authCall = async (path, body = {}) => {
  const response = await fetch(`/api/auth/${path}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify(body)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.error || 'Authentication could not be completed.');
  return data;
};
const planner = initPlanner(api, () => account);
const priorityTools = $('homePriorityTools');
window.addEventListener('aura:points-updated', () => reloadAccount());
// Web Focus is unrestricted; the timer keeps counting while the browser tab is in the background.
const focusStoreKey = 'aura_focus_studio_v1';
const focusPointIntervalSeconds = 120;
const focusDay = new Date(); focusDay.setHours(focusDay.getHours() - 2); const focusToday = `${focusDay.getFullYear()}-${String(focusDay.getMonth()+1).padStart(2,'0')}-${String(focusDay.getDate()).padStart(2,'0')}`;
let focusState;
try { focusState = JSON.parse(localStorage.getItem(focusStoreKey)) || {}; } catch { focusState = {}; }
if (focusState.date !== focusToday) focusState = { date: focusToday, minutes: 25, remaining: 25 * 60, completed: 0, running: false, pendingAwards: [] };
focusState.minutes = Number.isInteger(Number(focusState.minutes)) && Number(focusState.minutes) >= 1 && Number(focusState.minutes) <= 180 ? Number(focusState.minutes) : 25;
focusState.remaining = Math.max(0, Math.min(focusState.minutes * 60, Number(focusState.remaining) || focusState.minutes * 60));
focusState.completed = Math.max(0, Number(focusState.completed) || 0);
focusState.activeSeconds = Math.max(0, Number(focusState.activeSeconds) || 0);
focusState.awardedSegments = Math.max(0, Number(focusState.awardedSegments) || 0);
focusState.pendingAwards = []; // Remove old non-Focus reward requests.
focusState.sessionId = typeof focusState.sessionId === 'string' ? focusState.sessionId : '';
focusState.subjectId = typeof focusState.subjectId === 'string' ? focusState.subjectId : '';
// Start a fresh point segment when upgrading from the old five-minute award cadence.
if (focusState.sessionId && !focusState.sessionId.startsWith('f2-')) {
  focusState.sessionId = ''; focusState.activeSeconds = 0; focusState.awardedSegments = 0;
}
focusState.running = false;
let focusInterval;
let focusAudioContext;
let focusPeriodStartedAt = 0;
let focusPeriodBaseSeconds = focusState.activeSeconds;
function playFocusTick(at) {
  if (!focusAudioContext || focusAudioContext.state !== 'running') return;
  const oscillator = focusAudioContext.createOscillator(), gain = focusAudioContext.createGain();
  oscillator.type = 'square'; oscillator.frequency.value = 1250;
  gain.gain.setValueAtTime(0.035, at); gain.gain.exponentialRampToValueAtTime(0.001, at + 0.055);
  oscillator.connect(gain); gain.connect(focusAudioContext.destination); oscillator.start(at); oscillator.stop(at + 0.06);
}
function playFocusAlarm() {
  try {
    const AudioContextType = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextType) return;
    focusAudioContext ||= new AudioContextType();
    if (focusAudioContext.state === 'suspended') focusAudioContext.resume();
    const start = focusAudioContext.currentTime + 0.04;
    for (let pair = 0; pair < 10; pair++) { const at = start + pair * 0.54; playFocusTick(at); playFocusTick(at + 0.13); }
  } catch { /* Audio may be unavailable; the completed state still appears. */ }
}
function persistFocus() { localStorage.setItem(focusStoreKey, JSON.stringify(focusState)); }
function renderFocusSubjects() {
  const select = $('focusSubject'), subjects = planner.getSubjects(), current = focusState.subjectId;
  select.innerHTML = '<option value="">Choose a subject</option>' + subjects.map(subject => `<option value="${escapeHtml(subject.id)}">${escapeHtml(subject.name)}</option>`).join('');
  select.value = subjects.some(subject => subject.id === current) ? current : '';
  focusState.subjectId = select.value; select.disabled = focusState.running;
}
async function syncFocusAwards() {
  if (!account || !navigator.onLine || focusState.syncingAwards) return;
  focusState.syncingAwards = true;
  try {
    while (focusState.pendingAwards.length && account) {
      const result = await api('/api/planner', { action: 'focus-point', ...focusState.pendingAwards[0] });
      focusState.pendingAwards.shift();
      state.total = Number(result.total ?? state.total); persistFocus(); reloadAccount();
    }
  } catch (error) {
    if (error.status === 409) { focusState.pendingAwards.shift(); persistFocus(); toast(error.message); }
  } finally { focusState.syncingAwards = false; }
}
window.addEventListener('online', syncFocusAwards);
window.addEventListener('aura:planner-synced', syncFocusAwards);
function awardFocusSegment() {
  if (!focusState.sessionId || !focusState.subjectId) return;
  const segment = focusState.awardedSegments + 1;
  const activityDay = new Date(); activityDay.setHours(activityDay.getHours() - 2);
  const activityDate = `${activityDay.getFullYear()}-${String(activityDay.getMonth()+1).padStart(2,'0')}-${String(activityDay.getDate()).padStart(2,'0')}`;
  focusState.awardedSegments = segment;
  focusState.pendingAwards.push({ sessionId: focusState.sessionId, segment, subjectId: focusState.subjectId, date: activityDate });
  planner.logFocusStudyBlock(focusState.sessionId, segment, focusState.subjectId);
  persistFocus(); syncFocusAwards(); toast('2 focused minutes · +0.01 AP earned');
}
function updateFocusElapsed() {
  if (!focusState.running || !focusPeriodStartedAt) return;
  const now = Date.now();
  focusState.remaining = Math.max(0, Math.ceil((focusState.deadlineAt - now) / 1000));
  focusState.activeSeconds = focusPeriodBaseSeconds + Math.floor((now - focusPeriodStartedAt) / 1000);
}
function tickFocus() {
  if (!focusState.running) return;
  updateFocusElapsed();
  while (Math.floor(focusState.activeSeconds / focusPointIntervalSeconds) > focusState.awardedSegments) awardFocusSegment();
  if (focusState.remaining <= 0) return finishFocus();
  renderFocus();
}
function finishFocus() {
  clearInterval(focusInterval); focusInterval = null;
  focusState.running = false; focusState.remaining = focusState.minutes * 60; focusState.completed += 1;
  focusState.activeSeconds = 0; focusState.awardedSegments = 0; focusState.sessionId = '';
  focusPeriodStartedAt = 0; focusState.deadlineAt = 0;
  playFocusAlarm();
  persistFocus(); renderFocus();
  $('focusStatus').textContent = `Session complete · ${focusState.completed} today. Take a short break.`;
}
function renderFocus() {
  const remaining = focusState.remaining;
  $('focusClock').textContent = `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  $('focusProgress').style.width = `${Math.max(0, Math.min(100, (1 - remaining / (focusState.minutes * 60)) * 100))}%`;
  $('focusToggle').textContent = focusState.running ? 'Pause focus' : 'Start focus';
  $('focusLaunch').textContent = focusState.running ? 'Focus active' : 'Focus';
  $('focusToggle').setAttribute('aria-pressed', String(focusState.running));
  document.querySelectorAll('.focus-preset').forEach(button => {
    const active = Number(button.dataset.focusMinutes) === focusState.minutes;
    button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  $('focusStatus').textContent = `${focusState.completed} sessions today · ${formatAP(focusState.awardedSegments)} AP earned`;
  window.AURA_FOCUS_ACTIVE = Boolean(focusState.running);
  const screen = $('focusScreen'); screen.classList.toggle('active', Boolean(focusState.running)); screen.setAttribute('aria-hidden', String(!focusState.running));
  $('focusScreenTime').textContent = `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  $('focusScreenClock').textContent = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date());
  const subject = planner.getSubjects().find(item => item.id === focusState.subjectId);
  $('focusScreenSubject').textContent = subject?.name || 'Choose a subject';
  $('focusSubject').disabled = focusState.running;
}
renderFocusSubjects();
window.addEventListener('aura:subjects-updated', renderFocusSubjects);
$('focusSubject').addEventListener('change', () => {
  focusState.subjectId = $('focusSubject').value;
  if (focusState.activeSeconds > 0) { focusState.sessionId = ''; focusState.activeSeconds = 0; focusState.awardedSegments = 0; }
  persistFocus(); renderFocus();
});
document.querySelectorAll('.focus-preset').forEach(button => button.addEventListener('click', () => {
  clearInterval(focusInterval); focusInterval = null;
  if (focusState.running) focusState.running = false;
  focusState.minutes = Number(button.dataset.focusMinutes); focusState.remaining = focusState.minutes * 60;
  focusState.sessionId = ''; focusState.activeSeconds = 0; focusState.awardedSegments = 0;
  $('focusCustomMinutes').value = focusState.minutes;
  persistFocus(); renderFocus();
}));
$('focusCustomSet').addEventListener('click', () => {
  const minutes = Number($('focusCustomMinutes').value);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 180) return toast('Choose a custom session from 1 to 180 minutes.');
  clearInterval(focusInterval); focusInterval = null;
  focusState.running = false; focusState.minutes = minutes; focusState.remaining = minutes * 60;
  focusState.sessionId = ''; focusState.activeSeconds = 0; focusState.awardedSegments = 0;
  persistFocus(); renderFocus(); toast(`${minutes}-minute session set`);
});
$('focusToggle').addEventListener('click', () => {
  clearInterval(focusInterval); focusInterval = null;
  if (focusState.running) {
    updateFocusElapsed();
    focusState.running = false; focusPeriodStartedAt = 0; focusState.deadlineAt = 0;
  } else {
    if (!$('focusSubject').value) { $('focusSubject').focus(); return toast('Choose a subject before starting focus.'); }
    focusState.subjectId = $('focusSubject').value;
    focusState.sessionId ||= `f2-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2,8)}`}`;
    try { const AudioContextType = window.AudioContext || window.webkitAudioContext; if (AudioContextType) { focusAudioContext ||= new AudioContextType(); focusAudioContext.resume(); } } catch {}
    focusState.running = true;
    focusPeriodStartedAt = Date.now(); focusPeriodBaseSeconds = focusState.activeSeconds;
    focusState.deadlineAt = focusPeriodStartedAt + focusState.remaining * 1000;
    focusInterval = window.setInterval(tickFocus, 1000);
  }
  persistFocus(); renderFocus();
  if (!focusState.running) window.dispatchEvent(new Event('focus'));
});
$('focusLaunch').addEventListener('click', () => {
  if (focusState.running) return;
  if (location.hash !== '#home') location.hash = '#home';
  window.setTimeout(() => {
    document.querySelector('.focus-studio').scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (!$('focusSubject').value) { $('focusSubject').focus(); toast('Choose a subject to start'); return; }
    $('focusToggle').click();
  }, 80);
});
$('focusReset').addEventListener('click', () => { clearInterval(focusInterval); focusInterval = null; focusState.running = false; focusState.remaining = focusState.minutes * 60; focusState.activeSeconds = 0; focusState.awardedSegments = 0; focusState.sessionId = ''; persistFocus(); renderFocus(); });
$('focusScreenExit').addEventListener('click', () => { if (focusState.running) $('focusToggle').click(); });
document.addEventListener('keydown', event => { if (event.key === 'Escape' && focusState.running) $('focusScreenExit').click(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden && focusState.running) tickFocus(); });
window.addEventListener('pagehide', () => {
  if (focusState.running) { updateFocusElapsed(); clearInterval(focusInterval); focusInterval = null; focusState.running = false; focusPeriodStartedAt = 0; focusState.deadlineAt = 0; persistFocus(); }
});
$('focusCustomMinutes').value = focusState.minutes;
renderFocus();
syncFocusAwards();

function message(text, error = false) {
  $('authError').textContent = text || '';
  $('authError').style.color = error ? 'var(--orange)' : 'var(--olive)';
}
const installButton = $('installApp');
const appInstallDialog = $('appInstallDialog');
let installPrompt = null;
const isInstalled = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const isAppleMobile = /iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isAndroid = /Android/i.test(navigator.userAgent);
installButton.hidden = isInstalled || (!isAppleMobile && !isAndroid);
if (!isInstalled) installButton.textContent = isAppleMobile ? 'Add AURA' : 'Install AURA';
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); installPrompt = event; installButton.hidden = false; installButton.textContent = 'Install AURA'; });
async function openWebInstaller() {
  if (!installPrompt) {
    $('toast').textContent = isAppleMobile
      ? 'In Safari, tap Share, then Add to Home Screen.'
      : isAndroid
        ? 'In Chrome, tap ⋮, then Install app or Add to Home screen.'
        : 'Open your browser menu and choose Install app or Add to Home Screen.';
    $('toast').classList.add('show'); setTimeout(() => $('toast').classList.remove('show'), 4000);
    return;
  }
  await installPrompt.prompt();
  const { outcome } = await installPrompt.userChoice;
  installPrompt = null;
  if (outcome === 'accepted') installButton.hidden = true;
}
installButton.addEventListener('click', openWebInstaller);
document.getElementById('appInstallShortcut')?.addEventListener('click', () => appInstallDialog?.showModal());
document.getElementById('closeAppInstallDialog')?.addEventListener('click', () => appInstallDialog?.close());
appInstallDialog?.addEventListener('click', event => { if (event.target === appInstallDialog) appInstallDialog.close(); });
document.getElementById('installWebOption')?.addEventListener('click', () => installButton.click());
document.getElementById('installWebHomeOption')?.addEventListener('click', () => installButton.click());
document.getElementById('installWebDialogOption')?.addEventListener('click', () => { appInstallDialog?.close(); openWebInstaller(); });
window.addEventListener('appinstalled', () => { installPrompt = null; installButton.hidden = true; });
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('/sw.js').catch(() => {});
function setAuthMode(signup) {
  $('authTitle').textContent = signup ? 'Create your AURA account' : 'Welcome back';
  $('authSubmit').textContent = signup ? 'Create account' : 'Sign in';
  $('authMode').textContent = signup ? 'I already have an account' : 'Create an account';
  $('authNameLabel').hidden = !signup;
  document.querySelector('#authForm [name="password"]').autocomplete = signup ? 'new-password' : 'current-password';
  $('authForm').dataset.mode = signup ? 'signup' : 'login';
  message('');
}
async function refreshLeaderboard() {
  try {
    const { entries } = await api('/api/leaderboard');
    $('leaderboardList').innerHTML = entries.map((entry, i) => `<div class="leader-entry"><span>${i + 1}. ${i === 0 ? `${escapeHtml(entry.title || 'Champion')} ` : ''}${escapeHtml(entry.name)}</span><b>${formatAP(entry.points)} AP</b></div>`).join('') || '<p class="help">No validated scores yet.</p>';
    $('leaderNotice').textContent = 'Only Focus timer sessions earn Aura Points.';
  } catch { $('leaderboardList').innerHTML = '<p class="help">Leaderboard unavailable right now.</p>'; }
}
function escapeHtml(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function renderPeriodTracker() {
  const selectedMonth = $('periodDate').value || `${localPeriodNow.getFullYear()}-${String(localPeriodNow.getMonth() + 1).padStart(2, '0')}`;
  const selectedYear = Number($('periodYear').value) || localPeriodNow.getFullYear();
  const selected = periodMode === 'month' ? selectedMonth : String(selectedYear);
  const rows = verifiedHistory.filter(row => periodMode === 'month' ? row.day.startsWith(selectedMonth) : row.day.startsWith(`${selectedYear}-`));
  const totalTasks = rows.reduce((sum, row) => sum + Number(row.completed || 0), 0);
  const totalPoints = rows.reduce((sum, row) => sum + Number(row.points || 0), 0);
  $('periodCompleted').textContent = totalTasks.toLocaleString();
  $('periodPoints').textContent = formatAP(totalPoints);
  $('periodDays').textContent = rows.length.toLocaleString();
  const count = periodMode === 'month' ? new Date(Number(selectedMonth.slice(0, 4)), Number(selectedMonth.slice(5, 7)), 0).getDate() : 12;
  const buckets = Array.from({ length: count }, () => ({ tasks: 0, points: 0 }));
  for (const row of rows) {
    const index = periodMode === 'month' ? Number(row.day.slice(8, 10)) - 1 : Number(row.day.slice(5, 7)) - 1;
    if (buckets[index]) { buckets[index].tasks += Number(row.completed || 0); buckets[index].points += Number(row.points || 0); }
  }
  const max = Math.max(1, ...buckets.map(item => item.tasks));
  $('periodChart').innerHTML = buckets.map((item, index) => `<div class="period-bar${item.tasks ? ' active' : ''}" style="height:${Math.max(2, item.tasks / max * 100)}%" title="${periodMode === 'month' ? `${selectedMonth}-${String(index + 1).padStart(2, '0')}` : new Date(selectedYear, index).toLocaleString(undefined, { month: 'long' })}: ${item.tasks} verified tasks · ${formatAP(item.points)} AP"></div>`).join('');
  $('periodLabels').innerHTML = periodMode === 'month' ? `<span>1</span><span>${Math.ceil(count / 2)}</span><span>${count}</span>` : '<span>Jan</span><span>Apr</span><span>Aug</span><span>Dec</span>';
  $('periodNote').textContent = account ? (rows.length ? `${periodMode === 'month' ? new Date(`${selectedMonth}-01T12:00:00`).toLocaleString(undefined, { month: 'long', year: 'numeric' }) : selectedYear} · Focus timer records only.` : `No Focus timer activity for ${periodMode === 'month' ? selectedMonth : selectedYear} yet.`) : 'Sign in to sync Focus timer points across devices.';
}
document.querySelectorAll('[data-period]').forEach(button => button.addEventListener('click', () => {
  periodMode = button.dataset.period;
  document.querySelectorAll('[data-period]').forEach(item => { const active = item === button; item.classList.toggle('active', active); item.setAttribute('aria-pressed', String(active)); });
  $('periodDate').hidden = periodMode !== 'month'; $('periodYear').hidden = periodMode !== 'year'; renderPeriodTracker();
}));
$('periodDate').addEventListener('change', renderPeriodTracker);
$('periodYear').addEventListener('change', renderPeriodTracker);
function appDatePlus(days) {
  const date = new Date(); date.setHours(date.getHours() - 2); date.setDate(date.getDate() + days);
  const datePart = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  return `${datePart}T09:00`;
}
let tomorrowTemplate = [];
function planRows(rows, readonly = false) {
  $('tomorrowScheduleRows').innerHTML = rows.map((item, index) => `<div class="tomorrow-schedule-row${readonly ? ' is-template' : ''}" data-subject-id="${escapeHtml(item.subjectId || '')}" data-kind="${escapeHtml(item.kind || 'Personal')}"><input class="field" data-plan-title value="${escapeHtml(item.title || '')}" placeholder="Class, study, or activity" aria-label="Activity ${index + 1}" ${readonly ? 'readonly' : 'required'}><input class="field" data-plan-start type="time" value="${escapeHtml(item.start || '')}" aria-label="Start time" ${readonly ? 'readonly' : 'required'}><input class="field" data-plan-end type="time" value="${escapeHtml(item.end || '')}" aria-label="End time" ${readonly ? 'readonly' : 'required'}><button class="icon-button" type="button" data-plan-remove aria-label="Remove activity" ${readonly ? 'hidden' : ''}>×</button></div>`).join('');
  $('tomorrowAddRow').hidden = readonly;
}
function showTomorrowPlan() {
  if (!account?.user?.id || window.AURA_FOCUS_ACTIVE || $('tomorrowPlanDialog').open) return;
  const day = today(), user = account.user.id, savedKey = `aura_tomorrow_schedule_saved_${user}`;
  const sessionKey = `aura_tomorrow_schedule_later_${user}_${day}`;
  if (localStorage.getItem(savedKey) === day || sessionStorage.getItem(sessionKey) === day) return;
  if (document.querySelector('dialog[open]')) return window.setTimeout(showTomorrowPlan, 1200);
  const tomorrowDate = appDatePlus(1).slice(0, 10), lastWeek = new Date(`${tomorrowDate}T12:00:00`); lastWeek.setDate(lastWeek.getDate() - 7);
  const lastWeekDate = `${lastWeek.getFullYear()}-${String(lastWeek.getMonth() + 1).padStart(2, '0')}-${String(lastWeek.getDate()).padStart(2, '0')}`;
  tomorrowTemplate = planner.getScheduleTemplate(lastWeekDate);
  $('tomorrowPlanMode').value = 'same';
  planRows(tomorrowTemplate, true);
  $('tomorrowPlanDialog').showModal();
}
$('tomorrowPlanMode').addEventListener('change', () => {
  const mode = $('tomorrowPlanMode').value;
  planRows(mode === 'new' ? [{ title: '', start: '', end: '', kind: 'Personal' }] : tomorrowTemplate, mode === 'same');
});
$('tomorrowAddRow').addEventListener('click', () => {
  const rows = [...$('tomorrowScheduleRows').querySelectorAll('.tomorrow-schedule-row')].map(row => ({ title: row.querySelector('[data-plan-title]').value, start: row.querySelector('[data-plan-start]').value, end: row.querySelector('[data-plan-end]').value, subjectId: row.dataset.subjectId || '', kind: row.dataset.kind || 'Personal' }));
  rows.push({ title: '', start: '', end: '', kind: 'Personal' }); planRows(rows, false);
});
$('tomorrowScheduleRows').addEventListener('click', event => {
  if (!event.target.closest('[data-plan-remove]')) return;
  const row = event.target.closest('.tomorrow-schedule-row'); row.remove();
  if (!$('tomorrowScheduleRows').children.length) planRows([{ title: '', start: '', end: '', kind: 'Personal' }], false);
});
$('tomorrowPlanLater').addEventListener('click', () => {
  if (account?.user?.id) sessionStorage.setItem(`aura_tomorrow_schedule_later_${account.user.id}_${today()}`, today());
  $('tomorrowPlanDialog').close();
});
$('tomorrowPlanForm').addEventListener('submit', event => {
  event.preventDefault();
  const mode = $('tomorrowPlanMode').value;
  const rows = [...$('tomorrowScheduleRows').querySelectorAll('.tomorrow-schedule-row')].map(row => ({ title: row.querySelector('[data-plan-title]').value.trim(), start: row.querySelector('[data-plan-start]').value, end: row.querySelector('[data-plan-end]').value, subjectId: row.dataset.subjectId || '', kind: row.dataset.kind || 'Personal' }));
  if (mode !== 'same' && (!rows.length || rows.some(item => !item.title || !item.start || !item.end || item.end <= item.start))) return toast('Add at least one activity with a valid start and end time.');
  const tomorrowDate = appDatePlus(1).slice(0, 10);
  planner.addOneOffActivities(tomorrowDate, mode === 'same' ? tomorrowTemplate : rows, true);
  localStorage.setItem(`aura_tomorrow_schedule_saved_${account.user.id}`, today());
  $('tomorrowPlanDialog').close(); toast('Tomorrow’s timetable is ready');
});

function showAccount(data, options = {}) {
  account = data;
  startDeviceSync();
  planner.setAccount(data, options).then(() => { renderFocusSubjects(); syncFocusAwards(); });
  verifiedHistory = data?.history || [];
  renderPeriodTracker();
  $('authState').textContent = data ? 'Signed in' : 'Local preview';
  $('accountName').textContent = data?.profile?.display_name || 'Account';
  $('avatar').textContent = (data?.profile?.display_name || state.name || 'A').trim().slice(0, 1).toUpperCase();
  $('authOpen').hidden = !!data;
  $('authOut').hidden = !data;
  $('displayName').disabled = false;
  $('saveState').textContent = data ? 'Progress synced to your account' : 'Sign in to sync across devices';
  if (!data) { refreshLeaderboard(); return; }
  state.name = data.profile.display_name;
  state.total = Number(data.stats.total || 0);
  pending = 0;
  state.todayFocus = Number(data.stats.today || 0);
  const days = data.days || [];
  let streak = 0, expected = today();
  for (const day of days) { if (day !== expected) break; streak++; const d = new Date(`${expected}T00:00:00Z`); d.setUTCDate(d.getUTCDate() - 1); expected = d.toISOString().slice(0,10); }
  state.streak = streak;
  $('displayName').value = state.name;
  save();
  refreshLeaderboard();
  window.setTimeout(showTomorrowPlan, 400);
}
async function reloadAccount() {
  try { showAccount(await api('/api/me')); return true; }
  catch (error) {
    if (error.message === 'Sign in to sync your progress.') showAccount(null);
    else { $('authState').textContent = 'Sync unavailable'; $('saveState').textContent = 'Could not reach AURA sync'; }
    return false;
  }
}
if (viewFromHash() === 'devices') refreshDevices();
$('refreshDevices')?.addEventListener('click', () => { reportDevice(); refreshDevices(); });

$('authOpen').addEventListener('click', () => { setAuthMode(false); $('authDialog').showModal(); });
$('authClose').addEventListener('click', () => $('authDialog').close());
$('authMode').addEventListener('click', () => setAuthMode($('authForm').dataset.mode !== 'signup'));
$('authForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = new FormData(event.currentTarget), signup = event.currentTarget.dataset.mode === 'signup';
  const displayName = String(form.get('name') || '').trim() || 'Aura Explorer';
  const gender = String(form.get('gender') || '');
  $('authSubmit').disabled = true; $('authSubmit').textContent = signup ? 'Creating account…' : 'Signing in…'; message(signup ? 'Creating your account securely…' : 'Signing you in…');
  try {
    const result = await authCall(signup ? 'sign-up/email' : 'sign-in/email', {
      name: displayName,
      email: String(form.get('email') || '').trim(), password: String(form.get('password') || ''), ...(!signup ? { rememberMe: true } : {})
    });
    const signedIn = await reloadAccount();
    if (signedIn) {
      if (signup || gender) {
        try { await api('/api/profile', { name: signup ? displayName : state.name, gender: gender || 'prefer_not' }); refreshLeaderboard(); }
        catch (error) { message(`Account created, but the leaderboard title could not be saved: ${error.message}`, true); return; }
      }
      $('authDialog').close(); event.currentTarget.reset();
    } else if (signup && result?.user) {
      message('Your account was created. Sign in with the same email and password to start syncing.');
      setAuthMode(false);
      message('Your account was created. Sign in with the same email and password to start syncing.');
    } else if (!signup) {
      message('Sign-in succeeded, but AURA could not start your session. Please try again.', true);
    } else {
      message('AURA could not confirm account creation. Please try again.', true);
    }
  } catch (error) { message(error.message, true); }
  finally { $('authSubmit').disabled = false; $('authSubmit').textContent = $('authForm').dataset.mode === 'signup' ? 'Create account' : 'Sign in'; }
});
$('authOut').addEventListener('click', async () => {
  try { await authCall('sign-out'); } catch { /* local view still signs out */ }
  $('accountMenu').open = false; showAccount(null, { signedOut: true }); toast('Signed out');
});

async function saveDisplayName() {
  const name = $('displayName').value.trim().slice(0, 24);
  if (!name) { $('displayName').value = state.name || 'Aura Explorer'; return; }
  if (!account) { state.name = name; save(); toast('Name saved on this device'); return; }
  try { const result = await api('/api/profile', { name }); state.name = result.name; $('accountName').textContent = result.name; $('avatar').textContent = result.name.trim().slice(0, 1).toUpperCase(); save(); $('leaderName').textContent = result.name; toast('Profile saved'); }
  catch (error) { $('displayName').value = state.name; toast(error.message); }
}
$('displayName').addEventListener('change', saveDisplayName);
$('saveName').addEventListener('click', () => $('displayName').dispatchEvent(new Event('change', { bubbles: true })));


reloadAccount();

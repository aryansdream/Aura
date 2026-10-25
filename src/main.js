import { adjustedReward } from '../lib/progression.js';

const $ = (id) => document.getElementById(id);
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
function setView(view) {
  const library = view === 'library';
  $('homeView').hidden = library;
  $('libraryView').hidden = !library;
  document.querySelectorAll('.view-tab').forEach((button) => {
    const active = button.dataset.view === view;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  if (library) renderTasks();
}
document.querySelectorAll('.view-tab').forEach((button) => {
  button.addEventListener('click', () => { location.hash = button.dataset.view === 'library' ? 'tasks' : 'home'; });
});
window.addEventListener('hashchange', () => setView(location.hash === '#tasks' ? 'library' : 'home'));
setView(location.hash === '#tasks' ? 'library' : 'home');
const api = async (url, body) => {
  const response = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    headers: body === undefined ? {} : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store'
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || 'That request could not be completed.');
  return data;
};
const authCall = async (path, body = {}) => {
  const response = await fetch(`/api/auth/${path}`, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify(body)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.error || 'Authentication could not be completed.');
  return data;
};
// A device-local focus timer; it tracks time only and never awards AP.
const focusStoreKey = 'aura_focus_studio_v1';
const focusToday = new Date().toISOString().slice(0, 10);
let focusState;
try { focusState = JSON.parse(localStorage.getItem(focusStoreKey)) || {}; } catch { focusState = {}; }
if (focusState.date !== focusToday) focusState = { date: focusToday, minutes: 25, remaining: 25 * 60, completed: 0, running: false };
focusState.minutes = Number.isInteger(Number(focusState.minutes)) && Number(focusState.minutes) >= 1 && Number(focusState.minutes) <= 180 ? Number(focusState.minutes) : 25;
focusState.remaining = Math.max(0, Number(focusState.remaining) || focusState.minutes * 60);
focusState.completed = Math.max(0, Number(focusState.completed) || 0);
let focusInterval;
let focusAudioContext;
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
function finishFocus() {
  clearInterval(focusInterval); focusInterval = null;
  focusState.running = false; focusState.endAt = null; focusState.remaining = focusState.minutes * 60; focusState.completed += 1;
  persistFocus(); renderFocus(); playFocusAlarm();
  $('focusStatus').textContent = `Session complete · ${focusState.completed} today. Take a short break.`;
}
function renderFocus() {
  const remaining = focusState.running ? Math.max(0, Math.ceil((focusState.endAt - Date.now()) / 1000)) : focusState.remaining;
  if (focusState.running && remaining <= 0) return finishFocus();
  $('focusClock').textContent = `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  $('focusProgress').style.width = `${Math.max(0, Math.min(100, (1 - remaining / (focusState.minutes * 60)) * 100))}%`;
  $('focusToggle').textContent = focusState.running ? 'Pause focus' : 'Start focus';
  $('focusToggle').setAttribute('aria-pressed', String(focusState.running));
  document.querySelectorAll('.focus-preset').forEach(button => {
    const active = Number(button.dataset.focusMinutes) === focusState.minutes;
    button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  $('focusStatus').textContent = `Sessions today: ${focusState.completed} · focus time stays separate from Aura Points.`;
}
document.querySelectorAll('.focus-preset').forEach(button => button.addEventListener('click', () => {
  clearInterval(focusInterval); focusInterval = null;
  if (focusState.running) { focusState.running = false; focusState.endAt = null; }
  focusState.minutes = Number(button.dataset.focusMinutes); focusState.remaining = focusState.minutes * 60;
  $('focusCustomMinutes').value = focusState.minutes;
  persistFocus(); renderFocus();
}));
$('focusCustomSet').addEventListener('click', () => {
  const minutes = Number($('focusCustomMinutes').value);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 180) return toast('Choose a custom session from 1 to 180 minutes.');
  clearInterval(focusInterval); focusInterval = null;
  focusState.running = false; focusState.endAt = null; focusState.minutes = minutes; focusState.remaining = minutes * 60;
  persistFocus(); renderFocus(); toast(`${minutes}-minute session set`);
});
$('focusToggle').addEventListener('click', () => {
  clearInterval(focusInterval); focusInterval = null;
  if (focusState.running) { focusState.remaining = Math.max(0, Math.ceil((focusState.endAt - Date.now()) / 1000)); focusState.running = false; focusState.endAt = null; }
  else { try { const AudioContextType = window.AudioContext || window.webkitAudioContext; if (AudioContextType) { focusAudioContext ||= new AudioContextType(); focusAudioContext.resume(); } } catch {} focusState.endAt = Date.now() + focusState.remaining * 1000; focusState.running = true; focusInterval = window.setInterval(renderFocus, 1000); }
  persistFocus(); renderFocus();
});
$('focusReset').addEventListener('click', () => { clearInterval(focusInterval); focusInterval = null; focusState.running = false; focusState.endAt = null; focusState.remaining = focusState.minutes * 60; persistFocus(); renderFocus(); });
if (focusState.running && focusState.endAt) focusState.remaining = Math.max(0, Math.ceil((focusState.endAt - Date.now()) / 1000));
$('focusCustomMinutes').value = focusState.minutes;
renderFocus();
if (focusState.running) focusInterval = window.setInterval(renderFocus, 1000);

function message(text, error = false) {
  $('authError').textContent = text || '';
  $('authError').style.color = error ? 'var(--orange)' : 'var(--olive)';
}
const installButton = $('installApp');
let installPrompt = null;
const isInstalled = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const isAppleMobile = /iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
installButton.hidden = isInstalled;
if (isAppleMobile && !isInstalled) installButton.textContent = 'Add AURA';
window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); installPrompt = event; installButton.hidden = false; installButton.textContent = 'Install AURA'; });
installButton.addEventListener('click', async () => {
  if (!installPrompt) {
    $('toast').textContent = isAppleMobile ? 'In Safari, tap Share, then Add to Home Screen.' : 'Open your browser menu and choose Install app or Add to Home Screen.';
    $('toast').classList.add('show'); setTimeout(() => $('toast').classList.remove('show'), 4000);
    return;
  }
  await installPrompt.prompt();
  const { outcome } = await installPrompt.userChoice;
  installPrompt = null;
  if (outcome === 'accepted') installButton.hidden = true;
});
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
    $('leaderboardList').innerHTML = entries.map((entry, i) => `<div class="leader-entry"><span>${i + 1}. ${i === 0 ? `${escapeHtml(entry.title || 'Champion')} ` : ''}${escapeHtml(entry.name)}</span><b>${Number(entry.points).toLocaleString()} AP</b></div>`).join('') || '<p class="help">No validated scores yet.</p>';
    $('leaderNotice').textContent = 'Scores include server-recorded missions after the day’s screen tracker is submitted.';
  } catch { $('leaderboardList').innerHTML = '<p class="help">Leaderboard unavailable right now.</p>'; }
}
function escapeHtml(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function refreshPending() {
  let el = $('pendingPoints');
  if (!el) { el = document.createElement('span'); el.id = 'pendingPoints'; el.className = 'pending-ap'; $('totalAP').insertAdjacentElement('afterend', el); }
  el.textContent = pending ? `${pending} AP awaiting today’s tracker` : '';
}
function renderPeriodTracker() {
  const selectedMonth = $('periodDate').value || `${localPeriodNow.getFullYear()}-${String(localPeriodNow.getMonth() + 1).padStart(2, '0')}`;
  const selectedYear = Number($('periodYear').value) || localPeriodNow.getFullYear();
  const selected = periodMode === 'month' ? selectedMonth : String(selectedYear);
  const rows = verifiedHistory.filter(row => periodMode === 'month' ? row.day.startsWith(selectedMonth) : row.day.startsWith(`${selectedYear}-`));
  const totalTasks = rows.reduce((sum, row) => sum + Number(row.completed || 0), 0);
  const totalPoints = rows.reduce((sum, row) => sum + Number(row.points || 0), 0);
  $('periodCompleted').textContent = totalTasks.toLocaleString();
  $('periodPoints').textContent = totalPoints.toLocaleString();
  $('periodDays').textContent = rows.length.toLocaleString();
  const count = periodMode === 'month' ? new Date(Number(selectedMonth.slice(0, 4)), Number(selectedMonth.slice(5, 7)), 0).getDate() : 12;
  const buckets = Array.from({ length: count }, () => ({ tasks: 0, points: 0 }));
  for (const row of rows) {
    const index = periodMode === 'month' ? Number(row.day.slice(8, 10)) - 1 : Number(row.day.slice(5, 7)) - 1;
    if (buckets[index]) { buckets[index].tasks += Number(row.completed || 0); buckets[index].points += Number(row.points || 0); }
  }
  const max = Math.max(1, ...buckets.map(item => item.tasks));
  $('periodChart').innerHTML = buckets.map((item, index) => `<div class="period-bar${item.tasks ? ' active' : ''}" style="height:${Math.max(2, item.tasks / max * 100)}%" title="${periodMode === 'month' ? `${selectedMonth}-${String(index + 1).padStart(2, '0')}` : new Date(selectedYear, index).toLocaleString(undefined, { month: 'long' })}: ${item.tasks} verified tasks · ${item.points} AP"></div>`).join('');
  $('periodLabels').innerHTML = periodMode === 'month' ? `<span>1</span><span>${Math.ceil(count / 2)}</span><span>${count}</span>` : '<span>Jan</span><span>Apr</span><span>Aug</span><span>Dec</span>';
  $('periodNote').textContent = account ? (rows.length ? `${periodMode === 'month' ? new Date(`${selectedMonth}-01T12:00:00`).toLocaleString(undefined, { month: 'long', year: 'numeric' }) : selectedYear} · verified records only; unsubmitted days are not included.` : `No verified activity for ${periodMode === 'month' ? selectedMonth : selectedYear} yet.`) : 'Sign in to see your monthly and yearly history. Only tracker-verified days count.';
}
document.querySelectorAll('[data-period]').forEach(button => button.addEventListener('click', () => {
  periodMode = button.dataset.period;
  document.querySelectorAll('[data-period]').forEach(item => { const active = item === button; item.classList.toggle('active', active); item.setAttribute('aria-pressed', String(active)); });
  $('periodDate').hidden = periodMode !== 'month'; $('periodYear').hidden = periodMode !== 'year'; renderPeriodTracker();
}));
$('periodDate').addEventListener('change', renderPeriodTracker);
$('periodYear').addEventListener('change', renderPeriodTracker);
function showTracker(proof) {
  $('trackerStatus').textContent = proof ? `Tracker checked · ${Math.ceil(proof.size_bytes / 1024)} KB · screenshot discarded · points validated${Number(proof.balance_bonus) ? ' · +25 AP balance bonus' : ''}.` : (account ? 'Upload at day’s end to validate points and claim any balance bonus.' : 'Sign in to submit a tracker.');
  $('trackerStatus').className = `help ${proof ? 'tracker-ok' : ''}`;
  $('trackerFile').disabled = !account || !!proof;
  $('trackerForm').querySelector('button').disabled = !account || !!proof;
  $('trackerForm').querySelector('button').textContent = proof ? 'Tracker submitted' : 'Submit today’s tracker';
}
function showAccount(data) {
  account = data;
  verifiedHistory = data?.history || [];
  renderPeriodTracker();
  window.AURA_PROOF_SUBMITTED = !!data?.proof;
  window.AURA_BALANCE_BONUS = Number(data?.proof?.balance_bonus || 0);
  for (const task of data?.customTasks || []) {
    if (!window.AURA_CATALOG.some(item => item.id === task.id)) window.AURA_CATALOG.unshift({ ...task, difficulty: task.difficulty || 'Moderate', icon: task.icon || '✳' });
  }
  $('authState').textContent = data ? `Signed in · ${data.user.email}` : 'Local preview';
  $('authOpen').hidden = !!data;
  $('authOut').hidden = !data;
  $('displayName').disabled = false;
  $('leaderName').textContent = data ? data.profile.display_name : 'Sign in';
  $('leaderCaption').textContent = data ? 'Your validated score' : 'Registered AURA scores';
  $('leaderAP').textContent = data ? `${Number(data.stats.total).toLocaleString()} AP` : '— AP';
  $('saveState').textContent = data ? 'Progress synced to your account' : 'Sign in to sync across devices';
  if (!data) { showTracker(null); refreshLeaderboard(); return; }
  state.name = data.profile.display_name;
  state.total = Number(data.stats.total || 0);
  pending = Number(data.stats.pending || 0);
  window.AURA_BALANCE_AREAS = Number(data.balanceAreas || 0);
  state.missions = data.today.map(item => {
    const task = window.AURA_CATALOG.find(t => t.id === item.task_id);
    const points = Number(item.points) || adjustedReward(task?.points || 1, state.total);
    return { key: `${item.task_id}-${today()}`, id: item.task_id, title: task?.title || item.title || 'AURA mission', category: task?.category || item.category || 'Mission', points, date: today(), done: item.done };
  });
  const days = data.days || [];
  let streak = 0, expected = today();
  for (const day of days) { if (day !== expected) break; streak++; const d = new Date(`${expected}T00:00:00Z`); d.setUTCDate(d.getUTCDate() - 1); expected = d.toISOString().slice(0,10); }
  state.streak = streak;
  $('displayName').value = state.name;
  save(); renderTasks(); renderMissions(); refreshPending(); showTracker(data.proof);
  refreshLeaderboard();
}
async function reloadAccount() {
  try { showAccount(await api('/api/me')); return true; }
  catch (error) {
    if (error.message === 'Sign in to sync your progress.') showAccount(null);
    else { $('authState').textContent = 'Sync unavailable'; $('saveState').textContent = 'Could not reach AURA sync'; }
    return false;
  }
}

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
  showAccount(null); toast('Signed out');
});

// Route signed-in writes through the server. The existing local-only behavior remains
// available for visitors who have not created an account.
$('taskList').addEventListener('click', async (event) => {
  if (!account) return;
  const button = event.target.closest('[data-id]');
  if (!button) return;
  event.preventDefault(); event.stopImmediatePropagation();
  const task = window.AURA_CATALOG.find(t => t.id === button.dataset.id);
  if (!task) return;
  button.disabled = true;
  try {
    const { task: selectedTask } = await api('/api/select', { taskId: task.id });
    state.missions.push({ key: `${task.id}-${today()}`, id: task.id, title: task.title, category: task.category, points: selectedTask.points, date: today(), done: false });
    save(); renderTasks(); renderMissions(); toast('Mission added to today’s promises');
  } catch (error) { button.disabled = false; toast(error.message); }
}, true);
$('missions').addEventListener('click', async (event) => {
  if (!account) return;
  const button = event.target.closest('[data-check]');
  if (!button) return;
  event.preventDefault(); event.stopImmediatePropagation();
  const mission = state.missions.find(item => item.key === button.dataset.check);
  if (!mission || mission.done) return;
  button.disabled = true;
  try {
    const result = await api('/api/complete', { taskId: mission.id });
    mission.done = true; state.total = result.total; pending = result.pending;
    save(); renderMissions(); renderTasks(); refreshPending(); refreshLeaderboard();
    toast(`+${result.points} AP pending today’s tracker`);
  } catch (error) { button.disabled = false; toast(error.message); }
}, true);
$('customForm').addEventListener('submit', async (event) => {
  if (!account) return;
  event.preventDefault(); event.stopImmediatePropagation();
  const title = $('customTitle').value.trim();
  const difficulty = $('customDifficulty').value;
  try {
    const { task } = await api('/api/custom', { title, difficulty });
    window.AURA_CATALOG.unshift(task);
    $('customTitle').value = '';
    if (!$('category').querySelector('option[value="Your own"]')) $('category').insertAdjacentHTML('beforeend', '<option value="Your own">Your own</option>');
    $('category').value = 'all'; $('search').value = ''; renderTasks(); toast(`Custom mission saved · up to ${task.points} AP`);
  } catch (error) { toast(error.message); }
}, true);
async function saveDisplayName() {
  const name = $('displayName').value.trim().slice(0, 24);
  if (!name) { $('displayName').value = state.name || 'Aura Explorer'; return; }
  if (!account) { state.name = name; save(); toast('Name saved on this device'); return; }
  try { const result = await api('/api/profile', { name }); state.name = result.name; save(); $('leaderName').textContent = result.name; toast('Profile saved'); }
  catch (error) { $('displayName').value = state.name; toast(error.message); }
}
$('displayName').addEventListener('change', saveDisplayName);
$('saveName').addEventListener('click', () => $('displayName').dispatchEvent(new Event('change', { bubbles: true })));
$('trackerForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  const file = $('trackerFile').files[0];
  if (!file || !account) return;
  if (file.size > 2.5 * 1024 * 1024) return toast('Choose a screenshot under 2.5 MB.');
  const button = event.currentTarget.querySelector('button'); button.disabled = true; button.textContent = 'Submitting…';
  try {
    const data = await new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = () => reject(new Error('Could not read that screenshot.')); reader.readAsDataURL(file); });
    const result = await api('/api/proof', { mime: file.type, data });
    toast(`${result.todayPoints} Aura Points validated`); await reloadAccount();
  } catch (error) { button.disabled = false; button.textContent = 'Submit today’s tracker'; toast(error.message); }
});

reloadAccount();

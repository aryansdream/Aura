const STORE_KEY = 'aura_planner_guest_v1';
const LAST_USER_KEY = 'aura_planner_last_user_v1';
const pendingKey = userId => `aura_planner_pending_${encodeURIComponent(userId)}`;
const userStoreKey = userId => `aura_planner_user_${encodeURIComponent(userId)}`;
const week = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const defaultData = () => ({ subjects: [], schedule: [], scheduleOverrides: [], tasks: [], studyLogs: [], expenses: [], lifestyle: [], settings: { currency: 'INR', notificationCopy: '' }, completionChecks: [] });
const id = () => globalThis.crypto?.randomUUID?.() || `a${Date.now()}${Math.random().toString(36).slice(2, 8)}`;
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const localDate = (date = new Date()) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const appDate = (date = new Date()) => { const shifted = new Date(date); shifted.setHours(shifted.getHours() - 2); return localDate(shifted); };
const activityDateTime = (date, time) => { const result = new Date(`${date}T${time}:00`); if (Number(time.slice(0, 2)) < 2) result.setDate(result.getDate() + 1); return result; };
const currencyOptions = [['INR', '₹'], ['USD', '$'], ['EUR', '€'], ['GBP', '£']];
const dateLabel = value => value ? new Date(`${value.slice(0, 10)}T12:00:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : 'No deadline';

export function initPlanner(api, getAccount) {
  const get = id => document.getElementById(id);
  let pendingDelete = null;
  let editingScheduleId = null;
  let editingSubjectId = null;
  const deleteDialog = get('deleteConfirmDialog');
  function confirmDelete(label, action) {
    get('deleteConfirmTitle').textContent = `Delete “${label}”?`;
    get('deleteConfirmMessage').textContent = 'This will remove it from your AURA account. This can’t be undone.';
    pendingDelete = action;
    deleteDialog.showModal();
  }
  get('deleteConfirmCancel').addEventListener('click', () => { pendingDelete = null; deleteDialog.close(); });
  get('deleteConfirmAccept').addEventListener('click', () => {
    const action = pendingDelete;
    pendingDelete = null;
    deleteDialog.close();
    action?.();
  });
  deleteDialog.addEventListener('close', () => { pendingDelete = null; });
  let activeUser = localStorage.getItem(LAST_USER_KEY) || null;
  let data = defaultData();
  let syncTimer;
  let syncInFlight = false;
  let reminderTimer;
  let reminderAudio;
  let reminderAudioUnlocked = false;
  let completionQueue = [];
  let personalization = null;
  let scheduleSelectedDate = null;
  try { data = { ...defaultData(), ...JSON.parse(localStorage.getItem(activeUser ? userStoreKey(activeUser) : STORE_KEY) || '{}') }; } catch {}
  data.settings = { ...defaultData().settings, ...(data.settings || {}) };
  const saveLocal = () => localStorage.setItem(activeUser ? userStoreKey(activeUser) : STORE_KEY, JSON.stringify(data));
  async function flushPendingSync() {
    if (!activeUser || !navigator.onLine || syncInFlight || !localStorage.getItem(pendingKey(activeUser))) return;
    const userId = activeUser, snapshot = JSON.stringify(data);
    syncInFlight = true;
    try {
      const syncResult = await api('/api/planner', { data: JSON.parse(snapshot) });
      if (syncResult.comboAward?.addedPoints) window.dispatchEvent(new CustomEvent('aura:combo-awarded', { detail: syncResult.comboAward }));
      window.dispatchEvent(new Event('aura:planner-synced'));
      for (const check of data.completionChecks) {
        if (check.done && check.kind === 'Class' && !check.pointsSynced) await syncClassPoints(check);
      }
      if (activeUser === userId && JSON.stringify(data) === snapshot) {
        localStorage.removeItem(pendingKey(userId));
        get('plannerSync').textContent = 'Synced to your account';
      } else if (activeUser === userId) {
        clearTimeout(syncTimer); syncTimer = setTimeout(flushPendingSync, 0);
      }
    } catch {
      if (activeUser === userId) get('plannerSync').textContent = navigator.onLine ? 'Saved here · sync will retry' : 'Saved offline · will sync when online';
    } finally { syncInFlight = false; }
  }
  function persist(render = renderAll) {
    saveLocal();
    render();
    if (activeUser) {
      localStorage.setItem(pendingKey(activeUser), String(Date.now()));
      get('plannerSync').textContent = navigator.onLine ? 'Saved here · syncing…' : 'Saved offline · will sync when online';
      clearTimeout(syncTimer); syncTimer = setTimeout(flushPendingSync, 350);
    } else get('plannerSync').textContent = 'Saved on this device · sign in to sync';
  }
  window.addEventListener('online', flushPendingSync);
  const subjectName = subjectId => data.subjects.find(x => x.id === subjectId)?.name || 'General';
  const subjectColor = subjectId => data.subjects.find(x => x.id === subjectId)?.color || '#8b7cf6';
  function money(value) {
    const code = data.settings.currency || 'INR';
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: code, maximumFractionDigits: 2 }).format(Number(value) || 0);
  }
  function fillSubjectSelect(select, allowGeneral = true) {
    const first = allowGeneral ? '<option value="">General / no subject</option>' : '<option value="">Choose a subject</option>';
    select.innerHTML = first + data.subjects.map(s => `<option value="${esc(s.id)}">${esc(s.name)}</option>`).join('');
  }
  function plannedOn(date) {
    const weekday = new Date(`${date}T12:00:00`).getDay();
    const oneOff = data.schedule.filter(item => item.date === date);
    const recurring = data.schedule.filter(item => !item.date && item.days.includes(weekday));
    return data.scheduleOverrides.includes(date) || oneOff.some(item => item.overrideWeekday) ? oneOff : [...recurring, ...oneOff];
  }
  function renderSchedule() {
    const now = new Date(), appNow = new Date(now); appNow.setHours(appNow.getHours() - 2);
    const todayDate = localDate(appNow);
    if (!scheduleSelectedDate) scheduleSelectedDate = todayDate;
    const selected = new Date(`${scheduleSelectedDate}T12:00:00`), selectedDay = selected.getDay();
    const selectedItems = plannedOn(scheduleSelectedDate).sort((a,b) => a.start.localeCompare(b.start));
    const itemHtml = (item, compact = false) => `<div class="schedule-item${compact ? ' is-compact' : ''}" style="--subject:${subjectColor(item.subjectId)}"><span class="schedule-time">${esc(item.start)}–${esc(item.end)}</span><b>${esc(item.title)}</b>${compact ? '' : `<small>${esc(subjectName(item.subjectId))}${item.kind ? ` · ${esc(item.kind)}` : ''}</small><span class="schedule-actions"><button type="button" class="icon-button" data-edit-schedule="${esc(item.id)}" aria-label="Edit ${esc(item.title)}" title="Edit routine">✎</button><button type="button" class="icon-button" data-remove-schedule="${esc(item.id)}" aria-label="Remove ${esc(item.title)}" title="Remove routine">×</button></span>`}</div>`;
    const todayHtml = `<div class="schedule-featured-card"><div class="schedule-day-name">${scheduleSelectedDate === todayDate ? 'Today' : week[selectedDay]}<span>${dateLabel(scheduleSelectedDate)}</span></div>${selectedItems.length ? selectedItems.map(item => itemHtml(item)).join('') : '<div class="schedule-empty">Nothing planned</div>'}</div>`;
    const sunday = new Date(selected); sunday.setDate(sunday.getDate() - selectedDay);
    const weekHtml = week.map((day, index) => {
      if (index === selectedDay) return '';
      const slotDate = new Date(sunday); slotDate.setDate(slotDate.getDate() + index);
      const entries = plannedOn(localDate(slotDate)).sort((a,b) => a.start.localeCompare(b.start));
      return `<button type="button" class="schedule-day schedule-day-compact${localDate(slotDate) === todayDate ? ' is-today' : ''}" data-select-date="${localDate(slotDate)}" aria-label="View ${day}, ${dateLabel(localDate(slotDate))}"><span class="schedule-day-name">${day}<small>${dateLabel(localDate(slotDate))}</small></span>${entries.length ? entries.slice(0, 3).map(item => itemHtml(item, true)).join('') : '<span class="schedule-empty">Free</span>'}${entries.length > 3 ? `<span class="schedule-more">+${entries.length - 3} more</span>` : ''}</button>`;
    }).join('');
    get('scheduleDateSelect').value = scheduleSelectedDate;
    get('scheduleToday').innerHTML = todayHtml;
    get('scheduleTodayLabel').textContent = `${selectedItems.length} ${selectedItems.length === 1 ? 'block' : 'blocks'}`;
    get('scheduleWeek').innerHTML = weekHtml;
    const current = scheduleSelectedDate === todayDate && selectedItems.find(item => item.start <= `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}` && item.end > `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`);
    get('scheduleNow').textContent = current ? `In your day now · ${current.title}` : 'Make room for what matters';
    fillSubjectSelect(get('scheduleSubject'));
  }
  function renderSubjects() {
    const sorted = [...data.subjects].sort((a,b) => a.name.localeCompare(b.name));
    const week = Array.from({ length: 7 }, (_, i) => { const date = new Date(`${appDate()}T12:00:00`); date.setDate(date.getDate() - (6-i)); return localDate(date); });
    get('subjectList').innerHTML = sorted.length ? sorted.map(subject => {
      const logs = data.studyLogs.filter(log => log.subjectId === subject.id);
      const hours = logs.reduce((sum, log) => sum + Number(log.minutes || 0), 0) / 60;
      const tests = data.tasks.filter(task => task.subjectId === subject.id && task.kind === 'test' && !task.done).sort((a,b) => (a.deadline || '9999').localeCompare(b.deadline || '9999'));
      const daily = week.map(date => logs.filter(log => log.date === date).reduce((sum, log) => sum + Number(log.minutes || 0), 0));
      const max = Math.max(30, ...daily);
      const chart = `<div class="subject-mini-chart" role="img" aria-label="${esc(subject.name)} study minutes over the last seven days">${daily.map((minutes, i) => `<span class="subject-mini-day" title="${dateLabel(week[i])}: ${minutes} min"><i style="height:${minutes ? Math.max(5, Math.round(minutes/max*100)) : 2}%;background:${esc(subject.color)}"></i><small>${new Date(`${week[i]}T12:00:00`).toLocaleDateString(undefined,{weekday:'narrow'})}</small></span>`).join('')}</div>`;
      return `<article class="subject-card" style="--subject:${esc(subject.color)}"><div class="subject-card-top"><span class="subject-dot"></span><h3>${esc(subject.name)}</h3><div class="subject-actions"><button class="icon-button" type="button" data-edit-subject="${esc(subject.id)}" aria-label="Edit ${esc(subject.name)}" title="Edit subject">✎</button><button class="icon-button" type="button" data-remove-subject="${esc(subject.id)}" aria-label="Remove ${esc(subject.name)}" title="Remove subject">×</button></div></div>${subject.teacher ? `<p>${esc(subject.teacher)}</p>` : ''}<div class="subject-stats"><span><b>${hours.toFixed(1)}</b> study hours</span><span><b>${logs.length}</b> sessions</span><span><b>${tests.length}</b> upcoming tests</span></div><div class="subject-chart-label">STUDY TIME · 7 DAYS</div>${chart}${tests.slice(0,2).map(test => `<div class="subject-test"><span>${esc(test.title)}</span><b>${dateLabel(test.deadline)}</b></div>`).join('')}${subject.notes ? `<p class="subject-notes">${esc(subject.notes)}</p>` : ''}</article>`;
    }).join('') : '<div class="planner-empty">Add your first subject to bring classes, study time, and test dates together.</div>';
    fillSubjectSelect(get('taskSubject'));
    fillSubjectSelect(get('studySubject'), false);
    window.dispatchEvent(new Event('aura:subjects-updated'));
  }
  function renderTasks() {
    const sorted = [...data.tasks].sort((a,b) => {
      if (a.done !== b.done) return a.done ? 1 : -1;
      return (a.deadline || '9999-12-31').localeCompare(b.deadline || '9999-12-31');
    });
    const open = sorted.filter(x => !x.done), overdue = open.filter(x => x.deadline && new Date(x.deadline) < new Date());
    get('taskSummary').textContent = `${open.length} open · ${overdue.length} overdue`;
    get('plannerTaskList').innerHTML = sorted.length ? sorted.map(task => {
      const remaining = task.deadline ? new Date(task.deadline).getTime() - Date.now() : Infinity;
      const urgency = remaining < 0 || remaining <= 48 * 3600_000 ? ' is-urgent' : remaining <= 7 * 86400_000 ? ' is-soon' : task.deadline ? ' is-later' : '';
      const alarmLabel = task.alarmAt && !task.alarmFired ? `Alarm ${new Date(task.alarmAt).toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}` : '';
      const repeatLabel = task.reminderHours ? `Every ${task.reminderHours}h` : '';
      return `<div class="planner-task${task.done ? ' is-done' : ''}${urgency}"><button class="task-check" type="button" data-toggle-task="${esc(task.id)}" aria-label="${task.done ? 'Reopen' : 'Complete'} ${esc(task.title)}">${task.done ? '✓' : ''}</button><div class="planner-task-copy"><b>${esc(task.title)}</b><small>${esc(subjectName(task.subjectId))}${task.kind === 'test' ? ' · Test' : task.kind === 'assignment' ? ' · Assignment' : ''}</small></div>${task.deadline ? `<span class="deadline-badge">${remaining < 0 ? 'Overdue' : `Due ${dateLabel(task.deadline)}`}</span>` : ''}${alarmLabel || repeatLabel ? `<span class="reminder-pill">${[alarmLabel, repeatLabel].filter(Boolean).join(' · ')}</span>` : ''}<button class="icon-button" type="button" data-remove-task="${esc(task.id)}" aria-label="Delete ${esc(task.title)}">×</button></div>`;
    }).join('') : '<div class="planner-empty">Add your first task above.</div>';
    const soon = open.filter(task => task.deadline && new Date(task.deadline).getTime() - Date.now() <= 48 * 3600_000);
    const panel = get('upcomingTasksSection');
    panel.classList.toggle('is-empty', soon.length === 0);
    get('upcomingTaskList').innerHTML = soon.slice(0, 6).map(task => {
      const delta = new Date(task.deadline).getTime() - Date.now();
      const due = delta < 0 ? 'Overdue' : delta < 86400_000 ? 'Due today' : `Due in ${Math.ceil(delta / 86400_000)}d`;
      return `<a class="upcoming-task is-urgent" href="#deadlines"><span>${esc(task.title)}<small>${esc(subjectName(task.subjectId))}</small></span><b>${due}</b></a>`;
    }).join('');
    get('reminderPermission').textContent = 'Reminder alarms sound while AURA is open; notifications can appear in the background when enabled.';
  }
  function renderStudy() {
    const days = Array.from({ length: 7 }, (_, i) => { const date = new Date(`${appDate()}T12:00:00`); date.setDate(date.getDate() - (6-i)); return localDate(date); });
    const start = days[0];
    const logs = data.studyLogs.filter(log => log.date >= start && log.date <= days[6]).sort((a,b) => b.date.localeCompare(a.date));
    const total = logs.reduce((sum, log) => sum + Number(log.minutes || 0), 0);
    const bySubject = data.subjects.map(subject => ({ id: subject.id, name: subject.name, minutes: logs.filter(x => x.subjectId === subject.id).reduce((sum, log) => sum + Number(log.minutes || 0), 0), sessions: logs.filter(x => x.subjectId === subject.id).length, color: subject.color })).filter(x => x.minutes).sort((a,b) => b.minutes-a.minutes);
    get('studyTotal').textContent = `${(total / 60).toFixed(1)}h`;
    get('studySessions').textContent = `${logs.length} sessions`;
    const dayTotals = days.map(date => data.studyLogs.filter(log => log.date === date).reduce((sum, log) => sum + Number(log.minutes || 0), 0));
    const maxDaily = Math.max(60, ...dayTotals);
    const chart = `<div class="chart-heading"><b>Study time by day</b><span>Last 7 days · stacked by subject</span></div><div class="study-week-chart" role="img" aria-label="Weekly study time by day and subject">${days.map((date, dayIndex) => { const totalDay = dayTotals[dayIndex]; const segments = data.subjects.map(subject => ({ ...subject, minutes: data.studyLogs.filter(log => log.date === date && log.subjectId === subject.id).reduce((sum, log) => sum + Number(log.minutes || 0), 0) })).filter(item => item.minutes); const height = totalDay ? Math.max(5, totalDay/maxDaily*100) : 2; return `<div class="study-chart-column" title="${dateLabel(date)} · ${totalDay ? `${totalDay} minutes` : 'No study logged'}"><div class="study-chart-value">${totalDay ? `${Math.round(totalDay/60*10)/10}h` : ''}</div><div class="study-chart-track"><div class="study-chart-stack" style="height:${height}%">${segments.map(item => `<i style="height:${item.minutes/totalDay*100}%;background:${esc(item.color)}"></i>`).join('')}</div></div><small>${new Date(`${date}T12:00:00`).toLocaleDateString(undefined,{weekday:'short'})}</small></div>`; }).join('')}</div>${bySubject.length ? `<div class="chart-legend">${bySubject.map(item => `<span><i style="background:${esc(item.color)}"></i>${esc(item.name)} <b>${(item.minutes/60).toFixed(1)}h</b></span>`).join('')}</div>` : '<p class="chart-empty">Log a study session to see your weekly subject breakdown.</p>'}`;
    get('studyWeeklyChart').innerHTML = chart;
    get('studyTable').innerHTML = bySubject.length ? `<table class="summary-table"><thead><tr><th>Subject</th><th>Study time</th><th>Sessions</th></tr></thead><tbody>${bySubject.map(item => `<tr><td><span class="table-dot" style="background:${esc(item.color)}"></span>${esc(item.name)}</td><td>${(item.minutes / 60).toFixed(1)}h</td><td>${item.sessions}</td></tr>`).join('')}</tbody></table>` : '';
    get('studyLogRows').innerHTML = logs.slice(0,8).map(log => `<div class="compact-row"><span>${esc(subjectName(log.subjectId))}${log.topic ? ` · ${esc(log.topic)}` : ''}</span><b>${log.minutes} min · ${dateLabel(log.date)}</b></div>`).join('');
  }
  function renderExpenses() {
    const month = localDate().slice(0,7), rows = data.expenses.filter(x => x.date.startsWith(month)).sort((a,b) => b.date.localeCompare(a.date));
    const total = rows.reduce((sum, item) => sum + Number(item.amount || 0), 0);
    get('expenseTotal').textContent = money(total);
    const categories = [...new Set(rows.map(x => x.category || 'Other'))].map(category => ({ category, amount: rows.filter(x => (x.category || 'Other') === category).reduce((sum, item) => sum + Number(item.amount || 0), 0) })).sort((a,b) => b.amount-a.amount);
    const chartColors = ['#5b6fc4','#4b9a87','#d0924e','#9a77bd','#cf6b70','#5894b8','#86935d'];
    get('expenseChart').innerHTML = categories.length ? `<div class="chart-heading"><b>Spending by category</b><span>This month</span></div><div class="expense-bars">${categories.map((item,index) => `<div class="expense-bar-row"><span>${esc(item.category)}</span><div><i style="width:${Math.max(3,item.amount/total*100)}%;background:${chartColors[index%chartColors.length]}"></i></div><b>${money(item.amount)}</b></div>`).join('')}</div>` : '<p class="chart-empty">Add an expense to see your monthly breakdown.</p>';
    get('expenseTable').innerHTML = categories.length ? `<table class="summary-table"><thead><tr><th>Category</th><th>This month</th><th>Share</th></tr></thead><tbody>${categories.map(item => `<tr><td>${esc(item.category)}</td><td>${money(item.amount)}</td><td>${total ? Math.round(item.amount/total*100) : 0}%</td></tr>`).join('')}</tbody></table>` : '<div class="planner-empty">Add an expense to see where your money goes this month.</div>';
    get('expenseRows').innerHTML = rows.slice(0,8).map(item => `<div class="compact-row"><span>${esc(item.category || 'Other')} · ${esc(item.note || 'Expense')} · ${dateLabel(item.date)}</span><b>${money(item.amount)} <button type="button" class="icon-button" data-remove-expense="${esc(item.id)}" aria-label="Remove expense">×</button></b></div>`).join('');
  }
  function renderLifestyle() {
    const today = appDate(), lastSeven = Array.from({ length: 7 }, (_, i) => { const date = new Date(`${today}T12:00:00`); date.setDate(date.getDate() - (6-i)); return localDate(date); });
    const rows = lastSeven.map(date => data.lifestyle.find(item => item.date === date)).filter(Boolean);
    const avg = key => rows.length ? (rows.reduce((sum, x) => sum + Number(x[key] || 0), 0) / rows.length) : 0;
    get('lifeSleep').textContent = `${avg('sleep').toFixed(1)}h`;
    get('lifeWater').textContent = `${avg('water').toFixed(1)}`;
    get('lifeMovement').textContent = `${Math.round(avg('movement'))}m`;
    get('lifeDays').textContent = `${rows.length}/7 days logged`;
    const maxSleep = Math.max(8, ...lastSeven.map(date => Number(data.lifestyle.find(item => item.date === date)?.sleep || 0)));
    const maxMove = Math.max(60, ...lastSeven.map(date => Number(data.lifestyle.find(item => item.date === date)?.movement || 0)));
    get('lifestyleChart').innerHTML = `<div class="chart-heading"><b>Daily wellbeing</b><span>Last 7 days</span></div><div class="life-week-chart" role="img" aria-label="Sleep and movement logged over the last seven days">${lastSeven.map(date => { const item = data.lifestyle.find(row => row.date === date); const sleep = Number(item?.sleep || 0), movement = Number(item?.movement || 0); return `<div class="life-chart-day" title="${dateLabel(date)} · ${sleep}h sleep · ${movement}m movement"><div class="life-bars"><i class="sleep-bar" style="height:${sleep?Math.max(4,sleep/maxSleep*100):2}%"></i><i class="move-bar" style="height:${movement?Math.max(4,movement/maxMove*100):2}%"></i></div><small>${new Date(`${date}T12:00:00`).toLocaleDateString(undefined,{weekday:'narrow'})}</small></div>`; }).join('')}</div><div class="chart-legend"><span><i class="sleep-key"></i>Sleep</span><span><i class="move-key"></i>Movement</span></div>`;
    get('lifestyleTable').innerHTML = rows.length ? `<table class="summary-table"><thead><tr><th>Day</th><th>Sleep</th><th>Water</th><th>Movement</th><th>Calories</th><th>Steps</th><th>Screen time</th><th>Mood</th></tr></thead><tbody>${rows.slice().reverse().map(item => `<tr><td>${dateLabel(item.date)}</td><td>${Number(item.sleep).toFixed(1)}h</td><td>${item.water}</td><td>${item.movement}m</td><td>${Number(item.calories || 0).toLocaleString()}</td><td>${Number(item.steps || 0).toLocaleString()}</td><td>${Number(item.screenTime || 0)}m</td><td>${['','Low','Okay','Good','Great','Excellent'][item.mood] || '—'}</td></tr>`).join('')}</tbody></table>` : '<div class="planner-empty">A short daily check-in helps you notice patterns over time.</div>';
    get('lifeDate').value = today;
  }
  function renderSettings() {
    get('currency').value = data.settings.currency || 'INR';
    get('notificationCopyInput').value = data.settings.notificationCopy || '';
  }
  function renderAll() { renderSchedule(); renderSubjects(); renderTasks(); renderStudy(); renderExpenses(); renderLifestyle(); renderSettings(); }
  function updateSelects() { ['scheduleSubject', 'taskSubject', 'studySubject'].forEach(id => { if (get(id)) fillSubjectSelect(get(id), id !== 'studySubject'); }); }
  function focusActive() {
    if (window.AURA_FOCUS_ACTIVE) return true;
    try { const focus = JSON.parse(localStorage.getItem('aura_focus_studio_v1') || '{}'); return Boolean(focus.running); } catch { return false; }
  }
  function unlockReminderAudio() {
    if (reminderAudioUnlocked) return;
    const AudioContextType = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextType) return;
    try {
      reminderAudio ||= new AudioContextType();
      if (reminderAudio.state === 'suspended') reminderAudio.resume();
      reminderAudioUnlocked = true;
    } catch { /* Browser audio may be unavailable. */ }
  }
  function playReminderAlarm() {
    if (!reminderAudioUnlocked || !reminderAudio || reminderAudio.state !== 'running' || focusActive()) return;
    const start = reminderAudio.currentTime + 0.03;
    // A short two-tone alarm that can be heard without leaving a sound playing.
    for (let i = 0; i < 3; i++) {
      for (const [offset, frequency] of [[0, 880], [0.28, 660]]) {
        const oscillator = reminderAudio.createOscillator();
        const gain = reminderAudio.createGain();
        const at = start + i * 0.78 + offset;
        oscillator.type = 'sine'; oscillator.frequency.value = frequency;
        gain.gain.setValueAtTime(0.0001, at);
        gain.gain.exponentialRampToValueAtTime(0.12, at + 0.025);
        gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.2);
        oscillator.connect(gain); gain.connect(reminderAudio.destination);
        oscillator.start(at); oscillator.stop(at + 0.22);
      }
    }
  }
  // Browsers require a user gesture before scheduled audio is allowed.
  window.addEventListener('pointerdown', unlockReminderAudio, { once: true, passive: true });
  window.addEventListener('keydown', unlockReminderAudio, { once: true });
  function notify(title, body) {
    if (focusActive()) return;
    playReminderAlarm();
    if (!('Notification' in window) || Notification.permission !== 'granted') { toast(body); return; }
    const options = { body, icon: '/aura-icon-192.png', tag: `aura-${title.slice(0,40)}`, renotify: true, vibrate: [450, 180, 450, 180, 800] };
    navigator.serviceWorker?.getRegistration().then(registration => registration?.showNotification ? registration.showNotification(title, options) : new Notification(title, options)).catch(() => new Notification(title, options));
  }
  function reminderText(task) {
    const custom = String(data.settings.notificationCopy || '').trim();
    const defaultLines = ['AURA down? Keep your momentum up.', 'You don’t need AURA — AURA needs you.'];
    const line = custom || defaultLines[Math.floor(Math.random() * defaultLines.length)];
    return `${line} ${task.title}`;
  }
  async function checkReminders() {
    if (focusActive()) return;
    const now = Date.now(); let changed = false;
    for (const task of data.tasks) {
      if (task.done) continue;
      const alarmDue = task.alarmAt && !task.alarmFired && new Date(task.alarmAt).getTime() <= now;
      const repeatDue = task.reminderHours && task.nextReminderAt && task.nextReminderAt <= now;
      if (!alarmDue && !repeatDue) continue;
      notify('AURA · a little nudge', reminderText(task));
      if (alarmDue) task.alarmFired = true;
      if (repeatDue) task.nextReminderAt = now + task.reminderHours * 3600_000;
      changed = true;
    }
    if (changed) persist();
    const currentDay = appDate();
    const dates = Array.from({ length: 3 }, (_, i) => { const date = new Date(`${currentDay}T12:00:00`); date.setDate(date.getDate() - i); return localDate(date); });
    for (const date of dates) {
      for (const activity of plannedOn(date).sort((a,b) => a.end.localeCompare(b.end))) {
        const checkId = `${activity.id}:${date}`;
        if (data.completionChecks.some(item => item.id === checkId)) continue;
        const end = activityDateTime(date, activity.end);
        if (end.getTime() > now) continue;
        completionQueue.push({ activity, checkId, date });
      }
    }
    if (completionQueue.length) showNextCompletion();
  }
  function showNextCompletion() {
    const dialog = get('completionDialog');
    if (!completionQueue.length || dialog.open) return;
    const item = completionQueue.shift();
    if (data.completionChecks.some(check => check.id === item.checkId)) return showNextCompletion();
    get('completionTitle').textContent = item.activity.title;
    get('completionDetails').textContent = `${subjectName(item.activity.subjectId)} · ${dateLabel(item.date)} · ended at ${item.activity.end}`;
    dialog.dataset.checkId = item.checkId;
    dialog.dataset.activityId = item.activity.id;
    dialog.dataset.date = item.date;
    dialog.dataset.title = item.activity.title;
    dialog.dataset.kind = item.activity.kind || '';
    dialog.dataset.subjectId = item.activity.subjectId || '';
    dialog.showModal();
  }
  function answerCompletion(done) {
    const dialog = get('completionDialog');
    const checkId = dialog.dataset.checkId;
    if (!checkId) return;
    const check = {
      id: checkId, done, checkedAt: Date.now(), date: dialog.dataset.date,
      activityId: dialog.dataset.activityId, title: dialog.dataset.title,
      kind: dialog.dataset.kind, subjectId: dialog.dataset.subjectId || '', pointsSynced: false
    };
    data.completionChecks.push(check);
    persist(renderSchedule); dialog.close(); showNextCompletion();
  }
  async function syncClassPoints(check) {
    if (!check?.done || check.kind !== 'Class' || !activeUser || !navigator.onLine) return;
    try {
      const result = await api('/api/planner', { action: 'class-attendance', activityId: check.activityId, date: check.date, title: check.title, subjectId: check.subjectId });
      check.pointsSynced = true; check.points = Number(result.points || 0); saveLocal();
      window.dispatchEvent(new CustomEvent('aura:points-updated', { detail: result }));
      if (result.points) toast(`Class attended · +${result.points} AP earned`);
    } catch (error) {
      if (error.status === 409) { check.pointsSynced = true; check.points = 0; saveLocal(); toast(error.message); }
    }
  }
  get('scheduleForm').addEventListener('submit', event => {
    event.preventDefault(); const title = get('scheduleTitle').value.trim();
    const days = [...get('scheduleDays').querySelectorAll('input:checked')].map(input => Number(input.value));
    const existing = data.schedule.find(item => item.id === editingScheduleId);
    if (!title || (!days.length && !(existing && existing.date))) return toast('Choose an activity and at least one day.');
    const start = get('scheduleStart').value, end = get('scheduleEnd').value;
    if (end <= start) return toast('The end time must come after the start time.');
    const values = { title, subjectId: get('scheduleSubject').value, days: existing?.date ? existing.days : days, start, end, kind: get('scheduleKind').value };
    if (existing) Object.assign(existing, values);
    else data.schedule.push({ id: id(), ...values });
    const wasEditing = Boolean(existing); editingScheduleId = null;
    event.currentTarget.reset(); get('scheduleSubmit').textContent = 'Add to timetable'; get('scheduleCancelEdit').hidden = true;
    persist(renderSchedule); checkReminders(); toast(wasEditing ? 'Routine updated' : 'Added to your weekly timetable');
  });
  function editScheduleEntry(event) {
    const button = event.target.closest('[data-edit-schedule]'); if (!button) return;
    const item = data.schedule.find(entry => entry.id === button.dataset.editSchedule); if (!item) return;
    editingScheduleId = item.id; get('scheduleTitle').value = item.title; get('scheduleSubject').value = item.subjectId || '';
    get('scheduleKind').value = item.kind || 'Class'; get('scheduleStart').value = item.start; get('scheduleEnd').value = item.end;
    get('scheduleDays').querySelectorAll('input').forEach(input => { input.checked = item.days.includes(Number(input.value)); });
    get('scheduleSubmit').textContent = 'Save routine'; get('scheduleCancelEdit').hidden = false; get('scheduleForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
  get('scheduleCancelEdit').addEventListener('click', () => { editingScheduleId = null; get('scheduleForm').reset(); get('scheduleSubmit').textContent = 'Add to timetable'; get('scheduleCancelEdit').hidden = true; });
  function removeScheduleEntry(event) {
    const button = event.target.closest('[data-remove-schedule]');
    if (!button) return;
    const item = data.schedule.find(entry => entry.id === button.dataset.removeSchedule);
    confirmDelete(item?.title || 'this timetable activity', () => {
      data.schedule = data.schedule.filter(entry => entry.id !== button.dataset.removeSchedule);
      persist(renderSchedule);
    });
  }
  get('scheduleWeek').addEventListener('click', event => {
    const day = event.target.closest('[data-select-date]');
    if (day) { scheduleSelectedDate = day.dataset.selectDate; renderSchedule(); return; }
    editScheduleEntry(event);
    removeScheduleEntry(event);
  });
  get('scheduleToday').addEventListener('click', event => { editScheduleEntry(event); removeScheduleEntry(event); });
  get('scheduleDateSelect').addEventListener('change', event => { scheduleSelectedDate = event.target.value || appDate(); renderSchedule(); });
  const moveScheduleDate = offset => { const date = new Date(`${scheduleSelectedDate || appDate()}T12:00:00`); date.setDate(date.getDate() + offset); scheduleSelectedDate = localDate(date); renderSchedule(); };
  get('schedulePrevDate').addEventListener('click', () => moveScheduleDate(-1));
  get('scheduleNextDate').addEventListener('click', () => moveScheduleDate(1));
  get('scheduleTodayDate').addEventListener('click', () => { scheduleSelectedDate = appDate(); renderSchedule(); });
  get('subjectForm').addEventListener('submit', event => {
    event.preventDefault(); const name = get('subjectName').value.trim(); if (!name) return;
    const subject = data.subjects.find(item => item.id === editingSubjectId);
    const values = { name, color: get('subjectColor').value, teacher: get('subjectTeacher').value.trim(), notes: get('subjectNotes').value.trim() };
    if (subject) Object.assign(subject, values); else data.subjects.push({ id: id(), ...values });
    const wasEditing = Boolean(subject); editingSubjectId = null;
    event.currentTarget.reset(); get('subjectColor').value = '#8b7cf6'; get('subjectFormTitle').textContent = 'Add a subject'; get('subjectSubmit').textContent = 'Add subject'; get('subjectCancelEdit').hidden = true;
    persist(); toast(wasEditing ? `${name} updated` : `${name} added to your subjects`);
  });
  get('subjectList').addEventListener('click', event => {
    const editButton = event.target.closest('[data-edit-subject]');
    if (editButton) {
      const subject = data.subjects.find(item => item.id === editButton.dataset.editSubject); if (!subject) return;
      editingSubjectId = subject.id; get('subjectName').value = subject.name; get('subjectTeacher').value = subject.teacher || '';
      get('subjectNotes').value = subject.notes || ''; get('subjectColor').value = subject.color || '#8b7cf6';
      get('subjectFormTitle').textContent = `Edit ${subject.name}`; get('subjectSubmit').textContent = 'Save subject'; get('subjectCancelEdit').hidden = false;
      get('subjectForm').scrollIntoView({ behavior: 'smooth', block: 'center' }); return;
    }
    const button = event.target.closest('[data-remove-subject]'); if (!button) return;
    const subject = data.subjects.find(item => item.id === button.dataset.removeSubject);
    confirmDelete(subject?.name || 'this subject', () => {
      data.subjects = data.subjects.filter(item => item.id !== button.dataset.removeSubject);
      persist();
    });
  });
  get('subjectCancelEdit').addEventListener('click', () => { editingSubjectId = null; get('subjectForm').reset(); get('subjectColor').value = '#8b7cf6'; get('subjectFormTitle').textContent = 'Add a subject'; get('subjectSubmit').textContent = 'Add subject'; get('subjectCancelEdit').hidden = true; });
  get('taskReminder').addEventListener('change', () => { const custom = get('taskReminder').value === 'custom'; get('taskReminderCustom').hidden = !custom; if (custom && !get('taskReminderCustom').value) get('taskReminderCustom').value = '2'; });
  get('taskForm').addEventListener('submit', event => {
    event.preventDefault(); const title = get('taskTitle').value.trim(); if (!title) return;
    const alarmAt = get('taskAlarmAt').value;
    if (alarmAt && new Date(alarmAt).getTime() <= Date.now()) return toast('Choose an alarm time in the future.');
    const hours = get('taskReminder').value === 'custom' ? Number(get('taskReminderCustom').value) : Number(get('taskReminder').value);
    if (!Number.isInteger(hours) || hours < 0 || hours > 168 || (get('taskReminder').value === 'custom' && hours < 1)) return toast('Choose a reminder interval from 1 to 168 hours.');
    data.tasks.push({ id: id(), title, subjectId: get('taskSubject').value, kind: get('taskKind').value, deadline: get('taskDeadline').value, alarmAt: alarmAt ? new Date(alarmAt).toISOString() : null, alarmFired: false, done: false, reminderHours: hours, nextReminderAt: hours ? Date.now() + hours * 3600_000 : null, createdAt: Date.now() });
    event.currentTarget.reset(); get('taskReminderCustom').hidden = true; persist(() => { renderTasks(); renderSubjects(); }); toast(alarmAt ? 'Task saved · alarm scheduled' : hours ? 'Task saved · repeating reminder scheduled' : 'Task saved to your deadlines');
  });
  get('plannerTaskList').addEventListener('click', event => {
    const toggle = event.target.closest('[data-toggle-task]');
    if (toggle) { const task = data.tasks.find(x => x.id === toggle.dataset.toggleTask); if (task) { task.done = !task.done; persist(() => { renderTasks(); renderSubjects(); }); toast(task.done ? 'Task completed · nice work' : 'Task reopened'); } return; }
    const remove = event.target.closest('[data-remove-task]');
    if (remove) {
      const task = data.tasks.find(item => item.id === remove.dataset.removeTask);
      confirmDelete(task?.title || 'this task', () => {
        data.tasks = data.tasks.filter(item => item.id !== remove.dataset.removeTask);
        persist(() => { renderTasks(); renderSubjects(); });
      });
    }
  });
  get('studyForm').addEventListener('submit', event => {
    event.preventDefault(); const subjectId = get('studySubject').value, minutes = Number(get('studyMinutes').value);
    if (!subjectId || !minutes) return toast('Choose a subject and enter study time.');
    data.studyLogs.push({ id: id(), subjectId, date: get('studyDate').value || appDate(), minutes, topic: get('studyTopic').value.trim() });
    get('studyTopic').value = ''; get('studyMinutes').value = ''; persist(() => { renderStudy(); renderSubjects(); }); toast('Study session logged');
  });
  get('expenseForm').addEventListener('submit', event => {
    event.preventDefault(); const amount = Number(get('expenseAmount').value); if (!amount || amount < 0) return;
    data.expenses.push({ id: id(), date: get('expenseDate').value || appDate(), amount, category: get('expenseCategory').value, note: get('expenseNote').value.trim() });
    get('expenseAmount').value = ''; get('expenseNote').value = ''; persist(renderExpenses); toast('Expense added');
  });
  get('expenseRows').addEventListener('click', event => {
    const button = event.target.closest('[data-remove-expense]'); if (!button) return;
    const expense = data.expenses.find(item => item.id === button.dataset.removeExpense);
    const label = [expense?.category, expense?.note].filter(Boolean).join(' · ') || 'this expense';
    confirmDelete(label, () => {
      data.expenses = data.expenses.filter(item => item.id !== button.dataset.removeExpense);
      persist(renderExpenses);
    });
  });
  get('lifestyleForm').addEventListener('submit', event => {
    event.preventDefault(); const row = { date: get('lifeDate').value || appDate(), sleep: Number(get('lifeSleepInput').value) || 0, water: Number(get('lifeWaterInput').value) || 0, movement: Number(get('lifeMovementInput').value) || 0, calories: Number(get('lifeCaloriesInput').value) || 0, steps: Number(get('lifeStepsInput').value) || 0, screenTime: Number(get('lifeScreenTimeInput').value) || 0, mood: Number(get('lifeMood').value) || 3 };
    data.lifestyle = data.lifestyle.filter(item => item.date !== row.date); data.lifestyle.push(row); persist(renderLifestyle); toast('Daily check-in saved');
  });
  get('currency').addEventListener('change', () => { data.settings.currency = get('currency').value; persist(renderExpenses); });
  get('notificationCopyInput').addEventListener('change', () => { data.settings.notificationCopy = get('notificationCopyInput').value.trim().slice(0, 120); persist(renderSettings); });
  get('enableNotifications').addEventListener('click', async () => {
    unlockReminderAudio();
    if (!('Notification' in window)) return toast('This browser does not support notifications.');
    const permission = await Notification.requestPermission();
    toast(permission === 'granted' ? 'AURA reminders are enabled' : 'Notifications stayed off');
  });
  get('completionYes').addEventListener('click', () => answerCompletion(true));
  get('completionNo').addEventListener('click', () => answerCompletion(false));
  window.addEventListener('focus', checkReminders);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkReminders(); });
  reminderTimer = setInterval(checkReminders, 10_000);
  renderAll(); get('studyDate').value = appDate(); get('expenseDate').value = appDate();
  async function setAccount(account, options = {}) {
    const nextUser = account?.user?.id || null;
    if (options.signedOut) { localStorage.removeItem(LAST_USER_KEY); activeUser = null; }
    else if (!nextUser && activeUser && !navigator.onLine) {
      get('plannerSync').textContent = 'Saved offline · will sync when online'; renderAll(); return;
    } else if (!nextUser && navigator.onLine) { localStorage.removeItem(LAST_USER_KEY); activeUser = null; }
    if (nextUser) { activeUser = nextUser; localStorage.setItem(LAST_USER_KEY, activeUser); }
    personalization = null;
    if (!activeUser) {
      try { data = { ...defaultData(), ...JSON.parse(localStorage.getItem(STORE_KEY) || '{}') }; } catch { data = defaultData(); }
      get('plannerSync').textContent = 'Saved on this device · sign in to sync'; renderAll(); return;
    }
    const userKey = userStoreKey(activeUser), isDirty = Boolean(localStorage.getItem(pendingKey(activeUser)));
    let local = null; try { local = JSON.parse(localStorage.getItem(userKey) || 'null'); } catch {}
    if (local) data = { ...defaultData(), ...local };
    if (isDirty) {
      get('plannerSync').textContent = 'Saved offline · syncing your changes'; renderAll();
      if (navigator.onLine) await flushPendingSync();
      checkReminders(); return;
    }
    if (!navigator.onLine) {
      get('plannerSync').textContent = 'Saved offline · will sync when online'; renderAll(); return;
    }
    try {
      const result = await api('/api/planner'); personalization = result.personalization || null;
      data = result.data ? { ...defaultData(), ...result.data } : (local || data);
      if (!result.data) { localStorage.setItem(pendingKey(activeUser), String(Date.now())); await flushPendingSync(); }
      if (personalization?.copy && !data.settings.notificationCopy) data.settings.notificationCopy = personalization.copy;
      get('plannerSync').textContent = 'Synced to your account';
    } catch {
      data = local || data; get('plannerSync').textContent = 'Saved offline · will sync when online';
    }
    data.settings = { ...defaultData().settings, ...(data.settings || {}) }; saveLocal(); renderAll(); checkReminders();
  }
  function setPersonalization(value) { personalization = value || null; }
  function getScheduleTemplate(date) { return plannedOn(date).map(({ id: oldId, ...item }) => ({ ...item, id: id() })); }
  function getSubjects() { return data.subjects.map(({ id, name, color }) => ({ id, name, color })); }
  function addOneOffActivities(date, activities, replace = false) {
    if (replace) { data.schedule = data.schedule.filter(item => item.date !== date); if (!data.scheduleOverrides.includes(date)) data.scheduleOverrides.push(date); }
    for (const activity of activities) {
      const title = String(activity.title || '').trim().slice(0, 100);
      if (!title || !/^([01]\d|2[0-3]):[0-5]\d$/.test(activity.start) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(activity.end) || activity.end <= activity.start) continue;
      data.schedule.push({ id: id(), date, overrideWeekday: Boolean(replace), title, subjectId: activity.subjectId || '', days: [], start: activity.start, end: activity.end, kind: activity.kind || 'Personal' });
    }
    persist();
    return true;
  }
  function addTask({ title, deadline = '', subjectId = '' }) {
    const cleanTitle = String(title || '').trim().slice(0, 120);
    if (!cleanTitle) return false;
    data.tasks.push({ id: id(), title: cleanTitle, deadline, subjectId, kind: 'task', reminderHours: 0, done: false, createdAt: Date.now() });
    persist();
    return true;
  }
  function logFocusStudyBlock(sessionId, segment, subjectId) {
    if (!data.subjects.some(subject => subject.id === subjectId)) return false;
    const logId = `focus_${sessionId}_${segment}`;
    if (!data.studyLogs.some(log => log.id === logId)) {
      data.studyLogs.push({ id: logId, subjectId, date: appDate(), minutes: 2, topic: 'Focus session' });
      persist(() => { renderStudy(); renderSubjects(); });
    }
    return true;
  }
  renderAll(); checkReminders();
  return { setAccount, setPersonalization, renderAll, addTask, getScheduleTemplate, getSubjects, logFocusStudyBlock, addOneOffActivities };
}

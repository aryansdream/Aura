import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';

const cleanString = (value, max) => String(value ?? '').trim().slice(0, max);
function normalize(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
  const allowed = ['subjects', 'schedule', 'scheduleOverrides', 'tasks', 'studyLogs', 'expenses', 'lifestyle', 'settings', 'completionChecks'];
  const result = {};
  for (const key of allowed) result[key] = Array.isArray(data[key]) ? data[key].slice(-500) : (key === 'settings' && data[key] && typeof data[key] === 'object' ? data[key] : []);
  result.scheduleOverrides = Array.isArray(data.scheduleOverrides) ? data.scheduleOverrides.filter(x => /^\d{4}-\d{2}-\d{2}$/.test(x)).slice(-60) : [];
  result.subjects = result.subjects.map(x => ({ id: cleanString(x.id, 48), name: cleanString(x.name, 80), color: /^#[0-9a-f]{6}$/i.test(x.color) ? x.color : '#8b7cf6', teacher: cleanString(x.teacher, 80), notes: cleanString(x.notes, 500) })).filter(x => x.id && x.name);
  result.schedule = result.schedule.map(x => ({ id: cleanString(x.id, 48), title: cleanString(x.title, 100), subjectId: cleanString(x.subjectId, 48), days: Array.isArray(x.days) ? x.days.map(Number).filter(d => Number.isInteger(d) && d >= 0 && d <= 6) : [], date: /^\d{4}-\d{2}-\d{2}$/.test(x.date) ? x.date : '', overrideWeekday: Boolean(x.overrideWeekday), start: /^([01]\d|2[0-3]):[0-5]\d$/.test(x.start) ? x.start : '', end: /^([01]\d|2[0-3]):[0-5]\d$/.test(x.end) ? x.end : '', kind: cleanString(x.kind, 32) })).filter(x => x.id && x.title && x.start && x.end && ((x.date && !Number.isNaN(Date.parse(x.date))) || x.days.length));
  result.tasks = result.tasks.map(x => ({ id: cleanString(x.id, 48), title: cleanString(x.title, 120), subjectId: cleanString(x.subjectId, 48), deadline: cleanString(x.deadline, 32), alarmAt: x.alarmAt && !Number.isNaN(Date.parse(x.alarmAt)) ? new Date(x.alarmAt).toISOString() : null, alarmFired: Boolean(x.alarmFired), kind: ['task','assignment','test'].includes(x.kind) ? x.kind : 'task', done: Boolean(x.done), reminderType: ['notification','alarm'].includes(x.reminderType) ? x.reminderType : 'none', reminderHours: 0, nextReminderAt: null, createdAt: Number(x.createdAt) || Date.now() })).filter(x => x.id && x.title);
  result.studyLogs = result.studyLogs.map(x => ({ id: cleanString(x.id, 48), subjectId: cleanString(x.subjectId, 48), date: cleanString(x.date, 10), minutes: Math.max(0, Math.min(1440, Number(x.minutes) || 0)), topic: cleanString(x.topic, 120), source: x.source === 'focus' ? 'focus' : 'manual' })).filter(x => x.id && x.subjectId && x.date && x.minutes);
  result.expenses = result.expenses.map(x => ({ id: cleanString(x.id, 48), date: cleanString(x.date, 10), amount: Math.max(0, Math.min(100000000, Number(x.amount) || 0)), category: cleanString(x.category, 40), note: cleanString(x.note, 120) })).filter(x => x.id && x.date && x.amount);
  result.lifestyle = result.lifestyle.map(x => ({ date: cleanString(x.date, 10), sleep: Math.max(0, Math.min(24, Number(x.sleep) || 0)), water: Math.max(0, Math.min(40, Number(x.water) || 0)), movement: Math.max(0, Math.min(1440, Number(x.movement) || 0)), mood: Math.max(1, Math.min(5, Number(x.mood) || 3)), calories: Math.max(0, Math.min(20000, Math.floor(Number(x.calories) || 0))), steps: Math.max(0, Math.min(200000, Math.floor(Number(x.steps) || 0))), screenTime: Math.max(0, Math.min(1440, Number(x.screenTime) || 0)) })).filter(x => x.date);
  result.completionChecks = result.completionChecks.map(x => ({ id: cleanString(x.id, 100), done: Boolean(x.done), checkedAt: Number(x.checkedAt) || Date.now(), activityId: cleanString(x.activityId, 48), date: /^\d{4}-\d{2}-\d{2}$/.test(x.date) ? x.date : '', title: cleanString(x.title, 100), kind: ['Class','Study','Personal'].includes(x.kind) ? x.kind : '', subjectId: cleanString(x.subjectId, 48), pointsSynced: Boolean(x.pointsSynced), points: Math.max(0, Math.min(10, Number(x.points) || 0)) })).filter(x => x.id);
  return result;
}

export default async function handler(req, res) {
  if (!['GET', 'POST'].includes(req.method)) return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to sync your timetable and study data.' });
    const sql = db(); await ensureSchema(sql);
    if (req.method === 'POST' && req.body?.action === 'devices-list') {
      const devices = await sql`SELECT device_id, device_name AS name, battery_percent, charging, battery_supported, last_seen
        FROM aura_devices WHERE user_id=${user.id} ORDER BY last_seen DESC LIMIT 30`;
      return send(res, 200, { devices });
    }
    if (req.method === 'POST' && req.body?.action === 'device-heartbeat') {
      const body = req.body;
      const deviceId = String(body.deviceId || '').slice(0, 64);
      const name = String(body.name || 'Device').replace(/[^a-zA-Z0-9 ._-]/g, '').trim().slice(0, 48) || 'Device';
      const battery = body.batteryPercent === null ? null : Number(body.batteryPercent);
      const charging = typeof body.charging === 'boolean' ? body.charging : null;
      if (!/^[a-zA-Z0-9_-]{8,64}$/.test(deviceId) || !(battery === null || (Number.isInteger(battery) && battery >= 0 && battery <= 100))) {
        return send(res, 400, { error: 'Device details are invalid.' });
      }
      await sql`INSERT INTO aura_devices(user_id, device_id, device_name, battery_percent, charging, battery_supported, last_seen)
        VALUES(${user.id}, ${deviceId}, ${name}, ${battery}, ${charging}, ${Boolean(body.batterySupported)}, now())
        ON CONFLICT(user_id, device_id) DO UPDATE SET device_name=EXCLUDED.device_name,
          battery_percent=EXCLUDED.battery_percent, charging=EXCLUDED.charging,
          battery_supported=EXCLUDED.battery_supported, last_seen=now()`;
      return send(res, 200, { saved: true });
    }
    if (req.method === 'POST' && req.body?.action === 'device-remove') {
      const deviceId = String(req.body.deviceId || '').slice(0, 64);
      if (!/^[a-zA-Z0-9_-]{8,64}$/.test(deviceId)) return send(res, 400, { error: 'Device details are invalid.' });
      await sql`DELETE FROM aura_devices WHERE user_id=${user.id} AND device_id=${deviceId}`;
      return send(res, 200, { removed: true });
    }
    if (req.method === 'POST' && req.body?.action === 'class-attendance') return send(res, 410, { error: 'Classes no longer award Aura Points. Only Focus timer sessions earn points.' });
    if (req.method === 'POST' && req.body?.action === 'focus-point') {
      const sessionId = cleanString(req.body.sessionId, 40), segment = Number(req.body.segment);
      const subjectId = cleanString(req.body.subjectId, 48), requestedDay = cleanString(req.body.date, 10);
      if (!/^[a-zA-Z0-9-]{8,40}$/.test(sessionId) || !Number.isInteger(segment) || segment < 1 || segment > 90 || !subjectId) return send(res, 400, { error: 'Focus point details are invalid.' });
      const today = appDay(req), day = /^\d{4}-\d{2}-\d{2}$/.test(requestedDay) ? requestedDay : today;
      const todayDate = new Date(`${today}T12:00:00Z`), focusDate = new Date(`${day}T12:00:00Z`);
      const daysOld = Math.floor((todayDate - focusDate) / 86_400_000);
      if (daysOld < 0 || daysOld > 3) return send(res, 400, { error: 'Focus points can only sync for today or the last three days.' });
      const [subject] = await sql`SELECT subject->>'name' AS name FROM aura_planner_data,
        LATERAL jsonb_array_elements(COALESCE(data->'subjects', '[]'::jsonb)) AS subjects(subject)
        WHERE user_id=${user.id} AND subject->>'id'=${subjectId} LIMIT 1`;
      if (!subject) return send(res, 400, { error: 'Choose a subject saved to your AURA account.' });
      const [daily] = await sql`SELECT COALESCE(SUM(points),0)::int AS total FROM aura_completions WHERE user_id=${user.id} AND day=${day} AND task_id LIKE 'focus_%'`;
      if (daily.total >= 200) return send(res, 409, { error: 'AURA caps daily points at 200 to keep the leaderboard fair.' });
      const taskId = `focus_${sessionId}_${segment}`;
      const inserted = await sql`INSERT INTO aura_completions(user_id, task_id, title, category, points, verified, day)
        VALUES(${user.id}, ${taskId}, ${`Focus study: ${subject.name}`}, 'Study', 1, true, ${day})
        ON CONFLICT(user_id, task_id, day) DO NOTHING RETURNING points`;
      if (inserted.length) {
        await sql`UPDATE aura_planner_data SET data=jsonb_set(
          data, '{studyLogs}', COALESCE(data->'studyLogs', '[]'::jsonb) || jsonb_build_array(
            jsonb_build_object('id', ${taskId}, 'subjectId', ${subjectId}, 'date', ${day}, 'minutes', 2, 'topic', 'Focus session', 'source', 'focus')
          ), true), updated_at=now()
          WHERE user_id=${user.id}`;
      }
      const [totals] = await sql`SELECT COALESCE(SUM(points) FILTER (WHERE verified),0)::int AS total,
        0::int AS pending FROM aura_completions WHERE user_id=${user.id} AND task_id LIKE 'focus_%'`;
      return send(res, 200, { points: Number(inserted?.[0]?.points || 0), total: totals.total, pending: totals.pending, alreadyRecorded: !inserted.length });
    }
    if (req.method === 'GET') {
      const [row] = await sql`SELECT data FROM aura_planner_data WHERE user_id=${user.id}`;
      const matches = String(process.env.AURA_PERSONALIZED_EMAIL || '').trim().toLowerCase() === String(user.email || '').trim().toLowerCase();
      const personalization = matches && process.env.AURA_PERSONALIZED_COPY ? { copy: cleanString(process.env.AURA_PERSONALIZED_COPY, 120) } : null;
      return send(res, 200, { data: row?.data || null, personalization });
    }
    const data = normalize(req.body?.data);
    if (!data) return send(res, 400, { error: 'Planner data must be an object.' });
    if (JSON.stringify(data).length > 180_000) return send(res, 413, { error: 'Planner data is too large to sync.' });
    await sql`INSERT INTO aura_planner_data(user_id, data, updated_at) VALUES(${user.id}, ${JSON.stringify(data)}::jsonb, now())
      ON CONFLICT(user_id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()`;
    return send(res, 200, { saved: true });
  } catch (error) { console.error('aura planner error', error); return send(res, 500, { error: 'Could not sync planner data.' }); }
}

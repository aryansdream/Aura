import catalog from '../lib/tasks.json' with { type: 'json' };
import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';
import { adjustedReward } from '../lib/progression.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to sync your missions.' });
    const taskId = String(req.body?.taskId || '');
    const sql = db(); await ensureSchema(sql);
    if (req.body?.action === 'remove') {
      if (!taskId) return send(res, 400, { error: 'Choose a mission to remove.' });
      const day = appDay(req);
      const removed = await sql`DELETE FROM aura_missions
        WHERE user_id=${user.id} AND task_id=${taskId} AND day=${day} RETURNING task_id`;
      await sql`DELETE FROM aura_recurring_missions WHERE user_id=${user.id} AND task_id=${taskId}`;
      return removed.length ? send(res, 200, { removed: true }) : send(res, 404, { error: 'That mission is no longer on today’s list.' });
    }
    let task = catalog.find(item => item.id === req.body?.taskId);
    if (!task && String(req.body?.taskId || '').startsWith('c_')) {
      const [custom] = await sql`SELECT task_id AS id, title, category, points FROM aura_custom_tasks WHERE user_id=${user.id} AND task_id=${req.body.taskId}`;
      task = custom;
      if (task) task.icon = '✳';
    }
    if (!task) return send(res, 400, { error: 'That mission is not in the AURA library.' });
    const day = appDay(req);
    const [closed] = await sql`SELECT 1 FROM aura_day_submissions WHERE user_id=${user.id} AND day=${day}`;
    if (closed) return send(res, 409, { error: 'Today’s tracker is submitted. New missions are closed for the day.' });
    const [score] = await sql`SELECT COALESCE(SUM(points) FILTER (WHERE verified),0)::int AS total FROM aura_completions WHERE user_id=${user.id}`;
    const selectedTask = { ...task, points: adjustedReward(task.points, score.total) };
    const added = await sql`INSERT INTO aura_missions(user_id, task_id, day, reward_points)
      VALUES(${user.id},${task.id},${day},${selectedTask.points}) ON CONFLICT(user_id,task_id,day) DO NOTHING RETURNING task_id`;
    if (!added.length) return send(res, 409, { error: 'That mission is already on today’s list or was completed today.' });
    await sql`INSERT INTO aura_recurring_missions(user_id, task_id)
      VALUES(${user.id},${task.id}) ON CONFLICT(user_id,task_id) DO NOTHING`;
    return send(res, 200, { task: selectedTask });
  } catch (error) { console.error('aura select error', error); return send(res, 500, { error: 'Could not add that mission.' }); }
}

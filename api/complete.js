import catalog from '../lib/tasks.json' with { type: 'json' };
import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';
import { adjustedReward } from '../lib/progression.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to earn synced Aura Points.' });
    const day = appDay(req);
    const sql = db(); await ensureSchema(sql);
    let task = catalog.find(item => item.id === req.body?.taskId);
    const customTask = !task && String(req.body?.taskId || '').startsWith('c_');
    if (customTask) {
      const [custom] = await sql`SELECT task_id AS id, title, category, points FROM aura_custom_tasks WHERE user_id=${user.id} AND task_id=${req.body.taskId}`;
      task = custom;
    }
    if (!task) return send(res, 400, { error: 'That mission is not in the AURA library.' });
    const [closed] = await sql`SELECT 1 FROM aura_day_submissions WHERE user_id=${user.id} AND day=${day}`;
    if (closed) return send(res, 409, { error: 'Today’s tracker is submitted. New completions are closed for the day.' });
    const selected = await sql`SELECT task_id, reward_points FROM aura_missions WHERE user_id=${user.id} AND task_id=${task.id} AND day=${day}`;
    if (!selected.length) return send(res, 409, { error: 'Add this mission to today’s promises first.' });
    let rewardPoints = Number(selected[0].reward_points);
    if (!rewardPoints) {
      const [score] = await sql`SELECT COALESCE(SUM(points) FILTER (WHERE verified),0)::int AS total FROM aura_completions WHERE user_id=${user.id}`;
      rewardPoints = adjustedReward(task.points, score.total);
      await sql`UPDATE aura_missions SET reward_points=${rewardPoints} WHERE user_id=${user.id} AND task_id=${task.id} AND day=${day}`;
    }
    const [dailyEarned] = await sql`SELECT COALESCE(SUM(points),0)::int AS total FROM aura_completions WHERE user_id=${user.id} AND day=${day}`;
    if (dailyEarned.total + rewardPoints > 200) return send(res, 409, { error: 'AURA caps daily points at 200 to keep the leaderboard fair.' });
    if (customTask) {
      const [customEarned] = await sql`SELECT COALESCE(SUM(points),0)::int AS total FROM aura_completions WHERE user_id=${user.id} AND day=${day} AND category='Your own'`;
      if (customEarned.total + rewardPoints > 30) return send(res, 409, { error: 'Custom missions are capped at 30 AP per day.' });
    }
    const rows = await sql`INSERT INTO aura_completions(user_id, task_id, title, category, points, day)
      VALUES(${user.id}, ${task.id}, ${task.title}, ${task.category}, ${rewardPoints}, ${day})
      ON CONFLICT(user_id, task_id, day) DO NOTHING RETURNING points`;
    if (!rows.length) return send(res, 409, { error: 'That mission was already completed today.' });
    await sql`UPDATE aura_missions SET status='done' WHERE user_id=${user.id} AND task_id=${task.id} AND day=${day}`;
    const [total] = await sql`SELECT COALESCE(SUM(points) FILTER (WHERE verified),0)::int AS total,
      COALESCE(SUM(points) FILTER (WHERE NOT verified),0)::int AS pending FROM aura_completions WHERE user_id=${user.id}`;
    return send(res, 200, { points: rewardPoints, total: total.total, pending: total.pending });
  } catch (error) { console.error('aura complete error', error); return send(res, 500, { error: 'Could not save that completion.' }); }
}

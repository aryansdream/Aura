import catalog from '../lib/tasks.json' with { type: 'json' };
import { adjustedReward } from '../lib/progression.js';
import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';

export default async function handler(req, res) {
  if (req.method !== 'GET') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to sync your progress.' });
    const day = appDay(req);
    const sql = db();
    await ensureSchema(sql);
    const [profile] = await sql`INSERT INTO aura_profiles(user_id, display_name)
      VALUES(${user.id}, ${String(user.name || user.email?.split('@')[0] || 'Aura Explorer').slice(0,24)})
      ON CONFLICT(user_id) DO UPDATE SET user_id=EXCLUDED.user_id RETURNING display_name, gender`;
    const [[totals], proofRows] = await Promise.all([
      sql`SELECT (COALESCE(SUM(points) FILTER (WHERE verified),0) +
        (SELECT COALESCE(SUM(balance_bonus),0) FROM aura_day_submissions WHERE user_id=${user.id}) +
        (SELECT COALESCE(SUM(points),0) FROM aura_combo_awards WHERE user_id=${user.id}))::int AS total,
        COALESCE(SUM(points) FILTER (WHERE NOT verified),0)::int AS pending, COUNT(*)::int AS completed
        FROM aura_completions WHERE user_id=${user.id}`,
      sql`SELECT status, size_bytes, balance_bonus, created_at FROM aura_day_submissions
        WHERE user_id=${user.id} AND day=${day}`
    ]);

    // Materialize every saved daily selection for today, unless today's tracker has
    // already closed the day. This creates a fresh completion/reward record per day.
    if (!proofRows.length) {
      const recurring = await sql`SELECT task_id FROM aura_recurring_missions
        WHERE user_id=${user.id} ORDER BY added_at, task_id`;
      const currentTotal = Number(totals.total || 0);
      for (const row of recurring) {
        let task = catalog.find(item => item.id === row.task_id);
        if (!task && String(row.task_id).startsWith('c_')) {
          const [custom] = await sql`SELECT task_id AS id, title, category, points
            FROM aura_custom_tasks WHERE user_id=${user.id} AND task_id=${row.task_id}`;
          task = custom;
        }
        if (!task) continue;
        const reward = adjustedReward(task.points, currentTotal);
        await sql`INSERT INTO aura_missions(user_id, task_id, day, reward_points)
          VALUES(${user.id}, ${row.task_id}, ${day}, ${reward})
          ON CONFLICT(user_id, task_id, day) DO NOTHING`;
      }
    }

    const [today, coverage, customTasks, days, history] = await Promise.all([
      sql`SELECT m.task_id, c.title, c.category, COALESCE(c.points,m.reward_points,0)::int AS points,
        m.status='done' AS done
        FROM aura_missions m LEFT JOIN aura_completions c
          ON c.user_id=m.user_id AND c.task_id=m.task_id AND c.day=m.day
        WHERE m.user_id=${user.id} AND m.day=${day} ORDER BY m.selected_at`,
      sql`SELECT COUNT(DISTINCT category)::int AS areas FROM aura_completions
        WHERE user_id=${user.id} AND day=${day}`,
      sql`SELECT task_id AS id, title, category, points, 'Moderate' AS difficulty, '✳' AS icon
        FROM aura_custom_tasks WHERE user_id=${user.id} ORDER BY created_at DESC`,
      sql`SELECT to_char(activity.day,'YYYY-MM-DD') AS day FROM (
          SELECT day FROM aura_completions WHERE user_id=${user.id} GROUP BY day
          UNION SELECT day FROM aura_combo_awards WHERE user_id=${user.id}
        ) activity ORDER BY activity.day DESC LIMIT 2000`,
      sql`SELECT to_char(d.day,'YYYY-MM-DD') AS day, SUM(d.completed)::int AS completed,
          (SUM(d.points) + COALESCE(MAX(s.balance_bonus),0))::int AS points
        FROM (
          SELECT day, COUNT(*)::int AS completed, COALESCE(SUM(points),0)::int AS points
          FROM aura_completions WHERE user_id=${user.id} AND verified GROUP BY day
          UNION ALL
          SELECT day, 1::int AS completed, SUM(points)::int AS points
          FROM aura_combo_awards WHERE user_id=${user.id} GROUP BY day
        ) d LEFT JOIN aura_day_submissions s ON s.user_id=${user.id} AND s.day=d.day
        GROUP BY d.day ORDER BY d.day DESC LIMIT 2000`
    ]);
    const todayWithMeta = today.map(item => {
      const task = catalog.find(entry => entry.id === item.task_id) || customTasks.find(entry => entry.id === item.task_id);
      return { ...item, title: item.title || task?.title || 'AURA mission', category: item.category || task?.category || 'Mission' };
    });
    return send(res, 200, {
      profile, stats: totals, today: todayWithMeta, proof: proofRows[0] || null,
      balanceAreas: coverage[0].areas,
      days: days.map(x => typeof x === 'string' ? x : x.day), history, customTasks,
      user: { id: user.id, name: user.name, email: user.email }
    });
  } catch (error) {
    console.error('aura me error', error);
    return send(res, 500, { error: 'Could not load your account.' });
  }
}

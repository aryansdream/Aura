import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';
import { randomBytes } from 'node:crypto';

export default async function handler(req, res) {
  if (req.method !== 'GET') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to sync your progress.' });
    const day = appDay(req), sql = db();
    await ensureSchema(sql);
    await sql`INSERT INTO aura_profiles(user_id, display_name)
      VALUES(${user.id}, ${String(user.name || user.email?.split('@')[0] || 'Aura Explorer').slice(0,24)})
      ON CONFLICT(user_id) DO UPDATE SET user_id=EXCLUDED.user_id RETURNING display_name, gender`;
    const [profile] = await sql`UPDATE aura_profiles SET lan_sync_key=COALESCE(lan_sync_key, ${randomBytes(32).toString('hex')})
      WHERE user_id=${user.id} RETURNING display_name, gender, lan_sync_key AS "lanSyncKey"`;
    const [[stats], days, history] = await Promise.all([
      sql`SELECT COALESCE(SUM(points),0)::int AS total, 0::int AS pending, COUNT(*)::int AS completed,
          COUNT(*) FILTER (WHERE day=${day})::int AS today
        FROM aura_completions WHERE user_id=${user.id} AND verified AND task_id LIKE 'focus_%'`,
      sql`SELECT DISTINCT to_char(day,'YYYY-MM-DD') AS day FROM aura_completions
        WHERE user_id=${user.id} AND verified AND task_id LIKE 'focus_%' ORDER BY day DESC LIMIT 2000`,
      sql`SELECT to_char(day,'YYYY-MM-DD') AS day, COUNT(*)::int AS completed, SUM(points)::int AS points
        FROM aura_completions WHERE user_id=${user.id} AND verified AND task_id LIKE 'focus_%'
        GROUP BY day ORDER BY day DESC LIMIT 2000`
    ]);
    return send(res, 200, {
      profile, stats, today: [], proof: null, balanceAreas: 0,
      days: days.map(row => row.day), history,
      user: { id: user.id, name: user.name, email: user.email }
    });
  } catch (error) {
    console.error('aura me error', error);
    return send(res, 500, { error: 'Could not load your account.' });
  }
}

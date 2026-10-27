import { db, ensureSchema, send } from '../lib/shared.js';

export default async function handler(req, res) {
  if (req.method !== 'GET') return send(res, 405, { error: 'Method not allowed' });
  try {
    const sql = db(); await ensureSchema(sql);
    const rows = await sql`WITH focus_scores AS (
        SELECT user_id, SUM(points)::int AS points FROM aura_completions
        WHERE verified AND task_id LIKE 'focus_%' GROUP BY user_id
      )
      SELECT p.display_name AS name,
        CASE p.gender WHEN 'man' THEN 'King' WHEN 'woman' THEN 'Queen' WHEN 'nonbinary' THEN 'Commander' ELSE 'Champion' END AS title,
        COALESCE(f.points,0)::int AS points
      FROM aura_profiles p LEFT JOIN focus_scores f ON f.user_id=p.user_id
      ORDER BY points DESC, p.display_name ASC, p.user_id ASC`;
    return send(res, 200, { entries: rows });
  } catch (error) { console.error('aura leaderboard error', error); return send(res, 500, { error: 'Leaderboard is temporarily unavailable.' }); }
}

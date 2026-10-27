import { db, ensureSchema, send } from '../lib/shared.js';

export default async function handler(req, res) {
  if (req.method !== 'GET') return send(res, 405, { error: 'Method not allowed' });
  try {
    const sql = db(); await ensureSchema(sql);
    const rows = await sql`WITH mission_scores AS (
        SELECT user_id, SUM(points)::int AS points FROM aura_completions WHERE verified GROUP BY user_id
      ), balance_scores AS (
        SELECT user_id, SUM(balance_bonus)::int AS points FROM aura_day_submissions GROUP BY user_id
      ), combo_scores AS (
        SELECT user_id, SUM(points)::int AS points FROM aura_combo_awards GROUP BY user_id
      )
      SELECT p.display_name AS name,
        CASE p.gender WHEN 'man' THEN 'King' WHEN 'woman' THEN 'Queen' WHEN 'nonbinary' THEN 'Commander' ELSE 'Champion' END AS title,
        (COALESCE(m.points,0)+COALESCE(b.points,0)+COALESCE(c.points,0))::int AS points
      FROM aura_profiles p LEFT JOIN mission_scores m ON m.user_id=p.user_id LEFT JOIN balance_scores b ON b.user_id=p.user_id
      LEFT JOIN combo_scores c ON c.user_id=p.user_id
      ORDER BY points DESC, p.display_name ASC, p.user_id ASC`;
    return send(res, 200, { entries: rows });
  } catch (error) { console.error('aura leaderboard error', error); return send(res, 500, { error: 'Leaderboard is temporarily unavailable.' }); }
}

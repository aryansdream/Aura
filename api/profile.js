import { db, ensureSchema, send, userFromRequest } from '../lib/shared.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in first.' });
    const name = String(req.body?.name || '').trim().slice(0, 24);
    if (!name) return send(res, 400, { error: 'Enter a display name.' });
    const gender = req.body?.gender == null ? null : String(req.body.gender);
    if (gender != null && !['man','woman','nonbinary','prefer_not'].includes(gender)) return send(res, 400, { error: 'Choose a valid optional leaderboard title.' });
    const sql = db(); await ensureSchema(sql);
    await sql`INSERT INTO aura_profiles(user_id, display_name, gender) VALUES(${user.id},${name},COALESCE(${gender},'prefer_not')) ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name, gender=COALESCE(${gender},aura_profiles.gender)`;
    return send(res, 200, { name, gender: gender || 'prefer_not' });
  } catch (error) { console.error('aura profile error', error); return send(res, 500, { error: 'Could not save your profile.' }); }
}

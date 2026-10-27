import { createHash } from 'node:crypto';
import { appDay, db, ensureSchema, send, userFromRequest } from '../lib/shared.js';

const allowed = new Set(['image/png','image/jpeg','image/webp']);
export default async function handler(req, res) {
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in before submitting your screen tracker.' });
    const day = appDay(req);
    const sql = db(); await ensureSchema(sql);
    if (req.method === 'GET') {
      const [row] = await sql`SELECT status, size_bytes, balance_bonus, created_at FROM aura_day_submissions WHERE user_id=${user.id} AND day=${day}`;
      return send(res, 200, { submission: row || null });
    }
    if (req.method !== 'POST') return send(res, 405, { error: 'Method not allowed' });
    const mime = String(req.body?.mime || '');
    if (!allowed.has(mime)) return send(res, 400, { error: 'Upload a PNG, JPEG, or WebP screenshot.' });
    const data = String(req.body?.data || '').replace(/^data:[^,]+,/, '');
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(data)) return send(res, 400, { error: 'The screenshot could not be read.' });
    const image = Buffer.from(data, 'base64');
    if (!image.length || image.length > 2.5 * 1024 * 1024) return send(res, 413, { error: 'Choose a screenshot under 2.5 MB.' });
    const signatures = { 'image/png': image.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])), 'image/jpeg': image[0]===255 && image[1]===216 && image[image.length-2]===255 && image[image.length-1]===217, 'image/webp': image.subarray(0,4).toString()==='RIFF' && image.subarray(8,12).toString()==='WEBP' };
    if (!signatures[mime]) return send(res, 400, { error: 'The file content does not match its image type.' });
    const sha = createHash('sha256').update(image).digest('hex');
    const evidenceSize = image.length;
    image.fill(0);
    await sql`INSERT INTO aura_day_submissions(user_id, day, image_base64, mime, sha256, size_bytes)
      VALUES(${user.id}, ${day}, NULL, ${mime}, ${sha}, ${evidenceSize})
      ON CONFLICT(user_id,day) DO UPDATE SET image_base64=NULL, mime=EXCLUDED.mime, sha256=EXCLUDED.sha256, size_bytes=EXCLUDED.size_bytes, status='submitted', created_at=now()`;
    await sql`UPDATE aura_completions SET verified=true WHERE user_id=${user.id} AND day=${day}`;
    const [coverage] = await sql`SELECT COUNT(DISTINCT category)::int AS areas FROM aura_completions WHERE user_id=${user.id} AND day=${day} AND verified`;
    const balanceBonus = coverage.areas >= 4 ? 25 : 0;
    await sql`UPDATE aura_day_submissions SET balance_bonus=${balanceBonus} WHERE user_id=${user.id} AND day=${day}`;
    const [earned] = await sql`SELECT COALESCE(SUM(points),0)::int AS points FROM aura_completions WHERE user_id=${user.id} AND day=${day} AND verified`;
    return send(res, 200, { status: 'submitted', evidenceSize, todayPoints: earned.points + balanceBonus, missionPoints: earned.points, balanceBonus, areasCovered: coverage.areas, message: balanceBonus ? 'Tracker checked in memory. Mission points validated, plus a 25 AP balance bonus for work across four areas.' : 'Tracker checked in memory. Today’s Aura Points have been validated from your server-recorded missions.' });
  } catch (error) { console.error('aura proof error', error); return send(res, 500, { error: 'Could not validate your screen tracker.' }); }
}

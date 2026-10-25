import { randomUUID } from 'node:crypto';
import { db, ensureSchema, send, userFromRequest } from '../lib/shared.js';

const pointsByDifficulty = { Easy: 5, Moderate: 10, Hard: 10, Challenging: 10, Milestone: 10 };
export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'Method not allowed' });
  try {
    const user = await userFromRequest(req);
    if (!user) return send(res, 401, { error: 'Sign in to save a custom mission.' });
    const title = String(req.body?.title || '').trim().slice(0, 90);
    const difficulty = String(req.body?.difficulty || 'Moderate');
    if (title.length < 3) return send(res, 400, { error: 'Give your mission a little more detail.' });
    if (!Object.hasOwn(pointsByDifficulty, difficulty)) return send(res, 400, { error: 'Choose a listed difficulty.' });
    const sql = db(); await ensureSchema(sql);
    const id = `c_${randomUUID()}`;
    const [task] = await sql`INSERT INTO aura_custom_tasks(user_id,task_id,title,points)
      VALUES(${user.id},${id},${title},${pointsByDifficulty[difficulty]})
      RETURNING task_id AS id,title,category,points`;
    return send(res, 200, { task: { ...task, icon: '✳', difficulty } });
  } catch (error) { console.error('aura custom task error', error); return send(res, 500, { error: 'Could not save that custom mission.' }); }
}

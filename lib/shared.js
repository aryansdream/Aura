import { neon } from '@neondatabase/serverless';

export const db = () => {
  if (!process.env.DATABASE_URL) throw new Error('Database is not configured');
  return neon(process.env.DATABASE_URL);
};

let schemaReady;
export async function ensureSchema(sql) {
  if (!schemaReady) schemaReady = (async () => {
  await sql`CREATE TABLE IF NOT EXISTS aura_profiles (
    user_id text PRIMARY KEY, display_name varchar(24) NOT NULL DEFAULT 'Aura Explorer',
    gender varchar(20) NOT NULL DEFAULT 'prefer_not' CHECK(gender IN ('man','woman','nonbinary','prefer_not')),
    created_at timestamptz NOT NULL DEFAULT now()
  )`;
  await sql`ALTER TABLE aura_profiles ADD COLUMN IF NOT EXISTS gender varchar(20) NOT NULL DEFAULT 'prefer_not' CHECK(gender IN ('man','woman','nonbinary','prefer_not'))`;
  await sql`CREATE TABLE IF NOT EXISTS aura_completions (
    id bigserial PRIMARY KEY, user_id text NOT NULL, task_id varchar(80) NOT NULL,
    title varchar(120) NOT NULL, category varchar(80) NOT NULL, points integer NOT NULL CHECK(points BETWEEN 0 AND 100),
    verified boolean NOT NULL DEFAULT false,
    day date NOT NULL, completed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, task_id, day)
  )`;
  await sql`CREATE TABLE IF NOT EXISTS aura_missions (
    user_id text NOT NULL, task_id varchar(80) NOT NULL, day date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'selected' CHECK(status IN ('selected','done')),
    selected_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, task_id, day)
  )`;
  await sql`ALTER TABLE aura_missions ADD COLUMN IF NOT EXISTS reward_points integer CHECK(reward_points BETWEEN 1 AND 100)`;
  await sql`CREATE TABLE IF NOT EXISTS aura_recurring_missions (
    user_id text NOT NULL, task_id varchar(80) NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, task_id)
  )`;
  // Existing selections become daily missions too, so this works for current accounts.
  await sql`INSERT INTO aura_recurring_missions(user_id, task_id)
    SELECT DISTINCT user_id, task_id FROM aura_missions
    ON CONFLICT(user_id, task_id) DO NOTHING`;
  await sql`CREATE TABLE IF NOT EXISTS aura_custom_tasks (
    user_id text NOT NULL, task_id varchar(80) NOT NULL, title varchar(90) NOT NULL,
    points integer NOT NULL CHECK(points BETWEEN 5 AND 10), category varchar(30) NOT NULL DEFAULT 'Your own',
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, task_id)
  )`;
  await sql`CREATE TABLE IF NOT EXISTS aura_day_submissions (
    user_id text NOT NULL, day date NOT NULL, image_base64 text,
    mime varchar(30) NOT NULL, sha256 char(64) NOT NULL, size_bytes integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'submitted' CHECK(status IN ('submitted','reviewed')),
    balance_bonus integer NOT NULL DEFAULT 0 CHECK(balance_bonus BETWEEN 0 AND 25),
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, day)
  )`;
  await sql`ALTER TABLE aura_day_submissions ADD COLUMN IF NOT EXISTS balance_bonus integer NOT NULL DEFAULT 0 CHECK(balance_bonus BETWEEN 0 AND 25)`;
  // Tracker screenshots are verified in memory only. Erase legacy image payloads.
  await sql`ALTER TABLE aura_day_submissions ALTER COLUMN image_base64 DROP NOT NULL`;
  await sql`UPDATE aura_day_submissions SET image_base64=NULL WHERE image_base64 IS NOT NULL`;
  await sql`CREATE INDEX IF NOT EXISTS aura_completions_day_idx ON aura_completions(day, user_id)`;
  })();
  try { await schemaReady; }
  catch (error) { schemaReady = null; throw error; }
}

export async function userFromRequest(req) {
  const base = process.env.NEON_AUTH_BASE_URL;
  if (!base) return null;
  const response = await fetch(`${base.replace(/\/$/, '')}/get-session`, {
    headers: { cookie: req.headers.cookie || '', origin: process.env.AURA_APP_ORIGIN || 'https://aura-day-tracker.vercel.app' }
  });
  if (!response.ok) return null;
  const data = await response.json();
  return data?.user?.id ? data.user : null;
}

export function send(res, status, body) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json; charset=utf-8');
  res.setHeader('cache-control', 'no-store');
  res.end(JSON.stringify(body));
}

export function todayUTC() { return new Date().toISOString().slice(0, 10); }

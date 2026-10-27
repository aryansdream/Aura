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
  await sql`CREATE TABLE IF NOT EXISTS aura_data_migrations (
    migration_id varchar(100) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
  )`;
  // Reset the legacy focus-score baseline once for every account. Keep completion rows
  // so streak dates and study records survive; future focus segments use the new rate.
  await sql`WITH first_run AS (
    INSERT INTO aura_data_migrations(migration_id)
    VALUES('reset-legacy-focus-points-before-0.10-ap')
    ON CONFLICT DO NOTHING RETURNING 1
  ) UPDATE aura_completions SET points=0
    WHERE points>0 AND task_id LIKE 'focus_%' AND EXISTS (SELECT 1 FROM first_run)`;
  // Class attendance and focus study points are earned directly, without tracker validation.
  await sql`UPDATE aura_completions SET verified=true WHERE verified=false AND
    ((category='Classes' AND left(task_id, 6)='class_') OR (category='Study' AND left(task_id, 6)='focus_'))`;
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
  await sql`CREATE TABLE IF NOT EXISTS aura_planner_data (user_id text PRIMARY KEY, data jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL DEFAULT now())`;
  await sql`CREATE TABLE IF NOT EXISTS aura_devices (
    user_id text NOT NULL, device_id varchar(64) NOT NULL, device_name varchar(48) NOT NULL,
    battery_percent integer CHECK(battery_percent BETWEEN 0 AND 100), charging boolean,
    battery_supported boolean NOT NULL DEFAULT false, last_seen timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, device_id)
  )`;
  await sql`ALTER TABLE aura_profiles ADD COLUMN IF NOT EXISTS lan_sync_key varchar(64)`;
  await sql`CREATE TABLE IF NOT EXISTS aura_combo_awards (
    user_id text NOT NULL, day date NOT NULL, combo_id varchar(40) NOT NULL,
    points integer NOT NULL CHECK(points BETWEEN 1 AND 20000),
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, day)
  )`;
  await sql`CREATE INDEX IF NOT EXISTS aura_completions_day_idx ON aura_completions(day, user_id)`;
  })();
  try { await schemaReady; }
  catch (error) { schemaReady = null; throw error; }
}

export async function userFromRequest(req) {
  const base = process.env.NEON_AUTH_BASE_URL;
  if (!base) throw new Error('Authentication is not configured.');
  const response = await fetch(`${base.replace(/\/$/, '')}/get-session`, {
    headers: { cookie: req.headers.cookie || '', origin: process.env.AURA_APP_ORIGIN || 'https://aura-day-tracker.vercel.app' }
  });
  if (response.status === 401 || response.status === 403) return null;
  if (!response.ok) throw new Error(`Authentication service returned ${response.status}.`);
  const data = await response.json();
  return data?.user?.id ? data.user : null;
}

export function send(res, status, body) {
  res.statusCode = status;
  res.setHeader('content-type', 'application/json; charset=utf-8');
  res.setHeader('cache-control', 'no-store');
  res.end(JSON.stringify(body));
}

export function appDay(req) {
  const requestedZone = String(req?.headers?.['x-aura-time-zone'] || 'UTC').slice(0, 80);
  let zone = 'UTC';
  try { new Intl.DateTimeFormat('en', { timeZone: requestedZone }); zone = requestedZone; } catch {}
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23' }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  const base = new Date(Date.UTC(Number(values.year), Number(values.month) - 1, Number(values.day) - (Number(values.hour) < 2 ? 1 : 0)));
  return base.toISOString().slice(0, 10);
}

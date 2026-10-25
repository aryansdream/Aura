export default async function proxy(req, res, path) {
  const base = process.env.NEON_AUTH_BASE_URL;
  if (!base) return res.status(503).json({ error: 'Authentication is not configured.' });
  const query = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  const target = `${base.replace(/\/$/, '')}/${path}${query}`;
  const headers = new Headers();
  for (const key of ['accept', 'content-type', 'cookie', 'authorization', 'user-agent']) {
    if (req.headers[key]) headers.set(key, req.headers[key]);
  }
  // Use the canonical production origin for Better Auth's trusted-origin checks.
  // The previous deployment-specific alias differs from the URL users actually visit.
  headers.set('origin', process.env.AURA_APP_ORIGIN || 'https://aura-day-tracker.vercel.app');
  const response = await fetch(target, { method: req.method, headers, body: ['GET','HEAD'].includes(req.method) ? undefined : JSON.stringify(req.body), redirect: 'manual' });
  res.statusCode = response.status;
  for (const key of ['content-type', 'cache-control', 'location']) if (response.headers.get(key)) res.setHeader(key, response.headers.get(key));
  // Node's Headers.getSetCookie is available on current Vercel runtimes. Keep a
  // fallback for runtimes that only expose the combined header value.
  const cookies = response.headers.getSetCookie?.() || (response.headers.get('set-cookie') ? [response.headers.get('set-cookie')] : []);
  if (cookies.length) res.setHeader('set-cookie', cookies.map(value => value.replace(/;\s*Domain=[^;]+/ig, '').replace(/;\s*Path=[^;]+/ig, '; Path=/')));
  res.end(Buffer.from(await response.arrayBuffer()));
}

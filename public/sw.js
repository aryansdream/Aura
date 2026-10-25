const CACHE = 'aura-shell-v2';
const CORE = ['/tasks.js', '/manifest.webmanifest', '/aura-icon-192.png', '/aura-icon-512.png', '/aura-maskable-512.png'];

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    const page = await fetch('/', { cache: 'reload' });
    if (page.ok) {
      const source = await page.clone().text();
      await cache.put('/', page);
      const modulePath = source.match(/src="(\/assets\/[^\"]+\.js)"/)?.[1];
      const paths = [...CORE, ...(modulePath ? [modulePath] : [])];
      await Promise.allSettled(paths.map(async path => {
        const response = await fetch(path, { cache: 'reload' });
        if (response.ok) await cache.put(path, response);
      }));
    }
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(key => key.startsWith('aura-shell-') && key !== CACHE).map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const response = await fetch(request);
      if (response.ok && response.type === 'basic') await cache.put(request, response.clone());
      return response;
    } catch {
      return await cache.match(request) || (request.mode === 'navigate' ? await cache.match('/') : undefined) || Response.error();
    }
  })());
});

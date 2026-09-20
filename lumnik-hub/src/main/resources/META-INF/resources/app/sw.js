// lumnik PWA app-shell service worker (E2). App-shell cache only — data is NEVER
// cached: tenant data (/api/*) and auth (/realms/*) stay off-disk, always network.
//
// MAINTENANCE RULE (learned live, first user, first day): bump CACHE on EVERY
// shell-file change. The browser only re-installs when sw.js itself changes bytes —
// an unchanged sw.js means returning visitors keep the OLD cached shell forever
// (cache-first). The version bump IS the deploy signal; activate() purges old caches.
const CACHE = 'lumnik-shell-v10';

const SHELL = [
  './',
  './index.html',
  './styles.css',
  './manifest-clients.json',
  './manifest-stock.json',
  './manifest-generic.json',
  './icon-192.png',
  './icon-512.png',
  './icon-stock-192.png',
  './icon-stock-512.png',
  './icon-generic-192.png',
  './icon-generic-512.png',
  './icon-event-192.png',
  './icon-event-512.png',
  './icon-alert-192.png',
  './icon-alert-512.png',
  './icon-contract-192.png',
  './icon-contract-512.png',
  './src/html.mjs',
  './src/api.mjs',
  './src/appRegistry.mjs',
  './src/auth.mjs',
  './src/capabilities.mjs',
  './src/config.mjs',
  './src/door-tokens.mjs',
  './src/format.mjs',
  './src/views.mjs',
  './src/components/app.mjs',
  './src/components/cards.mjs',
  './src/components/chat.mjs',
  './src/components/detail.mjs',
  './src/components/fiches.mjs',
  './src/components/filterbar.mjs',
  './src/components/inbox.mjs',
  './src/components/login.mjs',
  './src/components/refusal.mjs',
  './src/components/tabbar.mjs',
  './src/components/table.mjs',
  './src/components/viewpicker.mjs',
  './vendor/preact.mjs',
  './vendor/hooks.mjs',
  './vendor/htm.mjs',
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((names) => Promise.all(
        names.filter((name) => name !== CACHE).map((name) => caches.delete(name))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/realms/')) return;

  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request).catch(() => caches.match('./index.html'))
    );
    return;
  }

  if (url.origin === self.location.origin && e.request.method === 'GET') {
    e.respondWith(
      caches.match(e.request).then((cached) => cached || fetch(e.request))
    );
  }
});

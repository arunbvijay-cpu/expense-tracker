// Cache the app shell so the PWA opens offline.
// Expense data itself is fetched live from Apps Script — when offline,
// the page falls back to its in-memory state + queued entries in localStorage.

const CACHE = 'expense-tracker-v7';
const SHELL = ['./', './index.html', './manifest.json', './bg.jpg', './icon-192.jpg', './icon-512.jpg', './icon-180.jpg'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c =>
      // Don't fail install if bg.jpg is missing yet.
      Promise.all(SHELL.map(u => c.add(u).catch(()=>{})))
    ).then(()=>self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k))))
      .then(()=>self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);

  // Never cache Apps Script API calls
  if (url.hostname.endsWith('script.google.com')) return;

  if (req.method === 'GET' && url.origin === self.location.origin) {
    e.respondWith(
      caches.match(req).then(hit => hit || fetch(req).then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
        return res;
      }).catch(()=>caches.match('./index.html')))
    );
  }
});

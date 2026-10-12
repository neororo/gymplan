const CACHE = 'pdflow-v3';
const ASSETS = [
  './',
  './index.html',
  './app.js',
  './manifest.json',
  './icon.svg',
  './vendor/pdf.min.js',
  './vendor/pdf.worker.min.js',
  './vendor/pdf-lib.min.js',
  './vendor/fontkit.umd.min.js',
  './fonts/LiberationSans-Regular.ttf',
  './fonts/LiberationSans-Bold.ttf',
  './fonts/LiberationSans-Italic.ttf',
  './fonts/LiberationSans-BoldItalic.ttf',
  './fonts/LiberationSerif-Regular.ttf',
  './fonts/LiberationSerif-Bold.ttf',
  './fonts/LiberationSerif-Italic.ttf',
  './fonts/LiberationSerif-BoldItalic.ttf',
  './fonts/LiberationMono-Regular.ttf',
  './fonts/LiberationMono-Bold.ttf',
  './fonts/LiberationMono-Italic.ttf',
  './fonts/LiberationMono-BoldItalic.ttf',
  './fonts/Carlito-Regular.ttf',
  './fonts/Carlito-Bold.ttf',
  './fonts/Carlito-Italic.ttf',
  './fonts/Carlito-BoldItalic.ttf',
  './fonts/Caladea-Regular.ttf',
  './fonts/Caladea-Bold.ttf',
  './fonts/Caladea-Italic.ttf',
  './fonts/Caladea-BoldItalic.ttf'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k.startsWith('pdflow-') && k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request).then(cached => cached || fetch(e.request).catch(() => caches.match('./index.html')))
  );
});

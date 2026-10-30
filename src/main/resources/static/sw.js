'use strict';

// Keep this version in sync with the ?v= query strings in index.html.
const CACHE_VERSION = '1.0.18';
const CACHE_NAME = `geometry-helper-${CACHE_VERSION}`;
const SCOPE = self.registration.scope;
const INDEX_URL = new URL('index.html', SCOPE).href;
const API_BASE_PATH = new URL('api/', SCOPE).pathname;
const APP_SHELL = [
  '',
  'index.html',
  'list.html',
  'css/app.css?v=1.0.18',
  'css/list.css?v=1.0.18',
  'css/vendor/katex.min.css?v=1.0.18',
  'js/app.js?v=1.0.18',
  'js/markdown.js?v=1.0.18',
  'js/net.js?v=1.0.18',
  'js/list.js?v=1.0.18',
  'js/vendor/marked.min.js?v=1.0.18',
  'js/vendor/purify.min.js?v=1.0.18',
  'js/vendor/katex.min.js?v=1.0.18',
  'css/vendor/fonts/KaTeX_AMS-Regular.woff2',
  'css/vendor/fonts/KaTeX_Caligraphic-Bold.woff2',
  'css/vendor/fonts/KaTeX_Caligraphic-Regular.woff2',
  'css/vendor/fonts/KaTeX_Fraktur-Bold.woff2',
  'css/vendor/fonts/KaTeX_Fraktur-Regular.woff2',
  'css/vendor/fonts/KaTeX_Main-Bold.woff2',
  'css/vendor/fonts/KaTeX_Main-BoldItalic.woff2',
  'css/vendor/fonts/KaTeX_Main-Italic.woff2',
  'css/vendor/fonts/KaTeX_Main-Regular.woff2',
  'css/vendor/fonts/KaTeX_Math-BoldItalic.woff2',
  'css/vendor/fonts/KaTeX_Math-Italic.woff2',
  'css/vendor/fonts/KaTeX_SansSerif-Bold.woff2',
  'css/vendor/fonts/KaTeX_SansSerif-Italic.woff2',
  'css/vendor/fonts/KaTeX_SansSerif-Regular.woff2',
  'css/vendor/fonts/KaTeX_Script-Regular.woff2',
  'css/vendor/fonts/KaTeX_Size1-Regular.woff2',
  'css/vendor/fonts/KaTeX_Size2-Regular.woff2',
  'css/vendor/fonts/KaTeX_Size3-Regular.woff2',
  'css/vendor/fonts/KaTeX_Size4-Regular.woff2',
  'css/vendor/fonts/KaTeX_Typewriter-Regular.woff2',
  'manifest.json',
  'favicon.ico',
  'icons/icon.svg',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon-maskable-512.png',
  'icons/apple-touch-icon.png'
].map((path) => new URL(path, SCOPE).href);

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((key) => key.startsWith('geometry-helper-') && key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;

  if (request.method !== 'GET') {
    return;
  }

  const url = new URL(request.url);

  if (url.origin !== self.location.origin) {
    return;
  }

  // API requests are always network-only. Offline handling belongs to the page.
  if (url.pathname.startsWith(API_BASE_PATH)) {
    return;
  }

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response && response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => {
          return (await caches.match(request))
            || (await caches.match(INDEX_URL))
            || new Response('当前离线', {
              status: 503,
              headers: { 'Content-Type': 'text/plain; charset=utf-8' }
            });
        })
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response && response.ok) {
            const copy = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached || new Response('', { status: 504, statusText: 'Offline' }));

      return cached || network;
    })
  );
});

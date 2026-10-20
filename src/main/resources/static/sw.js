/*
 * The service worker (redesign.md R-22).
 *
 * <p>It exists for one reason: when the app is down - which is most often because the owner is
 * rebuilding it - a browser shows "This site can't be reached". Nothing of the product is on the
 * screen, and the manager cannot tell a rebuild from a broken product.
 *
 * <p>So the worker keeps the maintenance page and the two images it needs in a cache, and when a
 * **navigation** cannot reach the network, it answers with the cached page instead of the browser's
 * error page. The page then pings the server and puts the manager back where they were as soon as
 * it is up.
 *
 * <p>Two rules it lives by, and both of them are about not making the app worse:
 *
 * <ul>
 *   <li><b>Only navigations are touched.</b> Scripts, styles, images and API calls go straight to
 *       the network. A worker that cached the application's own files would serve stale code after
 *       every rebuild - the exact class of bug this page exists to hide.</li>
 *   <li><b>Nothing is cached that the app needs.</b> The cache holds the maintenance page and the
 *       two images on it. The app's own assets are not in it.</li>
 * </ul>
 */

const CACHE_NAME = 'fm-maintenance-v2';
const MAINTENANCE_PAGE = '/maintenance.html';
const MAINTENANCE_ASSETS = [
    MAINTENANCE_PAGE,
    '/images/maintenance-bg.jpg',
    '/images/logomain.jpg'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.addAll(MAINTENANCE_ASSETS))
            .then(() => self.skipWaiting())
            .catch(() => self.skipWaiting())
    );
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys()
            .then(keys => Promise.all(keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const request = event.request;

    // Only a page navigation. Everything else - the app's scripts, its styles, its API calls -
    // goes to the network untouched, so a rebuild is never served a stale file.
    if (request.method !== 'GET' || request.mode !== 'navigate') return;

    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;

    event.respondWith((async () => {
        try {
            return await fetch(request);
        } catch (error) {
            return await maintenanceResponse(url);
        }
    })());
});

/**
 * The cached maintenance page, with the page the manager was trying to reach written into it.
 *
 * <p>The target is injected rather than read from the URL, because the worker is the only thing
 * that knows which page failed to load.
 */
async function maintenanceResponse(url) {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(MAINTENANCE_PAGE);
    if (!cached) {
        return new Response(
            '<!doctype html><meta charset="utf-8"><title>Maintenance</title>'
            + '<body style="font-family:system-ui;background:#120d1c;color:#f6f1e6;display:grid;'
            + 'place-items:center;height:100vh;margin:0"><h1>Maintenance in progress</h1></body>',
            { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } }
        );
    }

    const html = await cached.text();
    const target = url.pathname + url.search;
    const injected = html.replace(
        '</head>',
        `<script>window.__FM_BACK_TARGET = ${JSON.stringify(target)};</script></head>`
    );
    return new Response(injected, {
        status: 200,
        headers: { 'Content-Type': 'text/html; charset=utf-8' }
    });
}

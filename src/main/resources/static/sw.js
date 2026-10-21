/*
 * The service worker (redesign.md R-22).
 *
 * <p>It exists for one reason: when the app is down - which is most often because the owner is
 * rebuilding it - a browser shows "This site can't be reached". Nothing of the product is on the
 * screen, and the manager cannot tell a rebuild from a broken product.
 *
 * <p>So the worker keeps the maintenance page and the two images it needs in a cache, and when a
 * **navigation** cannot reach the network, it answers with the cached page instead of the browser's
 * error page. The page then pings the server and puts the manager back where they were as soon as it
 * is up.
 *
 * <p>Two rules it lives by, and both of them are about not making the app worse:
 *
 * <ul>
 *   <li><b>Only navigations are touched.</b> Scripts, styles, images and API calls go straight to the
 *       network. A worker that cached the application's own files would serve stale code after
 *       every rebuild - the exact class of bug this page exists to hide.</li>
 *   <li><b>Nothing is cached that the app needs.</b> The cache holds the maintenance page and the
 *       two images on it. The app's own assets are not in it.</li>
 * </ul>
 */

/**
 * The cache's name.
 *
 * <p><b>It is a version, and it has to move when the maintenance page or its images change.</b>
 * The cache is filled when the worker installs, and the worker installs when **this file** changes -
 * so a page that changed without the name changing would be a page a deploy behind, and the
 * manager would be looking at the previous picture and the previous words while the app was down
 * (owner, 2026-10-21: "i dalje nije slika za mob na maintenance strani kad udjem s moba.. probao
 * refresh, probao private tab - opet isecena slika sa desktopa").
 *
 * <p>The refresh below makes that unnecessary in the steady state, but the name still has to move
 * once, so that the first visit after this deploy picks the new page up.
 */
const CACHE_NAME = 'fm-maintenance-v3';
const MAINTENANCE_PAGE = '/maintenance.html';
const MAINTENANCE_ASSETS = [
    MAINTENANCE_PAGE,
    '/images/maintenance-bg.jpg',
    '/images/maintenance-bg-portrait.jpg',
    '/images/logomain.jpg'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => fillTheCache(cache))
            .then(() => self.skipWaiting())
            .catch(() => self.skipWaiting())
    );
});

/**
 * Fills the cache with the current page and its images, from the network.
 *
 * <p><b>Fetched with {@code no-store}, one at a time.</b> The browser's own HTTP cache is not asked:
 * the app serves the page with {@code no-cache} but the images with an hour's lifetime, and a cached
 * picture is a picture from the last hour. The whole point of the cache is to be the copy the manager
 * sees while the app is down, and that copy has to be the one the deploy shipped.
 *
 * <p>One at a time, and a failure on one asset does not lose the others: the page matters more than
 * the badge, and a missing picture is better than no page.
 */
async function fillTheCache(cache) {
    for (const asset of MAINTENANCE_ASSETS) {
        try {
            const response = await fetch(asset, { cache: 'no-store' });
            if (response && response.ok) {
                await cache.put(asset, response);
            }
        } catch (error) {
            // The network was down for this one. The others may still be there.
        }
    }
}

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.match(MAINTENANCE_PAGE))
            .then(cached => {
                // **The old caches go only if the new one has the page.** The install fills it from
                // the network, and a rebuild is exactly when the network is down - so an install
                // that could not fetch must not throw away the page that is there. The picture and
                // the words would then be a deploy behind, which is better than no page at all, and
                // the next visit with the app up fills the new cache.
                if (!cached) return null;
                return caches.keys()
                    .then(keys => Promise.all(
                            keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))));
            })
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const request = event.request;
    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;

    /*
     * **The maintenance page's own two images are served from the cache when the network is down.**
     *
     * <p>They are the picture across the whole page and the badge in the corner (owner, 2026-10-20:
     * "zasto u lokalu ne prikazuje sliku prekocele strane, logo gore i ovaj text ima?"). The page was
     * cached and served, but its images went straight to the network like everything else - and the
     * network was down, which is the whole reason the page was being shown. So the manager got the
     * sentence and none of the game around it.
     *
     * <p>The network is still tried first, so a live app always gets the live file. The rule that
     * nothing the app needs is cached still holds: these are the maintenance page's own.
     *
     * <p><b>And what the network answers is written back into the cache.</b> Otherwise the cache
     * would be a deploy behind: the app comes up with a new maintenance page, the manager opens the
     * site and is answered with the cached one - the old picture, the old words. A rebuild is
     * exactly the moment this page is shown, so it must not be the one thing that is out of date.
     */
    if (request.method === 'GET' && url.pathname !== MAINTENANCE_PAGE && MAINTENANCE_ASSETS.includes(url.pathname)) {
        event.respondWith((async () => {
            try {
                const response = await fetch(request);
                putInTheCache(request, response.clone());
                return response;
            } catch (error) {
                const cached = await caches.match(request);
                if (cached) return cached;
                throw error;
            }
        })());
        return;
    }

    // Only a page navigation. Everything else - the app's scripts, its styles, its API calls -
    // goes to the network untouched, so a rebuild is never served a stale file.
    if (request.method !== 'GET' || request.mode !== 'navigate') return;

    event.respondWith((async () => {
        try {
            const response = await fetch(request);
            if (url.pathname === MAINTENANCE_PAGE) {
                // The page itself, and it is the one that changes with a deploy.
                putInTheCache(MAINTENANCE_PAGE, response.clone());
            } else {
                // Any other page: the network is up, so this is the moment to make sure the
                // maintenance page in the cache is the current one. The page the manager was
                // trying to reach is not the maintenance page and must never be cached as it.
                refreshTheMaintenancePage();
            }
            return response;
        } catch (error) {
            return await maintenanceResponse(url);
        }
    })());
});

/**
 * Puts a response into the maintenance cache, if it can.
 *
 * <p>The write is not awaited by the caller: a cache write that fails must not turn a page that
 * loaded into one that did not.
 */
function putInTheCache(key, response) {
    caches.open(CACHE_NAME)
        .then(cache => cache.put(key, response))
        .catch(() => { /* the cache is an aid, not a requirement */ });
}

/**
 * Fetches the maintenance page in the background and puts it in the cache.
 *
 * <p>Called on every navigation that succeeded, so the cached page is the current one by the time
 * the app goes down again - which is the only time the cache is read.
 */
function refreshTheMaintenancePage() {
    fetch(MAINTENANCE_PAGE, { cache: 'no-store' })
        .then(response => putInTheCache(MAINTENANCE_PAGE, response))
        .catch(() => { /* the network was up a moment ago; nothing to do */ });
}

/**
 * The cached maintenance page, with the page the manager was trying to reach written into it.
 *
 * <p>The target is injected rather than read from the URL, because the worker is the only thing
 * that knows which page failed to load.
 */
async function maintenanceResponse(url) {
    const cache = await caches.open(CACHE_NAME);
    let cached = await cache.match(MAINTENANCE_PAGE);
    if (!cached) {
        // Any cache, not only this one's: an install that could not reach the network leaves the
        // previous cache holding the page, and that page is better than the bare fallback below.
        cached = await caches.match(MAINTENANCE_PAGE);
    }
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

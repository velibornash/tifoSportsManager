// notifications.js
//
// The badge, the dropdown, and the 30-second poll (owner, 2026-10-05).
//
// WHY POLLING AND NOT A WEBSOCKET
//
// The owner chose polling, and the reason is in the codebase rather than in taste: all four WebSocket
// endpoints registered by WebSocketConfig are dead — no frontend connects, nothing broadcasts, and the
// handshake interceptor puts a username into session attributes that no handler ever reads. Routing is
// by matchId, so there is no per-user channel to hang a notification on. Building one is a week of work
// and it would be the first thing in the application carrying a live socket for a feature that has not
// been watched running yet.
//
// 30 seconds matches the existing game-clock poll in clock.js, so this is one more timer rather than a
// new pattern.
//
// WHAT THIS DELIBERATELY DOES NOT DO
//
// It does not delete notifications. Marking one read is a POST the backend performs; the row stays, so
// "what happened to me" survives the badge clearing. The dropdown shows read and unread alike, newest
// first, with read ones dimmed.

import { authFetch, handleAuthFailure } from './auth.js';
import { escapeHtml } from './ui/escape.js';

const POLL_MS = 30 * 1000;
const DROPDOWN_PAGE = 30;

/**
 * Whether this page has already started polling.
 *
 * <p>A flag rather than a question about the document, and the previous version asked the wrong
 * question:
 *
 * <pre>{@code
 * if (document.getElementById('notification-bell')) { return; }   // always true
 * }</pre>
 *
 * The bell is in `dashboard.html`, so it exists from the first byte — which meant the guard returned
 * every time and <b>the poll never started at all</b>. The badge only ever updated when the manager
 * opened the dropdown, which is why nobody noticed: opening the dropdown is what people do anyway. The
 * red dot and the ring then had nothing to run on, and both were reported as "does not work".
 *
 * <p>The intent was right — a second interval would double the request rate for the rest of the
 * browser's life — so it is kept, asked of the state it actually means: have I started?
 */
let pollStarted = false;

/**
 * Starts the poll. Called once, after the session is known.
 *
 * <p>Not started at import time: a 401 from an anonymous session would send the manager to the login
 * page for no reason, and the login page does not want a notification poll running against it.
 */
export function startNotificationPolling() {
    if (pollStarted) {
        return;
    }
    pollStarted = true;
    // First paint immediately rather than after 30 seconds of nothing, because a badge that appears
    // half a minute late looks broken even when it is only late.
    void refreshNotifications();
    window.setInterval(refreshNotifications, POLL_MS);

    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            // A tab left open in the background for an hour should not show a badge from when it was
            // last visible. Polling continues while hidden on purpose — a laptop asleep is not a
            // laptop idle — but the first thing done on return is a fresh read.
            void refreshNotifications();
        }
    });
}

/**
 * Reads the notifications and repaints the bell.
 *
 * <p>One request serves both the badge and the dropdown, deliberately: two requests are two chances for
 * the count and the list to disagree, and a badge that says 3 above a list showing 2 is the kind of bug
 * that gets reported as "it's just wrong sometimes".
 */
export async function refreshNotifications() {
    try {
        const response = await authFetch(`/notifications?size=${DROPDOWN_PAGE}`);
        if (!response.ok) return;
        const payload = await response.json();
        paintBell(payload);
    } catch (err) {
        // A failed poll is not worth interrupting the manager for. The next one is 30 seconds away, and
        // an alert box every 30 seconds is how a manager learns to ignore alerts.
        if (err && err.status === 401) {
            handleAuthFailure(err, 'Your session expired.');
        }
    }
}

/** Reads the unread count out of a payload, treating anything unexpected as zero. */
export function readUnreadCount(payload) {
    const count = Number(payload?.unreadCount);
    return Number.isFinite(count) && count > 0 ? count : 0;
}

function paintBell(payload) {
    const bell = document.getElementById('notification-bell');
    const badge = document.getElementById('notification-badge');
    const dropdown = document.getElementById('notification-dropdown');
    if (!bell || !badge) return;

    const unread = readUnreadCount(payload);

    if (badge) {
        badge.textContent = unread > 99 ? '99+' : String(unread);
        badge.hidden = unread === 0;
    }

    // The red dot is a *separate* mark from the count, on purpose (owner, 2026-10-06). The count already
    // says whether anything is unread, but a number is read rather than noticed: at 40px of top bar, in
    // peripheral vision, a manager sees the bell change colour and then decides to look. The dot is
    // what makes it noticeable; the number is what tells him how bad it is once he has looked.
    bell.classList.toggle('has-unread', unread > 0);

    bell.setAttribute('aria-label',
        unread > 0 ? `Notifications, ${unread} unread` : 'Notifications, none unread');

    announceNewArrivals(unread);

    if (dropdown && !dropdown.hidden) {
        dropdown.innerHTML = buildDropdownHtml(payload);
        bindDropdown(dropdown);
    }
}

/**
 * The count this session has already seen, or null before the first read.
 *
 * <p>Null rather than zero on purpose. Starting at zero would ring the bell for a manager who already had
 * four unread when he signed in, which is the wrong event entirely: the sound is for *something arriving
 * while he is watching*, not for a backlog he already owns.
 */
let lastSeenUnread = null;

/**
 * Rings when the unread count goes up, and says so through the dot.
 *
 * <p><b>Only on an increase.</b> The poll runs every 30 seconds for as long as the tab is open, so a
 * sound tied to "there is something unread" would ring every 30 seconds for the rest of the session — the
 * fastest possible way to make a manager mute a tab. It is tied to the count rising instead, which is
 * the actual event.
 *
 * <p>A drop never rings, so reading the dropdown on the phone and then the laptop does not set off an
 * alarm on the laptop.
 */
function announceNewArrivals(unread) {
    if (lastSeenUnread !== null && unread > lastSeenUnread) {
        playNotificationChime();
    }
    lastSeenUnread = unread;
}

/**
 * A notification has been dealt with: take it off the screen and off the badge, at once.
 *
 * <p><b>The screen changes before the server is asked</b>, because the owner asked for the count to drop
 * the moment he opens a conversation, and a badge that waits on a round trip to update is a badge that
 * reads as broken. If the POST fails the next poll corrects it - the row is on its way back into the
 * database's state either way, and a count that is briefly one too high is better than one that is
 * briefly wrong about something he has already dealt with.
 *
 * <p>Shared by both ways of reading a notification: clicking the row, and clicking the link that opens
 * the topic or the conversation. They are the same event and used to be handled differently.
 */
async function consumeNotification(id, row) {
    if (!id) {
        await refreshNotifications();
        return;
    }
    if (row) {
        row.remove();
    }
    decrementUnread();
    try {
        await authFetch(`/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' });
    } catch {
        // Left for the next poll. A read that fails stays unread on the server, which is the honest
        // state: better a badge one too high than one that clears a notification he never saw.
    }
}

/** Moves the badge and the red dot down by one, without waiting for the server. */
function decrementUnread() {
    const badge = document.getElementById('notification-badge');
    const bell = document.getElementById('notification-bell');
    if (!badge || !bell) return;
    const current = Number(badge.textContent);
    const next = Number.isFinite(current) && current > 0 ? current - 1 : 0;
    badge.textContent = next > 99 ? '99+' : String(next);
    badge.hidden = next === 0;
    bell.classList.toggle('has-unread', next > 0);
    if (lastSeenUnread !== null) {
        lastSeenUnread = Math.max(0, lastSeenUnread - 1);
    }
}

/**
 * The ring itself.
 *
 * <p><b>Synthesised, not a file.</b> A two-note chime built with the Web Audio API costs nothing to
 * download and nothing to maintain, and it dodges the question a shipped {@code .mp3} would raise
 * anyway: whether a notification should be a 40 KB asset in the repository forever.
 *
 * <p><b>It fails silently, on purpose.</b> Browsers block audio until the page has been interacted with,
 * and the error arrives as a promise rejection rather than a throw. A manager who has not clicked
 * anything simply gets no sound, which is the correct outcome — he is not looking at the tab. An
 * unhandled rejection here would surface as a console error on the dashboard, and
 * {@code CountryPageRendersTest} treats a console error as a failure, so "no sound until you click"
 * would have broken a test that has nothing to do with notifications.
 */
function playNotificationChime() {
    try {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return;
        const context = new AudioContextClass();
        if (context.state === 'suspended') {
            context.close().catch(() => {});
            return;
        }

        // Two notes a fifth apart, the second quieter and later: a "ting-ting" rather than an alarm,
        // because most of these are somebody replying in a forum.
        [[880, 0], [1174.66, 0.12]].forEach(([frequency, at]) => {
            const oscillator = context.createOscillator();
            const gain = context.createGain();
            oscillator.type = 'sine';
            oscillator.frequency.value = frequency;
            gain.gain.setValueAtTime(0.0001, context.currentTime + at);
            gain.gain.exponentialRampToValueAtTime(0.15, context.currentTime + at + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + at + 0.28);
            oscillator.connect(gain).connect(context.destination);
            oscillator.start(context.currentTime + at);
            oscillator.stop(context.currentTime + at + 0.3);
        });

        // Closed after it has rung, so a long session does not accumulate one AudioContext per arrival.
        window.setTimeout(() => context.close().catch(() => {}), 800);
    } catch {
        // No audio, no complaint. A notification that cannot make a noise is still on the screen.
    }
}

/**
 * The dropdown body.
 *
 * <p>Every value goes through {@link escapeHtml}. A notification summary is built from a forum topic
 * title or a message subject — both of which are typed by another manager — so this is the one place in
 * the notification UI where untrusted text arrives, and it is the place a stored XSS would land.
 */
/**
 * The dropdown's contents: **unread only**.
 *
 * <p>This used to show read and unread alike, newest first, with the read ones dimmed — the original
 * reasoning being that "what happened to me" should survive the badge clearing. The owner overruled it
 * (2026-10-07):
 *
 * > *"kad se poruka procita skida se iz tickera, isto vazi i za ostale poruke, kad se uradi sto psie
 * > prestane da izlazi"*
 *
 * which is the right instinct: a list of things to deal with should empty as they are dealt with.
 * A ticker that keeps showing read items is a to-do list nobody can clear.
 *
 * <p>The rows stay in the database. This is a view over the unread set, not a delete — the unread count
 * and the list are read from one payload precisely so they cannot disagree about what is left.
 */
export function buildDropdownHtml(payload) {
    const all = Array.isArray(payload?.notifications) ? payload.notifications : [];
    const unread = readUnreadCount(payload);
    // Trusted over the payload's own count: if the two ever disagreed, the row the manager can see is
    // the honest answer, and the header is derived from the same list so it cannot contradict it.
    const rows = all.filter(row => row?.read !== true);

    if (!rows.length) {
        return all.length
            ? `<div class="notification-empty">Nothing unread. You are caught up.</div>`
            : `<div class="notification-empty">Nothing yet. Replies to your forum posts and your
                private messages will appear here.</div>`;
    }

    const items = rows.map(row => {
        const destination = targetActionHtml(row);
        return `
            <div class="notification-row" data-notification-id="${escapeHtml(row.id)}">
                <div class="notification-row-head">
                    <span class="notification-kind">${escapeHtml(kindLabel(row.kind))}</span>
                    <span class="fm-subtle">${escapeHtml(formatWhen(row.createdAt))}</span>
                </div>
                <div class="notification-summary">${escapeHtml(row.summary || '')}</div>
                ${destination}
            </div>`;
    }).join('');

    return `
        <div class="notification-dropdown-head">
            <strong>${rows.length} unread</strong>
            <button type="button" class="fm-link-btn js-read-all">Mark all read</button>
        </div>
        <div class="notification-list">${items}</div>`;
}

/**
 * Where a click goes.
 *
 * <p>Unknown or missing destinations render nothing rather than a link that goes nowhere. A notification
 * of a kind this build does not understand is still worth reading; it just is not clickable.
 */
function targetActionHtml(row) {
    const page = row?.targetPage;
    const id = row?.targetId;
    if (!page || !id) return '';
    // The notification's own id travels with the link, because opening a conversation *is* reading the
    // notification that led to it - see consumeNotification.
    const owner = `data-notification-id="${escapeHtml(row.id)}"`;
    if (page === 'forumTopic') {
        return `<button type="button" class="fm-link-btn js-go js-go-topic"
            data-topic-id="${escapeHtml(id)}" ${owner}>Open the topic</button>`;
    }
    if (page === 'messageThread') {
        return `<button type="button" class="fm-link-btn js-go js-go-thread"
            data-thread-id="${escapeHtml(id)}" ${owner}>Open the conversation</button>`;
    }
    return '';
}

function kindLabel(kind) {
    const labels = {
        FORUM_REPLY: 'Forum',
        PM_RECEIVED: 'Message',
        FORUM_BANNED: 'Moderation',
        REGISTRATION_DECIDED: 'Registration'
    };
    return labels[kind] || 'Notice';
}

/**
 * A short relative time.
 *
 * <p>Relative because a notification list is read by scanning, and "4 minutes ago" is scanned; a
 * timestamp is read one at a time. Falls back to the raw value rather than "Invalid Date" when the
 * string is not a date this browser can parse, which happens when the field is missing.
 */
function formatWhen(iso) {
    if (!iso) return '';
    const then = new Date(iso);
    if (Number.isNaN(then.getTime())) return String(iso);

    const seconds = Math.max(0, Math.round((Date.now() - then.getTime()) / 1000));
    if (seconds < 60) return 'just now';
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `${hours} h ago`;
    const days = Math.round(hours / 24);
    if (days < 7) return `${days} d ago`;
    return then.toLocaleDateString();
}

/** Wires the dropdown's buttons. Re-bound on every paint because the markup is replaced. */
function bindDropdown(dropdown) {
    dropdown.querySelectorAll('.js-read-all').forEach(button => {
        button.addEventListener('click', async () => {
            button.disabled = true;
            try {
                const response = await authFetch('/notifications/read-all', { method: 'POST' });
                if (response.ok) await refreshNotifications();
            } finally {
                button.disabled = false;
            }
        });
    });

    dropdown.querySelectorAll('.js-go').forEach(button => {
        button.addEventListener('click', () => {
            const topicId = button.dataset.topicId;
            const threadId = button.dataset.threadId;
            // **Opening the conversation is reading the notification** (owner, 2026-10-07): "kad se klikne
            // na open conversation ili open forum iz notifications odmah smanji broj unread-a jer je taj
            // vec procitan". It used to navigate without touching the row, and the row's own click
            // handler deliberately skipped these buttons - so a notification could be acted on for ever
            // and still sit in the ticker with the count unchanged.
            void consumeNotification(button.dataset.notificationId, button.closest('.notification-row'));
            closeDropdown();
            if (topicId && typeof window.openForumTopic === 'function') {
                window.openForumTopic(topicId);
            } else if (threadId && typeof window.openMessageThread === 'function') {
                window.openMessageThread(threadId);
            }
        });
    });

    dropdown.querySelectorAll('.notification-row').forEach(row => {
        row.addEventListener('click', event => {
            if (event.target.closest('.js-go')) return;
            void consumeNotification(row.dataset.notificationId, row);
        });
    });
}

function closeDropdown() {
    const dropdown = document.getElementById('notification-dropdown');
    if (dropdown) dropdown.hidden = true;
}

/**
 * Opens and closes the dropdown, and renders it the first time it is opened.
 *
 * <p>Rendered on open rather than on every poll: a closed dropdown nobody can see does not need its
 * markup rebuilt 120 times an hour.
 */
export function wireNotificationBell() {
    const bell = document.getElementById('notification-bell');
    const dropdown = document.getElementById('notification-dropdown');
    if (!bell || !dropdown) return;

    bell.addEventListener('click', async (event) => {
        event.stopPropagation();
        const willOpen = dropdown.hidden;
        dropdown.hidden = !willOpen;
        bell.setAttribute('aria-expanded', String(willOpen));
        if (willOpen) {
            try {
                const response = await authFetch(`/notifications?size=${DROPDOWN_PAGE}`);
                if (response.ok) {
                    const payload = await response.json();
                    dropdown.innerHTML = buildDropdownHtml(payload);
                    bindDropdown(dropdown);
                }
            } catch {
                dropdown.innerHTML = '<div class="notification-empty">Could not load notifications.</div>';
            }
        }
    });

    document.addEventListener('click', (event) => {
        if (dropdown.hidden) return;
        if (dropdown.contains(event.target) || bell.contains(event.target)) return;
        dropdown.hidden = true;
        bell.setAttribute('aria-expanded', 'false');
    });

    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !dropdown.hidden) {
            dropdown.hidden = true;
            bell.setAttribute('aria-expanded', 'false');
            bell.focus();
        }
    });
}
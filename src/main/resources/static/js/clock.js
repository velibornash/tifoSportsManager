// The shared implementation, not a local copy (owner, 2026-09-28).
//
// clock.js had its own stripped-down authFetch: it attached the token and threw on failure, but on a
// missing token it only threw, where the shared one redirects to the login page, and it ignored the
// redirect-to-login and network-error cases entirely. Two copies of an authenticated fetch is how a
// clock keeps polling against a session that has already ended, and the failure shows up as a page
// that quietly stops updating rather than as an error anyone is shown.
//
// The only behavioural change is the intended one: a missing or expired session now sends the manager
// to the login screen instead of leaving a dead clock on screen.
import { authFetch } from './auth.js';

/** The game's zone. Defined once on the server (GameTimeline.GAME_ZONE) and mirrored here for display. */
const GAME_ZONE = 'Europe/Belgrade';

/** Twelve weeks of seven days — the shape of a season, mirrored from SeasonCalendar. */
const DAYS_PER_SEASON = 84;

let seasonNumber = 1;
let weekNumber = 1;
let dayNumber = 1;
let dayLabel = '';
let phaseLabel = 'Season in progress';

/**
 * The game clock (owner, 2026-10-11).
 *
 * <p><b>It ticks like a real clock, and it is one clock.</b> Previously this header rendered
 * real Belgrade time, then was changed to render the game's own hour as "HH:00" — which was not a
 * clock at all, and which disagreed with the day beside it every time the day rolled over, because
 * the day came from a poll and the hour did not.
 *
 * <p>Now the server sends its own game time in epoch milliseconds. This file anchors on that, not on
 * the device clock: the server is a machine in another country, and a laptop four minutes out would
 * otherwise display a game time four minutes out — which is the same disagreement wearing a different
 * hat. Between polls the display advances locally, so the seconds hand moves, and the season, week
 * and day are derived from the same instant rather than arriving separately and drifting.
 */
let serverAnchorMillis = null;
let serverSkewMillis = 0;
let anchorDate = null;
let kickoffHour = null;
let kickoffMinute = null;
let matchDay = false;
let syncing = false;

/** The last game position the client saw, so a move can be noticed. */
let lastPositionKey = null;

/** Whether the world was mid-job the last time the clock was read. */
let worldIsSyncing = false;

/** Whether the job walk for the last clock move is still running. */
export function isSyncing() {
    return syncing;
}

/**
 * Waits until the job walk triggered by a clock move has finished.
 *
 * <p>Advancing the clock returns immediately and the walk runs on the server (one Advance Hour landing
 * on day 4 takes the training job over 14,880 clubs, which measured over five minutes on the owner's
 * database). So "advanced" and "the jobs have run" are two different moments, and anything that wants
 * to show what the jobs did — the training report, the league table, the finance page — has to wait for
 * the second one.
 *
 * @returns {Promise<boolean>} true if the walk finished, false if it was still running at the timeout
 */
export async function waitForSync(timeoutMs = 15 * 60 * 1000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        await syncGameClock();
        if (!syncing) return true;
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
    return false;
}

/** The game's own "now", in epoch milliseconds. */
function gameNow() {
    return serverAnchorMillis === null ? null : Date.now() + serverSkewMillis;
}

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: GAME_ZONE,
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
});

/** HH:MM:SS in the game's zone. Built from parts because some engines render midnight as "24". */
function formatGameTime(millis) {
    const parts = {};
    for (const part of timeFormatter.formatToParts(new Date(millis))) {
        parts[part.type] = part.value;
    }
    const hour = parts.hour === '24' ? '00' : parts.hour;
    return `${hour}:${parts.minute}:${parts.second}`;
}

/**
 * Season, week and day for a game instant, from the server's anchor.
 *
 * <p>The same arithmetic as `GameTimeline.positionAt`, done here so the day label changes the moment
 * the clock passes midnight rather than up to twenty seconds later. It is duplicated rather than
 * polled because a header that says "Day 3" over a clock reading 00:30 is precisely the bug this
 * rewrite exists to remove — the day and the time came from two places and had to be reconciled.
 */
function positionAt(millis) {
    if (!anchorDate) return null;
    // The game's local date, as YYYY-MM-DD, without depending on the device's zone.
    const localDate = new Intl.DateTimeFormat('en-CA', {
        timeZone: GAME_ZONE,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
    }).format(new Date(millis));
    const days = Math.round((Date.parse(`${localDate}T00:00:00Z`) - Date.parse(`${anchorDate}T00:00:00Z`)) / 86400000);
    return {
        season: Math.floor(days / DAYS_PER_SEASON) + 1,
        week: Math.floor(days / 7) % 12 + 1,
        day: ((days % 7) + 7) % 7 + 1
    };
}

async function syncGameClock() {
    try {
        const response = await authFetch('/api/game-clock');
        const data = await response.json();
        const gameMillis = Number(data.gameTimeEpochMillis);
        if (!Number.isFinite(gameMillis)) return;

        // Anchor on the server's clock, then let the display run locally between polls.
        serverAnchorMillis = gameMillis;
        serverSkewMillis = gameMillis - Date.now();
        anchorDate = typeof data.anchorDate === 'string' ? data.anchorDate.slice(0, 10) : null;

        matchDay = data.matchDay === true;
        syncing = data.syncing === true;
        kickoffHour = Number.isFinite(Number(data.kickoffHour)) ? Number(data.kickoffHour) : null;
        kickoffMinute = Number.isFinite(Number(data.kickoffMinute)) ? Number(data.kickoffMinute) : null;
        phaseLabel = data.phase || 'Season in progress';

        const position = positionAt(gameMillis);
        if (position) {
            seasonNumber = position.season;
            weekNumber = position.week;
            dayNumber = position.day;
        } else {
            seasonNumber = Number(data.seasonNumber || 1);
            weekNumber = Number(data.weekNumber || 1);
            dayNumber = Number(data.day || 1);
        }
        dayLabel = data.dayLabel || '';
        announceTheWorldMoved(data);
    } catch (err) {
        console.warn('Game clock sync failed:', err);
    }
}

/**
 * Says that the world moved, so the pages that show it can come back for it.
 *
 * <p><b>Two signals, and both of them are needed.</b>
 *
 * <ul>
 *   <li><b>The hour changed.</b> The matchday jobs fire on the hour, and the publish job is what moves
 *       a match out of "next matches" and into the results. The dashboard re-reads itself when the hour
 *       turns over (owner, 2026-10-21: "proveri takodje da kad publishing job okine da refreshuje
 *       dashboard i da mec iz next predje u recents").</li>
 *   <li><b>The world stopped syncing.</b> The job that publishes takes a couple of minutes - it plays
 *       four hundred and eighty fixtures - and the hour turns over while it is still running. The
 *       server says so: {@code syncing} is true while a job is in the body, and the response exists
 *       for exactly this, "the browser knows to come back".</li>
 * </ul>
 *
 * <p>So the page is told twice: once when the hour turns over, and once when the job that was running
 * has finished.
 */
function announceTheWorldMoved(data) {
    const positionKey = `${seasonNumber}/${weekNumber}/${dayNumber}/${data.hour}`;
    const moved = positionKey !== lastPositionKey;
    if (moved) {
        lastPositionKey = positionKey;
    }

    if (data.syncing === true) {
        worldIsSyncing = true;
    } else if (worldIsSyncing) {
        worldIsSyncing = false;
        announce('sync-finished');
    }
    if (moved) {
        announce('position-changed');
    }
}

/** Tells the pages that the world moved. */
function announce(reason) {
    window.dispatchEvent(new CustomEvent('fm-world-moved', {
        detail: { reason, season: seasonNumber, week: weekNumber, day: dayNumber }
    }));
}

function updateLiveClock() {
    const millis = gameNow();
    const timeStr = millis === null ? '--:--:--' : formatGameTime(millis);

    // Game state only, per the owner (2026-09-28): the real calendar date is not a category in this
    // world. The day is a position in the seven-day cycle and is derived from the clock, so it and
    // the time beside it are the same instant by construction.
    // Short header form: season, week, day. No day-name, no phase sentence.
    const seasonWeek = `S${seasonNumber}/W${weekNumber}/D${dayNumber}`;

    const timeEl = document.getElementById('clock-time');
    const dateEl = document.getElementById('clock-date');
    const phaseEl = document.getElementById('clock-phase');
    if (timeEl) timeEl.textContent = timeStr;
    if (dateEl) dateEl.textContent = seasonWeek;
    if (phaseEl) phaseEl.textContent = phaseLabel;

    document.querySelectorAll('.live-clock-time-mobile').forEach(el => { el.textContent = timeStr; });
    document.querySelectorAll('.live-clock-date-mobile').forEach(el => { el.textContent = seasonWeek; });
    document.querySelectorAll('.bottom-clock-date').forEach(el => { el.textContent = seasonWeek; });
}

syncGameClock();
setInterval(syncGameClock, 20 * 1000);
updateLiveClock();
setInterval(updateLiveClock, 1000);

/**
 * Re-reads the clock now, rather than waiting for the next poll.
 *
 * <p>Called after anything that moves the world — an advance, a week rollover, an admin offset
 * change. Without it the header kept showing the position the clock was in before the button was
 * pressed, which reads as the button having done nothing.
 */
export async function refreshGameClock() {
    await syncGameClock();
    updateLiveClock();
}

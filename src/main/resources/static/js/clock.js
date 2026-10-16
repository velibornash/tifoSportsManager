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
        if (!response.ok) return;
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

        // Publish the clock so the dashboard can gate "Watch Your Match" on the real kickoff hour
        // instead of a week boolean. Set here because this is the one place the server clock is
        // already being read every tick.
        window.__fmWatchStatus = {
            available: isWatchable(data),
            reason: watchReason(data),
            kickoffHour: data.kickoffHour,
            day: data.day,
            hour: data.hour
        };
    } catch (err) {
        console.warn('Game clock sync failed:', err);
    }
}

function updateLiveClock() {
    const millis = gameNow();
    const timeStr = millis === null ? '--:--:--' : formatGameTime(millis);

    // Game state only, per the owner (2026-09-28): the real calendar date is not a category in this
    // world. The day is a position in the seven-day cycle and is derived from the clock, so it and
    // the time beside it are the same instant by construction.
    const dayText = dayLabel ? ` · Day ${dayNumber} (${dayLabel})` : ` · Day ${dayNumber}`;
    const seasonWeek = `Season ${seasonNumber} • Week ${weekNumber}${dayText}`;

    const timeEl = document.getElementById('clock-time');
    const dateEl = document.getElementById('clock-date');
    const phaseEl = document.getElementById('clock-phase');
    if (timeEl) timeEl.textContent = timeStr;
    if (dateEl) dateEl.textContent = seasonWeek;
    if (phaseEl) phaseEl.textContent = phaseLabel;

    document.querySelectorAll('.live-clock-time-mobile').forEach(el => { el.textContent = timeStr; });
    document.querySelectorAll('.live-clock-date-mobile').forEach(el => { el.textContent = seasonWeek; });
}

syncGameClock();
setInterval(syncGameClock, 20 * 1000);
updateLiveClock();
setInterval(updateLiveClock, 1000);

/**
 * Whether the clock says a match can be watched right now (owner, 2026-09-29).
 *
 * <p>Mirrors the server rule exactly - a match day, and the hour at or past kickoff. The server
 * remains the authority and /api/watch/status is what the button ultimately follows; this exists so
 * the dashboard has the answer between ticks rather than rendering a stale enabled button.
 */
function isWatchable(clock) {
    if (!clock || clock.matchDay !== true) return false;
    if (clock.kickoffHour === null || clock.kickoffHour === undefined) return false;
    const kickoffMinute = clock.kickoffMinute === null || clock.kickoffMinute === undefined
        ? 0 : Number(clock.kickoffMinute);
    // Day 1 is a 20:45 slot. A kickoff inside the hour is not watchable until that hour has passed,
    // because the engine resolves matches to the hour and there is nothing to watch at 20:30.
    return kickoffMinute === 0
        ? Number(clock.hour) >= Number(clock.kickoffHour)
        : Number(clock.hour) > Number(clock.kickoffHour);
}

/** The template's kickoff as HH:MM. Day 1 is a 20:45 slot and printing ":00" said 20:00. */
function kickoffLabel(clock) {
    const minute = clock.kickoffMinute === null || clock.kickoffMinute === undefined ? 0 : Number(clock.kickoffMinute);
    return `${String(clock.kickoffHour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function watchReason(clock) {
    if (!clock) return 'The game clock is not available.';
    if (clock.matchDay !== true) return 'Not a match day.';
    if (clock.kickoffHour === null || clock.kickoffHour === undefined) return 'No kickoff on this day.';
    const kickoffMinute = clock.kickoffMinute === null || clock.kickoffMinute === undefined ? 0 : Number(clock.kickoffMinute);
    const hour = Number(clock.hour);
    const beforeKickoff = !(kickoffMinute === 0
        ? hour >= Number(clock.kickoffHour)
        : hour > Number(clock.kickoffHour));
    if (beforeKickoff) {
        return `Kickoff is at ${kickoffLabel(clock)}.`;
    }
    return 'Your match is ready to watch.';
}

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

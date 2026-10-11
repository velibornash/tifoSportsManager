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

let seasonNumber = 1;
let weekNumber = 1;
let dayNumber = 1;
let dayLabel = '';
let phaseLabel = "Season in progress";
/**
 * The game's hour, and nothing else, is what the header shows (owner, 2026-10-11).
 *
 * <p>It used to render real Belgrade time, on the reasoning that "Advance Hour" moves the season and
 * not the wall clock. That was true and it was the wrong thing to show: every fixture kickoff, every
 * job trigger and every deadline in this game is expressed in game hours, so the one number a manager
 * acts on was the one number the header refused to give them. It cost real confusion — a Day 1 Cup tie
 * reading "18:00" while the clock stood at 23 was impossible to reason about until you knew the first
 * was a kickoff time and the second was your laptop.
 *
 * <p>So there is no wall clock here any more, and {@code /api/server-time} is no longer read: it
 * existed only to correct that clock for drift. The game clock counts whole hours on purpose (see
 * {@link isWatchable}), so it is rendered as an hour with ":00" and no seconds. A second hand would be
 * inventing precision the simulation does not have.
 */
let gameHour = null;

async function syncGameClock() {
    try {
        const response = await authFetch('/api/game-clock');
        const data = await response.json();
        seasonNumber = Number(data.seasonNumber || 1);
        weekNumber = Number(data.weekNumber || 1);
        dayNumber = Number(data.day || 1);
        dayLabel = data.dayLabel || '';
        gameHour = Number.isFinite(Number(data.hour)) ? Number(data.hour) : null;
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
        phaseLabel = data.phase || "Season in progress";
    } catch (err) {
        console.warn("Game clock sync failed:", err);
    }
}

function updateLiveClock() {
    const timeStr = gameHour === null
        ? '--:--'
        : `${String(gameHour).padStart(2, '0')}:00`;

    // Game state only, per the owner (2026-09-28): the real calendar date was removed. It sat
    // directly next to "Day 3" and read as though the game day were a weekday, which it is not - the
    // day is a position in the seven-day cycle, so the same season would show different days
    // depending on when it was started.
    //
    // The day label is appended only when the server sent one, so a clock payload without it
    // degrades to the shorter text instead of rendering "Day undefined".
    const dayText = dayLabel ? ` · Day ${dayNumber} (${dayLabel})` : ` · Day ${dayNumber}`;
    const seasonWeek = `Season ${seasonNumber} • Week ${weekNumber}${dayText}`;

    const timeEl = document.getElementById('clock-time');
    const dateEl = document.getElementById('clock-date');
    const phaseEl = document.getElementById('clock-phase');
    if (timeEl) timeEl.textContent = timeStr;
    if (dateEl) dateEl.textContent = seasonWeek;
    if (phaseEl) phaseEl.textContent = phaseLabel;

    const timeMobile = document.getElementById('clock-time-m');
    const dateMobile = document.getElementById('clock-date-m');
    if (timeMobile) timeMobile.textContent = timeStr;
    if (dateMobile) dateMobile.textContent = seasonWeek;
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
    // The clock counts whole hours on purpose, so a slot inside an hour (day 1 is 20:45) becomes
    // available on the next hour tick rather than pretending it can be resolved to the minute.
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
    const kickoffMinute = clock.kickoffMinute === null || clock.kickoffMinute === undefined
        ? 0 : Number(clock.kickoffMinute);
    const minute = Number(clock.minute || 0);
    const hour = Number(clock.hour);
    const beforeKickoff = !(kickoffMinute === 0
        ? hour >= Number(clock.kickoffHour)
        : hour > Number(clock.kickoffHour));
    if (beforeKickoff) {
        return `Kickoff is at ${kickoffLabel(clock)}.`;
    }
    return 'Your match is ready to watch.';
}

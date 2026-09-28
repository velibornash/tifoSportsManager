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

let serverOffsetMs = 0;
let seasonNumber = 1;
let weekNumber = 1;
let dayNumber = 1;
let dayLabel = '';
let phaseLabel = "Season in progress";

async function syncWithServerTime() {
    try {
        const response = await authFetch('/api/server-time');
        const data = await response.json();
        const serverTimestamp = parseInt(data.timestamp, 10);
        serverOffsetMs = Date.now() - serverTimestamp;
    } catch (err) {
        console.warn("Time sync failed:", err);
        serverOffsetMs = 0;
    }
}

async function syncGameClock() {
    try {
        const response = await authFetch('/api/game-clock');
        const data = await response.json();
        seasonNumber = Number(data.seasonNumber || 1);
        weekNumber = Number(data.weekNumber || 1);
        dayNumber = Number(data.day || 1);
        dayLabel = data.dayLabel || '';
        phaseLabel = data.phase || "Season in progress";
    } catch (err) {
        console.warn("Game clock sync failed:", err);
    }
}

function updateLiveClock() {
    const nowMs = Date.now() - serverOffsetMs;
    const now = new Date(nowMs);

    const timeStr = now.toLocaleTimeString('sr-RS', {
        timeZone: 'Europe/Belgrade',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    });


    // Game state only, per the owner (2026-09-28): the real calendar date was removed. It sat
    // directly next to "Day 3" and read as though the game day were a weekday, which it is not - the
    // day is a position in the seven-day cycle, so the same season would show different days
    // depending on when it was started. The wall clock above is still the real time, which is what
    // the date was for.
    //
    // The day label is appended only when the server sent one, so a clock payload without it
    // degrades to the shorter text instead of rendering "Day undefined".
    const dayText = dayLabel ? ` \u00b7 Day ${dayNumber} (${dayLabel})` : ` \u00b7 Day ${dayNumber}`;
    const seasonWeek = `Season ${seasonNumber} \u2022 Week ${weekNumber}${dayText}`;

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

syncWithServerTime();
syncGameClock();
setInterval(syncWithServerTime, 5 * 60 * 1000);
setInterval(syncGameClock, 20 * 1000);
updateLiveClock();
setInterval(updateLiveClock, 1000);

// /js/demo.js
import { escapeHtml } from './ui/escape.js';
import { authFetch } from './auth.js';
import { refreshGameClock, waitForSync } from './clock.js';

let currentUserTeamId = null;
let advanceWeekPollTimer = null;

const DASHBOARD_FLOW_FLASH_KEY = 'dashboardSeasonFlowFlash';
const DASHBOARD_ADVANCE_WEEK_JOB_KEY = 'dashboardAdvanceWeekJob';

function resetButton(button, label) {
    if (!button) return;
    button.disabled = false;
    button.textContent = label;
}


function getButtonDefaultLabel(button, fallback) {
    return button?.dataset?.label || fallback;
}

function persistDashboardFlowFlash(message, tone = 'info') {
    if (!message) return;
    try {
        sessionStorage.setItem(DASHBOARD_FLOW_FLASH_KEY, JSON.stringify({ message, tone }));
    } catch (err) {
        console.warn('Failed to persist dashboard flow message:', err);
    }
}

function persistAdvanceWeekJob(jobId) {
    if (!jobId) return;
    try {
        sessionStorage.setItem(DASHBOARD_ADVANCE_WEEK_JOB_KEY, jobId);
    } catch (err) {
        console.warn('Failed to persist advance week job:', err);
    }
}

function clearAdvanceWeekJob() {
    try {
        sessionStorage.removeItem(DASHBOARD_ADVANCE_WEEK_JOB_KEY);
    } catch (err) {
        console.warn('Failed to clear advance week job:', err);
    }
}

function hasPersistedAdvanceWeekJob() {
    try {
        return !!sessionStorage.getItem(DASHBOARD_ADVANCE_WEEK_JOB_KEY);
    } catch (err) {
        console.warn('Failed to read advance week job:', err);
        return false;
    }
}

function setSeasonFlowStatus(message, tone = 'info', options = {}) {
    const host = document.getElementById('dashboard-season-flow-status');
    if (host) {
        host.className = `fm-season-flow-status is-${tone}`;
        host.textContent = message;
    }
    if (options.persist) {
        persistDashboardFlowFlash(message, tone);
    }
}

async function handleSeasonFlowResponse(data, button, defaultLabel) {
    const action = data?.action;

    if (action === 'ROUND_NOT_COMPLETE') {
        stopAdvanceWeekPolling();
        clearAdvanceWeekJob();
        resetButton(button, defaultLabel);
        const remaining = Number(data?.remainingFixtures || 0);
        setSeasonFlowStatus(data.message || `Current round still has ${remaining} unfinished fixture${remaining === 1 ? '' : 's'}.`, 'warning');
        return;
    }

    if (action === 'WEEK_ADVANCE_RUNNING') {
        persistAdvanceWeekJob(data.jobId);
        if (button) {
            button.disabled = true;
            button.textContent = 'Advancing week...';
        }
        setSeasonFlowStatus(data.message || 'Advancing week in the background.', 'info');
        return;
    }

    if (action === 'WEEK_ADVANCED') {
        stopAdvanceWeekPolling();
        clearAdvanceWeekJob();
        const message = data.message || 'Calendar advanced to the next week.';
        setSeasonFlowStatus(message, 'success', { persist: true });
        const shouldRefreshDashboard = !!button?.closest('.fm-dashboard-view') && typeof window.loadDashboard === 'function';
        if (shouldRefreshDashboard) {
            // The header clock too: Advance Week used to move the week counter and leave the clock
            // reading the hour the button was pressed at, which is the same split screen the
            // day/hour buttons had.
            await refreshGameClock();
            window.loadDashboard();
        } else {
            resetButton(button, defaultLabel);
        }
        return;
    }

    if (action === 'WEEK_ADVANCE_FAILED') {
        stopAdvanceWeekPolling();
        clearAdvanceWeekJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Advance week failed.', 'error');
        return;
    }

    resetButton(button, defaultLabel);
    if (data?.message) {
        setSeasonFlowStatus(data.message, 'info');
    }
}

function stopAdvanceWeekPolling() {
    if (advanceWeekPollTimer) {
        window.clearInterval(advanceWeekPollTimer);
        advanceWeekPollTimer = null;
    }
}

async function pollAdvanceWeekStatus(buttonId, fallbackLabel) {
    const button = buttonId ? document.getElementById(buttonId) : null;
    const defaultLabel = getButtonDefaultLabel(button, fallbackLabel || '📅 Advance Week');

    try {
        const response = await authFetch('/simulation/week/advance/status');
        const data = await response.json().catch(() => null);
        if (!response.ok || !data) {
            throw new Error(data?.message || data?.error || 'Failed to fetch week advance status.');
        }

        handleSeasonFlowResponse(data, button, defaultLabel);
    } catch (error) {
        console.error('Failed to poll advance week status:', error);
        stopAdvanceWeekPolling();
        clearAdvanceWeekJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(error.message || 'Failed to fetch week advance status.', 'error');
    }
}

function startAdvanceWeekPolling(buttonId, fallbackLabel) {
    stopAdvanceWeekPolling();
    pollAdvanceWeekStatus(buttonId, fallbackLabel);
    advanceWeekPollTimer = window.setInterval(() => {
        pollAdvanceWeekStatus(buttonId, fallbackLabel);
    }, 1500);
}

async function runSeasonFlowAction(buttonId, requestUrl, loadingLabel, fallbackLabel, requestOptions = {}) {
    const button = document.getElementById(buttonId);
    if (!button) {
        console.error(`Season flow button not found: ${buttonId}`);
        return;
    }

    const defaultLabel = getButtonDefaultLabel(button, fallbackLabel);
    button.disabled = true;
    button.textContent = loadingLabel;

    try {
        const response = await authFetch(requestUrl, requestOptions);
        const data = await response.json().catch(() => null);

        if (!response.ok && !data?.action) {
            throw new Error(data?.message || data?.error || 'Server error');
        }

        handleSeasonFlowResponse(data || {}, button, defaultLabel);
        if (data?.action === 'WEEK_ADVANCE_RUNNING') {
            startAdvanceWeekPolling(buttonId, fallbackLabel);
        }
    } catch (error) {
        console.error(`Failed season flow action for ${requestUrl}:`, error);
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(error.message || 'Season flow action failed.', 'error');
    }
}

window.addEventListener('load', async () => {
    const token = sessionStorage.getItem('token');
    if (!token) {
        console.warn('No token on load - redirecting');
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();
        currentUserTeamId = user.footballTeamId || user.teamId;
    } catch (err) {
        console.error('Failed to load /auth/me:', err);
    }

    if (hasPersistedAdvanceWeekJob()) {
        startAdvanceWeekPolling('advance-week-btn', '📅 Advance Week');
    }
});

async function advanceWeekTest() {
    await runSeasonFlowAction(
        'advance-week-btn',
        '/simulation/week/advance',
        'Advancing week...',
        '📅 Advance Week',
        { method: 'POST' }
    );
}

/**
 * Advances the game clock by one day or one hour (owner, 2026-09-28, reworked 2026-10-11).
 *
 * <p>These go through /api/game-clock/advance rather than /simulation/week/advance, because the
 * clock is what makes scheduled jobs due. The server moves the offset and synchronises in the same
 * call, so there is no window in which the clock has moved and the jobs have not run.
 *
 * <p><b>Everything the jobs touched is reloaded afterwards.</b> The clock, the header, the league
 * table, the milestones, the recent matches, the next match and the training ticker all read state
 * the jobs just changed. This used to update the status line and nothing else — `refreshGameClock()`
 * was called under a `typeof === 'function'` guard and that function existed nowhere in the
 * repository, so the guard could never pass. Pressing Advance Day moved the world and left the screen
 * describing the world as it was.
 */
async function advanceClockTest(unit, buttonId, label) {
    const button = buttonId ? document.getElementById(buttonId) : null;
    const defaultLabel = getButtonDefaultLabel(button, label);
    if (button) {
        button.disabled = true;
        button.textContent = 'Advancing...';
    }
    try {
        const response = await authFetch(`/api/game-clock/advance?unit=${unit}&amount=1`, { method: 'POST' });
        const data = await response.json();
        // The span totals, not `data.jobs`: a 24-hour advance reports only the twenty-fourth hour's
        // count under `jobs`, so advancing a day that ran training, finance and two matchdays said
        // "2 job(s) ran". The whole walk is under `advance`.
        const jobs = data.advance || data.jobs || {};
        const ranKeys = Array.isArray(jobs.jobsRan) ? jobs.jobsRan : [];
        const failedKeys = Array.isArray(jobs.jobsFailed) ? jobs.jobsFailed : [];
        const hoursWalked = Number(jobs.hoursAdvanced ?? 0);

        const outcome = `Advanced to season ${data.seasonNumber}, week ${data.weekNumber}, `
            + `day ${data.day} (${data.dayLabel}) at ${String(data.hour).padStart(2, '0')}:`
            + `${String(data.minute ?? 0).padStart(2, '0')}. `
            + (hoursWalked > 1 ? `Walked ${hoursWalked} game hours — ` : '')
            + (ranKeys.length
                ? `${ranKeys.join(', ')}.`
                : 'No jobs were due.');

        setSeasonFlowStatus(outcome, failedKeys.length ? 'error' : 'success', { persist: true });
        await refreshAfterClockMove(data);
        return data;
    } catch (err) {
        setSeasonFlowStatus(err.message, 'error');
    } finally {
        resetButton(button, defaultLabel);
    }
}

/**
 * Re-reads everything an advance can have changed (owner, 2026-10-11).
 *
 * <p>One place, called after every movement of the world, so "the clock moved but the screen did
 * not" stops being a thing that depends on which button was pressed. The dashboard rebuilds its own
 * stats, milestones, recent matches, next match and ticker; the header re-reads the clock.
 *
 * <p><b>It waits for the jobs first.</b> Advancing returns as soon as the clock has moved; the walk
 * that runs the jobs that fell due runs behind it. Refreshing at that instant shows a world the jobs
 * have not touched yet - a training report for a day that has not been trained, a league table missing
 * the round that was just played - and the manager has no way to tell that from "the job is broken".
 */
async function refreshAfterClockMove(data) {
    const dispatched = data?.jobs?.dispatched === true;
    if (dispatched) {
        setSeasonFlowStatus('Clock moved. Running the jobs it made due...', 'info');
    }
    let settled = true;
    try {
        if (dispatched) {
            settled = await waitForSync();
            if (!settled) {
                setSeasonFlowStatus(
                    'Clock moved. Jobs are still running in the background — dashboard will '
                    + 'refresh automatically when they finish.',
                    'info', { persist: true });
                // Keep polling in the background and refresh the dashboard when the walk settles.
                (async () => {
                    await waitForSync(30 * 60 * 1000);
                    // Switch to the dashboard view, then full reload so every view re-reads
                    // fresh data from the server.
                    if (typeof window.loadDashboard === 'function') {
                        try { await window.loadDashboard(); } catch (e) { /* ignore */ }
                    }
                    await new Promise(resolve => setTimeout(resolve, 500));
                    window.location.reload();
                })();
            }
        }
        await refreshGameClock();
    } catch (err) {
        console.warn('Clock refresh after advance failed:', err);
    }
    if (typeof window.loadDashboard === 'function' && document.querySelector('.fm-dashboard-view')) {
        try {
            await window.loadDashboard();
        } catch (err) {
            console.warn('Dashboard refresh after advance failed:', err);
        }
    }
    // Full reload after jobs settle so all views re-read fresh data, landing on dashboard.
    // Only when they actually settled within the wait; otherwise the background poll above handles it.
    if (dispatched && settled) {
        await new Promise(resolve => setTimeout(resolve, 500));
        if (typeof window.loadDashboard === 'function') {
            try { await window.loadDashboard(); } catch (e) { /* ignore */ }
        }
        window.location.reload();
    }
}

async function advanceDayTest() {
    return advanceClockTest('day', 'advance-day-btn', '📆 Advance Day');
}

async function advanceHourTest() {
    return advanceClockTest('hour', 'advance-hour-btn', '⏩ Advance Hour');
}

window.advanceDayTest = advanceDayTest;
window.advanceHourTest = advanceHourTest;

window.advanceWeekTest = advanceWeekTest;

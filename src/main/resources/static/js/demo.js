// /js/demo.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;
let roundSimulationPollTimer = null;
let advanceWeekPollTimer = null;
let weekPreparationPollTimer = null;

const DASHBOARD_FLOW_FLASH_KEY = 'dashboardSeasonFlowFlash';
const DASHBOARD_ROUND_SIM_JOB_KEY = 'dashboardRoundSimulationJob';
const DASHBOARD_ADVANCE_WEEK_JOB_KEY = 'dashboardAdvanceWeekJob';
const DASHBOARD_WEEK_PREP_JOB_KEY = 'dashboardWeekPreparationJob';
const DASHBOARD_WEEK_PREP_TARGET_KEY = 'dashboardWeekPreparationTarget';
const DASHBOARD_WEEK_CONSUMED_KEY = 'dashboardWeekConsumed';

function resetButton(button, label) {
    if (!button) return;
    button.disabled = false;
    button.textContent = label;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

function showModal(title, bodyHtml) {
    document.getElementById('season-flow-modal')?.remove();

    const overlay = document.createElement('div');
    overlay.id = 'season-flow-modal';
    overlay.style.cssText = [
        'position:fixed',
        'inset:0',
        'display:flex',
        'align-items:center',
        'justify-content:center',
        'padding:20px',
        'background:rgba(0,0,0,0.72)',
        'z-index:9999'
    ].join(';');

    overlay.innerHTML = `
        <div style="width:min(760px,100%);background:linear-gradient(180deg,#10233a,#0a1626);border:1px solid rgba(255,255,255,0.14);border-radius:18px;box-shadow:0 20px 60px rgba(0,0,0,0.45);color:#eef6ff;overflow:hidden;">
            <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;padding:16px 18px;border-bottom:1px solid rgba(255,255,255,0.1);">
                <div>
                    <div style="font-size:0.78rem;letter-spacing:0.08em;text-transform:uppercase;color:#87bfff;opacity:0.86;">Season transition</div>
                    <div style="font-size:1.18rem;font-weight:700;">${escapeHtml(title)}</div>
                </div>
                <button type="button" id="season-flow-modal-close" style="border:none;background:rgba(255,255,255,0.08);color:#fff;padding:8px 12px;border-radius:10px;cursor:pointer;">Close</button>
            </div>
            <div style="padding:18px;max-height:min(70vh,720px);overflow:auto;">${bodyHtml}</div>
        </div>
    `;

    const close = () => overlay.remove();
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    document.body.appendChild(overlay);
    document.getElementById('season-flow-modal-close')?.addEventListener('click', close);
}

function renderMoveList(items, emptyLabel, keyLabel, valueLabel) {
    if (!Array.isArray(items) || items.length === 0) {
        return `<div style="opacity:0.75;">${escapeHtml(emptyLabel)}</div>`;
    }

    return items.map(item => `
        <div style="padding:10px 12px;border-radius:12px;background:rgba(255,255,255,0.06);border-left:3px solid rgba(135,191,255,0.7);margin-bottom:8px;">
            <div style="font-weight:700;">${escapeHtml(item[keyLabel])}</div>
            <div style="font-size:0.92rem;opacity:0.8;">${escapeHtml(item[valueLabel])}</div>
        </div>
    `).join('');
}

function showPlayoffSummary(summary, message) {
    const playoffResults = Array.isArray(summary?.playoffResults) ? summary.playoffResults : [];
    const directPromotions = Array.isArray(summary?.directPromotions) ? summary.directPromotions : [];
    const directRelegations = Array.isArray(summary?.directRelegations) ? summary.directRelegations : [];

    const playoffHtml = playoffResults.length === 0
        ? '<div style="opacity:0.75;">No playoff fixtures were generated.</div>'
        : playoffResults.map(result => `
            <div style="padding:12px 14px;border-radius:12px;background:rgba(255,255,255,0.06);border-left:3px solid #ffd966;margin-bottom:8px;">
                <div style="font-weight:700;">${escapeHtml(result.homeTeam)} ${escapeHtml(result.homeGoals)} - ${escapeHtml(result.awayGoals)} ${escapeHtml(result.awayTeam)}</div>
                <div style="font-size:0.92rem;opacity:0.84;">Winner: ${escapeHtml(result.winner)}</div>
            </div>
        `).join('');

    const bodyHtml = `
        <div style="display:grid;gap:18px;">
            <div style="padding:12px 14px;border-radius:12px;background:rgba(93,168,255,0.14);border:1px solid rgba(135,191,255,0.25);line-height:1.5;">
                ${escapeHtml(message || 'Playoff week finished.')}
            </div>
            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px;">
                <section>
                    <div style="font-size:0.8rem;text-transform:uppercase;letter-spacing:0.08em;opacity:0.7;margin-bottom:10px;">Direct promotions</div>
                    ${renderMoveList(directPromotions, 'No direct promotions.', 'team', 'fromLeague')}
                </section>
                <section>
                    <div style="font-size:0.8rem;text-transform:uppercase;letter-spacing:0.08em;opacity:0.7;margin-bottom:10px;">Direct relegations</div>
                    ${renderMoveList(directRelegations, 'No direct relegations.', 'team', 'toLeague')}
                </section>
            </div>
            <section>
                <div style="font-size:0.8rem;text-transform:uppercase;letter-spacing:0.08em;opacity:0.7;margin-bottom:10px;">Playoff results</div>
                ${playoffHtml}
            </section>
            <div style="padding-top:4px;font-size:0.94rem;opacity:0.82;">Next click on <strong>Play Your Match</strong> starts the friendly week.</div>
        </div>
    `;

    showModal(`Season ${escapeHtml(summary?.seasonYear ?? '')} playoff summary`, bodyHtml);
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

function persistRoundSimulationJob(jobId) {
    if (!jobId) return;
    try {
        sessionStorage.setItem(DASHBOARD_ROUND_SIM_JOB_KEY, jobId);
    } catch (err) {
        console.warn('Failed to persist round simulation job:', err);
    }
}

function clearRoundSimulationJob() {
    try {
        sessionStorage.removeItem(DASHBOARD_ROUND_SIM_JOB_KEY);
    } catch (err) {
        console.warn('Failed to clear round simulation job:', err);
    }
}

function hasPersistedRoundSimulationJob() {
    try {
        return !!sessionStorage.getItem(DASHBOARD_ROUND_SIM_JOB_KEY);
    } catch (err) {
        console.warn('Failed to read round simulation job:', err);
        return false;
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

function persistWeekPreparationJob(jobId) {
    if (!jobId) return;
    try {
        sessionStorage.setItem(DASHBOARD_WEEK_PREP_JOB_KEY, jobId);
    } catch (err) {
        console.warn('Failed to persist week preparation job:', err);
    }
}

function clearWeekPreparationJob() {
    try {
        sessionStorage.removeItem(DASHBOARD_WEEK_PREP_JOB_KEY);
    } catch (err) {
        console.warn('Failed to clear week preparation job:', err);
    }
}

function hasPersistedWeekPreparationJob() {
    try {
        return !!sessionStorage.getItem(DASHBOARD_WEEK_PREP_JOB_KEY);
    } catch (err) {
        console.warn('Failed to read week preparation job:', err);
        return false;
    }
}

function persistWeekPreparationTarget(target) {
    try {
        if (target) {
            sessionStorage.setItem(DASHBOARD_WEEK_PREP_TARGET_KEY, target);
        } else {
            sessionStorage.removeItem(DASHBOARD_WEEK_PREP_TARGET_KEY);
        }
    } catch (err) {
        console.warn('Failed to persist week preparation target:', err);
    }
}

function readWeekPreparationTarget() {
    try {
        return sessionStorage.getItem(DASHBOARD_WEEK_PREP_TARGET_KEY) || null;
    } catch (err) {
        console.warn('Failed to read week preparation target:', err);
        return null;
    }
}

function persistWeekConsumed(consumed) {
    try {
        if (consumed) {
            sessionStorage.setItem(DASHBOARD_WEEK_CONSUMED_KEY, 'true');
        } else {
            sessionStorage.removeItem(DASHBOARD_WEEK_CONSUMED_KEY);
        }
    } catch (err) {
        console.warn('Failed to persist week consumed flag:', err);
    }
}

function isWeekConsumed() {
    try {
        return sessionStorage.getItem(DASHBOARD_WEEK_CONSUMED_KEY) === 'true';
    } catch (err) {
        console.warn('Failed to read week consumed flag:', err);
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

function renderLeagueResults(leagueResults) {
    if (!Array.isArray(leagueResults) || leagueResults.length === 0) {
        return '<div style="opacity:0.75;">No other league fixtures needed simulation in this round.</div>';
    }

    return leagueResults.map(result => `
        <div style="padding:12px 14px;border-radius:12px;background:rgba(255,255,255,0.06);border-left:3px solid rgba(135,191,255,0.7);margin-bottom:8px;">
            <div style="font-weight:700;">${escapeHtml(result.league || 'League')}</div>
            <div style="font-size:0.92rem;opacity:0.84;">Remaining before: ${escapeHtml(result.remainingBefore ?? 0)} · simulated: ${escapeHtml(result.simulated ?? 0)} · left now: ${escapeHtml(result.remainingAfter ?? 0)}</div>
        </div>
    `).join('');
}

function showRoundSimulationSummary(data) {
    const bodyHtml = `
        <div style="display:grid;gap:18px;">
            <div style="padding:12px 14px;border-radius:12px;background:rgba(93,168,255,0.14);border:1px solid rgba(135,191,255,0.25);line-height:1.5;">
                ${escapeHtml(data?.message || 'Current round simulation completed.')}
            </div>
            <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;">
                <div style="padding:12px 14px;border-radius:12px;background:rgba(255,255,255,0.06);">
                    <div style="font-size:0.78rem;opacity:0.72;text-transform:uppercase;letter-spacing:0.08em;">Fixtures simulated</div>
                    <div style="font-size:1.35rem;font-weight:700;">${escapeHtml(data?.simulatedCount ?? 0)}</div>
                </div>
                <div style="padding:12px 14px;border-radius:12px;background:rgba(255,255,255,0.06);">
                    <div style="font-size:0.78rem;opacity:0.72;text-transform:uppercase;letter-spacing:0.08em;">Leagues processed</div>
                    <div style="font-size:1.35rem;font-weight:700;">${escapeHtml(data?.leaguesProcessed ?? 0)}</div>
                </div>
            </div>
            <section>
                <div style="font-size:0.8rem;text-transform:uppercase;letter-spacing:0.08em;opacity:0.7;margin-bottom:10px;">League breakdown</div>
                ${renderLeagueResults(data?.leagueResults)}
            </section>
        </div>
    `;

    showModal('Current round simulation', bodyHtml);
}

function handleSeasonFlowResponse(data, button, defaultLabel) {
    const action = data?.action;

    if (action === 'SHOW_PLAYOFF_SUMMARY') {
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Playoff week finished.', 'success');
        showPlayoffSummary(data.summary, data.message);
        return;
    }

    if (action === 'START_MATCH') {
        setSeasonFlowStatus(data.message || 'Opening your live match...', 'success');
        const matchId = data.matchId;
        const dbMatchId = data.dbMatchId;
        if (!matchId) {
            throw new Error('Missing matchId in response');
        }
        const query = new URLSearchParams({ matchId, mode: 'live' });
        if (dbMatchId) query.set('dbMatchId', dbMatchId);
        window.location.href = `/realisticDemo.html?${query.toString()}`;
        return;
    }

    if (action === 'WEEK_PREPARATION_RUNNING') {
        persistWeekPreparationJob(data.jobId);
        if (button) {
            button.disabled = true;
            button.textContent = 'Preparing week...';
        }
        const processed = Number(data?.processedLeagues || 0);
        const total = Number(data?.leaguesProcessed || 0);
        const leagueLabel = data?.currentLeague ? ` ${data.currentLeague}` : '';
        const progressLabel = total > 0 ? ` (${processed}/${total})` : '';
        setSeasonFlowStatus(data.message || `Preparing current week${leagueLabel}${progressLabel}.`, 'info');
        return;
    }

    if (action === 'WEEK_PREPARED') {
        stopWeekPreparationPolling();
        clearWeekPreparationJob();
        persistWeekConsumed(true);
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Current week prepared.', 'success');
        const target = readWeekPreparationTarget();
        persistWeekPreparationTarget(null);
        if (target === 'match') {
            if (data.userMatchId) {
                const query = new URLSearchParams({ matchId: data.userMatchId, mode: 'live' });
                if (data.dbMatchId) query.set('dbMatchId', data.dbMatchId);
                window.location.href = `/realisticDemo.html?${query.toString()}`;
            } else {
                setSeasonFlowStatus('No scheduled match exists for your club in the current week.', 'warning');
            }
            return;
        }
        if (target === 'results') {
            window.location.href = `/simulateAllResults.html`;
            return;
        }
        return;
    }

    if (action === 'WEEK_PREPARATION_FAILED') {
        stopWeekPreparationPolling();
        clearWeekPreparationJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Preparing the current week failed.', 'error');
        return;
    }

    if (action === 'NO_MATCH_CURRENT_WEEK') {
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'No scheduled match exists for your club in the current week.', 'warning');
        return;
    }

    if (action === 'ROUND_SIMULATED') {
        stopRoundSimulationPolling();
        clearRoundSimulationJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Other fixtures for the current round were simulated.', 'success');
        showRoundSimulationSummary(data);
        return;
    }

    if (action === 'ROUND_SIMULATION_RUNNING') {
        persistRoundSimulationJob(data.jobId);
        if (button) {
            button.disabled = true;
            button.textContent = 'Simulation running...';
        }
        const processed = Number(data?.processedLeagues || 0);
        const total = Number(data?.leaguesProcessed || 0);
        const leagueLabel = data?.currentLeague ? ` ${data.currentLeague}` : '';
        const progressLabel = total > 0 ? ` (${processed}/${total})` : '';
        setSeasonFlowStatus(data.message || `Simulating other fixtures in background${leagueLabel}${progressLabel}.`, 'info');
        return;
    }

    if (action === 'ROUND_SIMULATION_FAILED') {
        stopRoundSimulationPolling();
        clearRoundSimulationJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Background round simulation failed.', 'error');
        return;
    }

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
        persistWeekConsumed(false);
        const message = data.message || 'Calendar advanced to the next week.';
        setSeasonFlowStatus(message, 'success', { persist: true });
        const shouldRefreshDashboard = !!button?.closest('.fm-dashboard-view') && typeof window.loadDashboard === 'function';
        if (shouldRefreshDashboard) {
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

function stopRoundSimulationPolling() {
    if (roundSimulationPollTimer) {
        window.clearInterval(roundSimulationPollTimer);
        roundSimulationPollTimer = null;
    }
}

function stopWeekPreparationPolling() {
    if (weekPreparationPollTimer) {
        window.clearInterval(weekPreparationPollTimer);
        weekPreparationPollTimer = null;
    }
}

async function pollWeekPreparationStatus(buttonId, fallbackLabel) {
    const button = buttonId ? document.getElementById(buttonId) : null;
    const defaultLabel = getButtonDefaultLabel(button, fallbackLabel || '⚽ Watch Your Match');

    try {
        const response = await authFetch('/simulation/current-round/prepare/status');
        const data = await response.json().catch(() => null);
        if (!response.ok || !data) {
            throw new Error(data?.message || data?.error || 'Failed to fetch week preparation status.');
        }
        handleSeasonFlowResponse(data, button, defaultLabel);
    } catch (error) {
        console.error('Failed to poll week preparation status:', error);
        stopWeekPreparationPolling();
        clearWeekPreparationJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(error.message || 'Failed to fetch week preparation status.', 'error');
    }
}

function startWeekPreparationPolling(buttonId, fallbackLabel) {
    stopWeekPreparationPolling();
    pollWeekPreparationStatus(buttonId, fallbackLabel);
    weekPreparationPollTimer = window.setInterval(() => {
        pollWeekPreparationStatus(buttonId, fallbackLabel);
    }, 1500);
}

async function pollRoundSimulationStatus(buttonId, fallbackLabel) {
    const button = buttonId ? document.getElementById(buttonId) : null;
    const defaultLabel = getButtonDefaultLabel(button, fallbackLabel || '🧮 Simulate Other Results');

    try {
        const response = await authFetch('/simulation/current-round/status');
        const data = await response.json().catch(() => null);
        if (!response.ok || !data) {
            throw new Error(data?.message || data?.error || 'Failed to fetch round simulation status.');
        }

        if (data.action === 'ROUND_SIMULATION_RUNNING') {
            handleSeasonFlowResponse(data, button, defaultLabel);
            return;
        }

        handleSeasonFlowResponse(data, button, defaultLabel);
    } catch (error) {
        console.error('Failed to poll round simulation status:', error);
        stopRoundSimulationPolling();
        clearRoundSimulationJob();
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(error.message || 'Failed to fetch round simulation status.', 'error');
    }
}

function startRoundSimulationPolling(buttonId, fallbackLabel) {
    stopRoundSimulationPolling();
    pollRoundSimulationStatus(buttonId, fallbackLabel);
    roundSimulationPollTimer = window.setInterval(() => {
        pollRoundSimulationStatus(buttonId, fallbackLabel);
    }, 1500);
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
        if (data?.action === 'WEEK_PREPARATION_RUNNING') {
            startWeekPreparationPolling(buttonId, fallbackLabel);
        } else if (data?.action === 'ROUND_SIMULATION_RUNNING') {
            startRoundSimulationPolling(buttonId, fallbackLabel);
        } else if (data?.action === 'WEEK_ADVANCE_RUNNING') {
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
        console.log('Logged in user:', user.username, 'Team ID:', currentUserTeamId);
    } catch (err) {
        console.error('Failed to load /auth/me:', err);
    }

    if (hasPersistedRoundSimulationJob()) {
        startRoundSimulationPolling('simulate-current-round-btn', '🧮 Simulate Other Results');
    }
    if (hasPersistedWeekPreparationJob()) {
        const target = readWeekPreparationTarget();
        const buttonId = target === 'results' ? 'simulate-current-round-btn' : 'start-realistic-demo-btn';
        const fallbackLabel = target === 'results' ? '🧮 Simulate All Results' : '⚽ Watch Your Match';
        startWeekPreparationPolling(buttonId, fallbackLabel);
    }
    if (hasPersistedAdvanceWeekJob()) {
        startAdvanceWeekPolling('advance-week-btn', '📅 Advance Week');
    }
});

async function startRealisticDemoTest() {
    if (isWeekConsumed()) {
        setSeasonFlowStatus('Current week is already locked. Advance Week to unlock the next match week.', 'warning');
        return;
    }
    persistWeekPreparationTarget('match');
    await runSeasonFlowAction(
        'start-realistic-demo-btn',
        '/simulation/current-round/prepare',
        'Preparing your match...',
        '⚽ Watch Your Match',
        { method: 'POST' }
    );
}

async function simulateCurrentRoundTest() {
    if (isWeekConsumed()) {
        setSeasonFlowStatus('Current week is already locked. Advance Week to unlock the next results desk.', 'warning');
        return;
    }
    persistWeekPreparationTarget('results');
    await runSeasonFlowAction(
        'simulate-current-round-btn',
        '/simulation/current-round/simulate-all',
        'Preparing all results...',
        '🧮 Simulate All Results',
        { method: 'POST' }
    );
}

async function advanceWeekTest() {
    await runSeasonFlowAction(
        'advance-week-btn',
        '/simulation/week/advance',
        'Advancing week...',
        '📅 Advance Week',
        { method: 'POST' }
    );
}

window.startRealisticDemoTest = startRealisticDemoTest;
window.simulateCurrentRoundTest = simulateCurrentRoundTest;
window.advanceWeekTest = advanceWeekTest;

// /js/demo.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;

const DASHBOARD_FLOW_FLASH_KEY = 'dashboardSeasonFlowFlash';

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
        if (!matchId) {
            throw new Error('Missing matchId in response');
        }
        window.location.href = `/realisticDemo.html?matchId=${matchId}&mode=live`;
        return;
    }

    if (action === 'NO_MATCH_CURRENT_WEEK') {
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'No scheduled match exists for your club in the current week.', 'warning');
        return;
    }

    if (action === 'ROUND_SIMULATED') {
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(data.message || 'Other fixtures for the current round were simulated.', 'success');
        showRoundSimulationSummary(data);
        return;
    }

    if (action === 'ROUND_NOT_COMPLETE') {
        resetButton(button, defaultLabel);
        const remaining = Number(data?.remainingFixtures || 0);
        setSeasonFlowStatus(data.message || `Current round still has ${remaining} unfinished fixture${remaining === 1 ? '' : 's'}.`, 'warning');
        return;
    }

    if (action === 'WEEK_ADVANCED') {
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

    resetButton(button, defaultLabel);
    if (data?.message) {
        setSeasonFlowStatus(data.message, 'info');
    }
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
    } catch (error) {
        console.error(`Failed season flow action for ${requestUrl}:`, error);
        resetButton(button, defaultLabel);
        setSeasonFlowStatus(error.message || 'Season flow action failed.', 'error');
    }
}

window.addEventListener('load', async () => {
    const token = localStorage.getItem('token');
    if (!token) {
        console.warn('No token on load - redirecting');
        return;
    }

    try {
        const res = await authFetch('/auth/me');
        const user = await res.json();
        currentUserTeamId = user.teamId;
        console.log('Logged in user:', user.username, 'Team ID:', currentUserTeamId);
    } catch (err) {
        console.error('Failed to load /auth/me:', err);
    }
});

async function startDemoTest() {
    const button = document.getElementById('start-demo-btn');
    if (!button) {
        console.error('Start demo button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Starting demo simulation...';

    try {
        const response = await authFetch('/start-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/demo.html?matchId=${matchId}`;
    } catch (error) {
        console.error('Failed to start demo:', error);
        alert('Failed to start demo simulation.');
        button.disabled = false;
        button.textContent = 'Simulate Next Round';
    }
}

async function startKeyEventsTest() {
    const button = document.getElementById('start-key-events-btn');
    if (!button) {
        console.error('Key events button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Preparing key events...';

    try {
        const response = await authFetch('/start-demo-key-events');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/key-events.html?matchId=${matchId}`;
    } catch (error) {
        console.error('Failed to start key events simulation:', error);
        alert('Failed to start key events simulation.');
        button.disabled = false;
        button.textContent = 'Simulate Key Events';
    }
}

async function startRealisticDemoTest() {
    await runSeasonFlowAction(
        'start-realistic-demo-btn',
        '/start-realistic-demo',
        'Preparing your match...',
        '⚽ Play Your Match'
    );
}

async function simulateCurrentRoundTest() {
    await runSeasonFlowAction(
        'simulate-current-round-btn',
        '/simulation/current-round/simulate-all',
        'Simulating other results...',
        '🧮 Simulate Other Results',
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

window.startDemoTest = startDemoTest;
window.startKeyEventsTest = startKeyEventsTest;
window.startRealisticDemoTest = startRealisticDemoTest;
window.simulateCurrentRoundTest = simulateCurrentRoundTest;
window.advanceWeekTest = advanceWeekTest;

// /js/demo.js
import { authFetch } from './auth.js';

let currentUserTeamId = null;

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
            <div style="padding-top:4px;font-size:0.94rem;opacity:0.82;">Next click on <strong>Realistic Match</strong> starts the friendly week.</div>
        </div>
    `;

    showModal(`Season ${escapeHtml(summary?.seasonYear ?? '')} playoff summary`, bodyHtml);
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
    const button = document.getElementById('start-realistic-demo-btn');
    if (!button) {
        console.error('Realistic demo button not found');
        return;
    }

    button.disabled = true;
    button.textContent = 'Starting realistic simulation...';

    try {
        const response = await authFetch('/start-realistic-demo');
        if (!response.ok) throw new Error('Server error');

        const data = await response.json();
        if (data.action === 'SHOW_PLAYOFF_SUMMARY') {
            resetButton(button, '⚽ Realistic Match');
            showPlayoffSummary(data.summary, data.message);
            return;
        }

        const matchId = data.matchId;
        if (!matchId) throw new Error('Missing matchId in response');

        window.location.href = `/realisticDemo.html?matchId=${matchId}&mode=live`;
    } catch (error) {
        console.error('Failed to start realistic demo:', error);
        alert('Failed to start realistic match simulation.');
        resetButton(button, '⚽ Realistic Match');
    }
}

window.startDemoTest = startDemoTest;
window.startKeyEventsTest = startKeyEventsTest;
window.startRealisticDemoTest = startRealisticDemoTest;

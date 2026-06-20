// bb-schedule.js — Schedule page rendering for basketball manager

async function bbRenderSchedule() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();

    const team = window._team(window.bbCurrentTeamId);

    let fixtures = [], matches = [], roundStatus = null;
    try { fixtures = await window.bbFetchTeamFixtures(window.bbCurrentTeamId); } catch { fixtures = []; }
    try { matches = await window.bbFetchTeamMatches(window.bbCurrentTeamId); } catch { matches = []; }
    try {
        const compId = window.BBALL_CONF.competitionId;
        if (compId) {
            const res = await window.authFetch('/api/bb/fixtures/round/' + compId + '/status?seasonYear=' + window.BBALL_CONF.seasonYear);
            roundStatus = await res.json();
        }
    } catch { roundStatus = null; }

    const currentRound = roundStatus?.currentRound || 1;

    const matchMap = new Map();
    for (const m of matches) {
        const key = `${m.homeTeamId}-${m.awayTeamId}-${m.roundNumber}`;
        matchMap.set(key, m);
    }

    const allGames = [];
    for (const f of fixtures) {
        const key = `${f.homeTeamId}-${f.awayTeamId}-${f.roundNumber}`;
        const match = matchMap.get(key);
        if (match) {
            allGames.push({ ...match, isFixture: false });
        } else {
            allGames.push({ ...f, isFixture: true });
        }
    }
    allGames.sort((a, b) => (a.roundNumber || 0) - (b.roundNumber || 0));

    const byRound = {};
    for (const g of allGames) {
        const rn = g.roundNumber || 1;
        if (!byRound[rn]) byRound[rn] = [];
        byRound[rn].push(g);
    }

    const roundKeys = Object.keys(byRound).map(Number).sort((a, b) => a - b);

    const roundHtml = roundKeys.map(rn => {
        const games = byRound[rn];
        const isCurrent = rn === currentRound;
        const isPast = rn < currentRound;
        const isFuture = rn > currentRound;
        const roundInfo = roundStatus?.rounds?.[String(rn)] || {};
        const allPlayed = games.every(g => g.played || !g.isFixture);
        const locked = isFuture || roundInfo.locked;
        const playedCount = games.filter(g => g.played || !g.isFixture).length;
        const totalCount = games.length;

        const rowsHtml = games.map(g => {
            const isHome = (g.homeTeamId === Number(window.bbCurrentTeamId));
            const opponentName = isHome ? g.awayTeamName : g.homeTeamName;
            const badge = isHome ? 'H' : 'A';

            if (g.isFixture && !g.played) {
                const canPlay = isCurrent || isPast;
                return `
                    <div class="match-row ${canPlay ? 'clickable' : 'locked-row'}" ${canPlay ? `onclick="bbPlayAndShowMatch(${g.id})"` : ''}>
                        <div style="display:flex;align-items:center;gap:12px;flex:1;">
                            <span class="result-badge" style="width:24px;height:24px;font-size:0.6rem;background:${isHome ? 'rgba(232,125,47,0.2)' : 'rgba(255,255,255,0.1)'};color:${isHome ? 'var(--bball-primary-light)' : '#99a6bb'};">${badge}</span>
                            <span style="font-weight:600;">${cmEscapeHtml(opponentName)}</span>
                        </div>
                        <div style="text-align:center;">
                            ${canPlay ? '<span class="play-btn">▶ Play</span>' : '<span style="color:#555;font-size:0.75rem;">🔒 Locked</span>'}
                            <div style="font-size:0.65rem;color:#99a6bb;margin-top:2px;">${cmFormatDate(g.matchDate)}</div>
                        </div>
                    </div>`;
            } else {
                const result = g.homeScore != null
                    ? `<span style="font-weight:900;font-size:1rem;">${isHome ? g.homeScore : g.awayScore} : ${isHome ? g.awayScore : g.homeScore}</span>`
                    : '<span style="color:#99a6bb;">—</span>';
                const won = isHome ? (g.homeScore > g.awayScore) : (g.awayScore > g.homeScore);
                const resultColor = g.homeScore != null ? (won ? '#6fcf97' : '#eb5757') : '#99a6bb';
                const matchId = g.playedMatchId ? g.playedMatchId : g.id;
                return `
                    <div class="match-row clickable" style="cursor:pointer;" onclick="loadPage('matchViewer', { fixtureId: ${matchId}, showQuarters: true })">
                        <div style="display:flex;align-items:center;gap:12px;flex:1;">
                            <span class="result-badge" style="width:24px;height:24px;font-size:0.6rem;background:${isHome ? 'rgba(232,125,47,0.2)' : 'rgba(255,255,255,0.1)'};color:${isHome ? 'var(--bball-primary-light)' : '#99a6bb'};">${badge}</span>
                            <span style="font-weight:600;">${cmEscapeHtml(opponentName)}</span>
                        </div>
                        <div style="text-align:center;">
                            <span style="font-weight:900;font-size:1rem;color:${resultColor};">${result}</span>
                            <div style="font-size:0.65rem;color:#99a6bb;margin-top:2px;">Final</div>
                        </div>
                    </div>`;
            }
        }).join('');

        const badge = allPlayed
            ? '<span style="color:#6fcf97;font-size:0.7rem;">✅ Complete</span>'
            : (isFuture
                ? '<span style="color:#555;font-size:0.7rem;">🔒 Locked</span>'
                : (isCurrent ? '<span style="color:#f5a623;font-size:0.7rem;">▶ Current Round</span>' : ''));

        return `
            <div class="fm-panel schedule-round ${isCurrent ? 'current-round' : ''}" style="margin-bottom:16px;${locked ? 'opacity:0.6;' : ''}">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;flex-wrap:wrap;gap:8px;">
                    <div>
                        <h3 style="margin:0;">Round ${rn}</h3>
                        <div style="font-size:0.75rem;color:#99a6bb;">${playedCount}/${totalCount} matches played ${badge}</div>
                    </div>
                    ${isCurrent && !allPlayed ? `
                        <button class="play-all-btn" onclick="bbPlayAllInRound(${rn})">
                            ▶ Play All (${totalCount - playedCount})
                        </button>
                    ` : ''}
                </div>
                ${rowsHtml}
            </div>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="bball-eyebrow">Schedule</div>
                    <h2 style="margin:0;">${cmEscapeHtml(team?.name || 'Team')}</h2>
                    <p style="color:#99a6bb;font-size:0.82rem;">${cmEscapeHtml(window.BBALL_CONF.leagueName)} · ${roundStatus?.totalRounds || '?'} game season</p>
                </div>
                <button class="back-button" onclick="loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            ${roundHtml || cmBuildEmptyState('No matches scheduled')}
        </div>`;
}

async function bbPlayAllInRound(roundNumber) {
    const compId = window.BBALL_CONF.competitionId;
    if (!compId) return;
    const mc = document.getElementById('main-content');
    if (mc) mc.innerHTML = '<div style="text-align:center;padding:40px;color:#f5a623;">Playing all matches in round ' + roundNumber + '...</div>';
    try {
        const res = await window.authFetch('/api/bb/fixtures/round/' + compId + '/play-all?roundNumber=' + roundNumber + '&seasonYear=' + window.BBALL_CONF.seasonYear, { method: 'POST' });
        const data = await res.json();
        if (mc) mc.innerHTML = '<div style="text-align:center;padding:20px;color:#6fcf97;">✅ Played ' + data.played + '/' + data.total + ' matches</div>';
        await window.bbInvalidateCache();
        setTimeout(() => bbRenderSchedule(), 800);
    } catch {
        mc.innerHTML = '<div style="text-align:center;padding:20px;color:#eb5757;">Error playing matches</div>';
    }
}

async function bbPlayAndShowMatch(fixtureId) {
    window.loadPage('matchViewer', { fixtureId, hideQuarters: true });
}

async function bbResetBasketball() {
    const mc = document.getElementById('main-content');
    if (mc) mc.innerHTML = '<div style="text-align:center;padding:40px;color:#f5a623;">Resetting basketball season...</div>';
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 30000);
        const res = await window.authFetch('/api/bb/reset', { method: 'POST', signal: controller.signal });
        clearTimeout(timeoutId);
        const data = await res.json();
        window.bbInvalidateCache();
        await window.bbRenderDashboard();
        if (mc) {
            const msg = document.createElement('div');
            msg.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);background:#1a1a2e;border:1px solid #6fcf97;color:#6fcf97;padding:12px 24px;border-radius:8px;z-index:9999;';
            msg.textContent = '✅ Season reset complete!';
            document.body.appendChild(msg);
            setTimeout(() => msg.remove(), 3000);
        }
    } catch (e) {
        console.error('Reset error', e);
        if (mc) mc.innerHTML = '<div style="text-align:center;padding:20px;color:#eb5757;">Error resetting</div>';
        else document.body.innerHTML = '<div style="text-align:center;padding:40px;color:#eb5757;">Error resetting</div>';
    }
}

window.bbRenderSchedule = bbRenderSchedule;
window.bbPlayAllInRound = bbPlayAllInRound;
window.bbPlayAndShowMatch = bbPlayAndShowMatch;
window.bbResetBasketball = bbResetBasketball;

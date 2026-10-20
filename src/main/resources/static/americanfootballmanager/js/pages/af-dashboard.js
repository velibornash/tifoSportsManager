async function afRenderDashboard() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();

    const team = window._team(window.afCurrentTeamId);
    if (!team) {
        mc.innerHTML = `
            <div class="fm-panel" style="max-width:600px;margin:40px auto;text-align:center;">
                <h2 style="color:var(--af-primary);margin-bottom:16px;">American Football</h2>
                <p style="color:#99a6bb;margin-bottom:24px;">No season data found. Start a new season to create your league and team.</p>
                <button class="play-all-btn" onclick="afInitSeason()" style="padding:14px 32px;font-size:1.1rem;">🏈 Start Season</button>
            </div>`;
        return;
    }

    let table, teamEntry;
    try {
        table = await window.afFetchLeagueTable();
        teamEntry = table.find(e => e.teamId === Number(window.afCurrentTeamId));
        if (!teamEntry && table.length > 0) teamEntry = table[0];
        if (!teamEntry) teamEntry = { position: '—', points: 0, wins: 0, losses: 0, pointDiff: 0, form: [] };
    } catch { table = []; teamEntry = { position: '—', points: 0, wins: 0, losses: 0, pointDiff: 0, form: [] }; }

    let fixtures = [], roundStatus = null, roundFixtures = [];
    try { fixtures = await window.afFetchTeamFixtures(window.afCurrentTeamId); } catch { fixtures = []; }
    try {
        const compId = window.AF_CONF.competitionId;
        if (compId) {
            const [statusRes, roundFixturesRes] = await Promise.all([
                window.authFetch('/api/af/fixtures/round/' + compId + '/status?seasonYear=' + window.AF_CONF.seasonYear),
                window.authFetch('/api/af/fixtures?competitionId=' + compId + '&seasonYear=' + window.AF_CONF.seasonYear)
            ]);
            roundStatus = await statusRes.json();
            const allFixtures = await roundFixturesRes.json();
            roundFixtures = allFixtures.filter(f => f.roundNumber === roundStatus?.currentRound && !f.played);
        }
    } catch { roundStatus = null; roundFixtures = []; }

    const currentRound = roundStatus?.currentRound || 1;
    const nextFixture = fixtures.find(f => !f.played);
    const nextMatchHtml = nextFixture ? `
        <div class="next-match-card clickable" onclick="window.afPlayAndShowMatch(${nextFixture.id})">
            <div class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:8px;">Next Match — Round ${nextFixture.roundNumber}</div>
            <div class="match-info" style="gap:16px;">
                <span style="font-weight:700;">${cmEscapeHtml(nextFixture.homeTeamName)}</span>
                <span class="vs">vs</span>
                <span style="font-weight:700;">${cmEscapeHtml(nextFixture.awayTeamName)}</span>
            </div>
            <div class="play-btn" style="margin-top:8px;">▶ Play Now</div>
        </div>
    ` : '';

    const playAllRoundHtml = (roundFixtures.length > 0) ? `
        <div style="margin-bottom:16px;text-align:center;">
            <button class="play-all-btn" onclick="window.afPlayAllInRound(${currentRound})">
                ▶ Play All Round ${currentRound} (${roundFixtures.length} matches)
            </button>
        </div>
    ` : '';

    const formHtml = (teamEntry.form || []).map(f =>
        `<span class="form-badge ${f === 'W' ? 'win' : 'loss'}">${f}</span>`
    ).join('');

    const standingHtml = teamEntry ? `
        <div class="stats-grid" style="grid-template-columns:repeat(4,1fr);">
            <div class="dash-tile" onclick="loadPage('league')" style="cursor:pointer;">
                <div class="dash-tile-value" style="color:var(--af-primary);">${teamEntry.position}</div>
                <div class="dash-tile-label">Position 📊</div>
            </div>
            <div class="dash-tile">
                <div class="dash-tile-value">${teamEntry.points}</div>
                <div class="dash-tile-label">Points</div>
            </div>
            <div class="dash-tile">
                <div class="dash-tile-value" style="font-size:1.3rem;">${teamEntry.wins}-${teamEntry.losses}</div>
                <div class="dash-tile-label">W-L</div>
            </div>
            <div class="dash-tile">
                <div class="dash-tile-value" style="font-size:1.3rem;color:${teamEntry.pointDiff >= 0 ? 'var(--af-primary)' : '#e74c3c'};">${cmFormatGoalDiff(teamEntry.pointDiff)}</div>
                <div class="dash-tile-label">+/-</div>
            </div>
        </div>
        ${formHtml ? `<div style="text-align:center;margin-bottom:16px;">${formHtml}</div>` : ''}
    ` : '';

    let recentMatchesHtml = '';
    try {
        const recent = await window.afFetchRecentMatches(window.afCurrentTeamId, 5);
        if (recent.length > 0) {
            recentMatchesHtml = `
                <div style="margin-top:24px;">
                    <h3 class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:12px;">Recent Matches</h3>
                    ${recent.map(m => {
                        const isHome = m.homeTeamId === Number(window.afCurrentTeamId);
                        const opponent = isHome ? m.awayTeamName : m.homeTeamName;
                        const score = `${isHome ? m.homeScore : m.awayScore} : ${isHome ? m.awayScore : m.homeScore}`;
                        const won = isHome ? (m.homeScore > m.awayScore) : (m.awayScore > m.homeScore);
                        const badgeClass = won ? 'win' : 'loss';
                        const badgeText = won ? 'W' : 'L';
                        return `
                            <div class="match-row clickable" onclick="window.loadPage('matchViewer', { fixtureId: ${m.id}, showQuarters: true })">
                                <span class="match-date-small">${cmFormatDate(m.matchDate)}</span>
                                <div class="match-teams">
                                    <span class="result-badge ${badgeClass}" style="width:24px;height:24px;font-size:0.6rem;">${badgeText}</span>
                                    <span style="font-weight:600;">${cmEscapeHtml(opponent)}</span>
                                </div>
                                <span class="score" style="color:#f5a623;">${score}</span>
                            </div>`;
                    }).join('')}
                </div>`;
        }
    } catch {}

    mc.innerHTML = `
        <div class="fm-panel" style="max-width:800px;margin:0 auto;">
            <h2 style="color:var(--af-primary);margin-bottom:4px;">${cmEscapeHtml(window.AF_CONF.teamName || 'My Team')}</h2>
            <div class="fm-eyebrow" style="margin-bottom:24px;">
                ${cmEscapeHtml(window.AF_CONF.leagueName || 'American Football League')}
            </div>
            ${playAllRoundHtml}
            ${nextMatchHtml}
            ${standingHtml}
            ${recentMatchesHtml}
            <div style="display:flex;gap:8px;justify-content:center;margin-top:20px;flex-wrap:wrap;">
                <button class="reset-btn" onclick="afInitSeason()" style="font-size:0.8rem;padding:8px 16px;">🏈 Start Season</button>
                <button class="reset-btn" onclick="loadPage('league')" style="font-size:0.8rem;padding:8px 16px;">📊 League Table</button>
                <button class="reset-btn" onclick="if(confirm('Reset ALL American Football matches? This cannot be undone.')) afResetSeason()" style="font-size:0.8rem;padding:8px 16px;">🔄 Reset Season</button>
            </div>
        </div>`;
}

async function afInitSeason() {
    const mc = document.getElementById('main-content');
    if (mc) mc.innerHTML = '<div style="text-align:center;padding:40px;color:var(--af-primary);">Initializing American Football season...</div>';
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 60000);
        const res = await window.authFetch('/api/af/init', { method: 'POST', signal: controller.signal });
        clearTimeout(timeoutId);
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.message || 'Init failed');
        }
        window.afInvalidateCache();
        await window.afRenderDashboard();
    } catch (e) {
        console.error('Init error', e);
        if (mc) mc.innerHTML = '<div style="text-align:center;padding:20px;color:#eb5757;">Error initializing: ' + e.message + '</div>';
    }
}

async function afResetSeason() {
    const mc = document.getElementById('main-content');
    if (mc) mc.innerHTML = '<div style="text-align:center;padding:40px;color:var(--af-primary);">Resetting American Football season...</div>';
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 30000);
        const res = await window.authFetch('/api/af/reset', { method: 'POST', signal: controller.signal });
        clearTimeout(timeoutId);
        const data = await res.json();
        window.afInvalidateCache();
        await window.afRenderDashboard();
        const msg = document.createElement('div');
        msg.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);background:#1a1a2e;border:1px solid var(--af-primary);color:var(--af-primary);padding:12px 24px;border-radius:8px;z-index:9999;';
        msg.textContent = '✅ Season reset complete!';
        document.body.appendChild(msg);
        setTimeout(() => msg.remove(), 3000);
    } catch (e) {
        console.error('Reset error', e);
        if (mc) mc.innerHTML = '<div style="text-align:center;padding:20px;color:#eb5757;">Error resetting</div>';
    }
}

window.afRenderDashboard = afRenderDashboard;
window.afInitSeason = afInitSeason;
window.afResetSeason = afResetSeason;

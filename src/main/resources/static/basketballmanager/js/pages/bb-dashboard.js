// bb-dashboard.js — Dashboard page rendering for basketball manager

async function bbRenderDashboard() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();

    const team = window._team(window.bbCurrentTeamId);
    if (!team) return;

    let table, teamEntry;
    try {
        table = await window.bbFetchLeagueTable();
        teamEntry = table.find(e => e.teamId === Number(window.bbCurrentTeamId));
        if (!teamEntry && table.length > 0) teamEntry = table[0];
        if (!teamEntry) teamEntry = { position: '—', points: 0, wins: 0, losses: 0, pointDiff: 0, form: [] };
    } catch { table = []; teamEntry = { position: '—', points: 0, wins: 0, losses: 0, pointDiff: 0, form: [] }; }

    let fixtures = [], roundStatus = null, roundFixtures = [];
    try { fixtures = await window.bbFetchTeamFixtures(window.bbCurrentTeamId); } catch { fixtures = []; }
    try {
        const compId = window.BBALL_CONF.competitionId;
        if (compId) {
            const [statusRes, roundFixturesRes] = await Promise.all([
                window.authFetch('/api/bb/fixtures/round/' + compId + '/status?seasonYear=' + window.BBALL_CONF.seasonYear),
                window.authFetch('/api/bb/fixtures?competitionId=' + compId + '&seasonYear=' + window.BBALL_CONF.seasonYear)
            ]);
            roundStatus = await statusRes.json();
            const allFixtures = await roundFixturesRes.json();
            roundFixtures = allFixtures.filter(f => f.roundNumber === roundStatus?.currentRound && !f.played);
        }
    } catch { roundStatus = null; roundFixtures = []; }

    const currentRound = roundStatus?.currentRound || 1;
    const nextFixture = fixtures.find(f => !f.played);
    const nextMatchHtml = nextFixture ? `
        <div class="next-match-card" onclick="bbPlayAndShowMatch(${nextFixture.id})" style="cursor:pointer;">
            <div style="font-size:0.7rem;text-transform:uppercase;letter-spacing:1px;color:var(--bball-primary-light);margin-bottom:8px;">Next Match — Round ${nextFixture.roundNumber}</div>
            <div style="display:flex;align-items:center;justify-content:center;gap:16px;">
                <span style="font-weight:700;">${cmEscapeHtml(nextFixture.homeTeamName)}</span>
                <span style="color:#99a6bb;">vs</span>
                <span style="font-weight:700;">${cmEscapeHtml(nextFixture.awayTeamName)}</span>
            </div>
            <div style="margin-top:8px;"><span class="play-btn">▶ Play Now</span></div>
        </div>
    ` : '';

    const playAllRoundHtml = (roundFixtures.length > 0) ? `
        <div style="margin-bottom:16px;text-align:center;">
            <button class="play-all-btn" onclick="bbPlayAllInRound(${currentRound})" style="padding:12px 24px;font-size:0.9rem;">
                ▶ Play All Round ${currentRound} Matches (${roundFixtures.length})
            </button>
        </div>
    ` : '';

    mc.innerHTML = `
        <div class="team-card fm-panel">
            <div class="bball-team-header" style="display:flex;align-items:center;gap:20px;flex-wrap:wrap;">
                <div style="width:72px;height:72px;border-radius:50%;background:${team.color};display:flex;align-items:center;justify-content:center;font-size:1.6rem;font-weight:900;color:#fff;flex-shrink:0;">
                    ${team.shortName}
                </div>
                <div style="flex:1;">
                    <div class="bball-eyebrow">Club Overview</div>
                    <h1 style="margin:0;">${cmEscapeHtml(team.name)}</h1>
                    <p style="margin:4px 0 0;color:#99a6bb;font-size:0.85rem;">
                        ${cmEscapeHtml(window.BBALL_CONF.leagueName)} · Season ${cmSeasonLabel(window.BBALL_CONF.seasonYear)}
                    </p>
                </div>
            </div>
            ${nextMatchHtml}
            ${playAllRoundHtml}
            <div style="display:flex;gap:8px;justify-content:center;margin-bottom:12px;flex-wrap:wrap;">
                <button class="play-all-btn" onclick="loadPage('schedule')" style="font-size:0.8rem;padding:8px 16px;">📅 View Schedule</button>
                <button class="reset-btn" onclick="if(confirm('Reset ALL basketball matches? This cannot be undone.')) bbResetBasketball()">🔄 Reset Season</button>
            </div>
            <div class="stats-grid clickable" onclick="loadPage('leagueTable')">
                <div class="stat-item">
                    <div class="stat-value">${teamEntry.position}.</div>
                    <div class="stat-label">Position</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">${teamEntry.points}</div>
                    <div class="stat-label">Points</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">${teamEntry.wins}-${teamEntry.losses}</div>
                    <div class="stat-label">W-L</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">${cmFormatGoalDiff(teamEntry.pointDiff)}</div>
                    <div class="stat-label">Pt Diff</div>
                </div>
            </div>
            <div class="hall-card" style="margin-bottom:16px;">
                <h4>Home Hall</h4>
                <div class="hall-name">${cmEscapeHtml(team.hall)}</div>
                <div style="font-size:0.8rem;color:#99a6bb;margin-top:4px;">Capacity: ${(team.hallCapacity || 0).toLocaleString()}</div>
            </div>
            <div class="recent-matches-section" style="margin-bottom:16px;">
                <h3>Recent Matches</h3>
                <div id="bball-dash-recent-matches">${cmBuildEmptyState('Loading...')}</div>
            </div>
        </div>`;
    const tbl = document.getElementById('bb-team-table');
    if (tbl) window.bbMakeSortable(tbl);
    bbRenderDashboardRecentMatches();
}

async function bbRenderTopPlayers() {
    const container = document.getElementById('bball-dash-players');
    if (!container) return;
    try {
        const players = await window.bbFetchTeamPlayers(window.bbCurrentTeamId);
        const sorted = [...players].sort((a, b) => (b.overall || 0) - (a.overall || 0)).slice(0, 5);
        container.innerHTML = sorted.map((p, i) => `
            <div class="match-row" onclick="bbShowPlayer(${p.id})" style="cursor:pointer;">
                <div style="display:flex;align-items:center;gap:12px;">
                    <span style="font-weight:900;color:var(--bball-primary-light);">${i + 1}.</span>
                    <span class="pos-badge ${p.position}">${p.position}</span>
                    <span style="font-weight:600;">${cmEscapeHtml(p.name)}</span>
                </div>
                <span style="font-weight:900;">${p.overall || window.bbCalculateOverall(p.skills, p.position)}</span>
            </div>`
        ).join('');
    } catch {
        container.innerHTML = cmBuildEmptyState('Could not load players');
    }
}

async function bbRenderDashboardRecentMatches() {
    const container = document.getElementById('bball-dash-recent-matches');
    if (!container) return;
    try {
        const matches = await window.bbFetchRecentMatches(window.bbCurrentTeamId, 5);
        if (!matches.length) {
            container.innerHTML = '<div style="color:#99a6bb;font-size:0.85rem;text-align:center;padding:16px;">No recent matches</div>';
            return;
        }
        container.innerHTML = matches.map(m => {
            const isHome = m.homeTeamId === Number(window.bbCurrentTeamId);
            const opponent = isHome ? m.awayTeamName : m.homeTeamName;
            const opponentColor = isHome ? m.awayTeamColor : m.homeTeamColor;
            const score = `${isHome ? m.homeScore : m.awayScore} : ${isHome ? m.awayScore : m.homeScore}`;
            const won = isHome ? (m.homeScore > m.awayScore) : (m.awayScore > m.homeScore);
            const badgeClass = won ? 'win' : 'loss';
            const badgeText = won ? 'W' : 'L';
            return `
                <div class="match-row clickable" onclick="loadPage('matchViewer', { fixtureId: ${m.id}, showQuarters: true })" style="display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid rgba(255,255,255,0.04);">
                    <span class="result-badge ${badgeClass}" style="width:24px;height:24px;font-size:0.6rem;">${badgeText}</span>
                    <span style="flex:1;font-weight:600;">${cmEscapeHtml(opponent)}</span>
                    <span style="font-weight:900;color:#f5a623;">${score}</span>
                    <span style="color:#99a6bb;font-size:0.7rem;">${cmFormatDate(m.matchDate)}</span>
                </div>`;
        }).join('');
    } catch {
        container.innerHTML = cmBuildEmptyState('Could not load recent matches');
    }
}

window.bbRenderDashboard = bbRenderDashboard;

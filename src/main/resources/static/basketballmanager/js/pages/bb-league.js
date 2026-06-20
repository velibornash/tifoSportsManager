// bb-league.js — League table page rendering for basketball manager

async function bbRenderLeagueTable() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();

    let table;
    try { table = await window.bbFetchLeagueTable(); } catch { table = []; }

    const rowsHtml = table.map(entry => {
        const team = window._team(entry.teamId);
        const isHighlight = entry.teamId === Number(window.bbCurrentTeamId);
        const formHtml = (entry.form || []).map(f =>
            `<span class="result-badge ${f === 'W' ? 'win' : 'loss'}" style="width:18px;height:18px;font-size:0.55rem;display:inline-flex;margin-left:1px;">${f}</span>`
        ).join('');
        return `
            <tr class="${isHighlight ? 'highlight-row' : ''}" onclick="bbSelectTeam(${entry.teamId})" style="cursor:pointer;">
                <td><span class="pos-num">${entry.position}.</span></td>
                <td>
                    <div class="team-cell">
                        <span style="display:inline-flex;width:22px;height:22px;border-radius:50%;background:${entry.teamColor || '#333'};align-items:center;justify-content:center;font-size:0.55rem;font-weight:800;color:#fff;flex-shrink:0;">${entry.teamShortName || '?'}</span>
                        ${cmEscapeHtml(entry.teamName)}
                    </div>
                </td>
                <td>${entry.played}</td>
                <td>${entry.wins}</td>
                <td>${entry.losses}</td>
                <td class="pts-col">${entry.points}</td>
                <td>${entry.pointsFor}</td>
                <td>${entry.pointsAgainst}</td>
                <td>${cmFormatGoalDiff(entry.pointDiff)}</td>
                <td style="font-size:0.7rem;white-space:nowrap;">${formHtml}</td>
            </tr>`;
    }).join('');

    let allMatches = [];
    try {
        const compId = window.BBALL_CONF.competitionId;
        if (compId) {
            const res = await window.authFetch('/api/bb/fixtures?competitionId=' + compId + '&seasonYear=' + window.BBALL_CONF.seasonYear);
            allMatches = await res.json();
        }
    } catch { allMatches = []; }

    const matchesByRound = {};
    for (const m of allMatches) {
        const rn = m.roundNumber || 1;
        if (!matchesByRound[rn]) matchesByRound[rn] = [];
        matchesByRound[rn].push(m);
    }
    const roundKeys = Object.keys(matchesByRound).map(Number).sort((a, b) => a - b);

    const resultsHtml = roundKeys.map(rn => {
        const games = matchesByRound[rn];
        const rows = games.map(g => {
            const home = window._team(g.homeTeamId);
            const away = window._team(g.awayTeamId);
            const played = g.played;
            const score = played && g.homeScore != null
                ? `${g.homeScore} : ${g.awayScore}`
                : '—';
            const date = cmFormatDate(g.matchDate);
            const matchId = g.playedMatchId ? g.playedMatchId : g.id;
            return `
                <div class="match-row ${played ? 'clickable' : ''}" style="display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid rgba(255,255,255,0.04);" ${played ? `onclick="loadPage('matchViewer', { fixtureId: ${matchId}, showQuarters: true })"` : ''}>
                    <div style="width:60px;text-align:right;font-weight:600;color:${home?.color || '#99a6bb'};">${cmEscapeHtml(home?.shortName || '?')}</div>
                    <div style="flex:1;text-align:center;font-weight:900;color:${played ? '#f5a623' : '#99a6bb'};">${score}</div>
                    <div style="width:60px;text-align:left;font-weight:600;color:${away?.color || '#99a6bb'};">${cmEscapeHtml(away?.shortName || '?')}</div>
                    <div style="width:100px;color:#99a6bb;font-size:0.7rem;">${date}</div>
                </div>`;
        }).join('');
        return `
            <div class="fm-panel" style="margin-top:16px;">
                <h4 style="color:var(--bball-primary-light);margin-bottom:12px;">Round ${rn}</h4>
                ${rows}
            </div>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div>
                    <div class="fm-eyebrow">${cmEscapeHtml(window.BBALL_CONF.country)}</div>
                    <h2>${cmEscapeHtml(window.BBALL_CONF.leagueName)}</h2>
                    <p style="color:#99a6bb;font-size:0.82rem;">Season ${cmSeasonLabel(window.BBALL_CONF.seasonYear)}</p>
                </div>
                <button class="back-button" onclick="loadPage('')">← Back to Dashboard</button>
            </div>
            <div style="overflow-x:auto;">
                <table class="league-table bball-table" id="bb-league-table">
                    <thead><tr>
                        <th data-sort="number">#</th><th data-sort="string" style="text-align:left;">Team</th><th data-sort="number">Pld</th><th data-sort="number">W</th><th data-sort="number">L</th>
                        <th data-sort="number">Pts</th><th data-sort="number">PF</th><th data-sort="number">PA</th><th data-sort="number">+/-</th><th data-sort="string">Form</th>
                    </tr></thead>
                    <tbody>${rowsHtml}</tbody>
                </table>
            </div>
            <div style="margin-top:16px;font-size:0.75rem;color:#99a6bb;">
                <span style="color:var(--bball-primary-light);font-weight:700;">Pts</span> = Points (3 per win) ·
                <span style="color:var(--bball-primary-light);font-weight:700;">PF</span> = Points For ·
                <span style="color:var(--bball-primary-light);font-weight:700;">PA</span> = Points Against ·
                <span style="color:var(--bball-primary-light);font-weight:700;">+/-</span> = Point Differential
            </div>
            ${resultsHtml ? `
            <div style="margin-top:24px;">
                <h3 style="color:var(--bball-primary-light);margin-bottom:16px;">📅 Match Results</h3>
                ${resultsHtml}
            </div>` : ''}
        </div>`;
    const lt = document.getElementById('bb-league-table');
    if (lt) window.bbMakeSortable(lt);
}

window.bbRenderLeagueTable = bbRenderLeagueTable;

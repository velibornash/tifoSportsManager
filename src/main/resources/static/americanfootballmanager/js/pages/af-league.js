async function afRenderLeagueTable() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();

    let table;
    try { table = await window.afFetchLeagueTable(); } catch { table = []; }

    mc.innerHTML = `
        <div class="fm-panel" style="max-width:800px;margin:0 auto;">
            <div style="margin-bottom:8px;">
                <button class="back-btn" onclick="window.loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            <h2 style="color:var(--af-primary);margin-bottom:4px;">League Table</h2>
            <div class="fm-eyebrow" style="margin-bottom:20px;">
                ${cmEscapeHtml(window.AF_CONF.leagueName || 'American Football League')} — ${window.AF_CONF.seasonYear}
            </div>
            <div style="overflow-x:auto;">
                <table class="league-table" style="min-width:550px;">
                    <thead>
                        <tr>
                            <th style="text-align:left;width:36px;">#</th>
                            <th style="text-align:left;">Team</th>
                            <th>Pld</th>
                            <th>W</th>
                            <th>L</th>
                            <th>PF</th>
                            <th>PA</th>
                            <th>+/-</th>
                            <th>Pts</th>
                            <th>Form</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${table.map(e => {
                            const isUser = e.teamId === Number(window.afCurrentTeamId);
                            const formHtml = (e.form || []).map(f =>
                                `<span class="form-badge ${f === 'W' ? 'win' : 'loss'}">${f}</span>`
                            ).join('');
                            return `
                                <tr class="${isUser ? 'highlight-row' : ''}">
                                    <td style="text-align:left;font-weight:800;color:var(--af-primary);">${e.position}</td>
                                    <td style="text-align:left;font-weight:${isUser ? '800' : '600'};">
                                        ${cmEscapeHtml(e.teamShortName || e.teamName)}
                                    </td>
                                    <td>${e.played}</td>
                                    <td style="color:#58a612;font-weight:700;">${e.wins}</td>
                                    <td style="color:#e74c3c;font-weight:700;">${e.losses}</td>
                                    <td>${e.pointsFor}</td>
                                    <td>${e.pointsAgainst}</td>
                                    <td style="color:${(e.pointDiff || 0) >= 0 ? '#58a612' : '#e74c3c'};font-weight:700;">${cmFormatGoalDiff(e.pointDiff)}</td>
                                    <td style="font-weight:900;color:var(--af-primary);">${e.points}</td>
                                    <td>${formHtml}</td>
                                </tr>`;
                        }).join('') || '<tr><td colspan="10" style="text-align:center;color:#99a6bb;padding:20px;">No data</td></tr>'}
                    </tbody>
                </table>
            </div>
        </div>`;
}

async function afRenderStats() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();

    let leaders;
    try { leaders = await window.afFetchLeagueLeaders(); } catch { leaders = null; }

    if (!leaders) {
        mc.innerHTML = `<div class="fm-panel" style="max-width:800px;margin:0 auto;">${cmBuildEmptyState('No stats yet')}</div>`;
        return;
    }

    const statSections = [
        { title: 'Passing Yards', data: leaders.topPassingYards, key: 'passingYards', suffix: ' yds' },
        { title: 'Rushing Yards', data: leaders.topRushingYards, key: 'rushingYards', suffix: ' yds' },
        { title: 'Receiving Yards', data: leaders.topReceivingYards, key: 'receivingYards', suffix: ' yds' },
        { title: 'Tackles', data: leaders.topTackles, key: 'tackles', suffix: '' },
        { title: 'Interceptions', data: leaders.topInterceptions, key: 'interceptions', suffix: '' },
        { title: 'Sacks', data: leaders.topSacks, key: 'sacks', suffix: '' },
    ];

    mc.innerHTML = `
        <div class="fm-panel" style="max-width:800px;margin:0 auto;">
            <div style="margin-bottom:8px;">
                <button class="back-btn" onclick="window.loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            <h2 style="color:var(--af-primary);margin-bottom:20px;">League Leaders</h2>
            <div class="side-stats-grid">
                ${statSections.map(section => `
                    <div class="fm-panel" style="margin-bottom:0;">
                        <h3 class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:8px;">${section.title}</h3>
                        ${(section.data || []).map((p, i) => `
                            <div class="clickable" onclick="window.loadPage('player', { playerId: ${p.id} })" style="display:flex;align-items:center;gap:8px;padding:5px 8px;background:rgba(255,255,255,0.02);border-radius:6px;margin-bottom:4px;">
                                <span style="width:20px;text-align:center;color:#99a6bb;font-weight:700;font-size:0.75rem;">${i + 1}</span>
                                <span style="flex:1;font-weight:600;font-size:0.85rem;">${cmEscapeHtml(p.name)}</span>
                                <span style="color:var(--af-primary);font-weight:800;font-size:0.85rem;">${section.data ? (p.stats?.[section.key] || 0) + section.suffix : ''}</span>
                            </div>
                        `).join('') || '<div style="color:#99a6bb;font-size:0.8rem;padding:8px;">No data</div>'}
                    </div>
                `).join('')}
            </div>
        </div>`;
}

window.afRenderLeagueTable = afRenderLeagueTable;
window.afRenderStats = afRenderStats;

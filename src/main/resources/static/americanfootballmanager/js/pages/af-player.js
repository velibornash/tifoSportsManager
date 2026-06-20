async function afRenderPlayer(playerId) {
    const mc = document.getElementById('main-content');
    if (!mc) return;

    let player, seasons;
    try {
        const [pRes, sRes] = await Promise.all([
            window.authFetch('/api/af/players/' + playerId),
            window.authFetch('/api/af/players/' + playerId + '/seasons')
        ]);
        player = await pRes.json();
        seasons = await sRes.json();
    } catch {
        mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Player not found')}</div>`;
        return;
    }

    const skills = player.skills || {};
    const stats = player.stats || {};

    const skillEntries = [
        { key: 'stamina', label: 'Stamina', cls: 'rating-stamina' },
        { key: 'strength', label: 'Strength', cls: 'rating-strength' },
        { key: 'pace', label: 'Pace', cls: 'rating-pace' },
        { key: 'playmaking', label: 'Playmaking', cls: 'rating-playmaking' },
        { key: 'passing', label: 'Passing', cls: 'rating-passing' },
        { key: 'running', label: 'Running', cls: 'rating-running' },
        { key: 'tackling', label: 'Tackling', cls: 'rating-tackling' },
        { key: 'shooting', label: 'Shooting', cls: 'rating-shooting' },
    ];

    mc.innerHTML = `
        <div style="max-width:900px;margin:0 auto;">
            <div style="margin-bottom:8px;">
                <button class="back-btn" onclick="window.loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            <div class="afl-player-header">
                <div class="afl-player-avatar">${cmEscapeHtml(player.name.charAt(0))}</div>
                <div class="afl-player-meta" style="flex:1;">
                    <h2 style="color:#fff;margin-bottom:2px;">${cmEscapeHtml(player.name)}</h2>
                    <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap;">
                        <span class="pos-badge ${player.position}">${player.position}</span>
                        <span style="color:${player.teamColor || '#99a6bb'};font-weight:600;">${cmEscapeHtml(player.teamName || '')}</span>
                        <span style="color:#99a6bb;">#${player.jerseyNumber || '—'}</span>
                        <span style="color:#99a6bb;">OVR: <strong style="color:var(--af-primary-light);">${player.overall || '—'}</strong></span>
                        <span style="color:#99a6bb;">Fatigue: <strong style="color:${(player.fatigue || 0) > 60 ? '#e74c3c' : '#99a6bb'};">${player.fatigue || 0}%</strong></span>
                    </div>
                </div>
            </div>

            <div class="fm-panel" style="margin-bottom:20px;">
                <h3 class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:12px;">Attributes</h3>
                <div class="stat-grid-af">
                    ${skillEntries.map(({ key, label, cls }) => `
                        <div class="stat-cell">
                            <div class="stat-cell-value" style="color:${cmRatingColor(skills[key] || 0, 20)};">${skills[key] || 0}</div>
                            <div class="stat-cell-label">${label}</div>
                            <span class="skill-bar skill-bar-af" style="margin-top:4px;"><span class="skill-bar-fill ${cls}" style="width:${((skills[key] || 0) / 20) * 100}%"></span></span>
                        </div>
                    `).join('')}
                </div>
            </div>

            <div class="fm-panel" style="margin-bottom:20px;">
                <h3 class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:12px;">Season Stats</h3>
                <div style="overflow-x:auto;">
                    <table class="afl-stats-table" style="min-width:500px;">
                        <thead>
                            <tr>
                                <th style="text-align:left;">Season</th>
                                <th>Team</th>
                                <th>G</th>
                                <th>TD</th>
                                <th>FG</th>
                                <th>FGA</th>
                                <th>Tkl</th>
                                <th>Int</th>
                                <th>Sack</th>
                                <th>Pass Yds</th>
                                <th>Rush Yds</th>
                                <th>Rec Yds</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${(seasons?.seasons || []).map(s => `
                                <tr>
                                    <td style="text-align:left;">${s.seasonYear}</td>
                                    <td style="text-align:left;">${cmEscapeHtml(s.teamName || '')}</td>
                                    <td>${s.gamesPlayed || 0}</td>
                                    <td>${s.touchdowns || 0}</td>
                                    <td>${s.fieldGoalsMade || 0}</td>
                                    <td>${s.fieldGoalsAttempted || 0}</td>
                                    <td>${s.tackles || 0}</td>
                                    <td>${s.interceptions || 0}</td>
                                    <td>${s.sacks || 0}</td>
                                    <td>${s.passingYards || 0}</td>
                                    <td>${s.rushingYards || 0}</td>
                                    <td>${s.receivingYards || 0}</td>
                                </tr>
                            `).join('') || '<tr><td colspan="12" style="text-align:center;color:#99a6bb;">No season stats</td></tr>'}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}

window.afRenderPlayer = afRenderPlayer;

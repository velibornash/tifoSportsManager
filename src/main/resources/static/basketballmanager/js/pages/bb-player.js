// bb-player.js — Player detail page rendering for basketball manager

async function bbRenderPlayer(playerId) {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!playerId) { mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Player not specified')}</div>`; return; }

    let player, seasonsResp;
    try {
        const [playerRes, seasonsRes] = await Promise.all([
            window.authFetch('/api/bb/players/' + playerId),
            window.authFetch('/api/bb/players/' + playerId + '/seasons')
        ]);
        player = await playerRes.json();
        seasonsResp = await seasonsRes.json();
    } catch {
        mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Could not load player')}</div>`;
        return;
    }
    if (!player) { mc.innerHTML = `<div class="fm-panel">${cmBuildEmptyState('Player not found')}</div>`; return; }

    const ovr = player.overall || window.bbCalculateOverall(player.skills, player.position);
    const stats = player.stats || {};
    const team = window._team(player.teamId);

    const perGame = (stat) => stats.games > 0 ? (stat / stats.games).toFixed(1) : '—';

    const shot2 = stats.twoPtAttempted > 0 ? `${stats.twoPtMade}/${stats.twoPtAttempted} (${((stats.twoPtMade/stats.twoPtAttempted)*100).toFixed(1)}%)` : '—';
    const shot3 = stats.threePtAttempted > 0 ? `${stats.threePtMade}/${stats.threePtAttempted} (${((stats.threePtMade/stats.threePtAttempted)*100).toFixed(1)}%)` : '—';
    const ft = stats.ftAttempted > 0 ? `${stats.ftMade}/${stats.ftAttempted} (${((stats.ftMade/stats.ftAttempted)*100).toFixed(1)}%)` : '—';

    const seasons = seasonsResp?.seasons || [];
    const totals = seasonsResp?.totals || {};
    const totGames = totals.gamesPlayed || 0;
    const careerRows = seasons.map(s => `
        <tr>
            <td>${s.seasonYear}</td>
            <td>${cmEscapeHtml(s.competitionName || '—')}</td>
            <td>${cmEscapeHtml(s.teamName || '—')}</td>
            <td>${s.gamesPlayed || 0}</td>
            <td>${s.ppg != null ? s.ppg.toFixed(1) : '—'}</td>
            <td>${s.rpg != null ? s.rpg.toFixed(1) : '—'}</td>
            <td>${s.apg != null ? s.apg.toFixed(1) : '—'}</td>
            <td>${s.spg != null ? s.spg.toFixed(1) : '—'}</td>
            <td>${s.bpg != null ? s.bpg.toFixed(1) : '—'}</td>
            <td>${s.twoPtPct != null ? s.twoPtPct.toFixed(1) + '%' : '—'}</td>
            <td>${s.threePtPct != null ? s.threePtPct.toFixed(1) + '%' : '—'}</td>
            <td>${s.ftPct != null ? s.ftPct.toFixed(1) + '%' : '—'}</td>
        </tr>
    `).join('');

    const totPpg = totGames > 0 ? (totals.pointsScored / totGames).toFixed(1) : '—';
    const totRpg = totGames > 0 ? (totals.reboundsTotal / totGames).toFixed(1) : '—';
    const totApg = totGames > 0 ? (totals.assistsTotal / totGames).toFixed(1) : '—';

    mc.innerHTML = `
        <div class="fm-panel">
            <button class="back-button" onclick="loadPage('firstTeam')">← Back to Team</button>
            <div class="bball-player-header">
                <div class="bball-player-avatar">
                    <span>${player.name.split(' ').map(n => n[0]).join('')}</span>
                </div>
                <div class="bball-player-meta">
                    <span class="pos-badge ${player.position}" style="font-size:0.75rem;padding:4px 12px;">${player.position}</span>
                    <h2>${cmEscapeHtml(player.name)}</h2>
                    <div class="phys-stats">
                        ${player.height}cm · ${player.weight}kg · #${player.jerseyNumber || '?'}
                        ${team ? `· ${cmEscapeHtml(team.name)}` : ''}
                    </div>
                    <div style="margin-top:8px;font-size:1.4rem;font-weight:900;color:${cmRatingColor(ovr, 20)};">
                        OVR ${ovr}
                    </div>
                </div>
            </div>
            <div style="margin-bottom:20px;">
                <h4 style="color:var(--bball-primary-light);font-size:0.75rem;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px;">Attributes</h4>
                <div class="stat-grid-bball">
                    ${_statCell('Pace', player.skills?.pace, 'pace')}
                    ${_statCell('Steals', player.skills?.steals, 'steals')}
                    ${_statCell('Blocks', player.skills?.blocks, 'blocks')}
                    ${_statCell('Free Throws', player.skills?.freeThrows, 'ft')}
                    ${_statCell('2-Point', player.skills?.twoPtShot, '2pt')}
                    ${_statCell('3-Point', player.skills?.threePtShot, '3pt')}
                    ${_statCell('Rebounding', player.skills?.rebounding, 'reb')}
                    ${_statCell('Playmaking', player.skills?.playmaking, 'play')}
                </div>
            </div>
            <div style="margin-bottom:20px;">
                <h4 style="color:var(--bball-primary-light);font-size:0.75rem;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px;">Season Stats</h4>
                <div class="stat-grid-bball">
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.ppg != null ? stats.ppg.toFixed(1) : perGame(stats.points)}</div>
                        <div class="stat-cell-label">PPG</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.rpg != null ? stats.rpg.toFixed(1) : perGame(stats.rebounds)}</div>
                        <div class="stat-cell-label">RPG</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.apg != null ? stats.apg.toFixed(1) : perGame(stats.assists)}</div>
                        <div class="stat-cell-label">APG</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.spg != null ? stats.spg.toFixed(1) : perGame(stats.steals)}</div>
                        <div class="stat-cell-label">SPG</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.bpg != null ? stats.bpg.toFixed(1) : perGame(stats.blocks)}</div>
                        <div class="stat-cell-label">BPG</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.topg != null ? stats.topg.toFixed(1) : perGame(stats.turnovers)}</div>
                        <div class="stat-cell-label">TO</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value" style="font-size:0.75rem;">${shot2}</div>
                        <div class="stat-cell-label">2PT%</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value" style="font-size:0.75rem;">${shot3}</div>
                        <div class="stat-cell-label">3PT%</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value" style="font-size:0.75rem;">${ft}</div>
                        <div class="stat-cell-label">FT%</div>
                    </div>
                    <div class="stat-cell">
                        <div class="stat-cell-value">${stats.games || 0}</div>
                        <div class="stat-cell-label">GP</div>
                    </div>
                </div>
            </div>
            ${seasons.length > 0 ? `
            <div style="margin-bottom:20px;">
                <h4 style="color:var(--bball-primary-light);font-size:0.75rem;text-transform:uppercase;letter-spacing:1px;margin-bottom:10px;">Career Stats by Season</h4>
                <div style="overflow-x:auto;">
                    <table class="stats-table" style="width:100%;font-size:0.7rem;" id="bb-career-table">
                        <thead>
                            <tr>
                                <th data-sort="number">Season</th>
                                <th data-sort="string">Competition</th>
                                <th data-sort="string">Team</th>
                                <th data-sort="number">GP</th>
                                <th data-sort="number">PPG</th>
                                <th data-sort="number">RPG</th>
                                <th data-sort="number">APG</th>
                                <th data-sort="number">SPG</th>
                                <th data-sort="number">BPG</th>
                                <th data-sort="number">2PT%</th>
                                <th data-sort="number">3PT%</th>
                                <th data-sort="number">FT%</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${careerRows}
                        </tbody>
                        <tfoot>
                            <tr style="font-weight:700;border-top:1px solid rgba(255,255,255,0.1);">
                                <td colspan="3">Career Total</td>
                                <td>${totGames}</td>
                                <td>${totPpg}</td>
                                <td>${totRpg}</td>
                                <td>${totApg}</td>
                                <td>—</td>
                                <td>—</td>
                                <td>—</td>
                                <td>—</td>
                                <td>—</td>
                            </tr>
                        </tfoot>
                    </table>
                </div>
            </div>
            ` : ''}
        </div>`;
    const ct = document.getElementById('bb-career-table');
    if (ct) window.bbMakeSortable(ct);
}

function _statCell(label, value, key) {
    const v = value || 0;
    const pct = Math.min(100, (v / 20) * 100);
    const color = pct >= 80 ? '#6fcf97' : pct >= 50 ? '#f2c94c' : '#eb5757';
    return `
        <div class="stat-cell">
            <div class="stat-cell-value" style="color:${color};">${v}</div>
            <div class="stat-cell-label">${label}</div>
            <div class="skill-bar" style="width:100%;margin-top:4px;"><div class="skill-bar-fill rating-${key}" style="width:${pct}%;background:${color};"></div></div>
        </div>`;
}

window.bbRenderPlayer = bbRenderPlayer;

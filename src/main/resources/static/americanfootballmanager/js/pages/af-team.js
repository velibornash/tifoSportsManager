async function afRenderTeam() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.afTeams.length) await window.afInit();

    let players;
    try { players = await window.afFetchTeamPlayers(window.afCurrentTeamId); } catch { players = []; }

    const grouped = {};
    for (const p of players) {
        const posGroup = p.position === 'QB' ? 'Quarterbacks' :
            p.position === 'RB' ? 'Running Backs' :
            p.position === 'WR' ? 'Wide Receivers' :
            p.position === 'TE' ? 'Tight Ends' :
            p.position === 'OL' ? 'Offensive Line' :
            p.position === 'DE' ? 'Defensive Ends' :
            p.position === 'DT' ? 'Defensive Tackles' :
            p.position === 'LB' ? 'Linebackers' :
            p.position === 'CB' ? 'Cornerbacks' :
            p.position === 'S' ? 'Safeties' :
            p.position === 'K' ? 'Kickers' : 'Punters';
        if (!grouped[posGroup]) grouped[posGroup] = [];
        grouped[posGroup].push(p);
    }

    const order = ['Quarterbacks', 'Running Backs', 'Wide Receivers', 'Tight Ends', 'Offensive Line',
        'Defensive Ends', 'Defensive Tackles', 'Linebackers', 'Cornerbacks', 'Safeties', 'Kickers', 'Punters'];

    let allHtml = '';
    for (const gName of order) {
        const gp = grouped[gName];
        if (!gp || gp.length === 0) continue;
        const posClass = gp[0].position;
        allHtml += `
            <div class="fm-panel" style="margin-bottom:16px;">
                <h3 class="fm-eyebrow" style="color:var(--af-primary-light);margin-bottom:8px;">
                    <span class="pos-badge ${posClass}">${posClass}</span> ${gName} (${gp.length})
                </h3>
                <div style="overflow-x:auto;">
                    <table class="afl-stats-table" style="min-width:600px;">
                        <thead>
                            <tr>
                                <th style="text-align:left;">#</th>
                                <th style="text-align:left;">Name</th>
                                <th>Pos</th>
                                <th>OVR</th>
                                <th>STA</th>
                                <th>STR</th>
                                <th>PAC</th>
                                <th>PLA</th>
                                <th>PAS</th>
                                <th>RUN</th>
                                <th>TAK</th>
                                <th>SHO</th>
                                <th>Fat</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${gp.map(p => `
                                <tr class="clickable" onclick="window.loadPage('player', { playerId: ${p.id} })">
                                    <td style="text-align:left;color:#99a6bb;">${p.jerseyNumber || '—'}</td>
                                    <td style="text-align:left;font-weight:600;">${cmEscapeHtml(p.name)}</td>
                                    <td><span class="pos-badge ${p.position}">${p.position}</span></td>
                                    <td style="font-weight:800;">${p.overall != null ? p.overall : '—'}</td>
                                    <td>${p.skills?.stamina || '—'}</td>
                                    <td>${p.skills?.strength || '—'}</td>
                                    <td>${p.skills?.pace || '—'}</td>
                                    <td>${p.skills?.playmaking || '—'}</td>
                                    <td>${p.skills?.passing || '—'}</td>
                                    <td>${p.skills?.running || '—'}</td>
                                    <td>${p.skills?.tackling || '—'}</td>
                                    <td>${p.skills?.shooting || '—'}</td>
                                    <td style="color:${(p.fatigue || 0) > 60 ? '#e74c3c' : '#99a6bb'};">${p.fatigue || 0}%</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>`;
    }

    mc.innerHTML = `
        <div style="max-width:900px;margin:0 auto;">
            <div class="fm-panel" style="margin-bottom:20px;">
                <div style="margin-bottom:8px;">
                    <button class="back-btn" onclick="window.loadPage('dashboard')">← Back to Dashboard</button>
                </div>
                <h2 style="color:var(--af-primary);margin-bottom:4px;">Roster</h2>
                <div class="fm-eyebrow" style="margin-bottom:4px;">
                    ${cmEscapeHtml(window.AF_CONF.teamName || 'My Team')} — ${players.length} players
                </div>
            </div>
            ${allHtml || cmBuildEmptyState('No players')}
        </div>`;
}

window.afRenderTeam = afRenderTeam;

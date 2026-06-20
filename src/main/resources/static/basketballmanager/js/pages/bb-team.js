// bb-team.js — Team roster page rendering for basketball manager

async function bbRenderTeam() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();

    const team = window._team(window.bbCurrentTeamId);
    if (!team) return;

    let players;
    try { players = await window.bbFetchTeamPlayers(team.id); } catch { players = []; }
    const sorted = [...players].sort((a, b) => (b.overall || 0) - (a.overall || 0));

    const rowsHtml = sorted.map(p => {
        const ovr = p.overall || window.bbCalculateOverall(p.skills, p.position);
        const ovrColor = cmRatingColor(ovr, 20);
        const s = p.skills || {};
        return `
            <tr onclick="bbShowPlayer(${p.id})" style="cursor:pointer;">
                <td style="color:#99a6bb;">${p.jerseyNumber || p.number || '—'}</td>
                <td class="player-name-cell">${cmEscapeHtml(p.name)}</td>
                <td><span class="pos-badge ${p.position}">${p.position}</span></td>
                <td style="font-weight:900;color:${ovrColor};">${ovr}</td>
                <td>${cmSkillBar(s.pace)}</td>
                <td>${cmSkillBar(s.steals)}</td>
                <td>${cmSkillBar(s.blocks)}</td>
                <td>${cmSkillBar(s.freeThrows)}</td>
                <td>${cmSkillBar(s.twoPtShot)}</td>
                <td>${cmSkillBar(s.threePtShot)}</td>
                <td>${cmSkillBar(s.rebounding)}</td>
                <td>${cmSkillBar(s.playmaking)}</td>
                <td>${p.height}cm</td>
                <td style="font-size:0.75rem;color:#99a6bb;">${p.fatigue}%</td>
            </tr>`;
    }).join('');

    mc.innerHTML = `
        <div class="fm-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:10px;">
                <div style="display:flex;align-items:center;gap:16px;">
                    <div style="width:56px;height:56px;border-radius:50%;background:${team.color};display:flex;align-items:center;justify-content:center;font-size:1.3rem;font-weight:900;color:#fff;">
                        ${team.shortName}
                    </div>
                    <div>
                        <div class="bball-eyebrow">First Team</div>
                        <h2 style="margin:0;">${cmEscapeHtml(team.name)}</h2>
                        <p style="margin:2px 0 0;color:#99a6bb;font-size:0.82rem;">${cmEscapeHtml(team.hall)} · ${(team.hallCapacity || 0).toLocaleString()} capacity</p>
                    </div>
                </div>
                <button class="back-button" onclick="loadPage('dashboard')">← Back to Dashboard</button>
            </div>
            <div style="overflow-x:auto;">
                <table class="player-table" id="bb-team-table">
                    <thead><tr>
                        <th data-sort="number">#</th><th data-sort="string" style="text-align:left;">Player</th><th data-sort="string">Pos</th><th data-sort="number">OVR</th>
                        <th data-sort="number">Pace</th><th data-sort="number">Stl</th><th data-sort="number">Blk</th><th data-sort="number">FT</th><th data-sort="number">2PT</th><th data-sort="number">3PT</th>
                        <th data-sort="number">Reb</th><th data-sort="number">Ply</th><th data-sort="number">Ht</th><th data-sort="number">Fat</th>
                    </tr></thead>
                    <tbody>${rowsHtml}</tbody>
                </table>
            </div>
            <div style="margin-top:12px;font-size:0.75rem;color:#99a6bb;display:flex;gap:16px;flex-wrap:wrap;">
                <span><span style="color:var(--bball-primary-light);font-weight:700;">OVR</span> = Overall Rating</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">Stl</span> = Steals</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">Blk</span> = Blocks</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">FT</span> = Free Throws</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">2PT</span> = 2-Point Shot</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">3PT</span> = 3-Point Shot</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">Reb</span> = Rebounding</span>
                <span><span style="color:var(--bball-primary-light);font-weight:700;">Ply</span> = Playmaking</span>
            </div>
        </div>`;
}

window.bbRenderTeam = bbRenderTeam;

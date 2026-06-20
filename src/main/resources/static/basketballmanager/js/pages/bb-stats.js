// bb-stats.js — League leaders stats page rendering for basketball manager

async function bbRenderStats() {
    const mc = document.getElementById('main-content');
    if (!mc) return;
    if (!window.bbTeams.length) await window.bbInit();

    let leaders;
    try { leaders = await window.bbFetchLeagueLeaders(); } catch { leaders = {}; }

    const topScorers = leaders.topScorers || [];
    const topRebounders = leaders.topRebounders || [];
    const topAssists = leaders.topAssists || [];

    function leaderTable(title, data, statKey, label) {
        const rows = data.map((p, i) => {
            const t = window._team(p.teamId);
            const val = p.stats ? (p.stats[statKey] != null ? p.stats[statKey].toFixed(1) : '—') : '—';
            return `
                <tr onclick="bbShowPlayer(${p.id})" style="cursor:pointer;">
                    <td><span class="pos-num">${i + 1}.</span></td>
                    <td style="text-align:left;font-weight:600;">${cmEscapeHtml(p.name)}</td>
                    <td><span style="display:inline-flex;width:20px;height:20px;border-radius:50%;background:${p.teamColor || t?.color || '#333'};align-items:center;justify-content:center;font-size:0.5rem;font-weight:800;color:#fff;margin-right:4px;">${p.teamShortName || t?.shortName || '?'}</span>${cmEscapeHtml(p.teamName || t?.name || '')}</td>
                    <td><span class="pos-badge ${p.position}">${p.position}</span></td>
                    <td style="font-weight:900;color:var(--bball-primary-light);">${val}</td>
                </tr>`;
        }).join('');
        return `
            <div class="fm-panel" style="margin-bottom:16px;">
                <h3 style="margin-bottom:12px;">${title}</h3>
                ${rows ? `<table class="league-table bball-table"><thead><tr><th data-sort="number">#</th><th data-sort="string" style="text-align:left;">Player</th><th data-sort="string">Team</th><th data-sort="string">Pos</th><th data-sort="number">${label}</th></tr></thead><tbody>${rows}</tbody></table>` : cmBuildEmptyState('No stats available')}
            </div>`;
    }

    mc.innerHTML = `
        <div id="bb-stats-page">
        <div style="margin-bottom:16px;">
            <button class="back-button" onclick="loadPage('')">← Back to Dashboard</button>
        </div>
        <h2 style="margin-bottom:16px;">${cmEscapeHtml(window.BBALL_CONF.leagueName)} — Statistics</h2>
        ${leaderTable('Top Scorers (PPG)', topScorers, 'ppg', 'PPG')}
        ${leaderTable('Top Rebounders (RPG)', topRebounders, 'rpg', 'RPG')}
        ${leaderTable('Top Assists (APG)', topAssists, 'apg', 'APG')}
        </div>
    `;
    document.querySelectorAll('#bb-stats-page table.league-table').forEach(t => window.bbMakeSortable(t));
}

window.bbRenderStats = bbRenderStats;

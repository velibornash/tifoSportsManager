export const NATIONAL_COMPETITIONS = [
    { level: 'senior', stage: 'QUALIFYING', label: 'NT Qualifiers' },
    { level: 'senior', stage: 'WORLD_CUP', label: 'World Cup' },
    { level: 'u21', stage: 'QUALIFYING', label: 'U-21 Qualifiers' },
    { level: 'u21', stage: 'WORLD_CUP', label: 'U-21 World Cup' }
];

export function createNationalTournamentView({ authFetch, escapeHtml }) {
    async function readJson(path) {
        const response = await authFetch(path);
        if (!response.ok) throw new Error(`The server answered ${response.status} for ${path}.`);
        return response.json();
    }

    function table(rows) {
        if (!rows.length) return '<p class="fm-empty">No standings yet.</p>';
        return `<div class="fm-squad-wrap"><table class="fm-squad fm-league-table">
            <thead><tr><th>#</th><th>Team</th><th>P</th><th>GD</th><th>Pts</th></tr></thead>
            <tbody>${rows.map(row => `<tr>
                <td>${escapeHtml(row.position)}</td><td>${escapeHtml(row.teamName || 'Unknown team')}</td>
                <td>${escapeHtml(row.played)}</td><td>${escapeHtml(row.goalDifference)}</td>
                <td><strong>${escapeHtml(row.points)}</strong></td>
            </tr>`).join('')}</tbody></table></div>`;
    }

    function competitionBody(data) {
        const groups = Array.isArray(data.groups) ? data.groups : [];
        const rounds = Array.isArray(data.rounds) ? data.rounds : [];
        return `${groups.map(group => `<section class="fm-panel">
            <div class="fm-panel-head"><div><h3>Group ${escapeHtml(group.code)}</h3>
                <p class="fm-subtle">${group.table?.length || 0} teams</p></div></div>
            ${table(Array.isArray(group.table) ? group.table : [])}
        </section>`).join('')}
        ${rounds.length ? `<section class="fm-panel"><div class="fm-panel-head"><div>
            <h3>Results and bracket</h3><p class="fm-subtle">${rounds.length} rounds</p></div></div>
            ${rounds.map(round => `<div class="fm-cup-round"><div class="fm-cup-round-head">
                <span>${escapeHtml(round.name || `Round ${round.round}`)}</span>
                <span>${round.ties?.length || 0} ties</span></div>
                ${(round.ties || []).map(tie => `<div class="fm-cup-tie">
                    <span>${escapeHtml(tie.homeName || 'TBC')}</span>
                    <span class="fm-cup-tie-sep">${tie.played ? `${escapeHtml(tie.homeGoals ?? '')} – ${escapeHtml(tie.awayGoals ?? '')}` : 'v'}</span>
                    <span>${escapeHtml(tie.awayName || 'TBC')}</span>
                </div>`).join('')}
            </div>`).join('')}
        </section>` : ''}
        ${!groups.length && !rounds.length ? '<section class="fm-panel"><p class="fm-empty">This competition has not been drawn yet.</p></section>' : ''}`;
    }

    async function load(level, stage) {
        const mainContent = document.getElementById('main-content');
        const definition = NATIONAL_COMPETITIONS.find(item => item.level === level && item.stage === stage)
            || NATIONAL_COMPETITIONS[0];
        try {
            const data = await readJson(`/api/national-tournaments/${definition.level}/${definition.stage}`);
            mainContent.innerHTML = `<div class="fm-page"><div class="fm-page-head">
                <button type="button" class="back-to-dashboard" data-nav-back>Back</button>
                <div><h2>${escapeHtml(data.name || definition.label)}</h2>
                <p class="fm-subtle">Season ${escapeHtml(data.season)} · week ${escapeHtml(data.week)}</p></div>
            </div>${data.exists ? competitionBody(data) : `<section class="fm-panel"><p class="fm-empty">
                ${escapeHtml(data.note || 'This competition has not been drawn yet.')}</p></section>`}</div>`;
        } catch (error) {
            console.error('Failed to load national tournament:', error);
            mainContent.innerHTML = '<div class="manager-card"><h2>Error</h2><p>Could not load this national competition.</p></div>';
        }
    }

    return { load };
}

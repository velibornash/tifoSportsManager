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

    /**
     * A team name, as a link to that country.
     *
     * <p>The owner asked for this: a qualifying table is six countries, and a manager who wants to know
     * who is in the group should be able to click one and land on that country. A name with no country
     * behind it renders as plain text rather than a link that goes nowhere — which is also why the
     * server sends the ISO code beside every name.
     */
    function teamLink(name, iso) {
        const label = escapeHtml(name || 'Unknown team');
        if (!iso) return label;
        return `<button type="button" class="fm-link-btn js-country" data-country-iso="${escapeHtml(iso)}">${label}</button>`;
    }

    function table(rows) {
        if (!rows.length) return '<p class="fm-empty">No standings yet.</p>';
        return `<div class="fm-squad-wrap"><table class="fm-squad fm-league-table">
            <thead><tr><th>#</th><th>Team</th><th>P</th><th>GD</th><th>Pts</th></tr></thead>
            <tbody>${rows.map(row => `<tr>
                <td>${escapeHtml(row.position)}</td>
                <td>${teamLink(row.teamName, row.countryIso)}</td>
                <td>${escapeHtml(row.played)}</td><td>${escapeHtml(row.goalDifference)}</td>
                <td><strong>${escapeHtml(row.points)}</strong></td>
            </tr>`).join('')}</tbody></table></div>`;
    }

    /** One group's matchdays, with the day each falls on. */
    function groupSchedule(group) {
        const matchdays = Array.isArray(group.fixtures) ? group.fixtures : [];
        if (!matchdays.length) return '';
        return matchdays.map(day => `<div class="fm-cup-round">
            <div class="fm-cup-round-head">
                <span>Matchday ${escapeHtml(day.round)}</span>
                <span>Week ${escapeHtml(group.week ?? '')} · day ${escapeHtml(day.day)}</span>
            </div>
            ${(day.fixtures || []).map(tie => `<div class="fm-cup-tie">
                <span>${teamLink(tie.homeName, tie.homeIso)}</span>
                <span class="fm-cup-tie-sep">${tie.played
                    ? `${escapeHtml(tie.homeGoals ?? '')} – ${escapeHtml(tie.awayGoals ?? '')}`
                    : 'v'}</span>
                <span>${teamLink(tie.awayName, tie.awayIso)}</span>
            </div>`).join('')}
        </div>`).join('');
    }

    /**
     * One group: its table, and the whole qualifying schedule behind the group name.
     *
     * <p>The schedule is rendered but hidden, and the group name opens it. A rendered-then-hidden
     * schedule rather than a separate page because a manager comparing two groups wants both on screen,
     * and fetching on click would make the first open a spinner where a schedule should be.
     */
    function groupSection(group) {
        const code = escapeHtml(group.code);
        const hasSchedule = Array.isArray(group.fixtures) && group.fixtures.length > 0;
        return `<section class="fm-panel fm-nt-group" data-group="${code}">
            <div class="fm-panel-head"><div>
                ${hasSchedule
                    ? `<button type="button" class="fm-link-btn js-toggle-group" data-group="${code}">Group ${code}</button>`
                    : `<h3>Group ${code}</h3>`}
                <p class="fm-subtle">${group.table?.length || 0} teams</p>
            </div></div>
            ${table(Array.isArray(group.table) ? group.table : [])}
            <div class="fm-nt-group-schedule" hidden>${groupSchedule(group)}</div>
        </section>`;
    }

    function competitionBody(data) {
        const groups = Array.isArray(data.groups) ? data.groups : [];
        const rounds = Array.isArray(data.rounds) ? data.rounds : [];
        return `${groups.map(group => groupSection({ ...group, week: data.week })).join('')}
        ${rounds.length ? `<section class="fm-panel"><div class="fm-panel-head"><div>
            <h3>Results and bracket</h3><p class="fm-subtle">${rounds.length} rounds</p></div></div>
            ${rounds.map(round => `<div class="fm-cup-round"><div class="fm-cup-round-head">
                <span>${escapeHtml(round.name || `Round ${round.round}`)}</span>
                <span>${round.ties?.length || 0} ties</span></div>
                ${(round.ties || []).map(tie => `<div class="fm-cup-tie">
                    <span>${teamLink(tie.homeName, tie.homeIso)}</span>
                    <span class="fm-cup-tie-sep">${tie.played ? `${escapeHtml(tie.homeGoals ?? '')} – ${escapeHtml(tie.awayGoals ?? '')}` : 'v'}</span>
                    <span>${teamLink(tie.awayName, tie.awayIso)}</span>
                </div>`).join('')}
            </div>`).join('')}
        </section>` : ''}
        ${!groups.length && !rounds.length ? '<section class="fm-panel"><p class="fm-empty">This competition has not been drawn yet.</p></section>' : ''}`;
    }

    /** Opens a group on its country page, or tells the caller to go back if it cannot. */
    function openCountry(iso) {
        const code = String(iso || '').toUpperCase();
        if (!code) return;
        if (typeof window.loadPage === 'function') {
            window.loadPage('country', { simulatedCountry: code });
            return;
        }
        if (typeof window.openCountryPage === 'function') {
            window.openCountryPage(code);
        }
    }

    function wire(mainContent) {
        mainContent.querySelectorAll('.js-toggle-group').forEach(button => {
            button.addEventListener('click', () => {
                const panel = mainContent.querySelector(`[data-group="${button.dataset.group}"]`);
                const schedule = panel?.querySelector('.fm-nt-group-schedule');
                if (!schedule) return;
                schedule.hidden = !schedule.hidden;
            });
        });
        mainContent.querySelectorAll('.js-country').forEach(button => {
            button.addEventListener('click', () => openCountry(button.dataset.countryIso));
        });
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
            wire(mainContent);
        } catch (error) {
            console.error('Failed to load national tournament:', error);
            mainContent.innerHTML = '<div class="manager-card"><h2>Error</h2><p>Could not load this national competition.</p></div>';
        }
    }

    return { load };
}
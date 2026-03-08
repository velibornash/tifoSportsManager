import { backButtonHtml } from './ui/components.js';

function htmlEscape(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function normalizePercent(value, fallback = 78) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return fallback;
    if (numeric <= 20) return Math.max(35, Math.min(100, Math.round(numeric * 5)));
    return Math.max(35, Math.min(100, Math.round(numeric)));
}

function buildStars(score) {
    const filled = Math.max(1, Math.min(5, Math.round((Number(score) || 52) / 18)));
    return `<div class="fm-stars">${Array.from({ length: 5 }, (_, index) => `<span class="star${index < filled ? ' on' : ''}"></span>`).join('')}</div>`;
}

function moraleMeta(player) {
    const form = Number(player?.form);
    if (Number.isFinite(form) && form >= 7.8) return { icon: '&#9650;', label: 'High', className: 'up' };
    if (Number.isFinite(form) && form <= 5.8) return { icon: '&#9660;', label: 'Low', className: 'down' };
    return { icon: '&#9644;', label: 'Stable', className: 'flat' };
}

function badgeDeck(player) {
    const badges = [];
    const form = Number(player?.form);
    const goals = Number(player?.totalGoals ?? player?.goals ?? 0);
    const assists = Number(player?.totalAssists ?? player?.assists ?? 0);

    if (player?.injured) badges.push('<span class="fm-badge fm-badge-inj">INJ</span>');
    if (Number.isFinite(form) && form >= 7.8) badges.push('<span class="fm-badge fm-badge-hot">HOT</span>');
    if (Number.isFinite(form) && form <= 5.8) badges.push('<span class="fm-badge fm-badge-cold">LOW</span>');
    if (goals >= 5) badges.push('<span class="fm-badge fm-badge-goal">GLS</span>');
    if (assists >= 5) badges.push('<span class="fm-badge fm-badge-ast">AST</span>');

    return badges.length ? badges.join('') : '<span class="fm-badge fm-badge-fit">FIT</span>';
}

export function buildSquadTableHtml(players, options = {}) {
    const rows = Array.isArray(players) ? players : [];
    const rowClass = options.rowClass || 'league-player-card';
    const teamId = options.teamId ?? '';
    const teamName = options.teamName ?? '';
    const emptyText = options.emptyText || 'No players found.';

    if (!rows.length) {
        return `<div class="fm-empty">${htmlEscape(emptyText)}</div>`;
    }

    return `
        <div class="fm-squad-wrap">
            <table class="fm-squad">
                <thead>
                    <tr>
                        <th class="sq-inf"></th>
                        <th class="sq-name">Name</th>
                        <th>Position</th>
                        <th class="sq-age">Age</th>
                        <th class="sq-ability">Ability</th>
                        <th class="sq-potential">Potential</th>
                        <th class="sq-cond">Condition</th>
                        <th class="sq-morale">Morale</th>
                        <th class="sq-games">Apps</th>
                        <th class="sq-goals">Gls</th>
                        <th class="sq-goals">Ast</th>
                        <th class="sq-rating">Av Rat</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows.map(player => {
                        const overall = Number(player?.overall ?? 0);
                        const averageRating = Number(player?.averageRating10);
                        const condition = normalizePercent(player?.staminaExact ?? player?.stamina);
                        const morale = moraleMeta(player);
                        const appearances = Number(player?.played ?? player?.matchesPlayed ?? 0);
                        const goals = Number(player?.totalGoals ?? player?.goals ?? 0);
                        const assists = Number(player?.totalAssists ?? player?.assists ?? 0);

                        return `
                            <tr class="fm-squad-row ${rowClass}" data-player-id="${player?.id ?? ''}" data-team-id="${teamId}" data-team-name="${htmlEscape(teamName)}">
                                <td class="sq-inf"><div class="fm-badge-deck">${badgeDeck(player)}</div></td>
                                <td class="sq-name"><span class="sq-player-link">${htmlEscape(player?.name || 'Unknown')}</span></td>
                                <td class="sq-pos">${htmlEscape(player?.position || '-')}</td>
                                <td class="sq-age">${player?.age ?? '-'}</td>
                                <td class="sq-ability">${buildStars(overall)}</td>
                                <td class="sq-potential">${buildStars(Math.min(99, overall + 6))}</td>
                                <td class="sq-cond">
                                    <div class="fm-cond">
                                        <div class="fm-cond-bar"><div class="fm-cond-fill" style="width:${condition}%"></div></div>
                                        <span class="fm-cond-val">${condition}%</span>
                                    </div>
                                </td>
                                <td class="sq-morale"><span class="fm-morale fm-morale-${morale.className}">${morale.icon}</span><span class="fm-morale-text">${morale.label}</span></td>
                                <td class="sq-games">${appearances > 0 ? appearances : '-'}</td>
                                <td class="sq-goals">${goals > 0 ? goals : '-'}</td>
                                <td class="sq-goals">${assists > 0 ? assists : '-'}</td>
                                <td class="sq-rating">${Number.isFinite(averageRating) && appearances > 0 ? averageRating.toFixed(1) : '-'}</td>
                            </tr>`;
                    }).join('')}
                </tbody>
            </table>
        </div>`;
}

export function bindSquadRowClicks(container, onClick, selector = '.league-player-card') {
    container.querySelectorAll(selector).forEach(row => {
        row.addEventListener('click', () => onClick(row));
    });
}

function buildActionRowHtml(actions, currentPage = '') {
    return `
        <div class="fm-club-actions">
            ${actions.map(action => {
                const currentPages = Array.isArray(action.currentPages) && action.currentPages.length
                    ? action.currentPages
                    : [action.page];
                const base = action.variant === 'primary' ? 'fm-action-btn' : 'fm-action-btn secondary';
                const className = currentPages.includes(currentPage) ? `${base} is-current` : base;
                const onclick = action.onclick || `loadPage('${action.page}')`;
                return `<button type="button" class="${className}" onclick="${onclick}">${htmlEscape(action.label)}</button>`;
            }).join('')}
        </div>`;
}

export function buildClubActionsHtml(currentPage = '') {
    return buildActionRowHtml([
        { label: 'First Team', page: 'firstTeam', variant: 'primary' },
        { label: 'Schedule', page: 'schedule' },
        { label: 'Club Profile', page: 'profile' },
        { label: 'Medical Center', page: 'medicalCenter' },
        { label: 'Juniors', page: 'juniors' },
        { label: 'Tactics', page: 'formations', currentPages: ['formations', 'tactics'] },
        { label: 'Staff', page: 'staff' },
        { label: 'Finances', page: 'finances' },
        { label: 'Transfers', page: 'transfers' },
    ], currentPage);
}

export function buildTrainingActionsHtml(currentPage = '') {
    return buildActionRowHtml([
        { label: 'Training Setup', page: 'trainingSetup', variant: 'primary', currentPages: ['training', 'trainingSetup'] },
        { label: 'Training Reports', page: 'trainingReports' },
    ], currentPage);
}

export function buildCommunityActionsHtml(currentPage = '') {
    return buildActionRowHtml([
        { label: 'Forum', page: 'forum', variant: 'primary' },
        { label: 'Chat', page: 'chat' },
        { label: 'Events', page: 'events' },
        { label: 'Initialize DB (Demo)', onclick: 'initializeDatabase()' },
        { label: 'Reset DB (Demo)', onclick: 'resetDatabase()' },
    ], currentPage);
}

export function renderPlayersView(players, title, { loadPlayer, getImageFilename }) {
    const mainContent = document.getElementById('main-content');

    if (!Array.isArray(players)) {
        mainContent.innerHTML = `<div class="manager-card" style="text-align:center; padding:40px;"><h2>No players found</h2></div>`;
        return;
    }

    const isClubSquad = /first team|juniors/i.test(title);
    const callerPage = /juniors/i.test(title) ? 'juniors' : 'firstTeam';

    mainContent.innerHTML = `
    <div class="fm-page fm-page--club">
        <section class="fm-panel fm-club-hero">
            ${backButtonHtml('Back', 'dashboard')}
            <div class="fm-club-hero-main">
                <div>
                    <div class="fm-eyebrow">${isClubSquad ? 'Club squad' : 'Squad view'}</div>
                    <h2>${htmlEscape(title)}</h2>
                    <p class="fm-subtle">Click a player row to open the full player profile.</p>
                </div>
                ${isClubSquad ? buildClubActionsHtml(callerPage) : ''}
            </div>
        </section>
        <section class="fm-panel">
            <div class="fm-panel-head">
                <h3>Squad</h3>
                <span class="fm-panel-action">${players.length} players</span>
            </div>
            ${buildSquadTableHtml(players, { rowClass: 'league-player-card', teamName: title })}
        </section>
    </div>`;

    bindSquadRowClicks(mainContent, row => {
        const playerId = Number(row.dataset.playerId);
        if (playerId) loadPlayer(playerId, callerPage);
    });
}

export function renderMatchesView(matches, title, { loadMatch, currentPage = 'schedule' } = {}) {
    const mainContent = document.getElementById('main-content');
    const matchRows = Array.isArray(matches) ? matches : [];
    const completedMatches = matchRows.filter(match => Number.isFinite(Number(match?.homeGoals)) && Number.isFinite(Number(match?.awayGoals)));
    const wins = completedMatches.filter(match => Number(match.homeGoals) > Number(match.awayGoals)).length;
    const draws = completedMatches.filter(match => Number(match.homeGoals) === Number(match.awayGoals)).length;
    const losses = completedMatches.filter(match => Number(match.homeGoals) < Number(match.awayGoals)).length;

    let html = `
    <div class="fm-page fm-page--club">
        <section class="fm-panel fm-club-hero">
            ${backButtonHtml('Back', 'dashboard')}
            <div class="fm-club-hero-main">
                <div>
                    <div class="fm-eyebrow">Club schedule</div>
                    <h2>${htmlEscape(title)}</h2>
                    <p class="fm-subtle">Every Club-opened match list now keeps the full club action row visible.</p>
                </div>
                ${buildClubActionsHtml(currentPage)}
            </div>
            <div class="fm-medical-stat-grid team-summary-grid">
                <div><strong>${matchRows.length}</strong><span>Matches</span></div>
                <div><strong>${wins}</strong><span>Wins</span></div>
                <div><strong>${draws}</strong><span>Draws</span></div>
                <div><strong>${losses}</strong><span>Losses</span></div>
            </div>
        </section>
        <section class="fm-panel">
            <div class="fm-panel-head">
                <div>
                    <h3>Match list</h3>
                    <p class="fm-subtle">Open any row to jump into detailed match data.</p>
                </div>
                <span class="fm-panel-action">${matchRows.length} items</span>
            </div>
            <div class="match-list">`;

    if (matchRows.length === 0) {
        html += `<div class="fm-empty">No matches to display.</div>`;
    } else {
        matchRows.forEach(match => {
            const homeEsc = String(match.homeTeam || '').replace(/'/g, "\\'");
            const awayEsc = String(match.awayTeam || '').replace(/'/g, "\\'");
            html += `
            <div class="match-row" data-match-id="${match.id}" data-caller="match">
                <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">Date: ${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${match.homeTeam}</span></span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${match.awayTeam}</span></span>
                </div>
            </div>`;
        });
    }

    html += `</div></section></div>`;
    mainContent.innerHTML = html;

    mainContent.onclick = (e) => {
        const row = e.target.closest('.match-row');
        if (!row || !mainContent.contains(row)) return;
        const matchId = Number(row.dataset.matchId);
        const caller = row.dataset.caller || 'match';
        if (matchId) loadMatch(matchId, caller);
    };
}

export function renderFixturesView(fixtures, title, { currentPage = 'schedule' } = {}) {
    const mainContent = document.getElementById('main-content');
    const fixtureRows = Array.isArray(fixtures) ? fixtures : [];

    let html = `
    <div class="fm-page fm-page--club">
        <section class="fm-panel fm-club-hero">
            ${backButtonHtml('Back', 'dashboard')}
            <div class="fm-club-hero-main">
                <div>
                    <div class="fm-eyebrow">Club schedule</div>
                    <h2>${htmlEscape(title)}</h2>
                    <p class="fm-subtle">Fixture pages now keep the same Club shell instead of dropping to a back-only card.</p>
                </div>
                ${buildClubActionsHtml(currentPage)}
            </div>
            <div class="fm-medical-stat-grid team-summary-grid">
                <div><strong>${fixtureRows.length}</strong><span>Fixtures</span></div>
                <div><strong>${fixtureRows.filter(fixture => fixture?.stadiumName).length}</strong><span>Venues set</span></div>
                <div><strong>${fixtureRows.filter(fixture => fixture?.matchTime).length}</strong><span>Kick-off set</span></div>
                <div><strong>${htmlEscape(title)}</strong><span>View</span></div>
            </div>
        </section>
        <section class="fm-panel">
            <div class="fm-panel-head">
                <div>
                    <h3>Upcoming fixtures</h3>
                    <p class="fm-subtle">Open a row to see the detailed fixture card.</p>
                </div>
                <span class="fm-panel-action">${fixtureRows.length} items</span>
            </div>
            <div class="match-list">`;

    if (fixtureRows.length === 0) {
        html += `<div class="fm-empty">No fixtures to display.</div>`;
    }

    fixtureRows.forEach((fixture, idx) => {
        const fixtureId = fixture.id || idx;
        const homeEsc = String(fixture.homeTeam || '').replace(/'/g, "\\'");
        const awayEsc = String(fixture.awayTeam || '').replace(/'/g, "\\'");
        html += `
        <div class="match-row upcoming-match" data-fixture-id="${fixtureId}">
            <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">Date: ${fixture.matchDate || 'N/A'} ${fixture.matchTime || ''}</div>
            <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${fixture.homeTeam}</span></span>
            <span class="score">VS</span>
            <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${fixture.awayTeam}</span></span>
            <div style="font-size:0.85em; color:#888; margin-top:6px;">Stadium: ${fixture.stadiumName || 'N/A'}</div>
        </div>`;
    });

    html += `</div></section></div>`;
    mainContent.innerHTML = html;

    mainContent.querySelectorAll('.upcoming-match[data-fixture-id]').forEach(row => {
        row.addEventListener('click', () => {
            const fixtureId = Number(row.dataset.fixtureId);
            if (fixtureId && typeof window.loadFixture === 'function') window.loadFixture(fixtureId);
        });
    });
}

export function renderLeagueMatchesView(matches, title = 'League Results', { loadMatch }) {
    const mainContent = document.getElementById('main-content');

    let html = `
    <div class="manager-card">
        ${backButtonHtml('Back', 'dashboard')}
        <h2>${title}</h2>
        <div class="match-list">`;

    if (!Array.isArray(matches) || matches.length === 0) {
        html += `<p style="text-align:center; color:#aaa;">No matches in this league yet.</p>`;
    } else {
        matches.forEach(match => {
            const homeEsc = String(match.homeTeam || '').replace(/'/g, "\\'");
            const awayEsc = String(match.awayTeam || '').replace(/'/g, "\\'");
            let badgeClass = '';
            let badgeText = '';

            if (match.homeGoals !== null && match.awayGoals !== null) {
                if (match.homeGoals > match.awayGoals) {
                    badgeClass = 'win';
                    badgeText = '1';
                } else if (match.homeGoals < match.awayGoals) {
                    badgeClass = 'loss';
                    badgeText = '2';
                } else {
                    badgeClass = 'draw';
                    badgeText = 'X';
                }
            }

            html += `
            <div class="match-row" data-match-id="${match.id}" data-caller="leagueMatches">
                <div style="font-size:0.9em; color:#aaa;">${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${match.homeTeam}</span></span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${match.awayTeam}</span></span>
                </div>
                ${badgeText ? `<span class="result-badge ${badgeClass}">${badgeText}</span>` : ''}
            </div>`;
        });
    }

    html += `</div></div>`;
    mainContent.innerHTML = html;

    mainContent.onclick = (e) => {
        const row = e.target.closest('.match-row');
        if (!row || !mainContent.contains(row)) return;
        const matchId = Number(row.dataset.matchId);
        const caller = row.dataset.caller || 'leagueMatches';
        if (matchId) loadMatch(matchId, caller);
    };
}

export function renderTableView(payload, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, escapeHtml, formatGoalDiff }) {
    const mainContent = document.getElementById('main-content');
    const safe = escapeHtml || htmlEscape;
    const data = Array.isArray(payload) ? { table: payload } : (payload || {});
    const rows = Array.isArray(data.table) ? data.table : [];
    const fixtures = Array.isArray(data.fixtures) ? data.fixtures : [];
    const topScorers = Array.isArray(data.topScorers) ? data.topScorers : [];
    const topAssists = Array.isArray(data.topAssists) ? data.topAssists : [];
    const seasons = Array.isArray(data.seasons) ? data.seasons : [];

    function zoneClass(rank, total) {
        if (rank <= 4) return 'zone-ucl';
        if (rank <= 6) return 'zone-uel';
        if (rank >= Math.max(1, total - 1)) return 'zone-rel';
        return '';
    }

    function standingsRowsHtml() {
        return rows.map((team, index) => {
            const rank = Number(team.position || index + 1);
            const wins = Number(team.wins || 0);
            const draws = Number(team.draws || 0);
            const losses = Number(team.losses || 0);
            const played = wins + draws + losses;
            const gd = Number(team.goalDifference || 0);
            return `
                <tr class="${zoneClass(rank, rows.length)} ${team.teamId ? 'js-load-team' : ''}" data-team-id="${team.teamId || ''}" data-team-name="${safe(team.name)}">
                    <td class="st-pos">${rank}</td>
                    <td class="st-club">${team.teamId ? `<span class="fm-team-link">${safe(team.name)}</span>` : safe(team.name)}</td>
                    <td>${played}</td>
                    <td>${wins}</td>
                    <td>${draws}</td>
                    <td>${losses}</td>
                    <td>${team.goalsScored ?? 0}</td>
                    <td>${team.goalsConceded ?? 0}</td>
                    <td class="st-gd">${formatGoalDiff(gd)}</td>
                    <td class="st-pts">${team.points ?? 0}</td>
                </tr>`;
        }).join('');
    }

    function fixturesHtml() {
        if (!fixtures.length) {
            return '<div class="fm-empty">No schedule available yet.</div>';
        }

        return fixtures.map(group => `
            <div class="fm-fixture-group${group.isFocusRound ? ' is-focus-round' : ''}"${group.isFocusRound ? ' data-fixture-focus="current"' : ''}>
                <div class="fm-matchday-hd">Round ${group.round}${group.label ? ` <span class="fm-round-label">${safe(group.label)}</span>` : ''}</div>
                ${(group.matches || []).map(match => `
                    <div class="fm-fixture ${match.played && match.id ? 'js-load-match is-played' : ''}" data-match-id="${match.id || ''}">
                        <div class="fx-date">${safe(match.matchDate || 'TBD')}</div>
                        <div class="fx-main">
                            <span class="fx-home ${match.homeTeamId ? 'js-load-team' : ''}" data-team-id="${match.homeTeamId || ''}" data-team-name="${safe(match.homeTeam)}">${safe(match.homeTeam)}</span>
                            <span class="fx-score ${match.played ? '' : 'pending'}">${match.played ? `${match.homeGoals ?? 0} – ${match.awayGoals ?? 0}` : '–'}</span>
                            <span class="fx-away ${match.awayTeamId ? 'js-load-team' : ''}" data-team-id="${match.awayTeamId || ''}" data-team-name="${safe(match.awayTeam)}">${safe(match.awayTeam)}</span>
                        </div>
                    </div>`).join('')}
            </div>`).join('');
    }

    function statTableHtml(items, type) {
        if (!items.length) {
            return '<div class="fm-empty">No data available yet.</div>';
        }
        return `
            <table class="fm-player-stats">
                <thead>
                    <tr>
                        <th class="ps-pos">#</th>
                        <th class="ps-name">Player</th>
                        <th class="ps-val">${type === 'goals' ? 'Gls' : 'Ast'}</th>
                    </tr>
                </thead>
                <tbody>
                    ${items.slice(0, 8).map((item, index) => `
                        <tr>
                            <td class="ps-pos">${index + 1}</td>
                            <td class="ps-name">
                                ${item.playerId && item.teamId
                                    ? `<span class="fm-player-link js-load-league-player" data-player-id="${item.playerId}" data-team-id="${item.teamId}" data-team-name="${safe(item.teamName || 'Team')}">${safe(item.playerName || item.name || 'Unknown')}</span>`
                                    : safe(item.playerName || item.name || 'Unknown')}
                                <span class="ps-team">${item.teamId ? `<span class="fm-team-link js-load-team" data-team-id="${item.teamId}" data-team-name="${safe(item.teamName)}">${safe(item.teamName)}</span>` : safe(item.teamName || 'No Team')}</span>
                            </td>
                            <td class="ps-val">${type === 'goals' ? (item.goals ?? 0) : (item.assists ?? 0)}</td>
                        </tr>`).join('')}
                </tbody>
            </table>`;
    }

    mainContent.innerHTML = `
    <div class="fm-page fm-page--league">
        <div class="fm-page-toolbar">
            ${backButtonHtml('Back', 'dashboard')}
            <div class="fm-page-title-block">
                <div class="fm-eyebrow">Open-football inspired league view</div>
                <h2 class="league-table-title">Serbian Superliga${data.selectedSeasonNumber ? ` · Season ${data.selectedSeasonNumber}` : ''}</h2>
            </div>
            ${seasons.length ? `
            <label class="fm-season-select-wrap">
                <span>Season</span>
                <select id="league-overview-season-select" class="fm-season-select">
                    ${seasons.map(season => `<option value="${season.seasonYear}" ${season.seasonYear === data.selectedSeason ? 'selected' : ''}>Season ${season.seasonNumber}</option>`).join('')}
                </select>
            </label>` : ''}
        </div>

        <div class="fm-grid-top">
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <h3>League Table</h3>
                    <span class="fm-panel-action">Clubs clickable</span>
                </div>
                <table class="fm-standings">
                    <thead>
                        <tr>
                            <th class="st-pos">#</th>
                            <th class="st-club">Club</th>
                            <th>P</th>
                            <th>W</th>
                            <th>D</th>
                            <th>L</th>
                            <th>GF</th>
                            <th>GA</th>
                            <th>GD</th>
                            <th class="st-pts">Pts</th>
                        </tr>
                    </thead>
                    <tbody>${standingsRowsHtml()}</tbody>
                </table>
                <div class="fm-legend">
                    <span><i class="legend-dot ucl"></i> Europe</span>
                    <span><i class="legend-dot uel"></i> Playoff race</span>
                    <span><i class="legend-dot rel"></i> Relegation zone</span>
                </div>
            </section>

            <section class="fm-panel">
                <div class="fm-panel-head">
                    <h3>Fixtures & Results</h3>
                    <span class="fm-panel-action">Current focus</span>
                </div>
                <div class="fm-fixtures-scroll">
                    <div class="fm-fixtures">${fixturesHtml()}</div>
                </div>
            </section>
        </div>

        <div class="fm-grid-bottom">
            <section class="fm-panel">
                <div class="fm-panel-head"><h3>Top Scorers</h3></div>
                ${statTableHtml(topScorers, 'goals')}
            </section>
            <section class="fm-panel">
                <div class="fm-panel-head"><h3>Top Assisters</h3></div>
                ${statTableHtml(topAssists, 'assists')}
            </section>
        </div>
    </div>`;

    mainContent.querySelectorAll('.js-load-team').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            const teamId = Number(node.dataset.teamId);
            const teamName = node.dataset.teamName || 'Team';
            if (teamId) loadLeagueTeam(teamId, teamName);
        });
    });

    mainContent.querySelectorAll('.js-load-league-player').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            const playerId = Number(node.dataset.playerId);
            const teamId = Number(node.dataset.teamId);
            const teamName = node.dataset.teamName || 'Team';
            if (playerId && teamId) loadLeagueTeamPlayer(playerId, teamId, teamName);
        });
    });

    mainContent.querySelectorAll('.js-load-match').forEach(node => {
        node.addEventListener('click', () => {
            const matchId = Number(node.dataset.matchId);
            if (matchId) loadMatch(matchId, 'leagueMatches');
        });
    });

    const seasonSelect = document.getElementById('league-overview-season-select');
    if (seasonSelect) {
        seasonSelect.addEventListener('change', () => loadLeagueTable(Number(seasonSelect.value)));
    }

    const fixturesScroll = mainContent.querySelector('.fm-fixtures-scroll');
    const focusGroup = mainContent.querySelector('[data-fixture-focus="current"]');
    if (fixturesScroll && focusGroup) {
        requestAnimationFrame(() => {
            const targetTop = Math.max(0, focusGroup.offsetTop - Math.max(24, Math.round(fixturesScroll.clientHeight * 0.28)));
            fixturesScroll.scrollTop = targetTop;
        });
    }
}

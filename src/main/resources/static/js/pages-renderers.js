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

function clampPercent(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return 0;
    return Math.max(0, Math.min(100, Math.round(numeric)));
}

function conditionPercent(player) {
    const fatigue = Number(player?.fatigue);
    if (Number.isFinite(fatigue)) {
        return clampPercent(100 - fatigue);
    }
    return normalizePercent(player?.staminaExact ?? player?.stamina);
}

function buildStars(score) {
    const filled = Math.max(1, Math.min(5, Math.round((Number(score) || 52) / 18)));
    return `<div class="fm-stars">${Array.from({ length: 5 }, (_, index) => `<span class="star${index < filled ? ' on' : ''}"></span>`).join('')}</div>`;
}

function buildEmptyStars() {
    return `<div class="fm-stars is-empty">${Array.from({ length: 5 }, () => '<span class="star"></span>').join('')}</div>`;
}

function formatDecimal(value, digits = 1, fallback = '—') {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric.toFixed(digits) : fallback;
}

function formatPercentLabel(value) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? `${clampPercent(numeric)}%` : '—';
}

function resolveFixtureVenue(fixture) {
    return fixture?.stadium || fixture?.stadiumName || 'N/A';
}

function resolveFixtureIdentifier(fixture, fallback = '') {
    return fixture?.fixtureId ?? fixture?.id ?? fallback;
}

function buildFixtureSnapshotHtml(teamName, sideLabel, strength, form, safe = htmlEscape) {
    const numericStrength = Number(strength);
    const hasStrength = Number.isFinite(numericStrength);
    const numericForm = Number(form);
    const stars = hasStrength ? buildStars(numericStrength) : buildEmptyStars();

    return `
        <div class="fx-snapshot">
            <div class="fx-snapshot-kicker">${safe(sideLabel)}</div>
            <div class="fx-snapshot-team">${safe(teamName || sideLabel)}</div>
            <div class="fx-snapshot-stars">${stars}</div>
            <div class="fx-snapshot-meta">
                <span>OVR <strong>${hasStrength ? Math.round(numericStrength) : '—'}</strong></span>
                <span>Form <strong>${Number.isFinite(numericForm) ? numericForm.toFixed(1) : '—'}</strong></span>
            </div>
        </div>`;
}

function buildFixturePredictionHtml(prediction, safe = htmlEscape) {
    if (!prediction) return '';

    const confidence = Number(prediction?.confidence);
    return `
        <div class="fx-prediction">
            <div class="fx-prediction-head">
                <span class="fx-prediction-title">Match preview</span>
                <span class="fx-confidence">${Number.isFinite(confidence) ? `${Math.round(confidence)}% conf` : 'Heuristic'}</span>
            </div>
            <div class="fx-prob-grid">
                <div class="fx-prob"><span class="fx-prob-label">1</span><strong>${formatPercentLabel(prediction.homeWinProbability)}</strong></div>
                <div class="fx-prob"><span class="fx-prob-label">X</span><strong>${formatPercentLabel(prediction.drawProbability)}</strong></div>
                <div class="fx-prob"><span class="fx-prob-label">2</span><strong>${formatPercentLabel(prediction.awayWinProbability)}</strong></div>
            </div>
            <div class="fx-xg">xG ${formatDecimal(prediction.expectedHomeGoals, 2)} : ${formatDecimal(prediction.expectedAwayGoals, 2)}</div>
            ${prediction.analysis ? `<div class="fx-prediction-note">${safe(prediction.analysis)}</div>` : ''}
        </div>`;
}

function buildResultBadgeHtml(label, className) {
    if (!label) return '';
    return `<span class="result-badge ${className}">${htmlEscape(label)}</span>`;
}

function resolveLeagueResultBadge(match) {
    const homeGoals = Number(match?.homeGoals);
    const awayGoals = Number(match?.awayGoals);
    if (!Number.isFinite(homeGoals) || !Number.isFinite(awayGoals)) return null;
    if (homeGoals > awayGoals) return { label: '1', className: 'win' };
    if (homeGoals < awayGoals) return { label: '2', className: 'loss' };
    return { label: 'X', className: 'draw' };
}

function resolveClubResultBadge(match, currentTeamName = '') {
    const homeGoals = Number(match?.homeGoals);
    const awayGoals = Number(match?.awayGoals);
    if (!Number.isFinite(homeGoals) || !Number.isFinite(awayGoals)) return null;

    const normalizedTeamName = String(currentTeamName || '').trim().toLowerCase();
    const normalizedHomeName = String(match?.homeTeam || '').trim().toLowerCase();
    const normalizedAwayName = String(match?.awayTeam || '').trim().toLowerCase();

    let goalDiff = homeGoals - awayGoals;
    if (normalizedTeamName && normalizedTeamName === normalizedAwayName) {
        goalDiff = awayGoals - homeGoals;
    } else if (normalizedTeamName && normalizedTeamName !== normalizedHomeName) {
        return null;
    }

    if (goalDiff > 0) return { label: 'W', className: 'win' };
    if (goalDiff < 0) return { label: 'L', className: 'loss' };
    return { label: 'D', className: 'draw' };
}

export function buildScheduleFixtureCardHtml(match, options = {}) {
    const safe = options.safe || htmlEscape;
    const matchCaller = options.matchCaller || 'leagueMatches';
    const backTarget = options.backTarget || 'leagueTable';
    const pendingLabel = options.pendingLabel || 'VS';
    const allowFixtureClick = options.allowFixtureClick !== false;
    const showPlayedInsights = options.showPlayedInsights === true;
    const allowInsights = options.showInsights !== false;
    const seasonYear = options.seasonYear ?? '';
    const fixtureId = resolveFixtureIdentifier(match, '');
    const venue = resolveFixtureVenue(match);
    const hasSnapshotData = [match?.homeTeamStrength, match?.awayTeamStrength, match?.homeTeamForm, match?.awayTeamForm]
        .some(value => Number.isFinite(Number(value)));
    const showInsights = allowInsights && hasSnapshotData && (!match?.played || showPlayedInsights);
    const classes = ['fm-fixture'];

    if (match?.played && match?.id) classes.push('js-load-match', 'is-played');
    if (!match?.played && allowFixtureClick && fixtureId) classes.push('js-load-fixture');
    if (showInsights) classes.push('has-insights');

    const h2h = match?.h2h;
    const playedBadge = match?.played ? resolveLeagueResultBadge(match) : null;
    const h2hSummary = Number(h2h?.played || 0) > 0
        ? `<div class="fx-h2h"><strong>${safe(h2h.summary || 'H2H')}</strong>${h2h.lastMeetingSummary ? `<span>${safe(h2h.lastMeetingSummary)}</span>` : ''}</div>`
        : '';

    return `
        <div class="${classes.join(' ')}"
             data-match-id="${match?.played && match?.id ? match.id : ''}"
             data-caller="${safe(matchCaller)}"
             data-back-target="${safe(backTarget)}"
             data-fixture-id="${!match?.played && allowFixtureClick ? fixtureId : ''}">
            <div class="fx-topline">
                <span class="fx-date">${safe(match?.matchDate || 'TBD')}</span>
                <span class="fx-venue">${safe(venue)}</span>
            </div>
            <div class="fx-main">
                <span class="fx-home ${match?.homeTeamId ? 'js-load-team' : ''}" data-team-id="${match?.homeTeamId || ''}" data-team-name="${safe(match?.homeTeam || 'Home')}" data-season-year="${seasonYear}">${safe(match?.homeTeam || 'Home')}</span>
                <span class="fx-score ${match?.played ? '' : 'pending'}">${match?.played ? `${match?.homeGoals ?? 0} – ${match?.awayGoals ?? 0}` : safe(pendingLabel)}</span>
                <span class="fx-away ${match?.awayTeamId ? 'js-load-team' : ''}" data-team-id="${match?.awayTeamId || ''}" data-team-name="${safe(match?.awayTeam || 'Away')}" data-season-year="${seasonYear}">${safe(match?.awayTeam || 'Away')}</span>
            </div>
            ${playedBadge ? `<div class="fx-result-chip">${buildResultBadgeHtml(playedBadge.label, playedBadge.className)}</div>` : ''}
            ${showInsights ? `
                <div class="fx-insights">
                    ${buildFixtureSnapshotHtml(match?.homeTeam, 'Home', match?.homeTeamStrength, match?.homeTeamForm, safe)}
                    ${buildFixtureSnapshotHtml(match?.awayTeam, 'Away', match?.awayTeamStrength, match?.awayTeamForm, safe)}
                </div>` : ''}
            ${h2hSummary}
        </div>`;
}

function buildFixtureGroupsHtml(groups, options = {}) {
    const safe = options.safe || htmlEscape;
    const fixtureGroups = Array.isArray(groups) ? groups : [];
    if (!fixtureGroups.length) {
        return '<div class="fm-empty">No schedule available yet.</div>';
    }

    return fixtureGroups.map(group => `
        <div class="fm-fixture-group${group.isFocusRound ? ' is-focus-round' : ''}"${group.isFocusRound ? ' data-fixture-focus="current"' : ''}>
            <div class="fm-matchday-hd">Round ${group.round}${group.label ? ` <span class="fm-round-label">${safe(group.label)}</span>` : ''}</div>
            ${(group.matches || []).map(match => buildScheduleFixtureCardHtml(match, options)).join('')}
        </div>`).join('');
}

export function bindScheduleInteractions(container, handlers = {}) {
    if (!container) return;

    const onLoadTeam = handlers.loadLeagueTeam || window.loadLeagueTeam;
    const onLoadMatch = handlers.loadMatch || window.loadMatch;
    const onLoadFixture = handlers.loadFixture || window.loadFixture;

    container.querySelectorAll('.js-load-team').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            const teamId = Number(node.dataset.teamId);
            const teamName = node.dataset.teamName || 'Team';
            const seasonYear = node.dataset.seasonYear ? Number(node.dataset.seasonYear) : null;
            if (teamId && typeof onLoadTeam === 'function') onLoadTeam(teamId, teamName, seasonYear);
        });
    });

    container.querySelectorAll('.js-load-match').forEach(node => {
        node.addEventListener('click', () => {
            const matchId = Number(node.dataset.matchId);
            const caller = node.dataset.caller || 'leagueMatches';
            if (matchId && typeof onLoadMatch === 'function') onLoadMatch(matchId, caller);
        });
    });

    container.querySelectorAll('.js-load-fixture').forEach(node => {
        node.addEventListener('click', () => {
            const fixtureId = Number(node.dataset.fixtureId);
            const backTarget = node.dataset.backTarget || 'leagueTable';
            if (fixtureId && typeof onLoadFixture === 'function') onLoadFixture(fixtureId, { backTarget });
        });
    });
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
                        const condition = conditionPercent(player);
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
        { label: 'Tactic Editor', page: 'tacticEditor', currentPages: ['tacticEditor'] },
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
        { label: 'Chat', page: 'chat', variant: 'primary', currentPages: ['forum', 'chat', 'events'] },
    ], currentPage);
}

export function renderPlayersView(players, title, options = {}) {
    const {
        loadPlayer,
        getImageFilename,
        teamId = '',
        teamName = '',
        milestonesHtml = '',
        medicalOverview = null
    } = options;
    const mainContent = document.getElementById('main-content');

    if (!Array.isArray(players)) {
        mainContent.innerHTML = `<div class="manager-card" style="text-align:center; padding:40px;"><h2>No players found</h2></div>`;
        return;
    }

    const isClubSquad = /first team|juniors/i.test(title);
    const isEnhancedFirstTeam = /first team/i.test(title);
    const callerPage = /juniors/i.test(title) ? 'juniors' : 'firstTeam';

    if (isEnhancedFirstTeam) {
        const resolvedTeamName = teamName || title;
        const avgOverall = players.length
            ? (players.reduce((sum, player) => sum + Number(player?.overall || 0), 0) / players.length).toFixed(1)
            : '-';
        const avgAge = players.length
            ? (players.reduce((sum, player) => sum + Number(player?.age || 0), 0) / players.length).toFixed(1)
            : '-';
        const totalGoals = players.reduce((sum, player) => sum + Number(player?.totalGoals ?? player?.goals ?? 0), 0);
        const totalAssists = players.reduce((sum, player) => sum + Number(player?.totalAssists ?? player?.assists ?? 0), 0);
        const averageCondition = medicalOverview?.averageConditionPercent ?? (players.length
            ? Math.round(players.reduce((sum, player) => sum + conditionPercent(player), 0) / players.length)
            : 100);
        const injuryCount = medicalOverview?.injuredCount ?? players.filter(player => player?.injured).length;
        const rehabCount = medicalOverview?.rehabCount ?? players.filter(player => player?.injured || conditionPercent(player) < 82).length;

        mainContent.innerHTML = `
        <div class="fm-page fm-page--team-detail">
            <section class="fm-panel fm-club-hero">
                ${backButtonHtml('Back', 'dashboard')}
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club squad</div>
                        <h2>${htmlEscape(title)}</h2>
                        <p class="fm-subtle">First Team now uses the same stronger club shell and responsive panel layout as the better-scaling team views.</p>
                    </div>
                    ${buildClubActionsHtml(callerPage)}
                </div>
                <div class="fm-medical-stat-grid team-summary-grid">
                    <div><strong>${players.length}</strong><span>Players</span></div>
                    <div><strong>${avgOverall}</strong><span>Avg OVR</span></div>
                    <div><strong>${avgAge}</strong><span>Avg age</span></div>
                    <div><strong>${totalGoals}/${totalAssists}</strong><span>Goals / assists</span></div>
                </div>
            </section>
            ${milestonesHtml ? `
            <section class="fm-panel fm-milestone-board-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Club milestones</h3>
                        <p class="fm-subtle">Season context stays visible directly on the First Team page too.</p>
                    </div>
                    <span class="fm-panel-action">Club area</span>
                </div>
                ${milestonesHtml}
            </section>` : ''}
            <div class="fm-team-layout has-side-panel">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <h3>Squad</h3>
                        <span class="fm-panel-action">${players.length} registered</span>
                    </div>
                    ${buildSquadTableHtml(players, {
                        rowClass: 'league-player-card',
                        teamId,
                        teamName: resolvedTeamName,
                        emptyText: 'No registered players found for this team.'
                    })}
                </section>
                <aside class="fm-panel fm-medical-panel">
                    <div class="fm-panel-head">
                        <h3>Medical Center</h3>
                        <span class="fm-panel-action">Club area</span>
                    </div>
                    <div class="fm-medical-icon">&#10010; &#129658;</div>
                    <p class="fm-subtle">Condition now follows fatigue consistently here and on the player page, with recovery cases always visible.</p>
                    <div class="fm-medical-stat-grid">
                        <div><strong>${injuryCount}</strong><span>Injuries</span></div>
                        <div><strong>${rehabCount}</strong><span>Recovery queue</span></div>
                        <div><strong>${averageCondition}%</strong><span>Avg condition</span></div>
                    </div>
                    <button type="button" class="fm-action-btn secondary" onclick="loadPage('medicalCenter')">Open Medical Center</button>
                </aside>
            </div>
        </div>`;

        bindSquadRowClicks(mainContent, row => {
            const playerId = Number(row.dataset.playerId);
            if (playerId) loadPlayer(playerId, callerPage);
        });
        return;
    }

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

export function renderMatchesView(matches, title, { loadMatch, currentPage = 'schedule', currentTeamName = '' } = {}) {
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
            const clubResultBadge = resolveClubResultBadge(match, currentTeamName);
            html += `
            <div class="match-row" data-match-id="${match.id}" data-caller="match">
                <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">Date: ${match.matchDate || 'N/A'}</div>
                <div class="match-teams">
                    <span class="team-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${match.homeTeam}</span></span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="team-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${match.awayTeam}</span></span>
                </div>
                ${clubResultBadge ? buildResultBadgeHtml(clubResultBadge.label, clubResultBadge.className) : ''}
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
    const playedCount = fixtureRows.filter(fixture => fixture?.played).length;
    const upcomingCount = fixtureRows.filter(fixture => !fixture?.played).length;
    const venueCount = fixtureRows.filter(fixture => resolveFixtureVenue(fixture) !== 'N/A').length;
    const h2hCount = fixtureRows.filter(fixture => Number(fixture?.h2h?.played || 0) > 0).length;

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
                <div><strong>${fixtureRows.length}</strong><span>Total</span></div>
                <div><strong>${playedCount}</strong><span>Played</span></div>
                <div><strong>${upcomingCount}</strong><span>Upcoming</span></div>
                <div><strong>${h2hCount}</strong><span>H2H notes</span></div>
            </div>
        </section>
        <section class="fm-panel">
            <div class="fm-panel-head">
                <div>
                    <h3>Schedule timeline</h3>
                    <p class="fm-subtle">Played matches open match details, while upcoming fixtures keep a lighter OVR/form snapshot.</p>
                </div>
                <span class="fm-panel-action">${venueCount} venues · ${h2hCount} H2H notes</span>
            </div>
            <div class="fm-fixtures">`;

    if (fixtureRows.length === 0) {
        html += `<div class="fm-empty">No schedule entries to display.</div>`;
    }

    html += fixtureRows
        .map((fixture, idx) => buildScheduleFixtureCardHtml(fixture, {
            matchCaller: 'match',
            pendingLabel: 'VS',
            allowFixtureClick: true,
            safe: htmlEscape,
            fallbackFixtureId: idx
        }))
        .join('');

    html += `</div></section></div>`;
    mainContent.innerHTML = html;
    bindScheduleInteractions(mainContent);
}

export function renderLeagueMatchesView(matches, title = 'League Results', { loadMatch, backTarget = 'dashboard', caller = 'leagueMatches' } = {}) {
    const mainContent = document.getElementById('main-content');

    let html = `
    <div class="manager-card">
        ${backButtonHtml('Back', backTarget)}
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
            <div class="match-row" data-match-id="${match.id}" data-caller="${caller}">
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

export function renderTableView(payload, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml, formatGoalDiff }) {
    const mainContent = document.getElementById('main-content');
    const safe = escapeHtml || htmlEscape;
    const data = Array.isArray(payload) ? { table: payload } : (payload || {});
    const rows = Array.isArray(data.table) ? data.table : [];
    const fixtures = Array.isArray(data.fixtures) ? data.fixtures : [];
    const topScorers = Array.isArray(data.topScorers) ? data.topScorers : [];
    const topAssists = Array.isArray(data.topAssists) ? data.topAssists : [];
    const seasons = Array.isArray(data.seasons) ? data.seasons : [];
    const selectedSeason = data.selectedSeason ?? null;
    const milestones = data.milestones || {};
    const seasonSummary = data.seasonSummary || {};
    const leagueTitle = safe(data.leagueName || 'League');
    const backTarget = data.backTarget || 'dashboard';
    const fixtureBackTarget = data.fixtureBackTarget || 'leagueTable';
    const matchCaller = data.matchCaller || 'leagueTable';

    function zoneClass(rank, total) {
        if (rank === 1) return 'zone-title';
        if (rank <= Math.min(4, total)) return 'zone-top';
        if (rank >= Math.max(1, total - 1)) return 'zone-rel';
        return '';
    }

    function ownershipBadgeHtml(humanControlled) {
        if (typeof humanControlled !== 'boolean') return '';
        return `<span class="fm-badge ${humanControlled ? 'fm-badge-owner' : 'fm-badge-ai'}">${humanControlled ? 'PLAYER' : 'AI'}</span>`;
    }

    function standingsRowsHtml() {
        return rows.map((team, index) => {
            const rank = Number(team.position || index + 1);
            const wins = Number(team.wins || 0);
            const draws = Number(team.draws || 0);
            const losses = Number(team.losses || 0);
            const played = wins + draws + losses;
            const gd = Number(team.goalDifference || 0);
            const teamLabel = team.teamId ? `<span class="fm-team-link">${safe(team.name)}</span>` : safe(team.name);
            return `
                <tr class="${zoneClass(rank, rows.length)} ${team.teamId ? 'js-load-team' : ''}" data-team-id="${team.teamId || ''}" data-team-name="${safe(team.name)}" data-season-year="${selectedSeason ?? ''}">
                    <td class="st-pos">${rank}</td>
                    <td class="st-club"><div class="fm-club-cell">${teamLabel}${ownershipBadgeHtml(team.humanControlled)}</div></td>
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
        return buildFixtureGroupsHtml(fixtures, {
            safe,
            matchCaller,
            backTarget: fixtureBackTarget,
            pendingLabel: '–',
            allowFixtureClick: true,
            showInsights: true,
            showPlayedInsights: false,
            seasonYear: selectedSeason
        });
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
                                    ? `<span class="fm-player-link js-load-league-player" data-player-id="${item.playerId}" data-team-id="${item.teamId}" data-team-name="${safe(item.teamName || 'Team')}" data-season-year="${selectedSeason ?? ''}">${safe(item.playerName || item.name || 'Unknown')}</span>`
                                    : safe(item.playerName || item.name || 'Unknown')}
                                <span class="ps-team">${item.teamId ? `<span class="fm-team-link js-load-team" data-team-id="${item.teamId}" data-team-name="${safe(item.teamName)}" data-season-year="${selectedSeason ?? ''}">${safe(item.teamName)}</span>` : safe(item.teamName || 'No Team')}</span>
                            </td>
                            <td class="ps-val">${type === 'goals' ? (item.goals ?? 0) : (item.assists ?? 0)}</td>
                        </tr>`).join('')}
                </tbody>
            </table>`;
    }

    function formatAttendance(value) {
        const numeric = Number(value || 0);
        return numeric > 0 ? numeric.toLocaleString() : '—';
    }

    function milestoneCardHtml(title, value, meta, extraClass = '') {
        return `
            <article class="fm-milestone-card ${extraClass}">
                <div class="fm-milestone-kicker">${safe(title)}</div>
                <div class="fm-milestone-value">${value || '—'}</div>
                <div class="fm-milestone-meta">${meta || 'No milestone logged yet.'}</div>
            </article>`;
    }

    function milestoneBoardHtml() {
        const scorer = milestones.topScorer;
        const assist = milestones.topAssist;
        const biggestWin = milestones.biggestWin;
        const biggestLoss = milestones.biggestLoss;
        const attendance = milestones.attendance;

        return `
            <div class="fm-milestone-grid">
                ${milestoneCardHtml(
                    'Top scorer',
                    scorer?.playerName ? safe(scorer.playerName) : '—',
                    scorer?.playerName ? `${safe(scorer.teamName || 'No team')} · ${Number(scorer.value || 0)} goals` : 'No goals filed yet.'
                )}
                ${milestoneCardHtml(
                    'Top assist',
                    assist?.playerName ? safe(assist.playerName) : '—',
                    assist?.playerName ? `${safe(assist.teamName || 'No team')} · ${Number(assist.value || 0)} assists` : 'No assists filed yet.'
                )}
                ${milestoneCardHtml(
                    'Biggest win',
                    biggestWin?.summary ? safe(biggestWin.summary) : '—',
                    biggestWin?.context ? safe(biggestWin.context) : 'Waiting for a standout result.'
                )}
                ${milestoneCardHtml(
                    'Heaviest loss',
                    biggestLoss?.summary ? safe(biggestLoss.summary) : '—',
                    biggestLoss?.context ? safe(biggestLoss.context) : 'No heavy defeat registered yet.'
                )}
                ${milestoneCardHtml(
                    'Attendance',
                    formatAttendance(attendance?.averageAttendance),
                    attendance?.averageAttendance
                        ? `High ${formatAttendance(attendance.highestAttendance)} (${safe(attendance.highestMatchLabel || '—')}) · Low ${formatAttendance(attendance.lowestAttendance)} (${safe(attendance.lowestMatchLabel || '—')}) · ${safe(attendance.insight || '')}`
                        : safe(attendance?.insight || 'Crowd data will appear once played fixtures start filing gates.'),
                    'attendance'
                )}
            </div>`;
    }

    function summaryListHtml(items, emptyText, formatter) {
        if (!Array.isArray(items) || !items.length) {
            return `<div class="fm-subtle fm-season-summary-empty">${safe(emptyText)}</div>`;
        }
        return items.map(item => formatter(item)).join('');
    }

    function seasonSummaryBoardHtml() {
        const directPromotions = Array.isArray(seasonSummary.directPromotions) ? seasonSummary.directPromotions : [];
        const directRelegations = Array.isArray(seasonSummary.directRelegations) ? seasonSummary.directRelegations : [];
        const playoffResults = Array.isArray(seasonSummary.playoffResults) ? seasonSummary.playoffResults : [];
        if (!directPromotions.length && !directRelegations.length && !playoffResults.length) {
            return '';
        }

        const latestSeasonYear = seasons[seasons.length - 1]?.seasonYear ?? selectedSeason;
        const isArchive = latestSeasonYear != null && selectedSeason != null && Number(selectedSeason) < Number(latestSeasonYear);

        return `
            <section class="fm-panel fm-season-summary-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>${isArchive ? 'Promotion & Relegation Archive' : 'Promotion & Relegation'}</h3>
                        <p class="fm-subtle">${isArchive ? 'Who went up, who went down, and how the playoff ended in this archived season.' : 'Current snapshot of direct movement and playoff outcome for the selected season.'}</p>
                    </div>
                    <span class="fm-panel-action">${isArchive ? 'Archive' : 'Season flow'}</span>
                </div>
                <div class="fm-milestone-grid fm-season-summary-grid">
                    <article class="fm-milestone-card fm-season-summary-card">
                        <div class="fm-milestone-kicker">Direct up</div>
                        <div class="fm-season-summary-list">
                            ${summaryListHtml(directPromotions, 'No direct promotion data yet.', item => `
                                <div class="fm-season-summary-row">
                                    <strong>${safe(item.team || 'Unknown')}</strong>
                                    <span>${safe(item.fromLeague || 'Tier 2')}</span>
                                </div>`)}
                        </div>
                    </article>
                    <article class="fm-milestone-card fm-season-summary-card">
                        <div class="fm-milestone-kicker">Direct down</div>
                        <div class="fm-season-summary-list">
                            ${summaryListHtml(directRelegations, 'No direct relegation data yet.', item => `
                                <div class="fm-season-summary-row">
                                    <strong>${safe(item.team || 'Unknown')}</strong>
                                    <span>${safe(item.toLeague || 'Tier 2')}</span>
                                </div>`)}
                        </div>
                    </article>
                    <article class="fm-milestone-card fm-season-summary-card">
                        <div class="fm-milestone-kicker">Playoff</div>
                        <div class="fm-season-summary-list">
                            ${summaryListHtml(playoffResults, 'No playoff fixtures logged yet.', item => `
                                <div class="fm-season-summary-row">
                                    <strong>${safe(item.homeTeam || 'Home')} ${Number(item.homeGoals ?? 0)}:${Number(item.awayGoals ?? 0)} ${safe(item.awayTeam || 'Away')}</strong>
                                    <span>Winner: ${safe(item.winner || 'TBD')}</span>
                                </div>`)}
                        </div>
                    </article>
                </div>
            </section>`;
    }

    mainContent.innerHTML = `
    <div class="fm-page fm-page--league">
        <div class="fm-page-toolbar">
            ${backButtonHtml('Back', backTarget)}
            <div class="fm-page-title-block">
                <div class="fm-eyebrow">Open-football inspired league view</div>
                <h2 class="league-table-title">${leagueTitle}${data.selectedSeasonNumber ? ` · Season ${data.selectedSeasonNumber}` : ''}</h2>
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
                    <span><i class="legend-dot ucl"></i> Title pace</span>
                    <span><i class="legend-dot uel"></i> Top places</span>
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

        <section class="fm-panel fm-milestone-board-panel">
            <div class="fm-panel-head">
                <h3>Milestones</h3>
                <span class="fm-panel-action">Season board</span>
            </div>
            ${milestoneBoardHtml()}
        </section>

        ${seasonSummaryBoardHtml()}

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

    bindScheduleInteractions(mainContent, {
        loadLeagueTeam: (teamId, teamName, seasonYear) => loadLeagueTeam(teamId, teamName, { seasonYear: seasonYear ?? selectedSeason }),
        loadMatch,
        loadFixture
    });

    mainContent.querySelectorAll('.js-load-league-player').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            const playerId = Number(node.dataset.playerId);
            const teamId = Number(node.dataset.teamId);
            const teamName = node.dataset.teamName || 'Team';
            const seasonYear = node.dataset.seasonYear ? Number(node.dataset.seasonYear) : selectedSeason;
            if (playerId && teamId) loadLeagueTeamPlayer(playerId, teamId, teamName, { seasonYear });
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

export function renderLeagueScheduleView(payload, { loadLeagueSchedule, loadMatch, loadLeagueTeam, loadFixture } = {}) {
    const mainContent = document.getElementById('main-content');
    const data = payload || {};
    const rounds = Array.isArray(data.rounds) ? data.rounds : [];
    const seasons = Array.isArray(data.seasons) ? data.seasons : [];
    const selectedSeason = data.selectedSeason ?? null;
    const selectedSeasonNumber = data.selectedSeasonNumber ?? null;
    const leagueTitle = htmlEscape(data.leagueName || 'League');
    const backTarget = data.backTarget || 'dashboard';
    const matchCaller = data.matchCaller || 'leagueSchedule';
    const fixtureBackTarget = data.fixtureBackTarget || 'leagueSchedule';
    const totalFixtures = rounds.reduce((sum, group) => sum + (group.matches || []).length, 0);
    const upcomingCount = rounds.reduce((sum, group) => sum + (group.matches || []).filter(match => !match?.played).length, 0);
    const focusRound = rounds.find(group => group.isFocusRound)?.round ?? '—';
    const roundCount = rounds.length;

    mainContent.innerHTML = `
        <div class="fm-page fm-page--league">
            <div class="fm-page-toolbar">
                ${backButtonHtml('Back', backTarget)}
                <div class="fm-page-title-block">
                    <div class="fm-eyebrow">League schedule</div>
                    <h2 class="league-table-title">${leagueTitle}${selectedSeasonNumber ? ` · Season ${selectedSeasonNumber}` : ''}</h2>
                </div>
                ${seasons.length ? `
                <label class="fm-season-select-wrap">
                    <span>Season</span>
                    <select id="league-schedule-season-select" class="fm-season-select">
                        ${seasons.map(season => `<option value="${season.seasonYear}" ${season.seasonYear === selectedSeason ? 'selected' : ''}>Season ${season.seasonNumber}</option>`).join('')}
                    </select>
                </label>` : ''}
            </div>

            <div class="fm-grid-top">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Schedule overview</h3>
                            <p class="fm-subtle">Open a fixture or played match for the deeper preview, while the round list stays compact and readable.</p>
                        </div>
                        <span class="fm-panel-action">League-wide</span>
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${totalFixtures}</strong><span>Fixtures</span></div>
                        <div><strong>${upcomingCount}</strong><span>Upcoming</span></div>
                        <div><strong>${roundCount}</strong><span>Rounds</span></div>
                        <div><strong>${focusRound}</strong><span>Focus round</span></div>
                    </div>
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <h3>Rounds</h3>
                        <span class="fm-panel-action">Current focus</span>
                    </div>
                    <div class="fm-fixtures-scroll">
                        <div class="fm-fixtures">
                            ${buildFixtureGroupsHtml(rounds, {
                                safe: htmlEscape,
                                matchCaller,
                                backTarget: fixtureBackTarget,
                                pendingLabel: 'VS',
                                allowFixtureClick: true,
                                showInsights: true,
                                showPlayedInsights: false,
                                seasonYear: selectedSeason
                            })}
                        </div>
                    </div>
                </section>
            </div>
        </div>`;

    bindScheduleInteractions(mainContent, {
        loadLeagueTeam: (teamId, teamName, seasonYear) => loadLeagueTeam(teamId, teamName, { seasonYear: seasonYear ?? selectedSeason }),
        loadMatch,
        loadFixture
    });

    const seasonSelect = document.getElementById('league-schedule-season-select');
    if (seasonSelect) {
        seasonSelect.addEventListener('change', () => loadLeagueSchedule(Number(seasonSelect.value)));
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

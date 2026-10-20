import { backButtonHtml } from './ui/components.js';
import { hiddenResultActions, bindHiddenResultActions } from './reveal-ui.js';
import { escapeHtml } from './ui/escape.js';
import { ownClubChipHtml, parseMatchDate } from './pages/views/utils.js';
import { playerMiniCardHtml } from './pages/views/player-card.js';

// Was a third copy of the escaper, byte-for-byte the same as the other two. ui/escape.js exists to be
// the only one — AGENTS.md asserts it is — so this now imports it. Every extra copy is somewhere a fix
// lands in one implementation and misses another.
const htmlEscape = escapeHtml;

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
            <div class="fx-snapshot-CTeam">${safe(teamName || sideLabel)}</div>
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

    // Tag the row with what kind of match it is, so the competition-type filter can select without a
    // second round trip. `competitionType` is already on every row from the backend; without the class
    // the filter would have to re-read the payload to use a field the page already has. The class is
    // the group, not the raw type, so a Champions Cup tie is `international` and the chip matches it.
    const competitionType = competitionGroupOf(match);
    if (competitionType) {
        classes.push(`fm-fixture--${competitionType}`);
    }

    if (match?.played && match?.id) classes.push('js-load-match', 'is-played');
    if (!match?.played && allowFixtureClick && fixtureId) classes.push('js-load-fixture');
    if (showInsights) classes.push('has-insights');

    const h2h = match?.h2h;
    const playedBadge = match?.played ? resolveLeagueResultBadge(match) : null;

    /**
     * The fixture's calendar, as the season counts it rather than as the wall clock does.
     *
     * `seasonDayLabel` alone is *"Season 1 · Day 3"*, which is the owner's exact complaint: a league
     * round sits on two days a week, so three different rounds all say "day 3 of week 1" and nobody can
     * tell which round a fixture is (owner, 2026-10-10). So the week goes in explicitly, and the league
     * round next to it - `week` for everything, `leagueRound` only where it differs from `round`.
     */
    const calendarBits = [];
    if (Number.isFinite(Number(match?.seasonYear))) calendarBits.push(`S${match.seasonYear}`);
    if (Number.isFinite(Number(match?.day))) calendarBits.push(`D${match.day}`);
    if (Number.isFinite(Number(match?.week))) calendarBits.push(`Wk ${match.week}`);
    if (Number.isFinite(Number(match?.leagueRound))) {
        calendarBits.push(`Rd ${match.leagueRound}`);
    } else if (Number.isFinite(Number(match?.round))) {
        calendarBits.push(`Rd ${match.round}`);
    }
    const calendarLabel = calendarBits.length ? calendarBits.join(' · ') : '';
    const h2hSummary = Number(h2h?.played || 0) > 0
        ? `<div class="fx-h2h"><strong>${safe(h2h.summary || 'H2H')}</strong>${h2h.lastMeetingSummary ? `<span>${safe(h2h.lastMeetingSummary)}</span>` : ''}</div>`
        : '';

    if (match?.resultHidden) {
        // No score, no W/D/L badge and no click-through to the match: any one of them alone gives the
        // result away, and the badge is the quietest leak of the three.
        return `
        <div class="${classes.join(' ')}" data-caller="${safe(matchCaller)}" data-back-target="${safe(backTarget)}">
            <div class="fx-topline">
                <span class="fx-date">${safe(fixtureCalendarLabel(match))}</span>
                ${calendarLabel ? `<span class="fx-calendar">${safe(calendarLabel)}</span>` : ''}
                <span class="fx-venue">${safe(venue)}</span>
            </div>
            <div class="fx-main">
                <span class="fx-home">${safe(match?.homeTeam || 'Home')}</span>
                <span class="fx-score pending fm-hidden-score">Result not shown yet</span>
                <span class="fx-away">${safe(match?.awayTeam || 'Away')}</span>
            </div>
            ${hiddenResultActions(match?.id)}
        </div>`;
    }

    return `
        <div class="${classes.join(' ')}"
             data-match-id="${match?.played && match?.id ? match.id : ''}"
             data-caller="${safe(matchCaller)}"
             data-back-target="${safe(backTarget)}"
             data-fixture-id="${!match?.played && allowFixtureClick ? fixtureId : ''}">
            <div class="fx-topline">
                <span class="fx-date">${safe(fixtureCalendarLabel(match))}</span>
                ${calendarLabel ? `<span class="fx-calendar">${safe(calendarLabel)}</span>` : ''}
                <!-- The ground, linked to the club that plays there. It was a plain span, so the
                     only way to get from a fixture to a stadium was to already know the club. -->
                <span class="fx-venue">${match?.stadiumOwnerTeamId
                    ? `<a class="fm-CTeam-link" href="#" data-open-team="${safe(String(match.stadiumOwnerTeamId))}"
                           data-open-team-name="${safe(match.stadiumOwnerTeamName || '')}">${safe(venue)}</a>`
                    : safe(venue)}</span>
            </div>
            <div class="fx-main">
                <span class="fx-home ${match?.homeTeamId ? 'js-load-CTeam' : ''}" data-CTeam-id="${match?.homeTeamId || ''}" data-CTeam-name="${safe(match?.homeTeam || 'Home')}" data-season-year="${seasonYear}">${safe(match?.homeTeam || 'Home')}</span>
                <span class="fx-score ${match?.played ? '' : 'pending'}">${match?.played ? `${match?.homeGoals ?? 0} – ${match?.awayGoals ?? 0}` : safe(pendingLabel)}</span>
                <span class="fx-away ${match?.awayTeamId ? 'js-load-CTeam' : ''}" data-CTeam-id="${match?.awayTeamId || ''}" data-CTeam-name="${safe(match?.awayTeam || 'Away')}" data-season-year="${seasonYear}">${safe(match?.awayTeam || 'Away')}</span>
            </div>
            ${match?.competitionType ? `<div class="fx-competition-type">${safe(capitalise(competitionGroupOf(match)))}</div>` : ''}
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

    // The schedule's own filter, so "upcoming" and "played" are a choice rather than a page each.
    container.querySelectorAll('[data-competition-filter]').forEach(button => {
        button.addEventListener('click', () => {
            container.querySelectorAll('[data-competition-filter]').forEach(b => b.classList.remove('fm-chip--active'));
            button.classList.add('fm-chip--active');
            const type = button.dataset.competitionFilter || 'all';
            container.dataset.competitionFilter = type;
            const statusChip = container.querySelector('[data-schedule-filter]');
            const status = statusChip ? statusChip.dataset.scheduleFilter : 'upcoming';
            container.querySelectorAll('[data-schedule-filter]').forEach(b => b.classList.remove('fm-chip--active'));
            if (statusChip) statusChip.classList.toggle('fm-chip--active', status === 'upcoming');
            applyScheduleRowVisibility(container, status, type);
        });
    });

    container.querySelectorAll('[data-schedule-filter]').forEach(button => {
        button.addEventListener('click', () => {
            const mode = button.dataset.scheduleFilter;
            container.querySelectorAll('[data-schedule-filter]').forEach(b => b.classList.remove('fm-chip--active'));
            button.classList.add('fm-chip--active');
            applyScheduleFilter(container, mode);   // keeps the chosen competition type
        });
    });

    const onLoadTeam = handlers.loadLeagueTeam || window.loadLeagueTeam;
    const onLoadMatch = handlers.loadMatch || window.loadMatch;
    const onLoadFixture = handlers.loadFixture || window.loadFixture;

    container.querySelectorAll('.js-load-CTeam').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            // Read the id from either spelling. The markup writes data-CTeam-id, which the dataset
            // exposes as cTeamId, while this handler was reading dataset.teamId — so the id was
            // always undefined and clicking a team name did nothing at all. Accepting both is not
            // belt-and-braces, it is the fix: one of the two is what the markup produces and the
            // other is what the code believed it produced.
            // HTML attribute names are case-insensitive and the browser lowercases them, so
            // `data-CTeam-id` arrives as `data-cteam-id` and the dataset property is `cteamId`,
            // not `cTeamId`. Reading the camelCase spelling returned undefined and made every
            // club in every league table unclickable (owner, 2026-10-17).
            const teamId = Number(node.dataset.teamId ?? node.dataset.cteamId ?? node.dataset.cTeamId);
            // `data-CTeam-name` is lowercased by the browser to `data-cteam-name`, so the dataset
            // property is `cteamName`, not `teamName`. Accept both, plus `data-team-name` for the
            // league-table rows that use that spelling.
            const teamName = node.dataset.teamName || node.dataset.cteamName || node.dataset.cTeamName || 'Team';
            const seasonYear = node.dataset.seasonYear ? Number(node.dataset.seasonYear) : null;
            // Passed as an OPTIONS OBJECT, matching loadLeagueTeam's real signature
            // (teamId, teamName, options = {}). It used to be passed positionally, which happened to
            // work only because both live call sites supplied an adapter that converted it back. The
            // default fallback is `window.loadLeagueTeam` itself, and handing that a Number where it
            // expects an object meant options.seasonYear was undefined - the season silently vanished.
            // Unreachable today only because the one call site that passes no handlers also renders
            // no .js-load-CTeam markup, which is luck rather than a design.
            if (teamId && typeof onLoadTeam === 'function') onLoadTeam(teamId, teamName, { seasonYear });
        });
    });

    // The manager's profile from a league table. Bound here, beside the team-cell handler it has to
    // not fire alongside, and stopPropagation is load-bearing rather than defensive: the club cell
    // and this button are siblings inside the same .fm-club-cell, and the row itself also carries a
    // js-load-CTeam handler, so without it a click on the name opens the club instead of the person.
    container.querySelectorAll('.js-open-manager').forEach(node => {
        node.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            const userId = node.dataset.managerId;
            if (!userId) return;
            const open = handlers.openUserProfile || window.openUserProfile;
            if (typeof open === 'function') {
                open(userId);
            } else {
                window.alert('The profile page is still loading. Try again in a moment.');
            }
        });
    });

    // Venue link: goes to the club that plays there, so a fixture is a way into a stadium.
    container.querySelectorAll('[data-open-team]').forEach(node => {
        node.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            const teamId = Number(node.dataset.openTeam);
            const teamName = node.dataset.openTeamName || 'Team';
            // Same contract as above: an options object, not null. `null` was a third positional
            // argument of a shape that no longer exists, and the default fallback would have read
            // a property off it.
            if (teamId && typeof onLoadTeam === 'function') onLoadTeam(teamId, teamName, {});
        });
    });

    // League link: goes to the division this fixture belongs to.
    container.querySelectorAll('[data-open-league]').forEach(node => {
        node.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            const onOpenLeague = handlers.openLeagueById || window.openLeagueById;
            if (typeof onOpenLeague === 'function') {
                onOpenLeague(node.dataset.openLeague, node.dataset.leagueName || 'League');
            }
        });
    });

    container.querySelectorAll('.js-load-match').forEach(node => {
        node.addEventListener('click', () => {
            const matchId = Number(node.dataset.matchId);
            const caller = node.dataset.caller || 'leagueMatches';
            if (matchId && typeof onLoadMatch === 'function') onLoadMatch(matchId, caller);
        });
    });

    // An unplayed match opens the match view on its Preview tab, not the fixture sheet (owner,
    // 2026-10-01). Both used to exist and a manager met two different surfaces for one thing: "the
    // fixture I am about to play" and "the match before kickoff" were the same fixture.
    //
    // `loadFixture` is still the fallback, because a caller that supplied neither loader is then simply
    // doing nothing, and a card that used to open something should not stop opening anything.
    container.querySelectorAll('.js-load-fixture').forEach(node => {
        node.addEventListener('click', () => {
            const fixtureId = Number(node.dataset.fixtureId);
            const backTarget = node.dataset.backTarget || 'leagueTable';
            if (!fixtureId) return;
            if (typeof onLoadMatch === 'function') {
                // `fixture: true` - this node came from a fixture row, and the ids overlap.
                onLoadMatch(fixtureId, 'schedule', { initialTab: 'preview', fixture: true });
            } else if (typeof onLoadFixture === 'function') {
                onLoadFixture(fixtureId, { backTarget });
            }
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

/**
 * A fixture row's calendar line, in the game's own words: **Season · Week · Day · Hour**.
 *
 * <p>It used to fall back to the raw `matchDate` when the server had no label for the row, and that
 * printed a real calendar date - "2026-10-19" - on a league that has no dates at all (owner,
 * 2026-10-19: "nikad nigde u igrici nemamo datum"). A season is twelve weeks; the hour is the part
 * that matters.
 */
function fixtureCalendarLabel(match) {
    if (match?.seasonDayLabel) return match.seasonDayLabel;
    const parts = [];
    if (match?.seasonYear != null) parts.push(`Season ${match.seasonYear}`);
    if (match?.week != null) parts.push(`Week ${match.week}`);
    if (match?.day != null) parts.push(`Day ${match.day}`);
    const when = parseMatchDate(match?.matchDate);
    if (when && !Number.isNaN(when.getTime())) {
        parts.push(`${String(when.getHours()).padStart(2, '0')}:${String(when.getMinutes()).padStart(2, '0')}`);
    }
    return parts.join(' \u00b7 ') || 'TBD';
}

export function buildSquadTableHtml(players, options = {}) {
    const rows = Array.isArray(players) ? players : [];
    const rowClass = options.rowClass || 'league-player-card';
    const teamId = options.teamId ?? '';
    const teamName = options.teamName ?? '';
    const emptyText = options.emptyText || 'No players found.';
    const sortable = !!options.sortable;
    const wrapClass = sortable ? ' fm-squad-wrap--sortable' : '';
    /**
     * The phone's own column order for the squad table (owner, 2026-10-20: "ne treba ispod imena i
     * slike da stoji Apps- Gls - Ast - to su kolone; tabela mora biti skrolabilna; prve kolone moraju
     * da budu apps, gls, ast").
     *
     * <p>The appearances, the goals and the assists were a strip under the player's name and the
     * table's real columns were hidden - so the numbers a manager reads were not columns and the
     * table had nothing to scroll. They are columns now, and they are the first ones: the table
     * scrolls sideways, and what the phone shows first is the season's numbers.
     *
     * <p>Only the pages that ask for it, and only on a phone. The other squad tables keep the strip,
     * because their columns are hidden for a reason and the strip is the only place their numbers
     * are; and the desktop's table is wide enough to show every column where it stands.
     */
    const phone = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        ? window.matchMedia('(max-width: 480px)').matches
        : false;
    const statsFirst = options.statsFirst === true && phone;
    /**
     * The First Team's own column order (owner, 2026-10-20: "Club - Squad - First team - mora da
     * pocne imenom igraca tabela pobogu, znaci redosred kolona name, AV rat (i po njoj treba biti
     * sortabilna), apps, gls, ast, ... name, position, age").
     *
     * <p>The table starts with the player's name, and the four columns a manager reads and sorts by
     * - the average rating, the appearances, the goals and the assists - come straight after it. The
     * position, the age and the attributes follow.
     *
     * <p>It is an option and not the builder's only order, because the other squad tables in the app
     * were not part of the request.
     */
    const ratingFirst = options.ratingFirst === true;

    if (!rows.length) {
        return `<div class="fm-empty">${htmlEscape(emptyText)}</div>`;
    }

    /**
     * The season's numbers, under the player's name.
     *
     * <p>On a phone the squad table keeps the columns that say <i>who</i> this is - the number, the
     * name, the position, the age and the average rating - and the ones that say <i>how he has done</i>
     * (the appearances, the goals, the assists) move under the name (redesign.md R-9). They are not
     * lost: they are simply read where there is room for them.
     */
    function squadStripHtml(appearances, goals, assists) {
        return `<span class="fm-squad-strip">`
            + `<span>Apps ${appearances > 0 ? appearances : '-'}</span>`
            + `<span>Gls ${goals > 0 ? goals : '-'}</span>`
            + `<span>Ast ${assists > 0 ? assists : '-'}</span>`
            + `</span>`;
    }

    /**
     * The column headings, in the order the table shows them.
     *
     * <p>Two orders, because there are two tables: the default one, and the First Team's (owner,
     * 2026-10-20: "mora da pocne imenom igraca tabela pobogu, znaci redosred kolona name, AV rat
     * (i po njoj treba biti sortabilna), apps, gls, ast, ... name, position, age"), where the name
     * comes first and the four columns a manager reads and sorts by follow it.
     */
    const headCells = ratingFirst
        ? `<th class="sq-name">Name</th>
           <th class="sq-inf"></th>
           <th class="sq-rating sortable" data-sort="rating">Av Rat \u21c5</th>
           <th class="sq-games sortable" data-sort="apps">Apps \u21c5</th>
           <th class="sq-goals sortable" data-sort="goals">Gls \u21c5</th>
           <th class="sq-goals sortable" data-sort="assists">Ast \u21c5</th>
           <th>Position</th>
           <th class="sq-age">Age</th>
           <th class="sq-ability">Ability</th>
           <th class="sq-potential">Potential</th>
           <th class="sq-cond">Condition</th>
           <th class="sq-morale">Morale</th>`
        : `<th class="sq-inf"></th>
           <th class="sq-name">Name</th>
           <th>Position</th>
           <th class="sq-age">Age</th>
           <th class="sq-ability">Ability</th>
           <th class="sq-potential">Potential</th>
           <th class="sq-cond">Condition</th>
           <th class="sq-morale">Morale</th>
           <th class="sq-games sortable" data-sort="apps">Apps \u21c5</th>
           <th class="sq-goals sortable" data-sort="goals">Gls \u21c5</th>
           <th class="sq-goals sortable" data-sort="assists">Ast \u21c5</th>
           <th class="sq-rating sortable" data-sort="rating">Av Rat \u21c5</th>`;

    return `
        <div class="fm-squad-wrap${sortable ? " fm-squad-wrap--sortable" : ""}">
            <table class="fm-squad${statsFirst ? ' fm-squad--stats-first' : ''}">
                <thead>
                    <tr>${headCells}</tr>
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

                        const nameCell = `<td class="sq-name">
                            <span class="sq-player-cell">
                                ${playerMiniCardHtml(player)}
                                <span class="sq-player-link">${htmlEscape(player?.name || 'Unknown')}</span>
                            </span>
                            ${statsFirst ? '' : squadStripHtml(appearances, goals, assists)}
                        </td>`;
                        const badgeCell = `<td class="sq-inf"><div class="fm-badge-deck">${badgeDeck(player)}</div></td>`;
                        const ratingCell = `<td class="sq-rating">${Number.isFinite(averageRating) && appearances > 0 ? averageRating.toFixed(1) : '-'}</td>`;
                        const gamesCell = `<td class="sq-games">${appearances > 0 ? appearances : '-'}</td>`;
                        const goalsCell = `<td class="sq-goals">${goals > 0 ? goals : '-'}</td>`;
                        const assistsCell = `<td class="sq-goals">${assists > 0 ? assists : '-'}</td>`;
                        const attributesCells = `<td class="sq-pos">${htmlEscape(player?.position || '-')}</td>
                            <td class="sq-age">${player?.age ?? '-'}</td>
                            <td class="sq-ability">${buildStars(overall)}</td>
                            <td class="sq-potential">${buildStars(Math.min(99, overall + 6))}</td>
                            <td class="sq-cond">
                                <div class="fm-cond">
                                    <div class="fm-cond-bar"><div class="fm-cond-fill" style="width:${condition}%"></div></div>
                                    <span class="fm-cond-val">${condition}%</span>
                                </div>
                            </td>
                            <td class="sq-morale"><span class="fm-morale fm-morale-${morale.className}">${morale.icon}</span><span class="fm-morale-text">${morale.label}</span></td>`;

                        const cells = ratingFirst
                            ? `${nameCell}${badgeCell}${ratingCell}${gamesCell}${goalsCell}${assistsCell}${attributesCells}`
                            : `${badgeCell}${nameCell}${attributesCells}${gamesCell}${goalsCell}${assistsCell}${ratingCell}`;

                        return `
                            <tr class="fm-squad-row ${rowClass}" data-player-id="${player?.id ?? ''}" data-CTeam-id="${teamId}" data-CTeam-name="${htmlEscape(teamName)}" data-apps="${Number(player?.played ?? player?.matchesPlayed ?? 0)}" data-goals="${Number(player?.totalGoals ?? player?.goals ?? 0)}" data-assists="${Number(player?.totalAssists ?? player?.assists ?? 0)}" data-rating="${Number.isFinite(Number(player?.averageRating10)) ? Number(player?.averageRating10) : 0}">
                                ${cells}
                            </tr>`;
                    }).join('')}
                </tbody>
            </table>
        </div>`;
}

/**
 * Makes `.fm-squad-wrap--sortable` tables reorderable by clicking a sortable column header.
 * Sorting happens client-side on the values emitted as `data-*` attributes, so a click only
 * reorders the rows the server already sent. Sensible for a club squad, whose players are a
 * single fixed set for this page.
 */
export function bindSortableSquadTable(container) {
    const wrap = container?.querySelector?.('.fm-squad-wrap--sortable');
    if (!wrap) return;
    const table = wrap.querySelector('table.fm-squad');
    if (!table) return;
    const tbody = table.querySelector('tbody');
    const heads = Array.from(table.querySelectorAll('thead .sortable[data-sort]'));
    if (!tbody || !heads.length) return;

    const attrOf = (key) => `data-${key === 'rating' ? 'rating' : key === 'assists' ? 'assists' : key === 'goals' ? 'goals' : 'apps'}`;
    const readNum = (row, key) => {
        if (key === 'apps') return Number(row.dataset.apps) || 0;
        if (key === 'goals') return Number(row.dataset.goals) || 0;
        if (key === 'assists') return Number(row.dataset.assists) || 0;
        return Number(row.dataset.rating) || 0;
    };

    heads.forEach(head => {
        head.style.cursor = 'pointer';
        head.addEventListener('click', () => {
            const key = head.dataset.sort;
            const ascending = head.dataset.dir !== 'asc';
            heads.forEach(h => { if (h !== head) h.dataset.dir = ''; });
            head.dataset.dir = ascending ? 'asc' : 'desc';
            const rows = Array.from(tbody.querySelectorAll('tr.fm-squad-row'));
            rows.sort((a, b) => {
                const left = readNum(a, key);
                const right = readNum(b, key);
                if (left === right) return 0;
                return (ascending ? 1 : -1) * (left > right ? 1 : -1);
            });
            rows.forEach(row => tbody.appendChild(row));
        });
    });
}
export function bindSquadRowClicks(container, onClick, selector = '.league-player-card') {
    container.querySelectorAll(selector).forEach(row => {
        row.addEventListener('click', () => onClick(row));
    });
}

function buildActionRowHtml(actions, currentPage = '') {
    const body = actions.map(action => {
        const currentPages = Array.isArray(action.currentPages) && action.currentPages.length
            ? action.currentPages
            : [action.page];
        const base = action.variant === 'primary' ? 'fm-action-btn' : 'fm-action-btn secondary';
        const className = currentPages.includes(currentPage) ? `${base} is-current` : base;
        const onclick = action.onclick || `loadPage('${action.page}')`;
        return `<button type="button" class="${className}" onclick="${onclick}">${htmlEscape(action.label)}</button>`;
    }).join('');

    // The same options live in the hamburger/menu already, so do not repeat a full button toolbar
    // on every club subpage. It stays collapsed into one summary line on every viewport.
    const mobile = true;
    if (mobile) {
        return `<details class="fm-club-actions fm-club-actions-collapse">
            <summary class="fm-club-actions-summary">Club actions (${actions.length})</summary>
            <div class="fm-club-actions-inner">${body}</div>
        </details>`;
    }
    return `<div class="fm-club-actions">${body}</div>`;
}

/**
 * The two Community screens, as an option row under the Community tab.
 *
 * <p>Forum and Messages are two tabs in this application's terms and two <b>options</b> in the owner's:
 * one Community entry in the top bar, with the choice made on the page — exactly how Club carries First
 * Team, Schedule and the rest. Phase 4 gave them a top-bar button each, which was the wrong shape.
 *
 * <p>{@code currentPages} lists every route each option covers, so "Forum" stays lit while you are
 * reading a topic rather than only on the section list.
 */
export function buildCommunityActionsHtml(currentPage = '') {
    // The Community page carries Forum and Messages as plain options, not as a "Club actions" wrapper:
    // the owner's complaint was that the wrapper said "Club actions (2)" on a page that has nothing to
    // do with club actions. The two buttons go out bare.
    const actions = [
        { label: 'Forum', page: 'forum', variant: 'primary',
          currentPages: ['forum', 'forumSection', 'forumTopic'] },
        { label: 'Messages', page: 'messages',
          currentPages: ['messages', 'messageThread'] },
    ];
    return actions.map(action => {
        const currentPages = Array.isArray(action.currentPages) && action.currentPages.length
            ? action.currentPages
            : [action.page];
        const base = action.variant === 'primary' ? 'fm-action-btn' : 'fm-action-btn secondary';
        const className = currentPages.includes(currentPage) ? `${base} is-current` : base;
        const onclick = action.onclick || `loadPage('${action.page}')`;
        return `<button type="button" class="${className}" onclick="${onclick}">${htmlEscape(action.label)}</button>`;
    }).join(' ');
}

export function buildClubActionsHtml(currentPage = '') {
    // The same destinations already exist in the top menu and the mobile sidebar, so the duplicate
    // toolbar is deliberately not rendered here anymore.
    return '';
}

export function buildTrainingActionsHtml(currentPage = '') {
    return buildActionRowHtml([
        { label: 'Training Setup', page: 'trainingSetup', variant: 'primary', currentPages: ['training', 'trainingSetup'] },
        { label: 'Training Reports', page: 'trainingReports' },
    ], currentPage);
}

/**
 * The league area's own action row.
 *
 * <p>The league pages had no navigation of their own - they were reached from the top bar and then
 * had nowhere to go, so league matches, the top scorers and the top assists existed as routes with
 * no way in.
 *
 * <p>Only pages backed by a real endpoint belong here. `cup`, `international`, `upcoming`,
 * `friendlies`, `coaches` and `events` are all routed but all fetch from `/demo/...`, which is
 * DummyDataController: fake data, every route hardcoded to team 1. A menu entry on one of those is a
 * fabricated table, or an error card, in the manager's navigation.
 */
export function buildLeagueActionsHtml(currentPage = '') {
    // League navigation is a context header, not a Club action row. The table is the page itself,
    // so an extra action wrapper here only created a misleading "Club actions (1)" control.
    return '';
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

    const isClubSquad = /first team|first CTeam|juniors/i.test(title);
    const isEnhancedFirstTeam = /first team|first CTeam/i.test(title);
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
        <div class="fm-page fm-page--CTeam-detail">
            <section class="fm-panel fm-club-hero">
                ${backButtonHtml('Back', 'dashboard')}
                <div class="fm-club-hero-main">
                    <div>
                        <div class="fm-eyebrow">Club squad</div>
                        <h2>${htmlEscape(title)}</h2>
                        <p class="fm-subtle">First Team now uses the same stronger club shell and responsive panel layout as the better-scaling CTeam views.</p>
                    </div>
                    ${buildClubActionsHtml(callerPage)}
                </div>
                <div class="fm-medical-stat-grid CTeam-summary-grid">
                    <div><strong>${players.length}</strong><span>Players</span></div>
                    <div><strong>${avgOverall}</strong><span>Avg OVR</span></div>
                    <div><strong>${avgAge}</strong><span>Avg age</span></div>
                    <div><strong>${totalGoals}/${totalAssists}</strong><span>Goals / assists</span></div>
                </div>
            </section>
            <div class="fm-CTeam-layout">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <h3>Squad</h3>
                        <span class="fm-panel-action">${players.length} registered</span>
                    </div>
                    ${buildSquadTableHtml(players, {
                        rowClass: 'league-player-card',
                        teamId,
                        teamName: resolvedTeamName,
                        emptyText: 'No registered players found for this CTeam.',
                        sortable: true,
                        // The phone shows the season's numbers as columns (owner, 2026-10-20).
                        statsFirst: true,
                        // The table starts with the player's name, and the rating and the three season
                        // numbers come straight after it (owner, 2026-10-20).
                        ratingFirst: true
                    })}
                </section>
            </div>
        </div>`;

        bindSquadRowClicks(mainContent, row => {
            const playerId = Number(row.dataset.playerId);
            if (playerId) loadPlayer(playerId, callerPage);
        });
        bindSortableSquadTable(mainContent);
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
    bindSortableSquadTable(mainContent);
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
            <div class="fm-medical-stat-grid CTeam-summary-grid">
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
                <div style="font-size:0.9em; color:#aaa; margin-bottom:4px;">${htmlEscape(fixtureCalendarLabel(match))}</div>
                <div class="match-teams">
                    <span class="CTeam-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${match.homeTeam}</span></span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="CTeam-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${match.awayTeam}</span></span>
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
            <div class="fm-medical-stat-grid CTeam-summary-grid">
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
            ${scheduleFilterHtml(fixtureRows)}
            <div class="fm-fixtures" data-schedule-list>`;
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
    bindHiddenResultActions(mainContent);
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

            if (match.resultHidden === true) {
                html += `
                <div class="match-row recent-match is-hidden-result" data-match-id="${match.id}">
                    <div style="font-size:0.9em; color:#aaa;">${htmlEscape(fixtureCalendarLabel(match))}${match.competitionType ? ` <span style="color:#7a7a7a;">· ${htmlEscape(match.competitionType)}</span>` : ''}</div>
                    <div class="match-teams">
                        <span class="CTeam-home">${match.homeTeam}</span>
                        <span class="score fm-hidden-score">Result not shown yet</span>
                        <span class="CTeam-away">${match.awayTeam}</span>
                    </div>
                    ${hiddenResultActions(match.id)}
                </div>`;
                return;
            }

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
                <div style="font-size:0.9em; color:#aaa;">${htmlEscape(fixtureCalendarLabel(match))}${match.competitionType ? ` <span style="color:#7a7a7a;">· ${htmlEscape(match.competitionType)}</span>` : ''}</div>
                <div class="match-teams">
                    <span class="CTeam-home"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${homeEsc}')">${htmlEscape(match.homeTeam)}</span></span>
                    <span class="score">${match.homeGoals ?? '-'} : ${match.awayGoals ?? '-'}</span>
                    <span class="CTeam-away"><span class="cs-clickable" onclick="event.stopPropagation(); openTeamByName('${awayEsc}')">${htmlEscape(match.awayTeam)}</span></span>
                </div>
                ${badgeText ? `<span class="result-badge ${badgeClass}">${badgeText}</span>` : ''}
            </div>`;
        });
    }

    html += `</div></div>`;
    mainContent.innerHTML = html;
    bindHiddenResultActions(mainContent, loadMatch);

    mainContent.onclick = (e) => {
        const row = e.target.closest('.match-row');
        if (!row || !mainContent.contains(row)) return;
        const matchId = Number(row.dataset.matchId);
        const caller = row.dataset.caller || 'leagueMatches';
        if (matchId) loadMatch(matchId, caller);
    };
}

export function renderTableView(payload, { loadLeagueTeam, loadLeagueTeamPlayer, loadLeagueTable, loadMatch, loadFixture, escapeHtml, formatGoalDiff, getTeamId }) {
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

    /**
     * The rating movement, signed, and honest about not having one.
     *
     * <p>Three states and all three are real: a club that has been rated and moved (+3 / -11), a club that
     * has been rated and has not moved since its last match (0, shown as a dash rather than a "+0" that
     * reads as a gain), and a club nobody has rated yet — which is what every club looks like on a world
     * whose replay has not run.
     *
     * <p>Unsigned movement is the point of the column, so the sign is always printed rather than carried
     * by colour alone.
     */
    function formatRatingDelta(delta) {
        if (typeof delta !== 'number' || !Number.isFinite(delta)) return '—';
        if (delta === 0) return '–';
        return delta > 0 ? `+${Math.round(delta)}` : String(Math.round(delta));
    }

    /** The class is decoration on top of the sign, never the only carrier of the meaning. */
    function ratingDeltaClass(delta) {
        if (typeof delta !== 'number' || !Number.isFinite(delta) || delta === 0) return 'is-flat';
        return delta > 0 ? 'is-up' : 'is-down';
    }

    function ownershipBadgeHtml(humanControlled) {
        if (typeof humanControlled !== 'boolean') return '';
        return `<span class="fm-badge ${humanControlled ? 'fm-badge-owner' : 'fm-badge-ai'}">${humanControlled ? 'PLAYER' : 'AI'}</span>`;
    }

    /**
     * The manager's name beside a club in a league table, and a way into his profile.
     *
     * <p>Owner decision, 2026-10-05: click a club, see who runs it, click him, read his profile. A
     * league table is the natural place to start — it is where you meet the other clubs in your
     * division.
     *
     * <p>Separate from the club cell's own click handler rather than nested inside it. The row
     * navigates to the club; this navigates to a person. Wrapping one in the other means one of the
     * two needs a stopPropagation, and the wrong one winning is a click that opens the wrong page.
     *
     * <p>Shows nothing for a bot club, because the AI badge already says there is no person. And when
     * the name is only a login address, it says so rather than presenting an email as a name.
     */
    function managerLinkHtml(row) {
        if (!row?.managerUserId) return '';
        const hasName = row.managerHasChosenName !== false;
        const label = safe(row.managerName || 'Manager');
        const title = hasName
            ? `Open ${label}'s profile`
            : `Open ${label}'s profile — he has not set a name, so his login is shown`;
        return `<button type="button" class="fm-link-btn fm-link-btn--inline js-open-manager" `
            + `data-manager-id="${safe(row.managerUserId)}" `
            + `data-manager-name="${label}" `
            + `title="${title}">${label}</button>`;
    }

    /**
     * Is this row the manager's own club?
     *
     * <p>The league table is the screen the whole game is about and it did not say which row was you.
     * The colour bars down its left edge are **promotion and relegation zones** — rank 2-4 colouring —
     * and they are on TSK Tamiš Leskovac and ŽFK Radnički Sport exactly as they are on OFK Omladinac, so
     * they read as ownership and are not. The only real signals were a `PLAYER` chip (which means *a
     * human manages this club*, not *this is you*) and a clickable email address.
     *
     * <p>By id, never by name. Two clubs share the name GFK Zemun in this very division, and four
     * defects in this codebase came from matching a team by its name.
     */
    function isOwnLeagueRow(row) {
        const myId = typeof getTeamId === 'function' ? getTeamId() : null;
        if (myId === null || myId === undefined || !row?.teamId) return false;
        return Number(row.teamId) === Number(myId);
    }

    /**
     * "Where am I in this table" - one line above the standings.
     *
     * <p>The row mark (`is-own`) says which row is yours while you are looking at it. On a phone the
     * table scrolls sideways and a 16-row table does not fit on a screen, so the answer to "where am I"
     * has to exist outside the table as well. The league table is the one page where the answer is a
     * single number - a position - so it is a single line.
     */
    function ownPositionLineHtml() {
        const ownIndex = rows.findIndex(isOwnLeagueRow);
        if (ownIndex === -1) return '';
        const ownRow = rows[ownIndex];
        const position = Number(ownRow.position || ownIndex + 1);
        const points = Number(ownRow.points || 0);
        const played = Number(ownRow.wins || 0) + Number(ownRow.draws || 0) + Number(ownRow.losses || 0);
        return `
            <p class="fm-own-position">
                <span class="fm-own-position-label">Your position</span>
                <strong class="fm-own-position-value">${position}${ordinalSuffix(position)}</strong>
                <span class="fm-own-position-detail">of ${rows.length} · ${points} pts${played ? ` in ${played} played` : ''}</span>
            </p>`;
    }

    function ordinalSuffix(value) {
        const n = Number(value);
        const mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return 'th';
        switch (n % 10) {
            case 1: return 'st';
            case 2: return 'nd';
            case 3: return 'rd';
            default: return 'th';
        }
    }

    function standingsRowsHtml() {
        return rows.map((CTeam, index) => {
            const rank = Number(CTeam.position || index + 1);
            const wins = Number(CTeam.wins || 0);
            const draws = Number(CTeam.draws || 0);
            const losses = Number(CTeam.losses || 0);
            const played = wins + draws + losses;
            const gd = Number(CTeam.goalDifference || 0);
            const isOwn = isOwnLeagueRow(CTeam);
            const teamLabel = CTeam.teamId
                ? `<span class="fm-CTeam-link js-load-CTeam" data-CTeam-id="${CTeam.teamId}" data-team-name="${safe(CTeam.name)}" data-season-year="${selectedSeason ?? ''}">${safe(CTeam.name)}</span>`
                : safe(CTeam.name);
            return `
                <tr class="${zoneClass(rank, rows.length)} ${CTeam.teamId ? 'js-load-CTeam' : ''}${isOwn ? ' is-own' : ''}" data-CTeam-id="${CTeam.teamId || ''}" data-CTeam-name="${safe(CTeam.name)}" data-season-year="${selectedSeason ?? ''}">
                    <td class="st-pos">${rank}</td>
                    <td class="st-club"><div class="fm-club-cell">${teamLabel}${isOwn ? ownClubChipHtml() : ''}${ownershipBadgeHtml(CTeam.humanControlled)}${managerLinkHtml(CTeam)}${formStripHtml(wins, draws, losses)}</div></td>
                    <td>${played}</td>
                    <td>${wins}</td>
                    <td>${draws}</td>
                    <td>${losses}</td>
                    <td>${CTeam.goalsScored ?? 0}</td>
                    <td>${CTeam.goalsConceded ?? 0}</td>
                    <td class="st-gd">${formatGoalDiff(gd)}</td>
                    <td class="st-pts">${CTeam.points ?? 0}</td>
                    <td class="st-rating">${CTeam.rating ?? '—'}</td>
                    <td class="st-delta ${ratingDeltaClass(CTeam.ratingDelta)}">${formatRatingDelta(CTeam.ratingDelta)}</td>
                </tr>`;
        }).join('');
    }

    /**
     * The season's record, under the club's name.
     *
     * <p>On a phone the league table shows only `# · Club · P · GD · Pts` (redesign.md R-10) - and the
     * wins, the draws and the losses are exactly the three columns that go. Rather than lose them,
     * they move under the name: "W 12 · D 3 · L 5" is the same information, and it is where a manager
     * looks for it anyway. Hidden on the desktop, where the columns themselves are there.
     */
    function formStripHtml(wins, draws, losses) {
        return `<span class="fm-form-strip">`
            + `<span class="fm-form-strip-item is-win">W ${wins}</span>`
            + `<span class="fm-form-strip-item is-draw">D ${draws}</span>`
            + `<span class="fm-form-strip-item is-loss">L ${losses}</span>`
            + `</span>`;
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
        // Wrapped for the phone. Measured on an iPhone 14 Pro Max (430 CSS px): the table's natural
        // width is wider than the viewport, and with no scroll container the right-hand columns were
        // simply unreachable. The squad table already had this wrapper; these two did not.
        return `
            <div class="fm-squad-wrap">
            <table class="fm-player-stats">
                <thead>
                    <tr>
                        <th class="ps-pos">#</th>
                        <th class="ps-name">Player</th>
                        <th class="ps-val" title="${type === 'goals' ? 'Goals' : 'Assists'}">${type === 'goals' ? '⚽' : '🅰️'}</th>
                    </tr>
                </thead>
                <tbody>
                    ${items.slice(0, 8).map((item, index) => `
                        <tr>
                            <td class="ps-pos">${index + 1}</td>
                            <td class="ps-name">
                                ${item.playerId && item.teamId
                                    ? `<span class="fm-player-link js-load-league-player" data-player-id="${item.playerId}" data-CTeam-id="${item.teamId}" data-CTeam-name="${safe(item.teamName || 'Team')}" data-season-year="${selectedSeason ?? ''}">${safe(item.playerName || item.name || 'Unknown')}</span>`
                                    : safe(item.playerName || item.name || 'Unknown')}
                                <span class="ps-CTeam">${item.teamId ? `<span class="fm-CTeam-link js-load-CTeam" data-CTeam-id="${item.teamId}" data-CTeam-name="${safe(item.teamName)}" data-season-year="${selectedSeason ?? ''}">${safe(item.teamName)}</span>` : safe(item.teamName || 'No Team')}</span>
                            </td>
                            <td class="ps-val">${type === 'goals' ? (item.goals ?? 0) : (item.assists ?? 0)}</td>
                        </tr>`).join('')}
                </tbody>
            </table>
            </div>`;
    }

    function formatAttendance(value) {
        const numeric = Number(value || 0);
        return numeric > 0 ? numeric.toLocaleString() : '—';
    }

    /**
     * A count shown as repeated icons - "⚽ ⚽ ⚽" for three goals - using the same badge classes as the
     * match lineup, so a goal looks identical wherever it appears.
     */
    function iconCount(badgeClass, icon, count) {
        const total = Math.max(0, Number(count) || 0);
        if (total === 0) return '0';
        return Array.from({ length: total }, () => (
            `<span class="fm-badge fm-badge-icon ${badgeClass}" aria-hidden="true">${icon}</span>`
        )).join('');
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
                    // The icons the rest of the app already uses for a goal and an assist, rather than
                    // the words "goals" and "assists" - a manager reads the symbol everywhere else.
                    scorer?.playerName ? `${safe(scorer.teamName || 'No CTeam')} · ${iconCount('fm-badge-goal', '⚽', Number(scorer.value || 0))}` : 'No goals filed yet.'
                )}
                ${milestoneCardHtml(
                    'Top assist',
                    assist?.playerName ? safe(assist.playerName) : '—',
                    assist?.playerName ? `${safe(assist.teamName || 'No CTeam')} · ${iconCount('fm-badge-ast', '🅰️', Number(assist.value || 0))}` : 'No assists filed yet.'
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
                                    <strong>${safe(item.CTeam || 'Unknown')}</strong>
                                    <span>${safe(item.fromLeague || 'Tier 2')}</span>
                                </div>`)}
                        </div>
                    </article>
                    <article class="fm-milestone-card fm-season-summary-card">
                        <div class="fm-milestone-kicker">Direct down</div>
                        <div class="fm-season-summary-list">
                            ${summaryListHtml(directRelegations, 'No direct relegation data yet.', item => `
                                <div class="fm-season-summary-row">
                                    <strong>${safe(item.CTeam || 'Unknown')}</strong>
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
                <!--
                    **The league's name is the green line and nothing else** (owner, 2026-10-20: "u
                    naslovu lige stoji naziv lige zelenom bojom - to bih zadrzao i malko povecao font,
                    imamo naziv lige belom bojom - to je visak i imamo Season 1 sto je visak jer ima mo
                    desno padajuci meni; verujem i na mob i na desktop").

                    <p>The name was printed twice - once in green above and once in white under it - and
                    the season was printed a third time, next to the dropdown that already holds it. The
                    green line is the title now, and it is a little larger.
                -->
                <div class="fm-eyebrow">${leagueTitle}</div>
            </div>
            ${seasons.length ? `
            <label class="fm-season-select-wrap">
                <span>Season</span>
                <select id="league-overview-season-select" class="fm-season-select">
                    ${seasons.map(season => `<option value="${season.seasonYear}" ${season.seasonYear === data.selectedSeason ? 'selected' : ''}>${season.seasonNumber}</option>`).join('')}
                </select>
            </label>` : ''}
        </div>

        <div class="fm-grid-top">
            <section class="fm-panel fm-panel--league-table">
                <div class="fm-panel-head">
                    <h3>League Table</h3>
                </div>
                ${ownPositionLineHtml()}
                <!--
                    Scrollable on a phone. Measured at 430 CSS px: the table rendered 510px wide, the
                    panel ended at 408px, and there was no scroller - so the Elo and rating-delta
                    columns ran under the panel's rounded edge and out of reach. Same wrapper the squad
                    table uses, for the same reason.
                -->
                <div class="fm-squad-wrap">
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
                            <th class="st-rating" title="Club Elo. Every match counts - league, cup and international club cup.">Elo</th>
                            <th class="st-delta" title="Movement since this club's previous rating.">&plusmn;</th>
                        </tr>
                    </thead>
                    <tbody>${standingsRowsHtml()}</tbody>
                </table>
                </div>
                <div class="fm-legend">
                    <span><i class="legend-dot ucl"></i> Title pace</span>
                    <span><i class="legend-dot uel"></i> Top places</span>
                    <span><i class="legend-dot rel"></i> Relegation zone</span>
                </div>
            </section>

            <section class="fm-panel fm-panel--league-fixtures">
                <div class="fm-panel-head">
                    <h3>Fixtures & Results</h3>
                    <span class="fm-panel-action">Current focus</span>
                </div>
                <div class="fm-fixtures-scroll">
                    <div class="fm-fixtures">${fixturesHtml()}</div>
                </div>
            </section>
        </div>

        <section class="fm-panel fm-milestone-board-panel fm-panel--league-milestones">
            <div class="fm-panel-head">
                <h3>Milestones</h3>
                <span class="fm-panel-action">Season board</span>
            </div>
            ${milestoneBoardHtml()}
        </section>

        ${seasonSummaryBoardHtml()}

        <div class="fm-grid-bottom">
            <section class="fm-panel fm-panel--league-topscorers">
                <div class="fm-panel-head"><h3>Top Scorers</h3></div>
                ${statTableHtml(topScorers, 'goals')}
            </section>
            <section class="fm-panel fm-panel--league-topassists">
                <div class="fm-panel-head"><h3>Top Assisters</h3></div>
                ${statTableHtml(topAssists, 'assists')}
            </section>
        </div>
    </div>`;

    bindScheduleInteractions(mainContent, {
        loadLeagueTeam: (teamId, teamName, options = {}) => loadLeagueTeam(teamId, teamName, { seasonYear: options.seasonYear ?? selectedSeason }),
        loadMatch,
        loadFixture,
        // The standings rows carry the manager's name (LeagueTableDTO), and clicking it opens his
        // profile. Without this handler the name renders and does nothing, which is the worst of the
        // three outcomes: it looks like the feature works.
        openUserProfile: window.openUserProfile
    });

    mainContent.querySelectorAll('.js-load-league-player').forEach(node => {
        node.addEventListener('click', event => {
            event.stopPropagation();
            const playerId = Number(node.dataset.playerId);
            const teamId = Number(node.dataset.teamId);
            // `data-CTeam-name` is lowercased by the browser to `data-cteam-name`, so the dataset
            // property is `cteamName`, not `teamName`. Accept both, plus `data-team-name` for the
            // league-table rows that use that spelling.
            const teamName = node.dataset.teamName || node.dataset.cteamName || node.dataset.cTeamName || 'Team';
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
                    <div class="fm-medical-stat-grid CTeam-summary-grid">
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
        loadLeagueTeam: (teamId, teamName, options = {}) => loadLeagueTeam(teamId, teamName, { seasonYear: options.seasonYear ?? selectedSeason }),
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

/**
 * The three ways to read a schedule: what is left, what has happened, everything.
 *
 * The owner asked whether played matches belong on this screen at all, given that Results is a separate
 * view (2026-10-10). They do not need to be here - but a manager who wants the season as one timeline
 * should not have to open a second page to see it. So the default is **upcoming**, which is what a
 * schedule is for, and **All** keeps the played ones in place.
 *
 * The counts are already in the header; this makes them clickable rather than merely true.
 */
function scheduleFilterHtml(fixtureRows) {
    const played = fixtureRows.filter(f => f?.played).length;
    const upcoming = fixtureRows.length - played;

    const button = (mode, label, count) =>
        `<button type="button" class="fm-chip${mode === 'upcoming' ? ' fm-chip--active' : ''}"
                 data-schedule-filter="${mode}">${label} ${count}</button>`;

    return `<div class="fm-schedule-filter" data-schedule-filter-row>
        ${button('upcoming', 'Upcoming', upcoming)}
        ${button('played', 'Played', played)}
        ${button('all', 'All', fixtureRows.length)}
    </div>${competitionTypeFilterHtml(fixtureRows)}`;
}

/**
 * League / Cup / International, as a filter beside the played-one rather than instead of it.
 *
 * <p>Independent on purpose: "upcoming cup ties" is a question the manager really asks, and folding
 * the two into one list of chips would make every combination its own button.
 */
function competitionTypeFilterHtml(fixtureRows) {
    const countOf = type => fixtureRows.filter(f => competitionGroupOf(f) === type).length;
    const types = ['league', 'cup', 'international'];
    if (!types.some(type => countOf(type) > 0)) {
        return '';
    }
    const chip = type =>
        `<button type="button" class="fm-chip${type === 'all' ? ' fm-chip--active' : ''}"
             data-competition-filter="${type}">${type === 'all' ? 'All types' : capitalise(type)} ${countOf(type)}</button>`;
    return `<div class="fm-schedule-filter" data-competition-filter-row>
        ${chip('all')}${types.map(chip).join('')}
    </div>`;
}

/**
 * Which of the three chips a fixture belongs to, as a lowercase slug the filter and the CSS class
 * agree on.
 *
 * <p><b>Scope first, type second.</b> A Champions Cup round is `competitionType = CUP` and
 * `competitionScope = INTERNATIONAL`, so grouping by type alone put every continental tie in the same
 * bucket as the domestic cup and left "International" at zero on a club that is in the Champions Cup
 * (owner, 2026-10-16). A competition's scope is the better answer to "who plays this": a national
 * league and the national cup are NATIONAL, the continental cups are INTERNATIONAL, and a friendly
 * belongs to neither and is grouped by its match type.
 */
function competitionGroupOf(fixture) {
    const scope = String(fixture?.competitionScope ?? '').trim().toUpperCase();
    if (scope === 'INTERNATIONAL') return 'international';
    return normaliseCompetitionType(fixture?.competitionType);
}

/** The backend's `competitionType`, as a lowercase slug. Scope is handled above this. */
function normaliseCompetitionType(raw) {
    const value = String(raw ?? '').trim().toUpperCase();
    if (!value) return '';
    if (value === 'LEAGUE' || value === 'CUP' || value === 'TOURNAMENT') {
        return value.toLowerCase();
    }
    // Anything international falls back to the group rather than becoming an unmatched chip that
    // silently filters everything away.
    if (value.includes('INTERNATIONAL')) return 'international';
    return '';
}

function capitalise(value) {
    return String(value).charAt(0).toUpperCase() + String(value).slice(1);
}

/** Hides the rows the chosen filter excludes, and restores them on the next choice. */
function applyScheduleFilter(container, mode = 'upcoming') {
    const chosen = container.querySelector('[data-schedule-filter]');
    if (chosen) {
        chosen.classList.toggle('fm-chip--active', mode === 'upcoming');
    }
    applyScheduleRowVisibility(container, mode,
        container.dataset.competitionFilter || 'all');
}

/**
 * Both filters, together, in one pass.
 *
 * <p>They used to be a single filter; keeping them as two means the row is hidden when <i>either</i>
 * excludes it, which is what "upcoming cup ties" has to mean. Reading the chosen competition type off
 * the container rather than off a module-level variable keeps the two filters from having to know about
 * each other.
 */
function applyScheduleRowVisibility(container, mode = 'upcoming', competition = 'all') {
    const rows = [...container.querySelectorAll('[data-schedule-list] > *')];
    for (const row of rows) {
        const played = row.classList.contains('is-played');
        const showByStatus = mode === 'all' || (mode === 'played' ? played : !played);
        const showByType = competition === 'all'
            || row.classList.contains(`fm-fixture--${competition}`);
        row.style.display = showByStatus && showByType ? '' : 'none';
    }
}
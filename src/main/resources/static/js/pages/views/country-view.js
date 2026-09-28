// pages/views/country-view.js
import {
    htmlEscape, buildEmptyState, sortCountryLeagues, buildCountryFlagBadgeHtml,
    buildLeagueMetaLabel
} from './utils.js';

export function createCountryView(deps) {
    const {
        authFetch, loadPage, setActiveLeagueContext,
        getCurrentUserCountryIsoCode, getActiveLeagueCountryIsoCode,
        getCurrentUserCountryName
    } = deps;

    async function openCountryLeague(leagueId, leagueName) {
        setActiveLeagueContext({
            leagueId,
            leagueName,
            countryIsoCode: getCurrentUserCountryIsoCode() || getActiveLeagueCountryIsoCode() || '',
            backTarget: 'country'
        });
        await loadPage('leagueTable', { preserveLeagueContext: true });
    }

    async function openNationalTeam(level) {
        setActiveLeagueContext({ nationalTeamLevel: level, backTarget: 'country' });
        await loadPage(level === 'u21' ? 'u21Team' : 'nationalTeam');
    }

    /** A national team that failed to load must not blank the page it sits on. */
    async function readNationalTeam(countryIso, level) {
        try {
            const response = await authFetch(
                `/countries/${encodeURIComponent(countryIso)}/national-team?level=${encodeURIComponent(level)}`
            );
            if (!response.ok) return null;
            return await response.json();
        } catch (err) {
            console.warn(`National team (${level}) unavailable:`, err);
            return null;
        }
    }

    /**
     * One row per player: position, name, age, rating.
     *
     * <p>Not a table, and deliberately not a grid of cards. A 25-player squad is a list; rendering it
     * as 25 boxes gives every row 150px of vertical padding and pushes the page to 4000px for no gain.
     */
    function buildSquadRows(squad) {
        return (squad || []).map((player, index) => `
            <div class="fm-squad-row">
                <span class="fm-squad-rank">${index + 1}</span>
                <span class="fm-squad-pos">${htmlEscape(player?.position || '—')}</span>
                <span class="fm-squad-name">${htmlEscape(player?.name || 'Unknown')}</span>
                <span class="fm-squad-meta">${player?.age ?? '—'} yrs</span>
                <span class="fm-squad-rating">${player?.rating ?? '—'}</span>
            </div>`).join('');
    }

    function buildNationalTeamSegment(nationalTeam, level) {
        if (!nationalTeam || !nationalTeam.exists) {
            return `
                <article class="fm-panel fm-nt-card">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">${level === 'u21' ? 'U-21' : 'Senior'}</div>
                            <h3>Not created yet</h3>
                            <p class="fm-subtle">This national team has not been set up.</p>
                        </div>
                    </div>
                </article>`;
        }

        const squad = nationalTeam.squad || [];
        // Stated, not implied. The manager standing in is a temporary convenience, and the screen
        // must not read as though a vote happened.
        const selectorLine = nationalTeam.selectorName
            ? `${htmlEscape(nationalTeam.selectorName)}${nationalTeam.selectorIsProvisional ? ' <span class="fm-nt-provisional">provisional</span>' : ''}`
            : 'No selector assigned';

        return `
            <article class="fm-panel fm-nt-card">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">${level === 'u21' ? 'U-21' : 'Senior national team'}</div>
                        <h3>${htmlEscape(nationalTeam.teamName || 'National Team')}</h3>
                        <p class="fm-subtle">Selector: ${selectorLine}</p>
                    </div>
                    <div class="fm-panel-head-actions">
                        <span class="fm-nt-squad-count">${squad.length} squad</span>
                        <button type="button" class="fm-action-btn secondary" data-nt-level="${level}">Manage</button>
                    </div>
                </div>
                ${squad.length
                    ? `<div class="fm-squad">${buildSquadRows(squad)}</div>`
                    : '<div class="fm-empty">Squad is empty. The country has no players to call up.</div>'}
            </article>`;
    }

    function buildLeagueSegment(sortedLeagues) {
        if (!sortedLeagues.length) {
            return '<section class="fm-panel"><div class="fm-empty">No leagues found for this country yet.</div></section>';
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Leagues</h3>
                        <p class="fm-subtle">${sortedLeagues.length} competition${sortedLeagues.length === 1 ? '' : 's'} in this country.</p>
                    </div>
                </div>
                <div class="fm-league-list">
                    ${sortedLeagues.map(league => `
                        <button type="button" class="fm-league-row" data-country-league-id="${league?.id || ''}" data-country-league-name="${htmlEscape(league?.name || 'League')}">
                            <span class="fm-league-row-tier">${htmlEscape(buildLeagueMetaLabel(league))}</span>
                            <span class="fm-league-row-name">${htmlEscape(league?.name || 'League')}</span>
                            <span class="fm-league-row-go">Open table &rsaquo;</span>
                        </button>`).join('')}
                </div>
            </section>`;
    }

    function buildWeekSegment(weekDays, calendarWeek, calendarNote) {
        if (!weekDays.length) {
            return '<section class="fm-panel"><div class="fm-empty">The weekly schedule could not be loaded.</div></section>';
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>${htmlEscape(calendarNote ? `Week ${calendarWeek}` : 'This week')}</h3>
                        <p class="fm-subtle">${htmlEscape(calendarNote || 'What happens each day, for every club in the country.')}</p>
                    </div>
                </div>
                <div class="fm-week-strip">
                    ${weekDays.map(day => `
                        <div class="fm-week-day${day.matchDay ? ' is-match' : ''}">
                            <div class="fm-week-day-head">
                                <span class="fm-week-day-number">Day ${day.day}</span>
                                ${day.kickoff ? `<span class="fm-week-day-time">${htmlEscape(day.kickoff)}</span>` : ''}
                            </div>
                            <div class="fm-week-day-kind">${htmlEscape(day.label)}</div>
                            ${(day.events || []).map(event => `<div class="fm-week-day-event">${htmlEscape(event.label)}</div>`).join('')}
                        </div>`).join('')}
                </div>
            </section>`;
    }

    function buildSeasonSegment(seasonWeeks) {
        if (!seasonWeeks.length) {
            return '<section class="fm-panel"><div class="fm-empty">The season calendar could not be loaded.</div></section>';
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Season</h3>
                        <p class="fm-subtle">All twelve weeks. Week 6 and week 12 have no league football.</p>
                    </div>
                </div>
                <div class="fm-season-grid">
                    ${seasonWeeks.map(week => `
                        <div class="fm-season-week${week.current ? ' is-current' : ''}${week.note ? ' is-special' : ''}">
                            <div class="fm-season-week-head">
                                <span class="fm-season-week-number">Week ${week.week}</span>
                                ${week.current ? '<span class="fm-season-week-now">now</span>' : ''}
                            </div>
                            <div class="fm-season-week-rounds">
                                <span>Day 3 &middot; ${htmlEscape(week.dayThree || '—')}</span>
                                <span>Day 7 &middot; ${htmlEscape(week.daySeven || '—')}</span>
                            </div>
                            ${week.note ? `<div class="fm-season-week-note">${htmlEscape(week.note)}</div>` : ''}
                        </div>`).join('')}
                </div>
            </section>`;
    }

    async function loadCountryPage() {
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }

        try {
            const countryIso = String(countryIsoCode).toUpperCase();

            // Render the shell first so a slow national-team call cannot leave the page blank, then
            // fill the squads in when they land.
            const [countriesResponse, leaguesResponse, calendarResponse, seasonResponse] = await Promise.all([
                authFetch('/countries'),
                authFetch(`/countries/${encodeURIComponent(countryIso)}/leagues`),
                // The schedule is read from the server, not restated here. The seven-day template is a
                // fact about the game; a copy of it in JavaScript is a second fact that will drift.
                authFetch('/calendar/week'),
                authFetch('/calendar/season')
            ]);

            if (!leaguesResponse.ok) throw new Error(`Country leagues load failed: ${leaguesResponse.status}`);

            const countries = countriesResponse.ok ? await countriesResponse.json() : [];
            const leagues = await leaguesResponse.json();
            const sortedLeagues = sortCountryLeagues(leagues);
            const country = (Array.isArray(countries) ? countries : []).find(item => String(item?.isoCode || '').toUpperCase() === countryIso) || {
                name: getCurrentUserCountryName() || countryIso,
                isoCode: countryIso,
                flagImagePath: '',
                currencyCode: ''
            };
            const countryName = country?.name || getCurrentUserCountryName() || countryIso;
            const countryTitle = htmlEscape(countryName);

            // A failed calendar load must not take the page down with it. The rest of the country page
            // is still worth showing, and a blank schedule says "nothing is on", which is a lie.
            const calendar = calendarResponse && calendarResponse.ok
                ? await calendarResponse.json().catch(() => null)
                : null;
            const weekDays = Array.isArray(calendar?.days) ? calendar.days : [];

            // The whole season, so weeks 6 and 12 are visible as deliberate rather than as a gap. Both
            // have no league football, and a manager who does not know that reads them as a bug.
            const season = seasonResponse && seasonResponse.ok
                ? await seasonResponse.json().catch(() => null)
                : null;
            const seasonWeeks = Array.isArray(season?.weeks) ? season.weeks : [];

            // Facts, once, in a strip. The previous four tall cards held 50 / 50 / 31 / RSD - three of
            // which a manager can already read off the page around them.
            const facts = [
                { label: 'ISO', value: country?.isoCode || countryIso },
                { label: 'Currency', value: country?.currencyCode || '—' },
                { label: 'Leagues', value: sortedLeagues.length },
                { label: 'Reputation', value: country?.reputation ?? '—' },
                { label: 'Youth rating', value: country?.youthRating ?? '—' }
            ];

            mainContent.innerHTML = `
                <div class="fm-page fm-page--country">
                    <header class="fm-country-header">
                        <div class="fm-country-header-main">
                            ${buildCountryFlagBadgeHtml(country, countryName)}
                            <div>
                                <div class="fm-eyebrow">National football &middot; ${htmlEscape(countryName)}</div>
                                <h2 class="fm-country-header-title">${countryTitle}</h2>
                            </div>
                        </div>
                        <button class="back-to-dashboard fm-country-header-back" data-nav-back="dashboard">Back</button>
                        <dl class="fm-country-facts">
                            ${facts.map(fact => `
                                <div class="fm-country-fact">
                                    <dt>${htmlEscape(fact.label)}</dt>
                                    <dd>${htmlEscape(String(fact.value))}</dd>
                                </div>`).join('')}
                        </dl>
                    </header>

                    <div class="fm-country-stack">
                        ${buildWeekSegment(weekDays, calendar?.week, calendar?.note)}
                        ${buildSeasonSegment(seasonWeeks)}
                        ${buildNationalTeamSegment(null, 'senior')}
                        ${buildNationalTeamSegment(null, 'u21')}
                        ${buildLeagueSegment(sortedLeagues)}
                    </div>
                </div>`;

            mainContent.querySelectorAll('[data-country-league-id]').forEach(button => {
                button.addEventListener('click', () => {
                    openCountryLeague(Number(button.dataset.countryLeagueId), button.dataset.countryLeagueName || 'League');
                });
            });
            mainContent.querySelectorAll('[data-nt-level]').forEach(button => {
                button.addEventListener('click', () => openNationalTeam(button.dataset.ntLevel));
            });

            // The squads arrive after the shell so the page is usable immediately.
            const [senior, u21] = await Promise.all([
                readNationalTeam(countryIso, 'senior'),
                readNationalTeam(countryIso, 'u21')
            ]);
            const cards = mainContent.querySelectorAll('.fm-nt-card');
            const replacements = [buildNationalTeamSegment(senior, 'senior'), buildNationalTeamSegment(u21, 'u21')];
            cards.forEach((card, index) => {
                if (!replacements[index]) return;
                const holder = document.createElement('div');
                holder.innerHTML = replacements[index];
                const fresh = holder.firstElementChild;
                card.replaceWith(fresh);
                fresh.querySelectorAll('[data-nt-level]').forEach(button => {
                    button.addEventListener('click', () => openNationalTeam(button.dataset.ntLevel));
                });
            });

        } catch (err) {
            console.error('Failed to load country page:', err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <h2>Error</h2>
                    <p>Could not load your country overview.</p>
                </div>`;
        }
    }

    /**
     * The national-team screen. Reads the real squad created by NationalTeamSeeder.
     *
     * <p>Replaces the placeholder that said "not built yet" while a real squad sat in the database.
     */
    async function loadNationalTeamPage(level = 'senior') {
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }

        const countryIso = String(countryIsoCode).toUpperCase();
        const nationalTeam = await readNationalTeam(countryIso, level);
        if (!nationalTeam) {
            mainContent.innerHTML = `
                <div class="manager-card">
                    <h2>Error</h2>
                    <p>Could not load the national team.</p>
                </div>`;
            return;
        }

        const squad = nationalTeam.squad || [];
        const selectorLine = nationalTeam.selectorName
            ? `${htmlEscape(nationalTeam.selectorName)}${nationalTeam.selectorIsProvisional ? ' <span class="fm-nt-provisional">provisional, pending elections</span>' : ''}`
            : 'No selector assigned';

        mainContent.innerHTML = `
            <div class="fm-page fm-page--national-team">
                <header class="fm-country-header">
                    <div class="fm-country-header-main">
                        <div>
                            <div class="fm-eyebrow">${level === 'u21' ? 'Under-21' : 'Senior national team'} &middot; ${htmlEscape(nationalTeam.countryName || countryIso)}</div>
                            <h2 class="fm-country-header-title">${htmlEscape(nationalTeam.teamName || 'National Team')}</h2>
                        </div>
                    </div>
                    <button class="back-to-dashboard fm-country-header-back" data-nav-back="dashboard">Back</button>
                    <dl class="fm-country-facts">
                        <div class="fm-country-fact"><dt>Selector</dt><dd>${selectorLine}</dd></div>
                        <div class="fm-country-fact"><dt>Squad</dt><dd>${squad.length}</dd></div>
                    </dl>
                </header>

                <div class="fm-country-stack">
                    <section class="fm-panel">
                        <div class="fm-panel-head">
                            <div>
                                <h3>Squad</h3>
                                <p class="fm-subtle">Best-rated players from every club in the country. Call-ups, form and fixtures come next.</p>
                            </div>
                        </div>
                        ${squad.length
                            ? `<div class="fm-squad">${buildSquadRows(squad)}</div>`
                            : '<div class="fm-empty">No squad has been selected for this national team yet.</div>'}
                    </section>
                </div>
            </div>`;
    }

    return { loadCountryPage, loadNationalTeamPage, openCountryLeague };
}

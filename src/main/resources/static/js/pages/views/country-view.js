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

    /** Never let one failing read blank the page: the section shows why instead. */
    async function readJson(path) {
        try {
            const response = await authFetch(path);
            if (!response.ok) return { failed: true, status: response.status };
            return await response.json();
        } catch (err) {
            console.warn(`Could not read ${path}:`, err);
            return { failed: true };
        }
    }

    /**
     * The real country row, so headers can draw the real flag.
     *
     * <p>The cup and playoff views used to hand buildHeader a stub of { name: countryIso }. The flag
     * badge builder had no flagImagePath to work from and drew a broken placeholder, which is why
     * the Serbian flag looked wrong there while it was fine on the country page.
     */
    async function resolveCountry(countryIso) {
        const countries = await readJson('/countries');
        const found = (Array.isArray(countries) ? countries : [])
            .find(item => String(item?.isoCode || '').toUpperCase() === countryIso);
        return found || { name: countryIso, isoCode: countryIso, flagImagePath: '' };
    }

    function readNationalTeam(countryIso, level) {
        return readJson(
            `/countries/${encodeURIComponent(countryIso)}/national-team?level=${encodeURIComponent(level)}`);
    }

    // ---------------------------------------------------------------- header

    /**
     * Flag and title left, Back hard right, facts on one line. Shared by all three tabs so the
     * country page does not change shape when you switch tab.
     */
    function buildHeader(country, countryIso, facts, extraFacts) {
        return `
            <header class="fm-country-header">
                <div class="fm-country-header-main">
                    ${buildCountryFlagBadgeHtml(country, country?.name || countryIso)}
                    <div>
                        <div class="fm-eyebrow">${htmlEscape(country?.name || countryIso)}</div>
                        <h2 class="fm-country-header-title">${htmlEscape(country?.name || countryIso)}</h2>
                    </div>
                </div>
                <button class="back-to-dashboard fm-country-header-back" data-nav-back="dashboard">Back</button>
                <dl class="fm-country-facts">
                    ${[...facts, ...(extraFacts || [])].map(fact => `
                        <div class="fm-country-fact">
                            <dt>${htmlEscape(fact.label)}</dt>
                            <dd>${fact.raw ? fact.value : htmlEscape(String(fact.value))}</dd>
                        </div>`).join('')}
                </dl>
            </header>`;
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Only the selector sees the squad tabs.
     *
     * <p>The tabs are not rendered at all for a non-selector, rather than rendered and disabled: a
     * permanent greyed-out tab reads as "locked", which invites the user to find a way around it. The
     * endpoints check the appointment independently, so hiding is a courtesy, not the control.
     */
    function buildTabs(activeTab) {
        return `
            <nav class="fm-player-tabs fm-country-tabs">
                <button type="button" class="fm-player-tab ${activeTab === 'general' ? 'is-active' : ''}" data-country-tab="general">General</button>
                <button type="button" class="fm-player-tab ${activeTab === 'calendar' ? 'is-active' : ''}" data-country-tab="calendar">Calendar</button>
                <button type="button" class="fm-player-tab ${activeTab === 'senior' ? 'is-active' : ''}" data-country-tab="senior">National Team</button>
                <button type="button" class="fm-player-tab ${activeTab === 'u21' ? 'is-active' : ''}" data-country-tab="u21">U-21</button>
            </nav>`;
    }

    // --------------------------------------------------------------- general

    function buildGeneralTab(ctx) {
        const { sortedLeagues, weekDays, calendarWeek, calendarNote, seasonWeeks, senior, u21 } = ctx;
        return `
            <div class="fm-country-stack">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <h3>Competitions</h3>
                            <p class="fm-subtle">Cup, leagues and playoffs for this country.</p>
                        </div>
                    </div>
                    <div class="fm-country-links">
                        <button type="button" class="fm-country-link-row" data-country-route="cup">
                            <span class="fm-country-link-label">Cup</span>
                            <span class="fm-country-link-sub">${ctx.cup.exists
                                ? `${ctx.cup.totalFixtures} ties drawn`
                                : 'Not created yet'}</span>
                            <span class="fm-country-link-go">Open &rsaquo;</span>
                        </button>
                        <button type="button" class="fm-country-link-row" data-country-route="playoffs">
                            <span class="fm-country-link-label">Playoffs</span>
                            <span class="fm-country-link-sub">${htmlEscape(ctx.playoffs.note || '')}</span>
                            <span class="fm-country-link-go">Open &rsaquo;</span>
                        </button>
                    </div>
                    <div class="fm-country-select-row">
                        <label class="fm-season-select-wrap fm-country-select-control">
                            <span>Leagues</span>
                            <select id="country-league-select" class="fm-season-select">
                                ${sortedLeagues.map(league => `<option value="${league?.id || ''}" data-league-name="${htmlEscape(league?.name || 'League')}">${htmlEscape(league?.name || 'League')} &middot; ${htmlEscape(buildLeagueMetaLabel(league))}</option>`).join('')}
                            </select>
                        </label>
                        <button type="button" id="country-open-selected-league" class="fm-action-btn">Open league</button>
                    </div>
                </section>

                ${buildNationalTeamSummary('Senior national team', 'senior', senior)}
                ${buildNationalTeamSummary('Under-21', 'u21', u21)}

            </div>`;
    }

    function buildCalendarTab(ctx) {
        return `
            <div class="fm-country-stack">
                ${buildWeekSegment(ctx.weekDays, ctx.calendarWeek, ctx.calendarNote)}
                ${buildSeasonSegment(ctx.seasonWeeks)}
            </div>`;
    }

    /** Selector, ranking, last match, next match and the election panel. */
    function buildNationalTeamSummary(eyebrow, level, nt) {
        if (!nt || nt.failed) {
            return `
                <section class="fm-panel">
                    <div class="fm-panel-head"><div>
                        <div class="fm-eyebrow">${htmlEscape(eyebrow)}</div>
                        <h3>Unavailable</h3>
                        <p class="fm-subtle">This national team could not be loaded.</p>
                    </div></div>
                </section>`;
        }
        if (!nt.exists) {
            return `
                <section class="fm-panel">
                    <div class="fm-panel-head"><div>
                        <div class="fm-eyebrow">${htmlEscape(eyebrow)}</div>
                        <h3>Not created yet</h3>
                    </div></div>
                </section>`;
        }

        const election = nt.election || {};
        const ranking = nt.ranking || {};
        const selectorLink = nt.isSelector
            ? `<span class="fm-subtle">You are the selector</span>`
            : nt.selectorName
                ? `<button type="button" class="fm-country-inline-link" data-country-route="selector" data-level="${level}">${htmlEscape(nt.selectorName)}</button>`
                : `<span class="fm-subtle">No selector</span>`;

        return `
            <section class="fm-panel" data-country-nt-level="${level}">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">${htmlEscape(eyebrow)}</div>
                        <h3>${htmlEscape(nt.teamName || 'National Team')}</h3>
                        <p class="fm-subtle">Selector: ${selectorLink}${nt.selectorIsProvisional
                            ? ' <span class="fm-nt-provisional">provisional</span>' : ''}</p>
                    </div>
                    <div class="fm-panel-head-actions">
                        <span class="fm-nt-squad-count">${nt.squadSize} squad</span>
                        ${nt.isSelector ? `<button type="button" class="fm-action-btn secondary" data-country-tab-jump="${level}">Manage</button>` : ''}
                    </div>
                </div>
                <div class="fm-nt-summary-grid">
                    <div class="fm-nt-summary-cell">
                        <span class="fm-nt-summary-label">Ranking</span>
                        <span class="fm-nt-summary-value">${ranking.rank == null ? '&mdash;' : htmlEscape(String(ranking.rank))}</span>
                        <span class="fm-nt-summary-note">${htmlEscape(ranking.reason || '')}</span>
                    </div>
                    <div class="fm-nt-summary-cell">
                        <span class="fm-nt-summary-label">Last match</span>
                        <span class="fm-nt-summary-value">${nt.lastMatch
                            ? htmlEscape(`${nt.lastMatch.homeName} v ${nt.lastMatch.awayName}`)
                            : 'None played'}</span>
                        <span class="fm-nt-summary-note">${nt.lastMatch ? `Week ${nt.lastMatch.week}` : 'No results yet'}</span>
                    </div>
                    <div class="fm-nt-summary-cell">
                        <span class="fm-nt-summary-label">Next match</span>
                        <span class="fm-nt-summary-value">${nt.nextMatch
                            ? htmlEscape(`${nt.nextMatch.homeName} v ${nt.nextMatch.awayName}`)
                            : 'Not scheduled'}</span>
                        <span class="fm-nt-summary-note">${nt.nextMatch ? `Week ${nt.nextMatch.week}` : 'No fixture yet'}</span>
                    </div>
                </div>
                ${buildElectionBlock(level, election, nt)}
            </section>`;
    }

    /**
     * The election panel: stand, vote, or show the result.
     *
     * <p>Rendered in every state, disabled when there is nothing to do. A section that only appears
     * when it works is one nobody can find, and the owner asked for it to go live when an election
     * opens rather than to appear from nowhere.
     *
     * <p>Vote counts are only rendered when the server sent them. For an undecided election the
     * endpoint omits the key entirely for ordinary callers, so there is nothing here to leak and
     * nothing to accidentally un-hide.
     */
    function buildElectionBlock(level, election, nt) {
        const state = election.stage || 'NONE';
        const canStand = !!election.acceptingCandidates;
        const canVote = !!election.acceptingVotes;
        const viewerIsCandidate = !!election.viewerIsCandidate;
        const candidates = (election.candidates || []).filter(c => !c.withdrawn);
        const decided = state === 'DECIDED';

        let body;
        if (decided) {
            body = `
                <p class="fm-subtle">${htmlEscape(election.winner ? `${election.winner} takes the job for one season.` : 'Decided.')}</p>`;
        } else if (candidates.length === 0) {
            body = `
                <p class="fm-subtle">${htmlEscape(election.note || 'No candidates yet.')}</p>
                <button type="button" class="fm-action-btn" data-election-stand="${level}" ${canStand ? '' : 'disabled'}>
                    ${canStand ? 'Stand for election' : 'Registration closed'}
                </button>`;
        } else {
            const rows = candidates.map(candidate => {
                const chosen = nt.election?.myVote?.candidateId === candidate.id;
                const votes = Object.prototype.hasOwnProperty.call(candidate, 'votes')
                    ? `${candidate.votes} vote${candidate.votes === 1 ? '' : 's'}`
                    : 'tally hidden';
                return `
                    <div class="fm-nt-candidate${chosen ? ' is-chosen' : ''}">
                        <span class="fm-nt-candidate-name">${htmlEscape(candidate.name)}</span>
                        <span class="fm-nt-candidate-votes">${htmlEscape(votes)}</span>
                        <button type="button" class="fm-squad-add" data-election-vote="${candidate.id}" data-election-level="${level}"
                            ${canVote ? '' : 'disabled'}>${chosen ? 'Your vote' : 'Vote'}</button>
                    </div>`;
            }).join('');

            const myVote = nt.election?.myVote
                ? `<p class="fm-subtle">You voted for ${htmlEscape(nt.election.myVote.candidateName)}${nt.election.myVote.changeCount
                    ? ` (changed ${nt.election.myVote.changeCount}×)` : ''}. Changeable until voting closes.</p>`
                : '';

            body = `
                ${myVote}
                <div class="fm-nt-candidates">${rows}</div>
                <button type="button" class="fm-action-btn secondary" data-election-stand="${level}" ${canStand ? '' : 'disabled'}>
                    ${viewerIsCandidate ? 'Withdraw' : (canStand ? 'Stand for election' : 'Registration closed')}
                </button>`;
        }

        const stateLabel = decided ? 'Declared'
            : state === 'ANNULLED' ? 'Void'
            : canVote ? 'Voting open'
            : canStand ? 'Registration open'
            : 'Closed';

        return `
            <div class="fm-nt-election${election.open || decided ? ' is-open' : ''}">
                <div class="fm-nt-election-head">
                    <span class="fm-nt-summary-label">Elections</span>
                    <span class="fm-nt-election-state">${htmlEscape(stateLabel)}</span>
                </div>
                ${body}
            </div>`;
    }

    // ------------------------------------------------------------- squad tab

    function buildSquadRows(squad, options) {
        const { showClub = false, removable = false, addable = false } = options || {};
        return (squad || []).map((player, index) => `
            <div class="fm-squad-row">
                <span class="fm-squad-rank">${index + 1}</span>
                <span class="fm-squad-pos">${htmlEscape(player?.position || '—')}</span>
                <span class="fm-squad-name" title="${htmlEscape(player?.name || '')}">${htmlEscape(player?.name || 'Unknown')}</span>
                ${showClub ? `<span class="fm-squad-club">${htmlEscape(player?.clubName || '')}</span>` : ''}
                <span class="fm-squad-meta">${player?.age ?? '—'} yrs</span>
                <span class="fm-squad-rating">${player?.rating ?? '—'}</span>
                ${removable
                    ? `<button type="button" class="fm-squad-remove" data-squad-remove="${player?.id}" title="Release from squad">Release</button>`
                    : ''}
                ${addable
                    ? `<button type="button" class="fm-squad-add" data-squad-add="${player?.id}" title="Call up">Call up</button>`
                    : ''}
            </div>`).join('');
    }

    function buildSelectorTab(nt, level) {
        if (!nt || nt.failed) {
            return '<section class="fm-panel"><div class="fm-empty">This national team could not be loaded.</div></section>';
        }
        if (!nt.exists) {
            return '<section class="fm-panel"><div class="fm-empty">This national team has not been created yet.</div></section>';
        }

        if (!nt.isSelector) {
            // The tab is only built for the selector, so this is a guard rather than a normal state.
            return `
                <section class="fm-panel">
                    <div class="fm-empty">Only the selector of this team can manage the squad.</div>
                </section>`;
        }

        const squad = nt.squad || [];
        const pool = nt.pool || [];
        const full = squad.length >= 25;

        return `
            <div class="fm-country-stack">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">Squad</div>
                            <h3>${htmlEscape(nt.teamName || 'National Team')}</h3>
                            <p class="fm-subtle">${squad.length} of 25 selected. Release a player to make room.</p>
                        </div>
                    </div>
                    ${squad.length
                        ? `<div class="fm-squad">${buildSquadRows(squad, { showClub: true, removable: true })}</div>`
                        : '<div class="fm-empty">Squad is empty. Call players up from the pool below.</div>'}
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">National pool</div>
                            <h3>Available players</h3>
                            <p class="fm-subtle">${nt.poolSize} players in the country, best first. ${full ? 'The squad is full - release someone first.' : 'Calling a player up copies them onto the national roster; they keep playing for their club.'}</p>
                        </div>
                    </div>
                    ${pool.length
                        ? `<div class="fm-squad">${buildSquadRows(pool, { showClub: true, addable: !full })}</div>`
                        : '<div class="fm-empty">No players available.</div>'}
                </section>
            </div>`;
    }

    // ------------------------------------------------------------- schedules

    function buildWeekSegment(weekDays, calendarWeek, calendarNote) {
        if (!weekDays || !weekDays.length) {
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
        if (!seasonWeeks || !seasonWeeks.length) {
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

    // ------------------------------------------------------------------ cup

    function buildCupPage(cup, country) {
        if (!cup.exists) {
            return `
                <section class="fm-panel"><div class="fm-empty">No cup exists for ${htmlEscape(country?.name || 'this country')} yet.</div></section>`;
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <div class="fm-eyebrow">Cup</div>
                        <h2 class="fm-country-header-title">${htmlEscape(cup.name || 'Cup')}</h2>
                        <p class="fm-subtle">${cup.totalFixtures} ties across ${cup.rounds.length} rounds.</p>
                    </div>
                </div>
                ${cup.rounds.map(round => `
                    <div class="fm-cup-round">
                        <div class="fm-cup-round-head">
                            <span class="fm-eyebrow">Week ${round.week}</span>
                            <span class="fm-cup-round-count">${round.fixtures.length} ties</span>
                        </div>
                        ${round.fixtures.length
                            ? `<div class="fm-cup-ties">${round.fixtures.map(tie => `
                                <div class="fm-cup-tie">
                                    <span>${htmlEscape(tie.home || 'TBC')}</span>
                                    <span class="fm-cup-tie-sep">v</span>
                                    <span>${htmlEscape(tie.away || 'TBC')}</span>
                                </div>`).join('')}</div>`
                            : '<div class="fm-empty">Not drawn yet.</div>'}
                    </div>`).join('')}
            </section>`;
    }

    // ------------------------------------------------------------------ page

    async function loadCountryPage(options) {
        const { tab = 'general', level: forcedLevel } = options || {};
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }

        const countryIso = String(countryIsoCode).toUpperCase();

        try {
            const [countries, leaguesResponse, calendarResponse, seasonResponse, seniorNt, u21Nt, cup, playoffs] =
                await Promise.all([
                    readJson('/countries'),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/leagues`),
                    readJson('/calendar/week'),
                    readJson('/calendar/season'),
                    readNationalTeam(countryIso, 'senior'),
                    readNationalTeam(countryIso, 'u21'),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/cup`),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/playoffs`)
                ]);

            if (leaguesResponse.failed) throw new Error(`Country leagues load failed: ${leaguesResponse.status}`);
            const sortedLeagues = sortCountryLeagues(leaguesResponse || []);
            const country = (Array.isArray(countries) ? countries : [])
                .find(item => String(item?.isoCode || '').toUpperCase() === countryIso) || {
                    name: getCurrentUserCountryName() || countryIso,
                    isoCode: countryIso
                };

            const weekDays = calendarResponse && !calendarResponse.failed && Array.isArray(calendarResponse.days)
                ? calendarResponse.days : [];
            const seasonWeeks = seasonResponse && !seasonResponse.failed && Array.isArray(seasonResponse.weeks)
                ? seasonResponse.weeks : [];

            // Only the selector gets a squad tab, and the tab they get depends on the level.
            const level = forcedLevel || (tab === 'u21' ? 'u21' : 'senior');
            const activeNt = level === 'u21' ? u21Nt : seniorNt;
            const canManage = !!(activeNt && !activeNt.failed && activeNt.exists && activeNt.isSelector);
            const resolvedTab = tab === 'calendar' ? 'calendar'
                : (tab === 'general' ? 'general' : (canManage ? level : 'general'));

            const facts = [
                { label: 'ISO', value: country?.isoCode || countryIso },
                { label: 'Currency', value: country?.currencyCode || '—' },
                { label: 'Leagues', value: sortedLeagues.length },
                { label: 'Reputation', value: country?.reputation ?? '—' },
                { label: 'Youth rating', value: country?.youthRating ?? '—' }
            ];

            const schedule = {
                weekDays, seasonWeeks,
                calendarWeek: calendarResponse?.week, calendarNote: calendarResponse?.note
            };

            let body;
            if (resolvedTab === 'general') {
                body = buildGeneralTab({
                    sortedLeagues, senior: seniorNt, u21: u21Nt,
                    cup: cup || { exists: false }, playoffs: playoffs || { note: 'Unavailable' }
                });
            } else if (resolvedTab === 'calendar') {
                body = buildCalendarTab(schedule);
            } else {
                body = buildSelectorTab(activeNt, resolvedTab);
            }

            const tabs = buildTabs(resolvedTab);
            const isGeneral = resolvedTab === 'general';

            mainContent.innerHTML = `
                <div class="fm-page fm-page--country">
                    ${buildHeader(country, countryIso, facts,
                        isGeneral ? [] : [{ label: 'Squad', value: String(activeNt?.squadSize ?? 0) + ' / 25' }])}
                    ${tabs}
                    ${body}
                </div>`;

            wireCountryPage(mainContent, countryIso, resolvedTab);
        } catch (err) {
            console.error('Failed to load country page:', err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load your country overview.</p>
                </div>`;
        }
    }

    function wireCountryPage(root, countryIso, activeTab) {
        root.querySelectorAll('[data-country-route]').forEach(element => {
            element.addEventListener('click', () => {
                const route = element.dataset.countryRoute;
                if (route === 'cup' || route === 'playoffs') {
                    loadPage(route === 'cup' ? 'countryCup' : 'countryPlayoffs');
                    return;
                }
                // selector / election have no screen yet; the panel stays visible and disabled.
                window.alert('Not built yet.');
            });
        });
        root.querySelectorAll('[data-country-tab]').forEach(button => {
            button.addEventListener('click', () => loadCountryPage({ tab: button.dataset.countryTab }));
        });
        root.querySelectorAll('[data-country-tab-jump]').forEach(button => {
            button.addEventListener('click', () => loadCountryPage({ tab: button.dataset.countryTabJump }));
        });
        root.querySelectorAll('[data-country-league-id]').forEach(button => {
            button.addEventListener('click', () => {
                openCountryLeague(Number(button.dataset.countryLeagueId), button.dataset.countryLeagueName || 'League');
            });
        });

        const select = root.querySelector('#country-league-select');
        const openButton = root.querySelector('#country-open-selected-league');
        if (select && openButton) {
            openButton.addEventListener('click', () => {
                const option = select.options[select.selectedIndex];
                const id = Number(select.value);
                if (!id) return;
                openCountryLeague(id, option?.dataset?.leagueName || option?.textContent || 'League');
            });
        }

        root.querySelectorAll('[data-election-stand], [data-election-vote]').forEach(button => {
            button.addEventListener('click', async () => {
                button.disabled = true;
                await runElectionAction(countryIso, button);
                await loadCountryPage({ tab: activeTab === 'general' ? 'general' : activeTab });
            });
        });

        root.querySelectorAll('[data-squad-add]').forEach(button => {
            button.addEventListener('click', async () => {
                button.disabled = true;
                await postSquad(countryIso, activeTab, button.dataset.squadAdd);
            });
        });
        root.querySelectorAll('[data-squad-remove]').forEach(button => {
            button.addEventListener('click', async () => {
                button.disabled = true;
                await deleteSquad(countryIso, activeTab, button.dataset.squadRemove);
            });
        });
    }

    /**
     * Election actions.
     *
     * <p>Withdraw is a DELETE and stand is a POST to the same route, so the button's own label
     * decides which: a user who is already a candidate means withdraw. Sending the wrong one is
     * harmless but confusing, so the intent is read from the payload the server returns.
     */
    async function runElectionAction(countryIso, element) {
        const level = element.dataset.electionLevel
            || element.dataset.electionStand
            || element.closest('[data-country-nt-level]')?.dataset.countryNtLevel
            || 'senior';

        if (element.dataset.squadAdd != null) {
            return;
        }
        if (element.dataset.electionVote) {
            await postJson(`/countries/${encodeURIComponent(countryIso)}/national-team/election/vote?level=${encodeURIComponent(level)}`,
                { candidateId: Number(element.dataset.electionVote) });
            return;
        }
        if (element.dataset.electionStand) {
            const election = await readJson(
                `/countries/${encodeURIComponent(countryIso)}/national-team/election?level=${encodeURIComponent(level)}`);
            if (election && election.viewerIsCandidate) {
                await deleteJson(`/countries/${encodeURIComponent(countryIso)}/national-team/election/candidacy?level=${encodeURIComponent(level)}`);
            } else {
                await postJson(`/countries/${encodeURIComponent(countryIso)}/national-team/election/candidacy?level=${encodeURIComponent(level)}`, {});
            }
        }
    }

    async function postJson(path, body) {
        const response = await authFetch(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
        await reportElectionFailure(response);
    }

    async function deleteJson(path) {
        const response = await authFetch(path, { method: 'DELETE' });
        await reportElectionFailure(response);
    }

    /** A rejected election action has to say why, or the panel just blinks. */
    async function reportElectionFailure(response) {
        if (response.ok) return;
        const body = await response.json().catch(() => ({}));
        window.alert(body.message || 'That election action was refused.');
    }

    async function postSquad(countryIso, level, playerId) {
        const response = await authFetch(
            `/countries/${encodeURIComponent(countryIso)}/national-team/squad?level=${encodeURIComponent(level)}`,
            {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ playerId: Number(playerId) })
            });
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            window.alert(body.message || 'Could not call that player up.');
        }
        await loadCountryPage({ tab: level });
    }

    async function deleteSquad(countryIso, level, playerId) {
        const response = await authFetch(
            `/countries/${encodeURIComponent(countryIso)}/national-team/squad/${encodeURIComponent(playerId)}?level=${encodeURIComponent(level)}`,
            { method: 'DELETE' });
        if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            window.alert(body.message || 'Could not release that player.');
        }
        await loadCountryPage({ tab: level });
    }

    /** The cup, as its own view. Reached from the general tab. */
    async function loadCupPage() {
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }
        const countryIso = String(countryIsoCode).toUpperCase();
        const [cup, country] = await Promise.all([
            readJson(`/countries/${encodeURIComponent(countryIso)}/cup`),
            resolveCountry(countryIso)
        ]);
        mainContent.innerHTML = `
            <div class="fm-page fm-page--country">
                ${buildHeader(country, countryIso, [
                    { label: 'ISO', value: countryIso },
                    { label: 'Ties', value: cup?.totalFixtures ?? 0 },
                    { label: 'Rounds', value: cup?.rounds?.length ?? 0 }
                ])}
                ${buildTabs('cup')}
                ${buildCupPage(cup || { exists: false }, country)}
            </div>`;
        mainContent.querySelectorAll('[data-country-tab]').forEach(button => {
            button.addEventListener('click', () => loadCountryPage({ tab: button.dataset.countryTab }));
        });
    }

    async function loadPlayoffPage() {
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }
        const countryIso = String(countryIsoCode).toUpperCase();
        const [playoffs, country] = await Promise.all([
            readJson(`/countries/${encodeURIComponent(countryIso)}/playoffs`),
            resolveCountry(countryIso)
        ]);
        mainContent.innerHTML = `
            <div class="fm-page fm-page--country">
                ${buildHeader(country, countryIso, [
                    { label: 'ISO', value: countryIso },
                    { label: 'Ties', value: (playoffs?.fixtures || []).length }
                ])}
                ${buildTabs('playoffs')}
                <section class="fm-panel">
                    <div class="fm-panel-head"><div>
                        <h3>Playoffs</h3>
                        <p class="fm-subtle">${htmlEscape(playoffs?.note || '')}</p>
                    </div></div>
                    ${(playoffs?.fixtures || []).length
                        ? `<div class="fm-cup-ties">${playoffs.fixtures.map(tie => `
                            <div class="fm-cup-tie"><span>${htmlEscape(tie.home || 'TBC')}</span>
                            <span class="fm-cup-tie-sep">v</span>
                            <span>${htmlEscape(tie.away || 'TBC')}</span></div>`).join('')}</div>`
                        : '<div class="fm-empty">No playoff ties have been generated.</div>'}
                </section>
            </div>`;
        mainContent.querySelectorAll('[data-country-tab]').forEach(button => {
            button.addEventListener('click', () => loadCountryPage({ tab: button.dataset.countryTab }));
        });
    }

    /** Routes for the country page's own sub-views, wired by the SPA router. */
    function routeTo(route) {
        if (route === 'cup') return loadCupPage();
        if (route === 'playoffs') return loadPlayoffPage();
        return loadCountryPage({ tab: 'general' });
    }

    async function loadNationalTeamPage(level = 'senior') {
        return loadCountryPage({ tab: level });
    }

    return { loadCountryPage, loadNationalTeamPage, loadCupPage, loadPlayoffPage, routeTo, openCountryLeague };
}

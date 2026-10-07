// pages/views/country-view.js
import {
    htmlEscape, buildEmptyState, sortCountryLeagues, buildCountryFlagBadgeHtml,
    buildLeagueMetaLabel
} from './utils.js';

export function createCountryView(deps) {
    const {
        authFetch, loadPage, loadMatch, setActiveLeagueContext,
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

    function buildQualifyingSummary(data) {
        if (!data || data.failed) {
            return `
                <section class="fm-panel">
                    <div class="fm-panel-head"><div>
                        <h3>International qualifying</h3>
                        <p class="fm-subtle">The qualifying table could not be loaded.</p>
                    </div></div>
                </section>`;
        }
        const tiers = Array.isArray(data.tiers) ? data.tiers : [];
        const body = tiers.length === 0
            ? '<p class="fm-subtle">No league standings are available for the qualifying race yet.</p>'
            : tiers.map(tier => `
                <div class="fm-qualifying-tier">
                    <h4>Tier ${htmlEscape(tier.tier)}</h4>
                    ${(Array.isArray(tier.cups) ? tier.cups : []).map(cup => `
                        <div class="fm-qualifying-division">
                            <div class="fm-subtle">${htmlEscape(cup.cup)} · ${htmlEscape(cup.places)} place${cup.places === 1 ? '' : 's'}</div>
                            <div class="fm-squad-wrap">
                            <table class="fm-squad fm-league-table fm-qualifying-table">
                                <thead><tr><th>Pos</th><th>Club</th><th>Pts</th><th>GD</th><th>Status</th></tr></thead>
                                <tbody>${(Array.isArray(cup.standings) ? cup.standings : []).map(row => `
                                    <tr${row.qualifies ? ' class="is-qualified"' : ''}>
                                        <td>${htmlEscape(String(row.position ?? '—'))}</td>
                                        <td>${htmlEscape(row.teamName || 'Unknown club')}</td>
                                        <td>${htmlEscape(String(row.points ?? 0))}</td>
                                        <td>${htmlEscape(String(row.goalDifference ?? 0))}</td>
                                        <td>${row.qualifies ? 'Qualifies' : 'Candidate'}</td>
                                    </tr>`).join('')}</tbody>
                            </table>
                            </div>
                        </div>`).join('')}
                </div>`).join('');
        return `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>International qualifying</h3>
                    <p class="fm-subtle">Current league positions decide the Champions, Masters and Challenge Cup places.</p>
                </div></div>
                ${body}
            </section>`;
    }

    function buildGeneralTab(ctx) {
        const { sortedLeagues, senior, u21 } = ctx;
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

                ${buildQualifyingSummary(ctx.qualifying)}

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
                        ${nt.squadLock && nt.squadLock.locked
                            ? '<span class="fm-badge">Squad fixed</span>'
                            : ''}
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
                <div class="fm-country-links">
                    <button type="button" class="fm-country-link-row" data-national-competition-level="${level}" data-national-competition-stage="QUALIFYING">
                        <span class="fm-country-link-label">Qualifiers</span>
                        <span class="fm-country-link-sub">Group tables and matchdays</span>
                        <span class="fm-country-link-go">Open &rsaquo;</span>
                    </button>
                    <button type="button" class="fm-country-link-row" data-national-competition-level="${level}" data-national-competition-stage="WORLD_CUP">
                        <span class="fm-country-link-label">World Cup</span>
                        <span class="fm-country-link-sub">Knockout results and bracket</span>
                        <span class="fm-country-link-go">Open &rsaquo;</span>
                    </button>
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

        // The tournament squad is fixed from week 12 day 1, 10:00 (owner). The server refuses the write,
        // so this is not a permission - it is so a selector is not offered a button that answers with an
        // error, and so a manager who cannot change the list is told why instead of discovering it by
        // failing. `squadLock` is sent to everyone, not only the selector, for the same reason.
        const lock = nt.squadLock || {};
        const locked = lock.locked === true;
        const lockNote = locked
            ? `<div class="fm-callout fm-callout--warn">
                   <strong>The squad is fixed.</strong> ${htmlEscape(lock.reason || '')}
                   ${lock.fromWeek ? ` (Week ${lock.fromWeek}, day ${lock.fromDay}, ${String(lock.fromHour).padStart(2, '0')}:00.)` : ''}
               </div>`
            : '';
        const lockedNote = locked
            ? ' The squad is fixed for the tournament.'
            : '';

        return `
            <div class="fm-country-stack">
                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">Squad</div>
                            <h3>${htmlEscape(nt.teamName || 'National Team')}</h3>
                            <p class="fm-subtle">${squad.length} of 25 selected.${lockedNote}${locked ? '' : ' Release a player to make room.'}</p>
                        </div>
                    </div>
                    ${lockNote}
                    ${squad.length
                        ? `<div class="fm-squad">${buildSquadRows(squad, { showClub: true, removable: !locked })}</div>`
                        : '<div class="fm-empty">Squad is empty. Call players up from the pool below.</div>'}
                </section>

                <section class="fm-panel">
                    <div class="fm-panel-head">
                        <div>
                            <div class="fm-eyebrow">National pool</div>
                            <h3>Available players</h3>
                            <p class="fm-subtle">${nt.poolSize} players in the country, best first. ${locked ? 'The squad is fixed for the tournament.' : full ? 'The squad is full - release someone first.' : 'Calling a player up copies them onto the national roster; they keep playing for their club.'}</p>
                        </div>
                    </div>
                    ${pool.length
                        ? `<div class="fm-squad">${buildSquadRows(pool, { showClub: true, addable: !full && !locked })}</div>`
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
                                <button type="button" class="fm-cup-tie"
                                    data-cup-fixture="${tie.id}"
                                    data-cup-played="${tie.played ? 'true' : 'false'}"
                                    data-cup-match-id="${tie.matchId ?? ''}"
                                    title="${tie.played
                                        ? 'Open this tie'
                                        : 'Open the pre-match state for this tie'}">
                                    <span>${htmlEscape(tie.home || 'TBC')}</span>
                                    <span class="fm-cup-tie-sep">${tie.played ? tie.score || 'v' : 'v'}</span>
                                    <span>${htmlEscape(tie.away || 'TBC')}</span>
                                </button>`).join('')}</div>`
                            : '<div class="fm-empty">Not drawn yet.</div>'}
                    </div>`).join('')}
            </section>`;
    }

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
        // A cup tie opens the SHARED match view, like a league fixture. Not the bespoke sheet: that one
        // could only ever show the two squads and whether the tie had been played, and the owner asked
        // for every generated match to open the same way a league one does - prediction before, and
        // lineups, stats, goals and a report after (P0-PREV-1).
        //
        // `fixture: true` is explicit and never inferred. A fixture id and a match id are both small
        // integers over separate tables, and guessing between them has already once resolved a
        // dashboard link to somebody else's played match.
        mainContent.querySelectorAll('[data-cup-fixture]').forEach(button => {
            button.addEventListener('click', () => {
                const fixtureId = Number(button.dataset.cupFixture);
                if (!fixtureId || typeof loadMatch !== 'function') {
                    return;
                }
                // A played tie opens the MATCH, because lineups, stats, goals and the report are all
                // keyed by match id. An unplayed one opens the fixture, and is told so explicitly.
                const played = button.dataset.cupPlayed === 'true';
                const matchId = Number(button.dataset.cupMatchId);
                if (played && matchId) {
                    loadMatch(matchId, 'countryCup', { initialTab: 'preview' });
                } else {
                    loadMatch(fixtureId, 'countryCup', { initialTab: 'preview', fixture: true });
                }
            });
        });
        mainContent.querySelectorAll('[data-country-route]').forEach(button => {
            button.addEventListener('click', () => {
                if (button.dataset.countryRoute === 'cup') loadCupPage();
            });
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

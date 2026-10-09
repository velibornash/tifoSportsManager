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
                <button type="button" class="fm-player-tab ${activeTab === 'clubs' ? 'is-active' : ''}" data-country-tab="clubs">Clubs</button>
                <button type="button" class="fm-player-tab ${activeTab === 'qualifying' ? 'is-active' : ''}" data-country-tab="qualifying">Qualifying</button>
                <button type="button" class="fm-player-tab ${activeTab === 'senior' ? 'is-active' : ''}" data-country-tab="senior">National Team</button>
                <button type="button" class="fm-player-tab ${activeTab === 'u21' ? 'is-active' : ''}" data-country-tab="u21">U-21</button>
            </nav>`;
    }

    // --------------------------------------------------------------- general

    /**
     * International qualifying, as its own tab (owner, 2026-10-07, P1-CTRY-2).
     *
     * <p>*"International qualifying sekciju sa general taba iz Country dela da se prebaci u zaseban tab
     * kao sto su trenutno General, Calendar, national team i u-21"*.
     *
     * <p>It was a panel in the middle of General, between the competitions list and the two national-team
     * summaries — a per-tier table of league positions feeding three continental cups, sitting where it
     * looked like part of the national-team block.
     */
    function buildQualifyingTab(data) {
        return `<div class="fm-country-stack">${buildQualifyingSummary(data)}</div>`;
    }

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

                ${buildNationalTeamSummary('Senior national team', 'senior', senior)}
                ${buildNationalTeamSummary('Under-21', 'u21', u21)}

            </div>`;
    }

    /**
     * Arranging a national warm-up — optional, and it says so (owner, 2026-10-06/08).
     *
     * <p>The three endpoints behind this (`/slot`, `/opponents`, `POST /`) had been written since
     * 2026-10-06 and **nothing in the frontend called them**. The whole feature was unreachable: a manager
     * could not see the slot, name an opponent, or ask for a match.
     *
     * <p>Written as optional on purpose. The owner's point is that a warm-up is <b>not compulsory</b> — it
     * is an opportunity in one week, not an obligation — so the panel offers it and states the week, and
     * says plainly that not playing costs nothing.
     */
    function buildNationalWarmUpHtml(level, teamId, view, slot, opponents) {
        if (!teamId) return '';
        if (!view || view.failed || !slot || slot.failed) {
            return `
                <section class="fm-panel">
                    <div class="fm-panel-head">National warm-up</div>
                    <p class="fm-subtle">The warm-up list could not be loaded.</p>
                </section>`;
        }
        const list = Array.isArray(opponents) ? opponents : [];
        const week = slot.week ?? view.week;
        const day = slot.day ?? view.day;
        const mine = Array.isArray(view.outgoing) ? view.outgoing : [];
        const theirs = Array.isArray(view.incoming) ? view.incoming : [];

        return `
            <section class="fm-panel" data-warmup-team-id="${htmlEscape(String(teamId))}">
                <div class="fm-panel-head">National warm-up</div>
                <p class="fm-subtle">
                    Optional. A warm-up is an extra match in week ${htmlEscape(String(week))} day
                    ${htmlEscape(String(day))}; not playing one costs nothing and skips no rule.
                </p>
                <div class="fm-country-select-row">
                    <label class="fm-season-select-wrap fm-country-select-control">
                        <span>Opponent</span>
                        <select class="fm-season-select" data-warmup-opponent>
                            <option value="">Choose a national side…</option>
                            ${list.filter(o => String(o.id) !== String(teamId)).map(o => `
                                <option value="${htmlEscape(String(o.id))}">${htmlEscape(o.name || 'National team')} · ${htmlEscape(o.country || '')}</option>
                            `).join('')}
                        </select>
                    </label>
                    <button type="button" class="fm-action-btn" data-warmup-request>Ask for a warm-up</button>
                </div>
                <p class="fm-subtle" data-warmup-note></p>
                ${warmUpRequestListHtml('You have asked', mine, teamId)}
                ${warmUpRequestListHtml('Asked of this team', theirs, teamId)}
            </section>`;
    }

    /**
     * Asking for a warm-up, and accepting or cancelling one that is already on the table.
     *
     * <p>The 409 body is shown as it comes back rather than replaced by a generic failure: the endpoint's
     * refusal explains itself — wrong week, both sides must be national sides, or that side already has a
     * match on — and that sentence is more useful than "could not save".
     */
    function wireNationalWarmUp(root, activeTab) {
        const button = root.querySelector('[data-warmup-request]');
        const note = root.querySelector('[data-warmup-note]');
        const say = (text) => { if (note) note.textContent = text; };

        if (button) {
            button.addEventListener('click', async () => {
                const select = root.querySelector('[data-warmup-opponent]');
                const opponentId = select?.value;
                if (!opponentId) {
                    say('Choose an opponent first.');
                    return;
                }
                button.disabled = true;
                say('Asking…');
                try {
                    const response = await authFetch(
                        `/api/national/friendly-requests?requesterId=${encodeURIComponent(root.querySelector('[data-warmup-team-id]')?.dataset.warmupTeamId || '')}`
                        + `&opponentId=${encodeURIComponent(opponentId)}`,
                        { method: 'POST' });
                    const body = await response.json().catch(() => ({}));
                    if (!response.ok) {
                        say(body.detail || body.error || 'That warm-up could not be asked for.');
                        button.disabled = false;
                        return;
                    }
                    say('Asked. The other side has to accept it before it is a match.');
                    await loadCountryPage({ tab: activeTab });
                } catch (err) {
                    say(`That warm-up could not be asked for. ${err.message || ''}`);
                    button.disabled = false;
                }
            });
        }
    }

    /** One warm-up request: who it is with, and what it is waiting for. */
    function warmUpRequestListHtml(title, rows, teamId) {
        if (!rows.length) {
            return `<p class="fm-subtle">${htmlEscape(title)}: nothing.</p>`;
        }
        const statusText = (r) => {
            const other = String(r.requesterTeamId) === String(teamId)
                ? (r.opponentTeamId ?? '—')
                : (r.requesterTeamId ?? '—');
            const status = String(r.status || 'PENDING').replace(/_/g, ' ').toLowerCase();
            return `Side ${other} · ${status}`;
        };
        return `
            <div class="club-profile-detail-list">
                <div class="club-profile-detail-row">
                    <span>${htmlEscape(title)}</span>
                    <strong>${htmlEscape(rows.map(statusText).join(' · '))}</strong>
                </div>
            </div>`;
    }

    /**
     * The clubs of this country, ranked (owner, 2026-10-07, P1-CTRY-1).
     *
     * <p>*"nedostaje mi na stranici Country novi tab gde je ranking lista klubova iz te zemlje"*.
     *
     * <p>Ordered by the same ranking points the national ranking uses — one system, not two. The club
     * Elo that used to be the rating is head-to-head and is not a ranking any more, and a country page
     * showing its national side ordered by points and its clubs ordered by Elo would be two orderings on
     * one screen.
     *
     * <p><b>The division is on every row</b>, because the points are scaled by tier: two clubs on the
     * same number in different divisions are not equal, and a table that does not say which division a
     * row is in cannot honestly be read.
     */
    function buildClubsTab(payload) {
        const clubs = Array.isArray(payload?.clubs) ? payload.clubs : [];
        if (!clubs.length) {
            return '<section class="fm-panel"><p class="fm-empty">This country has no clubs yet.</p></section>';
        }
        return `
            <section class="fm-panel fm-clubs-ranking">
                <div class="fm-panel-head">
                    <div>
                        <h3>Club ranking</h3>
                        <p class="fm-subtle">${clubs.length} of ${payload.totalClubs ?? clubs.length}</p>
                    </div>
                </div>
                <div class="fm-table-scroll">
                    <table class="fm-table fm-clubs-ranking-table">
                        <thead>
                            <tr>
                                <th>#</th>
                                <th>Club</th>
                                <th>Division</th>
                                <th>Tier</th>
                                <th>Points</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${clubs.map(club => `
                                <tr>
                                    <td>${escapeNumber(club.position)}</td>
                                    <td>${htmlEscape(club.name || 'Unknown')}</td>
                                    <td>${htmlEscape(club.division || '—')}</td>
                                    <td>${club.tier ?? '—'}</td>
                                    <td class="fm-clubs-ranking-points">
                                        ${typeof club.points === 'number' ? club.points.toFixed(2) : '—'}
                                        ${club.rated ? '' : '<span class="fm-subtle"> (not yet rated)</span>'}
                                    </td>
                                </tr>`).join('')}
                        </tbody>
                    </table>
                </div>
            </section>`;
    }

    function escapeNumber(value) {
        return Number.isFinite(value) ? String(value) : '—';
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

    /**
     * The national-team tab: the squad, and the optional warm-up beside it.
     *
     * <p><b>The warm-up panel was first written into the General tab and broke the country page.</b> It
     * referenced `tab`, `warmUpSideId`, `warmUp`, `warmUpSlot` and `warmUpOpponents` — none of which are
     * in `buildGeneralTab`'s scope, since it only destructures `sortedLeagues, senior, u21` out of its
     * context. Every tab of the country page died with `ReferenceError: tab is not defined`, found in the
     * browser and not by any test.
     *
     * <p>It also belongs here rather than there: a warm-up is a thing the **national team** does, so it
     * sits on the senior and U-21 tabs where that team is, and it is passed in as context like every other
     * tab's data rather than closed over from the caller.
     */
    function buildSelectorTab(nt, level, warmUp) {
        const warmUpPanel = warmUp
            ? buildNationalWarmUpHtml(level, warmUp.teamId, warmUp.view, warmUp.slot, warmUp.opponents)
            : '';
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
                ${warmUpPanel}
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
                                    title="${tie.played ? 'Open this tie' : 'Open the pre-match state for this tie'}">
                                    <span>${htmlEscape(tie.home || 'TBC')}</span>
                                    <span class="fm-cup-tie-sep">${tie.played ? tie.score || 'v' : 'v'}</span>
                                    <span>${htmlEscape(tie.away || 'TBC')}</span>
                                </button>`).join('')}</div>`
                            : '<div class="fm-empty">Not drawn yet.</div>'}
                    </div>`).join('')}
            </section>`;
    }

    // ------------------------------------------------------- represented country

    /**
     * A country that has national sides and no club pyramid.
     *
     * <p>The owner replaced the old page (2026-10-07), which said only "ROU is represented, not played"
     * and pointed at Admin. True, and useless: the twenty-four other countries on the World page are
     * exactly this, and a manager who clicks one wants to know how its national team is doing. That is
     * the whole of what a represented country *is* in this world.
     *
     * <p>So: where it stands in the ranking, on what rating; which qualifying group each of its two
     * national sides is in; and whether it has played anything yet. The rating is the senior Elo for
     * the senior side and the youth rating for U-21 — two different columns, and reading one for the
     * other is the mistake this codebase has made before.
     *
     * <p>Every one of these is allowed to be absent. An undrawn tournament and an unplayed ranking are
     * normal states, and they are said in words rather than shown as zero.
     *
     * <p><b>These four functions were deleted and never restored</b> (recovered 2026-10-09). The commit
     * that put the country page back — {@code 73aafa6}, P1-CTRY-1 — restored {@code loadCountryPage}
     * and re-introduced the call to {@code renderRepresentedCountry}, but not the function. Every one
     * of the six tabs worked, every test was green, and {@code node --check} passed, because nothing
     * rendered this path. Clicking a country name in a World Cup group table threw
     * {@code ReferenceError: renderRepresentedCountry is not defined}, from a call that sits
     * <em>outside</em> the try block, so nothing caught it and the manager got a blank page.
     */
    async function renderRepresentedCountry(mainContent, countryIso) {
        const readOptional = async path => {
            try {
                return await readJson(path);
            } catch {
                return null;
            }
        };

        const [ranking, seniorQualifying, u21Qualifying] = await Promise.all([
            readOptional('/countries/ranking?level=senior'),
            readOptional('/api/national-tournaments/senior/QUALIFYING'),
            readOptional('/api/national-tournaments/u21/QUALIFYING')
        ]);

        const rows = Array.isArray(ranking) ? ranking : [];
        const senior = rows.find(row => String(row.isoCode || '').toUpperCase() === countryIso);
        const name = senior?.name || countryIso;

        const groupOf = payload => {
            if (!payload?.exists) return null;
            for (const group of (Array.isArray(payload.groups) ? payload.groups : [])) {
                const inGroup = (Array.isArray(group.table) ? group.table : [])
                    .some(entry => String(entry.countryIso || '').toUpperCase() === countryIso);
                if (inGroup) return { code: group.code, group };
            }
            return null;
        };
        const seniorGroup = groupOf(seniorQualifying);
        const u21Group = groupOf(u21Qualifying);

        const played = rows.filter(row => row.rated).length;
        const positionLine = senior
            ? (senior.rated
                ? `${senior.position}${ordinal(senior.position)} of ${rows.length} — ${formatNumber(senior.points)} points`
                : `Unrated, on the starting ${formatNumber(senior.points)} — no international results yet`)
            : 'Not in the ranking.';

        const sideCard = (label, group, competition) => `
            <section class="fm-panel">
                <div class="fm-panel-head"><div>
                    <h3>${htmlEscape(label)}</h3>
                    <p class="fm-subtle">${group
                        ? htmlEscape(competition) + ' — Group ' + htmlEscape(group.code)
                        : 'Not in a qualifying group yet.'}</p>
                </div></div>
                ${group ? groupStandingTable(group.group, countryIso) : `
                    <p class="fm-empty">The ${htmlEscape(competition)} has not been drawn, or this side is not in it.</p>`}
            </section>`;

        mainContent.innerHTML = `
            <div class="fm-page fm-page--country">
                <header class="fm-country-header">
                    <div class="fm-country-header-main">
                        <div>
                            <div class="fm-eyebrow">Represented country</div>
                            <h2 class="fm-country-header-title">${htmlEscape(name)}</h2>
                        </div>
                    </div>
                    <button class="back-to-dashboard fm-country-header-back" data-nav-back="dashboard">Back</button>
                </header>

                <section class="fm-panel">
                    <div class="fm-panel-head"><div>
                        <h3>Senior ranking</h3>
                        <p class="fm-subtle">${htmlEscape(positionLine)}</p>
                    </div></div>
                    <p class="fm-subtle">${played} of ${rows.length} countries have played an international
                        match and hold a rating that means something.</p>
                </section>

                ${sideCard('Senior national team', seniorGroup, 'World Cup Qualifiers')}
                ${sideCard('U-21 national team', u21Group, 'U-21 World Cup Qualifiers')}

                <div class="fm-callout">
                    No club pyramid, so there is nothing to manage here. A country is given its own
                    five-tier pyramid from Admin &rarr; Activate a country.
                </div>
            </div>`;

        mainContent.querySelectorAll('.js-country').forEach(button => {
            button.addEventListener('click', () => {
                loadCountryPage({ tab: 'general', simulatedCountry: button.dataset.countryIso });
            });
        });
    }

    /**
     * One group's table with this country marked.
     *
     * <p>The whole group, not just this country: "who are we drawn with" is the question the page is
     * answering, and a table of one row answers nothing.
     *
     * <p>The {@code is-highlighted} class this emits had no definition anywhere in the stylesheet, so
     * the country being viewed was marked with nothing at all. Restored with the rule (2026-10-09).
     */
    function groupStandingTable(group, countryIso) {
        const table = Array.isArray(group.table) ? group.table : [];
        if (!table.length) return '<p class="fm-empty">No standings yet.</p>';
        return `<div class="fm-squad-wrap"><table class="fm-squad fm-league-table">
                <thead><tr><th>#</th><th>Team</th><th>P</th><th>GD</th><th>Pts</th></tr></thead>
                <tbody>${table.map(row => `<tr${String(row.countryIso || '').toUpperCase() === countryIso ? ' class="is-highlighted"' : ''}>
                    <td>${htmlEscape(row.position)}</td>
                    <td>${htmlEscape(row.teamName || 'Unknown team')}</td>
                    <td>${htmlEscape(row.played)}</td><td>${htmlEscape(row.goalDifference)}</td>
                    <td><strong>${htmlEscape(row.points)}</strong></td>
                </tr>`).join('')}</tbody></table></div>`;
    }

    function ordinal(position) {
        const n = Number(position);
        if (!Number.isFinite(n)) return '';
        const suffix = n % 100 >= 11 && n % 100 <= 13 ? 'th'
            : ({ 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th');
        return suffix;
    }

    function formatNumber(value) {
        const n = Number(value);
        if (!Number.isFinite(n)) return '0';
        return Number.isInteger(n) ? String(n) : n.toFixed(1);
    }

    // ------------------------------------------------------------------ page

    async function loadCountryPage(options) {
        const { tab = 'general', level: forcedLevel, simulatedCountry = '' } = options || {};
        const mainContent = document.getElementById('main-content');
        // Resolved by the host, which prefers an explicit choice from the World page over the manager's
        // own country. It used to ask for the manager's own country directly, so every country in the
        // world showed the manager's own side.
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }

        const countryIso = String(countryIsoCode).toUpperCase();
        if (simulatedCountry && String(simulatedCountry).toUpperCase() === countryIso) {
            await renderRepresentedCountry(mainContent, countryIso);
            return;
        }

        try {
            // The club ranking is fetched ONLY when that tab is asked for. Everything above is fetched
            // on every tab because every tab can be reached from any other, and the club list is the
            // heaviest of them - one row per club in the country. Tab switching is a full re-render, so
            // an unconditional tenth read here would be paid on the calendar and both squad tabs too.
            const clubRanking = tab === 'clubs'
                ? readJson(`/countries/${encodeURIComponent(countryIso)}/clubs/ranking?limit=100`)
                : Promise.resolve(null);

            // **A national warm-up is optional, and the panel says so.** The endpoints have existed since
            // 2026-10-06 with no frontend caller at all, so a country that wanted to arrange one had no
            // way to and the slot was invisible. Read only on the two national-team tabs: they are the
            // only place it means anything, and every tab can be reached from any other.
            const wantsWarmUp = tab === 'senior' || tab === 'u21';

            const [countries, leaguesResponse, calendarResponse, seasonResponse, seniorNt, u21Nt, cup, playoffs,
                qualifying] =
                await Promise.all([
                    readJson('/countries'),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/leagues`),
                    readJson('/calendar/week'),
                    readJson('/calendar/season'),
                    readNationalTeam(countryIso, 'senior'),
                    readNationalTeam(countryIso, 'u21'),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/cup`),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/playoffs`),
                    readJson(`/countries/${encodeURIComponent(countryIso)}/qualifying`),
                    clubRanking
                ]);

            // Only now, with the side's own id known: the warm-up view is per national team.
            const warmUpSideId = tab === 'u21' ? (u21Nt?.teamId ?? null) : (seniorNt?.teamId ?? null);
            const warmUp = wantsWarmUp && warmUpSideId
                ? await readJson(`/api/national/friendly-requests/${warmUpSideId}`)
                : null;
            const warmUpSlot = wantsWarmUp && warmUpSideId
                ? await readJson('/api/national/friendly-requests/slot')
                : null;
            const warmUpOpponents = wantsWarmUp && warmUpSideId
                ? await readJson(`/api/national/friendly-requests/opponents?level=${encodeURIComponent(tab)}`)
                : null;

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
            // 'clubs' is open to every viewer. Only the senior and U-21 squad tabs collapse to General
            // for a non-selector, because they are the selector's own working surface.
            const resolvedTab = tab === 'calendar' ? 'calendar'
                : (tab === 'clubs' ? 'clubs'
                    : (tab === 'qualifying' ? 'qualifying'
                        : (tab === 'general' ? 'general' : (canManage ? level : 'general'))));

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
                    cup: cup || { exists: false }, playoffs: playoffs || { note: 'Unavailable' }, qualifying
                });
            } else if (resolvedTab === 'calendar') {
                body = buildCalendarTab(schedule);
            } else if (resolvedTab === 'clubs') {
                body = buildClubsTab(clubRanking);
            } else if (resolvedTab === 'qualifying') {
                body = buildQualifyingTab(qualifying);
            } else {
                body = buildSelectorTab(activeNt, resolvedTab, wantsWarmUp
                    ? { teamId: warmUpSideId, view: warmUp, slot: warmUpSlot, opponents: warmUpOpponents }
                    : null);
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
        wireNationalWarmUp(root, activeTab);
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
        root.querySelectorAll('[data-national-competition-level]').forEach(button => {
            button.addEventListener('click', () => loadPage('nationalTournament', {
                level: button.dataset.nationalCompetitionLevel,
                stage: button.dataset.nationalCompetitionStage
            }));
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
        // A cup tie opens the SHARED match view, like a league fixture, not a bespoke sheet that could
        // only ever show two squads (owner, 2026-10-07, P0-PREV-1). `fixture: true` is explicit and
        // never inferred: fixture ids and match ids are both small integers over separate tables.
        mainContent.querySelectorAll('[data-cup-fixture]').forEach(button => {
            button.addEventListener('click', () => {
                const fixtureId = Number(button.dataset.cupFixture);
                if (!fixtureId || typeof loadMatch !== 'function') {
                    return;
                }
                // A played tie opens the MATCH, because lineups, stats, goals and the report are all
                // keyed by match id. An unplayed one opens the fixture.
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

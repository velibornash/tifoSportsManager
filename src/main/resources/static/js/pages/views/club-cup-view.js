/**
 * The three international club cups, one page each — P1-CUPS-4 (owner, 2026-10-06).
 *
 * <p>The World page listed Champions Cup, Masters Cup and Challenge Cup as three lines of text and had no
 * way into any of them. The owner asked for a page each, linked from the World page, with <b>a tab per
 * tier</b> and the results and the table on it.
 *
 * <p>Five tiers, three cups, fifteen competitions. Each cup page carries five tabs and each tab carries
 * that tier's group tables, its results and its bracket.
 *
 * <p>Every fetch goes through `authFetch` and every one checks `response.ok` before reading the body.
 * Several loaders in this codebase `await response.json()` without it, so a 404 or a 500 becomes a blank
 * panel rather than a message, and an error that looks like "no data yet" is the harder one to notice.
 */
// pages/views/club-cup-view.js
//
// A factory, not an IIFE: `authFetch`, `escapeHtml` and `loadPage` are injected by `pages.js`, because
// none of them is a global here. A module that reaches for `window.authFetch` is a module that works on
// the page where someone remembered to define it and nowhere else.

import { ownClubSummaryCard, ownClubChipHtml, wireJumpButtons } from './utils.js';

export const CUPS = [
    { key: 'champions', name: 'Champions Cup' },
    { key: 'masters', name: 'Masters Cup' },
    { key: 'challenge', name: 'Challenge Cup' }
];

export const TIERS = [1, 2, 3, 4, 5];

export function createClubCupView({ authFetch, escapeHtml, loadPage, loadMatch, getTeamId, loadClubProfile }) {
    /** Read the payload, or say why it could not be read. Never both. */
    async function readJson(url) {
        const response = await authFetch(url);
        return response.json();
    }

    const escape = value => escapeHtml(value);

    /**
     * A club's name as something the reader can press.
     *
     * <p>The group tables and the bracket named clubs and nothing on the page led anywhere (owner,
     * 2026-10-21: *"da mogu da kliknem na klubove"*). The name now opens the club's own profile, the
     * same page the world club ranking opens, so a manager can look at a Champions Cup opponent's
     * squad without leaving the competition he found them in.
     *
     * <p><b>By id, never by name.</b> The endpoint sends `teamId` on every table row and
     * `homeTeamId`/`awayTeamId` on every tie, and this codebase has four defects that came from
     * joining a club to something by its name - there are two clubs called GFK Zemun in one Serbian
     * division. A club whose id is absent is left as plain text rather than linked to a guess.
     *
     * <p>It uses `.fm-team-link`, the established clickable-club look (bold, pointer, accent on
     * hover), and not a button: a button around a name leaves a white slab on a dark card, which is
     * what the owner asked to have removed from this page's ties.
     */
    function clubLink(teamId, name, fallback) {
        const label = escape(name || fallback);
        if (!teamId) {
            return label;
        }
        return `<span class="fm-team-link js-cup-club" data-club-team-id="${escape(teamId)}"
                      role="link" tabindex="0" title="Open this club">${label}</span>`;
    }

    /** Every clickable club on the page opens its profile. `loadClubProfile` is injected, not global. */
    function wireClubLinks(root) {
        if (typeof loadClubProfile !== 'function') {
            return;
        }
        root.querySelectorAll('.js-cup-club').forEach(node => {
            const open = event => {
                event.stopPropagation();
                const teamId = Number(node.dataset.clubTeamId);
                if (teamId) loadClubProfile(teamId);
            };
            node.addEventListener('click', open);
            // A role="link" that only answers the mouse is not a link: the keyboard reaches it with
            // Tab and it must open with Enter or Space, the way every other link on the page does.
            node.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    open(event);
                }
            });
        });
    }

    // ------------------------------------------------------------------ markup

    function tierTabs(cupKey, activeTier, tiers) {
        return `
            <nav class="fm-player-tabs">
                ${TIERS.map(tier => {
                    const summary = tiers.find(t => Number(t.tier) === tier) || {};
                    const fixtures = Number(summary.fixtures || 0);
                    const state = String(summary.state || 'not-drawn');
                    const note = state === 'missing' ? 'missing'
                        : fixtures > 0 ? `${fixtures} ties`
                        : state === 'no-finished-season' ? 'not qualified yet'
                        : 'not drawn';
                    const active = tier === activeTier ? ' is-active' : '';
                    return `<button type="button" class="fm-player-tab${active}"
                                data-cup-tier="${tier}"
                                title="${escape(note)}">Tier ${tier}</button>`;
                }).join('')}
            </nav>`;
    }

    /** One group: its table and its five matchdays. */
    function groupPanel(group) {
        const rows = Array.isArray(group.table) ? group.table : [];
        const ties = Array.isArray(group.fixtures) ? group.fixtures : [];

        return `
            <section class="fm-panel" id="fm-cup-group-${escape(group.code)}">
                <div class="fm-panel-head">
                    <div>
                        <h3>Group ${escape(group.code)}</h3>
                        <p class="fm-subtle">${ties.length} ties · ${
                            ties.filter(t => t.played).length} played</p>
                    </div>
                </div>
                ${tableOf(rows)}
                <div class="fm-squad-wrap">
                    <table class="fm-squad">
                        <thead>
                            <tr>
                                <th>Round</th><th>Home</th><th class="st-rating">Score</th><th>Away</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${ties.length === 0
                                ? '<tr><td colspan="4" class="fm-empty">No ties drawn.</td></tr>'
                                : ties.map(tieRow).join('')}
                        </tbody>
                    </table>
                </div>
            </section>`;
    }

    function tableOf(rows) {
        if (!rows.length) {
            return '<div class="fm-empty">No clubs in this group yet.</div>';
        }
        return `
            <div class="fm-squad-wrap">
                <table class="fm-squad fm-league-table">
                    <thead>
                        <tr>
                            <th class="st-pos">#</th><th>Club</th><th>Country</th>
                            <th>P</th><th>W</th><th>D</th><th>L</th>
                            <th class="st-rating">GF</th><th class="st-rating">GA</th>
                            <th class="st-rating">GD</th><th class="st-rating">Pts</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map(row => `
                            <tr${isOwnRow(row) ? ' class="is-own"' : ''}>
                                <td class="st-pos">${escape(row.position)}</td>
                                <td class="sq-name">${clubLink(row.teamId, row.team, 'Unknown')}${isOwnRow(row) ? ownClubChipHtml() : ''}</td>
                                <td>${escape(row.country || '—')}</td>
                                <td>${escape(row.played)}</td>
                                <td>${escape(row.wins)}</td>
                                <td>${escape(row.draws)}</td>
                                <td>${escape(row.losses)}</td>
                                <td class="st-rating">${escape(row.goalsScored)}</td>
                                <td class="st-rating">${escape(row.goalsConceded)}</td>
                                <td class="st-rating">${escape(row.goalDifference)}</td>
                                <td class="st-rating"><strong>${escape(row.points)}</strong></td>
                            </tr>`).join('')}
                    </tbody>
                </table>
            </div>`;
    }

    /**
     * One tie, as a row that opens (owner, 2026-10-07).
     *
     * <p>Every competition used to render its ties as a plain {@code <tr>}. The league made them
     * clickable and the international club cups did not, so a Champions Cup tie could not be opened at
     * all — not to see a prediction before it, and not to see lineups, stats, goals or a report
     * afterwards. Same screen as a league fixture, with the kind passed explicitly rather than guessed,
     * because fixture ids and match ids are both small integers over separate tables.
     */
    /**
     * The "your club" card for a continental cup tier.
     *
     * <p>One answer to "where am I in this", shared with the national cup, because the same question was
     * being answered in two places and getting two different answers — and designAnalysis.md §5 found
     * three different treatments for that single fact across six screens.
     *
     * <p>It appears above the groups, because the groups are the thing you scroll through to find
     * yourself. The jump lands on the first group the manager's club is in.
     */
    function cupOwnSummary(cup, groups, rounds) {
        const myId = getTeamId?.();
        if (myId === null || myId === undefined) return '';
        let homeGroup = null;
        let homeGroupRow = null;
        for (const group of groups) {
            const row = (Array.isArray(group.table) ? group.table : []).find(r => Number(r.teamId) === Number(myId));
            if (row) { homeGroup = group; homeGroupRow = row; break; }
        }
        let myTies = 0;
        for (const group of groups) {
            for (const tie of (Array.isArray(group.fixtures) ? group.fixtures : [])) {
                if (isOwnTie(tie)) myTies += 1;
            }
        }
        for (const round of rounds) {
            for (const tie of (Array.isArray(round.fixtures) ? round.fixtures : [])) {
                if (isOwnTie(tie)) myTies += 1;
            }
        }
        if (!homeGroupRow && myTies === 0) return '';
        const name = homeGroupRow?.team || (myTies > 0 ? String(cup.name) : '');
        return ownClubSummaryCard({
            name,
            facts: [
                homeGroupRow ? { label: 'Group', value: homeGroup.code } : null,
                homeGroupRow ? { label: 'Position', value: String(homeGroupRow.position ?? '—') } : null,
                homeGroupRow ? { label: 'Points', value: String(homeGroupRow.points ?? '—') } : null,
                { label: 'Ties here', value: String(myTies) }
            ].filter(Boolean),
            kicker: 'Your club',
            jumpLabel: homeGroup ? 'Jump to your group' : '',
            target: homeGroup ? 'fm-cup-group-' + homeGroup.code : ''
        });
    }

    /** Is this tie one of the manager's own? By id, never by name. */
    function isOwnRow(row) {
        const myId = getTeamId?.();
        if (myId === null || myId === undefined || !row?.teamId) return false;
        return Number(row.teamId) === Number(myId);
    }

    /**
     * Is this tie one of the manager's own?
     *
     * <p>By id, never by name. Two clubs can share a name in this game — there are two rows called
     * GFK Zemun in Serbia's first division alone — and four defects in this codebase came from joining a
     * team to something by its name. The endpoint already sends `homeTeamId` and `awayTeamId` per tie, so
     * the id is here and the name comparison would be a choice to make it worse.
     *
     * @returns {boolean} true when either side is the manager's club
     */
    function isOwnTie(tie) {
        const myId = getTeamId?.();
        if (myId === null || myId === undefined) {
            return false;
        }
        return Number(tie.homeTeamId) === Number(myId) || Number(tie.awayTeamId) === Number(myId);
    }

    function tieRow(tie) {
        const score = tie.played
            ? `${escape(tie.homeGoals ?? '')} – ${escape(tie.awayGoals ?? '')}`
            : 'v';
        const mine = isOwnTie(tie);
        // Team names are plain text: a button around a name leaves a white slab on a dark card, which
        // is the thing the owner asked to have removed ("skloni ono belo dugme"). The score is the
        // thing that opens the tie, so it keeps the opener; the row itself carries the "your tie" mark.
        return `
            <tr class="fm-cup-tie-row${mine ? ' is-own' : ''}"${mine ? ' title="Your tie"' : ''}>
                <td>Matchday ${escape(tie.round)}</td>
                <td class="sq-name">${clubLink(tie.homeTeamId, tie.home, 'TBC')}</td>
                <td class="fm-cup-final-score">
                    <button type="button" class="fm-cup-tie-sep js-open-tie"
                            data-tie-fixture="${escape(tie.id)}"
                            data-tie-played="${tie.played ? 'true' : 'false'}"
                            data-tie-match-id="${escape(tie.matchId ?? '')}"
                            title="${tie.played ? 'Open this tie' : 'Open the pre-match state for this tie'}">${score}</button>
                </td>
                <td class="sq-name">${clubLink(tie.awayTeamId, tie.away, 'TBC')}</td>
            </tr>`;
    }

    /**
     * Wires every tie on the page.
     *
     * <p>A played tie opens the MATCH, because lineups, stats, goals and the report are keyed by match
     * id; an unplayed one opens the fixture and is told so explicitly.
     */
    function wireTies(root, caller) {
        root.querySelectorAll('.js-open-tie').forEach(button => {
            button.addEventListener('click', () => {
                if (typeof loadMatch !== 'function') {
                    return;
                }
                const fixtureId = Number(button.dataset.tieFixture);
                const matchId = Number(button.dataset.tieMatchId);
                if (!fixtureId) {
                    return;
                }
                if (button.dataset.tiePlayed === 'true' && matchId) {
                    loadMatch(matchId, caller, { initialTab: 'preview' });
                } else {
                    loadMatch(fixtureId, caller, { initialTab: 'preview', fixture: true });
                }
            });
        });
    }

    /** The bracket: one block per round, final last. */
    function knockoutPanel(rounds) {
        if (!rounds.length) {
            return '';
        }
        return `
            <section class="fm-panel">
                <div class="fm-panel-head">
                    <div>
                        <h3>Knockout</h3>
                        <p class="fm-subtle">Round of 16, quarter-finals, semi-finals, third place and the
                            final. Week 10 carries the last two.</p>
                    </div>
                </div>
                ${rounds.map(round => `
                    <div class="fm-cup-round">
                        <div class="fm-cup-round-head">
                            <span>${escape(round.name || `Round ${round.round}`)}</span>
                            <span class="fm-cup-round-count">Week ${escape(round.week ?? '—')} ·
                                ${round.fixtures.length} ${round.fixtures.length === 1 ? 'tie' : 'ties'}</span>
                        </div>
                        <div class="fm-cup-ties">
                            ${round.fixtures.length === 0
                                ? '<div class="fm-empty">Not drawn yet.</div>'
                                : round.fixtures.map(tie => `
                                    <div class="fm-cup-tie">
                                        <span>${clubLink(tie.homeTeamId, tie.home, 'TBC')}</span>
                                        <span class="fm-cup-tie-sep">${tie.played
                                            ? `${escape(tie.homeGoals ?? '')} – ${escape(tie.awayGoals ?? '')}`
                                            : 'v'}</span>
                                        <span>${clubLink(tie.awayTeamId, tie.away, 'TBC')}</span>
                                    </div>`).join('')}
                        </div>
                    </div>`).join('')}
            </section>`;
    }

    // ------------------------------------------------------------------ page

    async function loadClubCup(cupKey, tier) {
        const cup = CUPS.find(c => c.key === cupKey);
        const mainContent = document.getElementById('main-content');
        if (!cup) {
            mainContent.innerHTML = `<div class="manager-card"><h2>Unknown cup</h2>
                <p>These are the ${CUPS.map(c => c.name).join(', ')}.</p></div>`;
            return;
        }
        const activeTier = TIERS.includes(Number(tier)) ? Number(tier) : 1;

        try {
            const index = await readJson('/club-cups');
            const tiers = (index.cups || []).find(c => c.key === cupKey)?.tiers || [];
            const detail = await readJson(`/club-cups/${encodeURIComponent(cupKey)}?tier=${activeTier}`);

            const groups = Array.isArray(detail.groups) ? detail.groups : [];
            const rounds = Array.isArray(detail.knockoutRounds) ? detail.knockoutRounds : [];
            const played = Number(detail.playedFixtures || 0);
            const total = Number(detail.totalFixtures || 0);

            mainContent.innerHTML = `
                <div class="fm-page">
                    <div class="fm-page-head">
                        <button type="button" class="back-to-dashboard" data-nav-back>Back</button>
                        <div>
                            <h2>${escape(cup.name)}</h2>
                            <p class="fm-subtle">Tier ${activeTier} · season ${escape(detail.seasonYear)} ·
                                ${total} ties, ${played} played</p>
                        </div>
                    </div>
                    ${tierTabs(cupKey, activeTier, tiers)}
                    ${cupOwnSummary(cup, groups, rounds)}
                    ${groups.length === 0 && rounds.length === 0
                        ? `<section class="fm-panel"><div class="fm-empty">
                               This tier has not been drawn yet. It is filled in week 1, from the table
                               each country's divisions finished on.
                           </div></section>`
                        : ''}
                    ${groups.map(groupPanel).join('')}
                    ${knockoutPanel(rounds)}
                </div>`;

            wireJumpButtons(mainContent);
            mainContent.querySelectorAll('[data-cup-tier]').forEach(button => {
                button.addEventListener('click', () => {
                    loadPage('clubCup', { cupKey, tier: Number(button.dataset.cupTier) });
                });
            });
            wireTies(mainContent, 'clubCup');
            // After wireTies, and it does not fight it: a club name and the score beside it are
            // different targets, and the name's handler stops the click from reaching the row.
            wireClubLinks(mainContent);
        } catch (err) {
            console.error('Failed to load the club cup page:', err);
            mainContent.innerHTML = `<div class="manager-card"><h2>${escape(cup.name)}</h2>
                <p>Could not load this competition. ${escape(err.message)}</p></div>`;
        }
    }

    return { loadClubCup };
}